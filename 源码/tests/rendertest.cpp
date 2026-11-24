#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include <cstdio>
#include <cstring>

static const char* html =
"<!DOCTYPE html><html><head><title>t</title></head><body>"
"<h1 style='color:#E91E63'>标题一</h1>"
"<p>这是一段中文测试文字，用来验证自动换行是否正常工作，需要足够长才能触发换行。</p>"
"<div style='background:#F0F4FA;padding:12px;margin:10px 0;border:1px solid #CCC'>"
"  <span style='color:#2196F3'>蓝色行内</span> 普通文字"
"</div>"
"<hr>"
"<p style='text-align:center;color:#4CAF50'>居中绿色</p>"
"<img src=\"test.png\" width=\"120\" height=\"80\">"
"<ul><li>项目一</li><li>项目二</li></ul>"
"<table><tr><th>标题</th><th>值</th></tr><tr><td>A</td><td>1</td></tr></table>"
"</body></html>";

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    void* d = ea_html_doc_create();
    int rc = ea_html_doc_parse_utf8(d, html, strlen(html));
    printf("parse rc=%d nodes=%d\n", rc, ea_html_doc_node_count(d));
    if (rc != 0) return 1;

    void* r = ea_render_create();
    rc = ea_render_layout(r, d, 360, 640);
    printf("layout rc=%d\n", rc);
    printf("content height = %.1f\n", ea_render_content_height(r));
    int n = ea_render_draw_count(r);
    printf("draw cmds = %d\n\n", n);

    const char* tname[] = {"RECT", "TEXT", "BORDER", "LINE"};
    for (int i = 0; i < n; ++i) {
        const ea_draw_cmd* c = ea_render_draw_at(r, i);
        if (!c) continue;
        printf("[%02d] %-6s x=%6.1f y=%7.1f w=%6.1f h=%6.1f color=%08X", i,
               tname[c->type & 3], c->x, c->y, c->w, c->h, c->color);
        if (c->type == EA_DRAW_TEXT) {
            printf(" size=%.1f bold=%d align=%d text=\"%s\"", c->textSize, c->bold, c->align, c->text ? c->text : "(null)");
        } else if (c->type == EA_DRAW_BORDER) {
            printf(" bw=%.1f", c->textSize);
        }
        printf("\n");
    }

    printf("\n--- hit test ---\n");
    ea_html_node hit = ea_render_hit_test(r, 10, 10);
    printf("hit(10,10) => %s\n", hit ? ea_html_node_name(hit) : "(null)");

    ea_render_destroy(r);
    ea_html_doc_destroy(d);
    return 0;
}