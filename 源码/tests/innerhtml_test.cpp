#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    const char* html="<html><body><div id='box'>旧内容</div></body></html>";
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,html,strlen(html));
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    void* js=ea_js_create();
    ea_js_bind_document(js,d);
    char out[1024];

    printf("--- 初始 ---\n");
    const char* t=ea_html_doc_dump_tree(d);
    printf("%s\n",t); ea_html_free_string(t);

    const char* s="document.getElementById('box').innerHTML = '<h2 style=\"color:#E91E63\">动态标题</h2><p>动态段落</p><ul><li>项1</li><li>项2</li></ul>'";
    int rc=ea_js_eval(js,s,strlen(s),out,sizeof(out));
    printf("set innerHTML rc=%d %s\n",rc,rc?ea_js_last_error(js):"");

    printf("--- 设置后 DOM ---\n");
    t=ea_html_doc_dump_tree(d);
    printf("%s\n",t); ea_html_free_string(t);

    ea_render_relayout(r);
    printf("--- 重绘指令数: %d ---\n",ea_render_draw_count(r));

    /* 读回 innerHTML */
    const char* s2="document.getElementById('box').innerHTML";
    rc=ea_js_eval(js,s2,strlen(s2),out,sizeof(out));
    printf("get innerHTML rc=%d => %.200s\n",rc,rc?ea_js_last_error(js):out);

    ea_js_destroy(js);ea_render_destroy(r);ea_html_doc_destroy(d);
    return 0;
}