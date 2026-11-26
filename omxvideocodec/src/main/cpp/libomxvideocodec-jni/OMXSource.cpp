#include "OMXSource.h"
#include "H264NalParser.h"
#include "VideoStats.h"
#include "Log.h"

#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/openmax/OMX_IVCommon.h>
#include <media/stagefright/openmax/OMX_Video.h>
#include <cinttypes>
#include <sstream>
#include <sys/prctl.h>

using namespace android;

namespace {

// Previous behaviour: a short backlog already counts as "the decoder is late".
constexpr std::size_t kLegacyMaxQueuedBuffers = 8;
constexpr int64_t kLegacyMaxQueuedDurationUs = 250000;

// Without legacy dropping the queue only gives up when something is badly
// stuck (about 3 s of video), to bound memory. Dropping P-frames breaks the
// reference chain, so below this limit every frame is decoded.
constexpr std::size_t kSafetyMaxQueuedBuffers = 90;
constexpr int64_t kSafetyMaxQueuedDurationUs = 3000000;

constexpr std::size_t kMaxSyncWaitBuffers = 8;
constexpr auto kMaxSyncWait = std::chrono::milliseconds(250);

// Idle push: how long to wait for a frame before each empty buffer. Four
// pushes cover about 150 ms after a frame, enough for the decoder to finish
// it; after that read() just waits for the next frame.
constexpr std::chrono::milliseconds kPushDelays[] = {
    std::chrono::milliseconds(10), std::chrono::milliseconds(20),
    std::chrono::milliseconds(40), std::chrono::milliseconds(80),
};
constexpr std::size_t kMaxPushes = sizeof(kPushDelays) / sizeof(kPushDelays[0]);

} // namespace

// https://www.programmersought.com/article/87712558400/
// https://vec.io/posts/use-android-hardware-decoder-with-omxcodec-in-ndk
// https://stackoverflow.com/questions/9832503/android-include-native-stagefright-features-in-my-own-project
OMXSource::OMXSource(int width, int height, int fps, bool legacyDrop, bool idlePush):
        format_(nullptr),
        maxQueuedBuffers_(legacyDrop ? kLegacyMaxQueuedBuffers : kSafetyMaxQueuedBuffers),
        maxQueuedDurationUs_(legacyDrop ? kLegacyMaxQueuedDurationUs : kSafetyMaxQueuedDurationUs),
        quitFlag_(false), waitingForSync_(false), droppedBuffers_(0),
        syncWaitDroppedBuffers_(0),
        idlePush_(idlePush), pushesSinceFrame_(kMaxPushes), lastFrameTimeUs_(-1),
        claimed_(0)
{

    int32_t bufferSize = (width * height * 3) / 2;
    if (Log::isVerbose()) Log_v("BufferSize: %d", bufferSize);
    if (Log::isInfo()) Log_i("backlog limit: %d buffers / %lld us (%s)",
                             static_cast<int>(maxQueuedBuffers_),
                             static_cast<long long>(maxQueuedDurationUs_),
                             legacyDrop ? "legacy drop" : "safety limit only");

    format_ = new MetaData();
    format_->setInt32(kKeyWidth, width);
    format_->setInt32(kKeyHeight, height);
    format_->setInt32(kKeyStride, width);
    format_->setInt32(kKeySliceHeight, height);
    format_->setInt32(kKeyFrameRate, fps);
//    format_->setInt32(kKeyIFramesInterval, 1);
    format_->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
    format_->setInt32(kKeyVideoLevel, OMX_VIDEO_AVCLevel31);
    format_->setInt32(kKeyVideoProfile, OMX_VIDEO_AVCProfileBaseline);
    format_->setInt32(kKeyMaxInputSize, bufferSize);
    format_->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420Planar);
}

OMXSource::~OMXSource() {
    if (Log::isDebug()) Log_d("destructor");
    {
        std::unique_lock<std::mutex> l(mutex_);
        quitFlag_ = true;
        forwardCond_.notify_one();
    }
    std::lock_guard<std::mutex> guard(joinMutex_);
    if (forwarder_.joinable()) {
        if (forwarder_.get_id() != std::this_thread::get_id()) {
            forwarder_.join();
        } else {
            forwarder_.detach();
        }
    }
}

