#include "../engine/include/html_parser.h"
#include <cstdio>
#include <cstring>
#include <string>


static const char* kFallbackHtml =
    "<!DOCTYPE html><html><head><title>fallback</title></head>"
    "<body><p>hello &amp; world</p><br/></body></html>";

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

int main(int argc, char** argv) {

    const char* path = (argc > 1) ? argv[1] : "test.html";
    bool fileOk = false;
    std::string html = readAll(path, fileOk);

    printf("==================================================\n");
    printf("  EA HTML Parser - Console Runner v1\n");
    printf("==================================================\n");
    if (fileOk) printf("[INPUT] file: %s (%u bytes)\n", path, (unsigned)html.size());
    else {
        printf("[INPUT] file NOT found: %s -> built-in fallback\n", path);
        html = kFallbackHtml;
    }

    void* doc = ea_html_doc_create();
    if (!doc) { printf("[FAIL] doc_create failed\n"); return 1; }

    int rc = fileOk ? ea_html_doc_parse_file(doc, path)
                    : ea_html_doc_parse_utf8(doc, html.data(), html.size());

    if (rc != EA_HTML_OK) {
        const char* err = ea_html_doc_last_error(doc);
        printf("[FAIL] parse rc=%d err=%s\n", rc, err ? err : "(null)");
        ea_html_free_string(err);
        ea_html_doc_destroy(doc);
        return 2;
    }

    printf("[OK]   parse success  nodes=%d errors=%d\n\n",
           ea_html_doc_node_count(doc), ea_html_doc_error_count(doc));

    const char* tree = ea_html_doc_dump_tree(doc);
    printf("------------------ DOM TREE ------------------\n%s\n", tree ? tree : "(null)");
    ea_html_free_string(tree);

    const char* out = ea_html_doc_serialize(doc);
    printf("------------------ SERIALIZE ----------------\n%s\n", out ? out : "(null)");
    ea_html_free_string(out);

    printf("\n------------------ QUERY TEST --------------\n");
    const char* ids[] = {"title", "box1", "box2", "main", "notexist"};
    for (auto id : ids) {
        ea_html_node n = ea_html_get_by_id(doc, id);
        if (n) {
            const char* nm = ea_html_node_name(n);
            const char* tx = ea_html_node_text(n);
            printf("[by-id %-8s] found <%s> textlen=%u\n", id, nm ? nm : "?", tx ? (unsigned)strlen(tx) : 0);
            ea_html_free_string(nm); ea_html_free_string(tx);
        } else {
            printf("[by-id %-8s] NOT FOUND\n", id);
        }
    }

    const char* classes[] = {"card", "label", "desc", "post", "highlight", "nope"};
    for (auto cls : classes) {
        ea_html_node buf[32];
        int total = ea_html_query_all_class(doc, cls, buf, 32);
        printf("[by-class %-10s] count=%d", cls, total);
        if (total > 0) printf(" first=<%s>", ea_html_node_name(buf[0]));
        printf("\n");
        if (total > 0) ea_html_free_string(ea_html_node_name(buf[0]));
    }

    const char* tags[] = {"div", "p", "span", "article", "h2"};
    for (auto tg : tags) {
        ea_html_node buf[32];
        int total = ea_html_query_all_tag(doc, tg, buf, 32);
        printf("[by-tag %-8s] count=%d\n", tg, total);
    }

    /* 遍历验证：从 #box1 找第一个 span.label 再找同级 */
    ea_html_node box1 = ea_html_get_by_id(doc, "box1");
    if (box1) {
        ea_html_node sp = ea_html_get_by_class(doc, "label", box1);
        if (sp) {
            const char* s1 = ea_html_node_text(sp);
            ea_html_node sib = ea_html_node_next_sibling(sp);
            const char* s2 = sib ? ea_html_node_text(sib) : nullptr;
            printf("[navigate] box1 first label=\"%s\" next sibling=\"%s\"\n",
                   s1 ? s1 : "", s2 ? s2 : "");
            ea_html_free_string(s1); ea_html_free_string(s2);
        }
    }

    printf("\n------------------ SUMMARY ------------------\n");
    printf("nodes  : %d\n", ea_html_doc_node_count(doc));
    printf("errors : %d\n", ea_html_doc_error_count(doc));
    printf("RESULT : PASS\n");

    ea_html_doc_destroy(doc);
    return 0;
}