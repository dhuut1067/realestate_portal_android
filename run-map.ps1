param([string]$DeviceSerial = '')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$reportDir = Join-Path $PSScriptRoot 'build-diagnostics'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$logPath = Join-Path $reportDir 'last-build.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($logPath, "EstatePortalNative build report`r`n", $utf8)

function Write-Report([string]$Message) {
    Write-Host $Message
    [IO.File]::AppendAllText($logPath, $Message + "`r`n", $utf8)
}
function Invoke-LoggedNative([string]$Executable, [string[]]$Arguments) {
    # Windows PowerShell 5.1 treats redirected native stderr as ErrorRecord.
    # Capture it without aborting before Java/Gradle reports its real failure.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments 2>&1 | ForEach-Object { Write-Report $_.ToString() }
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    return $nativeExit
}
function Show-BuildFailure {
    $lines = @(Get-Content -LiteralPath $logPath -Encoding UTF8)
    Write-Host "`n===== GRADLE ROOT ERROR =====" -ForegroundColor Red
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\* What went wrong:') { $start = $i; break }
    }
    if ($start -ge 0) {
        $last = [Math]::Min($start + 35, $lines.Count - 1)
        for ($i = $start; $i -le $last; $i++) {
            if ($lines[$i] -match '^\* Try:') { break }
            Write-Host $lines[$i] -ForegroundColor Red
        }
    } else {
        $lines | Select-Object -Last 35 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    }
    $compilerErrors = @($lines | Where-Object { $_ -match '^e: | error: ' })
    if ($compilerErrors.Count -gt 0) {
        Write-Host '===== COMPILER ERRORS =====' -ForegroundColor Red
        $compilerErrors | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    }
    Write-Host "FULL REPORT: $logPath" -ForegroundColor Yellow
}

