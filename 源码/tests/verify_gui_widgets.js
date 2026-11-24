/* GUI 控件补强验证：滑块拖动、下拉选择、确认对话框确定/取消走回 Python。
 * 运行：node tests/verify_gui_widgets.js
 */
const fs = require('fs');
const path = require('path');

global.window = {};
const candidates = [
  path.join(__dirname, '..', 'android', 'BrowserApp', 'app', 'src', 'main', 'assets', 'python_interpreter.js'),
  path.join(__dirname, '..', 'assets', 'python_interpreter.js')
];
const asset = candidates.find(p => fs.existsSync(p));
if (!asset) throw new Error('找不到 python_interpreter.js，试过:\n' + candidates.join('\n'));
eval(fs.readFileSync(asset, 'utf8'));
const py = window.__PY;

/* 渲染层：用假 DOM 跑一遍 gui_sim.js，确认滑块/下拉/对话框真的产出可点结构 */
const host = { innerHTML: '', style: {} };
global.document = { getElementById: id => (id === 'guiHost' ? host : null) };
const guiCandidates = [
  path.join(__dirname, '..', 'android', 'BrowserApp', 'app', 'src', 'main', 'assets', 'gui_sim.js'),
  path.join(__dirname, '..', 'assets', 'gui_sim.js')
];
const guiAsset = guiCandidates.find(p => fs.existsSync(p));
if (!guiAsset) throw new Error('找不到 gui_sim.js，试过:\n' + guiCandidates.join('\n'));
eval(fs.readFileSync(guiAsset, 'utf8'));

let failures = 0;

function check(name, ok, detail) {
  if (ok) {
    console.log('[ok] ' + name);
  } else {
    failures++;
    console.log('[fail] ' + name + (detail ? ' -> ' + detail : ''));
  }
}

function rows() {
  const wins = py.gui.windows();
  const out = [];
  for (const w of wins) for (const r of w.rows) out.push(r);
  return out;
}

function rowOf(kind) {
  return rows().filter(r => r.kind === kind)[0];
}

/* 1) tkinter Scale：command 收到拖动值，取值按步长取整并夹在范围内 */
{
  const src = [
    'import tkinter as tk',
    'win = tk.Tk()',
    's = tk.Scale(win, from_=0, to=100, resolution=5, command=lambda v: print("v=" + str(v)))',
    's.pack()'
  ].join('\n');
  const r = py.run(src);
  check('tk Scale 建立', !r.error, r.error);
  const s = rowOf('scale');
  check('Scale 参数', s && s.scaleMin === 0 && s.scaleMax === 100 && s.scaleStep === 5,
    JSON.stringify(s));
  const rr = py.gui.scale(s.id, 97);
  check('Scale 拖动回调', /v=95/.test(rr.output || ''), rr.output);
  check('Scale 值写回', rowOf('scale').value === '95', rowOf('scale').value);
  const r2 = py.gui.scale(s.id, -20);
  check('Scale 下限夹紧', rowOf('scale').value === '0', rowOf('scale').value);
}

/* 2) ttk.Combobox：选择触发 <<ComboboxSelected>> 绑定，get() 返回新值 */
{
  const src = [
    'import tkinter as tk',
    'from tkinter import ttk',
    'win = tk.Tk()',
    'c = ttk.Combobox(win, values=["a", "b", "c"])',
    'c.pack()',
    'c.bind("<<ComboboxSelected>>", lambda e: print("pick=" + c.get()))'
  ].join('\n');
  const r = py.run(src);
  check('ttk Combobox 建立', !r.error, r.error);
  const c = rowOf('combobox');
  check('Combobox 选项', c && c.items.length === 3, JSON.stringify(c && c.items));
  const rr = py.gui.select(c.id, 2);
  check('Combobox 选择回调', /pick=c/.test(rr.output || ''), rr.output);
  check('Combobox 选中项', rowOf('combobox').selected === 2, String(rowOf('combobox').selected));
  const rr2 = py.gui.select(c.id, 0);
  check('Combobox 再选回调', /pick=a/.test(rr2.output || ''), rr2.output);
}

