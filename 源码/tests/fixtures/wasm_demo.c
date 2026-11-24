/* wasm_demo.c —— tests/fixtures/wasm_demo.wasm 的语义源码。
 *
 * 本机没有 wasm32 工具链（无 clang/emcc/NDK），因此 .wasm 由
 * tests/fixtures/gen_wasm_demo.py 按本文件的语义手工汇编出等价模块。
 * 若有工具链，可用下面命令得到同样行为的模块：
 *
 *   clang --target=wasm32 -O2 -nostdlib -Wl,--no-entry \
 *         -Wl,--export=add -Wl,--export=sum_to -Wl,--export=fact \
 *         -Wl,--export=mul64 -Wl,--export=fadd -Wl,--export=dmul \
 *         -Wl,--export=getmem -Wl,--export=setmem -Wl,--export=memsum \
 *         -Wl,--export=call_host -Wl,--export=get_global \
 *         -Wl,--export=gcount -Wl,--export-memory \
 *         -Wl,--initial-memory=65536 -Wl,--max-memory=131072 \
 *         -o wasm_demo.wasm wasm_demo.c
 */

int mem[4] = { 1, 2, 3, 4 };
int gcount = 42;                 /* 对应导出 global gcount，可变 */

int add(int a, int b) { return a + b; }

int sum_to(int n) {              /* 1+2+...+n，覆盖 block/loop/br_if */
    int s = 0;
    int i = 1;
    while (i <= n) {
        s += i;
        i += 1;
    }
    return s;
}

int fact(int n) {                /* 覆盖 if/else/result 与递归 call */
    return n <= 1 ? 1 : n * fact(n - 1);
}

long long mul64(long long a, long long b) { return a * b; }
float fadd(float a, float b) { return a + b; }
double dmul(double a, double b) { return a * b; }

int getmem(int i) { return mem[i]; }
int setmem(int i, int v) { mem[i] = v; return v; }
int memsum(int n) {
    int s = 0;
    for (int i = 0; i < n; ++i) s += mem[i];
    return s;
}

extern int host_log(int);        /* 导入 env.log，JS 侧返回 x * 2 */
int call_host(int x) { return host_log(x) + 1; }

int get_global(void) { return gcount; }
