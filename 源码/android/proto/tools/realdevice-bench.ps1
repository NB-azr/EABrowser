# 真机复测脚本（Adreno / Mali / PowerVR）
#
# 用途：在真实 GPU 手机上重复跑原型基准，输出每档指令数的 avg/p95/max、fps、
#       上传耗时、离群帧，并自动给出"GPU 阈值能否从 300 上调"的判定。
#
# 前置：
#   1. 手机开启开发者选项 + USB 调试，adb devices 能看到设备；
#   2. 安装的是 debug 版 APK（脚本用 run-as 拉取结果文件）；
#   3. 同一台机器至少跑 3 轮（默认 Runs=3），取中位数，避免单轮噪声。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File realdevice-bench.ps1
#   powershell ... -Serial <设备序列号> -Runs 5 -Frames 20
#   powershell ... -Apk "E:\浏览器\android\proto\app\build\outputs\apk\debug\app-debug.apk"
#   powershell ... -Serial <序列号> -SkipInstall     # 已装好，跳过重装
#   powershell ... -LibApk <主包 APK> -LibPackage com.ea.browser   # 自定义 arm64 .so 检查目标
#
# 输出：
#   <OutDir>\device.txt          设备/GPU/系统信息
#   <OutDir>\run1.txt ...        每轮原始输出（含 JSON 与逐帧数组）
#   <OutDir>\summary.csv         用例 × 轮次的汇总（中位数，可直接贴表）
#   <OutDir>\frames.csv          逐帧耗时明细（定位离群帧用）
#   <OutDir>\verdict.txt         阈值判定结论（逐条列出未通过的判据）
#
# 判定规则（见 docs/arch-subprocess-and-gpu.md §9.10）：
#   候选阈值上调到 N 条，需要同时满足：
#     1) GL-async 中位 avg <= Canvas 中位 avg × 0.80
#     2) GL-async submitP95 中位 <= 8 ms
#     3) 每轮 fps >= 60（avg <= 16.7 ms）
#     4) 每轮 outliers == 0（没有 > 2×均值的离群帧）
#   任一条不满足 → 该档保持 Canvas，阈值不上调。

param(
    [string]$Apk     = "E:\浏览器\android\proto\app\build\outputs\apk\debug\app-debug.apk",
    # 主包（com.ea.browser）的 APK：arm64 .so 检查以它为准（原型包是纯 Java，没有 .so）
    [string]$LibApk  = "E:\浏览器\android\BrowserApp\app\build\outputs\apk\debug\app-debug.apk",
    [string]$LibPackage = "com.ea.browser",
    [string]$Adb     = "E:\AndroidSDK\platform-tools\adb.exe",
    [string]$Serial  = "",
    [int]$Runs       = 3,
    [int]$Frames     = 20,
    [int]$TimeoutSec = 300,
    [string]$OutDir  = "e:\浏览器\android\proto\evidence\realdevice",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Continue"

# adb 输出是 UTF-8，PowerShell 5.1 默认按控制台 ANSI 解码，中文会变乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

# ---------- 基础工具 ----------
$AdbPre = @()
if ($Serial -ne "") { $AdbPre = @("-s", $Serial) }

function Invoke-Adb([string[]]$a) {
    return (& $Adb @($AdbPre + $a) 2>&1)
}

function EnsureDir([string]$p) {
    if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p | Out-Null }
}

function Median($arr) {
    $s = @($arr | Sort-Object)
    $n = $s.Count
    if ($n -eq 0) { return 0 }
    if ($n % 2 -eq 0) { return ([double]$s[$n / 2 - 1] + [double]$s[$n / 2]) / 2 }
    return [double]$s[[int]($n / 2)]
}

function F2($v) { return ([double]$v).ToString("F2", [System.Globalization.CultureInfo]::InvariantCulture) }
function F1($v) { return ([double]$v).ToString("F1", [System.Globalization.CultureInfo]::InvariantCulture) }

