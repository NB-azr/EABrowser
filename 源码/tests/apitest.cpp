#include "../engine/include/html_parser.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int fail = 0;
#define CHECK(c, msg) do { if (!(c)) { printf("[FAIL] %s\n", msg); ++fail; } else printf("[ok] %s\n", msg); } while(0)

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);

    /* 1. 空指针 */
    CHECK(ea_html_doc_parse_utf8(nullptr, "x", 1) == EA_HTML_ERR_NULL, "parse_utf8(null doc)");
    CHECK(ea_html_doc_parse_file(nullptr, "x") == EA_HTML_ERR_NULL, "parse_file(null doc)");
    CHECK(ea_html_doc_parse_file((void*)1, nullptr) == EA_HTML_ERR_NULL, "parse_file(null path)");
    CHECK(ea_html_doc_dump_tree(nullptr) == nullptr, "dump_tree(null)");
    CHECK(ea_html_doc_node_count(nullptr) == -1, "node_count(null)");
    CHECK(ea_html_get_by_id(nullptr, "x") == nullptr, "get_by_id(null)");
    CHECK(ea_html_node_name(nullptr) == nullptr, "node_name(null)");
    CHECK(ea_html_node_parent(nullptr) == nullptr, "node_parent(null)");
    ea_html_free_string(nullptr);
    printf("[ok] free_string(null) no crash\n");
    ea_html_doc_destroy(nullptr);
    printf("[ok] destroy(null) no crash\n");

    /* 2. 正常解析 + 重复解析 */
    void* d = ea_html_doc_create();
    CHECK(d != nullptr, "doc_create");
    const char* h1 = "<html><body><p id=a>1</p></body></html>";
    CHECK(ea_html_doc_parse_utf8(d, h1, strlen(h1)) == EA_HTML_OK, "parse #1");
    int n1 = ea_html_doc_node_count(d);
    CHECK(n1 > 0, "node_count > 0");

    const char* h2 = "<div><span>x</span></div>";
    CHECK(ea_html_doc_parse_utf8(d, h2, strlen(h2)) == EA_HTML_OK, "re-parse #2");
    int n2 = ea_html_doc_node_count(d);
    printf("     nodes: #1=%d  #2=%d\n", n1, n2);
    CHECK(n2 > 0, "re-parse produced valid tree");
    CHECK(ea_html_get_by_id(d, "a") == nullptr, "old tree gone after re-parse");

    /* 3. 重复解析 1000 次不泄漏（粗测） */
    for (int i = 0; i < 1000; ++i) {
        std::string h = "<div id=n" + std::to_string(i) + ">x</div>";
        if (ea_html_doc_parse_utf8(d, h.data(), h.size()) != EA_HTML_OK) { printf("[FAIL] loop parse %d\n", i); ++fail; break; }
    }
    printf("[ok] 1000 re-parse loop\n");
    ea_html_doc_destroy(d);

    /* 4. 多实例并存 */
    std::vector<void*> docs;
    for (int i = 0; i < 50; ++i) {
        void* doc = ea_html_doc_create();
        std::string h = "<div id=d" + std::to_string(i) + ">v" + std::to_string(i) + "</div>";
        ea_html_doc_parse_utf8(doc, h.data(), h.size());
        docs.push_back(doc);
    }
    int okcnt = 0;
    for (int i = 0; i < 50; ++i) {
        std::string id = "d" + std::to_string(i);
        if (ea_html_get_by_id(docs[i], id.c_str())) ++okcnt;
    }
    printf("     multi-doc: %d/50 isolated\n", okcnt);
    CHECK(okcnt == 50, "50 docs isolated, no shared state");
    for (auto dd : docs) ea_html_doc_destroy(dd);
    printf("[ok] 50 docs destroyed\n");

    /* 5. 非法参数 */
    void* d2 = ea_html_doc_create();
    CHECK(ea_html_doc_parse_utf8(d2, "", 0) == EA_HTML_OK, "parse empty");
    ea_html_node buf[8];
    CHECK(ea_html_query_all_tag(d2, "div", nullptr, 8) == 0, "query null out");
    CHECK(ea_html_query_all_tag(d2, nullptr, buf, 8) == 0, "query null tag");
    CHECK(ea_html_query_all_tag(d2, "div", buf, 0) == 0, "query cap=0");
    ea_html_doc_destroy(d2);

    printf("\n==== APITEST %s (fail=%d) ====\n", fail == 0 ? "PASS" : "FAIL", fail);
    return fail == 0 ? 0 : 1;
}