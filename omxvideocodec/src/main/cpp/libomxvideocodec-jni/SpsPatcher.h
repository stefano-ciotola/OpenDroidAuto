#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// Low-latency rewrite of the H.264 sequence parameter set.
//
// Without VUI bitstream restrictions a decoder has to assume the stream may
// reorder pictures, so it may hold decoded frames back until its picture
// buffer is full. Declaring max_num_reorder_frames = 0 (and the smallest
// picture buffer the references allow) lets it output every frame as soon as
// it is decoded. Low-latency game streaming clients do the same. (The Android
// box this fork is tested with already declares both.)
//
// Optionally the declared level is raised to a minimum: some streams declare
// a level too low for their own picture size (level 1.0 for 800x480), and a
// decoder that sizes its buffers from the level cannot do so from that.
namespace h264 {

struct SpsInfo {
    int profileIdc = 0;
    int constraintFlags = 0;
    int levelIdc = 0;
    // Luma size after cropping.
    int width = 0;
    int height = 0;
    int numRefFrames = 0;
    int pocType = 0;
    bool vuiPresent = false;
    bool bitstreamRestriction = false;
    // -1 when the SPS does not signal them.
    int maxNumReorderFrames = -1;
    int maxDecFrameBuffering = -1;
};

enum class SpsPatch {
    // Not a parsable SPS: passed on untouched.
    Invalid,
    // The stream may reorder pictures (B-frames): passed on untouched.
    Unsupported,
    // It already declares no reordering and a minimal picture buffer.
    AlreadyLowLatency,
    // Rewritten: no reordering, minimal picture buffer.
    Patched,
    // Only the level was raised (the rest already low latency, or the stream
    // may reorder and is otherwise left alone).
    PatchedLevel,
    // Both.
    PatchedBoth,
};

inline bool isPatched(SpsPatch result) {
    return result == SpsPatch::Patched || result == SpsPatch::PatchedLevel || result == SpsPatch::PatchedBoth;
}

// nal points at the NAL header byte (no start code).
bool parseSps(const uint8_t* nal, std::size_t size, SpsInfo* info);

// Writes the rewritten NAL unit (header byte included) to out when the result
// is one of the Patched values. info receives what the original SPS said,
// whenever it parses. minLevelIdc > 0 raises a lower declared level to it
// (31 = level 3.1); a higher level is never lowered.
SpsPatch patchSps(const uint8_t* nal, std::size_t size, std::vector<uint8_t>* out, SpsInfo* info,
                  int minLevelIdc = 0);

// Runs patchSps on every SPS of an Annex B buffer. Returns true when out holds
// a rewritten copy of the whole buffer; every other byte is kept as it is.
// sawSps tells whether the buffer had an SPS at all; info and result describe
// the last one.
bool patchAccessUnit(const uint8_t* data, std::size_t size, std::vector<uint8_t>* out,
                     bool* sawSps, SpsInfo* info, SpsPatch* result, int minLevelIdc = 0);

// "800x480 BP L3.1 ref 1 vui no" style summary for logs and the overlay.
std::string describeSps(const SpsInfo& info);
// "patched: reorder 0", "patched: level 3.1"... minLevelIdc as given to patchSps.
std::string describePatch(SpsPatch result, int minLevelIdc = 0);

} // namespace h264
