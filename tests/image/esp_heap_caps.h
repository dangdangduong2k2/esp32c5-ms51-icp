#pragma once
#include <stdlib.h>
#include <stdint.h>
extern size_t image_test_allocated, image_test_limit;
static inline void *image_test_calloc(size_t n, size_t size) {
    if (n && size > SIZE_MAX / n) return NULL;
    size_t bytes = n * size;
    if (image_test_allocated > image_test_limit || bytes > image_test_limit - image_test_allocated) return NULL;
    size_t *block = calloc(1, sizeof(size_t) + bytes);
    if (!block) return NULL;
    *block = bytes; image_test_allocated += bytes;
    return block + 1;
}
static inline void image_test_free(void *ptr) {
    if (!ptr) return;
    size_t *block = (size_t *)ptr - 1;
    image_test_allocated -= *block; free(block);
}
#define MALLOC_CAP_8BIT 1
#define heap_caps_calloc(n,size,caps) image_test_calloc(n,size)
#define heap_caps_free(ptr) image_test_free(ptr)
