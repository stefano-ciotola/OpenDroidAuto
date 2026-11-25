#pragma once

#include <cstdint>
#include <string>

// Experimental decoder switches, passed from Java as a bit mask.
// Keep the bit values in sync with the OPTION_* constants of
// it.smg.libs.omxvideocodec.OMXVideoCodec.
struct DecoderOptions {
    enum : int32_t {
        kOneInputBuffer   = 1 << 0,
        kNvidiaLowLatency = 1 << 1,
        kLegacyDrop       = 1 << 2,
        kStats            = 1 << 3,
        kSpsLowLatency    = 1 << 4,
    };

    // Hand OMXCodec a single input buffer at a time
    // (OMXCodec::kOnlySubmitOneInputBufferAtOneTime).
    bool oneInputBuffer = false;
    // Enable the Nvidia "video telephony" decoder parameters on the OMX node.
    bool nvidiaLowLatency = false;
    // Previous behaviour: flush the queue after 8 frames / 250 ms of backlog
    // and wait for an IDR frame.
    bool legacyDrop = true;
    // Collect per-stage statistics (logcat + on-screen overlay).
    bool stats = false;
    // Rewrite the SPS so the decoder outputs each frame as soon as it is
    // decoded instead of holding a few back for reordering (SpsPatcher.h).
    bool spsLowLatency = false;

    static DecoderOptions fromBits(int32_t bits) {
        DecoderOptions options;
        options.oneInputBuffer = (bits & kOneInputBuffer) != 0;
        options.nvidiaLowLatency = (bits & kNvidiaLowLatency) != 0;
        options.legacyDrop = (bits & kLegacyDrop) != 0;
        options.stats = (bits & kStats) != 0;
        options.spsLowLatency = (bits & kSpsLowLatency) != 0;
        return options;
    }
};
