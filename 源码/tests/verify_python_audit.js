/* Python 解释器审计回归：kwargs / global / nonlocal / try-else / re.sub / json tuple
 * 运行：node tests/verify_python_audit.js
 */
const fs = require('fs');
const path = require('path');

global.window = {};
const candidates = [
  path.join(__dirname, '..', 'android', 'BrowserApp', 'app', 'src', 'main', 'assets', 'python_interpreter.js'),
  path.join(__dirname, '..', 'assets', 'python_interpreter.js')
];
const asset = candidates.find(p => fs.existsSync(p));
if (!asset) throw new Error('找不到 python_interpreter.js');
eval(fs.readFileSync(asset, 'utf8'));
const py = window.__PY;

let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('[ok] ' + name);
  else { failures++; console.log('[fail] ' + name + (detail ? ' -> ' + detail : '')); }
}
function run(src) {
  const r = py.run(src);
  return { out: (r.output || '').trim(), err: r.error };
}

{
  const r = run('def add(a, b):\n    return a + b\nprint(add(a=1, b=2))\nprint(add(1, b=2))\n');
  check('关键字实参', r.out === '3\n3', JSON.stringify(r.out) + ' err=' + r.err);
}
{
  const r = run('x = 1\ndef f():\n    global x\n    x = 5\nf()\nprint(x)\n');
  check('global 写回模块', r.out === '5', JSON.stringify(r.out) + ' err=' + r.err);
}
{
  const r = run('def outer():\n    n = 0\n    def inner():\n        nonlocal n\n        n = 7\n    inner()\n    return n\nprint(outer())\n');
  check('nonlocal 写回外层', r.out === '7', JSON.stringify(r.out) + ' err=' + r.err);
}
{
  const r = run('try:\n    v = 1\nexcept:\n    v = 2\nelse:\n    v = 3\nprint(v)\n');
  check('try-else 正常路径', r.out === '3', JSON.stringify(r.out) + ' err=' + r.err);
}
{
  const r = run('try:\n    raise Exception("x")\nexcept:\n    v = 2\nelse:\n    v = 3\nprint(v)\n');
  check('try-else 异常时跳过', r.out === '2', JSON.stringify(r.out) + ' err=' + r.err);
}
{
  const r = run('import re\nprint(re.sub("x", "y", "abc"))\nprint(re.sub("a", "b", "aaa", 2))\n');
  check('re.sub 默认 count 保留尾部', r.out === 'abc\nbba', JSON.stringify(r.out) + ' err=' + r.err);
}
{
  const r = run('import json\nprint(json.dumps((1, 2)))\nprint(json.dumps([1, (2, 3)]))\n');
  check('json.dumps tuple', r.out === '[1, 2]\n[1, [2, 3]]', JSON.stringify(r.out) + ' err=' + r.err);
}

print_result();
function print_result() {
  console.log('RESULT failures=' + failures);
  if (failures) process.exit(1);
}
