/*
 * VIGIL Emitter — a red-team beacon for validating the VIGIL tracker detector.
 *
 * WHAT IT IS. VIGIL's headline aim is to detect identity-rotating tracker clones,
 * the attack Positive Security's "Find You" demonstrated (~2000 Apple Find My keys,
 * one beacon per key every 30 s) and every shipping detector misses. v1 only
 * catches clones that keep talking: any silence over 20 s resets it, so it misses
 * Find You's own firmware. Validating either result needs a ground-truth-positive
 * emitter — a real radio, carried by the researcher, that co-moves and rotates its
 * identity. Paper §7 and docs/detection-rotation-clone.md §5 specify an ESP32 for
 * the job; the Flipper's STM32WB55 does it with hardware already in the pocket. Its
 * duty settings emit in 30 s windows, so none reproduces Find You's one beacon per
 * key.
 *
 * WHY IT IS NOT A TRACKER. A real Find My beacon advertises a P-224 public key
 * registered to someone's Apple ID; that registration is what lets the owner pull
 * location reports back out of the Find My network. The keys here are raw CSPRNG
 * output, generated on the Flipper and never registered anywhere. The beacon
 * therefore reproduces the advertisement *shape* VIGIL parses while being unable to
 * report its position to anyone — a detection target, not a functioning tracker.
 * Carry it yourself; see README.md before using it anywhere near other people.
 *
 * The two knobs are the two axes VIGIL's offline harness sweeps
 * (app/src/test/kotlin/org/soulstone/vigil/eval/), on the same clock model, so a
 * field run is comparable to a simulated one:
 *   - rotation period  30 s … static  (the rotation-rate squeeze, paper §5.4)
 *   - duty cycle       100% … 10%     (the duty-cycling adaptation, design doc §4.2)
 */

#include <furi.h>
#include <furi_hal.h>
#include <furi_hal_bt.h>
#include <furi_hal_random.h>
#include <gui/gui.h>
#include <input/input.h>
#include <extra_beacon.h>

#define TAG "VigilEmitter"

/* Separated Find My trackers advertise every ~2 s; match that so RSSI cadence and
 * the detector's per-advert accounting look like the real thing. */
#define ADV_INTERVAL_MS 2000

/* Duty cycling is expressed as a fixed 30 s emitting window plus a computed silent
 * window, which is exactly how SimTraces.CloneConfig models it. */
#define DUTY_ON_MS 30000UL

typedef struct {
    const char* label;
    uint32_t ms;
} RotationOption;

/* Spans the squeeze: below ~85 s the churn path should catch it; the identity path
 * needs one key to outlast the sensitivity's span (30/45/90 min on High/Medium/Low;
 * the harness has 45 min itself just missed at the default Medium), and the middle
 * is the gap the harness measured. "static" holds one identity for the whole run,
 * across duty-cycle silences too — a spec-compliant separated tag. */
static const RotationOption ROTATIONS[] = {
    {"30 s", 30000UL},
    {"60 s", 60000UL},
    {"90 s", 90000UL},
    {"2 min", 120000UL},
    {"5 min", 300000UL},
    {"15 min", 900000UL},
    {"45 min", 2700000UL},
    {"static", 0xFFFFFFFFUL},
};
#define ROTATION_COUNT (sizeof(ROTATIONS) / sizeof(ROTATIONS[0]))

typedef struct {
    const char* label;
    uint8_t percent;
} DutyOption;

static const DutyOption DUTIES[] = {
    {"100%", 100},
    {"50%", 50},
    {"20%", 20},
    {"10%", 10},
};
#define DUTY_COUNT (sizeof(DUTIES) / sizeof(DUTIES[0]))

typedef struct {
    const char* label;
    GapAdvPowerLevelInd level;
} PowerOption;

static const PowerOption POWERS[] = {
    {"0 dBm", GapAdvPowerLevel_0dBm},
    {"-4 dBm", GapAdvPowerLevel_Neg4dBm},
    {"-8.9 dBm", GapAdvPowerLevel_Neg8_85dBm},
    {"-14 dBm", GapAdvPowerLevel_Neg14_1dBm},
    {"-21 dBm", GapAdvPowerLevel_Neg20_85dBm},
    {"-40 dBm", GapAdvPowerLevel_Neg40dBm},
};
#define POWER_COUNT (sizeof(POWERS) / sizeof(POWERS[0]))

