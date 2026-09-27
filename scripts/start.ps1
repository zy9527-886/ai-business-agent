param([int]$BackendPort = 9090, [int]$FrontendPort = 5173)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$applicationRoot = Join-Path $projectRoot 'ai_knowledge'
$backendRoot = Join-Path $applicationRoot 'aiAgent-back'
$frontendRoot = Join-Path $applicationRoot 'aiAgent-front'
$vite = Join-Path $frontendRoot 'node_modules/vite/bin/vite.js'
$runtime = Join-Path $projectRoot '.runtime'
if (!(Test-Path -LiteralPath $vite)) { throw 'Run npm ci in aiAgent-front first.' }
foreach ($port in @($BackendPort, $FrontendPort)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use."
    }
}
if (!$env:DB_PASSWORD) {
    $secret = Read-Host 'PostgreSQL password' -AsSecureString
    $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
}
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$node = (Get-Command node -ErrorAction Stop).Source
$maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if (!$maven) { $maven = 'D:/workSoft/apache-maven-3.9.16/bin/mvn.cmd' }
if (!(Test-Path -LiteralPath $maven)) { throw 'Maven was not found.' }
$mavenSettings = Join-Path $projectRoot '.tools/maven-settings.xml'
$backendArgs = @('/c', ('"' + $maven + '"'), '-B', '-ntp', '-gs', ('"' + $mavenSettings + '"'), '-s', ('"' + $mavenSettings + '"'), 'spring-boot:run', "-Dspring-boot.run.arguments=--server.port=$BackendPort")
$backend = Start-Process -FilePath $env:ComSpec -ArgumentList $backendArgs -WorkingDirectory $backendRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'backend.log') -RedirectStandardError (Join-Path $runtime 'backend-error.log') -PassThru
$env:VITE_BASE_URL = "http://localhost:$BackendPort"
$frontend = Start-Process -FilePath $node -ArgumentList @(('"' + $vite + '"'), '--host', '127.0.0.1', '--port', "$FrontendPort", '--strictPort') -WorkingDirectory $frontendRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'frontend.log') -RedirectStandardError (Join-Path $runtime 'frontend-error.log') -PassThru
@{ backend = $backend.Id; frontend = $frontend.Id } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'processes.json')
Write-Output "Backend process $($backend.Id): http://localhost:$BackendPort"
Write-Output "Frontend process $($frontend.Id): http://localhost:$FrontendPort"
Write-Output "Logs: $runtime"
