#include "VideoStats.h"
#include <Log.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <utility>
#include <vector>

#include <dirent.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <unistd.h>

namespace {

using Clock = std::chrono::steady_clock;

int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            Clock::now().time_since_epoch()).count();
}

// /proc and /sys files are tiny: a single read() returns all of them.
bool readFile(const char* path, char* buffer, size_t capacity) {
    const int fd = open(path, O_RDONLY);
    if (fd < 0) {
        return false;
    }
    const ssize_t length = read(fd, buffer, capacity - 1);
    close(fd);
    if (length <= 0) {
        return false;
    }
    buffer[length] = '\0';
    return true;
}

// Reads utime + stime (fields 14 and 15) and the thread name from a
// /proc/<pid>[/task/<tid>]/stat file. The name sits between the first '(' and
// the last ')' and may itself contain spaces or parentheses.
bool readTicks(const char* path, std::string* name, uint64_t* ticks) {
    char text[512];
    if (!readFile(path, text, sizeof(text))) {
        return false;
    }
    const char* open = strchr(text, '(');
    const char* close = strrchr(text, ')');
    if (open == nullptr || close == nullptr || close < open) {
        return false;
    }
    if (name != nullptr) {
        name->clear();
        for (const char* c = open + 1; c < close; ++c) {
            name->push_back(*c >= 0x20 && *c < 0x7f ? *c : '?');
        }
    }

    unsigned long long utime = 0;
    unsigned long long stime = 0;
    int field = 2;  // the first token after ')' is field 3 (state)
    for (const char* p = close + 1; *p != '\0';) {
        while (*p == ' ') {
            ++p;
        }
        if (*p == '\0') {
            break;
        }
        ++field;
        if (field == 14) {
            utime = strtoull(p, nullptr, 10);
        } else if (field == 15) {
            stime = strtoull(p, nullptr, 10);
            *ticks = utime + stime;
            return true;
        }
        while (*p != '\0' && *p != ' ') {
            ++p;
        }
    }
    return false;
}

// Samples CPU usage between two calls: whole system, this process, mediaserver
// (it hosts the OMX decoder), surfaceflinger and the busiest threads of this
// process. Percentages per process/thread are relative to one core.
class CpuSampler {
public:
    CpuSampler() {
        const long hz = sysconf(_SC_CLK_TCK);
        hz_ = hz > 0 ? hz : 100;
        findProcesses();
    }

    // Empty on the first call: there is nothing to compare against yet.
    std::string sample() {
        const Clock::time_point now = Clock::now();

        uint64_t total = 0;
        uint64_t idle = 0;
        const bool haveSystem = readSystem(&total, &idle);

        uint64_t self = 0;
        readTicks("/proc/self/stat", nullptr, &self);

        uint64_t media = 0;
        uint64_t flinger = 0;
        bool haveMedia = mediaPid_ > 0 && readProcess(mediaPid_, &media);
        bool haveFlinger = flingerPid_ > 0 && readProcess(flingerPid_, &flinger);
        if ((!haveMedia || !haveFlinger) && now - lastScan_ > std::chrono::seconds(30)) {
            // mediaserver restarts after a media crash; find the new process.
            findProcesses();
            haveMedia = mediaPid_ > 0 && readProcess(mediaPid_, &media);
            haveFlinger = flingerPid_ > 0 && readProcess(flingerPid_, &flinger);
            primedMedia_ = primedFlinger_ = false;
        }

        std::map<int, ThreadTicks> threads;
        readThreads(&threads);

        std::string line;
        if (primed_) {
            const double seconds = std::chrono::duration<double>(now - lastTime_).count();
            const double coreTicks = seconds * static_cast<double>(hz_);

            char buffer[256];
            int sysPercent = -1;
            if (haveSystem && total > lastTotal_) {
                const uint64_t totalDelta = total - lastTotal_;
                const uint64_t idleDelta = idle >= lastIdle_ ? idle - lastIdle_ : 0;
                sysPercent = static_cast<int>(100.0 * (totalDelta - std::min(idleDelta, totalDelta)) / totalDelta);
            }

            snprintf(buffer, sizeof(buffer), "CPU sys %s %ldx%s | app %d%%",
                     sysPercent >= 0 ? (std::to_string(sysPercent) + "%").c_str() : "-",
                     sysconf(_SC_NPROCESSORS_ONLN), currentFrequency().c_str(),
                     percent(self, lastSelf_, coreTicks));
            line = buffer;
            if (haveMedia && primedMedia_) {
                line += " media " + std::to_string(percent(media, lastMedia_, coreTicks)) + "%";
            }
            if (haveFlinger && primedFlinger_) {
                line += " sf " + std::to_string(percent(flinger, lastFlinger_, coreTicks)) + "%";
            }

            std::vector<std::pair<uint64_t, std::string>> busiest;
            for (const auto& entry : threads) {
                const auto previous = threads_.find(entry.first);
                if (previous != threads_.end() && entry.second.ticks > previous->second.ticks) {
                    busiest.emplace_back(entry.second.ticks - previous->second.ticks, entry.second.name);
                }
            }
            std::sort(busiest.begin(), busiest.end(),
                      [](const std::pair<uint64_t, std::string>& a, const std::pair<uint64_t, std::string>& b) {
                          return a.first > b.first;
                      });
            if (!busiest.empty()) {
                line += " |";
            }
            for (size_t i = 0; i < busiest.size() && i < 3; ++i) {
                line += " " + busiest[i].second + " " +
                        std::to_string(static_cast<int>(100.0 * busiest[i].first / coreTicks + 0.5)) + "%";
            }
        }

        primed_ = true;
        lastTime_ = now;
        lastTotal_ = total;
        lastIdle_ = idle;
        lastSelf_ = self;
        if (haveMedia) {
            lastMedia_ = media;
            primedMedia_ = true;
        }
        if (haveFlinger) {
            lastFlinger_ = flinger;
            primedFlinger_ = true;
        }
        threads_.swap(threads);
        return line;
    }

private:
    struct ThreadTicks {
        std::string name;
        uint64_t ticks;
    };

