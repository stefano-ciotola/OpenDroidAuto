#include "EarlyReleaseOMX.h"
#include <Log.h>
#include <string.h>

namespace {

constexpr const char* kNvidiaAvcDecoder = "OMX.Nvidia.h264.decode";

} // namespace

// Only the Nvidia decoder gets the parking observer: it is the decoder whose
// behaviour this was measured on, and the only one kHardwareCodecsOnly picks
// on this head unit.
status_t EarlyReleaseOMX::allocateNode(const char* name, const sp<IOMXObserver>& observer, node_id* node) {
    const bool nvidia = name != nullptr && strcmp(name, kNvidiaAvcDecoder) == 0;
    sp<IOMXObserver> nodeObserver = observer;
    if (nvidia && source_ != nullptr) {
        nodeObserver = new EarlyReleaseObserver(observer, source_);
    }
    const status_t err = omx_->allocateNode(name, nodeObserver, node);
    if (err == OK && nvidia && source_ != nullptr) {
        source_->setEmptyDoneObserver(observer);
        installed_ = true;
        if (Log::isInfo()) Log_i("early frame release installed on %s", name);
    } else if (Log::isInfo()) {
        Log_i("allocateNode %s -> %d (early release %s)", name != nullptr ? name : "?", err,
              nvidia ? "not installed" : "not for this component");
    }
    return err;
}
