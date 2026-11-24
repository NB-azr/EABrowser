/* C++ 解释器容器补强验证：priority_queue、vector insert/erase/reserve、string substr/find/stoi。
 * 运行：node tests/verify_cpp_containers.js
 */
const fs = require('fs');
const path = require('path');

global.window = {};
// 开发目录用 ../android/...，release/tests 用 ../assets/...，两处都能直接跑
const candidates = [
  path.join(__dirname, '..', 'android', 'BrowserApp', 'app', 'src', 'main', 'assets', 'cpp_interpreter.js'),
  path.join(__dirname, '..', 'assets', 'cpp_interpreter.js')
];
const asset = candidates.find(p => fs.existsSync(p));
if (!asset) throw new Error('找不到 cpp_interpreter.js，试过:\n' + candidates.join('\n'));
eval(fs.readFileSync(asset, 'utf8'));
const cpp = window.__CPP;

const cases = [
  {
    name: 'priority_queue max-heap',
    code: [
      '#include <queue>',
      '#include <iostream>',
      'using namespace std;',
      'int main(){',
      '  priority_queue<int> q;',
      '  q.push(3); q.push(1); q.push(5);',
      '  cout << q.top() << " ";',
      '  q.pop();',
      '  cout << q.top() << " ";',
      '  cout << q.size() << " ";',
      '  cout << (q.empty() ? 1 : 0) << endl;',
      '  return 0;',
      '}'
    ].join('\n'),
    expect: '5 3 2 0'
  },
  {
    name: 'priority_queue min-heap (greater)',
    code: [
      '#include <queue>',
      '#include <vector>',
      '#include <iostream>',
      'using namespace std;',
      'int main(){',
      '  priority_queue<int, vector<int>, greater<int> > mq;',
      '  mq.push(3); mq.push(1); mq.push(5);',
      '  cout << mq.top() << " ";',
      '  mq.pop();',
      '  cout << mq.top() << endl;',
      '  return 0;',
      '}'
    ].join('\n'),
    expect: '1 3'
  },
  {
    name: 'vector insert/erase/reserve',
    code: [
      '#include <vector>',
      '#include <iostream>',
      'using namespace std;',
      'int main(){',
      '  vector<int> v;',
      '  v.push_back(10); v.push_back(20); v.push_back(30);',
      '  v.insert(v.begin(), 15);',
      '  v.insert(v.begin(), 2, 7);',
      '  cout << v.size() << ":";',
      '  for (int i = 0; i < v.size(); i++) cout << v[i] << ",";',
      '  cout << endl;',
      '  v.erase(v.begin());',
      '  v.erase(v.begin() + 1, v.begin() + 3);',
      '  cout << v.size() << ":";',
      '  for (int i = 0; i < v.size(); i++) cout << v[i] << ",";',
      '  cout << endl;',
      '  v.reserve(100);',
      '  cout << v.capacity() << endl;',
      '  return 0;',
      '}'
    ].join('\n'),
    expect: '6:7,7,15,10,20,30,\n3:7,20,30,\n100'
  },
  {
    name: 'string substr/find/stoi',
    code: [
      '#include <string>',
      '#include <iostream>',
      'using namespace std;',
      'int main(){',
      '  string s = "hello world hello";',
      '  cout << s.substr(6, 5) << endl;',
      '  cout << s.find("hello") << " ";',
      '  cout << s.find("hello", 1) << endl;',
      '  cout << stoi("  -42abc") << endl;',
      '  return 0;',
      '}'
    ].join('\n'),
    expect: 'world\n0 12\n-42'
  },
  {
    name: 'vector<vector<int>> 嵌套与范围遍历',
    code: [
      '#include <vector>',
      '#include <iostream>',
      'using namespace std;',
      'int main(){',
      '  vector<vector<int>> g;',
      '  vector<int> row;',
      '  row.push_back(1); row.push_back(2); row.push_back(3);',
      '  g.push_back(row);',
      '  vector<int> row2;',
      '  row2.push_back(4); row2.push_back(5);',
      '  g.push_back(row2);',
      '  g[1].push_back(6);',
      '  cout << g.size() << " " << g[0][2] << " " << g[1].size() << endl;',
      '  for (auto& r : g) {',
      '    for (int x : r) cout << x << ",";',
      '    cout << "|";',
      '  }',
      '  cout << endl;',
      '  return 0;',
      '}'
    ].join('\n'),
    expect: '2 3 3\n1,2,3,|4,5,6,|'
  },
  {
    name: 'map 范围遍历与 second 回写',
    code: [
      '#include <map>',
      '#include <string>',
      '#include <iostream>',
      'using namespace std;',
      'int main(){',
      '  map<string, int> m;',
      '  m["b"] = 2; m["a"] = 1; m["c"] = 3;',
      '  for (auto& kv : m) cout << kv.first << ":" << kv.second << ";";',
      '  cout << endl;',
      '  for (auto& kv : m) kv.second += 10;',
      '  for (auto& kv : m) cout << kv.first << "=" << kv.second << ",";',
      '  cout << endl;',
      '  return 0;',
      '}'
    ].join('\n'),
    expect: 'a:1;b:2;c:3;\na=11,b=12,c=13,'
  },
  {
    name: 'sort 自定义比较器',
    code: [
      '#include <vector>',
      '#include <string>',
      '#include <algorithm>',
      '#include <iostream>',
      'using namespace std;',
      'bool byDesc(int a, int b){ return a > b; }',
      'bool byLen(string a, string b){ return a.size() > b.size(); }',
      'int main(){',
      '  vector<int> v;',
      '  v.push_back(3); v.push_back(1); v.push_back(4); v.push_back(2);',
      '  sort(v.begin(), v.end(), byDesc);',
      '  for (int i = 0; i < v.size(); i++) cout << v[i] << ",";',
      '  cout << endl;',
      '  vector<string> w;',
      '  w.push_back("bb"); w.push_back("a"); w.push_back("ccc");',
      '  sort(w.begin(), w.end(), byLen);',
      '  for (int j = 0; j < w.size(); j++) cout << w[j] << ",";',
      '  cout << endl;',
      '  return 0;',
      '}'
    ].join('\n'),
    expect: '4,3,2,1,\nccc,bb,a,'
  }
];

let failures = 0;
for (const c of cases) {
  const r = cpp.run(c.code, '');
  const out = (r.output || '').replace(/\r/g, '').trim();
  if (r.error) {
    console.log('[FAIL] ' + c.name + ' error=' + r.error);
    failures++;
    continue;
  }
  if (out !== c.expect) {
    console.log('[FAIL] ' + c.name);
    console.log('  got     = ' + JSON.stringify(out));
    console.log('  expected= ' + JSON.stringify(c.expect));
    failures++;
  } else {
    console.log('[ok] ' + c.name + ' -> ' + JSON.stringify(out));
  }
}

// stoi 非数字必须明确报错
const bad = cpp.run([
  '#include <string>',
  '#include <iostream>',
  'using namespace std;',
  'int main(){',
  '  cout << stoi("abc");',
  '  return 0;',
  '}'
].join('\n'), '');
if (!bad.error) {
  console.log('[FAIL] stoi("abc") 未报错');
  failures++;
} else {
  console.log('[ok] stoi 非数字报错: ' + bad.error);
}

console.log('RESULT failures=' + failures);
process.exit(failures ? 1 : 0);
