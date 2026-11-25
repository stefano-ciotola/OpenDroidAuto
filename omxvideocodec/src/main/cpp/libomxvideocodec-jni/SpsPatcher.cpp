#include "SpsPatcher.h"

#include <algorithm>
#include <cstdio>

namespace h264 {

namespace {

constexpr uint8_t kNalTypeSps = 7;

// Removes emulation prevention bytes (00 00 03 -> 00 00).
void unescape(const uint8_t* src, std::size_t size, std::vector<uint8_t>* rbsp) {
    rbsp->clear();
    rbsp->reserve(size);
    int zeros = 0;
    for (std::size_t i = 0; i < size; ++i) {
        const uint8_t byte = src[i];
        if (zeros >= 2 && byte == 0x03) {
            zeros = 0;
            continue;
        }
        rbsp->push_back(byte);
        zeros = byte == 0 ? zeros + 1 : 0;
    }
}

// Inserts emulation prevention bytes so no start code appears in the payload.
void escape(const std::vector<uint8_t>& rbsp, std::vector<uint8_t>* out) {
    int zeros = 0;
    for (uint8_t byte : rbsp) {
        if (zeros >= 2 && byte <= 0x03) {
            out->push_back(0x03);
            zeros = 0;
        }
        out->push_back(byte);
        zeros = byte == 0 ? zeros + 1 : 0;
    }
}

// Reads past the end only flag the reader as failed (and return zeros), so
// the parser can check once per syntax structure.
class BitReader {
public:
    explicit BitReader(const std::vector<uint8_t>& data) : data_(data) {}

    bool ok() const { return ok_; }
    std::size_t position() const { return position_; }

    uint32_t bit() {
        if (position_ >= data_.size() * 8) {
            ok_ = false;
            return 0;
        }
        const uint32_t value = (data_[position_ >> 3] >> (7 - (position_ & 7))) & 1u;
        ++position_;
        return value;
    }

    uint32_t bits(int count) {
        uint32_t value = 0;
        for (int i = 0; i < count; ++i) {
            value = (value << 1) | bit();
        }
        return value;
    }

    uint32_t ue() {
        int leadingZeros = 0;
        while (ok_ && bit() == 0) {
            if (++leadingZeros > 31) {
                ok_ = false;
            }
        }
        if (!ok_ || leadingZeros == 0) {
            return 0;
        }
        const uint64_t value = (uint64_t{1} << leadingZeros) - 1 + bits(leadingZeros);
        return static_cast<uint32_t>(value);
    }

    int32_t se() {
        const uint32_t code = ue();
        return (code & 1u) ? static_cast<int32_t>((code + 1) / 2) : -static_cast<int32_t>(code / 2);
    }

private:
    const std::vector<uint8_t>& data_;
    std::size_t position_ = 0;
    bool ok_ = true;
};

class BitWriter {
public:
    void bit(uint32_t value) {
        current_ = static_cast<uint8_t>((current_ << 1) | (value & 1u));
        if (++filled_ == 8) {
            data_.push_back(current_);
            current_ = 0;
            filled_ = 0;
        }
    }

    void bits(uint64_t value, int count) {
        for (int i = count - 1; i >= 0; --i) {
            bit(static_cast<uint32_t>((value >> i) & 1u));
        }
    }

    void ue(uint32_t value) {
        const uint64_t code = uint64_t{value} + 1;
        int length = 0;
        while ((code >> (length + 1)) != 0) {
            ++length;
        }
        bits(0, length);
        bits(code, length + 1);
    }

    // Copies count bits of src starting at bit `from`.
    void copy(const std::vector<uint8_t>& src, std::size_t from, std::size_t count) {
        for (std::size_t i = 0; i < count; ++i) {
            const std::size_t position = from + i;
            bit((src[position >> 3] >> (7 - (position & 7))) & 1u);
        }
    }

    void trailingBits() {
        bit(1);
        while (filled_ != 0) {
            bit(0);
        }
    }

