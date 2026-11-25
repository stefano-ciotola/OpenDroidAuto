#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// Low-latency rewrite of the H.264 sequence parameter set.
//
// Without VUI bitstream restrictions a decoder has to assume the stream may
// reorder pictures, so it holds decoded frames back until its picture buffer
// is full. On the head unit's Tegra decoder that is 4-5 frames: about 150 ms
// at 30 fps, and a whole second on static screens where the phone only sends a
// few frames per second. Declaring max_num_reorder_frames = 0 (and the
// smallest picture buffer the references allow) lets it output every frame as
// soon as it is decoded. Low-latency game streaming clients do the same.
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
    // Rewritten.
    Patched,
};

// nal points at the NAL header byte (no start code).
bool parseSps(const uint8_t* nal, std::size_t size, SpsInfo* info);

// Writes the rewritten NAL unit (header byte included) to out when the result
// is Patched. info receives what the original SPS said, whenever it parses.
SpsPatch patchSps(const uint8_t* nal, std::size_t size, std::vector<uint8_t>* out, SpsInfo* info);

// Runs patchSps on every SPS of an Annex B buffer. Returns true when out holds
// a rewritten copy of the whole buffer; every other byte is kept as it is.
// sawSps tells whether the buffer had an SPS at all; info and result describe
// the last one.
bool patchAccessUnit(const uint8_t* data, std::size_t size, std::vector<uint8_t>* out,
                     bool* sawSps, SpsInfo* info, SpsPatch* result);

// "800x480 BP L3.1 ref 1 vui no" style summary for logs and the overlay.
std::string describeSps(const SpsInfo& info);
const char* describePatch(SpsPatch result);

} // namespace h264
