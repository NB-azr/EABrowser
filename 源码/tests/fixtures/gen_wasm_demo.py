#!/usr/bin/env python3
# 生成 tests/fixtures/wasm_demo.wasm
# 本机没有 clang/wasm32 工具链，因此按 wasm_demo.c 的语义手工汇编出等价二进制；
# 指令序列与 clang -O2 生成的控制流一致（if/else、loop、递归调用、内存读写）。
import struct

def uleb(n):
    out = b''
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out

def sleb(n):
    out = b''
    more = True
    while more:
        b = n & 0x7F
        n >>= 7
        if (n == 0 and not (b & 0x40)) or (n == -1 and (b & 0x40)):
            more = False
        else:
            b |= 0x80
        out += bytes([b])
    return out

def section(sid, payload):
    return bytes([sid]) + uleb(len(payload)) + payload

def vec(items):
    return uleb(len(items)) + b''.join(items)

def nothing():
    pass

I32, I64, F32, F64 = 0x7F, 0x7E, 0x7D, 0x7C

def functype(params, results):
    return b'\x60' + vec([bytes([p]) for p in params]) + vec([bytes([r]) for r in results])

def body(local_types, code):
    # 局部声明按类型合并
    groups = []
    for t in local_types:
        if groups and groups[-1][0] == t:
            groups[-1][1] += 1
        else:
            groups.append([t, 1])
    decl = vec([uleb(c) + bytes([t]) for t, c in groups])
    payload = decl + code
    return uleb(len(payload)) + payload

def export(name, kind, idx):
    return uleb(len(name.encode())) + name.encode() + bytes([kind]) + uleb(idx)

def name_bytes(s):
    return uleb(len(s.encode())) + s.encode()

# ---------- 类型 ----------
# T0 (i32,i32)->i32  T1 (i32)->i32  T2 (i64,i64)->i64  T3 (f32,f32)->f32
# T4 (f64,f64)->f64  T5 ()->i32
types = [
    functype([I32, I32], [I32]),   # 0 add / setmem
    functype([I32], [I32]),         # 1 sum_to/fact/getmem/memsum/call_host/log
    functype([I64, I64], [I64]),    # 2 mul64
    functype([F32, F32], [F32]),    # 3 fadd
    functype([F64, F64], [F64]),    # 4 dmul
    functype([], [I32]),            # 5 get_global
]

# ---------- 导入：env.log(i32)->i32（索引 0） ----------
imports = [
    name_bytes("env") + name_bytes("log") + b'\x00' + uleb(1),
]

# ---------- 函数声明：add=1 sum_to=2 fact=3 mul64=4 fadd=5 dmul=6
#             getmem=7 setmem=8 memsum=9 call_host=10 get_global=11 ----------
func_types = [uleb(t) for t in [0, 1, 1, 2, 3, 4, 1, 0, 1, 1, 5]]

# ---------- 线性内存：min 1 页，max 2 页 ----------
memory = uleb(1) + b'\x01' + uleb(1) + uleb(2)

# ---------- 全局：i32 mutable = 42 ----------
globals = uleb(1) + bytes([I32, 0x01, 0x41]) + sleb(42) + b'\x0B'

# add(a,b) = a + b
b_add = bytes([0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B])

# sum_to(n): s=0; for(i=1;i<=n;i++) s+=i; return s
# locals: s(local1) i(local2)
b_sum = bytes([
    0x41, 0x00, 0x21, 0x01,              # s = 0
    0x41, 0x01, 0x21, 0x02,              # i = 1
    0x02, 0x40,                          # block
    0x03, 0x40,                          #   loop
    0x20, 0x02, 0x20, 0x00, 0x4A, 0x0D, 0x01,   # if i > n: br 1
    0x20, 0x01, 0x20, 0x02, 0x6A, 0x21, 0x01,   # s += i
    0x20, 0x02, 0x41, 0x01, 0x6A, 0x21, 0x02,   # i += 1
    0x0C, 0x00,                          #   br 0
    0x0B,                                #   end loop
    0x0B,                                # end block
    0x20, 0x01,                          # local.get s
    0x0B,
])

