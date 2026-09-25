<#
  ビルドとユニットテスト。

  PATH の java は 1.8 なので、必ず Android Studio 同梱の JBR(OpenJDK 21)を
  JAVA_HOME にしてから gradlew を呼ぶ。
#>
param([string[]]$Tasks = @("testDebugUnitTest", "assembleDebug"))

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location (Join-Path $PSScriptRoot "..")
& .\gradlew.bat @Tasks
if ($LASTEXITCODE -ne 0) { throw "gradle failed with exit code $LASTEXITCODE" }
