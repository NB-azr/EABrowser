#include "../engine/include/html_parser.h"
#include "../engine/include/render.h"
#include <cstdio>
#include <cstring>
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    const char* h=
    "<html><body>"
    "<div style='display:flex;justify-content:center;gap:10px;background:#EEE;padding:8px'>"
    "<span style='background:#F99'>A</span>"
    "<span style='background:#9F9'>B</span>"
    "<span style='background:#99F'>C</span>"
    "</div>"
    "<div style='display:flex;justify-content:space-between;padding:8px'>"
    "<span>左</span><span>右</span>"
    "</div>"
    "<div style='display:flex;flex-direction:column;gap:4px;padding:8px'>"
    "<div>行1</div><div>行2</div>"
    "</div>"
    "</body></html>";
    void* d=ea_html_doc_create();
    ea_html_doc_parse_utf8(d,h,strlen(h));
    void* r=ea_render_create();
    ea_render_layout(r,d,360,640);
    int n=ea_render_draw_count(r);
    const char* tn[]={"RECT","TEXT","BORDER","LINE","IMAGE"};
    for(int i=0;i<n;i++){
        const ea_draw_cmd* c=ea_render_draw_at(r,i);
        printf("[%d] %s x=%.1f y=%.1f w=%.1f h=%.1f text=%s\n", i, tn[c->type&7], c->x,c->y,c->w,c->h, c->text?c->text:"");
    }
    ea_render_destroy(r); ea_html_doc_destroy(d);
    return 0;
}