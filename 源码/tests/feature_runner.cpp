/* 引擎特性验证 runner（宿主端，无 Android 依赖）
 * 用法:
 *   feature_runner <html> [width] [height] [--t 0,500,1000] [--svg out.svg]
 *        [--hit 10,10;50,60]
 * 输出: 布局高度 / 绘制指令 / 命中测试 / 动画逐帧 transform
 * 另外可把绘制指令导出为 SVG 快照，便于肉眼对照。
 */
#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <cctype>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <utility>
#include <filesystem>

/* ---- 桌面宿主：把渲染器的命中/文本命中/节点盒接到 JS 引擎 ---- */
struct EaHostHitCtx { void* r; void* doc; float vw, vh; };

static void eaHostEnsureLayout(EaHostHitCtx* c) {
    if (c && c->r && c->doc) ea_render_layout(c->r, c->doc, c->vw, c->vh);
}

static void* eaHostElementAt(void* ud, double x, double y) {
    EaHostHitCtx* c = (EaHostHitCtx*)ud;
    eaHostEnsureLayout(c);
    return ea_render_hit_test(c->r, (float)x, (float)y);
}

static int eaHostCaretAt(void* ud, double x, double y, void** outNode,
                         int* outOffset, double* outX, double* outY) {
    EaHostHitCtx* c = (EaHostHitCtx*)ud;
    eaHostEnsureLayout(c);
    ea_html_node n = nullptr;
    int off = 0;
    float cx = 0, cy = 0;
    if (!ea_render_caret_at(c->r, (float)x, (float)y, &n, &off, &cx, &cy)) return 0;
    if (outNode) *outNode = n;
    if (outOffset) *outOffset = off;
    if (outX) *outX = cx;
    if (outY) *outY = cy;
    return 1;
}

static int eaHostRect(void* ud, void* node, double* x, double* y, double* w, double* h) {
    EaHostHitCtx* c = (EaHostHitCtx*)ud;
    if (!c || !node) return 0;
    eaHostEnsureLayout(c);
    float fx = 0, fy = 0, fw = 0, fh = 0;
    if (!ea_render_node_box(c->r, (ea_html_node)node, &fx, &fy, &fw, &fh)) return 0;
    if (x) *x = fx;
    if (y) *y = fy;
    if (w) *w = fw;
    if (h) *h = fh;
    return 1;
}
#include <functional>
#include <algorithm>
#include <chrono>

static std::string readAll(const char* path, bool& ok) {
    ok = false;
    FILE* f = fopen(path, "rb");
    if (!f) return std::string();
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::string s;
    if (sz > 0) s.resize((size_t)sz);
    size_t rd = s.empty() ? 0 : fread(&s[0], 1, s.size(), f);
    fclose(f);
    s.resize(rd);
    ok = true;
    return s;
}

/* 递归展开 CSS 内的 @import（本地文件） */
static std::string inlineImports(const std::string& css, const std::string& base, int depth);

static std::string baseDirOf(const std::string& path) {
    size_t sep = path.find_last_of("/\\");
    return (sep == std::string::npos) ? std::string() : path.substr(0, sep + 1);
}

static std::string inlineImports(const std::string& css, const std::string& base, int depth) {
    if (depth > 4) return css;
    std::string out = css;
    size_t pos = 0;
    while ((pos = out.find("@import", pos)) != std::string::npos) {
        size_t end = out.find(';', pos);
        if (end == std::string::npos) break;
        std::string stmt = out.substr(pos, end - pos + 1);
        size_t q1 = stmt.find_first_of("\"'");
        std::string rel;
        if (q1 != std::string::npos) {
            size_t q2 = stmt.find(stmt[q1], q1 + 1);
            if (q2 != std::string::npos) rel = stmt.substr(q1 + 1, q2 - q1 - 1);
        } else {
            size_t up = stmt.find("url(");
            if (up != std::string::npos) {
                size_t q = stmt.find(')', up);
                rel = stmt.substr(up + 4, q - up - 4);
                if (!rel.empty() && (rel.front() == '"' || rel.front() == '\'')) rel = rel.substr(1, rel.size() - 2);
            }
        }
        if (rel.rfind("./", 0) == 0) rel = rel.substr(2);
        bool ok = false;
        std::string sub;
        if (!rel.empty() && rel.find("://") == std::string::npos)
            sub = readAll((base + rel).c_str(), ok);
        if (ok) {
            std::string rep = inlineImports(sub, baseDirOf(base + rel), depth + 1);
            out.replace(pos, end - pos + 1, rep);
            pos += rep.size();
        } else {
            pos = end + 1;
        }
    }
    return out;
}

/* 把本地 <link rel=stylesheet href=...> 内联进 HTML（模拟浏览器注入外链 CSS 后的结果） */
static std::string inlineLocalCss(const std::string& html, const std::string& htmlPath) {
    std::string base = baseDirOf(htmlPath);
    std::string out = html;
    size_t pos = 0;
    while ((pos = out.find("<link", pos)) != std::string::npos) {
        size_t end = out.find('>', pos);
        if (end == std::string::npos) break;
        std::string tag = out.substr(pos, end - pos + 1);
        if (tag.find("stylesheet") != std::string::npos) {
            size_t hp = tag.find("href");
            if (hp != std::string::npos) {
                size_t q1 = tag.find_first_of("\"'", hp);
                if (q1 != std::string::npos) {
                    size_t q2 = tag.find(tag[q1], q1 + 1);
                    if (q2 != std::string::npos) {
                        std::string rel = tag.substr(q1 + 1, q2 - q1 - 1);
                        if (rel.rfind("./", 0) == 0) rel = rel.substr(2);
                        if (rel.find("://") == std::string::npos && !rel.empty()) {
                            bool ok = false;
                            std::string css = readAll((base + rel).c_str(), ok);
                            if (ok) {
                                css = inlineImports(css, baseDirOf(base + rel), 0);
                                std::string rep = "<style>\n" + css + "\n</style>";
                                out.replace(pos, end - pos + 1, rep);
                                pos += rep.size();
                                continue;
                            }
                        }
                    }
                }
            }
        }
        pos = end + 1;
    }
    return out;
}

static std::string hexColor(unsigned int c) {
    char buf[16];
    snprintf(buf, sizeof(buf), "#%02X%02X%02X", (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
    return buf;
}

static std::string xmlEscape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '&') o += "&amp;";
        else if (c == '<') o += "&lt;";
        else if (c == '>') o += "&gt;";
        else if (c == '"') o += "&quot;";
        else o += c;
    }
    return o;
}

static const char* typeName(int t) {
    switch (t) {
        case EA_DRAW_RECT: return "RECT";
        case EA_DRAW_TEXT: return "TEXT";
        case EA_DRAW_BORDER: return "BORDER";
        case EA_DRAW_LINE: return "LINE";
        case EA_DRAW_IMAGE: return "IMAGE";
        case EA_DRAW_CONIC: return "CONIC";
        case EA_DRAW_SELECTION: return "SEL";
        case EA_DRAW_CLIP_PUSH: return "CLIP+";
        case EA_DRAW_CLIP_POP: return "CLIP-";
        case EA_DRAW_VIDEO: return "VIDEO";
        case EA_DRAW_SUBDOC: return "SUBDOC";
        case EA_DRAW_FILTER: return "FILTER";
        default: return "?";
    }
}

/* ---------- 页面脚本执行（模拟浏览器加载顺序） ---------- */

struct BigTextInfo {
    int len = 0;
    std::string preview;
    std::string parent;
};

static void collectTexts(ea_html_node n, const std::string& parentDesc, std::vector<BigTextInfo>& out) {
    if (!n) return;
    int t = ea_html_node_type_of(n);
    if (t == EA_NODE_TEXT || t == EA_NODE_RAW_TEXT) {
        const char* txt = ea_html_node_text(n);
        if (txt) {
            std::string s(txt);
            ea_html_free_string(txt);
            BigTextInfo b;
            b.len = (int)s.size();
            std::string p = s.substr(0, 50);
            for (auto& ch : p) if (ch == '\n' || ch == '\r' || ch == '\t') ch = ' ';
            b.preview = p;
            b.parent = parentDesc;
            out.push_back(b);
        }
        return;
    }
    std::string desc = parentDesc;
    if (t == EA_NODE_ELEMENT) {
        const char* nm = ea_html_node_name(n);
        desc = nm ? std::string(nm) : std::string("?");
        if (nm) ea_html_free_string(nm);
        const char* id = ea_html_node_attr(n, "id");
        if (id) { desc += "#" + std::string(id); ea_html_free_string(id); }
        const char* cls = ea_html_node_attr(n, "class");
        if (cls) { desc += "." + std::string(cls).substr(0, 40); ea_html_free_string(cls); }
    }
    for (ea_html_node c = ea_html_node_first_child(n); c; c = ea_html_node_next_sibling(c))
        collectTexts(c, desc, out);
}

static void collectScriptNodes(ea_html_node n, std::vector<ea_html_node>& out) {
    if (!n) return;
    const char* nm = ea_html_node_name(n);
    if (nm) {
        std::string name = nm;
        ea_html_free_string(nm);
        if (name == "script") { out.push_back(n); return; }
    }
    for (ea_html_node c = ea_html_node_first_child(n); c; c = ea_html_node_next_sibling(c))
        collectScriptNodes(c, out);
}

static std::string scriptText(ea_html_node n) {
    std::string s;
    for (ea_html_node c = ea_html_node_first_child(n); c; c = ea_html_node_next_sibling(c)) {
        int t = ea_html_node_type_of(c);
        if (t == EA_NODE_TEXT || t == EA_NODE_RAW_TEXT) {
            const char* txt = ea_html_node_text(c);
            if (txt) { s += txt; ea_html_free_string(txt); }
        }
    }
    return s;
}

static std::string stripQuery(const std::string& s) {
    size_t p = s.find_first_of("?#");
    return p == std::string::npos ? s : s.substr(0, p);
}

