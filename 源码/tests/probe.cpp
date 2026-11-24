#include "../engine/src/internal.h"
#include <cstdio>

using namespace ea;

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: probe <file>\n"); return 1; }
    FILE* f = fopen(argv[1], "rb");
    if (!f) { printf("open fail: %s\n", argv[1]); return 1; }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    std::string buf; buf.resize(sz > 0 ? (size_t)sz : 0);
    size_t rd = buf.empty() ? 0 : fread(&buf[0], 1, buf.size(), f);
    fclose(f); buf.resize(rd);
    if (buf.size() >= 3 && (unsigned char)buf[0]==0xEF && (unsigned char)buf[1]==0xBB && (unsigned char)buf[2]==0xBF) buf.erase(0,3);
    printf("read %zu bytes\n", buf.size());

    printf("--- TOKENIZING ---\n");
    Tokenizer tk(buf);
    Token t;
    int n = 0;
    while (tk.next(t)) {
        printf("#%d pos=%zu type=%d name=%s textlen=%zu\n", n, t.pos, (int)t.type, t.name.c_str(), t.text.size());
        if (++n > 200) { printf("TOO MANY TOKENS\n"); break; }
    }
    printf("--- TOKENIZE DONE n=%d finalpos=%zu ---\n", n, tk.position());

    printf("--- TREEBUILDING ---\n");
    Tokenizer tk2(buf);
    std::vector<Token> toks;
    while (tk2.next(t)) { toks.push_back(t); if (toks.size() > 200) break; }
    printf("collected %zu tokens\n", toks.size());
    Document doc;
    TreeBuilder tb(doc);
    tb.build(toks);
    printf("--- BUILD DONE ---\n");
    return 0;
}