# aapt 不认非 ASCII 路径（中文目录会报 Illegal byte sequence），用 subst 的 X: 或临时 ASCII 副本
function Get-AaptPath([string]$p) {
    if ($p -notmatch '[^\x00-\x7F]') { return $p }
    $x = $p -replace '^[Ee]:\\浏览器', 'X:'
    if (Test-Path -LiteralPath $x) { return $x }
    $tmp = Join-Path $env:TEMP ("aapt_" + [Guid]::NewGuid().ToString("N") + ".apk")
    Copy-Item -LiteralPath $p -Destination $tmp -Force
    return $tmp
}

EnsureDir $OutDir

# ---------- 0. APK 与设备 ----------
if (-not $SkipInstall) {
    if (-not (Test-Path $Apk)) {
        Write-Host "[FAIL] 找不到 APK：$Apk"
        Write-Host "       先构建：cd android\proto && gradlew.bat assembleDebug"
        exit 1
    }
}

$devLines = @((& $Adb devices) | Select-String "\sdevice$")
if ($Serial -eq "") {
    if ($devLines.Count -ne 1) {
        Write-Host "[FAIL] 设备数不为 1，请用 -Serial 指定。当前："
        & $Adb devices
        exit 1
    }
    $Serial = (($devLines[0] -split "\s+")[0]).Trim()
    $AdbPre = @("-s", $Serial)
}
Write-Host "[INFO] 设备 $Serial"

$model = (Invoke-Adb @("shell", "getprop ro.product.model") | Out-String).Trim()
$brand = (Invoke-Adb @("shell", "getprop ro.product.brand") | Out-String).Trim()
$sdk   = (Invoke-Adb @("shell", "getprop ro.build.version.sdk") | Out-String).Trim()
$soc   = (Invoke-Adb @("shell", "getprop ro.soc.model") | Out-String).Trim()
$egl   = (Invoke-Adb @("shell", "getprop ro.hardware.egl") | Out-String).Trim()
$wms   = (Invoke-Adb @("shell", "wm size") | Out-String)
$sizeM = [regex]::Match($wms, "(Physical|Override) size: (\d+x\d+)")
$res   = if ($sizeM.Success) { $sizeM.Groups[2].Value } else { $wms.Trim() }
$gles  = (Invoke-Adb @("shell", "dumpsys SurfaceFlinger") | Select-String "GLES:" | Select-Object -First 1)
$gles  = if ($gles) { ($gles -replace "\s+", " ").Trim() } else { "(dumpsys 无 GLES 行，用 gl info 补)" }