typedef enum {
    FieldRotation = 0,
    FieldDuty,
    FieldPower,
    FieldCount,
} Field;

typedef struct {
    FuriMessageQueue* queue;
    Gui* gui;
    ViewPort* view_port;
    FuriMutex* mutex;

    GapExtraBeaconConfig config;
    uint8_t adv_data[EXTRA_BEACON_MAX_DATA_SIZE];
    uint8_t adv_len;

    uint8_t rotation_idx;
    uint8_t duty_idx;
    uint8_t power_idx;
    Field field;

    bool running;
    bool emitting; /* within the "on" phase of the duty cycle */
    bool fault; /* last radio call failed — usually Bluetooth disabled */

    uint32_t started_ms;
    uint32_t key_epoch; /* rotation periods since started_ms when the current key began */
    uint32_t keys_emitted;
} VigilEmitter;

/* 64-bit intermediate: ticks * 1000 in 32 bits wraps after ~71.6 min at 1 kHz, which
 * forced an early rotation and jumped the duty phase and the on-screen timer. */
static uint32_t now_ms(void) {
    return (uint32_t)((uint64_t)furi_get_tick() * 1000ULL / furi_kernel_get_tick_frequency());
}

static uint32_t duty_off_ms(const VigilEmitter* app) {
    uint8_t pct = DUTIES[app->duty_idx].percent;
    if(pct >= 100) return 0;
    return DUTY_ON_MS * (100UL - pct) / pct;
}

/* Build one Find My "offline finding" advertisement around a fresh random key.
 *
 * Layout (OpenHaystack / Adam Catley's teardown), 31 bytes:
 *   [0]     0x1E        AD length: 30 bytes follow
 *   [1]     0xFF        AD type: manufacturer specific data
 *   [2..3]  0x4C 0x00   company id 0x004C (Apple), little-endian
 *   [4]     0x12        Apple message type: offline finding
 *   [5]     0x19        offline-finding payload length (25)
 *   [6]     status      bit 2 ("maintained") CLEAR => separated from owner
 *   [7..28] key[6..27]  22 public-key bytes
 *   [29]    key[0] >> 6 the key's top two bits
 *   [30]    0x00        hint
 *
 * Android hands VIGIL the manufacturer data with the length/type/company id
 * stripped, so TrackerParser sees [0x12, 0x19, status, key[6..27], bits, hint] and
 * keys its identity on bytes 3..24 — the 22 key bytes rotated here.
 */
static void vigil_build_payload(VigilEmitter* app) {
    uint8_t key[28];
    furi_hal_random_fill_buf(key, sizeof(key));

    uint8_t* d = app->adv_data;
    d[0] = 0x1E;
    d[1] = 0xFF;
    d[2] = 0x4C;
    d[3] = 0x00;
    d[4] = 0x12;
    d[5] = 0x19;
    d[6] = 0x00; /* separated: the only state a following tracker can be in */
    memcpy(&d[7], &key[6], 22);
    d[29] = key[0] >> 6;
    d[30] = 0x00;
    app->adv_len = 31;

    /* Apple derives the advertised address from the key, so both rotate together —
     * key[0] with the top two bits forced high (a static random address), then
     * key[1..5]. aci_gap_additional_beacon_start takes the address LSB-first.
     * VIGIL keys on the payload rather than the MAC, so this is about matching a
     * real tag's behaviour, not about being detected. */
    uint8_t mac[6] = {(uint8_t)(key[0] | 0xC0), key[1], key[2], key[3], key[4], key[5]};
    for(size_t i = 0; i < 6; i++) {
        app->config.address[i] = mac[5 - i];
    }
}

/* (Re)start the beacon with the current key. set_config is refused while the
 * beacon runs, so every (re)start is a stop/reconfigure/start cycle. */
