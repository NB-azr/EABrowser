#include <stdio.h>
#include <string.h>
#include "quickjs.h"

int main() {
    setvbuf(stdout, NULL, _IONBF, 0);
    JSRuntime* rt = JS_NewRuntime();
    JSContext* ctx = JS_NewContext(rt);

    const char* scripts[] = {
        "1 + 1",
        "'hello ' + 'world'",
        "var o = {a:1, b:2}; o.a + o.b",
        "(function(x){return x*x})(7)",
        "[1,2,3].map(x=>x*2).join(',')",
        "JSON.stringify({name:'EA', ver:1})",
        "Math.max(3,9,5)",
        "let s=''; for(let i=0;i<5;i++) s+=i; s"
    };
    for (int i = 0; i < 8; ++i) {
        JSValue v = JS_Eval(ctx, scripts[i], strlen(scripts[i]), "<test>", JS_EVAL_TYPE_GLOBAL);
        if (JS_IsException(v)) {
            JSValue e = JS_GetException(ctx);
            const char* es = JS_ToCString(ctx, e);
            printf("[%d] ERROR: %s\n", i, es ? es : "?");
            JS_FreeCString(ctx, es);
            JS_FreeValue(ctx, e);
        } else {
            const char* s = JS_ToCString(ctx, v);
            printf("[%d] %s  =>  %s\n", i, scripts[i], s ? s : "?");
            JS_FreeCString(ctx, s);
        }
        JS_FreeValue(ctx, v);
    }

    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    printf("DONE\n");
    return 0;
}