# Ignore inherited JVM/Gradle options in THIS process only. They can point to a
# disconnected drive even when JAVA_HOME / GRADLE_USER_HOME look correct.
$processSettings = @('JAVA_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'GRADLE_OPTS', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')
$oldSettings = @{}
foreach ($name in $processSettings) { $oldSettings[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$exitCode = 0
try {
    Write-Report ('Started: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    Write-Report ('Project: ' + $PSScriptRoot)
    Write-Report ('Inherited Gradle home: ' + $oldSettings['GRADLE_USER_HOME'])
    foreach ($name in @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'GRADLE_OPTS')) {
        if ($oldSettings[$name]) { Write-Report ("Ignoring inherited process option: " + $name) }
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }

    $javaCandidates = @((Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'), $oldSettings['JAVA_HOME'])
    $javaHome = $null
    foreach ($candidate in $javaCandidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe')) -and
            (Test-Path (Join-Path $candidate 'bin\javac.exe')) -and
            (Test-Path (Join-Path $candidate 'bin\jlink.exe'))) { $javaHome = $candidate; break }
    }
    if (-not $javaHome) { throw 'A complete Android Studio JBR/JDK (java, javac, jlink) was not found.' }
    $javaExe = Join-Path $javaHome 'bin\java.exe'
    $env:JAVA_HOME = $javaHome
    Write-Report ('Java: ' + $javaHome)
    $javaExit = Invoke-LoggedNative $javaExe @('-version')
    if ($javaExit -ne 0) { throw 'Java could not start; see the report above.' }

    # Use one dedicated, writable local cache. Do not inherit Z: or global
    # ~/.gradle/gradle.properties. Pin BOTH wrapper and daemon to these paths.
    $gradleHome = Join-Path $env:LOCALAPPDATA 'EstatePortalNative\gradle'
    New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null
    $probe = Join-Path $gradleHome ([IO.Path]::GetRandomFileName())
    [IO.File]::WriteAllText($probe, 'write-test', $utf8)
    [IO.File]::Delete($probe)
    $env:GRADLE_USER_HOME = $gradleHome
    Write-Report ('Gradle cache: ' + $gradleHome)

    $configPath = Join-Path $PSScriptRoot 'local.properties'
    if (-not (Test-Path $configPath)) { throw 'local.properties is missing. Pull the current GitHub version first.' }
    $config = [IO.File]::ReadAllText($configPath)
    if ($config -notmatch '(?m)^KAKAO_NATIVE_APP_KEY=\s*\S+') { throw 'KAKAO_NATIVE_APP_KEY is missing from local.properties.' }
    $sdkMatch = [regex]::Match($config, '(?m)^sdk\.dir=(.*)$')
    $configuredSdk = if ($sdkMatch.Success) { $sdkMatch.Groups[1].Value.Trim().Replace('\:', ':').Replace('\\', '\') } else { '' }
    $sdkCandidates = @($configuredSdk, $oldSettings['ANDROID_HOME'], $oldSettings['ANDROID_SDK_ROOT'], (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    $sdkPath = $null
    foreach ($candidate in $sdkCandidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'platform-tools\adb.exe')) -and
            (Test-Path (Join-Path $candidate 'platforms\android-36\android.jar'))) { $sdkPath = $candidate; break }
    }
    if (-not $sdkPath) { throw 'Android SDK with platform-tools and Android API 36 was not found. Check Android Studio SDK Manager.' }
    if ($configuredSdk -ne $sdkPath) {
        $sdkProperty = $sdkPath.Replace('\', '/').Replace(':', '\:')
        $config = [regex]::Replace($config, '(?m)^sdk\.dir=.*\r?\n?', '')
        [IO.File]::WriteAllText($configPath, "sdk.dir=$sdkProperty`r`n" + $config, $utf8)
        Write-Report 'Corrected sdk.dir to the installed local SDK; the native app key was preserved.'
    }
    $env:ANDROID_HOME = $sdkPath
    $env:ANDROID_SDK_ROOT = $sdkPath
    $adb = Join-Path $sdkPath 'platform-tools\adb.exe'
    Write-Report ('Android SDK: ' + $sdkPath)
    $wrapperJar = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'
    if (-not (Test-Path $wrapperJar)) { throw 'Gradle wrapper JAR is missing from this project.' }

    # Invoke the wrapper directly instead of inheriting an unknown batch/JVM setup.
    $buildArgs = @("-Dgradle.user.home=$gradleHome", "-Dorg.gradle.java.home=$javaHome", '-classpath', $wrapperJar,
        'org.gradle.wrapper.GradleWrapperMain', '--gradle-user-home', $gradleHome,
        "-Dorg.gradle.java.home=$javaHome", ':app:assembleDebug', '--no-daemon', '--stacktrace', '--console=plain')
    Write-Report 'BUILD START: assembleDebug --no-daemon --stacktrace'
    $buildExit = Invoke-LoggedNative $javaExe $buildArgs
    if ($buildExit -ne 0) {
        Show-BuildFailure
        $exitCode = $buildExit
    } else {
        $apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (-not (Test-Path $apk)) { throw 'Gradle completed but app-debug.apk is missing.' }
        $deviceOutput = @(& $adb devices)
        if ($LASTEXITCODE -ne 0) { throw 'ADB could not list connected devices.' }
        $devices = @($deviceOutput | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
        if (-not $DeviceSerial) {
            if ($devices.Count -ne 1) {
                $deviceOutput | ForEach-Object { Write-Report $_ }
                throw 'Keep one wireless debugging device connected, or use -DeviceSerial SERIAL. The APK build has succeeded.'
            }
            $DeviceSerial = $devices[0]
        }
        if ($DeviceSerial -notin $devices) { throw 'Selected wireless device is not connected. The APK build has succeeded.' }
        Write-Report ('Install device: ' + $DeviceSerial)
        $installExit = Invoke-LoggedNative $adb @('-s', $DeviceSerial, 'install', '-r', '-t', $apk)
        if ($installExit -ne 0) { throw 'APK installation failed. The APK build has succeeded; see the ADB error above.' }
        $null = Invoke-LoggedNative $adb @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', 'kr.co.estate.portal')
        $launchExit = Invoke-LoggedNative $adb @('-s', $DeviceSerial, 'shell', 'am', 'start', '-W', '-n', 'kr.co.estate.portal/.MainActivity')
        if ($launchExit -ne 0) { throw 'App launch failed; see the ADB error above.' }
        Write-Report 'Map version 10-20-02 installed and launched.'
    }
} catch {
    Write-Report ('STOPPED: ' + $_.Exception.Message)
    Write-Report ('FULL REPORT: ' + $logPath)
    $exitCode = 1
} finally {
    foreach ($name in $processSettings) { [Environment]::SetEnvironmentVariable($name, $oldSettings[$name], 'Process') }
}
exit $exitCode
