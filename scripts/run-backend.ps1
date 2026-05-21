param(
    [string]$Port = "8080",
    [string]$Model = $(if ($env:GEMINI_MODEL) { $env:GEMINI_MODEL } else { "gemini-2.5-flash-lite" }),
    [string]$ApiKey = $env:GEMINI_API_KEY,
    [string]$ProxyHost = $env:LLM_PROXY_HOST,
    [int]$ProxyPort = $(if ($env:LLM_PROXY_PORT) { [int]$env:LLM_PROXY_PORT } else { 0 }),
    [string]$JavaHome = "$HOME\.jdks\ms-21.0.11"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Host "GEMINI_API_KEY is empty. The app can start, but LLM calls will fail until you set it." -ForegroundColor Yellow
    Write-Host "Example: `$env:GEMINI_API_KEY='your-key'; .\scripts\run-backend.ps1" -ForegroundColor Yellow
} else {
    $env:GEMINI_API_KEY = $ApiKey
}

$env:SERVER_PORT = $Port
$env:GEMINI_MODEL = $Model
if (![string]::IsNullOrWhiteSpace($ProxyHost) -and $ProxyPort -gt 0) {
    $env:LLM_PROXY_ENABLED = "true"
    $env:LLM_PROXY_HOST = $ProxyHost
    $env:LLM_PROXY_PORT = [string]$ProxyPort
    Write-Host "Gemini proxy: http://${ProxyHost}:${ProxyPort}" -ForegroundColor Green
} else {
    $env:LLM_PROXY_ENABLED = "false"
}
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
$java = Join-Path $JavaHome "bin\java.exe"
if (!(Test-Path $java)) {
    throw "Java 21 executable not found: $java"
}

Write-Host "Starting Marketing Agent on http://localhost:$Port" -ForegroundColor Green
Write-Host "Web console: http://localhost:$Port/" -ForegroundColor Green
.\mvn-jdk21.cmd -pl marketing-agent-app -am -DskipTests package
& $java -jar .\marketing-agent-app\target\marketing-agent-app-0.0.1-SNAPSHOT.jar
