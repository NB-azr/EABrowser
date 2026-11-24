/* PDF 查看器 worker 路径验证：内联 pdf.worker + 不设 workerSrc，pdf.js 3.24.1 主线程可解析。
 * 运行：node tests/verify_pdf_worker.js
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const assetsDirs = [
  path.join(root, 'android', 'BrowserApp', 'app', 'src', 'main', 'assets'),
  path.join(root, 'assets')
];
const assets = assetsDirs.find(p => fs.existsSync(path.join(p, 'pdfjs', 'pdf.min.js')));
if (!assets) throw new Error('找不到 pdfjs 资源，试过:\n' + assetsDirs.join('\n'));

let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('[ok] ' + name);
  else { failures++; console.log('[fail] ' + name + (detail ? ' -> ' + detail : '')); }
}

/* 模拟页面：worker 先内联执行并挂到 globalThis.pdfjsWorker，随后才加载显示层。
 * 屏蔽 process，让 pdf.js 判定为非 Node（页面里没有 process）。 */
function loadUmd(file) {
  const m = { exports: {} };
  const fn = new Function('module', 'exports', 'process', fs.readFileSync(file, 'utf8'));
  fn(m, m.exports, undefined);
  return m.exports;
}

const worker = loadUmd(path.join(assets, 'pdfjs', 'pdf.worker.min.js'));
check('内联 worker 暴露 WorkerMessageHandler',
  !!(worker && worker.WorkerMessageHandler), Object.keys(worker || {}).join(','));
global.pdfjsWorker = worker;

const lib = loadUmd(path.join(assets, 'pdfjs', 'pdf.min.js'));
check('pdf.js 显示层加载', !!(lib && typeof lib.getDocument === 'function'));

check('未设置 workerSrc，走主线程 fake worker',
  !lib.GlobalWorkerOptions.workerSrc, lib.GlobalWorkerOptions.workerSrc);

const html = fs.readFileSync(path.join(assets, 'pdf_viewer.html'), 'utf8');
check('页面不再写 workerSrc', !/workerSrc\s*=/.test(html));
check('页面对内联 worker 做全局兜底', html.indexOf('window.pdfjsWorker') >= 0);

const pdfCandidates = [
  path.join(root, 'testfiles', 'sample.pdf'),
  path.join(root, '..', 'testfiles', 'sample.pdf')
];
const pdfPath = pdfCandidates.find(p => fs.existsSync(p));
if (!pdfPath) {
  failures++;
  console.log('[fail] 缺少 sample.pdf，试过:\n' + pdfCandidates.join('\n'));
} else {
  const data = new Uint8Array(fs.readFileSync(pdfPath));
  lib.getDocument({ data }).promise.then(doc => {
    check('主线程解析 sample.pdf', doc.numPages > 0, 'numPages=' + doc.numPages);
    console.log(failures === 0 ? 'RESULT failures=0' : 'RESULT failures=' + failures);
    process.exit(failures === 0 ? 0 : 1);
  }, err => {
    failures++;
    console.log('[fail] getDocument 失败 -> ' + (err && err.message ? err.message : err));
    console.log('RESULT failures=' + failures);
    process.exit(1);
  });
}
