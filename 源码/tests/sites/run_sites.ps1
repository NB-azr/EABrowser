param(
    [string]$OutCsv = "results.csv",
    [string]$UnknownCsv = "results_unknown.csv",
    [int]$TimeoutSec = 180,
    [int]$Repeat = 3
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Runner = 'E:\浏览器\build_host\feature_runner.exe'
if (-not (Test-Path $Runner)) { throw "feature_runner not found: $Runner" }

$logDir = Join-Path $Root 'logs'
$svgDir = Join-Path $Root 'snapshots'
New-Item -ItemType Directory -Force $logDir | Out-Null
New-Item -ItemType Directory -Force $svgDir | Out-Null

function Q([string]$s) {
    if ($s -match '[ \t"]') { return '"' + ($s -replace '"', '\"') + '"' }
    return $s
}
function Grab([string]$t, [string]$pat, [double]$def = 0) {
    $m = [regex]::Match($t, $pat)
    if ($m.Success) { return [double]$m.Groups[1].Value }
    return $def
}

$sites = [IO.File]::ReadAllText((Join-Path $Root 'sites.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
$rows = @()
$unknownRows = @()

foreach ($s in $sites) {
    $pageDir = Join-Path $Root ($s.dir -replace '/', '\')
    $page = Join-Path $pageDir 'page.html'
    $svg = Join-Path $svgDir ($s.slug + '.svg')
    $row = [ordered]@{
        page = $s.slug; url = $s.url; viewport = "$($s.vw)x$($s.vh)"; js = 'off'
        cmds = 0; text = 0; rect = 0; image = 0; border = 0; clip = 0
        unsupported_decls = 0; unknown_props = 0; ignored_conds = 0; dropped_parts = 0
        layout_ms = 0; style_ms = 0; total_ms = 0; wall_ms = 0
        exit_code = 0; status = 'OK'; svg_bytes = 0; unknown_top = ''
    }
    if (-not (Test-Path $page)) {
        $row.status = 'MISSING_PAGE'
        Write-Output ("[MISS] {0}  {1}" -f $s.slug, $page)
        $rows += [pscustomobject]$row
        continue
    }

    $argList = @($page, "$($s.vw)", "$($s.vh)", '--no-js', '--dpr', '1', '--gap-dump', '--svg', $svg)
    $imgFile = Join-Path $pageDir 'images.json'
    if (Test-Path $imgFile) {
        $imgs = [IO.File]::ReadAllText($imgFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
        foreach ($im in $imgs) {
            $argList += '--img'
            $argList += ('{0}:{1}:{2}' -f $im.url, $im.w, $im.h)
        }
    }
    $argLine = ($argList | ForEach-Object { Q $_ }) -join ' '

    $outLog = Join-Path $logDir ($s.slug + '.log')
    $runs = @(); $runTexts = @(); $runUnks = @()
    for ($rep = 0; $rep -lt $Repeat; $rep++) {
        $r2 = [ordered]@{
            page = $s.slug; url = $s.url; viewport = "$($s.vw)x$($s.vh)"; js = 'off'
            cmds = 0; text = 0; rect = 0; image = 0; border = 0; clip = 0
            unsupported_decls = 0; unknown_props = 0; ignored_conds = 0; dropped_parts = 0
            layout_ms = 0; style_ms = 0; total_ms = 0; wall_ms = 0
            exit_code = 0; status = 'OK'; svg_bytes = 0; unknown_top = ''
        }
        Remove-Item $svg -ErrorAction SilentlyContinue
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $Runner
        $psi.Arguments = $argLine
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true
        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi
        $proc.Start() | Out-Null
        $outTask = $proc.StandardOutput.ReadToEndAsync()
        $errTask = $proc.StandardError.ReadToEndAsync()
        $done = $proc.WaitForExit($TimeoutSec * 1000)
        $text = ''
        if (-not $done) {
            try { $proc.Kill() } catch {}
            $r2.status = 'TIMEOUT'; $r2.exit_code = -1
        } else {
            $proc.WaitForExit()
            $r2.exit_code = $proc.ExitCode
            if ($proc.ExitCode -ne 0) { $r2.status = 'CRASH' }
            $text = $outTask.Result + "`n" + $errTask.Result
        }
        $sw.Stop()
        $r2.wall_ms = [int]$sw.Elapsed.TotalMilliseconds

        $r2.cmds = [int](Grab $text '\[CMDS\]\s+count=(\d+)')
        $r2.text = [int](Grab $text '\[CMDS\][^\n]*TEXT=(\d+)')
        $r2.rect = [int](Grab $text '\[CMDS\][^\n]*RECT=(\d+)')
        $r2.image = [int](Grab $text '\[CMDS\][^\n]*IMAGE=(\d+)')
        $r2.border = [int](Grab $text '\[CMDS\][^\n]*BORDER=(\d+)')
        $r2.clip = [int](Grab $text '\[CMDS\][^\n]*CLIP=(\d+)')
        $r2.unsupported_decls = [int](Grab $text '\[GAP\][^\n]*unsupported_decls=(\d+)')
        $r2.unknown_props = [int](Grab $text '\[GAP\][^\n]*unknown_props=(\d+)')
        $r2.ignored_conds = [int](Grab $text '\[GAP\][^\n]*ignored_conds=(\d+)')
        $r2.dropped_parts = [int](Grab $text '\[GAP\][^\n]*dropped_parts=(\d+)')
        $r2.layout_ms = [math]::Round((Grab $text '\[PERF\][^\n]*layout_ms=([\d.]+)'), 2)
        $r2.style_ms = [math]::Round((Grab $text '\[PERF\][^\n]*style_ms=([\d.]+)'), 2)
        $r2.total_ms = [math]::Round($r2.layout_ms + $r2.style_ms, 2)

        $unks = [regex]::Matches($text, '\[UNKNOWN\]\s+([^=\r\n]+)=(\d+)')
        $top = @(); $uRows = @()
        foreach ($u in $unks) {
            $name = $u.Groups[1].Value.Trim()
            $cnt = [int]$u.Groups[2].Value
            $uRows += [pscustomobject]@{ page = $s.slug; prop = $name; count = $cnt }
            if ($top.Count -lt 4) { $top += ("{0}={1}" -f $name, $cnt) }
        }
        $r2.unknown_top = ($top -join '; ')

        if ((Test-Path $svg) -and ((Get-Item $svg).Length -gt 0)) {
            $r2.svg_bytes = (Get-Item $svg).Length
        } elseif ($r2.status -eq 'OK') {
            $r2.status = 'NO_SVG'
        }
        $runs += [pscustomobject]$r2
        $runTexts += $text
        $runUnks += ,$uRows
    }

    # 耗时取 3 次运行的中位数（不是最小值）：有模拟器/编译抢 CPU 时单次最小值系统性偏低。
    # 3 次实测值都追加到日志尾部，便于复核“不是挑最好的一次”。
    $okIdx = @(for ($i = 0; $i -lt $runs.Count; $i++) { if ($runs[$i].status -eq 'OK') { $i } })
    if ($okIdx.Count -eq 0) { $okIdx = @(0) }
    $sortedIdx = @($okIdx | Sort-Object { $runs[$_].total_ms })
    $pickIdx = $sortedIdx[[int][math]::Floor($sortedIdx.Count / 2)]
    $row = [ordered]@{}
    foreach ($k in $runs[$pickIdx].PSObject.Properties.Name) { $row[$k] = $runs[$pickIdx].$k }
    $runNote = ($runs | ForEach-Object { "[RUN] status={0} cmds={1} layout_ms={2} style_ms={3} total_ms={4}" -f $_.status, $_.cmds, $_.layout_ms, $_.style_ms, $_.total_ms }) -join "`n"
    Set-Content -Path $outLog -Value ($runTexts[$pickIdx] + "`n--- RUNS (median picked) ---`n" + $runNote) -Encoding UTF8
    foreach ($u in $runUnks[$pickIdx]) { $unknownRows += $u }
    $rows += [pscustomobject]$row
    Write-Output ("[{0}] {1,-16} cmds={2,-5} decls={3,-4} unk={4,-4} layout={5}ms style={6}ms wall={7}ms" -f `
        $row.status, $s.slug, $row.cmds, $row.unsupported_decls, $row.unknown_props, `
        $row.layout_ms, $row.style_ms, $row.wall_ms)
}

$rows | Export-Csv -Path (Join-Path $Root $OutCsv) -NoTypeInformation -Encoding UTF8
$unknownRows | Export-Csv -Path (Join-Path $Root $UnknownCsv) -NoTypeInformation -Encoding UTF8
Write-Output ("`nWrote {0} and {1} ({2} pages)" -f $OutCsv, $UnknownCsv, $rows.Count)
