#include "../engine/src/selector.h"
#include <cstdio>
using namespace ea;
int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* tests[] = {"[class|=box]", "[class~=red]", "[data-x=hello]", "[data-x]", "[data-x^=h]"};
    for (auto t : tests) {
        SimpleSel s;
        bool ok = parseSimpleSelector(t, s);
        printf("%-20s ok=%d attrs=%zu ops=%zu", t, ok, s.attrs.size(), s.attrOps.size());
        for (size_t i = 0; i < s.attrs.size(); ++i) {
            printf("  [%s op=%d val=%s]", s.attrs[i].first.c_str(), (int)s.attrOps[i], s.attrs[i].second.c_str());
        }
        printf("\n");
    }
    return 0;
}