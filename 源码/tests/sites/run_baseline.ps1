$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $Root 'run_sites.ps1') -OutCsv 'baseline.csv' -UnknownCsv 'baseline_unknown.csv'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output 'baseline.csv / baseline_unknown.csv refreshed.'
