param([switch]$CollectOnly, [string]$DeviceSerial = '')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$branch = 'diagnostics/build-' + $stamp
$relativeReport = 'build-diagnostics/snapshot-' + $stamp
$reportDir = Join-Path $PSScriptRoot $relativeReport
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$manifest = New-Object System.Collections.Generic.List[string]
$manifest.Add('Captured: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$manifest.Add('Project: ' + $PSScriptRoot)
$manifest.Add('Diagnostic branch: ' + $branch)
$manifest.Add('Files are copied in full, without tail/truncation.')

function Invoke-CheckedGit([string[]]$GitArguments) {
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& git @GitArguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    if ($code -ne 0) { throw ('git failed: ' + ($output -join "`n")) }
    return $output
}
function Copy-Evidence([string]$Source, [string]$Name) {
    if (Test-Path -LiteralPath $Source -PathType Leaf) {
        $destination = Join-Path $reportDir $Name
        Copy-Item -LiteralPath $Source -Destination $destination
        $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
        $manifest.Add("$Name | SHA256=$hash | source=$Source")
    } else { $manifest.Add('Not present: ' + $Source) }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git is not available in this terminal.' }
$null = Invoke-CheckedGit @('rev-parse', '--show-toplevel')
# Preserve the old report BEFORE a new execution can overwrite it.
Copy-Evidence (Join-Path $PSScriptRoot 'build-diagnostics\last-build.log') 'previous-last-build.log'
$gradleHomes = @($env:GRADLE_USER_HOME, (Join-Path $env:USERPROFILE '.gradle'),
    (Join-Path $env:LOCALAPPDATA 'EstatePortalNative\gradle')) | Where-Object { $_ } | Select-Object -Unique
$logNumber = 0
foreach ($gradleHome in $gradleHomes) {
    $daemonDir = Join-Path $gradleHome 'daemon'
    try {
        if (Test-Path -LiteralPath $daemonDir) {
            $logs = @(Get-ChildItem -LiteralPath $daemonDir -Filter 'daemon-*.out.log' -Recurse -File |
                Sort-Object LastWriteTime -Descending | Select-Object -First 3)
            foreach ($log in $logs) {
                Copy-Evidence $log.FullName ("previous-daemon-$logNumber.log")
                $logNumber++
            }
        } else { $manifest.Add('Daemon directory unavailable: ' + $daemonDir) }
    } catch { $manifest.Add('Cannot read daemon directory: ' + $daemonDir + ' | ' + $_.Exception.Message) }
}
foreach ($name in @('build-error.txt', 'build.log', 'gradle-build.log')) {
    Copy-Evidence (Join-Path $PSScriptRoot $name) ('previous-' + $name)
}
foreach ($name in @('local.properties', 'gradle.properties', 'app\build.gradle.kts',
    'gradle\wrapper\gradle-wrapper.properties', 'run-map.ps1')) {
    Copy-Evidence (Join-Path $PSScriptRoot $name) ($name.Replace('\', '-'))
}
$environment = New-Object System.Collections.Generic.List[string]
foreach ($name in @('JAVA_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS',
    'GRADLE_OPTS', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'LOCALAPPDATA', 'USERPROFILE')) {
    $environment.Add($name + '=' + [Environment]::GetEnvironmentVariable($name, 'Process'))
}
$environment.Add('Git HEAD: ' + ((Invoke-CheckedGit @('rev-parse', 'HEAD')) -join ''))
$environment.Add('Git status:')
$environment.Add(((Invoke-CheckedGit @('status', '--short')) -join "`r`n"))
[IO.File]::WriteAllLines((Join-Path $reportDir 'environment.txt'), $environment, $utf8)

# Capture a complete fresh build now, not only if another failure is reported.
# The old logs above remain intact even if the new environment fixes the build.
if (-not $CollectOnly) {
    $hostExe = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path $hostExe)) { $hostExe = Join-Path $PSHOME 'pwsh' }
    $runner = Join-Path $PSScriptRoot 'run-map.ps1'
    $runnerArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runner)
    if ($DeviceSerial) { $runnerArgs += @('-DeviceSerial', $DeviceSerial) }
    Write-Host 'Capturing complete build output now...'
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $hostExe @runnerArgs 2>&1 | ForEach-Object {
            $line = $_.ToString()
            Write-Host $line
            [IO.File]::AppendAllText((Join-Path $reportDir 'current-console.log'), $line + "`r`n", $utf8)
        }
        $buildExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    $manifest.Add('Fresh build/install/launch exit code: ' + $buildExit)
    Copy-Evidence (Join-Path $PSScriptRoot 'build-diagnostics\last-build.log') 'current-last-build.log'
}
[IO.File]::WriteAllLines((Join-Path $reportDir 'MANIFEST.txt'), $manifest, $utf8)

# An independent temporary Git index and parentless diagnostic commit keep the
# current branch, working files, and the user's existing staged changes intact.
$oldIndex = [Environment]::GetEnvironmentVariable('GIT_INDEX_FILE', 'Process')
$temporaryIndex = Join-Path ([IO.Path]::GetTempPath()) ('estate-diagnostics-' + [guid]::NewGuid().ToString('N') + '.index')
try {
    $env:GIT_INDEX_FILE = $temporaryIndex
    $null = Invoke-CheckedGit @('read-tree', '--empty')
    $null = Invoke-CheckedGit @('add', '-f', '--', $relativeReport)
    $tree = ((Invoke-CheckedGit @('write-tree')) -join '').Trim()
    $commit = ((Invoke-CheckedGit @('-c', 'user.name=EstatePortalDiagnostics', '-c', 'user.email=diagnostics@localhost',
        'commit-tree', $tree, '-m', ('Android build diagnostics ' + $stamp))) -join '').Trim()
    $null = Invoke-CheckedGit @('push', 'origin', ($commit + ':refs/heads/' + $branch))
    Write-Host "DIAGNOSTICS UPLOADED: $branch" -ForegroundColor Green
    Write-Host "REPORT DIRECTORY: $relativeReport"
    Write-Host "REPORT COMMIT: $commit"
} finally {
    [Environment]::SetEnvironmentVariable('GIT_INDEX_FILE', $oldIndex, 'Process')
    Remove-Item -LiteralPath $temporaryIndex -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath ($temporaryIndex + '.lock') -Force -ErrorAction SilentlyContinue
}
