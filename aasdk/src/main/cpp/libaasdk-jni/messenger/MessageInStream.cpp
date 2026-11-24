#include <messenger/MessageInStream.hpp>
#include <error/Error.hpp>
#include <Log.h>

namespace aasdk
{
namespace messenger
{

MessageInStream::MessageInStream(aasdk::io::ioService& ioService, transport::ITransport::Pointer transport, ICryptor::Pointer cryptor)
    : strand_(ioService)
    , transport_(std::move(transport))
    , cryptor_(std::move(cryptor))
    , isStopping_(false)
{
}

MessageInStream::~MessageInStream(){
    if (Log::isVerbose()) Log_v("destructor");
}

void MessageInStream::startReceive(ReceivePromise::Pointer promise)
{
    if (isStopping_) {
        if (Log::isInfo()) Log_i("Messenger stopped");
        return;
    }

    strand_->dispatch([this, self = this->shared_from_this(), promise = std::move(promise)]() mutable {
        if (Log::isVerbose()) Log_v("startReceive");
        if(promise_ == nullptr)
        {
            promise_ = std::move(promise);

            auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_, "MessageInStream_receive");
            transportPromise->then(
                    [this, self = this->shared_from_this()](common::Data data) mutable {
                        this->receiveFrameHeaderHandler(common::DataConstBuffer(data));
                    },
                    [this, self = this->shared_from_this()](const error::Error& e) mutable {
                        promise_->reject(e);
                        promise_.reset();
                    });

            transport_->receive(FrameHeader::getSizeOf(), std::move(transportPromise));
        }
        else
        {
            promise->reject(error::Error(error::ErrorCode::OPERATION_IN_PROGRESS));
        }
    });
}

void MessageInStream::receiveFrameHeaderHandler(const common::DataConstBuffer& buffer) {
    FrameHeader frameHeader(buffer);
    if (Log::isVerbose()) Log_v("%s / receiveFrameHeaderHandler: %s", channelIdToString(frameHeader.getChannelId()).c_str(), frameHeader.toString().c_str());
    if (Log::isVerbose() && Log::logProtocol()) Log_v("%s/receiveFrameHeaderHandler %s", channelIdToString(frameHeader.getChannelId()).c_str(), common::dump(buffer).c_str());

    if (message_ != nullptr) {
        if (Log::isDebug()) Log_d("%s / residual message present, buffering it", channelIdToString(message_->getChannelId()).c_str());
        messageBuffer_[message_->getChannelId()] = std::move(message_);
        message_ = nullptr;
    }

    if (frameHeader.getType() == FrameType::BULK) {
        // Keep any in-flight fragmented message on the same channel buffered while
        // handling an interleaved BULK frame.
        message_ = std::make_shared<Message>(frameHeader.getChannelId(), frameHeader.getEncryptionType(), frameHeader.getMessageType());
    } else if (frameHeader.getType() == FrameType::FIRST) {
        messageBuffer_.erase(frameHeader.getChannelId());
        message_ = std::make_shared<Message>(frameHeader.getChannelId(), frameHeader.getEncryptionType(), frameHeader.getMessageType());
    } else {
        auto bufferedMessage = messageBuffer_.find(frameHeader.getChannelId());
        if (bufferedMessage != messageBuffer_.end()) {
            message_ = std::move(bufferedMessage->second);
            messageBuffer_.erase(bufferedMessage);
        } else {
            if (Log::isWarn()) Log_w("%s / missing buffered message for frameType %s", channelIdToString(frameHeader.getChannelId()).c_str(), frameTypeToString(frameHeader.getType()).c_str());
            message_ = std::make_shared<Message>(frameHeader.getChannelId(), frameHeader.getEncryptionType(), frameHeader.getMessageType());
        }
    }

    recentFrameType_ = frameHeader.getType();
    const size_t frameSize = FrameSize::getSizeOf(frameHeader.getType() == FrameType::FIRST ? FrameSizeType::EXTENDED : FrameSizeType::SHORT);

    auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_, "MessageInStream_frameSizeHandler");
    transportPromise->then(
        [this, self = this->shared_from_this()](common::Data data) mutable {
            this->receiveFrameSizeHandler(common::DataConstBuffer(data));
        },
        [this, self = this->shared_from_this()](const error::Error& e) mutable {
            message_.reset();
            promise_->reject(e);
            promise_.reset();
        });

    transport_->receive(frameSize, std::move(transportPromise));
}

