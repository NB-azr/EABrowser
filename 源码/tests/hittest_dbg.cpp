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

    int ys[] = {110,120,130,140,150,160,170,180,190,200,210,220,230,240,250,260,270,280,290,300,310,320,330};
    for (int i = 0; i < 23; ++i) {
        ea_html_node h = ea_render_hit_test(r, 180, (float)ys[i]);
        const char* n = h ? ea_html_node_name(h) : "(null)";
        printf("y=%3d -> %s ptr=%p\n", ys[i], n ? n : "(null)", (void*)h);
        if (n) ea_html_free_string(n);
    }
    printf("--- DOM ---\n");
    const char* tree = ea_html_doc_dump_tree(d);
    printf("%s\n", tree ? tree : "");
    if (tree) ea_html_free_string(tree);
    ea_html_node btn = ea_html_get_by_id(d, "btn");
    printf("get_by_id(btn) = %p\n", (void*)btn);
    if (btn) {
        float bx=0, by=0, bw=0, bh=0;
        int ok = ea_render_node_box(r, btn, &bx, &by, &bw, &bh);
        printf("btn box found=%d x=%.1f y=%.1f w=%.1f h=%.1f\n", ok, bx, by, bw, bh);
    }
    /* 查 flex 容器 */
    ea_html_node p_btn = ea_html_node_parent(btn);
    if (p_btn) {
        float bx=0, by=0, bw=0, bh=0;
        int ok = ea_render_node_box(r, p_btn, &bx, &by, &bw, &bh);
        const char* pn = ea_html_node_name(p_btn);
        printf("parent(%s) box found=%d x=%.1f y=%.1f w=%.1f h=%.1f\n", pn?pn:"?", ok, bx, by, bw, bh);
        if (pn) ea_html_free_string(pn);
    }
    printf("DONE\n");
    ea_render_destroy(r); ea_html_doc_destroy(d); return 0;
}