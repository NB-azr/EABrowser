#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include <cstdio>
#include <cstring>
#include <string>
int main(int argc,char**argv){
    setvbuf(stdout,NULL,_IONBF,0);
    FILE* f=fopen(argv[1],"rb");
    fseek(f,0,SEEK_END); long sz=ftell(f); fseek(f,0,SEEK_SET);
    std::string buf; buf.resize(sz); fread(&buf[0],1,sz,f); fclose(f);
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,buf.data(),buf.size());
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    int n=ea_render_draw_count(r);
    printf("=== 绘制指令 ===\n");
    const char* tn[]={"RECT","TEXT","BORDER","LINE","IMAGE"};
    for(int i=0;i<n;i++){
        const ea_draw_cmd* c=ea_render_draw_at(r,i);
        printf("[%d] %-6s x=%.0f y=%.0f w=%.0f h=%.0f radius=%.0f op=%.2f\n",
            i, tn[c->type&7], c->x,c->y,c->w,c->h, c->radius, c->opacity);
        if (c->type==EA_DRAW_TEXT && c->text) printf("       text=%s\n", c->text);
    }
    ea_render_destroy(r); ea_html_doc_destroy(d); return 0;
}