#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ms51_image.h"
size_t image_test_allocated;
size_t image_test_limit = 36u * 1024u;
typedef struct { const uint8_t *bytes; size_t size, offset, chunk; } input_t;
static int read_chunk(void *arg,uint8_t *out,size_t max) {
    input_t *in=arg;
    size_t n=in->size-in->offset;
    if(n>in->chunk)n=in->chunk;
    if(n>max)n=max;
    memcpy(out,in->bytes+in->offset,n);in->offset+=n;return (int)n;
}
static esp_err_t parse(const uint8_t *bytes,size_t length,size_t chunk,const char *name,ms51_image_t *image) {
    input_t in={bytes,length,0,chunk};
    return ms51_image_parse_stream(read_chunk,&in,length,name,image);
}
int main(void) {
    uint8_t binary[32768];
    for(size_t i=0;i<sizeof(binary);i++)binary[i]=(uint8_t)i;
    char *hex=calloc(1,98304);size_t length=0;
    for(unsigned address=0;address<32768;address+=16) {
        length+=sprintf(hex+length,":10%04X00",address);
        unsigned sum=16+(address>>8)+(address&255);
        for(unsigned j=0;j<16;j++){length+=sprintf(hex+length,"%02X",binary[address+j]);sum+=binary[address+j];}
        length+=sprintf(hex+length,"%02X\r\n",(-sum)&255);
    }
    length+=sprintf(hex+length,":00000001FF\r\n");
    size_t chunks[]={1,2,7,511,512};
    for(size_t i=0;i<sizeof(chunks)/sizeof(*chunks);i++) {
        ms51_image_t image;
        assert(parse((uint8_t*)hex,length,chunks[i],"full.hex",&image)==ESP_OK);
        assert(image.covered_size==32768 && image.size==32768 && memcmp(binary,image.data,32768)==0);
        ms51_image_free(&image);
        assert(parse(binary,sizeof(binary),chunks[i],"full.bin",&image)==ESP_OK);
        assert(image.covered_size==32768 && memcmp(binary,image.data,32768)==0);
        ms51_image_free(&image);
    }
    const char *invalid[]={":0400000001020304F3\n:00000001FF\n", ":0400000001020304F2\n",
       ":0400000001020304F2\n:0400000001020304F2\n:00000001FF\n",
       ":048000000102030472\n:00000001FF\n", ":00000001FF\n", ":00000001FF\n:00000001FF\n"};
    for(size_t i=0;i<sizeof(invalid)/sizeof(*invalid);i++)for(size_t chunk=1;chunk<=512;chunk*=8) {
        ms51_image_t image;
        assert(parse((uint8_t*)invalid[i],strlen(invalid[i]),chunk,"bad.hex",&image)!=ESP_OK);
        assert(image.allocation==NULL);
    }
    ms51_image_t image;
    assert(parse((uint8_t*)hex,length,0,"broken.hex",&image)==ESP_ERR_INVALID_RESPONSE);
    assert(image.allocation==NULL);
    assert(image_test_allocated==0);
    image_test_limit=36u*1024u-1u;
    assert(parse(binary,sizeof(binary),512,"no-memory.bin",&image)==ESP_ERR_NO_MEM);
    assert(image.allocation==NULL && image_test_allocated==0);
    image_test_limit=36u*1024u;
    assert(parse(binary,sizeof(binary),512,"retry.bin",&image)==ESP_OK);
    ms51_image_free(&image);
    assert(image_test_allocated==0);
    free(hex);
    puts("PASS streaming 32KiB BIN/HEX, chunk boundaries, invalid inputs, interruption, 36KiB budget, allocation failure/recovery, no retained buffer");
}