    static int percent(uint64_t now, uint64_t before, double coreTicks) {
        if (coreTicks <= 0 || now < before) {
            return 0;
        }
        return static_cast<int>(100.0 * static_cast<double>(now - before) / coreTicks + 0.5);
    }

    static bool readSystem(uint64_t* total, uint64_t* idle) {
        char text[512];
        if (!readFile("/proc/stat", text, sizeof(text)) || strncmp(text, "cpu ", 4) != 0) {
            return false;
        }
        // user nice system idle iowait irq softirq steal
        unsigned long long values[8] = {};
        char* p = text + 4;
        for (int i = 0; i < 8; ++i) {
            char* end = nullptr;
            values[i] = strtoull(p, &end, 10);
            if (end == p) {
                break;
            }
            p = end;
        }
        *total = 0;
        for (unsigned long long value : values) {
            *total += value;
        }
        *idle = values[3] + values[4];
        return true;
    }

    static bool readProcess(int pid, uint64_t* ticks) {
        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/stat", pid);
        return readTicks(path, nullptr, ticks);
    }

    static void readThreads(std::map<int, ThreadTicks>* threads) {
        DIR* dir = opendir("/proc/self/task");
        if (dir == nullptr) {
            return;
        }
        while (dirent* entry = readdir(dir)) {
            const int tid = atoi(entry->d_name);
            if (tid <= 0) {
                continue;
            }
            char path[64];
            snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);
            ThreadTicks thread;
            if (readTicks(path, &thread.name, &thread.ticks)) {
                (*threads)[tid] = thread;
            }
        }
        closedir(dir);
    }

    static std::string currentFrequency() {
        char text[32];
        if (!readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", text, sizeof(text))) {
            return "?MHz";
        }
        return std::to_string(strtoul(text, nullptr, 10) / 1000) + "MHz";
    }

    void findProcesses() {
        lastScan_ = Clock::now();
        mediaPid_ = -1;
        flingerPid_ = -1;
        DIR* dir = opendir("/proc");
        if (dir == nullptr) {
            return;
        }
        while (dirent* entry = readdir(dir)) {
            const int pid = atoi(entry->d_name);
            if (pid <= 0) {
                continue;
            }
            char path[64];
            char command[128];
            snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
            if (!readFile(path, command, sizeof(command))) {
                continue;
            }
            const char* base = strrchr(command, '/');
            base = base == nullptr ? command : base + 1;
            if (strcmp(base, "mediaserver") == 0) {
                mediaPid_ = pid;
            } else if (strcmp(base, "surfaceflinger") == 0) {
                flingerPid_ = pid;
            }
            if (mediaPid_ > 0 && flingerPid_ > 0) {
                break;
            }
        }
        closedir(dir);
    }

    long hz_ = 100;
    bool primed_ = false;
    bool primedMedia_ = false;
    bool primedFlinger_ = false;
    Clock::time_point lastTime_;
    Clock::time_point lastScan_;
    uint64_t lastTotal_ = 0;
    uint64_t lastIdle_ = 0;
    uint64_t lastSelf_ = 0;
    uint64_t lastMedia_ = 0;
    uint64_t lastFlinger_ = 0;
    int mediaPid_ = -1;
    int flingerPid_ = -1;
    std::map<int, ThreadTicks> threads_;
};

