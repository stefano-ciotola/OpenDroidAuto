#pragma once

#include <cstdint>
#include <string>

// Experimental decoder switches, passed from Java as a bit mask.
// Keep the bit values in sync with the OPTION_* constants of
// it.smg.libs.omxvideocodec.OMXVideoCodec.
struct DecoderOptions {
    enum : int32_t {
        kOneInputBuffer   = 1 << 0,
        // All three Nvidia parameters (older builds); the next three set one each.
        kNvidiaLowLatency = 1 << 1,
        kLegacyDrop       = 1 << 2,
        kStats            = 1 << 3,
        kSpsLowLatency    = 1 << 4,
        kNvidiaLowBuffer  = 1 << 5,
        kNvidiaNoDpb      = 1 << 6,
        kNvidiaFullFrame  = 1 << 7,
        kSpsLevel31       = 1 << 8,
        kIdlePush         = 1 << 9,
        kEarlyRelease     = 1 << 10,
    };

    // Hand OMXCodec a single input buffer at a time
    // (OMXCodec::kOnlySubmitOneInputBufferAtOneTime).
    bool oneInputBuffer = false;
    // Nvidia decoder parameters on the OMX node (OMXDecoder.cpp), each on
    // its own: uselowbuffer, h264disabledpb, vdecfullframedata.
    bool nvidiaLowBuffer = false;
    bool nvidiaNoDpb = false;
    bool nvidiaFullFrame = false;
    // Previous behaviour: flush the queue after 8 frames / 250 ms of backlog
    // and wait for an IDR frame.
    bool legacyDrop = true;
    // Collect per-stage statistics (logcat + on-screen overlay).
    bool stats = false;
    // Rewrite the SPS so the decoder outputs each frame as soon as it is
    // decoded instead of holding a few back for reordering (SpsPatcher.h).
    bool spsLowLatency = false;
    // With the SPS rewrite: raise a declared level below 3.1 to 3.1.
    bool spsLevel31 = false;
    // When no frame arrives for a moment, hand the decoder an empty input
    // buffer so the codec's pending callbacks, and the decoded frame waiting
    // behind them, are delivered (OMXSource::nextBuffer).
    bool idlePush = false;
    // Park the decoder's EMPTY_BUFFER_DONE callbacks while no frame is
    // waiting, so decoded frames are delivered at once (EarlyReleaseOMX.h).
    bool earlyRelease = false;

    bool anyNvidia() const {
        return nvidiaLowBuffer || nvidiaNoDpb || nvidiaFullFrame;
    }

    static DecoderOptions fromBits(int32_t bits) {
        DecoderOptions options;
        options.oneInputBuffer = (bits & kOneInputBuffer) != 0;
        const bool allNvidia = (bits & kNvidiaLowLatency) != 0;
        options.nvidiaLowBuffer = allNvidia || (bits & kNvidiaLowBuffer) != 0;
        options.nvidiaNoDpb = allNvidia || (bits & kNvidiaNoDpb) != 0;
        options.nvidiaFullFrame = allNvidia || (bits & kNvidiaFullFrame) != 0;
        options.legacyDrop = (bits & kLegacyDrop) != 0;
        options.stats = (bits & kStats) != 0;
        options.spsLowLatency = (bits & kSpsLowLatency) != 0;
        options.spsLevel31 = (bits & kSpsLevel31) != 0;
        options.idlePush = (bits & kIdlePush) != 0;
        options.earlyRelease = (bits & kEarlyRelease) != 0;
        return options;
    }
};
