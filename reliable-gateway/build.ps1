param([ValidateSet('test', 'package', 'run', 'core-test')][string]$Action = 'test')
$ErrorActionPreference = 'Stop'
$toolRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../tools/java-build'))
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem -LiteralPath $toolRoot -Directory -ErrorAction SilentlyContinue | Where-Object Name -Like 'jdk*' | Select-Object -First 1
    if (-not $jdk) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
    $env:JAVA_HOME = $jdk.FullName
}
$maven = Join-Path $toolRoot 'apache-maven-3.9.9/bin/mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn -ErrorAction Stop).Source }
$jvmFlags = @('--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
    '--add-opens=java.base/java.io=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED')
if ($Action -eq 'core-test') {
    & $maven -f (Join-Path $PSScriptRoot '../pom.xml') '-P!default' '-Dtest=OrderBookNaiveImplExchangeTest,OrderBookDirectImplExchangeTest' test -B -ntp
    exit $LASTEXITCODE
}
if ($Action -ne 'run') {
    & $maven -f (Join-Path $PSScriptRoot '../pom.xml') '-P!default' '-Dmaven.test.skip=true' install -B -ntp
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $maven -f (Join-Path $PSScriptRoot 'pom.xml') $Action -B -ntp
    exit $LASTEXITCODE
}
$jar = Join-Path $PSScriptRoot 'target/reliable-trading-gateway-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run ./build.ps1 package first.' }
Push-Location $PSScriptRoot
try { & (Join-Path $env:JAVA_HOME 'bin/java.exe') @jvmFlags -jar $jar } finally { Pop-Location }
