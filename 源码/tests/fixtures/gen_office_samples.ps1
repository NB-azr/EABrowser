# 用本机 Word / Excel / tar 生成真实 OOXML 与 ZIP 样例（不是手写 XML）。
# 脚本本身是 UTF-8 无 BOM；首次运行会自动转存为带 BOM 后重跑，避免 PowerShell 5 把中文读成乱码。
$self = $MyInvocation.MyCommand.Path
$bytes = [System.IO.File]::ReadAllBytes($self)
if (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) {
    $text = [System.IO.File]::ReadAllText($self, [System.Text.Encoding]::UTF8)
    $utf8Bom = New-Object System.Text.UTF8Encoding($true)
    [System.IO.File]::WriteAllText($self, $text, $utf8Bom)
    powershell -NoProfile -ExecutionPolicy Bypass -File $self
    exit $LASTEXITCODE
}

$base = "E:\浏览器\tests\fixtures"
$officeDir = Join-Path $base "office"
$archiveDir = Join-Path $base "archive"
$tmpDir = Join-Path $base "tmp"
New-Item -ItemType Directory -Force -Path $officeDir | Out-Null
New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$docxPath = Join-Path $officeDir "sample-real.docx"
$xlsxPath = Join-Path $officeDir "sample-real.xlsx"

$word = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $doc = $word.Documents.Add()
    $doc.Content.Text = [string]::Join("`r", @(
        "季度工作汇报",
        "本季度完成了三项重点任务：接口联调、性能优化、文档整理。",
        "参与人员分工：张伟（后端，已完成）、李娜（前端，进行中）、王强（测试，待开始）。",
        "备注：以上数据来自本周例会记录，编号 RPT-2026-11。"
    ))
    if (Test-Path $docxPath) { Remove-Item $docxPath -Force }
    $doc.SaveAs([ref]$docxPath, [ref]16)
    $doc.Close(0)
    "docx ok: $docxPath"
} catch {
    "docx failed: $($_.Exception.Message)"
} finally {
    if ($word -ne $null) { try { $word.Quit() } catch { } }
}

$excel = $null
try {
    $excel = New-Object -ComObject Excel.Application
    $excel.Visible = $false
    $excel.DisplayAlerts = $false
    $wb = $excel.Workbooks.Add()
    $ws = $wb.Worksheets.Item(1)
    $rows = @(
        @("部门", "人数", "完成率"),
        @("研发", 18, 0.92),
        @("市场", 9, 0.75),
        @("客服", 6, 0.88)
    )
    for ($r = 1; $r -le 4; $r++) {
        for ($c = 1; $c -le 3; $c++) {
            $v = $rows[$r - 1][$c - 1]
            $cell = $ws.Cells.Item($r, $c)
            # Excel COM 对 Int32 赋值会报“无法转到 String”，数字统一转 double 再写
            if (($v -is [int]) -or ($v -is [double])) { $cell.Value2 = [double]$v } else { $cell.Value2 = [string]$v }
        }
    }
    $totalCell = $ws.Cells.Item(6, 1)
    $totalCell.Value2 = [string]"合计"
    $sumCell = $ws.Cells.Item(6, 2)
    $sumCell.Formula = "=SUM(B2:B4)"
    if (Test-Path $xlsxPath) { Remove-Item $xlsxPath -Force }
    $wb.SaveAs($xlsxPath, 51)
    $wb.Close($false)
    "xlsx ok: $xlsxPath"
} catch {
    "xlsx failed: $($_.Exception.Message)"
} finally {
    if ($excel -ne $null) { try { $excel.Quit() } catch { } }
}

# 真实 ZIP：用 Windows 自带 bsdtar，条目分隔符为正斜杠
$stage = Join-Path $tmpDir "zip-stage"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $stage "docs") | Out-Null
Set-Content -Path (Join-Path $stage "readme.txt") -Value "这是一个用于验证 ZIP 列表与单文件取出的真实压缩包。" -Encoding UTF8
Add-Content -Path (Join-Path $stage "readme.txt") -Value "第二行：offset check 1234567890" -Encoding UTF8
Set-Content -Path (Join-Path $stage "docs\notes.md") -Value "# 子目录`n- 条目 A`n- 条目 B" -Encoding UTF8
$zipPath = Join-Path $archiveDir "sample-real.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
tar.exe -a -c -f $zipPath -C $stage readme.txt docs
Remove-Item $stage -Recurse -Force
"zip ok: $zipPath"