/* 把相对/绝对 URL 映射到镜像目录；非本地返回 okUrl=false */
static std::string resolveLocal(const std::string& baseDir, const std::string& rootDir,
                                const std::string& href, bool& okUrl) {
    okUrl = false;
    std::string rel = stripQuery(href);
    if (rel.empty()) return std::string();
    if (rel.rfind("http://", 0) == 0 || rel.rfind("https://", 0) == 0 ||
        rel.rfind("//", 0) == 0 || rel.rfind("data:", 0) == 0)
        return std::string();
    while (!rel.empty() && rel[0] == '.' && (rel[1] == '/' || rel[1] == '\\')) rel = rel.substr(2);
    std::string base = baseDir;
    if (!rel.empty() && (rel[0] == '/' || rel[0] == '\\')) {
        rel = rel.substr(1);
        if (!rootDir.empty()) base = rootDir;
    }
    okUrl = true;
    return base + rel;
}

static bool matchAny(const std::string& s, const std::string& patterns) {
    if (patterns.empty()) return false;
    size_t p = 0;
    while (p <= patterns.size()) {
        size_t q = patterns.find(',', p);
        std::string pat = patterns.substr(p, q == std::string::npos ? std::string::npos : q - p);
        if (!pat.empty() && s.find(pat) != std::string::npos) return true;
        if (q == std::string::npos) break;
        p = q + 1;
    }
    return false;
}

/* ---- 桌面测试宿主：目录选择/列举后端（验证 File System Access 接线） ---- */
struct EaDirTestCtx { std::string pickPath; };
static int eaTestDirPicker(void* ud, const char*, char* out, size_t cap) {
    EaDirTestCtx* c = (EaDirTestCtx*)ud;
    if (!c || c->pickPath.empty()) return -1;
    snprintf(out, cap, "%s", c->pickPath.c_str());
    return 1;
}
static int eaTestDirList(void*, const char* path, char** outJson) {
    std::string json = "[";
    bool first = true;
    try {
        for (auto& e : std::filesystem::directory_iterator(std::filesystem::u8path(path))) {
            std::string nm = e.path().filename().u8string();
            if (!first) json += ",";
            first = false;
            std::string esc;
            for (char ch : nm) { if (ch == '"' || ch == '\\') esc += '\\'; esc += ch; }
            json += "{\"n\":\"" + esc + "\",\"d\":" + (e.is_directory() ? "1" : "0") + "}";
        }
    } catch (...) { return -1; }
    json += "]";
    *outJson = (char*)malloc(json.size() + 1);
    if (!*outJson) return -1;
    memcpy(*outJson, json.c_str(), json.size() + 1);
    return 0;
}

static void runPageScripts(void* js, void* doc, const std::string& htmlPath,
                           const std::string& rootDir, double jsMs, const std::string& skipList) {
    std::vector<ea_html_node> scripts;
    collectScriptNodes(ea_html_doc_root(doc), scripts);
    std::string base = baseDirOf(htmlPath);
    int idx = 0, executed = 0, skipped = 0, dynN = 0;

    for (ea_html_node n : scripts) {
        ++idx;
        const char* pt = ea_html_node_attr(n, "type");
        std::string type = pt ? pt : "";
        if (pt) ea_html_free_string(pt);
        if (!type.empty()) {
            std::string tl = type;
            for (auto& ch : tl) ch = (char)tolower((unsigned char)ch);
            if (tl.find("javascript") == std::string::npos && tl.find("ecmascript") == std::string::npos) {
                printf("[JS] #%d skip type=%s\n", idx, type.c_str());
                ++skipped;
                continue;
            }
        }
        const char* ps = ea_html_node_attr(n, "src");
        std::string src = ps ? ps : "";
        if (ps) ea_html_free_string(ps);
        std::string code, label;
        if (!src.empty()) {
            bool local = false;
            std::string path = resolveLocal(base, rootDir, src, local);
            if (!local) { printf("[JS] #%d skip remote src=%s\n", idx, src.c_str()); ++skipped; continue; }
            bool okf = false;
            code = readAll(path.c_str(), okf);
            if (!okf) { printf("[JS] #%d MISS %s\n", idx, path.c_str()); ++skipped; continue; }
            label = src + " (" + std::to_string(code.size()) + "B)";
        } else {
            code = scriptText(n);
            if (code.empty()) continue;
            label = "inline " + std::to_string(code.size()) + "B";
        }
        char out[64];
        if (matchAny(label, skipList)) {
            printf("[JS] #%d skip user %s\n", idx, label.c_str());
            ++skipped;
            continue;
        }
        int rc = ea_js_eval(js, code.data(), code.size(), out, sizeof(out));
        if (rc == 0) {
            printf("[JS] #%d ok %s\n", idx, label.c_str());
            ++executed;
        } else {
            std::string err = ea_js_last_error(js);
            if (err.size() > 260) err = err.substr(0, 260) + "...";
            printf("[JS] #%d FAIL %s err=%s\n", idx, label.c_str(), err.c_str());
        }
    }

    /* JS 动态注入的 <script src> */
    char buf[1024];
    const char* dyn = nullptr;
    while ((dyn = ea_js_take_pending_script(js, buf, sizeof buf)) != nullptr) {
        ++dynN;
        bool local = false;
        std::string path = resolveLocal(base, rootDir, dyn, local);
        bool okf = false;
        std::string code;
        if (local) code = readAll(path.c_str(), okf);
        if (!okf) {
            printf("[JS] dyn#%d MISS src=%s path=%s\n", dynN, dyn, path.c_str());
            ea_js_fire_script_load(js);
            continue;
        }
        int rc = ea_js_eval(js, code.data(), code.size(), nullptr, 0);
        printf("[JS] dyn#%d %s src=%s\n", dynN, rc == 0 ? "ok" : "FAIL", dyn);
        ea_js_fire_script_load(js);
    }

    ea_js_run_jobs(js);
    ea_js_fire_window_event(js, "DOMContentLoaded");
    ea_js_fire_window_event(js, "load");
    ea_js_run_jobs(js);
    if (jsMs > 0) {
        for (double t = 0; t <= jsMs; t += 20.0) ea_js_tick(js, t);
        ea_js_run_jobs(js);
    } else {
        ea_js_tick(js, 0);
    }
    printf("[JS] scripts=%d executed=%d skipped=%d dynamic=%d\n", idx, executed, skipped, dynN);
}

/* ---------- 多标签 / 页面切换生命周期验证 ---------- */
static ea_html_node findNodeById(ea_html_node root, const char* want) {
    if (!root) return nullptr;
    const char* id = ea_html_node_attr(root, "id");
    bool hit = (id && strcmp(id, want) == 0);
    if (id) ea_html_free_string(id);
    if (hit) return root;
    for (ea_html_node c = ea_html_node_first_child(root); c; c = ea_html_node_next_sibling(c)) {
        ea_html_node r = findNodeById(c, want);
        if (r) return r;
    }
    return nullptr;
}

static int evalInt(void* js, const char* expr, int defv) {
    char out[64];
    if (ea_js_eval(js, expr, strlen(expr), out, sizeof out) != 0) return -1;
    return atoi(out);
}

static std::string evalStr(void* js, const char* expr) {
    char out[256]; out[0] = 0;
    if (ea_js_eval(js, expr, strlen(expr), out, sizeof out) != 0) return std::string();
    return std::string(out);
}

struct EaTestFileRoot { std::string root; };
static int eaTestFileReader(void* ud, const char* utf8Path, unsigned char** outBytes, size_t* outLen, char** outErr) {
    EaTestFileRoot* ctx = (EaTestFileRoot*)ud;
    if (outBytes) *outBytes = nullptr;
    if (outLen) *outLen = 0;
    if (outErr) *outErr = nullptr;
    std::string deny = "EA_LOCAL_FILE_DENIED";
    auto denyNow = [&]() -> int {
        if (outErr) *outErr = (char*)malloc(deny.size() + 1);
        if (outErr) memcpy(*outErr, deny.c_str(), deny.size() + 1);
        return 1;
    };
    if (!ctx || ctx->root.empty() || !utf8Path) return denyNow();
    /* 只允许 html 所在目录（以及 --root 指定目录）下的文件 */
    std::string p(utf8Path);
    for (auto& ch : p) if (ch == '\\') ch = '/';
    std::string r = ctx->root;
    for (auto& ch : r) if (ch == '\\') ch = '/';
    if (r.back() != '/') r += '/';
    if (p.size() < r.size() || p.compare(0, r.size(), r) != 0) return denyNow();
    FILE* f = fopen(utf8Path, "rb");
    if (!f) return denyNow();
    std::string all;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0) all.append(buf, n);
    fclose(f);
    unsigned char* b = (unsigned char*)malloc(all.empty() ? 1 : all.size());
    if (!b) return denyNow();
    if (!all.empty()) memcpy(b, all.data(), all.size());
    if (outBytes) *outBytes = b;
    if (outLen) *outLen = all.size();
    return 0;
}

