#include "OMXDecoder.h"
#include "VideoStats.h"
#include <Log.h>
#include <cstring>
#include <media/IOMX.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/OMXCodec.h>
#include <media/stagefright/openmax/OMX_Core.h>

using namespace android;

namespace {

// OMXCodec keeps its OMX node id private and ICS has no accessor for it. An
// explicit template instantiation may name private members ([temp.explicit]),
// which is the one standard-conforming way to reach them.
//
// The layout these accessors rely on was checked against the head unit's own
// libstagefright.so (ext-libs/armeabi-v7a/system/hrv): its OMXCodec constructor
// stores mOMX at +0x08, mNode at +0x10 and mFlags at +0x18, the same layout as
// the ICS header in ext-libs. applyNvidiaLowLatency() still verifies it at run
// time before trusting the node id.
template <typename Tag, typename Tag::type Member>
struct PrivateMember {
    friend typename Tag::type get(Tag) { return Member; }
};

struct CodecOmx {
    typedef sp<IOMX> OMXCodec::*type;
    friend type get(CodecOmx);
};
struct CodecNode {
    typedef IOMX::node_id OMXCodec::*type;
    friend type get(CodecNode);
};
struct CodecFlags {
    typedef uint32_t OMXCodec::*type;
    friend type get(CodecFlags);
};

template struct PrivateMember<CodecOmx, &OMXCodec::mOMX>;
template struct PrivateMember<CodecNode, &OMXCodec::mNode>;
template struct PrivateMember<CodecFlags, &OMXCodec::mFlags>;

// Parameter structures of the Nvidia OMX extensions, laid out exactly as the
// firmware's own ACodec::setupCodecForVideoTelephony() and
// OMXCodec::configureCodec() fill them: nSize 16 and 12, version 1.0.0.0, then
// an OMX_U32 port index (low-buffer only) and an OMX_BOOL. Fixed-width fields
// so the layout does not depend on the compiler's idea of OMX_U32.
struct NvxVersion {
    uint8_t major;
    uint8_t minor;
    uint8_t revision;
    uint8_t step;
};

struct NvxPortBoolParam {
    uint32_t nSize;
    NvxVersion nVersion;
    uint32_t nPortIndex;
    uint32_t bEnable;
};

struct NvxBoolParam {
    uint32_t nSize;
    NvxVersion nVersion;
    uint32_t bEnable;
};

static_assert(sizeof(NvxPortBoolParam) == 16, "Nvidia low-buffer parameter must be 16 bytes");
static_assert(sizeof(NvxBoolParam) == 12, "Nvidia boolean parameter must be 12 bytes");

template <typename T>
void initNvxParam(T* param) {
    memset(param, 0, sizeof(*param));
    param->nSize = sizeof(*param);
    param->nVersion.major = 1;
    param->bEnable = OMX_TRUE;
}

// "ok", "n/a" (the component does not know the extension) or "fail".
const char* setNvxParam(const sp<IOMX>& omx, IOMX::node_id node, const char* name,
                        const void* param, size_t size) {
    OMX_INDEXTYPE index;
    status_t err = omx->getExtensionIndex(node, name, &index);
    if (err != OK) {
        if (Log::isInfo()) Log_i("%s not supported by the decoder (%d)", name, err);
        return "n/a";
    }
    err = omx->setParameter(node, index, param, size);
    if (Log::isInfo()) Log_i("%s -> %d", name, err);
    return err == OK ? "ok" : "fail";
}

} // namespace

OMXDecoder::OMXDecoder():
        source_(nullptr),
        client_(new OMXClient()),
        frameRate_(0),
        status_(-1)
{
}

void OMXDecoder::setNativeWindow(sp<ANativeWindow> &nativeWindow){
    nativeWindow_ = nativeWindow;
}

void OMXDecoder::setSource(OMXSource *source){
    source_ = source;
}

int32_t OMXDecoder::frameRate() const{
    return frameRate_;
}

status_t OMXDecoder::getStatus() const {
    return status_;
}

const std::string& OMXDecoder::summary() const {
    return summary_;
}

status_t OMXDecoder::init(const DecoderOptions& options){
    if (Log::isInfo()) Log_i("init decoder (one input buffer: %d, nvidia low latency: %d)",
                             options.oneInputBuffer, options.nvidiaLowLatency);

    summary_ = std::string("one-input ") + (options.oneInputBuffer ? "on" : "off");

    status_ = client_->connect();
    if (Log::isInfo()) Log_i("client connect %d", status_);

    if (status_ == OK) {
        uint32_t flags = OMXCodec::kHardwareCodecsOnly;
        if (options.oneInputBuffer) {
            // OMXCodec refills an input buffer from inside its OMX callback, and
            // OMXSource::read() blocks there until the phone sends the next frame.
            // The codec's callbacks are delivered one at a time, so every extra
            // input buffer in flight is one more decoded frame held back behind
            // a waiting callback. With a single buffer at most one is waiting.
            flags |= OMXCodec::kOnlySubmitOneInputBufferAtOneTime;
        }
        decoder_ = OMXCodec::Create(client_->interface(), source_->getFormat(), false, source_,
                                    nullptr, flags, nativeWindow_);
        if (decoder_ == nullptr) {
            Log_e("no hardware AVC decoder could be created");
            summary_ += " | decoder: none";
            status_ = NO_INIT;
            return status_;
        }

        summary_ += " | nvidia ";
        if (options.nvidiaLowLatency) {
            summary_ += applyNvidiaLowLatency(flags);
        } else {
            summary_ += "off";
        }

        status_ = decoder_->start();

        if (Log::isInfo()) Log_i("decoder started %d", status_);
        if (status_ != OK) {
            summary_ += " | start failed " + std::to_string(status_);
        }
    } else {
        summary_ += " | OMX connect failed";
    }

    return status_;
}

