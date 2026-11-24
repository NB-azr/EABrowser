# Office 写回回归：编译纯 Java 的 OfficeExtract/OfficeWriteback 并跑真实样例
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$javaHome = "C:\Users\Administrator\.jdks\jbr-21.0.11"
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path $javac)) { throw "找不到 javac: $javac" }

$out = Join-Path $env:TEMP "ea-office-wb"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$src = Join-Path $root "android\BrowserApp\app\src\main\java\com\ea\browser\tools"
if (-not (Test-Path $src)) { $src = Join-Path $root "src\android\app\src\main\java\com\ea\browser\tools" }
& $javac -encoding UTF-8 -d $out (Join-Path $src "OfficeExtract.java") (Join-Path $src "OfficeWriteback.java") (Join-Path $PSScriptRoot "OfficeWritebackCheck.java")
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

& $java "-Dfile.encoding=UTF-8" -cp $out OfficeWritebackCheck (Join-Path $PSScriptRoot "fixtures\office")
exit $LASTEXITCODE
