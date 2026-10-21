$ErrorActionPreference = 'Stop'
$projectPath = $PSScriptRoot
$configPath = Join-Path $projectPath 'local.properties'
$appKey = (Read-Host 'Kakao NATIVE app key').Trim().Trim('"')
if ($appKey -notmatch '^[A-Za-z0-9]+$') { throw 'Enter the native app key, not a REST or JavaScript key.' }
$config = if (Test-Path $configPath) { [IO.File]::ReadAllText($configPath) } else { '' }
$config = [regex]::Replace($config, '(?m)^KAKAO_NATIVE_APP_KEY=.*\r?\n?', '')
$config = $config.TrimEnd() + "`r`nKAKAO_NATIVE_APP_KEY=$appKey`r`n"
[IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))
Write-Host 'Saved. Android Studio: Sync Project with Gradle Files, then Run.'
Write-Host 'Kakao Developers Android package: kr.co.estate.portal'
$keytoolPath = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
$debugStore = Join-Path $env:USERPROFILE '.android\debug.keystore'
if ((Test-Path $keytoolPath) -and (Test-Path $debugStore)) {
    $certPath = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
    try {
        & $keytoolPath -exportcert -alias androiddebugkey -keystore $debugStore -storepass android -file $certPath
        if ($LASTEXITCODE -eq 0) {
            $sha = [Security.Cryptography.SHA1]::Create()
            try {
                $keyHash = [Convert]::ToBase64String($sha.ComputeHash([IO.File]::ReadAllBytes($certPath)))
                Write-Host "Android key hash: $keyHash"
                $keyHash | Set-Clipboard
                Write-Host 'Key hash copied to clipboard. Register it in Kakao Developers.'
            } finally { $sha.Dispose() }
        }
    } finally { Remove-Item $certPath -ErrorAction SilentlyContinue }
}