// The head unit firmware has a low-latency setup for this decoder,
// ACodec::setupCodecForVideoTelephony(), reached only through ACodec with the
// "vtel" key. It enables OMX.Nvidia.index.param.uselowbuffer and
// OMX.Nvidia.index.param.h264disabledpb. OMXCodec never sets them, so they are
// applied here on the node, while it is still in the Loaded state.
// vdecfullframedata tells the decoder that every input buffer is a complete
// frame, which is what Android Auto sends.
std::string OMXDecoder::applyNvidiaLowLatency(uint32_t requestedFlags) {
    OMXCodec* codec = static_cast<OMXCodec*>(decoder_.get());
    const sp<IOMX>& codecOmx = codec->*get(CodecOmx());
    const IOMX::node_id node = codec->*get(CodecNode());
    const uint32_t codecFlags = codec->*get(CodecFlags());

    // Never hand OMX a node id unless the object clearly looks like the OMXCodec
    // we just created: an unknown node id would crash mediaserver.
    if (codecOmx.get() != client_->interface().get() ||
        (codecFlags & requestedFlags) != requestedFlags || node == nullptr) {
        if (Log::isWarn()) Log_w("OMXCodec layout not recognised, Nvidia low-latency mode skipped");
        return "skipped";
    }

    NvxPortBoolParam lowBuffer;
    initNvxParam(&lowBuffer);
    lowBuffer.nPortIndex = 0;

    NvxBoolParam disableDpb;
    initNvxParam(&disableDpb);

    NvxBoolParam fullFrame;
    initNvxParam(&fullFrame);

    const sp<IOMX>& omx = client_->interface();
    std::string result = "lowbuf ";
    result += setNvxParam(omx, node, "OMX.Nvidia.index.param.uselowbuffer", &lowBuffer, sizeof(lowBuffer));
    result += ", nodpb ";
    result += setNvxParam(omx, node, "OMX.Nvidia.index.param.h264disabledpb", &disableDpb, sizeof(disableDpb));
    result += ", fullframe ";
    result += setNvxParam(omx, node, "OMX.Nvidia.index.param.vdecfullframedata", &fullFrame, sizeof(fullFrame));
    return result;
}

void OMXDecoder::stop(){
    if (Log::isInfo()) Log_i("stop decoder");
    if (decoder_ != nullptr) {
        status_t ret = decoder_->stop();
        if (Log::isDebug()) Log_d("stopped %d", ret);
    }
    if (Log::isInfo()) Log_i("disconnect client");
    client_->disconnect();
    if (Log::isDebug()) Log_d("stopped");
}

OMXDecoder::~OMXDecoder(){
    if (Log::isDebug()) Log_d("destructor");

    if (Log::isDebug()) Log_d("clear mDecoder");
    decoder_.clear();

    if (Log::isDebug()) Log_d("delete client");
    delete client_;

    if (Log::isDebug()) Log_d("all done");
}

status_t OMXDecoder::read(){
    if (Log::isVerbose()) Log_v("read");

    MediaBuffer *videoBuffer = nullptr;
    MediaSource::ReadOptions options;
    options.setLateBy(0);
    status_t ret = decoder_->read(&videoBuffer, &options);
    if (Log::isVerbose()) Log_v("decoder read ret = %d", ret);

    if (ret == OK && videoBuffer != nullptr) {
        if (videoBuffer->range_length() > 0) {
            if (Log::isVerbose()) Log_v("videobuffer length %d", videoBuffer->range_length());
            VideoStats& stats = VideoStats::instance();
            stats.onDecoded();
            sp<MetaData> metaData = videoBuffer->meta_data();
            int64_t timestampUs = 0;
            metaData->findInt64(kKeyTime, &timestampUs);
            native_window_set_buffers_timestamp(nativeWindow_.get(),
                                                NATIVE_WINDOW_TIMESTAMP_AUTO);
            ret = nativeWindow_->queueBuffer(nativeWindow_.get(),
                                             videoBuffer->graphicBuffer().get());
            if (ret == 0) {
                metaData->setInt32(kKeyRendered, 1);
                stats.onRendered(timestampUs);
            }
        }
    }


    if (videoBuffer != nullptr) {
        if (Log::isVerbose()) Log_v("release videobuffer");
        videoBuffer->release();
        videoBuffer = nullptr;
    }

    return ret;
}