/* 3) Qt：QSlider / QComboBox 的 valueChanged、currentIndexChanged */
{
  const src = [
    'from PyQt5.QtWidgets import QApplication, QWidget, QVBoxLayout, QSlider, QComboBox',
    'from PyQt5.QtCore import Qt',
    'app = QApplication([])',
    'w = QWidget()',
    'lay = QVBoxLayout(w)',
    'slider = QSlider(Qt.Horizontal)',
    'slider.setRange(0, 10)',
    'slider.setValue(3)',
    'slider.valueChanged.connect(lambda v: print("sv=" + str(v)))',
    'combo = QComboBox()',
    'combo.addItems(["x", "y", "z"])',
    'combo.currentIndexChanged.connect(lambda i: print("ci=" + str(i)))',
    'lay.addWidget(slider)',
    'lay.addWidget(combo)',
    'w.setLayout(lay)'
  ].join('\n');
  const r = py.run(src);
  check('Qt 控件建立', !r.error, r.error);
  const sl = rowOf('scale');
  const cb = rowOf('combobox');
  check('QSlider 范围', sl && sl.scaleMin === 0 && sl.scaleMax === 10, JSON.stringify(sl));
  check('QComboBox 当前项', cb && cb.selected === 0 && cb.items[0] === 'x', JSON.stringify(cb));
  const rr = py.gui.scale(sl.id, 7);
  check('QSlider valueChanged', /sv=7/.test(rr.output || ''), rr.output);
  const rr2 = py.gui.select(cb.id, 1);
  check('QComboBox currentIndexChanged', /ci=1/.test(rr2.output || ''), rr2.output);
}

/* 4) askokcancel：首次运行挂起，点确定/取消后重跑进入 Python 分支 */
{
  const src = [
    'from tkinter import messagebox',
    'if messagebox.askokcancel("确认", "继续吗？"):',
    '    print("YES-BRANCH")',
    'else:',
    '    print("NO-BRANCH")'
  ].join('\n');
  py.dialog.reset();
  const r1 = py.run(src);
  check('首次运行挂起对话框', r1 && r1.dialog && r1.dialog.kind === 'askokcancel',
    JSON.stringify(r1 && r1.dialog));
  check('挂起时无输出', r1 && r1.output === '', r1 && r1.output);

  py.dialog.answer(true);
  const r2 = py.run(src);
  check('确定进入 YES 分支', r2.output.indexOf('YES-BRANCH') >= 0, r2.output);
  check('确定后无残留对话框', !r2.dialog, JSON.stringify(r2.dialog));

  py.dialog.reset();
  const r3 = py.run(src);
  check('再次挂起', r3 && r3.dialog && r3.dialog.kind === 'askokcancel', '');
  py.dialog.answer(false);
  const r4 = py.run(src);
  check('取消进入 NO 分支', r4.output.indexOf('NO-BRANCH') >= 0, r4.output);
}

/* 5) 连续两次询问：答案按顺序回放 */
{
  const src = [
    'from tkinter import messagebox',
    'a = messagebox.askyesno("A", "第一个")',
    'b = messagebox.askyesno("B", "第二个")',
    'print("RESULT " + str(a) + " " + str(b))'
  ].join('\n');
  py.dialog.reset();
  let r = py.run(src);
  check('第一次询问挂起', r && r.dialog && r.dialog.title === 'A', JSON.stringify(r && r.dialog));
  py.dialog.answer(true);
  r = py.run(src);
  check('第二次询问挂起', r && r.dialog && r.dialog.title === 'B', JSON.stringify(r && r.dialog));
  py.dialog.answer(false);
  r = py.run(src);
  check('两次答案顺序正确', /RESULT True False/.test(r.output || ''), r.output);
}

