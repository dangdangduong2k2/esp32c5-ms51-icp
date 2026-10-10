#pragma once
#include <stdlib.h>
#define MALLOC_CAP_8BIT 1
#define heap_caps_calloc(n,size,caps) calloc(n,size)
#define heap_caps_free(ptr) free(ptr)
