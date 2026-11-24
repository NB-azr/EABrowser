#include "../engine/include/html_parser.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int fail = 0;
#define CK(c,m) do{ if(!(c)){printf("[FAIL] %s\n",m);++fail;} else printf("[ok] %s\n",m);}while(0)

static std::string parseViaStream(const std::string& html, size_t chunk) {
    void* s = ea_html_stream_create();
    if (!s) return "";
    for (size_t i = 0; i < html.size(); i += chunk) {
        size_t n = (i + chunk > html.size()) ? (html.size() - i) : chunk;
        if (ea_html_stream_feed(s, html.data() + i, n) != EA_HTML_OK) { ea_html_stream_destroy(s); return ""; }
    }
    void* doc = ea_html_stream_finish(s);
    if (!doc) { ea_html_stream_destroy(s); return ""; }
    const char* tree = ea_html_doc_dump_tree(doc);
    std::string r = tree ? tree : "";
    ea_html_free_string(tree);
    ea_html_stream_destroy(s);
    return r;
}

static int countNodesInTree(const std::string& t) {
    int c = 0;
    for (char ch : t) if (ch == '\n') ++c;
    return c;
}

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);

    std::string html = "<!DOCTYPE html><html><head><title>流式测试</title></head>"
                       "<body><p>中文段落一</p><p>中文段落二</p>"
                       "<div class='a'><span>嵌套</span></div></body></html>";

    printf("--- 不同块大小 ---\n");
    std::string t1 = parseViaStream(html, html.size());
    std::string t2 = parseViaStream(html, 1);
    std::string t4 = parseViaStream(html, 4);
    std::string t7 = parseViaStream(html, 7);
    std::string t16 = parseViaStream(html, 16);
    CK(!t1.empty() && !t2.empty(), "stream parse ok");
    CK(t1 == t2, "chunk=1 same as chunk=all");
    CK(t1 == t4, "chunk=4 same");
    CK(t1 == t7, "chunk=7 same (crosses utf8 boundary)");
    CK(t1 == t16, "chunk=16 same");

    printf("--- 中文跨块验证 ---\n");
    /* 每块1字节：中文字符3字节被拆成3块，验证tail保留逻辑 */
    bool hasChinese = t2.find("中文段落一") != std::string::npos;
    CK(hasChinese, "utf8 chinese survives 1-byte chunking");
    bool noReplace = t2.find("\xEF\xBF\xBD") == std::string::npos;
    CK(noReplace, "no U+FFFD replacement in 1-byte chunk");

    printf("--- 空流 ---\n");
    void* s = ea_html_stream_create();
    CK(s != nullptr, "stream_create");
    void* d = ea_html_stream_finish(s);
    CK(d != nullptr, "finish empty stream ok");
    ea_html_stream_destroy(s);

    printf("--- 空指针 ---\n");
    CK(ea_html_stream_feed(nullptr, "x", 1) == EA_HTML_ERR_NULL, "feed null");
    CK(ea_html_stream_finish(nullptr) == nullptr, "finish null");
    CK(ea_html_stream_error(nullptr) == EA_HTML_ERR_NULL, "error null");
    ea_html_stream_destroy(nullptr);
    printf("[ok] destroy null\n");

    printf("--- 大文件分块 ---\n");
    std::string big;
    for (int i = 0; i < 5000; ++i) big += "<div>item" + std::to_string(i) + "</div>";
    std::string bt = parseViaStream(big, 64);
    CK(!bt.empty(), "big 5000 div streamed");
    printf("     tree lines=%d\n", countNodesInTree(bt));

    printf("--- 流式结果可查询 ---\n");
    void* s2 = ea_html_stream_create();
    std::string h2 = "<div id='target' class='hit'>found</div>";
    ea_html_stream_feed(s2, h2.data(), h2.size());
    void* doc2 = ea_html_stream_finish(s2);
    if (doc2) {
        ea_html_node n = ea_html_get_by_id(doc2, "target");
        CK(n != nullptr, "query on streamed doc");
        ea_html_node m = ea_html_query_selector(doc2, ".hit");
        CK(m != nullptr, "selector on streamed doc");
    } else { printf("[FAIL] stream finish\n"); ++fail; }
    ea_html_stream_destroy(s2);

    printf("\n==== STREAM %s (fail=%d) ====\n", fail==0?"PASS":"FAIL", fail);
    return fail==0?0:1;
}