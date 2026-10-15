#include "gm_plugin.h"
#include "gm_plugin_system_events.h"

#define WIDTH 600U
#define HEIGHT 350U
#define STRIDE (WIDTH / 2U)
#define FRAME_BYTES (STRIDE * HEIGHT)
#define CHUNK_BYTES 4096U
#define REQUEST_CHANNEL 0x4956U
#define RESPONSE_CHANNEL 0x4957U
#define OP_HELLO 1U
#define OP_BEGIN 2U
#define OP_CHUNK 3U
#define OP_COMMIT 4U
#define OP_BEGIN_LZ4 5U
#define STATUS_OK 0U
#define STATUS_DEFERRED 1U
#define STATUS_INVALID 2U
#define STATUS_MEMORY 3U
#define STATUS_DRAW 4U
#define STATUS_STATE 5U
#define STATUS_UNSUPPORTED 6U

static const gm_plugin_host_api_t *s_host;
static const gm_plugin_system_events_extension_api_t *s_events;
static const gm_plugin_lz4_extension_api_t *s_lz4;
/* One decoded frame, plus a smaller temporary compressed block until commit.
 * Peak requested heap is < 210000 bytes; raw transfer uses only 105000. */
static uint8_t *s_frame, *s_compressed;
static uint32_t s_payload_bytes;
static uint32_t s_session, s_offset, s_expected_hash, s_idle_ms;
static uint16_t s_sequence;
static bool s_running, s_receiving, s_ready, s_call_active, s_redraw;
static uint8_t s_last_ack[20];
static uint32_t s_last_hash, s_last_length;
static bool s_cached;

