#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

// Per-stage counters of the video path, summarised every couple of seconds in
// logcat and on the optional on-screen overlay:
//
//   RX   frames handed over by aasdk (one per Android Auto video message)
//   IN   frames OMXCodec pulled from OMXSource for the decoder
//   OUT  frames the decoder returned
//   SHOW frames queued to the native window
//
// plus arrival->display latency, queue/decoder depth, drops and CPU usage.
// Every hook is a single branch while the collector is stopped.
class VideoStats {
public:
    static VideoStats& instance();

    void start();
    void stop();
    bool enabled() const { return enabled_.load(std::memory_order_relaxed); }

    // One line describing the active decoder options; shown with every report.
    void setDecoderSummary(const std::string& summary);
    // One line describing the stream (SPS) and what the decoder made of it.
    void setStreamSummary(const std::string& summary);

    // Hot-path hooks.
    void onReceived(size_t bytes, int64_t timestampUs);
    void onQueueLength(size_t length);
    void onDropped(size_t count);
    void onFed(bool hasSlice);
    void onDecoded();
    void onRendered(int64_t timestampUs);
    void onKeyFrame(size_t bytes);
    // An empty input buffer handed to the decoder while no frame arrived.
    void onPush();
    // Early release parked an EMPTY_BUFFER_DONE (no frame was waiting).
    void onPark();
    // Time the output thread spent handing a shown frame back to OMXCodec.
    void onRelease(int64_t ns);

    // Latest report, empty until the first window has closed. Any thread.
    std::string lastReport();

private:
    VideoStats() = default;
    VideoStats(const VideoStats&) = delete;
    VideoStats& operator=(const VideoStats&) = delete;

    struct Window {
        uint32_t rxFrames = 0;
        uint64_t rxBytes = 0;
        int64_t maxGapNs = 0;
        uint32_t fedFrames = 0;
        uint32_t decodedFrames = 0;
        uint32_t shownFrames = 0;
        uint32_t latencyCount = 0;
        int64_t latencySumNs = 0;
        int64_t latencyMaxNs = 0;
        uint32_t dropped = 0;
        size_t maxQueue = 0;
        int64_t maxDecoderDepth = 0;
        uint32_t releaseCount = 0;
        int64_t releaseSumNs = 0;
        int64_t releaseMaxNs = 0;
    };

    struct Arrival {
        int64_t timestampUs;
        int64_t arrivalNs;
    };

    static constexpr uint32_t kArrivalRing = 128;
    static constexpr int kReportIntervalMs = 2000;

    void run();
    void resetLocked();

    std::atomic<bool> enabled_{false};

    std::mutex mutex_;
    Window window_;
    int64_t lastArrivalNs_ = 0;
    Arrival arrivals_[kArrivalRing] = {};
    uint32_t arrivalHead_ = 0;
    size_t queueLength_ = 0;
    uint64_t fedTotal_ = 0;
    uint64_t decodedTotal_ = 0;
    uint64_t droppedTotal_ = 0;
    uint64_t keyFrames_ = 0;
    size_t lastKeyFrameBytes_ = 0;
    uint64_t pushTotal_ = 0;
    uint64_t parkTotal_ = 0;
    std::string decoderSummary_;
    std::string streamSummary_;

    std::mutex reportMutex_;
    std::string lastReport_;

    std::mutex threadMutex_;
    std::condition_variable wakeup_;
    bool quit_ = false;
    std::thread thread_;
};
