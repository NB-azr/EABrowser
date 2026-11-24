#include "../engine/include/html_parser.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html = "<html><body><p id='c'>0</p></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));
    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[512];

    const char* setup =
        "var n = 0;"
        "setInterval(function(){ n = n + 1; document.getElementById('c').textContent = 'tick: ' + n; }, 20);"
        "setTimeout(function(){ document.getElementById('c').textContent = 'timeout fired'; }, 100);";
    int rc = ea_js_eval(js, setup, strlen(setup), out, sizeof(out));
    printf("setup rc=%d %s\n", rc, rc ? ea_js_last_error(js) : "");

    printf("dirty after setup = %d\n", ea_js_is_dirty(js));
    ea_js_clear_dirty(js);

    /* 模拟时间推进：每 20ms 一帧，共 200ms */
    for (int t = 0; t <= 200; t += 20) {
        int ex = ea_js_tick(js, (double)t);
        int dirty = ea_js_is_dirty(js);
        if (ex > 0) printf("t=%3dms  executed=%d  dirty=%d\n", t, ex, dirty);
        ea_js_clear_dirty(js);
    }

    printf("--- DOM 最终 ---\n");
    const char* tree = ea_html_doc_dump_tree(d);
    printf("%s\n", tree); ea_html_free_string(tree);
    printf("has_timers = %d\n", ea_js_has_timers(js));

    ea_js_destroy(js); ea_html_doc_destroy(d);
    return 0;
}