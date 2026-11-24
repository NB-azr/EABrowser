param([int]$TimeoutSec = 90)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ShotDir = Join-Path $Root 'shots'
New-Item -ItemType Directory -Force $ShotDir | Out-Null

$candidates = @(
    'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    'C:\Program Files\Microsoft\Edge\Application\msedge.exe'
)
$edge = $null
foreach ($c in $candidates) { if (Test-Path $c) { $edge = $c; break } }
if (-not $edge) {
    Write-Output 'SKIP: msedge.exe not found.'
    exit 1
}

function Q([string]$s) {
    if ($s -match '[ \t"]') { return '"' + ($s -replace '"', '\"') + '"' }
    return $s
}

$sites = [IO.File]::ReadAllText((Join-Path $Root 'sites.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
$meta = @()
foreach ($s in $sites) {
    $png = Join-Path $ShotDir ($s.slug + '.png')
    Remove-Item $png -ErrorAction SilentlyContinue
    $prof = Join-Path $ShotDir ('_profile_' + $s.slug)
    New-Item -ItemType Directory -Force $prof | Out-Null
    $args = @(
        '--headless', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
        '--no-default-browser-check', '--disable-extensions',
        ('--user-data-dir=' + $prof),
        ('--window-size={0},{1}' -f $s.vw, $s.vh),
        ('--screenshot=' + $png),
        $s.url
    ) | ForEach-Object { Q $_ }
    $argLine = $args -join ' '
    $p = Start-Process -FilePath $edge -ArgumentList $argLine -PassThru -WindowStyle Hidden
    $done = $p.WaitForExit($TimeoutSec * 1000)
    if (-not $done) { try { $p.Kill() } catch {} }
    Start-Sleep -Milliseconds 500
    $ok = (Test-Path $png) -and ((Get-Item $png).Length -gt 8000)
    $entry = [ordered]@{
        page = $s.slug; url = $s.url; png = $png; ok = $ok
        bytes = if (Test-Path $png) { (Get-Item $png).Length } else { 0 }
        exit_code = $p.ExitCode
        reason = if ($ok) { '' } else { 'headless 未产出有效截图（站点反爬/登录墙/加载超时）' }
    }
    $meta += [pscustomobject]$entry
    if ($ok) { Write-Output ("[SHOT] {0,-16} {1} bytes" -f $s.slug, $entry.bytes) }
    else { Write-Output ("[MISS] {0,-16} {1}" -f $s.slug, $entry.reason) }
    Remove-Item $prof -Recurse -Force -ErrorAction SilentlyContinue
}
$meta | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ShotDir 'meta.json') -Encoding UTF8
Write-Output 'shots/meta.json written.'
