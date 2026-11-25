#include "OMXVideoCodec.h"
#include "H264NalParser.h"
#include "SpsPatcher.h"
#include "VideoStats.h"
#include <Log.h>

#include <unistd.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cinttypes>
#include <chrono>
#include <sstream>
#include <iomanip>

namespace {

// ANDROID_PRIORITY_URGENT_DISPLAY: the decoder thread waits most of the time,
// but when a frame is ready it should not queue behind ordinary app threads.
constexpr int kDecoderThreadPriority = -8;

} // namespace

OMXVideoCodec::OMXVideoCodec()
    : omxSource_(nullptr)
    , omxDecoder_(nullptr)
    , fps_(0)
    , decoderThreadInitialized_(false)
    , decoderThreadQuitFlag_(false)
    , statsStarted_(false){
}

OMXVideoCodec::~OMXVideoCodec() {
    if (Log::isVerbose()) Log_v("destructor");

    if (Log::isDebug()) Log_d("delete decoder");
    delete omxDecoder_;
    omxDecoder_ = nullptr;

    omxSource_ = nullptr;
}

status_t OMXVideoCodec::init(int fps, const DecoderOptions& options){
    fps_ = fps;
    options_ = options;
    if (Log::isInfo()) Log_i("FPS %d", fps_);

    if (options_.stats) {
        VideoStats::instance().start();
        statsStarted_ = true;
    }

    // Create decoder thread and wait until ready
    if (Log::isInfo()) Log_i("start omxDecoderThread");

    omxDecoderThread_ = std::thread(&OMXVideoCodec::init_omxDecoderThread, this);

    std::unique_lock<std::mutex> codeclock(codecMutex_);
    while (!decoderThreadInitialized_){
        omxDecoderCond_.wait(codeclock);
    }
    codeclock.unlock();
    if (Log::isInfo()) Log_i("omxDecoderThread ready");

    return omxDecoder_->getStatus();
}

void OMXVideoCodec::shutdown(){
    if (decoderThreadInitialized_) {
        if (Log::isInfo()) Log_i("Waiting for app thread to finish...");

        if (Log::isDebug()) Log_d("Stopping omxDecoderThread");
        decoderThreadQuitFlag_ = true;

        if (Log::isDebug()) Log_d("Stop omxSource");
        omxSource_->stop();

        if (omxDecoderThread_.joinable()){
            if (Log::isDebug()) Log_d("joining decoder thread");
            omxDecoderThread_.join();
        }

        if (Log::isDebug()) Log_d("release native window");
        ANativeWindow_release(nativeWindow_.get());
    }

    if (statsStarted_) {
        VideoStats::instance().stop();
        statsStarted_ = false;
    }

    if (Log::isInfo()) Log_i("all done");
}

