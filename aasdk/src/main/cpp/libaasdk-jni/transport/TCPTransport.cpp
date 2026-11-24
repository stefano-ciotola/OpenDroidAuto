#include <transport/TCPTransport.hpp>
#include <Log.h>

namespace aasdk
{
namespace transport
{

TCPTransport::TCPTransport(aasdk::io::ioService& ioService, tcp::ITCPEndpoint::Pointer tcpEndpoint)
    : Transport(ioService)
    , tcpEndpoint_(std::move(tcpEndpoint))
{

}

void TCPTransport::enqueueReceive(common::DataBuffer buffer)
{
    if (isStopping_) {
        this->rejectReceivePromises(error::Error(error::ErrorCode::OPERATION_ABORTED));
        return;
    }
    if (Log::isVerbose()) Log_v("enqueueReceive");
    auto receivePromise = tcp::ITCPEndpoint::Promise::defer(receiveStrand_, "TCPTransport_enqueueReceive");
    receivePromise->then([this, self = this->shared_from_this()](auto bytesTransferred) {
            this->receiveHandler(bytesTransferred);
        },
        [this, self = this->shared_from_this()](auto e) {
            this->rejectReceivePromises(e);
        });

    tcpEndpoint_->receive(buffer, std::move(receivePromise));
}

void TCPTransport::enqueueSend(SendQueue::iterator queueElement)
{
    if (isStopping_) {
        auto promise = std::move(queueElement->second);
        sendQueue_.erase(queueElement);
        if (promise) {
            promise->reject(error::Error(error::ErrorCode::OPERATION_ABORTED));
        }
        return;
    }

    auto sendPromise = tcp::ITCPEndpoint::Promise::defer(sendStrand_, "TCPTransport_enqueueSend");
    auto payload = std::make_shared<common::Data>(queueElement->first);

    sendPromise->then([this, self = this->shared_from_this(), queueElement, payload](auto) {
        this->sendHandler(queueElement, error::Error());
    },
    [this, self = this->shared_from_this(), queueElement, payload](auto e) {
        this->sendHandler(queueElement, e);
    });

	if (Log::isDebug()) Log_d("send to tcpendpoint");
    tcpEndpoint_->send(common::DataConstBuffer(*payload), std::move(sendPromise));
}

void TCPTransport::stop()
{
    if (Log::isDebug()) Log_d("stop tcp endpoint");
    if (isStopping_.exchange(true)) {
        return;
    }

    // Close the socket on sendStrand_ so it serializes with async_write startup and
    // drain queues without freeing payload buffers captured by in-flight promises.
    if (sendStrand_) {
        sendStrand_->dispatch([this, self = this->shared_from_this()]() {
            if (tcpEndpoint_) {
                tcpEndpoint_->stop();
            }
            for (auto& element : sendQueue_) {
                if (element.second) {
                    element.second->reject(error::Error(error::ErrorCode::OPERATION_ABORTED));
                }
            }
            sendQueue_.clear();
        });
    } else if (tcpEndpoint_) {
        tcpEndpoint_->stop();
    }

    if (receiveStrand_) {
        receiveStrand_->dispatch([this, self = this->shared_from_this()]() {
            this->rejectReceivePromises(error::Error(error::ErrorCode::OPERATION_ABORTED));
        });
    }
}

void TCPTransport::sendHandler(SendQueue::iterator queueElement, const error::Error& e)
{
    if (isStopping_) {
        return;
    }

    if(!e)
    {
        queueElement->second->resolve();
    }
    else
    {
        queueElement->second->reject(e);
    }

    sendQueue_.erase(queueElement);

    if(!sendQueue_.empty() && !isStopping_)
    {
        this->enqueueSend(sendQueue_.begin());
    }
}

}
}
