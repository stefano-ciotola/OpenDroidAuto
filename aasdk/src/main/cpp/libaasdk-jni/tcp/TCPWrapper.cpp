#include <boost/asio.hpp>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <chrono>
#include <cstring>
#include <tcp/TCPWrapper.hpp>
#include <Log.h>

namespace aasdk
{
namespace tcp
{

void TCPWrapper::asyncWrite(boost::asio::ip::tcp::socket& socket, common::DataConstBuffer buffer, Handler handler)
{
    boost::asio::async_write(socket, boost::asio::buffer(buffer.cdata, buffer.size), std::move(handler));
}

void TCPWrapper::asyncRead(boost::asio::ip::tcp::socket& socket, common::DataBuffer buffer, Handler handler)
{
    socket.async_receive(boost::asio::buffer(buffer.data, buffer.size), std::move(handler));
}

void TCPWrapper::close(boost::asio::ip::tcp::socket& socket)
{
    if (Log::isDebug()) Log_d("close socket");
    boost::system::error_code ec;
    socket.shutdown(boost::asio::ip::tcp::socket::shutdown_both, ec);
    socket.close(ec);

    if (ec) {
        if (Log::isWarn()) Log_w("closing socket error %d (%s) ", ec.value(), ec.message().c_str());
    }

    if (Log::isInfo()) Log_i("socket closed");
}

void TCPWrapper::asyncConnect(boost::asio::ip::tcp::socket& socket, const std::string& hostname, uint16_t port, ConnectHandler handler)
{
    socket.async_connect(boost::asio::ip::tcp::endpoint(boost::asio::ip::address::from_string(hostname), port), std::move(handler));
}

boost::system::error_code TCPWrapper::assignFd(boost::asio::ip::tcp::socket& socket, int fd)
{
    if (fd < 0) {
        return boost::asio::error::bad_descriptor;
    }

    struct sockaddr_storage ss{};
    socklen_t len = sizeof(ss);
    const bool isV6 = (::getsockname(fd, reinterpret_cast<struct sockaddr*>(&ss), &len) == 0)
            && (ss.ss_family == AF_INET6);

    boost::system::error_code ec;
    if (socket.is_open()) {
        socket.close(ec);
        ec.clear();
    }

    socket.assign(isV6 ? boost::asio::ip::tcp::v6() : boost::asio::ip::tcp::v4(), fd, ec);
    if (ec) {
        if (!socket.is_open()) {
            ::close(fd);
        }
        return ec;
    }

    // Socket options must be applied only after a descriptor is assigned/connected.
    boost::system::error_code optEc;
    socket.set_option(boost::asio::ip::tcp::no_delay(true), optEc);
    socket.set_option(boost::asio::socket_base::keep_alive(true), optEc);
    return ec;
}

boost::system::error_code TCPWrapper::connect(boost::asio::ip::tcp::socket& socket, const std::string& hostname, uint16_t port)
{
    static constexpr int kConnectTimeoutMs = 2500;

    boost::system::error_code ec;
    const auto addr = boost::asio::ip::address::from_string(hostname, ec);
    if (ec) {
        return ec;
    }

    struct sockaddr_storage ss{};
    socklen_t ssLen = 0;
    const int family = addr.is_v6() ? AF_INET6 : AF_INET;
    if (addr.is_v6()) {
        auto* sa6 = reinterpret_cast<struct sockaddr_in6*>(&ss);
        const auto v6 = addr.to_v6();
        const auto bytes = v6.to_bytes();
        sa6->sin6_family = AF_INET6;
        sa6->sin6_port = htons(port);
        std::memcpy(&sa6->sin6_addr, bytes.data(), bytes.size());
        sa6->sin6_scope_id = static_cast<uint32_t>(v6.scope_id());
        ssLen = sizeof(struct sockaddr_in6);
    } else {
        auto* sa4 = reinterpret_cast<struct sockaddr_in*>(&ss);
        sa4->sin_family = AF_INET;
        sa4->sin_port = htons(port);
        if (::inet_pton(AF_INET, addr.to_v4().to_string().c_str(), &sa4->sin_addr) != 1) {
            return boost::asio::error::invalid_argument;
        }
        ssLen = sizeof(struct sockaddr_in);
    }

    int fd = ::socket(family, SOCK_STREAM, 0);
    if (fd < 0) {
        return boost::system::error_code(errno, boost::system::system_category());
    }

    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0 || ::fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        const int err = errno;
        ::close(fd);
        return boost::system::error_code(err, boost::system::system_category());
    }

    const int rc = ::connect(fd, reinterpret_cast<const struct sockaddr*>(&ss), ssLen);
    if (rc < 0) {
        if (errno != EINPROGRESS) {
            const int err = errno;
            ::close(fd);
            return boost::system::error_code(err, boost::system::system_category());
        }

        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kConnectTimeoutMs);
        for (;;) {
            const auto now = std::chrono::steady_clock::now();
            if (now >= deadline) {
                ::close(fd);
                return boost::asio::error::timed_out;
            }
            int remainingMs = static_cast<int>(
                    std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count());
            if (remainingMs <= 0) {
                remainingMs = 1;
            }

            struct pollfd pfd{};
            pfd.fd = fd;
            pfd.events = POLLOUT;
            const int pr = ::poll(&pfd, 1, remainingMs);
            if (pr < 0) {
                if (errno == EINTR) {
                    continue;
                }
                const int err = errno;
                ::close(fd);
                return boost::system::error_code(err, boost::system::system_category());
            }
            if (pr == 0) {
                ::close(fd);
                return boost::asio::error::timed_out;
            }

            int soError = 0;
            socklen_t len = sizeof(soError);
            if (::getsockopt(fd, SOL_SOCKET, SO_ERROR, &soError, &len) < 0) {
                const int err = errno;
                ::close(fd);
                return boost::system::error_code(err, boost::system::system_category());
            }
            if (soError != 0) {
                ::close(fd);
                return boost::system::error_code(soError, boost::system::system_category());
            }
            break;
        }
    }

    if (::fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) < 0) {
        const int err = errno;
        ::close(fd);
        return boost::system::error_code(err, boost::system::system_category());
    }

    return assignFd(socket, fd);
}

}
}
