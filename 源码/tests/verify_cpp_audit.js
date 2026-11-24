/* C++ 解释器审计回归：整数截断、类型转换、vector(n)、迭代器自增、引用形参、
 * string append/clear、默认构造、嵌套模板 >>、map/set find 迭代器。
 * 运行：node tests/verify_cpp_audit.js
 */
const fs = require('fs');
const path = require('path');

global.window = {};
const candidates = [
  path.join(__dirname, '..', 'android', 'BrowserApp', 'app', 'src', 'main', 'assets', 'cpp_interpreter.js'),
  path.join(__dirname, '..', 'assets', 'cpp_interpreter.js')
];
const asset = candidates.find(p => fs.existsSync(p));
if (!asset) throw new Error('找不到 cpp_interpreter.js，试过:\n' + candidates.join('\n'));
eval(fs.readFileSync(asset, 'utf8'));
const cpp = window.__CPP;

let failures = 0;
function run(name, lines, expect) {
  const r = cpp.run(lines.join('\n'));
  if (r.error) {
    failures++;
    console.log('[fail] ' + name + ' -> ' + r.error);
    return;
  }
  const out = (r.output || '').replace(/\r/g, '').trim();
  if (out !== expect) {
    failures++;
    console.log('[fail] ' + name + ' -> got "' + out + '" want "' + expect + '"');
    return;
  }
  console.log('[ok] ' + name);
}

run('整数除法与 (int) 向零截断', [
  '#include <iostream>',
  'using namespace std;',
  'int main(){',
  '  cout << -7/2 << " " << (int)-2.9 << " " << 7/2 << endl;',
  '  return 0;',
  '}'
], '-3 -2 3');

run('声明/赋值/形参/返回按类型转换', [
  '#include <iostream>',
  'using namespace std;',
  'double half(double x){ return x/2; }',
  'int main(){',
  '  double d = 1;',
  '  int i = 2.9;',
  '  double e = 3;',
  '  cout << d/2 << " " << i << " " << e/2 << " " << half(1) << endl;',
  '  return 0;',
  '}'
], '0.5 2 1.5 0.5');

run('vector<int> v(n) 填充默认值', [
  '#include <vector>',
  '#include <iostream>',
  'using namespace std;',
  'int main(){',
  '  vector<int> v(5);',
  '  cout << v.size() << " " << v[0] << " " << v[4] << endl;',
  '  return 0;',
  '}'
], '5 0 0');

run('it++ / ++it 仍是迭代器', [
  '#include <map>',
  '#include <vector>',
  '#include <iostream>',
  'using namespace std;',
  'int main(){',
  '  map<string,int> m;',
  '  m["a"] = 1; m["b"] = 2;',
  '  auto it = m.begin();',
  '  while (it != m.end()) { cout << it->first << it->second; it++; }',
  '  vector<int> v; v.push_back(7); v.push_back(8);',
  '  for (auto p = v.begin(); p != v.end(); ++p) cout << p->first;',
  '  return 0;',
  '}'
], 'a1b278');

run('引用形参写回实参', [
  '#include <iostream>',
  'using namespace std;',
  'void inc(int& x){ x = x + 1; }',
  'int main(){',
  '  int a = 5;',
  '  inc(a);',
  '  cout << a << endl;',
  '  return 0;',
  '}'
], '6');

run('string append/clear 写回', [
  '#include <string>',
  '#include <iostream>',
  'using namespace std;',
  'int main(){',
  '  string s = "ab";',
  '  s.append("cd");',
  '  cout << s << " ";',
  '  s.clear();',
  '  cout << s.size() << endl;',
  '  return 0;',
  '}'
], 'abcd 0');

run('Type obj; 执行默认构造', [
  '#include <iostream>',
  'using namespace std;',
  'struct P { int x; int y; P(){ x = 1; y = 2; } };',
  'int main(){',
  '  P p;',
  '  cout << p.x << p.y << endl;',
  '  return 0;',
  '}'
], '12');

run('嵌套模板 >> 可解析', [
  '#include <vector>',
  '#include <map>',
  '#include <iostream>',
  'using namespace std;',
  'int main(){',
  '  vector<vector<int>> vv;',
  '  vector<int> row; row.push_back(1);',
  '  vv.push_back(row);',
  '  map<string, vector<int>> ms;',
  '  ms["k"].push_back(7);',
  '  cout << vv.size() << " " << vv[0][0] << " " << ms["k"][0] << endl;',
  '  return 0;',
  '}'
], '1 1 7');

run('map/set find 返回迭代器，未命中等于 end()', [
  '#include <map>',
  '#include <set>',
  '#include <iostream>',
  'using namespace std;',
  'int main(){',
  '  map<string,int> m;',
  '  m["a"] = 1;',
  '  auto hit = m.find("a");',
  '  if (hit != m.end()) cout << hit->second;',
  '  auto miss = m.find("z");',
  '  if (miss != m.end()) cout << "BAD"; else cout << "OK";',
  '  m.erase(hit);',
  '  cout << m.size();',
  '  set<int> s; s.insert(3);',
  '  if (s.find(3) != s.end()) cout << "Y";',
  '  if (s.find(9) != s.end()) cout << "BAD"; else cout << "N";',
  '  return 0;',
  '}'
], '1OK0YN');

console.log(failures === 0 ? 'RESULT failures=0' : 'RESULT failures=' + failures);
process.exit(failures === 0 ? 0 : 1);
