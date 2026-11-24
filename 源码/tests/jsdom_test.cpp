#include "../engine/include/html_parser.h"
#include "../engine/include/js_engine.h"
#include <cstdio>
#include <cstring>

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html = "<html><body><h1 id='t'>原始标题</h1><p id='p' class='x'>段落</p></body></html>";
    void* d = ea_html_doc_create();
    ea_html_doc_parse_utf8(d, html, strlen(html));

    void* js = ea_js_create();
    ea_js_bind_document(js, d);

    char out[1024];
    int rc;

    rc = ea_js_eval(js, "1+1", 3, out, sizeof(out));
    printf("[%d] 1+1 => %s\n", rc, out);

    /* 读元素 */
    rc = ea_js_eval(js, "document.getElementById('t').textContent", strlen("document.getElementById('t').textContent"), out, sizeof(out));
    printf("[%d] h1.textContent => %s err=%s\n", rc, out, ea_js_last_error(js));

    /* 改 textContent */
    rc = ea_js_eval(js, "document.getElementById('t').textContent = 'JS改的标题'", strlen("document.getElementById('t').textContent = 'JS改的标题'"), out, sizeof(out));
    printf("[%d] set textContent => rc=%d err=%s\n", rc, rc, ea_js_last_error(js));

    /* 读属性 */
    rc = ea_js_eval(js, "document.getElementById('p').getAttribute('class')", strlen("document.getElementById('p').getAttribute('class')"), out, sizeof(out));
    printf("[%d] p.getAttribute('class') => %s\n", rc, out);

    /* 改属性 */
    rc = ea_js_eval(js, "document.getElementById('p').setAttribute('data-new','yes')", strlen("document.getElementById('p').setAttribute('data-new','yes')"), out, sizeof(out));
    printf("[%d] setAttribute => rc=%d\n", rc, rc);

    /* 错误测试 */
    rc = ea_js_eval(js, "undefinedVar.foo", 15, out, sizeof(out));
    printf("[%d] error test => rc=%d err=%s\n", rc, rc, ea_js_last_error(js));

    /* 检查 DOM 是否真被改 */
    printf("--- 修改后 DOM ---\n");
    const char* tree = ea_html_doc_dump_tree(d);
    printf("%s\n", tree);
    ea_html_free_string(tree);

    ea_js_destroy(js);
    ea_html_doc_destroy(d);
    return 0;
}