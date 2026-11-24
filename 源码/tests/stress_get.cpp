#include "../engine/include/html_parser.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>
#include <string>
int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html = "<html><body><p id='n'>0</p></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));
    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[512];
    for (int i = 0; i < 1000; ++i) {
        std::string s = "document.getElementById('n').textContent = '" + std::to_string(i) + "'";
        int rc = ea_js_eval(js, s.c_str(), s.size(), out, sizeof(out));
        if (rc != 0) { printf("iter %d FAIL: %s\n", i, ea_js_last_error(js)); break; }
        if (i % 200 == 0) printf("iter %d ok\n", i);
    }
    printf("DONE\n");
    ea_js_destroy(js); ea_html_doc_destroy(d);
    return 0;
}