void MessageInStream::receiveFrameSizeHandler(const common::DataConstBuffer& buffer)
{
    if (Log::isVerbose()) Log_v("%s / receiveFrameSizeHandler", channelIdToString(message_->getChannelId()).c_str());
    if (Log::isVerbose() && Log::logProtocol()) Log_v("%s/receiveFrameSizeHandler %s", channelIdToString(message_->getChannelId()).c_str(), common::dump(buffer).c_str());
    auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_, "MessageInStream_framePayloadHandler");
    transportPromise->then(
            [this, self = this->shared_from_this()](common::Data data) mutable {
                this->receiveFramePayloadHandler(common::DataConstBuffer(data));
            },
            [this, self = this->shared_from_this()](const error::Error& e) mutable {
                message_.reset();
                promise_->reject(e);
                promise_.reset();
            });

    FrameSize frameSize(buffer);
    if (Log::isVerbose()) Log_v("%s / frameSize %s", channelIdToString(message_->getChannelId()).c_str(), frameSize.toString().c_str());
    transport_->receive(frameSize.getSize(), std::move(transportPromise));
}

void MessageInStream::receiveFramePayloadHandler(const common::DataConstBuffer& buffer) {
    if (Log::isVerbose()) Log_v("%s / receiveFramePayloadHandler", channelIdToString(message_->getChannelId()).c_str());
    if (Log::isVerbose() && Log::logProtocol()) Log_v("%s/receiveFramePayloadHandler %s", channelIdToString(message_->getChannelId()).c_str(), common::dump(buffer).c_str());

    if (message_->getEncryptionType() == EncryptionType::ENCRYPTED) {
        try {
            if (Log::isVerbose()) Log_v("%s / frame encrypted", channelIdToString(message_->getChannelId()).c_str());
            cryptor_->decrypt(message_->getPayload(), buffer);
        }
        catch (const error::Error &e) {
            message_.reset();
            promise_->reject(e);
            promise_.reset();
            return;
        }
    } else {
        if (Log::isVerbose()) Log_v("%s / frame not encrypted, add payload to message", channelIdToString(message_->getChannelId()).c_str());
        message_->insertPayload(buffer);
    }

    if (recentFrameType_ == FrameType::BULK && message_->getPayload().empty()) {
        // Partial TLS record buffered in Cryptor (WANT_READ): keep reading next frame
        // instead of passing an empty payload to MessageId(data).
        if (Log::isDebug()) Log_d("%s / empty BULK payload after decrypt (partial TLS fragment), waiting for next frame", channelIdToString(message_->getChannelId()).c_str());
        message_.reset();
        auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_, "MessageInStream_frameHeaderHandler_continue");
        transportPromise->then(
                [this, self = this->shared_from_this()](common::Data data) mutable {
                    this->receiveFrameHeaderHandler(common::DataConstBuffer(data));
                },
                [this, self = this->shared_from_this()](const error::Error &e) mutable {
                    message_.reset();
                    promise_->reject(e);
                    promise_.reset();
                });

        transport_->receive(FrameHeader::getSizeOf(), std::move(transportPromise));
    } else if (recentFrameType_ == FrameType::BULK || recentFrameType_ == FrameType::LAST) {
        if (message_->getPayload().size() < 2) {
            Log_e("%s / message payload too short (%zu bytes)", channelIdToString(message_->getChannelId()).c_str(), message_->getPayload().size());
            message_.reset();
            promise_->reject(error::Error(error::ErrorCode::PARSE_PAYLOAD));
            promise_.reset();
            return;
        }
        if (Log::isVerbose()) Log_v("%s / message BULK or LAST, resolve: %s", channelIdToString(message_->getChannelId()).c_str(), message_->toString().c_str());
        if (Log::isVerbose() && Log::logProtocol()) Log_v("%s / plain payload: %s", channelIdToString(message_->getChannelId()).c_str(), common::dump(message_->getPayload()).c_str());

        promise_->resolve(std::move(message_));
        message_.reset();
        promise_.reset();
    } else {
        if (Log::isVerbose()) Log_v("%s / message FIRST or MIDDLE, wait to receive remaining data", channelIdToString(message_->getChannelId()).c_str());
        messageBuffer_[message_->getChannelId()] = std::move(message_);
        message_.reset();
        auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_, "MessageInStream_frameHeaderHandler_continue");
        transportPromise->then(
                [this, self = this->shared_from_this()](common::Data data) mutable {
                    this->receiveFrameHeaderHandler(common::DataConstBuffer(data));
                },
                [this, self = this->shared_from_this()](const error::Error &e) mutable {
                    message_.reset();
                    promise_->reject(e);
                    promise_.reset();
                });

        transport_->receive(FrameHeader::getSizeOf(), std::move(transportPromise));
    }
}

void MessageInStream::stop() {
    if(Log::isVerbose()) Log_v("stop");
    isStopping_ = true;
    strand_ = boost::none;
}

}
}
