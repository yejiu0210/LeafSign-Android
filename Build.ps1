param([string]$SdkPath=$env:ANDROID_HOME,[string]$JavaHome=$env:JAVA_HOME,[switch]$SkipTests)
$ErrorActionPreference='Stop'
if(!$JavaHome -or !(Test-Path -LiteralPath "$JavaHome\bin\java.exe")){throw '请安装 JDK 17 并设置 JAVA_HOME，或传入 -JavaHome。'}
if(!$SdkPath -or !(Test-Path -LiteralPath "$SdkPath\platforms\android-36\android.jar")){throw '请安装 Android SDK 36、Build Tools 35.0.0，设置 ANDROID_HOME，或传入 -SdkPath。'}
$env:JAVA_HOME=$JavaHome
$env:ANDROID_HOME=$SdkPath
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') -Value ('sdk.dir='+$SdkPath.Replace('\','/').Replace(':','\:')) -Encoding ascii -NoNewline
Push-Location $PSScriptRoot
try {
    $taskArguments=@('--no-daemon','--console=plain',':app:assembleRelease')
    if(!$SkipTests){$taskArguments+=@(':app:testDebugUnitTest',':app:lintRelease')}
    & .\gradlew.bat @taskArguments
    if($LASTEXITCODE -ne 0){throw "Gradle 构建失败，退出码 $LASTEXITCODE"}
    Write-Output (Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk')
}finally{Pop-Location}
