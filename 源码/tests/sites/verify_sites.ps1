param(
    [switch]$UpdateBaseline,
    [int]$TimeoutSec = 180
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($UpdateBaseline) {
    & (Join-Path $Root 'run_sites.ps1') -OutCsv 'baseline.csv' -UnknownCsv 'baseline_unknown.csv' -TimeoutSec $TimeoutSec
    Write-Output 'baseline updated.'
    exit 0
}

& (Join-Path $Root 'run_sites.ps1') -OutCsv 'results.csv' -UnknownCsv 'results_unknown.csv' -TimeoutSec $TimeoutSec

$basePath = Join-Path $Root 'baseline.csv'
$baseUnkPath = Join-Path $Root 'baseline_unknown.csv'
if (-not (Test-Path $basePath)) {
    Write-Output 'FAIL: baseline.csv not found. Run .\run_baseline.ps1 first.'
    exit 1
}
$base = @{}
foreach ($r in (Import-Csv $basePath)) { $base[$r.page] = $r }
$now = Import-Csv (Join-Path $Root 'results.csv')
$baseUnk = @{}
if (Test-Path $baseUnkPath) {
    foreach ($u in (Import-Csv $baseUnkPath)) {
        $k = $u.page + '|' + $u.prop
        $baseUnk[$k] = [int]$u.count
    }
}
$nowUnk = @{}
if (Test-Path (Join-Path $Root 'results_unknown.csv')) {
    foreach ($u in (Import-Csv (Join-Path $Root 'results_unknown.csv'))) {
        $k = $u.page + '|' + $u.prop
        $nowUnk[$k] = [int]$u.count
    }
}

$fails = @()
$passes = @()
foreach ($r in $now) {
    $b = $base[$r.page]
    $why = @()
    if ($null -eq $b) {
        $why += 'baseline 中缺少该页（请确认基线版本）'
    } else {
        if ($r.status -ne 'OK') { $why += "状态 $($r.status)（exit=$($r.exit_code)）" }
        if ([int]$r.cmds -eq 0) { $why += '指令数为 0' }
        if ([int]$b.cmds -gt 0) {
            $d = [math]::Abs([int]$r.cmds - [int]$b.cmds)
            if (($d / [int]$b.cmds) -gt 0.15 -and $d -gt 10) {
                $why += "指令数异常 base=$($b.cmds) now=$($r.cmds)"
            }
        }
        if ([int]$r.unsupported_decls -gt [int]$b.unsupported_decls) {
            $why += "新增不支持声明 base=$($b.unsupported_decls) now=$($r.unsupported_decls)"
        }
        $bt = [double]$b.total_ms
        $nt = [double]$r.total_ms
        if ($bt -gt 0 -and $nt -gt (2 * $bt + 50)) {
            $why += "渲染耗时翻倍 base=${bt}ms now=${nt}ms"
        }
        $bw = [double]$b.wall_ms
        $nw = [double]$r.wall_ms
        if ($bw -gt 0 -and $nw -gt (2 * $bw + 1000)) {
            $why += "进程墙钟翻倍 base=${bw}ms now=${nw}ms"
        }
        if ([int]$r.svg_bytes -le 0) { $why += '未生成 SVG 渲染导出' }
    }
    foreach ($k in $nowUnk.Keys) {
        if ($k -like ($r.page + '|*')) {
            $prop = $k.Substring($r.page.Length + 1)
            $nv = $nowUnk[$k]
            $bv = 0
            if ($baseUnk.ContainsKey($k)) { $bv = $baseUnk[$k] }
            if ($bv -eq 0 -and $nv -gt 0) {
                $why += "新增未知属性 $prop=$nv"
            } elseif ($bv -gt 0 -and $nv -gt [math]::Max([math]::Ceiling($bv * 1.5), $bv + 2)) {
                $why += "未知属性计数增加 $prop base=$bv now=$nv"
            }
        }
    }
    if ($why.Count -gt 0) {
        $fails += ("FAIL {0}: {1}" -f $r.page, ($why -join '; '))
    } else {
        $passes += $r.page
    }
}

Write-Output ''
foreach ($p in $passes) { Write-Output ("PASS {0}" -f $p) }
foreach ($f in $fails) { Write-Output $f }
Write-Output ''
Write-Output ("结果：PASS {0} / FAIL {1}" -f $passes.Count, $fails.Count)
if ($fails.Count -gt 0) { exit 1 } else { exit 0 }