static bool vigil_arm(VigilEmitter* app) {
    furi_hal_bt_extra_beacon_stop(); /* false when already stopped; not an error */

    app->config.min_adv_interval_ms = ADV_INTERVAL_MS;
    app->config.max_adv_interval_ms = ADV_INTERVAL_MS;
    app->config.adv_channel_map = GapAdvChannelMapAll;
    app->config.adv_power_level = POWERS[app->power_idx].level;
    app->config.address_type = GapAddressTypeRandom;

    if(!furi_hal_bt_extra_beacon_set_config(&app->config)) {
        FURI_LOG_E(TAG, "set_config failed");
        return false;
    }
    if(!furi_hal_bt_extra_beacon_set_data(app->adv_data, app->adv_len)) {
        FURI_LOG_E(TAG, "set_data failed");
        return false;
    }
    if(!furi_hal_bt_extra_beacon_start()) {
        FURI_LOG_E(TAG, "start failed (is Bluetooth enabled?)");
        return false;
    }
    return true;
}

/* Roll a new identity and (re)start the beacon with it. */
static bool vigil_rotate(VigilEmitter* app) {
    vigil_build_payload(app);
    if(!vigil_arm(app)) return false;
    app->keys_emitted++;
    return true;
}

static void vigil_stop(VigilEmitter* app) {
    furi_hal_bt_extra_beacon_stop();
    app->running = false;
    app->emitting = false;
}

static void vigil_start(VigilEmitter* app) {
    app->keys_emitted = 0;
    app->started_ms = now_ms();
    app->key_epoch = 0;
    app->running = true;
    app->emitting = true;
    app->fault = !vigil_rotate(app);
    if(app->fault) vigil_stop(app);
}

/* Advance the duty-cycle phase and rotate the key when the period is up.
 *
 * Both run on one absolute clock from the start of the run, the way SimTraces
 * models them (identity `clone-${ts / rotationMs}`, duty `ts % (on + off) < on`):
 * a key lasts one rotation period of wall time whether or not the radio is in a
 * silent window, and resuming after silence keeps the same key unless a rotation
 * boundary passed meanwhile. A silent window never mints a key of its own. */
static void vigil_tick(VigilEmitter* app) {
    if(!app->running) return;
    uint32_t elapsed = now_ms() - app->started_ms;

    uint32_t epoch = elapsed / ROTATIONS[app->rotation_idx].ms;
    bool rekey = epoch != app->key_epoch;

    uint32_t off = duty_off_ms(app);
    bool should_emit = off == 0 || (elapsed % (DUTY_ON_MS + off)) < DUTY_ON_MS;

    if(!should_emit) {
        if(app->emitting) {
            app->emitting = false;
            furi_hal_bt_extra_beacon_stop();
        }
        return;
    }
    if(!app->emitting || rekey) {
        app->emitting = true;
        app->key_epoch = epoch;
        app->fault = !(rekey ? vigil_rotate(app) : vigil_arm(app));
    }
}

static void draw_row(Canvas* canvas, int y, bool selected, const char* label, const char* value) {
    if(selected) {
        canvas_draw_str(canvas, 2, y, ">");
    }
    canvas_draw_str(canvas, 10, y, label);
    canvas_draw_str(canvas, 66, y, value);
}

static void vigil_draw(Canvas* canvas, void* ctx) {
    VigilEmitter* app = ctx;
    furi_mutex_acquire(app->mutex, FuriWaitForever);

    canvas_clear(canvas);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 10, "VIGIL Emitter");

    canvas_set_font(canvas, FontSecondary);
    const char* state = app->fault ? "BT OFF?" :
                        !app->running ? "idle" :
                        app->emitting ? "EMIT" :
                                        "quiet";
    canvas_draw_str_aligned(canvas, 126, 10, AlignRight, AlignBottom, state);
    canvas_draw_line(canvas, 0, 13, 127, 13);

    draw_row(canvas, 25, app->field == FieldRotation, "Rotation", ROTATIONS[app->rotation_idx].label);
    draw_row(canvas, 35, app->field == FieldDuty, "Duty", DUTIES[app->duty_idx].label);
    draw_row(canvas, 45, app->field == FieldPower, "TX power", POWERS[app->power_idx].label);

    char line[40];
    if(app->running) {
        uint32_t secs = (now_ms() - app->started_ms) / 1000UL;
        snprintf(
            line,
            sizeof(line),
            "%lu keys  %lu:%02lu",
            (unsigned long)app->keys_emitted,
            (unsigned long)(secs / 60),
            (unsigned long)(secs % 60));
    } else {
        snprintf(line, sizeof(line), "OK to start");
    }
    canvas_draw_str(canvas, 2, 57, line);
    canvas_draw_str_aligned(canvas, 126, 57, AlignRight, AlignBottom, "OK=run");

    furi_mutex_release(app->mutex);
}