    const std::vector<uint8_t>& data() const { return data_; }

private:
    std::vector<uint8_t> data_;
    uint8_t current_ = 0;
    int filled_ = 0;
};

// Where the parts the rewrite touches sit in the RBSP (NAL header excluded),
// and the bitstream restriction values worth keeping.
struct Layout {
    std::size_t vuiFlagBit = 0;
    std::size_t restrictionFlagBit = 0;
    uint32_t motionVectorsOverPicBoundaries = 1;
    uint32_t maxBytesPerPicDenom = 2;
    uint32_t maxBitsPerMbDenom = 1;
    uint32_t log2MaxMvLengthHorizontal = 16;
    uint32_t log2MaxMvLengthVertical = 16;
};

bool skipScalingList(BitReader& reader, int size) {
    int last = 8;
    int next = 8;
    for (int j = 0; j < size; ++j) {
        if (next != 0) {
            const int32_t delta = reader.se();
            if (delta < -128 || delta > 127) {
                return false;
            }
            next = (last + delta + 256) % 256;
        }
        last = next == 0 ? last : next;
    }
    return reader.ok();
}

bool skipHrdParameters(BitReader& reader) {
    const uint32_t cpbCount = reader.ue() + 1;
    if (!reader.ok() || cpbCount > 32) {
        return false;
    }
    reader.bits(4); // bit_rate_scale
    reader.bits(4); // cpb_size_scale
    for (uint32_t i = 0; i < cpbCount; ++i) {
        reader.ue();  // bit_rate_value_minus1
        reader.ue();  // cpb_size_value_minus1
        reader.bit(); // cbr_flag
    }
    reader.bits(5); // initial_cpb_removal_delay_length_minus1
    reader.bits(5); // cpb_removal_delay_length_minus1
    reader.bits(5); // dpb_output_delay_length_minus1
    reader.bits(5); // time_offset_length
    return reader.ok();
}

bool hasChromaSyntax(int profileIdc) {
    switch (profileIdc) {
        case 100: case 110: case 122: case 244: case 44: case 83: case 86:
        case 118: case 128: case 138: case 139: case 134: case 135:
            return true;
        default:
            return false;
    }
}

// ITU-T H.264 7.3.2.1.1 and E.1.1.
bool parseRbsp(const std::vector<uint8_t>& rbsp, SpsInfo* info, Layout* layout) {
    BitReader reader(rbsp);

    info->profileIdc = static_cast<int>(reader.bits(8));
    info->constraintFlags = static_cast<int>(reader.bits(8));
    info->levelIdc = static_cast<int>(reader.bits(8));
    if (reader.ue() > 31) { // seq_parameter_set_id
        return false;
    }

    uint32_t chromaFormatIdc = 1;
    bool separateColourPlanes = false;
    if (hasChromaSyntax(info->profileIdc)) {
        chromaFormatIdc = reader.ue();
        if (chromaFormatIdc > 3) {
            return false;
        }
        if (chromaFormatIdc == 3) {
            separateColourPlanes = reader.bit() != 0;
        }
        if (reader.ue() > 6 || reader.ue() > 6) { // bit depths minus 8
            return false;
        }
        reader.bit(); // qpprime_y_zero_transform_bypass_flag
        if (reader.bit()) { // seq_scaling_matrix_present_flag
            const int lists = chromaFormatIdc != 3 ? 8 : 12;
            for (int i = 0; i < lists; ++i) {
                if (reader.bit() && !skipScalingList(reader, i < 6 ? 16 : 64)) {
                    return false;
                }
            }
        }
    }

    if (reader.ue() > 12) { // log2_max_frame_num_minus4
        return false;
    }
    const uint32_t pocType = reader.ue();
    if (pocType == 0) {
        if (reader.ue() > 12) { // log2_max_pic_order_cnt_lsb_minus4
            return false;
        }
    } else if (pocType == 1) {
        reader.bit(); // delta_pic_order_always_zero_flag
        reader.se();  // offset_for_non_ref_pic
        reader.se();  // offset_for_top_to_bottom_field
        const uint32_t cycle = reader.ue();
        if (cycle > 255) {
            return false;
        }
        for (uint32_t i = 0; i < cycle; ++i) {
            reader.se(); // offset_for_ref_frame
        }
    } else if (pocType != 2) {
        return false;
    }
    info->pocType = static_cast<int>(pocType);

    const uint32_t refFrames = reader.ue();
    if (refFrames > 16) {
        return false;
    }
    info->numRefFrames = static_cast<int>(refFrames);
    reader.bit(); // gaps_in_frame_num_value_allowed_flag

    const uint32_t widthInMbs = reader.ue() + 1;
    const uint32_t heightInMapUnits = reader.ue() + 1;
    const uint32_t frameMbsOnly = reader.bit();
    if (!frameMbsOnly) {
        reader.bit(); // mb_adaptive_frame_field_flag
    }
    reader.bit(); // direct_8x8_inference_flag
    uint32_t cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
    if (reader.bit()) { // frame_cropping_flag
        cropLeft = reader.ue();
        cropRight = reader.ue();
        cropTop = reader.ue();
        cropBottom = reader.ue();
    }
    if (!reader.ok() || widthInMbs > 1024 || heightInMapUnits > 1024) {
        return false;
    }

    // Crop units (7-19 .. 7-22); separate colour planes count as monochrome.
    const uint32_t chromaArrayType = separateColourPlanes ? 0 : chromaFormatIdc;
    const uint32_t subWidthC = chromaArrayType == 1 || chromaArrayType == 2 ? 2 : 1;
    const uint32_t subHeightC = chromaArrayType == 1 ? 2 : 1;
    const uint32_t cropUnitX = chromaArrayType == 0 ? 1 : subWidthC;
    const uint32_t cropUnitY = (chromaArrayType == 0 ? 1 : subHeightC) * (2 - frameMbsOnly);
    const int64_t width = int64_t{widthInMbs} * 16 - int64_t{cropUnitX} * (int64_t{cropLeft} + cropRight);
    const int64_t height = int64_t{heightInMapUnits} * (2 - frameMbsOnly) * 16 -
                           int64_t{cropUnitY} * (int64_t{cropTop} + cropBottom);
    if (width <= 0 || height <= 0) {
        return false;
    }
    info->width = static_cast<int>(width);
    info->height = static_cast<int>(height);

    layout->vuiFlagBit = reader.position();
    info->vuiPresent = reader.bit() != 0;
    if (info->vuiPresent) {
        if (reader.bit()) { // aspect_ratio_info_present_flag
            if (reader.bits(8) == 255) { // Extended_SAR
                reader.bits(16);
                reader.bits(16);
            }
        }
        if (reader.bit()) { // overscan_info_present_flag
            reader.bit();
        }
        if (reader.bit()) { // video_signal_type_present_flag
            reader.bits(3);
            reader.bit();
            if (reader.bit()) { // colour_description_present_flag
                reader.bits(24);
            }
        }
        if (reader.bit()) { // chroma_loc_info_present_flag
            reader.ue();
            reader.ue();
        }
        if (reader.bit()) { // timing_info_present_flag
            reader.bits(32);
            reader.bits(32);
            reader.bit();
        }
        const bool nalHrd = reader.bit() != 0;
        if (nalHrd && !skipHrdParameters(reader)) {
            return false;
        }
        const bool vclHrd = reader.bit() != 0;
        if (vclHrd && !skipHrdParameters(reader)) {
            return false;
        }
        if (nalHrd || vclHrd) {
            reader.bit(); // low_delay_hrd_flag
        }
        reader.bit(); // pic_struct_present_flag

        layout->restrictionFlagBit = reader.position();
        info->bitstreamRestriction = reader.bit() != 0;
        if (info->bitstreamRestriction) {
            layout->motionVectorsOverPicBoundaries = reader.bit();
            layout->maxBytesPerPicDenom = reader.ue();
            layout->maxBitsPerMbDenom = reader.ue();
            layout->log2MaxMvLengthHorizontal = reader.ue();
            layout->log2MaxMvLengthVertical = reader.ue();
            const uint32_t reorder = reader.ue();
            const uint32_t buffering = reader.ue();
            if (reorder > 16 || buffering > 16) {
                return false;
            }
            info->maxNumReorderFrames = static_cast<int>(reorder);
            info->maxDecFrameBuffering = static_cast<int>(buffering);
        }
    }

    return reader.ok();
}

bool parseNal(const uint8_t* nal, std::size_t size, std::vector<uint8_t>* rbsp,
              SpsInfo* info, Layout* layout) {
    if (nal == nullptr || size < 4 || (nal[0] & 0x1f) != kNalTypeSps || (nal[0] & 0x80) != 0) {
        return false;
    }
    unescape(nal + 1, size - 1, rbsp);
    return parseRbsp(*rbsp, info, layout);
}

// Next three-byte start code at or after `from`; size when there is none.
std::size_t findStartCode(const uint8_t* data, std::size_t size, std::size_t from) {
    for (std::size_t i = from; i + 2 < size; ++i) {
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            return i;
        }
    }
    return size;
}

} // namespace

