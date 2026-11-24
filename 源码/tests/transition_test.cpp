#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    const char* html =
    "<html><body>"
    "<div id='box' style='background:#4A90E2;width:60px;height:60px;transition:all 0.8s;transform:translate(0px,0px)'></div>"
    "</body></html>";
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,html,strlen(html));
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    /* 找 box 的绘制位置 */
    int n=ea_render_draw_count(r);
    for(int i=0;i<n;i++){const ea_draw_cmd* c=ea_render_draw_at(r,i);if(c->type==0)printf("初始 RECT x=%.0f y=%.0f\n",c->x,c->y);}

    void* js=ea_js_create();
    ea_js_bind_document(js,d);
    char out[256];
    const char* s="document.getElementById('box').setStyle('transform','translate(200px,40px)')";
    int rc=ea_js_eval(js,s,strlen(s),out,sizeof(out));
    printf("setStyle rc=%d dirty=%d\n",rc,ea_js_is_dirty(js));

    /* relayout 触发动画检测 */
    ea_render_relayout(r);
    printf("--- relayout 后 ---\n");
    /* 推进动画 */
    for(int t=0;t<=1000;t+=250){
        int a=ea_render_update_animations(r,(double)t);
        n=ea_render_draw_count(r);
        float bx=-1,by=-1,ctx=-1;
        for(int i=0;i<n;i++){const ea_draw_cmd* c=ea_render_draw_at(r,i);if(c->type==0){bx=c->x;by=c->y;ctx=c->tx;printf("  hasTf=%d tx=%.1f ty=%.1f\n",c->hasTransform,c->tx,c->ty);break;}}
        printf("t=%dms anims=%d box=(%.0f,%.0f) tx=%.1f\n",t,a,bx,by,ctx);
    }
    ea_js_destroy(js);ea_render_destroy(r);ea_html_doc_destroy(d);
    return 0;
}