$info = @(
    "serial=$Serial",
    "model=$model",
    "brand=$brand",
    "sdk=$sdk",
    "soc=$soc",
    "egl=$egl",
    "gles=$gles",
    "resolution=$res",
    ("time=" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
)
[System.IO.File]::WriteAllLines((Join-Path $OutDir "device.txt"), $info,
    (New-Object System.Text.UTF8Encoding($false)))
$info | ForEach-Object { Write-Host "[INFO] $_" }

# ---------- 0.5 前置条件检查（arm64 / GLES2 / debug 包 / 温度） ----------
$checks = @()
function AddCheck([string]$level, [string]$msg) {
    $script:checks += ("[{0}] {1}" -f $level, $msg)
}

$abiList = (Invoke-Adb @("shell", "getprop ro.product.cpu.abilist") | Out-String).Trim()
if ($abiList -eq "") {
    $abiList = (Invoke-Adb @("shell", "getprop ro.product.cpu.abi") | Out-String).Trim()
}
$hasArm64 = $abiList -match "arm64-v8a"
AddCheck $(if ($hasArm64) { "PASS" } else { "FAIL" }) "设备 ABI=$abiList（需要 arm64-v8a）"

$glVer = (Invoke-Adb @("shell", "getprop ro.opengles.version") | Out-String).Trim()
$glVerNum = 0
if ($glVer -match "^\d+$") { $glVerNum = [int]$glVer }
AddCheck $(if ($glVerNum -ge 131072) { "PASS" } else { "WARN" }) "ro.opengles.version=$glVer（131072 = ES2.0）"

# 主包（com.ea.browser）的 arm64 .so：原型包是纯 Java，arm64 库在主包里。
# 这里只查主包 APK，等 arm64 库进包后本行直接变 PASS，不影响原型包安装/测量。
$aapt = Get-ChildItem "E:\AndroidSDK\build-tools" -Filter "aapt.exe" -Recurse -ErrorAction SilentlyContinue |
    Select-Object -Last 1

if (Test-Path $LibApk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue 2>$null
    $libAbis = ""; $soCount = 0; $soSample = ""
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($LibApk)
        $libAbis = (($zip.Entries | Where-Object { $_.FullName -like "lib/*/*" } |
            ForEach-Object { ($_.FullName -split "/")[1] } | Sort-Object -Unique) -join ",")
        $so = @($zip.Entries | Where-Object { $_.FullName -like "lib/arm64-v8a/*.so" })
        $soCount = $so.Count
        if ($soCount -gt 0) {
            $soSample = (($so | Select-Object -First 3 |
                ForEach-Object { ($_.FullName -split "/")[-1] }) -join ",")
        }
        $zip.Dispose()
    } catch { $libAbis = "(读取失败)" }
    $arm64Ok = ($libAbis -match "arm64-v8a") -and ($soCount -gt 0)
    AddCheck $(if ($arm64Ok) { "PASS" } else { "FAIL" }) `
        ("主包 $LibPackage ABI=$libAbis arm64.so=$soCount" +
            $(if ($soSample -ne "") { " [$soSample]" } else { "" }) +
            "（需要 lib/arm64-v8a/*.so）")

    if ($aapt) {
        $bad2 = (& $aapt.FullName dump badging (Get-AaptPath $LibApk) 2>&1) | Out-String
        $pkg = [regex]::Match($bad2, "package: name='([^']+)'").Groups[1].Value
        AddCheck $(if ($pkg -eq $LibPackage) { "PASS" } else { "FAIL" }) "主包 APK package=$pkg（期望 $LibPackage）"
    }
    Write-Host "[INFO] 主包检查目标：$LibPackage -> $LibApk"
} else {
    AddCheck "WARN" "主包 APK 不存在，跳过 arm64 .so 检查：$LibApk"
}

# debug 包（run-as 依赖 application-debuggable）
if ($aapt -and (Test-Path $Apk)) {
    $bad = (& $aapt.FullName dump badging (Get-AaptPath $Apk) 2>&1) | Out-String
    AddCheck $(if ($bad -match "application-debuggable") { "PASS" } else { "WARN" }) "APK debuggable 标记（aapt 未检出时以安装后 run-as 实测为准）"
} else {
    AddCheck "WARN" "未找到 aapt，安装后再用 run-as 实测"
}

# 温度（避免热降频污染数据）
$tempRaw = (Invoke-Adb @("shell", "cat /sys/class/power_supply/battery/temp") | Out-String).Trim()
if ($tempRaw -match "^\d+$") {
    $tempC = [double]$tempRaw / 10
    AddCheck $(if ($tempC -le 38) { "PASS" } else { "WARN" }) ("电池温度 {0:F1}C（>38C 会降频，建议冷却后重测）" -f $tempC)
} else {
    AddCheck "WARN" "读不到电池温度（部分 ROM 无该节点），注意手感与降频"
}

$checks | ForEach-Object { Write-Host $_ }
[System.IO.File]::WriteAllLines((Join-Path $OutDir "precheck.txt"), $checks,
    (New-Object System.Text.UTF8Encoding($false))
)

# ---------- 1. 环境稳定化 ----------
Invoke-Adb @("shell", "svc power stayon true") | Out-Null
Invoke-Adb @("shell", "settings put system screen_off_timeout 1800000") | Out-Null
Invoke-Adb @("shell", "settings put global window_animation_scale 0") | Out-Null
Invoke-Adb @("shell", "settings put global transition_animation_scale 0") | Out-Null
Invoke-Adb @("shell", "settings put global animator_duration_scale 0") | Out-Null
Invoke-Adb @("shell", "input keyevent 224") | Out-Null   # WAKEUP
Invoke-Adb @("shell", "wm dismiss-keyguard") | Out-Null
Write-Host "[INFO] 常亮/动画已关闭，屏幕已唤醒"

# ---------- 2. 安装 ----------
if (-not $SkipInstall) {
    Write-Host "[INFO] 安装 $Apk"
    $r = Invoke-Adb @("install", "-r", $Apk) | Out-String
    if ($r -notmatch "Success") { Write-Host "[FAIL] 安装失败：$r"; exit 1 }
    Invoke-Adb @("shell", "pm clear com.ea.proto") | Out-Null
} else {
    Write-Host "[INFO] 跳过安装（-SkipInstall）"
}

# 安装后实测 run-as（比 aapt 更可靠）：不可用则无法拉取结果，直接失败
$ra = (Invoke-Adb @("shell", "run-as com.ea.proto id") | Out-String).Trim()
if ($ra -notmatch "uid=") {
    Write-Host "[FAIL] run-as 不可用，无法拉取 bench_result.txt：$ra"
    Write-Host "       需要 debug 版 APK（application-debuggable=true）"
    exit 1
}

# ---------- 3. 多轮采集 ----------
$runFiles = @()
for ($r = 1; $r -le $Runs; $r++) {
    Write-Host "[RUN] 第 $r/$Runs 轮（frames=$Frames）..."
    Invoke-Adb @("shell", "am force-stop com.ea.proto") | Out-Null
    Invoke-Adb @("shell", "run-as com.ea.proto rm -f files/bench_result.txt") | Out-Null
    $start = Invoke-Adb @("shell", "am start -n com.ea.proto/.MainActivity --ez bench true --ei frames $Frames") | Out-String
    if ($start -match "Error") { Write-Host "[FAIL] 启动失败：$start"; continue }

    $ok = $false
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $txt = (Invoke-Adb @("shell", "run-as com.ea.proto cat /data/data/com.ea.proto/files/bench_result.txt") | Out-String)
        if ($txt -match "JSON\s*\{") { $ok = $true; break }
    }
    if (-not $ok) {
        Write-Host ("[FAIL] 第 {0} 轮 {1}s 内没有结果（黑屏/被杀/ANR？跑 adb logcat -s EAProto 看）" -f $r, $TimeoutSec)
        continue
    }

    $file = Join-Path $OutDir ("run{0}.txt" -f $r)
    [System.IO.File]::WriteAllText($file, $txt, (New-Object System.Text.UTF8Encoding($false)))
    $runFiles += $file
    Write-Host "[OK] 已保存 $file"
}

if ($runFiles.Count -eq 0) { Write-Host "[FAIL] 没有任何轮次成功"; exit 1 }

# ---------- 4. 解析 ----------
$parsed = @()
foreach ($f in $runFiles) {
    $runNo = [int]([regex]::Match($f, "run(\d+)\.txt").Groups[1].Value)
    $text = Get-Content $f -Raw -Encoding UTF8
    # JSON 固定是单独一行（后面还可能跟“跨进程”那行），只取这一行，避免贪婪匹配
    $jsonLine = @(($text -split "`r?`n") | Where-Object { $_ -match '^\s*JSON\s*\{' }) |
        Select-Object -First 1
    if (-not $jsonLine) {
        $jsonLine = @(($text -split "`r?`n") | Where-Object { $_ -match 'JSON\s*\{' }) | Select-Object -First 1
    }
    if (-not $jsonLine) { Write-Host "[WARN] $f 无 JSON 段，跳过"; continue }
    $jsonStr = $jsonLine.Substring($jsonLine.IndexOf('{'))
    try {
        $obj = $jsonStr | ConvertFrom-Json
    } catch {
        Write-Host "[WARN] $f JSON 解析失败：$($_.Exception.Message)"; continue
    }
    $parsed += [pscustomobject]@{ run = $runNo; obj = $obj }
}

if ($parsed.Count -eq 0) { Write-Host "[FAIL] 没有可解析的轮次"; exit 1 }

$cases = @{}
$frameRows = @()
foreach ($p in $parsed) {
    foreach ($c in $p.obj.cases) {
        $k = $c.case
        if (-not $cases.ContainsKey($k)) { $cases[$k] = @() }
        $cases[$k] += [pscustomobject]@{ run = $p.run; c = $c }
        for ($i = 0; $i -lt $c.perFrameCanvasMs.Count; $i++) {
            $frameRows += ("{0},{1},{2},{3},{4}" -f $p.run, $k, $i,
                (F2 $c.perFrameCanvasMs[$i]),
                (F2 $(if ($c.perFrameAsyncMs.Count -gt $i) { $c.perFrameAsyncMs[$i] } else { 0 })))
        }
    }
}

# ---------- 5. 汇总 CSV ----------
$lines = @("case,cmds,runs,canvas_avg,canvas_p95,canvas_max,gl_avg,gl_fps,submit_p95,submit_max,ratio,outliers,build_ms,submit_ms,verts,draws,glyphs,pages")
$rows = @()
foreach ($k in ($cases.Keys | Sort-Object)) {
    $list = $cases[$k]
    $cmds = [int]$list[0].c.cmds
    $cAvg  = Median ($list | ForEach-Object { $_.c.canvas.avg })
    $cP95  = Median ($list | ForEach-Object { $_.c.canvas.p95 })
    $cMax  = Median ($list | ForEach-Object { $_.c.canvas.max })
    $gAvg  = Median ($list | ForEach-Object { $_.c.gles2Async.avg })
    $gFps  = Median ($list | ForEach-Object { $_.c.gles2Async.fps })
    $sP95  = Median ($list | ForEach-Object { $_.c.gles2Async.submitP95 })
    $sMax  = Median ($list | ForEach-Object { $_.c.gles2Async.submitMax })
    $ratio = if ($cAvg -gt 0) { $gAvg / $cAvg } else { 9 }
    $outl  = ($list | ForEach-Object { [int]$_.c.outliers } | Measure-Object -Sum).Sum
    $row = [pscustomobject]@{
        case = $k; cmds = $cmds; runs = $list.Count
        cAvg = $cAvg; cP95 = $cP95; cMax = $cMax
        gAvg = $gAvg; gFps = $gFps; sP95 = $sP95; sMax = $sMax
        ratio = $ratio; outl = $outl
        build = (Median ($list | ForEach-Object { $_.c.breakdown.build }))
        submit = (Median ($list | ForEach-Object { $_.c.breakdown.submit }))
        verts = $list[0].c.breakdown.verts
        draws = $list[0].c.breakdown.draws
        glyphs = $list[0].c.breakdown.glyphs
        pages = $list[0].c.breakdown.pages
    }
    $rows += $row
    $lines += ("{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13},{14},{15},{16},{17}" -f
        $k, $cmds, $list.Count, (F2 $cAvg), (F2 $cP95), (F2 $cMax), (F2 $gAvg), (F1 $gFps),
        (F2 $sP95), (F2 $sMax), (F2 $ratio), $outl, (F2 $row.build), (F2 $row.submit),
        $row.verts, $row.draws, $row.glyphs, $row.pages)
}

$csvPath = Join-Path $OutDir "summary.csv"
[System.IO.File]::WriteAllLines($csvPath, $lines, (New-Object System.Text.UTF8Encoding($false)))

# 上传耗时（每轮一行，再取中位）
$upLines = @("run,full_ms,dirty_ms,band_h")
$fulls = @(); $dirty = @(); $bandH = 0
foreach ($p in $parsed) {
    $fulls  += [double]$p.obj.uploadFullMs
    $dirty  += [double]$p.obj.uploadDirtyMs
    $bandH   = [int]$p.obj.dirtyH
    $upLines += ("{0},{1},{2},{3}" -f $p.run, (F2 $p.obj.uploadFullMs),
        (F2 $p.obj.uploadDirtyMs), [int]$p.obj.dirtyH)
}
$upLines += ("median,{0},{1},{2}" -f (F2 (Median $fulls)), (F2 (Median $dirty)), $bandH)
$upPath = Join-Path $OutDir "upload.csv"
[System.IO.File]::WriteAllLines($upPath, $upLines, (New-Object System.Text.UTF8Encoding($false)))

$frPath = Join-Path $OutDir "frames.csv"
[System.IO.File]::WriteAllLines($frPath,
    (@("run,case,frame,canvas_ms,async_ms") + $frameRows),
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host ("=== 汇总（中位数，{0} 轮） ===" -f $parsed.Count)
$rows | Format-Table case, cmds, cAvg, gAvg, gFps, sP95, sMax, outl -AutoSize | Out-String | Write-Host
Write-Host ("上传：整帧 {0} ms / 脏带 {1} ms（带高 {2}px）" -f (F2 (Median $fulls)), (F2 (Median $dirty)), $bandH)
Write-Host "已写 $csvPath / $upPath / $frPath"

# ---------- 6. 阈值判定 ----------
$verdict = @()
$verdict += ("阈值判定：GL_avg <= Canvas_avg*0.80 且 submitP95 <= 8ms 且 fps >= 60 且 outliers=0（{0} 轮中位数）" -f $parsed.Count)
$verdict += "数据来源：<OutDir>\summary.csv（每档逐帧原始值见 frames.csv）"
foreach ($row in $rows) {
    if ($row.cmds -lt 300) { continue }
    $fails = @()
    if ($row.ratio -gt 0.80) { $fails += ("ratio={0:P0}>80%" -f $row.ratio) }
    if ($row.sP95 -gt 8)     { $fails += ("submitP95={0}ms>8ms" -f (F2 $row.sP95)) }
    if ($row.gFps -lt 60)    { $fails += ("fps={0}<60" -f (F1 $row.gFps)) }
    if ($row.outl -gt 0)     { $fails += ("outliers={0}" -f $row.outl) }
    if ($fails.Count -eq 0) {
        $verdict += ("{0,-14} cmds={1,4}  GL/Canvas={2,4:P0}  GL={3,5}ms p95={4,5}ms fps={5,5}  -> 可以上调" -f
            $row.case, $row.cmds, $row.ratio, (F2 $row.gAvg), (F2 $row.sP95), (F1 $row.gFps))
    } else {
        $verdict += ("{0,-14} cmds={1,4}  GL/Canvas={2,4:P0}  GL={3,5}ms p95={4,5}ms fps={5,5}  -> 保持 Canvas（未过：{6}）" -f
            $row.case, $row.cmds, $row.ratio, (F2 $row.gAvg), (F2 $row.sP95), (F1 $row.gFps), ($fails -join "; "))
    }
}
$verdict += ""
$verdict += "上调建议：若 cjk-text 在 600 档仍满足四条判据，可把文字层阈值从 300 上调到 600；"
$verdict += "          shapes 单独看 1200 档（顶点增长 ≈ 线性，draws=1 不随指令数增长）。"
$verdictPath = Join-Path $OutDir "verdict.txt"
[System.IO.File]::WriteAllLines($verdictPath, $verdict, (New-Object System.Text.UTF8Encoding($false)))
$verdict | ForEach-Object { Write-Host $_ }

# ---------- 7. 恢复环境 ----------
Invoke-Adb @("shell", "svc power stayon false") | Out-Null
Write-Host "[INFO] 完成。把 $OutDir 整个目录带回分析。"
