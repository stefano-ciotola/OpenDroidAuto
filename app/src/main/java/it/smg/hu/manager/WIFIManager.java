package it.smg.hu.manager;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import it.smg.hu.R;
import it.smg.libs.common.Log;
import it.smg.hu.config.Settings;
import it.smg.hu.service.ODAService;
import it.smg.libs.aasdk.tcp.TCPEndpoint;

public class WIFIManager {

    private static final String TAG = "WiFiConnectionManager";
    public static final int DEFAULT_SERVER_PORT = 5288;
    public static final String ACTION_WIFI_INCOMING_CONNECTION = "it.smg.hu.WIFI_INCOMING_CONNECTION";
    public static final String EXTRA_LOCAL_IP = "localIp";
    public static final String EXTRA_PORT = "port";

    private volatile String localIpAddress_;
    private volatile String networkSsid_;
    private volatile String bssid_;
    private volatile int serverFd_ = -1;
    private volatile int acceptedSocketFd_ = -1;
    private volatile boolean serverRunning_ = false;
    private volatile boolean sessionActive_ = false;
    private boolean wasConnected_ = false;
    private String lastTriggerKey_;
    private Thread acceptThread_;
    private final WirelessAutoTrigger autoTrigger_ = new WirelessAutoTrigger();

    public static final String DISCONNECT_WIFI = "it.smg.hu.DISCONNECT_WIFI";
    public static final String CONNECT_WIFI = "it.smg.hu.CONNECT_WIFI";
    public static final String EXTRA_SSID = "ssid";

    private static WIFIManager instance_;
    private final LocalBroadcastManager localBroadcastManager_;

    private final Context ctx_;
    private volatile String ipAddress_;

    private Thread serverThread;

    private WIFIManager(Context ctx){
        ctx_ = ctx;
        localBroadcastManager_ = LocalBroadcastManager.getInstance(ctx_);
        WirelessAutoTrigger.warmUpBluetoothAdapter();
    }

    public static void init(Context ctx){
        if (instance_ == null){
            instance_ = new WIFIManager(ctx);
        }
    }

    public static WIFIManager instance() {
        return instance_;
    }

    public String getIpAddress(){
        return ipAddress_;
    }

    public String getLocalIpAddress() {
        return localIpAddress_;
    }

    public String getNetworkSsid() {
        return networkSsid_;
    }

    public int getServerPort() {
        return DEFAULT_SERVER_PORT;
    }

    public synchronized int consumeAcceptedSocketFd() {
        int fd = acceptedSocketFd_;
        acceptedSocketFd_ = -1;
        return fd;
    }

    public synchronized boolean hasPendingAcceptedSocket() {
        if (acceptedSocketFd_ < 0) {
            return false;
        }
        if (!TCPEndpoint.isSocketAlive(acceptedSocketFd_)) {
            TCPEndpoint.closeFd(acceptedSocketFd_);
            acceptedSocketFd_ = -1;
            return false;
        }
        return true;
    }

    public void setSessionActive(boolean active) {
        sessionActive_ = active;
        if (active) {
            autoTrigger_.stop();
        }
    }

    public void triggerWirelessStart() {
        String localIp;
        String gatewayIp;
        String ssid;
        String bssid;
        if (!Settings.instance().advanced.enableWiFi() || !isConnected()) {
            return;
        }
        synchronized (this) {
            if (localIpAddress_ == null || isUsbPriorityActive() || sessionActive_) {
                return;
            }
            localIp = localIpAddress_;
            gatewayIp = ipAddress_;
            ssid = currentSsidSafe();
            bssid = bssid_;
        }
        startServerListener();
        if (isUsbPriorityActive() || sessionActive_) {
            return;
        }
        autoTrigger_.trigger(localIp, DEFAULT_SERVER_PORT, gatewayIp, ssid, bssid);
    }

