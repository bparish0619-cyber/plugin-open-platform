#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* Test Host links the reference LZ4 library; production uses Host extension 2. */
extern int LZ4_decompress_safe(const char *, char *, int, int);
extern int LZ4_compress_default(const char *, char *, int, int);
#include "../examples/image_viewer/image_viewer.c"

static uint8_t pixels[FRAME_BYTES], ack[20], request[CHUNK_BYTES + 12U];
static unsigned locks, presents, allocations, decode_calls;
static unsigned allocation_limit = 2U;
static bool no_lz4;
static bool fail_alloc, fail_lock, bad_surface, old_firmware;
static gm_plugin_system_event_callback_t call_cb;
static void *call_context;
static gm_plugin_result_t info(gm_plugin_display_info_t *display) {
    display->width = WIDTH; display->height = HEIGHT;
    display->pixel_format = GM_PLUGIN_PIXEL_GRAY_4; return GM_PLUGIN_OK;
}
static void *allocate(size_t size) { if (fail_alloc || allocations >= allocation_limit) return NULL; ++allocations; return malloc(size); }
static void release(void *p) { assert(allocations); --allocations; free(p); }
static void log_message(const char *text, ...) { (void)text; }
static gm_plugin_result_t lock_surface(uint16_t y, gm_plugin_framebuffer_surface_t *surface) {
    assert(!s_call_active); ++locks;
    if (fail_lock) return GM_PLUGIN_EBUSY;
    /* Nonstandard 73-row slices catch accidental half-screen assumptions. */
    surface->pixels = pixels + (uint32_t)y * STRIDE;
    surface->y = y; surface->width = WIDTH; surface->stride = STRIDE;
    surface->height = bad_surface ? 0U : (HEIGHT-y < 73U ? HEIGHT-y : 73U);
    return GM_PLUGIN_OK;
}
static gm_plugin_result_t unlock_surface(const gm_plugin_rect_t *dirty, bool present) {
    if (present) { assert(dirty && (uint32_t)dirty->y + dirty->height == HEIGHT); ++presents; }
    return GM_PLUGIN_OK;
}
static gm_plugin_result_t send_ack(gm_plugin_bt_channel_t channel, const void *data, uint32_t length) {
    assert(channel == RESPONSE_CHANNEL && length == sizeof(ack)); memcpy(ack,data,length);
#ifdef IMAGE_VIEWER_PIPE
    assert(fwrite(data,1,length,stdout)==length); fflush(stdout);
#endif
    return GM_PLUGIN_OK;
}
static gm_plugin_result_t subscribe(gm_plugin_system_event_callback_t callback, void *context) {
    call_cb=callback; call_context=context; return GM_PLUGIN_OK;
}
static void unsubscribe(void) { call_cb=NULL; }
static const gm_plugin_system_events_extension_api_t system_events={subscribe,unsubscribe};
static int32_t decode_block(const void *src, void *dst, int32_t size, int32_t capacity) {
    ++decode_calls;
    assert(size > 0 && size < (int32_t)FRAME_BYTES && capacity == (int32_t)FRAME_BYTES);
    return LZ4_decompress_safe(src, dst, size, capacity);
}
static const gm_plugin_lz4_extension_api_t lz4_api = {NULL, NULL, decode_block};
static gm_plugin_result_t discover(gm_plugin_extension_id_t id, const void **api) {
    *api = id == GM_PLUGIN_EXTENSION_SYSTEM_EVENTS && !old_firmware ? &system_events : NULL;
    if (id == GM_PLUGIN_EXTENSION_LZ4 && !no_lz4) *api = &lz4_api;
    return *api ? GM_PLUGIN_OK : GM_PLUGIN_ENOTSUP;
}
static gm_plugin_host_api_t host;
static gm_plugin_descriptor_t plugin;
static void setup(void) {
    memset(&host,0,sizeof(host)); memset(&plugin,0,sizeof(plugin));
    host.struct_size=sizeof(host); host.abi_version=GM_PLUGIN_ABI_MIN_VERSION;
    host.capabilities=GM_PLUGIN_CAP_BLUETOOTH | GM_PLUGIN_CAP_DISPLAY_BITMAP;
    host.alloc=allocate; host.free=release; host.log=log_message; host.bt_send=send_ack;
    host.display_get_info=info; host.extension_get=discover;
    host.graphics.framebuffer.lock=lock_surface; host.graphics.framebuffer.unlock=unlock_surface;
    plugin.struct_size=sizeof(plugin);
    assert(gm_plugin_entry(&host,&plugin)==GM_PLUGIN_OK);
    assert(plugin.on_start(NULL)==GM_PLUGIN_OK);
}
static void dispatch(uint32_t length) {
    gm_plugin_event_t event={0}; event.type=GM_PLUGIN_EVENT_BT_MESSAGE;
    event.data.bt.channel=REQUEST_CHANNEL; event.data.bt.data=request; event.data.bt.length=length;
    assert(plugin.on_event(NULL,&event));
}
#ifndef IMAGE_VIEWER_PIPE
static void header(uint8_t op, uint16_t seq, uint32_t offset) {
    memset(request,0,sizeof(request)); request[0]=1; request[1]=op;
    write16(request+2,seq); write32(request+4,42); write32(request+8,offset);
}
static uint8_t source[FRAME_BYTES];
static void begin_frame(void) {
    header(OP_BEGIN,1,0); write16(request+12,WIDTH); write16(request+14,HEIGHT);
    write32(request+16,hash_bytes(source,FRAME_BYTES)); dispatch(20);
}
static uint16_t send_payload(const uint8_t *data, uint32_t size) {
    uint16_t seq=2;
    for (uint32_t offset=0;offset<size;offset+=CHUNK_BYTES) {
        uint32_t count=size-offset < CHUNK_BYTES ? size-offset : CHUNK_BYTES;
        header(OP_CHUNK,seq++,offset); memcpy(request+12,data+offset,count); dispatch(12+count);
        assert(ack[12]==STATUS_OK && read32(ack+8)==offset+count);
        dispatch(12+count); /* Lost ACK: duplicate must not append pixels twice. */
        assert(ack[12]==STATUS_OK && read32(ack+8)==offset+count);
    }
    return seq;
}
static uint16_t send_chunks(void) { return send_payload(source, FRAME_BYTES); }
static void overlay(bool active) {
    gm_plugin_system_event_t event={0}; event.struct_size=sizeof(event);
    event.type=GM_PLUGIN_SYSTEM_EVENT_CALL_UI; event.active=active;
    assert(call_cb); call_cb(call_context,&event);
}
static uint8_t compressed[FRAME_BYTES + FRAME_BYTES / 255U + 16U];
static void begin_lz4(uint32_t size) {
    header(OP_BEGIN_LZ4,1,0); write16(request+12,WIDTH); write16(request+14,HEIGHT);
    write32(request+16,hash_bytes(source,FRAME_BYTES));
    write32(request+20,size); write32(request+24,FRAME_BYTES); dispatch(28);
}
static void test_lz4(void) {
    old_firmware=false;
    assert(plugin.on_start(NULL)==GM_PLUGIN_OK);
    header(OP_HELLO,0,0); dispatch(12); assert(ack[13]&1);
    uint32_t random=1234567U;
    for(uint32_t i=0;i<FRAME_BYTES/2U;i++) {
        random^=random<<13; random^=random>>17; random^=random<<5;
        source[i]=(uint8_t)random; source[i+FRAME_BYTES/2U]=source[i];
    }
    int size=LZ4_compress_default((const char *)source,(char *)compressed,FRAME_BYTES,sizeof(compressed));
    assert(size>(int)CHUNK_BYTES && size<(int)FRAME_BYTES);
    unsigned before=presents;
    begin_lz4((uint32_t)size); assert(ack[12]==0 && allocations==2);
    dispatch(28); assert(allocations==2 && s_offset==0); /* duplicate BEGIN */
    uint16_t seq=send_payload(compressed,(uint32_t)size);
    assert(presents==before && decode_calls==0);
    header(OP_COMMIT,seq,(uint32_t)size); dispatch(12);
    assert(ack[12]==0 && allocations==1 && decode_calls==1 && presents==before+1);
    assert(!memcmp(source,pixels,FRAME_BYTES));
    dispatch(12); assert(decode_calls==1 && presents==before+1); /* lost final ACK */
    /* Invalid sizes rejected before allocation, decode, or touching pixels. */
    begin_lz4(0); assert(ack[12]==STATUS_INVALID);
    begin_lz4(FRAME_BYTES); assert(ack[12]==STATUS_INVALID);
    begin_lz4((uint32_t)size); plugin.on_loop(NULL,30000); assert(allocations==0);
    begin_lz4((uint32_t)size); plugin.on_suspend(NULL); assert(allocations==0); plugin.on_resume(NULL);
    begin_lz4((uint32_t)size); gm_plugin_event_t event={0}; event.type=GM_PLUGIN_EVENT_CONNECTION;
    assert(plugin.on_event(NULL,&event)); assert(allocations==0);
    allocation_limit=1; begin_lz4((uint32_t)size); assert(ack[12]==STATUS_MEMORY && allocations==0); allocation_limit=2;
    /* Malformed block and decoded length mismatch cannot present partial frames. */
    before=presents;
    begin_lz4(3); const uint8_t invalid[3]={0,0,0}; seq=send_payload(invalid,3);
    header(OP_COMMIT,seq,3); dispatch(12); assert(ack[12]==STATUS_INVALID && allocations==0 && presents==before);
    begin_lz4(2); const uint8_t short_block[2]={0x10,0}; seq=send_payload(short_block,2);
    header(OP_COMMIT,seq,2); dispatch(12); assert(ack[12]==STATUS_INVALID && allocations==0 && presents==before);
    begin_lz4((uint32_t)size); s_expected_hash^=1; seq=send_payload(compressed,(uint32_t)size);
    header(OP_COMMIT,seq,(uint32_t)size); dispatch(12); assert(ack[12]==STATUS_INVALID && allocations==0 && presents==before);
    /* Compressed path also respects calls, and keeps only the decoded frame. */
    overlay(true); begin_lz4((uint32_t)size); seq=send_payload(compressed,(uint32_t)size);
    header(OP_COMMIT,seq,(uint32_t)size); dispatch(12);
    assert(ack[12]==STATUS_DEFERRED && allocations==1 && presents==before);
    overlay(false); plugin.on_loop(NULL,0); assert(presents==before+1 && !memcmp(source,pixels,FRAME_BYTES));
    begin_lz4((uint32_t)size); begin_frame(); assert(allocations==1 && s_compressed==NULL);
    plugin.on_stop(NULL); assert(allocations==0);
    no_lz4=true; assert(plugin.on_start(NULL)==GM_PLUGIN_OK);
    header(OP_HELLO,0,0); dispatch(12); assert(!(ack[13]&1));
    begin_lz4((uint32_t)size); assert(ack[12]==STATUS_UNSUPPORTED && allocations==0);
    begin_frame(); seq=send_chunks(); header(OP_COMMIT,seq,FRAME_BYTES); dispatch(12); assert(ack[12]==STATUS_OK);
    plugin.on_stop(NULL); assert(allocations==0); no_lz4=false;
}
int main(void) {
    for(uint32_t i=0;i<FRAME_BYTES;i++) source[i]=(uint8_t)(i*31U);
    setup();
    header(OP_HELLO,0,0); dispatch(12); assert(ack[12]==0 && read16(ack+14)==600);
    fail_alloc=true; begin_frame(); assert(ack[12]==STATUS_MEMORY && allocations==0); fail_alloc=false;
    begin_frame(); assert(ack[12]==0 && allocations==1); dispatch(20); assert(s_offset==0 && allocations==1);
    header(OP_CHUNK,3,0); request[12]=1; dispatch(13); assert(ack[12]==STATUS_STATE);
    header(OP_CHUNK,2,1); dispatch(13); assert(ack[12]==STATUS_STATE);
    header(OP_COMMIT,2,0); dispatch(12); assert(ack[12]==STATUS_INVALID && presents==0);
    uint16_t seq=send_chunks(); assert(presents==0 && locks==0);
    header(OP_COMMIT,seq,FRAME_BYTES); dispatch(12);
    assert(ack[12]==STATUS_OK && presents==1 && locks==5 && !memcmp(source,pixels,FRAME_BYTES));
    dispatch(12); assert(ack[12]==0 && presents==1);
    /* Call overlay receives and validates the next complete image but yields pixels. */
    overlay(true); memset(source,0x9c,sizeof(source)); begin_frame(); seq=send_chunks();
    header(OP_COMMIT,seq,FRAME_BYTES); dispatch(12);
    assert(ack[12]==STATUS_DEFERRED && presents==1);
    overlay(false); assert(presents==1); plugin.on_loop(NULL,10);
    assert(presents==2 && !memcmp(source,pixels,FRAME_BYTES));
    overlay(true); overlay(false); plugin.on_loop(NULL,10); assert(presents==3);
    plugin.on_suspend(NULL); assert(!s_running); plugin.on_resume(NULL); plugin.on_loop(NULL,0); assert(presents==4);
    /* Failed or interrupted transfers cannot present a partial image. */
    begin_frame(); seq=send_chunks(); s_frame[0]^=1;
    header(OP_COMMIT,seq,FRAME_BYTES); dispatch(12); assert(ack[12]==STATUS_INVALID && allocations==0 && presents==4);
    begin_frame(); plugin.on_loop(NULL,30000); assert(allocations==0 && !s_receiving);
    begin_frame(); plugin.on_suspend(NULL); assert(allocations==0); plugin.on_resume(NULL);
    begin_frame(); gm_plugin_event_t event={0}; event.type=GM_PLUGIN_EVENT_CONNECTION;
    event.data.connection.connected=false; assert(plugin.on_event(NULL,&event)); assert(allocations==0);
    begin_frame(); seq=send_chunks(); fail_lock=true;
    header(OP_COMMIT,seq,FRAME_BYTES); dispatch(12); assert(ack[12]==STATUS_DRAW && presents==4); fail_lock=false;
    begin_frame(); seq=send_chunks(); bad_surface=true;
    header(OP_COMMIT,seq,FRAME_BYTES); dispatch(12); assert(ack[12]==STATUS_DRAW); bad_surface=false;
    plugin.on_stop(NULL); assert(allocations==0 && call_cb==NULL);
    assert(plugin.on_start(NULL)==GM_PLUGIN_OK); plugin.on_stop(NULL);
    old_firmware=true; assert(plugin.on_start(NULL)==GM_PLUGIN_OK); plugin.on_stop(NULL);
    test_lz4();
    host.struct_size=0; assert(gm_plugin_entry(&host,&plugin)!=GM_PLUGIN_OK);
    puts("image_viewer native tests passed"); return 0;
}
#else
int main(int argc, char **argv) {
    uint8_t size[4]; setup();
    while(fread(size,1,4,stdin)==4) {
        uint32_t n=read32(size); assert(n<=sizeof(request));
        assert(fread(request,1,n,stdin)==n); dispatch(n);
    }
    assert(argc==2 && s_ready && presents>0);
    FILE *output=fopen(argv[1],"wb"); assert(output);
    assert(fwrite(pixels,1,FRAME_BYTES,output)==FRAME_BYTES); fclose(output);
    plugin.on_stop(NULL); assert(allocations==0); return 0;
}
#endif
