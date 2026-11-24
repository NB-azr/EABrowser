#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>
#include <string>
int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html = "<html><body style='margin:16px'><h1 style='color:#4A90E2'>定时器测试</h1><p id='n' style='font-size:20px'>0</p><script>var c=0;setInterval(function(){c=c+1;document.getElementById('n').textContent=c;},500);</script></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));
    void* r = ea_render_create();
    ea_render_layout(r, d, 360, 640);
    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[512];
    /* 执行 script（模拟 JsView 的 runInlineScripts） */
    const char* setup = "var c=0;setInterval(function(){c=c+1;document.getElementById('n').textContent=c;},500);";
    ea_js_eval(js, setup, strlen(setup), out, sizeof(out));

    float* floats = new float[4096 * 20];
    std::string* texts = new std::string[4096];

    /* 模拟 300 帧（约 5 秒） */
    for (int frame = 0; frame < 300; ++frame) {
        double now = frame * 16.0;
        int ex = ea_js_tick(js, now);
        ea_js_run_jobs(js);
        if (ea_js_is_dirty(js)) {
            int rc = ea_render_relayout(r);
            int n = ea_render_draw_count(r);
            /* 模拟 drawData：按步长20填充 */
            for (int i = 0; i < n && i < 4096; ++i) {
                const ea_draw_cmd* c = ea_render_draw_at(r, i);
                if (!c) continue;
                int b = i * 20;
                floats[b] = (float)c->type;
                floats[b+1] = c->x; floats[b+2] = c->y; floats[b+3] = c->w; floats[b+4] = c->h;
                if (c->text) texts[i] = c->text;
            }
            ea_js_clear_dirty(js);
        }
        if (frame % 30 == 0) printf("frame %d ex=%d\n", frame, ex);
    }
    printf("DONE, timers=%d\n", ea_js_has_timers(js));
    delete[] floats; delete[] texts;
    ea_js_destroy(js); ea_render_destroy(r); ea_html_doc_destroy(d);
    return 0;
}