void OMXVideoCodec::init_omxDecoderThread() {
    prctl(PR_SET_NAME, "ODA-OMXDecoder", 0, 0, 0);
    if (Log::isInfo()) Log_i("omxDecoderThread begins");
    if (setpriority(PRIO_PROCESS, gettid(), kDecoderThreadPriority) != 0 && Log::isInfo()) {
        Log_i("could not raise the decoder thread priority");
    }

    omxSource_ = new OMXSource(screenSize_.width, screenSize_.height, fps_, options_.legacyDrop);

    if (Log::isDebug()) Log_d("Created OMXSource %p", omxSource_);

    omxDecoder_ = new OMXDecoder();
    omxDecoder_->setNativeWindow(nativeWindow_);
    omxDecoder_->setSource(omxSource_);
    const status_t initStatus = omxDecoder_->init(options_);

    std::string summary = omxDecoder_->summary();
    summary += std::string(" | legacy-drop ") + (options_.legacyDrop ? "on" : "off");
    summary += std::string(" | sps-patch ") + (options_.spsLowLatency ? "on" : "off");
    if (Log::isInfo()) Log_i("decoder options: %s", summary.c_str());
    VideoStats::instance().setDecoderSummary(summary);

    std::unique_lock<std::mutex> codeclock(codecMutex_);
    decoderThreadInitialized_ = true;
    codeclock.unlock();
    omxDecoderCond_.notify_one();

    if (initStatus != OK) {
        // Nothing to drive: calling read() on a decoder that never started only
        // spins on an error. Frames keep being dropped by OMXSource's limits.
        Log_e("decoder init failed (%d), no video will be shown", initStatus);
    }

    while (initStatus == OK && !decoderThreadQuitFlag_) {
        status_t ret = omxDecoder_->read();
        if (Log::isVerbose()) Log_v("omxDecoder_ read ret = %d", ret);
        if (ret != OK && ret != INFO_FORMAT_CHANGED && ret != TIMED_OUT) {
            // A codec in its error state answers immediately: do not burn a core.
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }

    if (Log::isInfo()) Log_i("omxDecoderThread ends");

    if (Log::isDebug()) Log_d("Stop omxDecoder");
    omxDecoder_->stop();
}

void OMXVideoCodec::setNativeWindow(ANativeWindow* nativeWindow, int width, int height){
    if (Log::isDebug()) Log_d("set native window size w: %d, h: %d", width, height);
    nativeWindow_ = nativeWindow;
    screenSize_.width = width;
    screenSize_.height = height;
}

void OMXVideoCodec::setSps(common::DataConstBuffer &b){
    if (Log::isDebug()) Log_d("sps packet len %d", b.size);
    omxSource_->getFormat()->setData(kKeyAVCC, kTypeAVCC, b.cdata, b.size);
}

OMXSource::Pointer OMXVideoCodec::source() {
    return omxSource_;
}

OMXDecoder::Pointer OMXVideoCodec::decoder() {
    return omxDecoder_;
}

void OMXVideoCodec::queueBuffer(common::DataConstBuffer& buffer, int64_t timestamp) {
    if (decoderThreadQuitFlag_){
        if(Log::isVerbose()) Log_v("decoder is stopping, return");
        return;
    }

    VideoStats::instance().onReceived(buffer.size, timestamp);

    const auto* data = static_cast<const uint8_t*>(buffer.cdata);
    std::size_t size = buffer.size;
    // Parameter sets come once per session (and with some encoders before
    // every keyframe), so the rewrite costs nothing on ordinary frames.
    std::vector<uint8_t> patched;
    if (h264::inspectNalUnits(data, size).hasSps && rewriteSps(data, size, &patched)) {
        data = patched.data();
        size = patched.size();
    }

    auto* mBuffer = new MediaBuffer(size);
    if (Log::isVerbose()) Log_v("create buffer %p", mBuffer);
    memcpy(mBuffer->data(), data, size);
    mBuffer->set_range(0, size);
    mBuffer->meta_data()->clear();
    mBuffer->meta_data()->setInt64(kKeyTime, timestamp);

    source()->queueBuffer(mBuffer);
}

bool OMXVideoCodec::rewriteSps(const uint8_t* data, std::size_t size, std::vector<uint8_t>* patched) {
    bool sawSps = false;
    h264::SpsInfo info;
    h264::SpsPatch result = h264::SpsPatch::Invalid;
    const bool changed = h264::patchAccessUnit(data, size, patched, &sawSps, &info, &result);
    if (!sawSps) {
        return false;
    }

    std::string summary = "SPS ";
    summary += result == h264::SpsPatch::Invalid ? "unreadable" : h264::describeSps(info);
    summary += " | ";
    summary += options_.spsLowLatency ? h264::describePatch(result) : "patch off";
    if (summary != streamSummary_) {
        streamSummary_ = summary;
        if (Log::isInfo()) Log_i("%s", summary.c_str());
        VideoStats::instance().setStreamSummary(summary);
    }
    return options_.spsLowLatency && changed;
}