bool parseSps(const uint8_t* nal, std::size_t size, SpsInfo* info) {
    std::vector<uint8_t> rbsp;
    SpsInfo parsed;
    Layout layout;
    if (!parseNal(nal, size, &rbsp, &parsed, &layout)) {
        return false;
    }
    if (info != nullptr) {
        *info = parsed;
    }
    return true;
}

SpsPatch patchSps(const uint8_t* nal, std::size_t size, std::vector<uint8_t>* out, SpsInfo* info) {
    std::vector<uint8_t> rbsp;
    SpsInfo parsed;
    Layout layout;
    if (!parseNal(nal, size, &rbsp, &parsed, &layout)) {
        return SpsPatch::Invalid;
    }
    if (info != nullptr) {
        *info = parsed;
    }

    // The picture buffer has to hold the reference frames; one is the minimum.
    const int buffering = std::max(1, parsed.numRefFrames);
    if (parsed.bitstreamRestriction && parsed.maxNumReorderFrames == 0 &&
        parsed.maxDecFrameBuffering >= parsed.numRefFrames && parsed.maxDecFrameBuffering <= buffering) {
        return SpsPatch::AlreadyLowLatency;
    }
    // Only streams that cannot reorder: Baseline has no B slices, and with
    // pic_order_cnt_type 2 output order is decoding order by definition.
    if (parsed.profileIdc != 66 && parsed.pocType != 2) {
        return SpsPatch::Unsupported;
    }

    BitWriter writer;
    writer.copy(rbsp, 0, layout.vuiFlagBit);
    writer.bit(1); // vui_parameters_present_flag
    if (parsed.vuiPresent) {
        // Everything up to bitstream_restriction_flag stays as the encoder wrote it.
        writer.copy(rbsp, layout.vuiFlagBit + 1, layout.restrictionFlagBit - layout.vuiFlagBit - 1);
    } else {
        // aspect ratio, overscan, video signal, chroma location, timing,
        // NAL HRD, VCL HRD, picture structure: none of them.
        writer.bits(0, 8);
    }
    writer.bit(1); // bitstream_restriction_flag
    writer.bit(layout.motionVectorsOverPicBoundaries);
    writer.ue(layout.maxBytesPerPicDenom);
    writer.ue(layout.maxBitsPerMbDenom);
    writer.ue(layout.log2MaxMvLengthHorizontal);
    writer.ue(layout.log2MaxMvLengthVertical);
    writer.ue(0);                                    // max_num_reorder_frames
    writer.ue(static_cast<uint32_t>(buffering));     // max_dec_frame_buffering
    writer.trailingBits();

    out->clear();
    out->reserve(writer.data().size() + 8);
    out->push_back(nal[0]);
    escape(writer.data(), out);
    return SpsPatch::Patched;
}

