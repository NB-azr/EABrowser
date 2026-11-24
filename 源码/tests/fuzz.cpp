#include "../engine/src/internal.h"
#include <cstdio>
#include <cstring>

using namespace ea;

static bool testOne(const std::string& s) {
    Tokenizer tk(s);
    Token t;
    size_t prev = 0;
    int iter = 0;
    while (iter++ < 1000) {
        bool got = tk.next(t);
        size_t now = tk.position();
        if (got && now == prev) return false;   /* 零进度 */
        if (!got) break;
        prev = now;
    }
    return iter < 1000;
}

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* chars = "<>!-/aZ \t\r\n=\"'&;#0";
    int n = (int)strlen(chars);
    int bad = 0;
    /* 1字节 */
    for (int a = 0; a < n; a++) {
        std::string s(1, chars[a]);
        if (!testOne(s)) { printf("ZERO-PROGRESS len1: [%02X]\n", (unsigned char)chars[a]); bad++; }
    }
    /* 2字节 */
    for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) {
        std::string s; s += chars[a]; s += chars[b];
        if (!testOne(s)) { printf("ZERO-PROGRESS len2: [%c][%c]\n", chars[a], chars[b]); if (++bad > 50) return 0; }
    }
    /* 3字节 */
    for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int c = 0; c < n; c++) {
        std::string s; s += chars[a]; s += chars[b]; s += chars[c];
        if (!testOne(s)) { printf("ZERO-PROGRESS len3: [%c][%c][%c]\n", chars[a], chars[b], chars[c]); if (++bad > 80) return 0; }
    }
    printf("DONE bad=%d\n", bad);
    return 0;
}