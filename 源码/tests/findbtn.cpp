#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include <cstdio>
#include <cstring>
#include <string>
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    FILE* f=fopen("C:/Temp/td.html","rb");
    if(!f){printf("open fail\n");return 1;}
    fseek(f,0,SEEK_END); long sz=ftell(f); fseek(f,0,SEEK_SET);
    std::string buf; buf.resize(sz); fread(&buf[0],1,sz,f); fclose(f);
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,buf.data(),buf.size());
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    ea_html_node btn = ea_html_get_by_id(d, "btn");
    ea_html_node box = ea_html_get_by_id(d, "box");
    printf("btn ptr=%p box ptr=%p\n", (void*)btn, (void*)box);
    if(btn){
        int line = ea_html_node_line(btn);
        printf("btn line=%d\n", line);
    }
    /* 扫描 y 找命中btn */
    for(int y=0;y<420;y+=10){
        ea_html_node h=ea_render_hit_test(r,180,(float)y);
        if(h==btn) printf("y=%d HIT BTN\n", y);
        else if(h==box) printf("y=%d hit box\n", y);
    }
    printf("DONE\n");
    ea_render_destroy(r);ea_html_doc_destroy(d);
    return 0;
}