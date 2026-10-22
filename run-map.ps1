param([string]$DeviceSerial = '')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$studioJava = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path (Join-Path $studioJava 'bin\java.exe')) { $env:JAVA_HOME = $studioJava }
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$configPath = Join-Path $PSScriptRoot 'local.properties'
if (Test-Path $configPath) {
    $sdkLine = Get-Content $configPath | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) {
        $sdkPath = ($sdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
        $candidate = Join-Path $sdkPath 'platform-tools\adb.exe'
        if (Test-Path $candidate) { $adb = $candidate }
    }
}
if (-not (Test-Path $adb)) { throw 'Android SDK adb.exe not found.' }
& .\gradlew.bat :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed. See Gradle output above.' }
$devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $DeviceSerial) {
    if ($devices.Count -ne 1) {
        & $adb devices
        throw 'Connect one wireless device, or run: .\run-map.ps1 -DeviceSerial SERIAL'
    }
    $DeviceSerial = $devices[0]
}
if ($DeviceSerial -notin $devices) { throw 'Selected wireless device is not connected.' }
& $adb -s $DeviceSerial install -r -t (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& $adb -s $DeviceSerial shell am force-stop kr.co.estate.portal
& $adb -s $DeviceSerial shell am start -W -n kr.co.estate.portal/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Host 'Map version 10-20-02 installed and launched.'
