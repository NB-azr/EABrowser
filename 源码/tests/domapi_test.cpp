#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>

static void dumpTexts(void* r, const char* tag) {
    int n = ea_render_draw_count(r);
    printf("--- %s (cmds=%d) ---\n", tag, n);
    for (int i = 0; i < n; ++i) {
        const ea_draw_cmd* c = ea_render_draw_at(r, i);
        if (c->type == EA_DRAW_TEXT && c->text && c->text[0])
            printf("  y=%.0f \"%s\"\n", c->y, c->text);
    }
}

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html = "<html><body><div id='root'><p>原有段落</p></div></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));
    void* r = ea_render_create();
    ea_render_layout(r, d, 360, 640);
    dumpTexts(r, "初始");

    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[512];
    const char* scripts[] = {
        "var el = document.createElement('h2'); el.textContent = 'JS新建的标题'; document.getElementById('root').appendChild(el);",
        "var p2 = document.createElement('p'); p2.textContent = 'JS新增段落'; p2.setStyle('color','#E91E63'); document.getElementById('root').appendChild(p2);"
    };
    for (auto s : scripts) {
        int rc = ea_js_eval(js, s, strlen(s), out, sizeof(out));
        printf("eval rc=%d %s\n", rc, rc != 0 ? ea_js_last_error(js) : "");
    }

    printf("--- 新 DOM ---\n");
    const char* t = ea_html_doc_dump_tree(d);
    printf("%s\n", t); ea_html_free_string(t);

    ea_render_relayout(r);
    dumpTexts(r, "重绘后");

    ea_js_destroy(js); ea_render_destroy(r); ea_html_doc_destroy(d);
    return 0;
}