std::string formatRate(double value) {
    char buffer[16];
    snprintf(buffer, sizeof(buffer), "%.1f", value);
    return buffer;
}

} // namespace

// Out-of-line definitions: the NDK build is C++14, where these are not
// implicitly inline and binding them to a reference needs a definition.
constexpr uint32_t VideoStats::kArrivalRing;
constexpr int VideoStats::kReportIntervalMs;

VideoStats& VideoStats::instance() {
    static VideoStats stats;
    return stats;
}

void VideoStats::start() {
    std::lock_guard<std::mutex> threadLock(threadMutex_);
    if (thread_.joinable()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        resetLocked();
    }
    {
        std::lock_guard<std::mutex> lock(reportMutex_);
        lastReport_.clear();
    }
    quit_ = false;
    enabled_.store(true, std::memory_order_relaxed);
    thread_ = std::thread(&VideoStats::run, this);
    if (Log::isInfo()) Log_i("video statistics started");
}

void VideoStats::stop() {
    std::unique_lock<std::mutex> threadLock(threadMutex_);
    if (!thread_.joinable()) {
        return;
    }
    enabled_.store(false, std::memory_order_relaxed);
    quit_ = true;
    wakeup_.notify_all();
    std::thread thread = std::move(thread_);
    threadLock.unlock();
    thread.join();

    std::lock_guard<std::mutex> lock(reportMutex_);
    lastReport_.clear();
    if (Log::isInfo()) Log_i("video statistics stopped");
}

void VideoStats::resetLocked() {
    window_ = Window();
    lastArrivalNs_ = 0;
    for (Arrival& arrival : arrivals_) {
        arrival = Arrival{0, 0};
    }
    arrivalHead_ = 0;
    queueLength_ = 0;
    fedTotal_ = 0;
    decodedTotal_ = 0;
    droppedTotal_ = 0;
    keyFrames_ = 0;
    lastKeyFrameBytes_ = 0;
    streamSummary_.clear();
}

void VideoStats::setDecoderSummary(const std::string& summary) {
    std::lock_guard<std::mutex> lock(mutex_);
    decoderSummary_ = summary;
}

void VideoStats::setStreamSummary(const std::string& summary) {
    std::lock_guard<std::mutex> lock(mutex_);
    streamSummary_ = summary;
}

