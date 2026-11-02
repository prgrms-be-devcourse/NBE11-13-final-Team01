param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [int[]]$Users = @(10, 30, 50, 100),
    [string]$Duration = '1m',
    [double]$ThinkSeconds = 0.5,
    [int]$P95Ms = 2000,
    [double]$ErrorRate = 0.01,
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$uri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http', 'https')) {
    throw 'BaseUrl에 http 또는 https 서버 주소를 입력해 주세요.'
}
if (@($Users | Where-Object { $_ -lt 1 }).Count -gt 0 -or $Users.Count -eq 0 -or $ThinkSeconds -lt 0 -or $P95Ms -lt 1 -or $ErrorRate -lt 0 -or $ErrorRate -gt 1) {
    throw '사용자 수, 대기 시간 또는 기준값을 확인해 주세요.'
}
if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw 'k6를 찾을 수 없습니다.' }

$scriptDir = $PSScriptRoot
if (-not $OutputDir) { $OutputDir = Join-Path $scriptDir ('results/run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$outputPath = (Resolve-Path -LiteralPath $OutputDir).Path
$scenarioFiles = @('map-list.js', 'housing-detail.js', 'filter.js', 'user-flow.js')
$env:BASE_URL = $uri.AbsoluteUri.TrimEnd('/')
$env:DURATION = $Duration
$env:THINK_SECONDS = $ThinkSeconds.ToString([Globalization.CultureInfo]::InvariantCulture)
$env:P95_MS = "$P95Ms"
$env:ERROR_RATE = $ErrorRate.ToString([Globalization.CultureInfo]::InvariantCulture)
$rows = @()

foreach ($scenario in $scenarioFiles) {
    foreach ($vus in $Users) {
        $env:VUS = "$vus"
        $name = [IO.Path]::GetFileNameWithoutExtension($scenario)
        $summaryPath = Join-Path $outputPath "$name-$vus.json"
        Write-Host "실행: $name / 동시 가상 사용자 $vus / $Duration"
        & k6 run "--summary-export=$summaryPath" (Join-Path $scriptDir $scenario)
        $status = $LASTEXITCODE
        $rows += [pscustomobject]@{ scenario = $name; vus = $vus; duration = $Duration; exitCode = $status; summary = $summaryPath }
        $rows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outputPath 'runs.json') -Encoding utf8
        if ($status -ne 0) {
            Write-Warning "실패 또는 기준 초과로 중단했습니다. 결과: $outputPath"
            exit $status
        }
    }
}
Write-Host "모든 단계 완료. 결과: $outputPath"