void OMXSource::queueBuffer(MediaBuffer* buffer){
    if (Log::isVerbose()) Log_v("add buffer to queue");
    VideoStats& stats = VideoStats::instance();
    std::unique_lock<std::mutex> l(mutex_);

    if (quitFlag_) {
        buffer->release();
        return;
    }

    const auto* bufferData = static_cast<const uint8_t*>(buffer->data());
    const auto* data = bufferData == nullptr ? nullptr : bufferData + buffer->range_offset();
    const h264::NalInfo nalInfo = h264::inspectNalUnits(data, buffer->range_length());
    if (nalInfo.hasIdr) {
        stats.onKeyFrame(buffer->range_length());
    }
    int64_t timestampUs = 0;
    int64_t oldestTimestampUs = 0;
    buffer->meta_data()->findInt64(kKeyTime, &timestampUs);

    bool durationExceeded = false;
    if (!pbuffers_.empty() && timestampUs > 0 &&
        pbuffers_.front().buffer->meta_data()->findInt64(kKeyTime, &oldestTimestampUs) &&
        oldestTimestampUs > 0 && timestampUs > oldestTimestampUs) {
        durationExceeded = timestampUs - oldestTimestampUs > maxQueuedDurationUs_;
    }

    if (!waitingForSync_ &&
        (pbuffers_.size() >= maxQueuedBuffers_ || durationExceeded)) {
        const std::size_t flushed = pbuffers_.size();
        droppedBuffers_ += flushed;
        clearQueuedBuffers();
        stats.onDropped(flushed);
        waitingForSync_ = true;
        syncWaitDroppedBuffers_ = 0;
        syncWaitStarted_ = std::chrono::steady_clock::now();
        if (Log::isWarn()) {
            Log_w("video decoder backlog exceeded %lld us / %d buffers; briefly waiting for an IDR frame",
                  static_cast<long long>(maxQueuedDurationUs_), static_cast<int>(maxQueuedBuffers_));
        }
    }

    if (waitingForSync_ && !nalInfo.hasCodecConfig && !nalInfo.hasIdr) {
        const bool waitExpired = syncWaitDroppedBuffers_ >= kMaxSyncWaitBuffers ||
            std::chrono::steady_clock::now() - syncWaitStarted_ >= kMaxSyncWait;
        if (!waitExpired) {
            ++droppedBuffers_;
            ++syncWaitDroppedBuffers_;
            buffer->release();
            stats.onDropped(1);
            return;
        }

        // Some transitions do not emit an IDR promptly. The decoder still owns its
        // previous reference frames, so resume feeding instead of freezing forever.
        waitingForSync_ = false;
        syncWaitDroppedBuffers_ = 0;
        if (Log::isWarn()) {
            Log_w("video decoder IDR wait expired after dropping %d buffers; resuming stream",
                  static_cast<int>(droppedBuffers_));
        }
        droppedBuffers_ = 0;
        buffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);
    } else {
        buffer->meta_data()->setInt32(kKeyIsSyncFrame, nalInfo.hasIdr ? 1 : 0);
    }

    pbuffers_.push_back(Pending{buffer, nalInfo.hasSlice, false});
    stats.onQueueLength(pbuffers_.size());

    if (nalInfo.hasIdr && waitingForSync_) {
        waitingForSync_ = false;
        syncWaitDroppedBuffers_ = 0;
        if (Log::isWarn()) {
            Log_w("video decoder resynchronized after dropping %d buffers",
                  static_cast<int>(droppedBuffers_));
        }
        droppedBuffers_ = 0;
    }

    if (Log::isVerbose()) Log_v("queueBuffer new size %d", pbuffers_.size());
    cond_.notify_one();
    if (!parked_.empty()) {
        forwardCond_.notify_one();
    }
}

sp<MetaData>  OMXSource::getFormat(){
    return format_;
}

// Blocks until a frame is available. OMXCodec calls read() from its OMX
// callbacks, so while this waits no other callback of the codec is delivered;
// DecoderOptions::oneInputBuffer keeps that to a single pending callback.
//
// That pending callback can be the one that hands out a decoded frame: the
// frame then reaches the screen only when the phone sends the next one, which
// on a static screen (5 frames per second) is a fifth of a second later, or
// more. With idlePush, when no frame arrives within a few milliseconds the
// decoder gets an empty input buffer instead: it returns it at once, read()
// returns, and the codec's waiting callbacks run.
bool OMXSource::nextBuffer(Pending* pending){
    if (Log::isVerbose()) Log_v("nextBuffer");
    std::unique_lock<std::mutex> l(mutex_);

    while (!quitFlag_) {
        if (pbuffers_.empty()){
            if (idlePush_ && pushesSinceFrame_ < kMaxPushes && lastFrameTimeUs_ >= 0) {
                cond_.wait_for(l, kPushDelays[pushesSinceFrame_]);
                if (!quitFlag_ && pbuffers_.empty()) {
                    ++pushesSinceFrame_;
                    pending->buffer = makePushBuffer();
                    pending->hasSlice = false;
                    pending->push = true;
                    if (claimed_ > 0) {
                        --claimed_;
                    }
                    return true;
                }
                continue;
            }
            cond_.wait_until(l, std::chrono::steady_clock::now() + std::chrono::milliseconds(1000));
            continue;
        }

        if (Log::isVerbose()) Log_v("buffer size %d", pbuffers_.size());
        *pending = pbuffers_.front();
        pbuffers_.pop_front();
        if (claimed_ > 0) {
            --claimed_;
        }
        VideoStats::instance().onQueueLength(pbuffers_.size());
        if (Log::isVerbose()) Log_v("found buffer %d", pbuffers_.size());
        if (pending->hasSlice) {
            int64_t timeUs = 0;
            if (pending->buffer->meta_data()->findInt64(kKeyTime, &timeUs) && timeUs >= 0) {
                lastFrameTimeUs_ = timeUs;
                pushesSinceFrame_ = 0;
            }
        }
        return true;
    }

    return false;
}