static void vigil_input(InputEvent* event, void* ctx) {
    VigilEmitter* app = ctx;
    furi_message_queue_put(app->queue, event, FuriWaitForever);
}

static void adjust(VigilEmitter* app, int delta) {
    switch(app->field) {
    case FieldRotation:
        app->rotation_idx = (app->rotation_idx + ROTATION_COUNT + delta) % ROTATION_COUNT;
        break;
    case FieldDuty:
        app->duty_idx = (app->duty_idx + DUTY_COUNT + delta) % DUTY_COUNT;
        break;
    case FieldPower:
        app->power_idx = (app->power_idx + POWER_COUNT + delta) % POWER_COUNT;
        break;
    default:
        break;
    }
    /* TX power and the duty phase only take effect through set_config, which needs
     * the beacon stopped — so re-arm immediately with the new settings. A change
     * while running starts a new run: fresh key, clock and duty phase. */
    if(app->running) {
        app->started_ms = now_ms();
        app->key_epoch = 0;
        app->emitting = true;
        app->fault = !vigil_rotate(app);
    }
}

int32_t vigil_emitter_app(void* p) {
    UNUSED(p);

    VigilEmitter* app = malloc(sizeof(VigilEmitter));
    memset(app, 0, sizeof(VigilEmitter));
    app->queue = furi_message_queue_alloc(8, sizeof(InputEvent));
    app->mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    app->view_port = view_port_alloc();
    view_port_draw_callback_set(app->view_port, vigil_draw, app);
    view_port_input_callback_set(app->view_port, vigil_input, app);
    app->gui = furi_record_open(RECORD_GUI);
    gui_add_view_port(app->gui, app->view_port, GuiLayerFullscreen);

    bool exit = false;
    while(!exit) {
        InputEvent event;
        /* A short wait doubles as the rotation/duty timer tick. */
        if(furi_message_queue_get(app->queue, &event, 100) == FuriStatusOk) {
            if(event.type == InputTypeShort || event.type == InputTypeRepeat) {
                furi_mutex_acquire(app->mutex, FuriWaitForever);
                switch(event.key) {
                case InputKeyBack:
                    exit = true;
                    break;
                case InputKeyOk:
                    if(app->running) {
                        vigil_stop(app);
                        app->fault = false;
                    } else {
                        vigil_start(app);
                    }
                    break;
                case InputKeyUp:
                    app->field = (app->field + FieldCount - 1) % FieldCount;
                    break;
                case InputKeyDown:
                    app->field = (app->field + 1) % FieldCount;
                    break;
                case InputKeyLeft:
                    adjust(app, -1);
                    break;
                case InputKeyRight:
                    adjust(app, +1);
                    break;
                default:
                    break;
                }
                furi_mutex_release(app->mutex);
            }
        }

        furi_mutex_acquire(app->mutex, FuriWaitForever);
        vigil_tick(app);
        furi_mutex_release(app->mutex);
        view_port_update(app->view_port);
    }

    /* Never leave the radio advertising after the app closes. */
    vigil_stop(app);

    gui_remove_view_port(app->gui, app->view_port);
    furi_record_close(RECORD_GUI);
    view_port_free(app->view_port);
    furi_message_queue_free(app->queue);
    furi_mutex_free(app->mutex);
    free(app);
    return 0;
}
