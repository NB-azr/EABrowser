#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include "../engine/include/network.h"
#include <cstdio>
#include <cstring>

/* mock fetcher：直接同步回调 */
static void mockFetch(void* hostCtx, const char* url, void* ud, ea_net_callback cb) {
    const char* body = "<html><body><h1>来自网络</h1></body></html>";
    cb(ud, url, body, strlen(body), 200, nullptr);
}

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    ea_net_set_fetcher(nullptr, mockFetch);

    const char* html = "<html><body><p id='out'>等待</p></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));
    void* r = ea_render_create();
    ea_render_layout(r, d, 360, 640);

    void* js = ea_js_create();
    ea_js_bind_document(js, d);
    char out[1024];

    const char* s =
        "fetch('https://x.com/api').then(function(resp){"
        "  document.getElementById('out').textContent = '拿到: ' + resp.text;"
        "});";
    int rc = ea_js_eval(js, s, strlen(s), out, sizeof(out));
    printf("fetch eval rc=%d %s\n", rc, rc ? ea_js_last_error(js) : "");

    /* 执行异步任务 */
    int jobs = ea_js_run_jobs(js);
    printf("jobs executed = %d\n", jobs);

    ea_render_relayout(r);
    printf("--- 重绘后 ---\n");
    int n = ea_render_draw_count(r);
    for (int i = 0; i < n; ++i) {
        const ea_draw_cmd* c = ea_render_draw_at(r, i);
        if (c->type == EA_DRAW_TEXT && c->text && c->text[0])
            printf("  \"%s\"\n", c->text);
    }
    ea_js_destroy(js); ea_render_destroy(r); ea_html_doc_destroy(d);
    return 0;
}