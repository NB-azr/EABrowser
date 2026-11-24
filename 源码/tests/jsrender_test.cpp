#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>

static void dumpDraw(void* r, const char* tag) {
    int n = ea_render_draw_count(r);
    printf("--- %s (cmds=%d) ---\n", tag, n);
    const char* tn[] = {"RECT","TEXT","BORDER","LINE","IMAGE"};
    for (int i = 0; i < n; ++i) {
        const ea_draw_cmd* c = ea_render_draw_at(r, i);
        if (c->type == EA_DRAW_TEXT)
            printf("  [%d] TEXT y=%.0f \"%s\"\n", i, c->y, c->text ? c->text : "");
    }
}

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html = "<html><body><h1 id='t'>原标题</h1><p id='p'>内容A</p></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));

    void* r = ea_render_create();
    ea_render_layout(r, d, 360, 640);
    dumpDraw(r, "初始");

    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[256];
    int rc = ea_js_eval(js, "document.getElementById('t').textContent='JS改后'", strlen("document.getElementById('t').textContent='JS改后'"), out, sizeof(out));
    printf("\nJS eval rc=%d\n", rc);
    rc = ea_js_eval(js, "document.getElementById('p').textContent='新内容很长很长很长很长很长很长很长很长很长很长很长很长很长很长'", strlen("document.getElementById('p').textContent='新内容很长很长很长很长很长很长很长很长很长很长很长很长很长很长'"), out, sizeof(out));
    printf("JS eval2 rc=%d\n\n", rc);

    /* 重新布局 */
    rc = ea_render_relayout(r);
    printf("relayout rc=%d\n", rc);
    dumpDraw(r, "JS改后重绘");

    ea_js_destroy(js);
    ea_render_destroy(r);
    ea_html_doc_destroy(d);
    return 0;
}