/* Host integration test: real parser + storage, byte-addressed NOR flash model. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ms51_storage.h"
static size_t test_strlcpy(char *dst, const char *src, size_t n) {
    size_t len=strlen(src); if(n) {size_t count=len<n-1?len:n-1;memcpy(dst,src,count);dst[count]=0;} return len;
}
#define strlcpy test_strlcpy
#include "../../main/ms51_storage.c"
size_t image_test_allocated;
size_t image_test_limit=4096;
static uint8_t flash[0x14000];
static const esp_partition_t partition={sizeof(flash)};
static int fail_write=-1, writes;
const esp_partition_t *esp_partition_find_first(int type,int sub,const char *label) {
    (void)type;(void)sub;assert(!strcmp(label,"ms51fw"));return &partition;
}
esp_err_t esp_partition_read(const esp_partition_t *p,size_t pos,void *dst,size_t n) {
    assert(p==&partition && pos+n<=sizeof(flash));memcpy(dst,flash+pos,n);return ESP_OK;
}
esp_err_t esp_partition_write(const esp_partition_t *p,size_t pos,const void *src,size_t n) {
    assert(p==&partition && pos+n<=sizeof(flash));
    int interrupted=writes++==fail_write;
    size_t written=interrupted?n/2:n;
    for(size_t i=0;i<written;i++) {uint8_t b=((const uint8_t*)src)[i];assert((flash[pos+i]&b)==b);flash[pos+i]&=b;}
    return interrupted?ESP_ERR_INVALID_STATE:ESP_OK;
}
esp_err_t esp_partition_erase_range(const esp_partition_t *p,size_t pos,size_t n) {
    assert(p==&partition && !(pos%4096) && !(n%4096) && pos+n<=sizeof(flash));
    memset(flash+pos,255,n);return ESP_OK;
}
esp_err_t esp_partition_mmap(const esp_partition_t *p,size_t pos,size_t n,int flags,const void **out,esp_partition_mmap_handle_t *handle) {
    (void)flags;assert(p==&partition && pos+n<=sizeof(flash));*out=flash+pos;*handle=1;return ESP_OK;
}
void esp_partition_munmap(esp_partition_mmap_handle_t handle) {assert(handle==1);}
typedef struct { const uint8_t *data;size_t size,pos,chunk,stop; } input_t;
static int read_input(void *arg,uint8_t *out,size_t max) {
    input_t *in=arg;if(in->pos>=in->stop)return -1;
    size_t n=in->size-in->pos;if(n>max)n=max;if(n>in->chunk)n=in->chunk;
    if(n>in->stop-in->pos)n=in->stop-in->pos;
    memcpy(out,in->data+in->pos,n);in->pos+=n;return (int)n;
}
static esp_err_t receive(const uint8_t *bytes,size_t len,size_t chunk,size_t stop,const char *name,ms51_storage_info_t *info) {
    input_t input={bytes,len,0,chunk,stop};
    esp_err_t result=ms51_storage_receive(read_input,&input,len,name,info);
    assert(image_test_allocated==0);return result;
}
static void check_image(const ms51_image_t *expected) {
    ms51_storage_image_t actual={0};assert(ms51_storage_acquire_image(0,&actual)==ESP_OK);
    assert(actual.size==expected->size && actual.covered_size==expected->covered_size);
    assert(!memcmp(actual.data,expected->data,MS51_IMAGE_MAX_SIZE));
    assert(!memcmp(actual.coverage,expected->coverage,MS51_IMAGE_COVERAGE_SIZE));
    ms51_storage_release_image(&actual);
}
int main(int argc,char **argv) {
    memset(flash,255,sizeof(flash));assert(ms51_storage_init()==ESP_OK);
    const uint8_t old[]={1,2,3,4,5,6};ms51_storage_info_t initial,info;
    assert(receive(old,sizeof(old),3,SIZE_MAX,"old.bin",&initial)==ESP_OK);
    uint8_t saved[sizeof(flash)];memcpy(saved,flash,sizeof(saved));
    const char *bad[]={":0400000001020304F3\n:00000001FF\n",":0400000001020304F2\n",
        ":0400000001020304F2\n:0400000001020304F2\n:00000001FF\n",":048000000102030472\n:00000001FF\n"};
    for(size_t i=0;i<sizeof(bad)/sizeof(*bad);i++) {
        assert(receive((const uint8_t*)bad[i],strlen(bad[i]),1,SIZE_MAX,"bad.hex",NULL)!=ESP_OK);
        assert(ms51_storage_init()==ESP_OK);assert(ms51_storage_get_info(&info)==ESP_OK);
        assert(info.generation==initial.generation && !strcmp(info.filename,"old.bin"));
    }
    uint8_t binary[32768];for(size_t i=0;i<sizeof(binary);i++)binary[i]=(uint8_t)(i*31);
    assert(receive(binary,sizeof(binary),7,123,"cut.bin",NULL)!=ESP_OK);
    image_test_limit=4095;assert(receive(old,sizeof(old),3,SIZE_MAX,"nomem.bin",NULL)==ESP_ERR_NO_MEM);image_test_limit=4096;
    assert(ms51_storage_init()==ESP_OK);assert(ms51_storage_get_info(&info)==ESP_OK);assert(info.generation==initial.generation);
    /* Fail halfway through every data/coverage/header/commit write, then reboot. */
    writes=0;assert(receive(old,sizeof(old),2,SIZE_MAX,"next.bin",NULL)==ESP_OK);int total_writes=writes;
    for(int step=0;step<total_writes;step++) {
        memcpy(flash,saved,sizeof(flash));assert(ms51_storage_init()==ESP_OK);
        writes=0;fail_write=step;
        assert(receive(old,sizeof(old),2,SIZE_MAX,"partial.bin",NULL)!=ESP_OK);
        fail_write=-1;assert(ms51_storage_init()==ESP_OK);assert(ms51_storage_get_info(&info)==ESP_OK);
        assert(info.generation==initial.generation && !strcmp(info.filename,"old.bin"));
    }
    ms51_storage_image_t mapped={0};assert(ms51_storage_acquire_image(0,&mapped)==ESP_OK);
    assert(receive(old,sizeof(old),2,SIZE_MAX,"busy.bin",NULL)==ESP_ERR_INVALID_STATE);ms51_storage_release_image(&mapped);
    image_test_limit=40960;ms51_image_t expected;
    assert(ms51_image_parse_upload(binary,sizeof(binary),"full.bin",&expected)==ESP_OK);
    image_test_limit=image_test_allocated+4096;
    size_t chunks[]={1,7,511,512};
    for(size_t i=0;i<sizeof(chunks)/sizeof(*chunks);i++) {
        input_t input={binary,sizeof(binary),0,chunks[i],SIZE_MAX};
        assert(ms51_storage_receive(read_input,&input,sizeof(binary),"full.bin",NULL)==ESP_OK);
        check_image(&expected);
    }
    ms51_image_free(&expected);image_test_limit=4096;
    /* Compare actual production sparse HEX against the original canonical parser. */
    assert(argc==2);FILE *f=fopen(argv[1],"rb");assert(f);fseek(f,0,SEEK_END);long len=ftell(f);rewind(f);
    uint8_t *hex=malloc(len);assert(hex && fread(hex,1,len,f)==(size_t)len);fclose(f);
    image_test_limit=40960;assert(ms51_image_parse_upload(hex,len,"production.hex",&expected)==ESP_OK);
    image_test_limit=image_test_allocated+4096;
    input_t input={hex,(size_t)len,0,7,SIZE_MAX};
    assert(ms51_storage_receive(read_input,&input,len,"production.hex",NULL)==ESP_OK);
    check_image(&expected);assert(ms51_storage_init()==ESP_OK);check_image(&expected);
    ms51_image_free(&expected);free(hex);assert(image_test_allocated==0);
    puts("PASS 4KiB staging: full BIN, production sparse HEX, chunk boundaries, corruption/EOF/overlap/bounds, interrupted reader/writer, allocation failure, mapped-image exclusion, reboot rollback, byte-identical payload");
}