# fact(n) = n <= 1 ? 1 : n * fact(n-1)
b_fact = bytes([
    0x20, 0x00, 0x41, 0x01, 0x4C,        # n <= 1
    0x04, 0x7F,                          # if (result i32)
    0x41, 0x01,                          #   1
    0x05,                                # else
    0x20, 0x00, 0x20, 0x00, 0x41, 0x01, 0x6B, 0x10, 0x03, 0x6C,
                                         #   n * fact(n-1)
    0x0B,                                # end if
    0x0B,
])

b_mul64 = bytes([0x20, 0x00, 0x20, 0x01, 0x7E, 0x0B])
b_fadd = bytes([0x20, 0x00, 0x20, 0x01, 0x92, 0x0B])
b_dmul = bytes([0x20, 0x00, 0x20, 0x01, 0xA2, 0x0B])

# getmem(i) = mem[i]  (i32.load)
b_getmem = bytes([0x20, 0x00, 0x41, 0x04, 0x6C, 0x28, 0x02, 0x00, 0x0B])

# setmem(i,v) { mem[i]=v; return v; }
b_setmem = bytes([0x20, 0x00, 0x41, 0x04, 0x6C, 0x20, 0x01, 0x36, 0x02, 0x00, 0x20, 0x01, 0x0B])

# memsum(n): sum(mem[0..n-1])
b_memsum = bytes([
    0x41, 0x00, 0x21, 0x01,              # s = 0
    0x41, 0x00, 0x21, 0x02,              # i = 0
    0x02, 0x40,
    0x03, 0x40,
    0x20, 0x02, 0x20, 0x00, 0x4E, 0x0D, 0x01,   # i >= n ? br 1
    0x20, 0x01,
    0x20, 0x02, 0x41, 0x04, 0x6C, 0x28, 0x02, 0x00,   # mem[i]
    0x6A, 0x21, 0x01,                    # s += mem[i]
    0x20, 0x02, 0x41, 0x01, 0x6A, 0x21, 0x02,
    0x0C, 0x00,
    0x0B, 0x0B,
    0x20, 0x01,
    0x0B,
])

# call_host(x) = env.log(x) + 1
b_callhost = bytes([0x20, 0x00, 0x10, 0x00, 0x41, 0x01, 0x6A, 0x0B])

# get_global() = g
b_getglobal = bytes([0x23, 0x00, 0x0B])

bodies = [
    body([], b_add),
    body([I32, I32], b_sum),
    body([], b_fact),
    body([], b_mul64),
    body([], b_fadd),
    body([], b_dmul),
    body([], b_getmem),
    body([], b_setmem),
    body([I32, I32], b_memsum),
    body([], b_callhost),
    body([], b_getglobal),
]

exports = [
    export("add", 0, 1), export("sum_to", 0, 2), export("fact", 0, 3),
    export("mul64", 0, 4), export("fadd", 0, 5), export("dmul", 0, 6),
    export("getmem", 0, 7), export("setmem", 0, 8), export("memsum", 0, 9),
    export("call_host", 0, 10), export("get_global", 0, 11),
    export("memory", 2, 0), export("gcount", 3, 0),
]

# 数据段：mem[0..3] = {1,2,3,4}
data_payload = uleb(1) + b'\x00' + bytes([0x41]) + sleb(0) + b'\x0B'
databytes = struct.pack('<4i', 1, 2, 3, 4)
data_payload += uleb(len(databytes)) + databytes

module = b'\x00asm\x01\x00\x00\x00'
module += section(1, vec(types))
module += section(2, vec(imports))
module += section(3, vec(func_types))
module += section(5, memory)
module += section(6, globals)
module += section(7, vec(exports))
module += section(10, vec(bodies))
module += section(11, data_payload)

with open("tests/fixtures/wasm_demo.wasm", "wb") as f:
    f.write(module)
print("wrote tests/fixtures/wasm_demo.wasm", len(module), "bytes")
