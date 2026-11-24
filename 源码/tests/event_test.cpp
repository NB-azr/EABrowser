#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>

static void texts(void* r, const char* tag) {
    printf("--- %s ---\n", tag);
    int n = ea_render_draw_count(r);
    for (int i = 0; i < n; ++i) {
        const ea_draw_cmd* c = ea_render_draw_at(r, i);
        if (c->type == EA_DRAW_TEXT && c->text && c->text[0])
            printf("  y=%.0f \"%s\"\n", c->y, c->text);
    }
}

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html =
        "<html><body>"
        "<h1 id='title'>计数器</h1>"
        "<p id='count'>0</p>"
        "<div id='btn' style='background:#4A90E2;padding:12px;color:#FFF'>点我 +1</div>"
        "</body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));
    void* r = ea_render_create();
    ea_render_layout(r, d, 360, 640);
    texts(r, "初始");

    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[512];

    const char* setup =
        "var n = 0;"
        "document.getElementById('btn').addEventListener('click', function() {"
        "  n = n + 1;"
        "  document.getElementById('count').textContent = '点击次数: ' + n;"
        "});";
    int rc = ea_js_eval(js, setup, strlen(setup), out, sizeof(out));
    printf("setup rc=%d %s\n", rc, rc ? ea_js_last_error(js) : "");

    /* 模拟点击 btn */
    ea_html_node btn = ea_html_get_by_id(d, "btn");
    printf("btn = %p\n", (void*)btn);
    for (int i = 0; i < 3; ++i) {
        rc = ea_js_dispatch_click(js, btn);
        printf("click #%d rc=%d\n", i + 1, rc);
    }

    ea_render_relayout(r);
    texts(r, "点击3次后");

    ea_js_destroy(js); ea_render_destroy(r); ea_html_doc_destroy(d);
    return 0;
}