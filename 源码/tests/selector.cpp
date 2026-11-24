#include "../engine/include/html_parser.h"
#include <cstdio>
#include <cstring>
#include <string>

static int fail = 0;
static void expectCount(void* doc, const char* sel, int want) {
    ea_html_node buf[64];
    int got = ea_html_query_selector_all(doc, sel, buf, 64);
    if (got != want) { printf("[FAIL] %-40s want=%d got=%d\n", sel, want, got); ++fail; }
    else printf("[ok]   %-40s count=%d\n", sel, got);
}

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* html =
        "<html><body>"
        "<div id='a' class='box red' data-x='hello'>"
          "<p class='t1'>P1</p>"
          "<p class='t2 highlight'>P2</p>"
          "<span class='t1'>S1</span>"
        "</div>"
        "<div id='b' class='box'>"
          "<p>P3</p>"
          "<ul><li>L1</li><li class='on'>L2</li><li>L3</li></ul>"
        "</div>"
        "<section class='box blue' data-x='world'><article><h2>H</h2></article></section>"
        "</body></html>";

    void* d = ea_html_doc_create();
    if (ea_html_doc_parse_utf8(d, html, strlen(html)) != EA_HTML_OK) { printf("parse fail\n"); return 1; }

    printf("--- 基础选择器 ---\n");
    expectCount(d, "div", 2);
    expectCount(d, "p", 3);
    expectCount(d, "li", 3);
    expectCount(d, "*", 15);

    printf("--- 类 ---\n");
    expectCount(d, ".box", 3);
    expectCount(d, ".t1", 2);
    expectCount(d, ".highlight", 1);
    expectCount(d, ".box.red", 1);
    expectCount(d, ".nope", 0);

    printf("--- ID ---\n");
    expectCount(d, "#a", 1);
    expectCount(d, "#b", 1);
    expectCount(d, "#nope", 0);
    expectCount(d, "div#a", 1);

    printf("--- 属性 ---\n");
    expectCount(d, "[data-x]", 2);
    expectCount(d, "[data-x=hello]", 1);
    expectCount(d, "[data-x^=hel]", 1);
    expectCount(d, "[data-x$=llo]", 1);
    expectCount(d, "[data-x*=ell]", 1);
    expectCount(d, "[class~=red]", 1);
    expectCount(d, "[class|=box]", 1);

    printf("--- 组合子 ---\n");
    expectCount(d, "div p", 3);
    expectCount(d, "div > p", 3);
    expectCount(d, "#a > p", 2);
    expectCount(d, "div ul li", 3);
    expectCount(d, "ul > li", 3);
    expectCount(d, "h2", 1);
    expectCount(d, "article > h2", 1);
    expectCount(d, "body > section", 1);

    printf("--- 相邻/兄弟 ---\n");
    expectCount(d, "p + p", 1);
    expectCount(d, "p ~ span", 1);
    expectCount(d, "li + li", 2);
    expectCount(d, ".on ~ li", 1);

    printf("--- 逗号分组 ---\n");
    expectCount(d, "div, section", 3);
    expectCount(d, "h2, li.on", 2);

    printf("--- 伪类 ---\n");
    expectCount(d, "li:first-child", 1);
    expectCount(d, "li:last-child", 1);
    expectCount(d, "li:nth-child(2)", 1);
    expectCount(d, "li:nth-child(odd)", 2);
    expectCount(d, "li:nth-child(even)", 1);
    expectCount(d, "p:not(.t1)", 2);
    expectCount(d, "div:first-child", 1);

    printf("--- 复杂组合 ---\n");
    expectCount(d, "#a > p.t2.highlight", 1);
    expectCount(d, "div.box[data-x] p", 2);
    expectCount(d, "section article h2", 1);
    expectCount(d, "body > div > ul > li.on", 1);

    printf("--- querySelector 首个 ---\n");
    ea_html_node first = ea_html_query_selector(d, "p");
    if (first) {
        const char* tx = ea_html_node_text(first);
        printf("[ok]   first <p> text=\"%s\"\n", tx ? tx : "");
        ea_html_free_string(tx);
    } else { printf("[FAIL] querySelector(p) null\n"); ++fail; }

    printf("--- matches ---\n");
    ea_html_node p2 = ea_html_query_selector(d, "p.t2");
    if (p2) {
        int m1 = ea_html_node_matches(p2, "p");
        int m2 = ea_html_node_matches(p2, ".highlight");
        int m3 = ea_html_node_matches(p2, "div > p");
        int m4 = ea_html_node_matches(p2, ".red");
        printf("[ok]   matches p=%d .highlight=%d div>p=%d .red=%d (expect 1 1 1 0)\n", m1,m2,m3,m4);
        if (!(m1==1&&m2==1&&m3==1&&m4==0)) ++fail;
    }

    printf("--- 非法选择器 ---\n");
    expectCount(d, "", 0);
    expectCount(d, ">>>", 0);
    expectCount(d, "[", 0);
    expectCount(d, "..x", 0);

    ea_html_doc_destroy(d);
    printf("\n==== SELECTOR %s (fail=%d) ====\n", fail==0?"PASS":"FAIL", fail);
    return fail==0?0:1;
}