static int runLifecycle(const std::string& html, const std::string& htmlPath,
                        const std::string& rootDir, double jsMs,
                        const std::string& skipList, int rounds) {
    struct Tab {
        void* doc = nullptr;
        void* js = nullptr;
    };
    std::string pageHtml = inlineLocalCss(html, htmlPath);
    static EaTestFileRoot lcFroot;
    {
        size_t slash = htmlPath.find_last_of("\\/");
        lcFroot.root = (slash == std::string::npos) ? std::string(".") : htmlPath.substr(0, slash);
    }
    auto openTab = [&](const std::string& url, int w, int hh) -> Tab {
        Tab t;
        t.doc = ea_html_doc_create();
        if (ea_html_doc_parse_utf8(t.doc, pageHtml.data(), pageHtml.size()) != 0) {
            ea_html_doc_destroy(t.doc); t.doc = nullptr; return t;
        }
        t.js = ea_js_create();
        if (!t.js) { ea_html_doc_destroy(t.doc); t.doc = nullptr; return t; }
        ea_js_bind_document(t.js, t.doc);
        ea_js_set_page_url(t.js, url.c_str());
        ea_js_set_viewport(t.js, w, hh, 0, 0);
        ea_js_set_file_reader(t.js, &lcFroot, eaTestFileReader);
        runPageScripts(t.js, t.doc, htmlPath, rootDir, jsMs, skipList);
        return t;
    };
    auto closeTab = [&](Tab& t) {
        if (t.js) { ea_js_destroy(t.js); t.js = nullptr; }
        if (t.doc) { ea_html_doc_destroy(t.doc); t.doc = nullptr; }
    };
    auto clickBtn = [&](Tab& t) {
        ea_html_node btn = findNodeById(ea_html_doc_root(t.doc), "btn");
        if (!btn) { printf("[LIFECYCLE] FAIL no #btn\n"); return false; }
        return ea_js_dispatch_click(t.js, btn) == 0;
    };

    printf("[LIFECYCLE] rounds=%d\n", rounds);
    for (int r = 0; r < rounds; ++r) {
        Tab a = openTab("https://a.example/", 360, 640);
        Tab b = openTab("https://b.example/", 720, 1280);
        if (!a.js || !b.js) { printf("[LIFECYCLE] FAIL create\n"); closeTab(a); closeTab(b); return 1; }
        /* 多标签页面状态必须按句柄隔离：url / 视口互不串用 */
        if (evalStr(a.js, "location.href") != "https://a.example/" ||
            evalInt(a.js, "innerWidth", -1) != 360 ||
            evalStr(b.js, "location.href") != "https://b.example/" ||
            evalInt(b.js, "innerWidth", -1) != 720) {
            printf("[LIFECYCLE] FAIL isolation A.url=%s A.w=%d B.url=%s B.w=%d\n",
                   evalStr(a.js, "location.href").c_str(), evalInt(a.js, "innerWidth", -1),
                   evalStr(b.js, "location.href").c_str(), evalInt(b.js, "innerWidth", -1));
            closeTab(a); closeTab(b); return 1;
        }
        /* 两个标签各自注册 click / load / timer */
        int la = evalInt(a.js, "window.__loaded", -1);
        int cb = evalInt(b.js, "window.__timer", -1);
        if (!clickBtn(a) || !clickBtn(b)) { printf("[LIFECYCLE] FAIL dispatch\n"); closeTab(a); closeTab(b); return 1; }
        int ca = evalInt(a.js, "window.__clicks", -1);
        int cbb = evalInt(b.js, "window.__clicks", -1);
        printf("[LIFECYCLE] r%d A loaded=%d timer=%d clicks=%d | B clicks=%d\n", r, la, cb, ca, cbb);
        if (ca != 1 || cbb != 1) { printf("[LIFECYCLE] FAIL counts\n"); closeTab(a); closeTab(b); return 1; }

        /* 关闭标签 A：B 的回调必须仍然有效（不能被 A 的销毁误释放） */
        closeTab(a);
        if (!clickBtn(b)) { printf("[LIFECYCLE] FAIL dispatch after close\n"); closeTab(b); return 1; }
        int c2 = evalInt(b.js, "window.__clicks", -1);
        printf("[LIFECYCLE] r%d after close A: B clicks=%d\n", r, c2);
        if (c2 != 2) { printf("[LIFECYCLE] FAIL survivor handler\n"); closeTab(b); return 1; }
        closeTab(b);
    }

    /* 重复 destroy：宿主若二次 close 同一句柄，必须安全返回 */
    Tab d = openTab("https://d.example/", 360, 640);
    if (!d.js) { printf("[LIFECYCLE] FAIL create double\n"); return 1; }
    ea_js_destroy(d.js);
    ea_js_destroy(d.js);
    ea_html_doc_destroy(d.doc);
    printf("[LIFECYCLE] double destroy ok\n");

    /* 同一运行时切换文档（前进/后退/重载）：旧页回调/定时器必须清掉 */
    void* jsx = ea_js_create();
    void* d1 = ea_html_doc_create();
    ea_html_doc_parse_utf8(d1, pageHtml.data(), pageHtml.size());
    ea_js_bind_document(jsx, d1);
    ea_js_set_page_url(jsx, "https://doc1.example/");
    runPageScripts(jsx, d1, htmlPath, rootDir, jsMs, skipList);
    ea_html_node b1 = findNodeById(ea_html_doc_root(d1), "btn");
    ea_js_dispatch_click(jsx, b1);
    int n1 = evalInt(jsx, "window.__clicks", -1);
    std::string u1 = evalStr(jsx, "location.href");
    void* d2 = ea_html_doc_create();
    ea_html_doc_parse_utf8(d2, pageHtml.data(), pageHtml.size());
    ea_js_bind_document(jsx, d2);       /* 切页：清旧回调/定时器 */
    ea_js_set_page_url(jsx, "https://doc2.example/");
    runPageScripts(jsx, d2, htmlPath, rootDir, jsMs, skipList);
    ea_html_node b2 = findNodeById(ea_html_doc_root(d2), "btn");
    ea_js_dispatch_click(jsx, b2);
    int n2 = evalInt(jsx, "window.__clicks", -1);
    std::string u2 = evalStr(jsx, "location.href");
    printf("[LIFECYCLE] navigate: doc1 clicks=%d url=%s doc2 clicks=%d url=%s\n",
           n1, u1.c_str(), n2, u2.c_str());
    ea_html_doc_destroy(d1);
    ea_js_destroy(jsx);
    ea_html_doc_destroy(d2);
    if (n1 != 1 || n2 != 1 || u1 != "https://doc1.example/" || u2 != "https://doc2.example/") {
        printf("[LIFECYCLE] FAIL navigate\n"); return 1;
    }

    printf("[LIFECYCLE] PASS\n");
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        printf("usage: feature_runner <html> [width] [height] [--t ms,ms] [--svg out.svg] [--hit x,y;x,y]\n"
               "       [--box id,id] [--select id,id] [--no-js] [--root dir] [--js-ms n] [--lifecycle rounds]\n"
               "       [--base url] [--css-res url=path] [--img url:w:h] [--img-fail url]\n");
        return 1;
    }
    /* 无缓冲输出：崩溃时也能看到最后执行到哪一步 */
    setvbuf(stdout, nullptr, _IONBF, 0);
    const char* htmlPath = argv[1];
    float vw = 1080, vh = 900;
    std::string times, svgPath, hits, boxes, rootDir, evalCode, skipList, htmlDump;
    std::string findText, picks;
    std::string selectIds, sysFontSpec;
    bool gapDump = false;
    std::vector<std::string> imgSizeSpecs;
    std::vector<std::string> imgFailUrls;
    bool crashRepro = false;
    bool tapRepro = false;
    std::string baseUrl;
    std::vector<std::pair<std::string, std::string>> cssResources;
    std::vector<std::pair<std::string, std::string>> fontResources;
    std::vector<std::string> fontFamilyReports;
    bool jsOn = true;
    double jsMs = 0;
    int texts = 0;
    int lifecycleRounds = 0;
    float scrollY = 0;
    std::string restyleSpec;
    int uaTier = 0;               /* 0=手机 1=电脑 */
    float deviceDpr = 1;
    bool forceZoom = false;
    bool forceZoomApi = false;    /* 走 ea_render_set_force_zoom，而不是 device_profile */
    float zoomScaleOpt = 0;       /* >0 时调用 ea_render_set_zoom_scale */
    int touchOverride = -1;       /* -1 按 UA 默认 */
    float desktopWidth = 1280;
    std::string activateSpec;
    std::string hoverId, focusId, checkedId, uncheckedId, activeId, targetId, visitedId;
    std::string selectionSpec, insetsSpec;
    std::string caretSpec, textRectSpec;
    std::string clickIds;
    std::string vvSpec, vvResizeSpec, dirPickPath;
    int uaSet = -1;
    int identityTierOpt = -1;     /* -1 保持引擎默认（1=均衡） */
    bool dirListTest = false;
    bool darkMode = false;
    bool darkToggle = false;
    bool shareTest = false;
    bool notifyTest = false;
    bool orientTest = false;
    bool vibrateTest = false;
    bool snapDump = false;
    bool svgFilterDump = false;
    for (int i = 2; i < argc; ++i) {
        if (!strcmp(argv[i], "--t") && i + 1 < argc) times = argv[++i];
        else if (!strcmp(argv[i], "--svg") && i + 1 < argc) svgPath = argv[++i];
        else if (!strcmp(argv[i], "--hit") && i + 1 < argc) hits = argv[++i];
        else if (!strcmp(argv[i], "--box") && i + 1 < argc) boxes = argv[++i];
        else if (!strcmp(argv[i], "--sysfont") && i + 1 < argc) sysFontSpec = argv[++i];
        else if (!strcmp(argv[i], "--gap-dump")) gapDump = true;
        else if (!strcmp(argv[i], "--img") && i + 1 < argc) imgSizeSpecs.push_back(argv[++i]);
        else if (!strcmp(argv[i], "--img-fail") && i + 1 < argc) imgFailUrls.push_back(argv[++i]);
        else if (!strcmp(argv[i], "--root") && i + 1 < argc) rootDir = argv[++i];
        else if (!strcmp(argv[i], "--skip") && i + 1 < argc) skipList = argv[++i];
        else if (!strcmp(argv[i], "--js-ms") && i + 1 < argc) jsMs = atof(argv[++i]);
        else if (!strcmp(argv[i], "--lifecycle") && i + 1 < argc) lifecycleRounds = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--eval") && i + 1 < argc) evalCode = argv[++i];
        else if (!strcmp(argv[i], "--texts") && i + 1 < argc) texts = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--base") && i + 1 < argc) baseUrl = argv[++i];
        else if (!strcmp(argv[i], "--html")) htmlDump = "1";
        else if (!strcmp(argv[i], "--find") && i + 1 < argc) findText = argv[++i];
        else if (!strcmp(argv[i], "--pick") && i + 1 < argc) picks = argv[++i];
        else if (!strcmp(argv[i], "--select") && i + 1 < argc) selectIds = argv[++i];
        else if (!strcmp(argv[i], "--crash-repro")) crashRepro = true;
        else if (!strcmp(argv[i], "--tap-repro")) tapRepro = true;
        else if (!strcmp(argv[i], "--scroll") && i + 1 < argc) scrollY = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "--restyle") && i + 1 < argc) restyleSpec = argv[++i];
        else if (!strcmp(argv[i], "--dark")) darkMode = true;
        else if (!strcmp(argv[i], "--insets") && i + 1 < argc) insetsSpec = argv[++i];
        else if (!strcmp(argv[i], "--selection") && i + 1 < argc) selectionSpec = argv[++i];
        else if (!strcmp(argv[i], "--ua") && i + 1 < argc) {
            std::string u = argv[++i];
            uaTier = (u == "desktop" || u == "pc") ? 1 : 0;
        }
        else if (!strcmp(argv[i], "--dpr") && i + 1 < argc) deviceDpr = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "--force-zoom")) forceZoom = true;
        else if (!strcmp(argv[i], "--force-zoom-api")) forceZoomApi = true;
        else if (!strncmp(argv[i], "--zoom-scale=", 13)) zoomScaleOpt = (float)atof(argv[i] + 13);
        else if (!strcmp(argv[i], "--touch") && i + 1 < argc) touchOverride = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--desktop-width") && i + 1 < argc) desktopWidth = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "--activate") && i + 1 < argc) activateSpec = argv[++i];
        else if (!strcmp(argv[i], "--click") && i + 1 < argc) clickIds = argv[++i];
        else if (!strcmp(argv[i], "--vv") && i + 1 < argc) vvSpec = argv[++i];
        else if (!strcmp(argv[i], "--vv-resize") && i + 1 < argc) vvResizeSpec = argv[++i];
        else if (!strcmp(argv[i], "--dir-pick") && i + 1 < argc) dirPickPath = argv[++i];
        else if (!strcmp(argv[i], "--dir-list")) dirListTest = true;
        else if (!strcmp(argv[i], "--dark-toggle")) darkToggle = true;
        else if (!strcmp(argv[i], "--share-test")) shareTest = true;
        else if (!strcmp(argv[i], "--notify-test")) notifyTest = true;
        else if (!strcmp(argv[i], "--orient-test")) orientTest = true;
        else if (!strcmp(argv[i], "--vibrate-test")) vibrateTest = true;
        else if (!strcmp(argv[i], "--tier") && i + 1 < argc) identityTierOpt = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--hover") && i + 1 < argc) hoverId = argv[++i];
        else if (!strcmp(argv[i], "--focus") && i + 1 < argc) focusId = argv[++i];
        else if (!strcmp(argv[i], "--caret") && i + 1 < argc) caretSpec = argv[++i];
        else if (!strcmp(argv[i], "--texrects") && i + 1 < argc) textRectSpec = argv[++i];
        else if (!strcmp(argv[i], "--checked") && i + 1 < argc) checkedId = argv[++i];
        else if (!strcmp(argv[i], "--unchecked") && i + 1 < argc) uncheckedId = argv[++i];
        else if (!strcmp(argv[i], "--active") && i + 1 < argc) activeId = argv[++i];
        else if (!strcmp(argv[i], "--target") && i + 1 < argc) targetId = argv[++i];
        else if (!strcmp(argv[i], "--visited") && i + 1 < argc) visitedId = argv[++i];
        else if (!strcmp(argv[i], "--css-res") && i + 1 < argc) {
            std::string spec = argv[++i];
            size_t eq = spec.find('=');
            if (eq == std::string::npos) {
                printf("[WARN] --css-res expects url=path, got %s\n", spec.c_str());
            } else {
                std::string url = spec.substr(0, eq), path = spec.substr(eq + 1);
                bool rok = false;
                std::string body = readAll(path.c_str(), rok);
                if (!rok) printf("[WARN] cannot read css-res %s\n", path.c_str());
                else cssResources.push_back({url, body});
            }
        }
        else if (!strcmp(argv[i], "--font") && i + 1 < argc) {
            std::string spec = argv[++i];
            size_t eq = spec.find('=');
            if (eq == std::string::npos) {
                printf("[WARN] --font expects url=path, got %s\n", spec.c_str());
            } else {
                std::string url = spec.substr(0, eq), path = spec.substr(eq + 1);
                bool rok = false;
                std::string body = readAll(path.c_str(), rok);
                if (!rok) printf("[WARN] cannot read font %s\n", path.c_str());
                else fontResources.push_back({url, body});
            }
        }
        else if (!strcmp(argv[i], "--font-family") && i + 1 < argc) fontFamilyReports.push_back(argv[++i]);
        else if (!strcmp(argv[i], "--snaps")) snapDump = true;
        else if (!strcmp(argv[i], "--svgfilters")) svgFilterDump = true;
        else if (!strcmp(argv[i], "--no-js")) jsOn = false;
        else if (vw == 1080 && vh == 900) {
            float a = (float)atof(argv[i]);
            if (a > 0) vw = a;
            if (i + 1 < argc) { float b = (float)atof(argv[i + 1]); if (b > 0) { vh = b; ++i; } }
        }
    }

    bool ok = false;
    std::string html = readAll(htmlPath, ok);
    if (!ok) { printf("[FAIL] cannot read %s\n", htmlPath); return 2; }

    if (lifecycleRounds > 0) {
        if (jsMs <= 0) jsMs = 300;   /* 让 setTimeout(100) 有机会执行 */
        return runLifecycle(html, htmlPath, rootDir, jsMs, skipList, lifecycleRounds);
    }
    html = inlineLocalCss(html, htmlPath);

    void* doc = ea_html_doc_create();
    int rc = ea_html_doc_parse_utf8(doc, html.data(), html.size());
    if (rc != 0) {
        printf("[FAIL] parse rc=%d\n", rc);
        ea_html_doc_destroy(doc);
        return 2;
    }

    /* ---- 渲染器 + 设备配置（先于页面脚本，保证 JS 看到一致的设备身份） ---- */
    void* r = ea_render_create();
    if (!baseUrl.empty()) ea_render_set_base_url(r, baseUrl.c_str());
    /* 宿主/资源层提供图片固有尺寸：--img url:w:h */
    for (auto& spec : imgSizeSpecs) {
        size_t c1 = spec.find(':');
        if (c1 == std::string::npos) continue;
        size_t c2 = spec.find(':', c1 + 1);
        if (c2 == std::string::npos) c2 = spec.find('x', c1 + 1);
        if (c2 == std::string::npos) continue;
        std::string url = spec.substr(0, c1);
        float iw = (float)atof(spec.substr(c1 + 1, c2 - c1 - 1).c_str());
        float ih = (float)atof(spec.substr(c2 + 1).c_str());
        ea_render_set_image_size(r, url.c_str(), iw, ih);
    }
    /* 资源层声明解码失败：绘制阶段应改画灰底占位，不再发 IMAGE */
    for (auto& u : imgFailUrls) ea_render_set_image_failed(r, u.c_str(), 1);
    for (auto& cr : cssResources) ea_render_add_style_resource(r, cr.first.c_str(), cr.second.c_str());
    for (auto& fr : fontResources)
        ea_render_add_font_resource(r, fr.first.c_str(), fr.second.data(), (int)fr.second.size());
    if (!sysFontSpec.empty()) {
        float a = 0.905f, d = -0.212f, lg = 0.033f, xh = 0.519f;
        sscanf(sysFontSpec.c_str(), "%f,%f,%f,%f", &a, &d, &lg, &xh);
        ea_font_metrics m{};
        m.unitsPerEm = 1000;
        m.ascent = a * 1000;
        m.descent = d * 1000;
        m.lineGap = lg * 1000;
        m.xHeight = xh * 1000;
        m.glyphCount = 0;
        m.codePoints = nullptr;
        m.advances = nullptr;
        ea_render_set_font_metrics(r, "", 0, &m);
    }
    ea_render_set_scroll_y(r, scrollY);
    {
        ea_device_profile profile;
        memset(&profile, 0, sizeof(profile));
        profile.uaTier = uaTier;
        profile.viewportW = vw;
        profile.viewportH = vh;
        profile.dpr = deviceDpr > 0 ? deviceDpr : 1;
        profile.forceZoom = forceZoom ? 1 : 0;
        profile.touch = touchOverride;
        profile.maxTouchPoints = 0;
        profile.desktopWidth = desktopWidth;
        ea_render_set_device_profile(r, &profile);
    }
    /* 宿主运行时开关路径：布局前调用 ea_render_set_force_zoom */
    if (forceZoomApi) ea_render_set_force_zoom(r, 1);
    /* <noscript>：宿主禁用脚本时应显示内容 */
    ea_render_set_scripts_enabled(r, jsOn ? 1 : 0);
    if (darkMode) ea_render_set_dark_mode(r, 1);
    if (!insetsSpec.empty()) {
        float t = 0, rr = 0, b = 0, l = 0;
        if (sscanf(insetsSpec.c_str(), "%f,%f,%f,%f", &t, &rr, &b, &l) >= 1)
            ea_render_set_safe_area_insets(r, t, rr, b, l);
        printf("[INSETS] %.0f,%.0f,%.0f,%.0f\n", t, rr, b, l);
    }

    /* ---- 页面 JS：执行脚本 → DOM 变更 → 再布局 ---- */
    void* js = nullptr;
    if (jsOn || !evalCode.empty()) {
        /* 宿主真实顺序：先布局取得 meta viewport，再同步给 JS（innerWidth 才是最终值） */
        ea_render_layout(r, doc, vw, vh);
        if (zoomScaleOpt > 0) ea_render_set_zoom_scale(r, zoomScaleOpt);
        int before = ea_html_doc_node_count(doc);
        js = ea_js_create();
        ea_js_bind_document(js, doc);
        ea_js_set_page_url(js, !baseUrl.empty() ? baseUrl.c_str() : htmlPath);
        {
            static EaTestFileRoot fr;
            if (!rootDir.empty()) fr.root = rootDir;
            else {
                size_t slash = std::string(htmlPath).find_last_of("\\/");
                fr.root = (slash == std::string::npos) ? std::string(".") : std::string(htmlPath).substr(0, slash);
            }
            ea_js_set_file_reader(js, &fr, eaTestFileReader);
        }
        if (ea_render_sync_device_to_js(r, js) != 0) printf("[DEVICE] JS sync failed\n");
        if (identityTierOpt >= 0) ea_js_set_identity_tier(js, identityTierOpt);
        {
            static EaDirTestCtx dctx;
            if (!dirPickPath.empty() || dirListTest) {
                dctx.pickPath = dirPickPath;
                ea_js_set_dir_picker_backend(js, &dctx, eaTestDirPicker);
                ea_js_set_dir_list_backend(js, nullptr, eaTestDirList);
            }
            if (!vvSpec.empty()) {
                double w = 0, hh = 0, sc = 1, ox = 0, oy = 0;
                sscanf(vvSpec.c_str(), "%lf,%lf,%lf,%lf,%lf", &w, &hh, &sc, &ox, &oy);
                ea_js_set_visual_viewport(js, w, hh, sc, ox, oy);
                printf("[VV] %.0f,%.0f,%.2f,%.0f,%.0f\n", w, hh, sc, ox, oy);
            }
            if (uaSet >= 0) {
                ea_js_set_user_activation(js, uaSet ? 1 : 0, uaSet ? 1 : 0);
                printf("[UA] host-set %d\n", uaSet ? 1 : 0);
            }
        }
        {
            EaHostHitCtx* hc = new EaHostHitCtx{ r, doc, vw, vh };
            ea_js_set_element_at_cb(js, hc, eaHostElementAt);
            ea_js_set_caret_at_cb(js, hc, eaHostCaretAt);
            ea_js_set_rect_provider(js, hc, eaHostRect);
            /* IO / matchMedia 依赖 JS 侧视口；测试宿主必须回填 */
            ea_js_set_viewport(js, vw, vh, 0, scrollY);
        }
        if (darkMode) ea_js_set_color_scheme(js, 1);
        if (shareTest) ea_js_set_share_backend(js, nullptr, [](void*, const char* j) -> int {
            printf("[SHARE] %s\n", j); return 1; });
        if (vibrateTest) ea_js_set_vibrate_backend(js, nullptr, [](void*, const char* j) -> int {
            printf("[VIBRATE] %s\n", j); return 1; });
        if (notifyTest) {
            static int notifySeq = 0;
            ea_js_set_notification_backend(js, nullptr,
                [](void*) -> int { return 1; },
                [](void*, const char* title, const char* opt) -> int {
                    printf("[NOTIFY] %s %s\n", title, opt); return ++notifySeq; },
                [](void*, int id) { printf("[NOTIFY-CLOSE] %d\n", id); });
        }
        if (orientTest) ea_js_set_orientation_lock_backend(js, nullptr, [](void*, const char* o) -> int {
            printf("[ORIENT] %s\n", o); return 1; });
        if (jsOn) runPageScripts(js, doc, htmlPath, rootDir, jsMs, skipList);
        if (darkToggle) {
            ea_js_set_color_scheme(js, 1);
            double base = jsMs > 0 ? jsMs : 0;
            for (double t = base; t <= base + 100; t += 20.0) ea_js_tick(js, t);
            printf("[DARK] toggled\n");
        }
        int after = ea_html_doc_node_count(doc);
        printf("[JS] nodes %d -> %d dirty=%d\n", before, after, ea_js_is_dirty(js));
        /* 模拟宿主真实点击（产生用户激活，用于 navigator.userActivation 等） */
        if (!clickIds.empty()) {
            std::stringstream ss(clickIds);
            std::string item;
            while (std::getline(ss, item, ',')) {
                if (item.empty()) continue;
                ea_html_node found = findNodeById(ea_html_doc_root(doc), item.c_str());
                if (!found) { printf("[CLICK] #%s (not found)\n", item.c_str()); continue; }
                int rc = ea_js_dispatch_click(js, found);
                printf("[CLICK] #%s rc=%d\n", item.c_str(), rc);
            }
            double base = jsMs > 0 ? jsMs : 0;
            for (double t = base; t <= base + 200; t += 20.0) ea_js_tick(js, t);
        }
        /* 模拟软键盘弹出：脚本注册监听后再下发新的视觉视口尺寸 */
        if (!vvResizeSpec.empty()) {
            double w = 0, hh = 0, sc = 1, ox = 0, oy = 0;
            sscanf(vvResizeSpec.c_str(), "%lf,%lf,%lf,%lf,%lf", &w, &hh, &sc, &ox, &oy);
            ea_js_set_visual_viewport(js, w, hh, sc, ox, oy);
            printf("[VV-RESIZE] %.0f,%.0f,%.2f,%.0f,%.0f\n", w, hh, sc, ox, oy);
            double base = jsMs > 0 ? jsMs : 0;
            for (double t = base; t <= base + 100; t += 20.0) ea_js_tick(js, t);
        }
        if (!evalCode.empty()) {
            char out[1024];
            int erc = ea_js_eval(js, evalCode.data(), evalCode.size(), out, sizeof(out));
            if (erc == 0) printf("[EVAL] %s\n", out);
            else printf("[EVAL] FAIL %s\n", ea_js_last_error(js));
        }
    }

    if (!htmlDump.empty()) {
        const char* s = ea_html_doc_serialize(doc);
        if (s) { printf("[HTML] %s\n", s); free((void*)s); }
    }

    if (texts > 0) {
        std::vector<BigTextInfo> list;
        collectTexts(ea_html_doc_root(doc), "", list);
        std::sort(list.begin(), list.end(), [](const BigTextInfo& a, const BigTextInfo& b) {
            return a.len > b.len;
        });
        printf("[TEXTS] nodes=%d\n", (int)list.size());
        int lim = (int)list.size() < texts ? (int)list.size() : texts;
        for (int i = 0; i < lim; ++i) {
            printf("[TEXT] len=%d parent=%s preview=\"%s\"\n",
                   list[i].len, list[i].parent.c_str(), list[i].preview.c_str());
        }
    }

    auto layoutT0 = std::chrono::steady_clock::now();
    rc = ea_render_layout(r, doc, vw, vh);
    double layoutMs = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - layoutT0).count();
    printf("[LAYOUT] rc=%d viewport=%.0fx%.0f contentHeight=%.1f\n",
           rc, vw, vh, ea_render_content_height(r));
    {
        ea_viewport_state vs;
        if (ea_render_get_viewport_state(r, &vs) == 0) {
            printf("[DEVICE] ua=%s layout=%.0fx%.0f device=%.0fx%.0f scale=%.3f dpr=%.1f touch=%d mtp=%d meta=%d(%.0f) zoom=%s min=%.2f max=%.2f\n",
                   vs.uaTier ? "desktop" : "mobile", vs.layoutW, vs.layoutH,
                   vs.deviceW, vs.deviceH, vs.pageScale, vs.dpr, vs.touch, vs.maxTouchPoints,
                   vs.metaWidth > 0 ? 1 : 0, vs.metaWidth,
                   vs.zoomForced ? "forced" : (vs.userScalable ? "allowed" : "blocked"),
                   vs.minScale, vs.maxScale);
        }
    }
    printf("[PERF] layout_ms=%.2f style_ms=%.2f rules=%d candidates=%d\n",
           layoutMs, ea_render_style_ms(r), ea_render_rule_count(r),
           ea_render_candidate_checks(r));
    if (!caretSpec.empty()) {
        float x = 0, y = 0;
        if (sscanf(caretSpec.c_str(), "%f,%f", &x, &y) >= 2) {
            ea_html_node cn = nullptr;
            int off = 0;
            float cx = 0, cy = 0, chh = 0;
            int okc = ea_render_caret_rect(r, x, y, &cn, &off, &cx, &cy, &chh);
            printf("[CARET] ok=%d node=%s off=%d x=%.1f y=%.1f h=%.1f\n",
                   okc, (okc && cn) ? ea_html_node_name(cn) : "-", off, cx, cy, chh);
        }
    }
    if (!textRectSpec.empty()) {
        size_t c1 = textRectSpec.find(',');
        size_t c2 = c1 == std::string::npos ? std::string::npos : textRectSpec.find(',', c1 + 1);
        if (c1 != std::string::npos && c2 != std::string::npos) {
            std::string id = textRectSpec.substr(0, c1);
            int st0 = atoi(textRectSpec.substr(c1 + 1, c2 - c1 - 1).c_str());
            int en0 = atoi(textRectSpec.substr(c2 + 1).c_str());
            ea_html_node tn = ea_html_get_by_id(doc, id.c_str());
            float rects[128];
            int nr = ea_render_text_rects(r, tn, st0, en0, rects, 128);
            printf("[TEXTRECTS] id=%s range=%d..%d n=%d", id.c_str(), st0, en0, nr);
            for (int ri = 0; ri < nr; ++ri)
                printf(" (%.1f,%.1f,%.1f,%.1f)", rects[ri * 4], rects[ri * 4 + 1],
                       rects[ri * 4 + 2], rects[ri * 4 + 3]);
            printf("\n");
            if (nr > 0) {
                ea_render_set_selection(r, rects, nr);
                printf("[TEXTSEL] set %d rect(s)\n", nr);
            }
        }
    }
    if (!selectionSpec.empty()) {
        float x = 0, y = 0, w = 0, h = 0;
        if (sscanf(selectionSpec.c_str(), "%f,%f,%f,%f", &x, &y, &w, &h) >= 4) {
            float rects[4] = {x, y, w, h};
            ea_render_set_selection(r, rects, 1);
            printf("[SELECTION] rect=%.1f,%.1f,%.1f,%.1f\n", x, y, w, h);
        }
    }
    for (auto& fam : fontFamilyReports) {
        float a = 0, d = 0, g = 0;
        int okFont = ea_render_font_metrics(r, fam.c_str(), 0, &a, &d, &g);
        printf("[FONT] family=%s loaded=%d ascent=%.4f descent=%.4f lineGap=%.4f normalLine=%.4f\n",
               fam.c_str(), okFont, a, d, g, okFont ? (a - d + (g > 0 ? g : 0)) : 0.0f);
    }
    if (snapDump) {
        int sc = ea_render_scroll_snap_count(r);
        printf("[SNAPS] count=%d\n", sc);
        for (int si = 0; si < sc; ++si) {
            ea_scroll_snap sn;
            if (!ea_render_scroll_snap_at(r, si, &sn)) continue;
            printf("[SNAP %d] box=%.1f,%.1f,%.1f,%.1f x=(en=%d strict=%d n=%d)",
                   si, sn.x, sn.y, sn.w, sn.h, sn.xAxis.enabled, sn.xAxis.strictness,
                   sn.xAxis.pointCount);
            for (int pi = 0; pi < sn.xAxis.pointCount; ++pi)
                printf(" %.1f/%s%s", sn.xAxis.points[pi].pos,
                       sn.xAxis.points[pi].align == 1 ? "start" :
                       sn.xAxis.points[pi].align == 2 ? "end" :
                       sn.xAxis.points[pi].align == 3 ? "center" : "none",
                       sn.xAxis.points[pi].stop ? "/stop" : "");
            printf(" y=(en=%d strict=%d n=%d)", sn.yAxis.enabled, sn.yAxis.strictness,
                   sn.yAxis.pointCount);
            for (int pi = 0; pi < sn.yAxis.pointCount; ++pi)
                printf(" %.1f/%s%s", sn.yAxis.points[pi].pos,
                       sn.yAxis.points[pi].align == 1 ? "start" :
                       sn.yAxis.points[pi].align == 2 ? "end" :
                       sn.yAxis.points[pi].align == 3 ? "center" : "none",
                       sn.yAxis.points[pi].stop ? "/stop" : "");
            printf("\n");
        }
    }
    if (svgFilterDump) {
        int fc = ea_render_svg_filter_count(r);
        printf("[SVGFILTERS] count=%d\n", fc);
        for (int fi = 0; fi < fc; ++fi) {
            ea_svg_filter_def fd;
            if (!ea_render_svg_filter_at(r, fi, &fd)) continue;
            printf("[SVGFILTER %d] region=%.2f,%.2f,%.2f,%.2f prims=%d",
                   fi, fd.regionX, fd.regionY, fd.regionW, fd.regionH, fd.primCount);
            for (int pi = 0; pi < fd.primCount; ++pi) {
                const ea_svg_filter_prim& pr = fd.prims[pi];
                if (pr.type == 0) printf(" blur(%.2f/%.2f)", pr.a, pr.b);
                else if (pr.type == 1) printf(" offset(%.1f/%.1f)", pr.a, pr.b);
                else if (pr.type == 2) printf(" cmat(kind=%.0f,arg=%.1f,m0=%.3f)", pr.a, pr.b, pr.m[0]);
                else if (pr.type == 3) {
                    printf(" merge[");
                    for (int mi = 0; mi < pr.mergeCount; ++mi)
                        printf("%s%d", mi ? "," : "", pr.mergeIndex[mi]);
                    printf("]");
                } else if (pr.type == 4) {
                    printf(" blend(mode=%d,in=%d,in2=%d)", pr.blendMode, pr.inIndex, pr.in2Index);
                } else if (pr.type == 5) {
                    printf(" composite(op=%d,in=%d,in2=%d,k=%.2f/%.2f/%.2f/%.2f)",
                           pr.op, pr.inIndex, pr.in2Index, pr.k[0], pr.k[1], pr.k[2], pr.k[3]);
                } else if (pr.type == 6) {
                    printf(" tile(in=%d)", pr.inIndex);
                }
            }
            printf("\n");
        }
    }
    if (!activateSpec.empty()) {
        float ax = 0, ay = 0;
        sscanf(activateSpec.c_str(), "%f,%f", &ax, &ay);
        ea_html_node an = ea_render_hit_test(r, ax, ay);
        int arc = ea_render_activate(r, an);
        printf("[ACTIVATE] at=%.0f,%.0f node=%s rc=%d contentHeight=%.1f\n",
               ax, ay, an ? ea_html_node_name(an) : "(none)", arc,
               ea_render_content_height(r));
    }
    if (!checkedId.empty() || !uncheckedId.empty()) {
        ea_html_node cn = checkedId.empty() ? nullptr : ea_html_get_by_id(doc, checkedId.c_str());
        ea_html_node un = uncheckedId.empty() ? nullptr : ea_html_get_by_id(doc, uncheckedId.c_str());
        if (cn) ea_render_set_checked(r, cn, 1);
        if (un) ea_render_set_checked(r, un, 0);
        printf("[CHECKED] on=%s(%d) off=%s(%d)\n",
               checkedId.c_str(), cn ? 1 : 0, uncheckedId.c_str(), un ? 1 : 0);
    }
    if (!activeId.empty()) {
        ea_html_node an = ea_html_get_by_id(doc, activeId.c_str());
        ea_render_set_active(r, an);
        printf("[ACTIVE] id=%s found=%d contentHeight=%.1f\n",
               activeId.c_str(), an ? 1 : 0, ea_render_content_height(r));
    }
    if (!targetId.empty()) {
        ea_html_node tn = ea_html_get_by_id(doc, targetId.c_str());
        ea_render_set_target(r, tn);
        printf("[TARGET] id=%s found=%d contentHeight=%.1f\n",
               targetId.c_str(), tn ? 1 : 0, ea_render_content_height(r));
    }
    if (!visitedId.empty()) {
        ea_html_node vn = ea_html_get_by_id(doc, visitedId.c_str());
        ea_render_set_visited(r, vn, 1);
        printf("[VISITED] id=%s found=%d contentHeight=%.1f\n",
               visitedId.c_str(), vn ? 1 : 0, ea_render_content_height(r));
    }
    if (!hoverId.empty()) {
        ea_html_node hn = ea_html_get_by_id(doc, hoverId.c_str());
        ea_render_set_hover(r, hn);
        printf("[HOVER] id=%s found=%d contentHeight=%.1f\n",
               hoverId.c_str(), hn ? 1 : 0, ea_render_content_height(r));
    }
    if (!focusId.empty()) {
        ea_html_node fn = ea_html_get_by_id(doc, focusId.c_str());
        ea_render_set_focus(r, fn);
        printf("[FOCUS] id=%s found=%d contentHeight=%.1f\n",
               focusId.c_str(), fn ? 1 : 0, ea_render_content_height(r));
    }
    printf("[GAP] unsupported_decls=%d unknown_props=%d ignored_conds=%d dropped_parts=%d unresolved_imports=%d\n",
           ea_render_unsupported_decls(r), ea_render_unknown_prop_count(r),
           ea_render_ignored_selector_conds(r), ea_render_dropped_selector_parts(r),
           ea_render_unresolved_imports(r));
    if (gapDump) {
        int un = ea_render_unknown_prop_count(r);
        std::vector<std::pair<int, std::string>> list;
        for (int i = 0; i < un; ++i) {
            const char* nm = nullptr; int cnt = 0;
            if (ea_render_unknown_prop_at(r, i, &nm, &cnt) && nm) list.push_back({cnt, nm});
        }
        std::sort(list.begin(), list.end(),
                  [](const std::pair<int, std::string>& a, const std::pair<int, std::string>& b) {
                      return a.first > b.first;
                  });
        printf("[GAP-TOP] count=%d\n", (int)list.size());
        for (size_t i = 0; i < list.size() && i < 20; ++i)
            printf("[UNKNOWN] %s=%d\n", list[i].second.c_str(), list[i].first);
    }

    /* 二次样式：模拟 JS/交互改样式后重新布局，用于验证 transition */
    if (!restyleSpec.empty()) {
        size_t eq = restyleSpec.find('=');
        if (eq != std::string::npos) {
            std::string id = restyleSpec.substr(0, eq);
            std::string css = restyleSpec.substr(eq + 1);
            ea_html_node node = ea_html_get_by_id(doc, id.c_str());
            if (node) {
                ea_html_node_set_attr(node, "style", css.c_str());
                int rc2 = ea_render_layout(r, doc, vw, vh);
                printf("[RELAYOUT] id=%s style=\"%s\" rc=%d contentHeight=%.1f\n",
                       id.c_str(), css.c_str(), rc2, ea_render_content_height(r));
            } else {
                printf("[RELAYOUT] id=%s not found\n", id.c_str());
            }
        }
    }

    int n = ea_render_draw_count(r);
    /* 类型分项：真实站点基线要能核对 TEXT/RECT/IMAGE/BORDER/CLIP 是否被误改 */
    int cText = 0, cRect = 0, cImage = 0, cBorder = 0, cClip = 0;
    for (int i = 0; i < n; ++i) {
        const ea_draw_cmd* c = ea_render_draw_at(r, i);
        if (!c) continue;
        switch (c->type) {
            case EA_DRAW_TEXT: ++cText; break;
            case EA_DRAW_RECT: ++cRect; break;
            case EA_DRAW_IMAGE: ++cImage; break;
            case EA_DRAW_BORDER: ++cBorder; break;
            case EA_DRAW_CLIP_PUSH:
            case EA_DRAW_CLIP_POP: ++cClip; break;
            default: break;
        }
    }
    printf("[CMDS] count=%d TEXT=%d RECT=%d IMAGE=%d BORDER=%d CLIP=%d\n",
           n, cText, cRect, cImage, cBorder, cClip);
    for (int i = 0; i < n; ++i) {
        const ea_draw_cmd* c = ea_render_draw_at(r, i);
        if (!c) continue;
        printf("[%02d] %-6s x=%7.1f y=%7.1f w=%7.1f h=%7.1f color=%08X op=%.2f r=%.1f",
               i, typeName(c->type), c->x, c->y, c->w, c->h, c->color, c->opacity, c->radius);
        if (c->radiusX[0] || c->radiusX[1] || c->radiusX[2] || c->radiusX[3] ||
            c->radiusY[0] || c->radiusY[1] || c->radiusY[2] || c->radiusY[3])
            printf(" r4=[%.1f/%.1f %.1f/%.1f %.1f/%.1f %.1f/%.1f]",
                   c->radiusX[0], c->radiusY[0], c->radiusX[1], c->radiusY[1],
                   c->radiusX[2], c->radiusY[2], c->radiusX[3], c->radiusY[3]);
        if (c->type == EA_DRAW_TEXT) {
            printf(" size=%.1f bold=%d align=%d text=\"%s\"", c->textSize, c->bold, c->align,
                   c->text ? c->text : "");
        } else if (c->type == EA_DRAW_BORDER) {
            printf(" bw=%.1f", c->textSize);
        } else if (c->type == EA_DRAW_SELECTION) {
            printf(" selText=%08X", c->selTextColor);
        } else if (c->type == EA_DRAW_CLIP_PUSH) {
            printf(" clipKind=%d rect=(%.1f,%.1f,%.1f,%.1f) polyN=%d",
                   c->clipKind, c->clipRectX, c->clipRectY, c->clipRectW, c->clipRectH,
                   c->clipPolyCount);
            for (int pi = 0; pi < c->clipPolyCount && pi < 8; ++pi)
                printf("%s(%.1f,%.1f)", pi ? "," : " poly=[", c->clipPolyX[pi], c->clipPolyY[pi]);
            if (c->clipPolyCount > 0) printf("]");
            if (c->maskImage && c->maskImage[0])
                printf(" mask=\"%s\" mode=%d rep=%d sizeKind=%d size=(%.1f,%.1f) pos=(%.0f%%,%.0f%%)",
                       c->maskImage, c->maskMode, c->maskRepeat, c->maskSizeKind,
                       c->maskSizeW, c->maskSizeH, c->maskPosX, c->maskPosY);
        } else if (c->type == EA_DRAW_VIDEO || c->videoLayer) {
            printf(" video node=%d fit=%d pos=%.0f%%/%.0f%%",
                   c->videoNode, c->videoFit, c->videoPosX, c->videoPosY);
        } else if (c->type == EA_DRAW_SUBDOC) {
            printf(" subdoc src=\"%s\"", c->src ? c->src : "");
        } else if (c->type == EA_DRAW_FILTER) {
            if (c->svgFilterIndex > 0) printf(" svgFilter=%d", c->svgFilterIndex - 1);
        }
        if (c->bgLocal) printf(" bgLocal=1");
        if (c->bgFixed) printf(" bgFixed=1");
        if (c->shadowColor >> 24) printf(" shadow=(%.0f,%.0f,%.0f,%08X)",
                                         c->shadowDx, c->shadowDy, c->shadowBlur, c->shadowColor);
        for (int si = 0; si < c->shadowCount && si < 4; ++si) {
            printf(" sh%d=(%.0f,%.0f,%.0f,%.0f,%08X%s)", si,
                   c->shadowOffX[si], c->shadowOffY[si], c->shadowBlurR[si],
                   c->shadowSpread[si], c->shadowLayerColor[si],
                   c->shadowInset[si] ? ",inset" : "");
        }
        if (c->filterBlur > 0) printf(" blur=%.1f", c->filterBlur);
        if (c->filterCount > 0) {
            printf(" filters=[");
            for (int fi = 0; fi < c->filterCount; ++fi) {
                if (fi) printf(",");
                printf("%d:%.2f", c->filterType[fi], c->filterValue[fi]);
                if (c->filterType[fi] == 9)
                    printf("/%.1f/%.1f/%08X", c->filterValue2[fi], c->filterValue3[fi],
                           c->filterColor[fi]);
            }
            printf("]");
        }
        if (c->backdropFilterCount > 0) {
            printf(" backdrop=[");
            for (int fi = 0; fi < c->backdropFilterCount; ++fi) {
                if (fi) printf(",");
                printf("%d:%.2f", c->backdropFilterType[fi], c->backdropFilterValue[fi]);
                if (c->backdropFilterType[fi] == 9)
                    printf("/%.1f/%.1f/%08X", c->backdropFilterValue2[fi],
                           c->backdropFilterValue3[fi], c->backdropFilterColor[fi]);
            }
            printf("] area=(%.1f,%.1f,%.1f,%.1f", c->backdropX, c->backdropY,
                   c->backdropW, c->backdropH);
            if (c->backdropRadiusX[0] || c->backdropRadiusY[0])
                printf(" radius=%.1f/%.1f", c->backdropRadiusX[0], c->backdropRadiusY[0]);
            printf(")");
        } else if (c->backdropBlur > 0) printf(" backdrop=%.1f", c->backdropBlur);
        if (c->gradientType) printf(" gradient=%d angle=%d spread=%d %08X->%08X",
                                    c->gradientType, c->gradientAngle, c->gradientSpread,
                                    c->gradientColor0, c->gradientColor1);
        if (c->gradientType && c->type != EA_DRAW_CONIC && c->gradStopCount > 0) {
            printf(" gstops=[");
            for (int gi = 0; gi < c->gradStopCount; ++gi) {
                if (gi) printf(",");
                printf("%.2f:%08X", c->gradStopPos[gi], c->gradStopColor[gi]);
            }
            printf("]");
            if (c->gradientType == 2)
                printf(" rc=(%.2f,%.2f) rr=(%.2f,%.2f)",
                       c->gradientCx, c->gradientCy, c->gradientRx, c->gradientRy);
        }
        if (c->type == EA_DRAW_CONIC && c->conicStopCount > 0) {
            printf(" stops=[");
            for (int ci = 0; ci < c->conicStopCount; ++ci) {
                if (ci) printf(",");
                printf("%.0fdeg:%08X", c->conicStopPos[ci], c->conicStopColor[ci]);
            }
            printf("]");
        }
        if (c->hasTransform) printf(" tf=(%.0f,%.0f s%.2f,%.2f r%.0f skew%.1f)",
                                    c->tx, c->ty, c->scaleX, c->scaleY, c->rotate, c->skewX);
        if (c->bgImage && c->bgImage[0]) printf(" bg=%s", c->bgImage);
        if (c->bgFixed) printf(" fixed=1");
        if (c->bgTileValid) printf(" tile=(%.1f,%.1f,%.1f,%.1f)",
                                   c->bgTileX, c->bgTileY, c->bgTileW, c->bgTileH);
        if (c->blendMode) printf(" blend=%d", c->blendMode);
        if (c->type == EA_DRAW_IMAGE && (c->objectFit || c->srcW > 0))
            printf(" fit=%d pos=%.0f%%/%.0f%% src=(%.1f,%.1f,%.1f,%.1f)",
                   c->objectFit, c->objectPosX, c->objectPosY,
                   c->srcX, c->srcY, c->srcW, c->srcH);
        printf("\n");
    }
    if (!boxes.empty()) {
        printf("[BOX]\n");
        std::stringstream ss(boxes);
        std::string item;
        while (std::getline(ss, item, ',')) {
            ea_html_node found = nullptr;
            std::function<void(ea_html_node)> walk = [&](ea_html_node n) {
                if (!n || found) return;
                const char* nid = ea_html_node_attr(n, "id");
                if (nid && item == nid) { found = n; ea_html_free_string(nid); return; }
                if (nid) ea_html_free_string(nid);
                for (ea_html_node c = ea_html_node_first_child(n); c; c = ea_html_node_next_sibling(c))
                    walk(c);
            };
            walk(ea_html_doc_root(doc));
            if (found) {
                float x = 0, y = 0, w = 0, h = 0;
                ea_render_node_box(r, found, &x, &y, &w, &h);
                printf("  #%s => x=%.1f y=%.1f w=%.1f h=%.1f\n", item.c_str(), x, y, w, h);
                ea_scroll_info si{};
                if (ea_render_get_scroll_info(r, found, &si)) {
                    printf("  #%s scroll => sx=%d sy=%d content=%.1fx%.1f view=%.1fx%.1f\n",
                           item.c_str(), si.scrollableX, si.scrollableY,
                           si.contentW, si.contentH, si.viewW, si.viewH);
                }
            } else {
                printf("  #%s => (not found)\n", item.c_str());
            }
        }
    }

    /* <select> 数据接口：打印 option 快照 */
    if (!selectIds.empty()) {
        std::stringstream ss(selectIds);
        std::string item;
        while (std::getline(ss, item, ',')) {
            ea_html_node found = nullptr;
            std::function<void(ea_html_node)> walk = [&](ea_html_node n) {
                if (!n || found) return;
                const char* nid = ea_html_node_attr(n, "id");
                if (nid && item == nid) { found = n; ea_html_free_string(nid); return; }
                if (nid) ea_html_free_string(nid);
                for (ea_html_node c = ea_html_node_first_child(n); c; c = ea_html_node_next_sibling(c))
                    walk(c);
            };
            walk(ea_html_doc_root(doc));
            if (!found) { printf("[SELECT] #%s => (not found)\n", item.c_str()); continue; }
            ea_select_option opts[64];
            int total = ea_render_select_info(r, found, opts, 64);
            printf("[SELECT] id=%s total=%d\n", item.c_str(), total);
            int lim = total < 64 ? total : 64;
            for (int i = 0; i < lim; ++i) {
                printf("  #%d text=\"%s\" value=\"%s\" sel=%d dis=%d group=%d groupName=\"%s\"\n",
                       opts[i].index, opts[i].text, opts[i].value,
                       opts[i].selected, opts[i].disabled,
                       opts[i].groupIndex, opts[i].group);
            }
        }
    }

    /* 命中测试 */
    if (!hits.empty()) {
        printf("[HIT]\n");
        std::stringstream ss(hits);
        std::string item;
        while (std::getline(ss, item, ';')) {
            float x = 0, y = 0;
            if (sscanf(item.c_str(), "%f,%f", &x, &y) != 2) continue;
            ea_html_node h = ea_render_hit_test(r, x, y);
            const char* nm = h ? ea_html_node_name(h) : "(null)";
            const char* id = h ? ea_html_node_attr(h, "id") : nullptr;
            printf("  (%.0f,%.0f) => <%s id=%s>\n", x, y, nm ? nm : "?", id ? id : "");
            /* 注意：“(null)”是字面量，只有 API 返回的字符串才能 free */
            if (h && nm) ea_html_free_string(nm);
            if (id) ea_html_free_string(id);
        }
    }

    /* 页面内查找：逐处命中矩形 */
    if (!findText.empty()) {
        printf("[FIND] text=%s\n", findText.c_str());
        int n = 0;
        int total = ea_render_find_text(r, findText.c_str(),
            [](void* u, float x, float y, float w, float h) -> int {
                int* cnt = (int*)u;
                ++*cnt;
                printf("  hit%d => x=%.1f y=%.1f w=%.1f h=%.1f\n", *cnt, x, y, w, h);
                return 0;
            }, &n);
        printf("  total=%d\n", total);
    }

    /* 坐标选元素（广告标注） */
    if (!picks.empty()) {
        printf("[PICK]\n");
        std::stringstream ss(picks);
        std::string item;
        while (std::getline(ss, item, ';')) {
            float x = 0, y = 0; int prefer = 0;
            if (sscanf(item.c_str(), "%f,%f,%d", &x, &y, &prefer) < 2) continue;
            ea_html_node h = ea_render_element_at(r, x, y, prefer);
            const char* nm = h ? ea_html_node_name(h) : nullptr;
            const char* id = h ? ea_html_node_attr(h, "id") : nullptr;
            float bx = 0, by = 0, bw = 0, bh = 0;
            if (h) ea_render_node_box(r, h, &bx, &by, &bw, &bh);
            printf("  (%.0f,%.0f) prefer=%d => <%s id=%s> box=(%.1f,%.1f,%.1f,%.1f)\n",
                   x, y, prefer, nm ? nm : "(null)", id ? id : "",
                   bx, by, bw, bh);
            if (nm) ea_html_free_string(nm);
            if (id) ea_html_free_string(id);
        }
    }

    /* 模拟 JsView.handleTap 的 input/textarea 补判路径（tombstone 里的 rs.hitTest 崩溃）：
     * 宿主分配定长数组 -> native 只填 total 项 -> Java 端按数组长度遍历整段，
     * total 之外的槽位若是脏值（如 1.0f 的位模式 0x3f800000），nodeAttr 解引用即 SIGSEGV。
     * 修复前本段会崩溃，修复后应全部为 0 并被跳过。 */
    if (tapRepro) {
        printf("[TAP-REPRO]\n");
        ea_html_node hit = ea_render_hit_test(r, 900, 800);
        printf("  blank hit=%s\n", hit ? "node" : "(null)");
        ea_html_node slots[32];
        for (int i = 0; i < 32; ++i)
            slots[i] = (ea_html_node)(intptr_t)0x3f800000ULL;   /* 1.0f 的位模式 */
        int total = ea_html_query_selector_all(doc, "input", slots, 32);
        printf("  querySelectorAll(input) total=%d cap=32\n", total);
        int used = 0, dirty = 0;
        for (int i = 0; i < 32; ++i) {
            ea_html_node n = slots[i];
            if (!n) continue;
            ++used;
            if ((uintptr_t)n == (uintptr_t)0x3f800000ULL) ++dirty;
            /* 与 Java 端完全一致：不判脏，直接解引用；修复前这里就是 SIGSEGV 点 */
            const char* ty = ea_html_node_attr(n, "type");
            const char* nm = ea_html_node_name(n);
            printf("  slot[%d] type=%s name=%s\n", i, ty ? ty : "(null)", nm ? nm : "(null)");
            if (ty) ea_html_free_string(ty);
            if (nm) ea_html_free_string(nm);
        }
        printf("  non-null slots=%d dirty=%d\n", used, dirty);
    }

    /* 崩溃复现：点击空白 / DOM 已销毁 / Renderer 已销毁 */
    if (crashRepro) {
        printf("[CRASH-REPRO]\n");
        ea_html_node live = ea_render_hit_test(r, 900, 800);
        printf("  live blank hit=%s\n", live ? "node" : "(null)");
        ea_render_detach_dom(r);
        ea_html_node det = ea_render_hit_test(r, 10, 10);
        int detFind = ea_render_find_text(r, "x", [](void*, float, float, float, float) -> int { return 0; }, nullptr);
        ea_render_relayout(r);
        printf("  detached hit=%s find=%d relayout=%d\n", det ? "node" : "(null)", detFind, ea_render_content_height(r) >= 0);

        /* 生产场景：宿主销毁文档却没有调用 detach_dom，之后仍点空白区域。
         * 旧实现会在 hitTest 里解引用已释放的布局节点 dom（rs.hitTest SIGSEGV）。 */
        void* d2 = ea_html_doc_create();
        ea_html_doc_parse_utf8(d2, html.data(), html.size());
        void* r2 = ea_render_create();
        ea_render_layout(r2, d2, vw, vh);
        ea_html_doc_destroy(d2);   /* 引擎自动断开 r2 */
        ea_html_node deadDomHit = ea_render_hit_test(r2, 900, 800);
        int deadDomFind = ea_render_find_text(r2, "blank",
            [](void*, float, float, float, float) -> int { return 0; }, nullptr);
        float bbx = 1, bby = 1, bbw = 1, bbh = 1;
        int deadBox = ea_render_node_box(r2, nullptr, &bbx, &bby, &bbw, &bbh);
        ea_render_element_at(r2, 150, 60, 0);
        printf("  doc-destroyed hit=%s find=%d nodebox=%d pick_ok\n",
               deadDomHit ? "node" : "(null)", deadDomFind, deadBox);
        ea_render_destroy(r2);
        void* dead = ea_render_create();
        ea_render_destroy(dead);
        ea_html_node stale = ea_render_hit_test(dead, 10, 10);
        float ch = ea_render_content_height(dead);
        int staleFind = ea_render_find_text(dead, "x", [](void*, float, float, float, float) -> int { return 0; }, nullptr);
        ea_render_destroy(dead);   /* 重复销毁应被忽略 */
        printf("  stale hit=%s h=%.1f find=%d ok\n", stale ? "node" : "(null)", ch, staleFind);
    }

    /* 动画逐帧：打印每帧的绘制指令（仅含 transform/opacity 变化部分） */
    if (!times.empty()) {
        std::stringstream ss(times);
        std::string item;
        while (std::getline(ss, item, ',')) {
            double ms = atof(item.c_str());
            int act = ea_render_update_animations(r, ms);
            /* 动画只改了 style，需要重新生成绘制指令才能看到结果 */
            ea_render_refresh_draw(r);
            printf("[ANIM t=%.0fms] active=%d\n", ms, act);
            int m = ea_render_draw_count(r);
            for (int i = 0; i < m; ++i) {
                const ea_draw_cmd* c = ea_render_draw_at(r, i);
                if (!c || c->type != EA_DRAW_RECT) continue;
                printf("   RECT#%d w=%.1f h=%.1f color=%08X op=%.3f radius=%.1f tf=(%.1f,%.1f s%.3f,%.3f r%.1f)\n",
                       i, c->w, c->h, c->color, c->opacity, c->radius,
                       c->tx, c->ty, c->scaleX, c->scaleY, c->rotate);
            }
        }
    }

    /* SVG 快照 */
    if (!svgPath.empty()) {
        std::ofstream f(svgPath.c_str());
        f << "<svg xmlns='http://www.w3.org/2000/svg' width='" << (int)vw
          << "' height='" << (int)ea_render_content_height(r) << "'>\n";
        f << "<defs><filter id='b'><feGaussianBlur stdDeviation='6'/></filter></defs>\n";
        f << "<rect width='100%' height='100%' fill='#FFFFFF'/>\n";
        int m = ea_render_draw_count(r);
        for (int i = 0; i < m; ++i) {
            const ea_draw_cmd* c = ea_render_draw_at(r, i);
            if (!c) continue;
            std::string tf;
            if (c->hasTransform) {
                char b[128];
                snprintf(b, sizeof(b), " transform='translate(%.1f %.1f) rotate(%.1f) scale(%.3f %.3f)'",
                         c->tx, c->ty, c->rotate, c->scaleX, c->scaleY);
                tf = b;
            }
            if (c->type == EA_DRAW_IMAGE) {
                if (c->src && *c->src) {
                    std::string href = c->src;
                    if (href.compare(0, 7, "file://") == 0) href = href.substr(7);
                    f << "<image x='" << c->x << "' y='" << c->y << "' width='" << c->w
                      << "' height='" << c->h << "' preserveAspectRatio='xMidYMid slice' href='"
                      << href << "'" << tf << "/>\n";
                }
            } else if (c->type == EA_DRAW_RECT || c->type == EA_DRAW_BORDER) {
                /* 背景图（含 body 壁纸 / cover / tile）先铺图，再叠底色与边框 */
                if (c->bgImage && *c->bgImage) {
                    std::string href = c->bgImage;
                    if (href.compare(0, 7, "file://") == 0) href = href.substr(7);
                    f << "<image x='" << c->x << "' y='" << c->y << "' width='" << c->w
                      << "' height='" << c->h << "' preserveAspectRatio='xMidYMid slice' href='"
                      << href << "'" << tf << "/>\n";
                }
                if (c->shadowColor >> 24) {
                    f << "<rect x='" << (c->x + c->shadowDx) << "' y='" << (c->y + c->shadowDy)
                      << "' width='" << c->w << "' height='" << c->h << "' rx='" << c->radius
                      << "' fill='" << hexColor(c->shadowColor) << "' fill-opacity='0.25'"
                      << tf << "/>\n";
                }
                if (c->type == EA_DRAW_BORDER) {
                    f << "<rect x='" << c->x << "' y='" << c->y << "' width='" << c->w
                      << "' height='" << c->h << "' rx='" << c->radius
                      << "' fill='none' stroke='" << hexColor(c->color) << "' stroke-width='"
                      << c->textSize << "'" << tf << "/>\n";
                } else {
                    unsigned int a8 = (c->color >> 24) & 0xFF;
                    if (a8 > 0) {
                        f << "<rect x='" << c->x << "' y='" << c->y << "' width='" << c->w
                          << "' height='" << c->h << "' rx='" << c->radius
                          << "' fill='" << hexColor(c->color) << "' fill-opacity='"
                          << (c->opacity * (a8 / 255.0)) << "'";
                        if (c->filterBlur > 0) f << " filter='url(#b)'";
                        f << tf << "/>\n";
                    }
                }
            } else if (c->type == EA_DRAW_LINE) {
                f << "<line x1='" << c->x << "' y1='" << c->y << "' x2='" << (c->x + c->w)
                  << "' y2='" << c->y << "' stroke='" << hexColor(c->color) << "'/>\n";
            } else if (c->type == EA_DRAW_TEXT) {
                f << "<text x='" << c->x << "' y='" << (c->y + c->textSize)
                  << "' font-size='" << c->textSize << "' fill='" << hexColor(c->color)
                  << "' font-family='sans-serif'>" << xmlEscape(c->text ? c->text : "") << "</text>\n";
            }
        }
        f << "</svg>\n";
        printf("[SVG] wrote %s\n", svgPath.c_str());
    }

    ea_render_destroy(r);
    ea_html_doc_destroy(doc);
    if (js) ea_js_destroy(js);
    return 0;
}
