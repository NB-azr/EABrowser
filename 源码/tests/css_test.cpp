#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include <cstdio>
#include <cstring>
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    const char* html="<html><head><style>.red{color:#E91E63;font-size:20px}.big{padding:16px}#x{background:#4A90E2}h1{color:#333}</style></head><body><h1>标题</h1><p class='red'>红色段落</p><div class='red big' id='x'>红色大块</div></body></html>";
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,html,strlen(html));
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    int n=ea_render_draw_count(r);
    const char* tn[]={"RECT","TEXT","BORDER","LINE","IMAGE"};
    for(int i=0;i<n;i++){
        const ea_draw_cmd* c=ea_render_draw_at(r,i);
        printf("[%d] %s x=%.0f y=%.0f w=%.0f h=%.0f color=%08X",i,tn[c->type&7],c->x,c->y,c->w,c->h,c->color);
        if(c->type==EA_DRAW_TEXT&&c->text)printf(" \"%s\"",c->text);
        printf("\n");
    }
    ea_render_destroy(r);ea_html_doc_destroy(d);
    return 0;
}