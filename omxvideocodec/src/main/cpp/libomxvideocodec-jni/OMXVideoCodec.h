#pragma once

#include <sys/types.h>
#include "DecoderOptions.h"
#include "OMXSource.h"
#include "OMXDecoder.h"
#include <cstddef>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>
#include <android/looper.h>

struct ScreenSize {
    int height = 0;
    int width = 0;
};

class OMXVideoCodec {
public:
    typedef OMXVideoCodec* Pointer;

    OMXVideoCodec();

    status_t init(int fps, const DecoderOptions& options);
    void shutdown();
    void setNativeWindow(ANativeWindow* nativeWindow, int width, int height);

    OMXSource::Pointer source();
    OMXDecoder::Pointer decoder();
    void queueBuffer(common::DataConstBuffer& b, int64_t timestamp);

    void setSps(common::DataConstBuffer &b);

    ~OMXVideoCodec();
private:
    void init_omxDecoderThread();
    // Applies the low-latency SPS rewrite when it is enabled and reports the
    // stream parameters; true when patched holds the buffer to decode.
    bool rewriteSps(const uint8_t* data, std::size_t size, std::vector<uint8_t>* patched);

    OMXSource::Pointer omxSource_;
    OMXDecoder::Pointer omxDecoder_;

    std::thread omxDecoderThread_;
    std::mutex codecMutex_;
    std::condition_variable omxDecoderCond_;

//    bool omxDecoderThreadRunning_;
    bool decoderThreadInitialized_;
    bool decoderThreadQuitFlag_;
    sp<ANativeWindow> nativeWindow_;
    ScreenSize screenSize_;
    int fps_;
    DecoderOptions options_;
    bool statsStarted_;
    std::string streamSummary_;
};
