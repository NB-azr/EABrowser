#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include <cstdio>
#include <cstring>
#include <string>
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    FILE* f=fopen("C:/Temp/css_style.html","rb");
    if(!f){printf("open fail\n");return 1;}
    fseek(f,0,SEEK_END); long sz=ftell(f); fseek(f,0,SEEK_SET);
    std::string buf; buf.resize(sz); fread(&buf[0],1,sz,f); fclose(f);
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,buf.data(),buf.size());
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    int n=ea_render_draw_count(r);
    const char* tn[]={"RECT","TEXT","BORDER","LINE","IMAGE"};
    for(int i=0;i<n && i<20;i++){
        const ea_draw_cmd* c=ea_render_draw_at(r,i);
        printf("[%d] %s x=%.0f y=%.0f w=%.0f color=%08X",i,tn[c->type&7],c->x,c->y,c->w,c->color);
        if(c->type==EA_DRAW_TEXT&&c->text)printf(" txt");
        printf("\n");
    }
    ea_render_destroy(r);ea_html_doc_destroy(d);
    return 0;
}