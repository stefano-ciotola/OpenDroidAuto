#pragma once

#include <cstddef>
#include <cstdint>

namespace h264 {

struct NalInfo {
    bool hasCodecConfig = false;
    bool hasIdr = false;
    // The buffer carries picture data (a coded slice), i.e. it is a frame the
    // decoder will output, as opposed to SPS/PPS/SEI only.
    bool hasSlice = false;
};

inline void addNalType(NalInfo& info, uint8_t header) {
    const uint8_t type = header & 0x1f;
    info.hasCodecConfig = info.hasCodecConfig || type == 7 || type == 8;
    info.hasIdr = info.hasIdr || type == 5;
    info.hasSlice = info.hasSlice || type == 1 || type == 5;
}

// Parameter sets and SEI come before the first slice of an access unit, and
// every slice of a picture has the same NAL type, so the scan can stop at the
// first slice header. It used to walk the whole compressed frame byte by byte,
// under the source lock, on every frame.
inline NalInfo inspectNalUnits(const uint8_t* data, std::size_t size) {
    NalInfo info;
    if (data == nullptr || size == 0) {
        return info;
    }

    bool foundAnnexB = false;

    for (std::size_t i = 0; i + 3 < size; ++i) {
        std::size_t headerOffset = 0;
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            headerOffset = i + 3;
        } else if (i + 4 < size && data[i] == 0 && data[i + 1] == 0 &&
                   data[i + 2] == 0 && data[i + 3] == 1) {
            headerOffset = i + 4;
        }

        if (headerOffset < size && headerOffset != 0) {
            addNalType(info, data[headerOffset]);
            foundAnnexB = true;
            if (info.hasSlice) {
                break;
            }
            i = headerOffset;
        }
    }

    if (foundAnnexB) {
        return info;
    }

    // Android Auto normally sends Annex B, but accept four-byte AVCC lengths too.
    std::size_t offset = 0;
    bool foundAvcc = false;
    while (offset + 4 < size) {
        const uint32_t nalSize = (static_cast<uint32_t>(data[offset]) << 24) |
                                 (static_cast<uint32_t>(data[offset + 1]) << 16) |
                                 (static_cast<uint32_t>(data[offset + 2]) << 8) |
                                 static_cast<uint32_t>(data[offset + 3]);
        if (nalSize == 0 || nalSize > size - offset - 4) {
            break;
        }

        addNalType(info, data[offset + 4]);
        foundAvcc = true;
        if (info.hasSlice) {
            break;
        }
        offset += 4 + nalSize;
    }

    if (!foundAvcc && size > 0) {
        addNalType(info, data[0]);
    }

    return info;
}

} // namespace h264
