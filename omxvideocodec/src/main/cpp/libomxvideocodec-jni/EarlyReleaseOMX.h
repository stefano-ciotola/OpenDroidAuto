#pragma once

#include <media/IOMX.h>
#include "OMXSource.h"

using namespace android;

// Early release of decoded frames (DecoderOptions::earlyRelease).
//
// Adapted from the hirneagabriel/OpenDroidAuto fork (GPL-3.0, LowLatencyOMX,
// commits 05e4075 and d37a728), measured there on a 2015 Civic with the same
// Honda Connect unit: frames held in the decoder from 6-7.5 to 1.0.
//
// OMXCodec reads its next input from OMXSource inside the decoder's
// EMPTY_BUFFER_DONE callback, holding its own lock, and the decoder's
// callbacks reach OMXCodec one at a time (one-way binder calls to a single
// observer). While read() waits for the next frame from the phone, the
// FILL_BUFFER_DONE of a frame already decoded waits behind it, and the output
// thread waits for the same lock in MediaBuffer::release(): every frame
// reaches the screen only when the next one arrives.
//
// EarlyReleaseOMX forwards every call to the real IOMX. When OMXCodec
// allocates the Nvidia H.264 decoder it gives the decoder an observer that
// asks OMXSource first about each EMPTY_BUFFER_DONE: with no frame waiting the
// message is parked and the callback returns at once, so the decoded frame
// behind it is delivered; OMXSource passes the message on to OMXCodec, from
// its own thread, as soon as a frame arrives (OMXSource::forwardParked).
class EarlyReleaseObserver : public BnOMXObserver {
public:
    EarlyReleaseObserver(const sp<IOMXObserver>& codecObserver, const sp<OMXSource>& source)
        : codecObserver_(codecObserver), source_(source) {}

    void onMessage(const omx_message& msg) override {
        if (msg.type == omx_message::EMPTY_BUFFER_DONE && source_->parkEmptyDone(msg)) {
            return;
        }
        codecObserver_->onMessage(msg);
    }

private:
    sp<IOMXObserver> codecObserver_;
    sp<OMXSource> source_;
};

class EarlyReleaseOMX : public IOMX {
public:
    EarlyReleaseOMX(const sp<IOMX>& omx, const sp<OMXSource>& source)
        : omx_(omx), source_(source), installed_(false) {}

    // True once the observer was installed on the Nvidia decoder.
    bool installed() const { return installed_; }

    bool livesLocally(pid_t pid) override { return omx_->livesLocally(pid); }
    status_t listNodes(List<ComponentInfo>* list) override { return omx_->listNodes(list); }
    status_t allocateNode(const char* name, const sp<IOMXObserver>& observer, node_id* node) override;
    status_t freeNode(node_id node) override { return omx_->freeNode(node); }
    status_t sendCommand(node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param) override {
        return omx_->sendCommand(node, cmd, param);
    }
    status_t getParameter(node_id node, OMX_INDEXTYPE index, void* params, size_t size) override {
        return omx_->getParameter(node, index, params, size);
    }
    status_t setParameter(node_id node, OMX_INDEXTYPE index, const void* params, size_t size) override {
        return omx_->setParameter(node, index, params, size);
    }
    status_t getConfig(node_id node, OMX_INDEXTYPE index, void* params, size_t size) override {
        return omx_->getConfig(node, index, params, size);
    }
    status_t setConfig(node_id node, OMX_INDEXTYPE index, const void* params, size_t size) override {
        return omx_->setConfig(node, index, params, size);
    }
    status_t getState(node_id node, OMX_STATETYPE* state) override { return omx_->getState(node, state); }
    status_t storeMetaDataInBuffers(node_id node, OMX_U32 port_index, OMX_BOOL enable) override {
        return omx_->storeMetaDataInBuffers(node, port_index, enable);
    }
    status_t enableGraphicBuffers(node_id node, OMX_U32 port_index, OMX_BOOL enable) override {
        return omx_->enableGraphicBuffers(node, port_index, enable);
    }
    status_t getGraphicBufferUsage(node_id node, OMX_U32 port_index, OMX_U32* usage) override {
        return omx_->getGraphicBufferUsage(node, port_index, usage);
    }
    status_t useBuffer(node_id node, OMX_U32 port_index, const sp<IMemory>& params, buffer_id* buffer) override {
        return omx_->useBuffer(node, port_index, params, buffer);
    }
    status_t useGraphicBuffer(node_id node, OMX_U32 port_index, const sp<GraphicBuffer>& graphicBuffer,
                              buffer_id* buffer) override {
        return omx_->useGraphicBuffer(node, port_index, graphicBuffer, buffer);
    }
    status_t allocateBuffer(node_id node, OMX_U32 port_index, size_t size, buffer_id* buffer,
                            void** buffer_data) override {
        return omx_->allocateBuffer(node, port_index, size, buffer, buffer_data);
    }
    status_t allocateBufferWithBackup(node_id node, OMX_U32 port_index, const sp<IMemory>& params,
                                      buffer_id* buffer) override {
        return omx_->allocateBufferWithBackup(node, port_index, params, buffer);
    }
    status_t freeBuffer(node_id node, OMX_U32 port_index, buffer_id buffer) override {
        return omx_->freeBuffer(node, port_index, buffer);
    }
    status_t fillBuffer(node_id node, buffer_id buffer) override { return omx_->fillBuffer(node, buffer); }
    status_t emptyBuffer(node_id node, buffer_id buffer, OMX_U32 range_offset, OMX_U32 range_length,
                         OMX_U32 flags, OMX_TICKS timestamp) override {
        return omx_->emptyBuffer(node, buffer, range_offset, range_length, flags, timestamp);
    }
    status_t getExtensionIndex(node_id node, const char* parameter_name, OMX_INDEXTYPE* index) override {
        return omx_->getExtensionIndex(node, parameter_name, index);
    }

protected:
    IBinder* onAsBinder() override { return omx_->asBinder().get(); }

private:
    sp<IOMX> omx_;
    sp<OMXSource> source_;
    bool installed_;
};
