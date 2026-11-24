#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include <cstdio>
#include <cstring>
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    const char* html =
    "<html><body style='margin:16px'>"
    "<div style='background:#EEEEEE;padding:10px;height:100px'>"
    "  <div id='a' style='position:absolute;top:10px;left:20px;background:#F99;padding:8px'>绝对定位</div>"
    "  <div style='background:#9F9;padding:8px'>正常流1</div>"
    "</div>"
    "<div style='position:relative;top:10px;left:30px;background:#99F;padding:8px;color:#FFF'>相对偏移</div>"
    "<div style='background:#FF9;padding:8px'>相对偏移后的正常块</div>"
    "</body></html>";
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,html,strlen(html));
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    int n=ea_render_draw_count(r);
    const char* tn[]={"RECT","TEXT","BORDER","LINE","IMAGE"};
    for(int i=0;i<n;i++){
        const ea_draw_cmd* c=ea_render_draw_at(r,i);
        printf("[%d] %-6s x=%.0f y=%.0f w=%.0f h=%.0f",i,tn[c->type&7],c->x,c->y,c->w,c->h);
        if(c->type==EA_DRAW_TEXT && c->text) printf("  \"%s\"",c->text);
        printf("\n");
    }
    ea_render_destroy(r);ea_html_doc_destroy(d);
    return 0;
}