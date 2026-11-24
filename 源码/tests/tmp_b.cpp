#include "X:/engine/src/css_value.h"
#include <cstdio>
void setv();
int main(){ setv(); printf("top=%g dark=%d\n", eacss::envInsets()[0], (int)eacss::prefersDark()); return 0; }
