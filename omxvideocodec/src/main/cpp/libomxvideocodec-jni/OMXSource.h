#pragma once

#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/RefBase.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <mutex>
#include <thread>
#include <Data.hpp>

using namespace android;

class OMXSource : public MediaSource, public MediaBufferObserver {
public:
    typedef OMXSource* Pointer;
    static constexpr common::Data::size_type cChunkSize = 38406 * 4;

    // legacyDrop: flush the backlog after 8 frames or 250 ms and wait for an
    // IDR frame (previous behaviour). Otherwise frames are only dropped past a
    // last-resort safety limit, so the decoder keeps its reference chain.
    OMXSource(int width, int height, int fps, bool legacyDrop);

    status_t read(MediaBuffer **buffer, const MediaSource::ReadOptions *options) override;
    status_t start(MetaData *params) override;
    status_t stop() override;
    sp<MetaData> getFormat() override;
    void queueBuffer(MediaBuffer* buffer);

    status_t pause() override {
        return ERROR_UNSUPPORTED;
    }

    status_t setBuffers(const Vector<MediaBuffer *> &buffers) override {
        return ERROR_UNSUPPORTED;
    }

    void signalBufferReturned(MediaBuffer *buffer) override;

    ~OMXSource() override;
protected:


private:
    struct Pending {
        MediaBuffer* buffer;
        bool hasSlice;
    };

    sp<MetaData> format_;
    std::deque<Pending> pbuffers_;
    std::mutex mutex_;
    std::condition_variable cond_;

    const std::size_t maxQueuedBuffers_;
    const int64_t maxQueuedDurationUs_;

    bool quitFlag_;
    bool waitingForSync_;
    std::size_t droppedBuffers_;
    std::size_t syncWaitDroppedBuffers_;
    std::chrono::steady_clock::time_point syncWaitStarted_;

    bool nextBuffer(Pending* pending);
    void clearQueuedBuffers();
};