// An empty input buffer. Its time is a microsecond after the last frame's, so
// it never matches a frame in the statistics and keeps timestamps increasing.
MediaBuffer* OMXSource::makePushBuffer() {
    auto* buffer = new MediaBuffer(16);
    buffer->set_range(0, 0);
    buffer->meta_data()->clear();
    buffer->meta_data()->setInt64(kKeyTime, lastFrameTimeUs_ + 1);
    return buffer;
}

status_t OMXSource::read(MediaBuffer **buffer, const MediaSource::ReadOptions *options) {
    if (Log::isVerbose()) Log_v("read");

    Pending pending{nullptr, false, false};
    if (!nextBuffer(&pending)){
        return ERROR_END_OF_STREAM;
    }

    if (pending.push) {
        VideoStats::instance().onPush();
    } else {
        VideoStats::instance().onFed(pending.hasSlice);
    }

    MediaBuffer* mBuffer = pending.buffer;
    mBuffer->setObserver(this);
    mBuffer->add_ref();
    (*buffer) = mBuffer;

    return OK;

}

void OMXSource::signalBufferReturned(MediaBuffer *buffer) {
    if (Log::isVerbose()) Log_v("signalBufferReturned: %p", buffer);
    buffer->setObserver(nullptr);
    buffer->release();
}

status_t OMXSource::start(MetaData *params){
    if (Log::isInfo()) Log_i("start");
    return OK;
}

status_t OMXSource::stop() {
    if (Log::isDebug()) Log_d("stopping");
    std::unique_lock<std::mutex> l(mutex_);

    if (!quitFlag_) {
        if (Log::isInfo()) Log_i("stop");
        quitFlag_ = true;
        if (Log::isVerbose()) Log_v("quit flag to true");
        if (Log::isDebug()) Log_d("delete pbuffer: %d", pbuffers_.size());
        clearQueuedBuffers();
        cond_.notify_one();
        forwardCond_.notify_one();
    }
    l.unlock();
    // The forwarder passes on everything still parked: OMXCodec waits for
    // those input buffers when it stops. The first stop() comes from
    // OMXVideoCodec::shutdown(), without OMXCodec's lock; OMXCodec calls
    // stop() again later, holding its lock, when the forwarder is gone.
    joinForwarder();
    if (Log::isDebug()) Log_d("stopped");
    return OK;
}

void OMXSource::joinForwarder() {
    std::lock_guard<std::mutex> guard(joinMutex_);
    if (forwarder_.joinable() && forwarder_.get_id() != std::this_thread::get_id()) {
        forwarder_.join();
    }
}

void OMXSource::setEmptyDoneObserver(const sp<IOMXObserver>& codecObserver) {
    std::unique_lock<std::mutex> l(mutex_);
    codecObserver_ = codecObserver;
    if (!forwarder_.joinable() && !quitFlag_) {
        forwarder_ = std::thread(&OMXSource::forwardParked, this);
    }
}

std::size_t OMXSource::unclaimedFrames() const {
    return pbuffers_.size() > claimed_ ? pbuffers_.size() - claimed_ : 0;
}

bool OMXSource::parkEmptyDone(const omx_message& msg) {
    std::unique_lock<std::mutex> l(mutex_);
    if (quitFlag_ || codecObserver_ == nullptr) {
        return false;
    }
    if (unclaimedFrames() > 0) {
        // OMXCodec's read() will find a frame at once: pass the message on.
        ++claimed_;
        return false;
    }
    parked_.push_back(msg);
    VideoStats::instance().onPark();
    return true;
}

// Own thread: OMXCodec may hold its lock while it waits in read() (its first
// input buffer is read from its own read()), so passing a parked message on
// from the thread that queues the phone's frames could deadlock.
void OMXSource::forwardParked() {
    prctl(PR_SET_NAME, "ODA-OMXRelease", 0, 0, 0);
    std::unique_lock<std::mutex> l(mutex_);
    while (true) {
        forwardCond_.wait(l, [this]() { return quitFlag_ || (!parked_.empty() && unclaimedFrames() > 0); });
        if (parked_.empty()) {
            if (quitFlag_) {
                break;
            }
            continue;
        }
        const omx_message msg = parked_.front();
        parked_.pop_front();
        if (!quitFlag_) {
            ++claimed_;
        }
        const sp<IOMXObserver> observer = codecObserver_;
        l.unlock();
        if (observer != nullptr) {
            observer->onMessage(msg);
        }
        l.lock();
    }
    if (Log::isDebug()) Log_d("early release forwarder ends");
}

void OMXSource::clearQueuedBuffers() {
    while (!pbuffers_.empty()) {
        MediaBuffer* buffer = pbuffers_.front().buffer;
        pbuffers_.pop_front();
        buffer->release();
    }
}