void VideoStats::onKeyFrame(size_t bytes) {
    if (!enabled()) {
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    ++keyFrames_;
    lastKeyFrameBytes_ = bytes;
}

void VideoStats::onReceived(size_t bytes, int64_t timestampUs) {
    if (!enabled()) {
        return;
    }
    const int64_t now = nowNs();
    std::lock_guard<std::mutex> lock(mutex_);
    ++window_.rxFrames;
    window_.rxBytes += bytes;
    if (lastArrivalNs_ != 0) {
        window_.maxGapNs = std::max(window_.maxGapNs, now - lastArrivalNs_);
    }
    lastArrivalNs_ = now;
    // Codec configuration arrives without a timestamp and never reaches the screen.
    if (timestampUs > 0) {
        arrivals_[arrivalHead_ % kArrivalRing] = Arrival{timestampUs, now};
        ++arrivalHead_;
    }
}

void VideoStats::onQueueLength(size_t length) {
    if (!enabled()) {
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    queueLength_ = length;
    window_.maxQueue = std::max(window_.maxQueue, length);
}

void VideoStats::onDropped(size_t count) {
    if (!enabled() || count == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    window_.dropped += static_cast<uint32_t>(count);
    droppedTotal_ += count;
}

void VideoStats::onFed(bool hasSlice) {
    if (!enabled() || !hasSlice) {
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    ++window_.fedFrames;
    ++fedTotal_;
    const int64_t depth = static_cast<int64_t>(fedTotal_) - static_cast<int64_t>(decodedTotal_);
    window_.maxDecoderDepth = std::max(window_.maxDecoderDepth, depth);
}

void VideoStats::onDecoded() {
    if (!enabled()) {
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    ++window_.decodedFrames;
    ++decodedTotal_;
}

void VideoStats::onRendered(int64_t timestampUs) {
    if (!enabled()) {
        return;
    }
    const int64_t now = nowNs();
    std::lock_guard<std::mutex> lock(mutex_);
    ++window_.shownFrames;
    if (timestampUs <= 0) {
        return;
    }
    const uint32_t available = arrivalHead_ < kArrivalRing ? arrivalHead_ : kArrivalRing;
    for (uint32_t i = 0; i < available; ++i) {
        const Arrival& arrival = arrivals_[(arrivalHead_ - 1 - i) % kArrivalRing];
        if (arrival.timestampUs == timestampUs) {
            const int64_t latency = now - arrival.arrivalNs;
            ++window_.latencyCount;
            window_.latencySumNs += latency;
            window_.latencyMaxNs = std::max(window_.latencyMaxNs, latency);
            break;
        }
    }
}

std::string VideoStats::lastReport() {
    std::lock_guard<std::mutex> lock(reportMutex_);
    return lastReport_;
}

void VideoStats::run() {
    prctl(PR_SET_NAME, "ODA-Stats", 0, 0, 0);

    CpuSampler cpu;
    cpu.sample();
    Clock::time_point windowStart = Clock::now();

    std::unique_lock<std::mutex> threadLock(threadMutex_);
    while (!quit_) {
        wakeup_.wait_for(threadLock, std::chrono::milliseconds(kReportIntervalMs), [this] { return quit_; });
        if (quit_) {
            break;
        }
        threadLock.unlock();

        const Clock::time_point now = Clock::now();
        const double seconds = std::max(0.001, std::chrono::duration<double>(now - windowStart).count());
        windowStart = now;

        Window window;
        size_t queueLength;
        int64_t decoderDepth;
        uint64_t droppedTotal;
        uint64_t keyFrames;
        size_t lastKeyFrameBytes;
        std::string summary;
        std::string stream;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            window = window_;
            queueLength = queueLength_;
            decoderDepth = std::max<int64_t>(0, static_cast<int64_t>(fedTotal_) - static_cast<int64_t>(decodedTotal_));
            droppedTotal = droppedTotal_;
            keyFrames = keyFrames_;
            lastKeyFrameBytes = lastKeyFrameBytes_;
            summary = decoderSummary_;
            stream = streamSummary_;
            // A frame that has been waiting since the last arrival is a gap too:
            // it is what a frozen picture looks like.
            if (lastArrivalNs_ != 0) {
                window.maxGapNs = std::max(window.maxGapNs, nowNs() - lastArrivalNs_);
            }
            window_ = Window();
            window_.maxQueue = queueLength;
            window_.maxDecoderDepth = decoderDepth;
        }

        char line1[160];
        snprintf(line1, sizeof(line1), "RX %s fps %u kb/s gap %d ms | IN %s | OUT %s | SHOW %s",
                 formatRate(window.rxFrames / seconds).c_str(),
                 static_cast<unsigned>(window.rxBytes * 8 / 1000 / seconds),
                 static_cast<int>(window.maxGapNs / 1000000),
                 formatRate(window.fedFrames / seconds).c_str(),
                 formatRate(window.decodedFrames / seconds).c_str(),
                 formatRate(window.shownFrames / seconds).c_str());

        char latency[64];
        if (window.latencyCount > 0) {
            snprintf(latency, sizeof(latency), "LAT avg %d max %d ms",
                     static_cast<int>(window.latencySumNs / window.latencyCount / 1000000),
                     static_cast<int>(window.latencyMaxNs / 1000000));
        } else {
            snprintf(latency, sizeof(latency), "LAT -");
        }
        char line2[192];
        snprintf(line2, sizeof(line2), "%s | in decoder %d (max %d) | queue %u (max %u) | drop %u (tot %llu)",
                 latency,
                 static_cast<int>(decoderDepth), static_cast<int>(window.maxDecoderDepth),
                 static_cast<unsigned>(queueLength), static_cast<unsigned>(window.maxQueue),
                 window.dropped, static_cast<unsigned long long>(droppedTotal));

        std::string report = line1;
        report += "\n";
        report += line2;
        const std::string cpuLine = cpu.sample();
        if (!cpuLine.empty()) {
            report += "\n" + cpuLine;
        }
        if (!summary.empty()) {
            report += "\n" + summary;
        }
        char keyFrameText[48];
        snprintf(keyFrameText, sizeof(keyFrameText), " | IDR %llu last %u KB",
                 static_cast<unsigned long long>(keyFrames),
                 static_cast<unsigned>((lastKeyFrameBytes + 512) / 1024));
        report += "\n" + (stream.empty() ? std::string("SPS -") : stream) + keyFrameText;

        if (Log::isInfo()) {
            // One logcat line per report, easy to grep for "RX ".
            std::string logLine;
            for (char c : report) {
                if (c == '\n') {
                    logLine += " || ";
                } else {
                    logLine += c;
                }
            }
            Log_i("%s", logLine.c_str());
        }

        {
            std::lock_guard<std::mutex> lock(reportMutex_);
            lastReport_ = report;
        }

        threadLock.lock();
    }
}