/* 6) askquestion 返回 yes/no 字符串 */
{
  const src = [
    'from tkinter import messagebox',
    'q = messagebox.askquestion("Q", "保存吗？")',
    'print("Q=" + q)'
  ].join('\n');
  py.dialog.reset();
  let r = py.run(src);
  check('askquestion 挂起', r && r.dialog && r.dialog.kind === 'askquestion', '');
  py.dialog.answer(true);
  r = py.run(src);
  check('askquestion 返回 yes', /Q=yes/.test(r.output || ''), r.output);
}

/* 8) 按钮回调里弹确认框：点确定后重跑并回放点击，回调继续执行 */
{
  const src = [
    'import tkinter as tk',
    'from tkinter import messagebox',
    'win = tk.Tk()',
    'def on_delete():',
    '    if messagebox.askyesno("确认", "删除吗？"):',
    '        print("DELETED")',
    '    else:',
    '        print("CANCELLED")',
    'btn = tk.Button(win, text="删除", command=on_delete)',
    'btn.pack()'
  ].join('\n');
  py.dialog.reset();
  let r = py.run(src);
  check('按钮脚本建立', !r.error && !r.dialog, r.error);
  const btn = rowOf('button');
  r = py.gui.click(btn.id);
  check('点击回调弹出确认框', r && r.dialog && r.dialog.kind === 'askyesno', JSON.stringify(r && r.dialog));

  py.dialog.answer(true);
  r = py.run(src);
  check('确定后点重复跑执行', r.output.indexOf('DELETED') >= 0 && !r.dialog, r.output);

  r = py.gui.click(rowOf('button').id);
  check('再次点击又挂起', r && r.dialog && r.dialog.kind === 'askyesno', '');
  py.dialog.answer(false);
  r = py.run(src);
  check('两次点击按序回放', r.output.indexOf('DELETED') >= 0 && r.output.indexOf('CANCELLED') >= 0, r.output);
}

/* 7) 渲染层：range / select / dialog 产出真实可点结构 */
{
  const win = {
    id: 'w1', title: '渲染测试', toolkitLabel: 'tkinter', rows: [
      { id: 'g1', kind: 'scale', text: '音量', value: '30', scaleMin: 0, scaleMax: 100,
        scaleStep: 5, gridRow: -1, gridCol: -1, gridRowSpan: 1, gridColSpan: 1 },
      { id: 'g2', kind: 'combobox', items: ['红', '绿', '蓝'], selected: 1,
        gridRow: -1, gridCol: -1, gridRowSpan: 1, gridColSpan: 1 }
    ]
  };
  window.__GUI.render([win], { kind: 'askokcancel', title: '确认', message: '继续吗？' });
  const html = host.innerHTML;
  check('range 渲染', html.indexOf('id="ea_scale_g1"') >= 0 &&
    html.indexOf('data-ea-id="g1"') >= 0 && html.indexOf('type="range"') >= 0, '');
  check('range 参数渲染', html.indexOf('min="0"') >= 0 && html.indexOf('max="100"') >= 0 &&
    html.indexOf('step="5"') >= 0, '');
  check('range 加减链接', html.indexOf('ea://gui/scale?w=g1&v=25') >= 0 &&
    html.indexOf('ea://gui/scale?w=g1&v=35') >= 0, '');
  check('combobox 渲染', html.indexOf('<select class="guiSelect" data-ea-id="g2"') >= 0, '');
  check('combobox 选中项', html.indexOf('<option value="1" selected>绿</option>') >= 0, '');
  check('对话框确定/取消', html.indexOf('ea://gui/dialog?ok=1') >= 0 &&
    html.indexOf('ea://gui/dialog?ok=0') >= 0 && html.indexOf('guiButtonCancel') >= 0, '');
  check('对话框遮罩', html.indexOf('guiDialogMask') >= 0, '');
  check('宿主显示', host.style.display === 'block', String(host.style.display));
  window.__GUI.render([]);
  check('清空后隐藏', host.style.display === 'none', String(host.style.display));
}

console.log(failures === 0 ? 'RESULT failures=0' : 'RESULT failures=' + failures);
process.exit(failures === 0 ? 0 : 1);