    private String currentSsidSafe() {
        WifiManager wifi = (WifiManager) ctx_.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return "";
        }
        WifiInfo info = wifi.getConnectionInfo();
        String raw = info != null ? info.getSSID() : null;
        String stripped = WirelessAutoTrigger.stripSsidQuotes(raw);
        if ("<unknown ssid>".equalsIgnoreCase(stripped)) {
            return "";
        }
        return stripped;
    }

    private boolean isConnected(){
        ConnectivityManager connManager = (ConnectivityManager) ctx_.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connManager == null) {
            return false;
        }
        NetworkInfo wifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifi != null && wifi.isConnected();
    }

    public void checkNetwork(){
        String triggerLocalIp = null;
        String triggerGatewayIp = null;
        String triggerSsid = null;
        String triggerBssid = null;
        boolean needStartServer = false;
        boolean needStopServer = false;
        boolean broadcastDisconnected = false;

        synchronized (this) {
            if (!Settings.instance().advanced.enableWiFi()) {
                needStopServer = (serverRunning_ || serverFd_ >= 0 || acceptedSocketFd_ >= 0);
                clearNetworkStateLocked();
                if (wasConnected_) {
                    wasConnected_ = false;
                    broadcastDisconnected = true;
                }
            } else if (isConnected()) {
                WifiManager wifi = (WifiManager) ctx_.getSystemService(Context.WIFI_SERVICE);
                DhcpInfo d = wifi != null ? wifi.getDhcpInfo() : null;
                WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;

                ipAddress_ = d != null ? WirelessAutoTrigger.formatIpv4Address(d.gateway) : null;
                localIpAddress_ = resolveLocalIpv4(d, info);
                String currentSsid = currentSsidSafe();
                networkSsid_ = currentSsid;
                bssid_ = info != null ? info.getBSSID() : null;

                if (ipAddress_ == null && localIpAddress_ == null) {
                    if (!isUsbPriorityActive()) {
                        ConnectionManager.instance().failed(ODAService.MODE_WIFI, ctx_.getString(R.string.connection_hotspot_gateway_error));
                    }
                    return;
                }
                if (Log.isDebug()) {
                    Log.d(TAG, "Network gateway ip: " + ipAddress_ + ", local ip: " + localIpAddress_ + ", SSID: " + currentSsid);
                }

                wasConnected_ = true;
                boolean usbPriority = isUsbPriorityActive();
                if (localIpAddress_ != null) {
                    needStartServer = true;
                    if (!usbPriority && !sessionActive_) {
                        String triggerKey = localIpAddress_ + "|" + currentSsid + "|" + String.valueOf(ipAddress_);
                        if (!triggerKey.equals(lastTriggerKey_)) {
                            lastTriggerKey_ = triggerKey;
                            triggerLocalIp = localIpAddress_;
                            triggerGatewayIp = ipAddress_;
                            triggerSsid = currentSsid;
                            triggerBssid = bssid_;
                        }
                    } else if (usbPriority) {
                        lastTriggerKey_ = null;
                    }
                }

                String readyLabel = !currentSsid.isEmpty()
                        ? currentSsid
                        : (localIpAddress_ != null ? localIpAddress_ : ipAddress_);
                ConnectionManager.instance().transportAvailable(ODAService.MODE_WIFI,
                        ctx_.getString(R.string.connection_hotspot_ready, readyLabel));
                Intent connectWifiIntent = new Intent(CONNECT_WIFI);
                connectWifiIntent.putExtra(EXTRA_SSID, currentSsid);
                connectWifiIntent.putExtra(EXTRA_LOCAL_IP, localIpAddress_);
                connectWifiIntent.putExtra(EXTRA_PORT, DEFAULT_SERVER_PORT);
                localBroadcastManager_.sendBroadcast(connectWifiIntent);
                // Fall through outside synchronized block to start server / WAA trigger.
            } else {
                if (Log.isDebug()) Log.d(TAG, "isConnected: false");
                needStopServer = (serverRunning_ || serverFd_ >= 0 || acceptedSocketFd_ >= 0);
                clearNetworkStateLocked();
                if (wasConnected_) {
                    wasConnected_ = false;
                    broadcastDisconnected = true;
                }
            }
        }

        if (needStopServer) {
            stopServerListener();
        }
        if (broadcastDisconnected) {
            Intent disconnectWifiIntent = new Intent(DISCONNECT_WIFI);
            localBroadcastManager_.sendBroadcast(disconnectWifiIntent);
            ConnectionManager.instance().detached(ODAService.MODE_WIFI,
                    ctx_.getString(R.string.connection_hotspot_disconnected));
            return;
        }
        if (needStartServer) {
            startServerListener();
        }
        if (triggerLocalIp != null && !isUsbPriorityActive() && !sessionActive_) {
            autoTrigger_.trigger(triggerLocalIp, DEFAULT_SERVER_PORT, triggerGatewayIp, triggerSsid, triggerBssid);
        }
    }

    private void clearNetworkStateLocked() {
        ipAddress_ = null;
        localIpAddress_ = null;
        networkSsid_ = null;
        bssid_ = null;
        lastTriggerKey_ = null;
    }

    private boolean isUsbPriorityActive() {
        USBManager usb = USBManager.instance();
        if (usb != null && usb.aoapDevice() != null) {
            return true;
        }
        ConnectionManager cm = ConnectionManager.instance();
        if (cm != null && ODAService.MODE_USB.equals(cm.mode())) {
            ConnectionState state = cm.state();
            return state == ConnectionState.PERMISSION_PENDING
                    || state == ConnectionState.AOAP_SWITCHING
                    || state == ConnectionState.CONNECTING
                    || state == ConnectionState.ACTIVE
                    || state == ConnectionState.DISCONNECTING
                    || state == ConnectionState.ERROR;
        }
        return false;
    }

    private void startServerListener() {
        Thread threadToJoin;
        synchronized (this) {
            if (serverRunning_ && serverFd_ >= 0) {
                return;
            }
            threadToJoin = acceptThread_;
        }
        joinAcceptThreadQuietly(threadToJoin);

        synchronized (this) {
            if (serverRunning_ && serverFd_ >= 0) {
                return;
            }
            int fd = TCPEndpoint.openServerSocket(DEFAULT_SERVER_PORT);
            if (fd < 0) {
                if (Log.isWarn()) Log.w(TAG, "Unable to open WAA TCP server on port " + DEFAULT_SERVER_PORT);
                return;
            }
            serverFd_ = fd;
            serverRunning_ = true;
            final int boundFd = fd;
            Thread thread = new Thread(() -> runAcceptLoop(boundFd), "ODA-WiFi-Server");
            thread.setDaemon(true);
            acceptThread_ = thread;
            thread.start();
        }
    }

    private void stopServerListener() {
        int fdToShutdown;
        Thread threadToJoin;
        synchronized (this) {
            serverRunning_ = false;
            fdToShutdown = serverFd_;
            serverFd_ = -1;
            if (acceptedSocketFd_ >= 0) {
                TCPEndpoint.closeFd(acceptedSocketFd_);
                acceptedSocketFd_ = -1;
            }
            threadToJoin = acceptThread_;
            autoTrigger_.stop();
        }
        if (fdToShutdown >= 0) {
            TCPEndpoint.shutdownFd(fdToShutdown);
        }
        joinAcceptThreadQuietly(threadToJoin);
    }

    private static void joinAcceptThreadQuietly(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runAcceptLoop(final int boundFd) {
        if (Log.isInfo()) Log.i(TAG, "Listening for WAA TCP connections on port " + DEFAULT_SERVER_PORT);
        try {
            while (serverRunning_ && serverFd_ == boundFd) {
                int clientFd = TCPEndpoint.acceptClient(boundFd, 1000);
                if (clientFd == TCPEndpoint.ACCEPT_TIMEOUT) {
                    continue;
                }
                if (clientFd < 0) {
                    break;
                }

                boolean reject = isUsbPriorityActive() || sessionActive_;
                if (reject) {
                    if (Log.isInfo()) Log.i(TAG, "Closing incoming Wi-Fi connection while another session/USB is active");
                    TCPEndpoint.closeFd(clientFd);
                    continue;
                }

                String localIp;
                synchronized (this) {
                    if (!serverRunning_ || serverFd_ != boundFd) {
                        TCPEndpoint.closeFd(clientFd);
                        break;
                    }
                    if (acceptedSocketFd_ >= 0) {
                        TCPEndpoint.closeFd(acceptedSocketFd_);
                    }
                    acceptedSocketFd_ = clientFd;
                    localIp = localIpAddress_;
                }

                autoTrigger_.stop();
                if (Log.isInfo()) Log.i(TAG, "Accepted incoming WAA TCP client on port " + DEFAULT_SERVER_PORT);
                Intent incomingIntent = new Intent(ACTION_WIFI_INCOMING_CONNECTION);
                incomingIntent.putExtra(EXTRA_LOCAL_IP, localIp);
                incomingIntent.putExtra(EXTRA_PORT, DEFAULT_SERVER_PORT);
                localBroadcastManager_.sendBroadcast(incomingIntent);
            }
        } finally {
            TCPEndpoint.closeFd(boundFd);
            synchronized (this) {
                if (acceptThread_ == Thread.currentThread()) {
                    acceptThread_ = null;
                }
                if (serverFd_ == boundFd) {
                    serverFd_ = -1;
                    serverRunning_ = false;
                }
            }
        }
    }

    private static String resolveLocalIpv4(DhcpInfo d, WifiInfo info) {
        if (d != null && d.ipAddress != 0) {
            return WirelessAutoTrigger.formatIpv4Address(d.ipAddress);
        }
        if (info != null && info.getIpAddress() != 0) {
            return WirelessAutoTrigger.formatIpv4Address(info.getIpAddress());
        }
        try {
            String fallback = null;
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface == null || iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (iface.getName() != null && iface.getName().startsWith("wlan")) {
                            return host;
                        }
                        if (fallback == null) {
                            fallback = host;
                        }
                    }
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
