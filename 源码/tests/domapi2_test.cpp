#include "../engine/include/html_parser.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html = "<html><body><div id='box'><p id='a'>A</p><p id='b'>B</p></div></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));
    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[1024];

    const char* tests[] = {
        "document.getElementById('box').classList.add('active')",
        "document.getElementById('box').classList.contains('active')",
        "document.getElementById('box').classList.contains('nope')",
        "var a = document.getElementById('a'); document.getElementById('box').removeChild(a); 'removed'",
        "var c = document.createElement('span'); c.textContent='NEW'; document.getElementById('box').appendChild(c); 'appended'"
    };
    for (auto s : tests) {
        int rc = ea_js_eval(js, s, strlen(s), out, sizeof(out));
        printf("rc=%d  %s  =>  %s\n", rc, s, rc ? ea_js_last_error(js) : out);
    }

    printf("--- DOM ---\n");
    const char* t = ea_html_doc_dump_tree(d);
    printf("%s\n", t); ea_html_free_string(t);

    ea_js_destroy(js); ea_html_doc_destroy(d);
    return 0;
}