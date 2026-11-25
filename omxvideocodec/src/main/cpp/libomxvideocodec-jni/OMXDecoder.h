#pragma once

#include <string>
#include <utils/Errors.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/MediaSource.h>
#include <OMXSource.h>
#include "DecoderOptions.h"

using namespace android;

class OMXDecoder {
public:
    typedef OMXDecoder* Pointer;

    explicit OMXDecoder();

    virtual status_t read();
    void setNativeWindow(sp<ANativeWindow> &nativeWindow);
    void setSource(OMXSource *source);
    status_t  init(const DecoderOptions& options);
    int32_t frameRate() const;
    void stop();
    ~OMXDecoder();
    status_t getStatus() const;
    // What init() actually enabled, for the statistics overlay and logcat.
    const std::string& summary() const;
protected:


private:
    std::string applyNvidiaLowLatency(uint32_t requestedFlags);

    OMXClient* client_;
    sp<MediaSource> decoder_;
    sp<ANativeWindow> nativeWindow_;
    OMXSource::Pointer source_;
    std::mutex mutex_;
    int32_t frameRate_;

    status_t status_;
    std::string summary_;
};
