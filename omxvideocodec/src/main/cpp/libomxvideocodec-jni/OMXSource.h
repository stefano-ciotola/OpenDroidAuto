#pragma once

#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/RefBase.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/IOMX.h>
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
    // idlePush: see nextBuffer().
    OMXSource(int width, int height, int fps, bool legacyDrop, bool idlePush);

    status_t read(MediaBuffer **buffer, const MediaSource::ReadOptions *options) override;
    status_t start(MetaData *params) override;
    status_t stop() override;
    sp<MetaData> getFormat() override;
    void queueBuffer(MediaBuffer* buffer);

    // Early release (EarlyReleaseOMX.h). OMXCodec's own observer: parked
    // EMPTY_BUFFER_DONE messages go to it from the forwarder thread, which
    // this starts.
    void setEmptyDoneObserver(const sp<IOMXObserver>& codecObserver);
    // Binder callback thread, for each EMPTY_BUFFER_DONE: true when the
    // message was parked because no frame is waiting; false to pass it on now.
    bool parkEmptyDone(const omx_message& msg);

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
        bool push;
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

    const bool idlePush_;
    // Empty buffers handed out since the last frame, and that frame's time.
    std::size_t pushesSinceFrame_;
    int64_t lastFrameTimeUs_;

    // Early release state, guarded by mutex_. claimed_ counts the
    // EMPTY_BUFFER_DONE messages passed on to OMXCodec whose read() has not
    // returned yet: each will take one of the queued frames.
    sp<IOMXObserver> codecObserver_;
    std::deque<omx_message> parked_;
    std::size_t claimed_;
    std::thread forwarder_;
    std::condition_variable forwardCond_;
    // stop() runs twice, possibly at once on two threads (shutdown and
    // OMXCodec::stop): only one of them may join the forwarder.
    std::mutex joinMutex_;

    bool nextBuffer(Pending* pending);
    MediaBuffer* makePushBuffer();
    void clearQueuedBuffers();
    std::size_t unclaimedFrames() const;
    void forwardParked();
    void joinForwarder();
};