static uint16_t read16(const uint8_t *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
static uint32_t read32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void write16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void write32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}
static uint32_t hash_bytes(const uint8_t *p, uint32_t length) {
    uint32_t value = 2166136261U;
    while (length--) value = (value ^ *p++) * 16777619U;
    return value;
}
static void clear_compressed(void) {
    if (s_compressed != NULL) s_host->free(s_compressed);
    s_compressed = NULL;
}
static void clear_frame(void) {
    clear_compressed();
    s_payload_bytes = 0U;
    if (s_frame != NULL) s_host->free(s_frame);
    s_frame = NULL;
    s_receiving = false; s_ready = false; s_redraw = false;
    s_session = 0U; s_offset = 0U; s_sequence = 0U; s_cached = false;
}
static gm_plugin_result_t draw_frame(void) {
    uint16_t next_y = 0U;
    if (!s_running || s_call_active || !s_ready) return GM_PLUGIN_ESTATE;
    while (next_y < HEIGHT) {
        gm_plugin_framebuffer_surface_t surface;
        uint32_t end;
        gm_plugin_rect_t dirty;
        gm_plugin_result_t result = s_host->graphics.framebuffer.lock(next_y, &surface);
        if (result != GM_PLUGIN_OK) return result;
        end = (uint32_t)surface.y + surface.height;
        if (surface.pixels == NULL || surface.width < WIDTH || surface.stride < STRIDE ||
            surface.y > next_y || end <= next_y || end > HEIGHT) {
            (void)s_host->graphics.framebuffer.unlock(NULL, false);
            return GM_PLUGIN_EINVAL;
        }
        for (uint16_t row = next_y; row < end; ++row) {
            uint8_t *target = surface.pixels + (uint32_t)(row - surface.y) * surface.stride;
            const uint8_t *source = s_frame + (uint32_t)row * STRIDE;
            for (uint16_t x = 0U; x < STRIDE; ++x) target[x] = source[x];
        }
        dirty.x = 0; dirty.y = (int16_t)next_y;
        dirty.width = WIDTH; dirty.height = (uint16_t)(end - next_y);
        result = s_host->graphics.framebuffer.unlock(&dirty, end == HEIGHT);
        if (result != GM_PLUGIN_OK) return result;
        next_y = (uint16_t)end;
    }
    s_redraw = false;
    return GM_PLUGIN_OK;
}
static void call_event(void *context, const gm_plugin_system_event_t *event) {
    (void)context;
    if (event == NULL || event->struct_size < sizeof(*event) ||
        event->type != GM_PLUGIN_SYSTEM_EVENT_CALL_UI) return;
    s_call_active = event->active;
    if (!s_call_active && s_ready) s_redraw = true;
}
static gm_plugin_result_t start(void *context) {
    gm_plugin_display_info_t display;
    gm_plugin_result_t result;
    (void)context;
    if (s_host->display_get_info(&display) != GM_PLUGIN_OK ||
        display.width != WIDTH || display.height != HEIGHT ||
        display.pixel_format != GM_PLUGIN_PIXEL_GRAY_4) return GM_PLUGIN_ENOTSUP;
    s_lz4 = NULL;
    if (s_host->extension_get != NULL) {
        const void *api = NULL;
        if (s_host->extension_get(GM_PLUGIN_EXTENSION_LZ4, &api) == GM_PLUGIN_OK && api != NULL) {
            const gm_plugin_lz4_extension_api_t *lz4 = (const gm_plugin_lz4_extension_api_t *)api;
            if (lz4->decompress_safe != NULL) s_lz4 = lz4;
        }
    }
    s_running = true; s_call_active = false;
    result = gm_plugin_system_events_get(s_host, &s_events);
    if (result == GM_PLUGIN_ENOTSUP) { s_events = NULL; return GM_PLUGIN_OK; }
    if (result == GM_PLUGIN_OK) result = s_events->subscribe(call_event, NULL);
    if (result != GM_PLUGIN_OK) {
        if (s_events != NULL) s_events->unsubscribe();
        s_events = NULL; s_running = false;
    }
    return result;
}
static void stop(void *context) {
    (void)context;
    s_running = false;
    if (s_events != NULL) s_events->unsubscribe();
    s_events = NULL; s_call_active = false;
    clear_frame();
}
static void suspend(void *context) {
    (void)context;
    s_running = false;
    if (s_receiving) clear_frame();
    s_cached = false;
}
static void resume(void *context) {
    (void)context;
    s_running = true;
    if (s_ready) s_redraw = true;
}
static uint8_t command(const uint8_t *data, uint32_t length) {
    uint8_t op = data[1];
    uint16_t seq = read16(data + 2);
    uint32_t session = read32(data + 4), offset = read32(data + 8);
    if (!s_running) return STATUS_STATE;
    if (op == OP_HELLO) return length == 12U && seq == 0U && offset == 0U ? STATUS_OK : STATUS_INVALID;
    if (op == OP_BEGIN || op == OP_BEGIN_LZ4) {
        uint32_t payload_bytes = FRAME_BYTES;
        if (length != (op == OP_BEGIN_LZ4 ? 28U : 20U) || seq != 1U || offset != 0U ||
            read16(data + 12) != WIDTH || read16(data + 14) != HEIGHT) return STATUS_INVALID;
        if (op == OP_BEGIN_LZ4) {
            payload_bytes = read32(data + 20);
            if (payload_bytes == 0U || payload_bytes >= FRAME_BYTES || read32(data + 24) != FRAME_BYTES)
                return STATUS_INVALID;
            if (s_lz4 == NULL) return STATUS_UNSUPPORTED;
        }
        /* New BEGIN discards incomplete input but never presents partial pixels. */
        clear_compressed();
        s_receiving = false; s_ready = false; s_redraw = false;
        if (s_frame == NULL) s_frame = s_host->alloc(FRAME_BYTES);
        if (s_frame == NULL) { clear_frame(); return STATUS_MEMORY; }
        if (op == OP_BEGIN_LZ4) {
            s_compressed = s_host->alloc(payload_bytes);
            if (s_compressed == NULL) { clear_frame(); return STATUS_MEMORY; }
        }
        s_payload_bytes = payload_bytes;
        s_session = session; s_offset = 0U; s_sequence = 2U; s_idle_ms = 0U;
        s_expected_hash = read32(data + 16);
        s_receiving = true;
        return STATUS_OK;
    }
    if (!s_receiving || session != s_session || seq != s_sequence || offset != s_offset) return STATUS_STATE;
    if (op == OP_CHUNK) {
        uint32_t count = length - 12U;
        if (count == 0U || count > CHUNK_BYTES || count > s_payload_bytes - s_offset) return STATUS_INVALID;
        uint8_t *target = s_compressed != NULL ? s_compressed : s_frame;
        for (uint32_t i = 0U; i < count; ++i) target[s_offset + i] = data[12U + i];
        s_offset += count; s_sequence++; s_idle_ms = 0U;
        return STATUS_OK;
    }
    if (op == OP_COMMIT) {
        if (length != 12U || s_offset != s_payload_bytes) return STATUS_INVALID;
        if (s_compressed != NULL) {
            int32_t decoded = s_lz4->decompress_safe(s_compressed, s_frame,
                (int32_t)s_payload_bytes, (int32_t)FRAME_BYTES);
            clear_compressed();
            if (decoded != (int32_t)FRAME_BYTES) { clear_frame(); return STATUS_INVALID; }
        }
        if (hash_bytes(s_frame, FRAME_BYTES) != s_expected_hash) { clear_frame(); return STATUS_INVALID; }
        s_receiving = false; s_ready = true;
        if (s_call_active) { s_redraw = true; return STATUS_DEFERRED; }
        return draw_frame() == GM_PLUGIN_OK ? STATUS_OK : STATUS_DRAW;
    }
    return STATUS_INVALID;
}
static bool on_event(void *context, const gm_plugin_event_t *event) {
    const uint8_t *data;
    uint32_t length, digest;
    uint8_t status;
    (void)context;
    if (event == NULL) return false;
    if (event->type == GM_PLUGIN_EVENT_CONNECTION) {
        if (!event->data.connection.connected) {
            if (s_receiving) clear_frame();
            s_cached = false;
        }
        return true;
    }
    if (event->type != GM_PLUGIN_EVENT_BT_MESSAGE || event->data.bt.channel != REQUEST_CHANNEL) return false;
    data = event->data.bt.data; length = event->data.bt.length;
    if (data == NULL || length < 12U || length > CHUNK_BYTES + 12U || data[0] != 1U || read32(data + 4) == 0U) return true;
    digest = hash_bytes(data, length);
    /* Sequential stop-and-wait sender: replay only the last identical packet.
     * This covers lost BEGIN, CHUNK and COMMIT acknowledgements without redrawing. */
    if (s_cached && s_last_length == length && s_last_hash == digest &&
        s_last_ack[1] == (uint8_t)(data[1] | 128U) &&
        read16(s_last_ack + 2) == read16(data + 2) && read32(s_last_ack + 4) == read32(data + 4)) {
        s_idle_ms = 0U;
        (void)s_host->bt_send(RESPONSE_CHANNEL, s_last_ack, sizeof(s_last_ack));
        return true;
    }
    status = command(data, length);
    s_last_ack[0] = 1U; s_last_ack[1] = (uint8_t)(data[1] | 128U);
    write16(s_last_ack + 2, read16(data + 2)); write32(s_last_ack + 4, read32(data + 4));
    write32(s_last_ack + 8, s_offset);
    s_last_ack[12] = status; s_last_ack[13] = s_lz4 != NULL ? 1U : 0U;
    write16(s_last_ack + 14, WIDTH); write16(s_last_ack + 16, HEIGHT); write16(s_last_ack + 18, CHUNK_BYTES);
    s_last_hash = digest; s_last_length = length; s_cached = status <= STATUS_DEFERRED;
    (void)s_host->bt_send(RESPONSE_CHANNEL, s_last_ack, sizeof(s_last_ack));
    return true;
}
static void loop(void *context, uint32_t elapsed_ms) {
    (void)context;
    if (s_receiving) {
        if (elapsed_ms >= 30000U - s_idle_ms) clear_frame();
        else s_idle_ms += elapsed_ms;
    }
    if (s_running && !s_call_active && s_ready && s_redraw) {
        /* A failed restoration is logged once; phone can explicitly resend. */
        s_redraw = false;
        if (draw_frame() != GM_PLUGIN_OK) s_host->log("image_viewer: framebuffer restore failed");
    }
}
gm_plugin_result_t gm_plugin_entry(const gm_plugin_host_api_t *host, gm_plugin_descriptor_t *plugin) {
    const gm_plugin_capabilities_t required = GM_PLUGIN_CAP_BLUETOOTH | GM_PLUGIN_CAP_DISPLAY_BITMAP;
    if (host == NULL || plugin == NULL || host->struct_size < GM_PLUGIN_HOST_API_MIN_SIZE ||
        plugin->struct_size < GM_PLUGIN_DESCRIPTOR_MIN_SIZE ||
        !GM_PLUGIN_VERSION_COMPATIBLE(host->abi_version, GM_PLUGIN_ABI_MIN_VERSION)) return GM_PLUGIN_ENOTSUP;
    if ((host->capabilities & required) != required || host->alloc == NULL || host->free == NULL ||
        host->display_get_info == NULL || host->graphics.framebuffer.lock == NULL ||
        host->graphics.framebuffer.unlock == NULL || host->bt_send == NULL || host->log == NULL) return GM_PLUGIN_ENOTSUP;
    s_host = host;
    plugin->abi_version = GM_PLUGIN_ABI_MIN_VERSION;
    plugin->on_start = start; plugin->on_stop = stop; plugin->on_unload = stop;
    plugin->on_suspend = suspend; plugin->on_resume = resume;
    plugin->on_loop = loop; plugin->on_event = on_event;
    return GM_PLUGIN_OK;
}