bool patchAccessUnit(const uint8_t* data, std::size_t size, std::vector<uint8_t>* out,
                     bool* sawSps, SpsInfo* info, SpsPatch* result) {
    if (sawSps != nullptr) {
        *sawSps = false;
    }
    if (result != nullptr) {
        *result = SpsPatch::Invalid;
    }
    if (data == nullptr || size < 4) {
        return false;
    }

    bool changed = false;
    std::size_t copied = 0;
    std::vector<uint8_t> patched;

    std::size_t code = findStartCode(data, size, 0);
    while (code < size) {
        const std::size_t start = code + 3;
        const std::size_t next = findStartCode(data, size, start);
        // Zero bytes before the next start code belong to it (four-byte start
        // codes, trailing_zero_8bits), not to this NAL unit.
        std::size_t end = next;
        while (end > start && data[end - 1] == 0) {
            --end;
        }

        if (end > start && (data[start] & 0x1f) == kNalTypeSps) {
            if (sawSps != nullptr) {
                *sawSps = true;
            }
            SpsInfo spsInfo;
            const SpsPatch outcome = patchSps(data + start, end - start, &patched, &spsInfo);
            if (info != nullptr && outcome != SpsPatch::Invalid) {
                *info = spsInfo;
            }
            if (result != nullptr) {
                *result = outcome;
            }
            if (outcome == SpsPatch::Patched) {
                if (!changed) {
                    out->clear();
                    out->reserve(size + 16);
                    changed = true;
                }
                out->insert(out->end(), data + copied, data + start);
                out->insert(out->end(), patched.begin(), patched.end());
                copied = end;
            }
        }
        code = next;
    }

    if (changed) {
        out->insert(out->end(), data + copied, data + size);
    }
    return changed;
}

std::string describeSps(const SpsInfo& info) {
    const char* profile;
    switch (info.profileIdc) {
        case 66: profile = (info.constraintFlags & 0x40) ? "CBP" : "BP"; break;
        case 77: profile = "MP"; break;
        case 88: profile = "XP"; break;
        case 100: profile = "HP"; break;
        default: profile = nullptr; break;
    }
    char text[128];
    char profileText[16];
    if (profile == nullptr) {
        snprintf(profileText, sizeof(profileText), "P%d", info.profileIdc);
        profile = profileText;
    }
    char restriction[32];
    if (info.bitstreamRestriction) {
        snprintf(restriction, sizeof(restriction), "reorder %d dpb %d",
                 info.maxNumReorderFrames, info.maxDecFrameBuffering);
    } else {
        snprintf(restriction, sizeof(restriction), "%s", info.vuiPresent ? "vui, no limits" : "no vui");
    }
    snprintf(text, sizeof(text), "%dx%d %s L%d.%d ref %d poc %d, %s",
             info.width, info.height, profile, info.levelIdc / 10, info.levelIdc % 10,
             info.numRefFrames, info.pocType, restriction);
    return text;
}

const char* describePatch(SpsPatch result) {
    switch (result) {
        case SpsPatch::Patched: return "patched: reorder 0";
        case SpsPatch::AlreadyLowLatency: return "already low latency";
        case SpsPatch::Unsupported: return "not patched (may reorder)";
        case SpsPatch::Invalid:
        default: return "not parsed";
    }
}

} // namespace h264
