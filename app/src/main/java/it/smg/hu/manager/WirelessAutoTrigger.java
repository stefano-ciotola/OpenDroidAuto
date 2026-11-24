package it.smg.hu.manager;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import it.smg.libs.common.Log;

/**
 * Triggers Wireless Android Auto (WAA) startup on the phone over Bluetooth RFCOMM
 * and Wi-Fi UDP so the phone initiates a direct TCP connection to the head unit.
 */
public class WirelessAutoTrigger {

    private static final String TAG = "WirelessAutoTrigger";

    public static final UUID WAA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    private static final String RFCOMM_SERVICE_NAME = "AndroidAuto";

    public static final int MSG_WIFI_START_REQUEST = 1;
    public static final int MSG_WIFI_INFO_REQUEST = 2;
    public static final int MSG_WIFI_INFO_RESPONSE = 3;
    public static final int MSG_WIFI_VERSION_REQUEST = 4;
    public static final int MSG_WIFI_VERSION_RESPONSE = 5;
    public static final int MSG_WIFI_CONNECT_STATUS = 6;
    public static final int MSG_WIFI_START_RESPONSE = 7;

    public static final int DEFAULT_UDP_PORT = 5277;
    public static final int SECURITY_WPA2_PERSONAL = 8;
    public static final int AP_TYPE_STATIC = 0;

    private static final int MAX_FRAME_PAYLOAD_BYTES = 16 * 1024;
    private static final int[] RETRY_DELAYS_MS = {0, 3000, 6000};
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static volatile BluetoothAdapter cachedAdapter_;

    private final AtomicInteger generation_ = new AtomicInteger(0);
    private final Object socketLock_ = new Object();
    private BluetoothServerSocket rfcommServerSocket_;
    private final Set<BluetoothSocket> activeRfcommSockets_ = new HashSet<>();

    /** Must be warmed on a Looper thread because API 15 BluetoothAdapter creates an internal Handler. */
    public static void warmUpBluetoothAdapter() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                cachedAdapter_ = adapter;
            }
        } catch (Throwable t) {
            if (Log.isDebug()) Log.d(TAG, "BluetoothAdapter warm-up unavailable: " + t.getMessage());
        }
    }

    private static BluetoothAdapter getBluetoothAdapterSafe() {
        BluetoothAdapter cached = cachedAdapter_;
        if (cached != null) {
            return cached;
        }
        if (Looper.myLooper() == null) {
            return null;
        }
        warmUpBluetoothAdapter();
        return cachedAdapter_;
    }

    public void trigger(final String localIp, final int serverPort, final String gatewayIp,
                        final String ssid, final String bssid) {
        if (localIp == null || localIp.trim().isEmpty() || serverPort <= 0 || serverPort > 65535) {
            return;
        }
        if (Looper.myLooper() != null) {
            warmUpBluetoothAdapter();
        }

        final int gen = generation_.incrementAndGet();
        closeSockets();

        final String cleanSsid = stripSsidQuotes(ssid);
        final byte[] startFrame = wrapRfcommFrame(MSG_WIFI_START_REQUEST,
                buildWifiStartRequestPayload(localIp, serverPort));
        final byte[] infoResponseFrame = (cleanSsid != null && !cleanSsid.isEmpty())
                ? wrapRfcommFrame(MSG_WIFI_INFO_RESPONSE,
                        buildWifiInfoResponsePayload(cleanSsid, "", normalizeBssid(bssid),
                                SECURITY_WPA2_PERSONAL, AP_TYPE_STATIC))
                : null;

        startRfcommServer(gen, startFrame, infoResponseFrame);

        Thread worker = new Thread(() -> {
            for (int i = 0; i < RETRY_DELAYS_MS.length; i++) {
                if (gen != generation_.get()) {
                    return;
                }
                if (RETRY_DELAYS_MS[i] > 0) {
                    SystemClock.sleep(RETRY_DELAYS_MS[i]);
                    if (gen != generation_.get()) {
                        return;
                    }
                }

                sendUdpTriggers(startFrame, gatewayIp, serverPort);
                if (tryRfcommClientTrigger(gen, startFrame, infoResponseFrame)) {
                    // Phone responded or accepted WAA control frame; keep server listener alive only briefly.
                    return;
                }
            }
        }, "ODA-WAA-trigger-" + gen);
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        generation_.incrementAndGet();
        closeSockets();
    }

    private void startRfcommServer(final int gen, final byte[] startFrame, final byte[] infoResponseFrame) {
        Thread serverThread = new Thread(() -> {
            BluetoothAdapter adapter = getBluetoothAdapterSafe();
            if (adapter == null || !isAdapterEnabled(adapter)) {
                return;
            }
            BluetoothServerSocket serverSocket = null;
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(RFCOMM_SERVICE_NAME, WAA_UUID);
                synchronized (socketLock_) {
                    if (gen != generation_.get()) {
                        return;
                    }
                    rfcommServerSocket_ = serverSocket;
                }
                while (gen == generation_.get()) {
                    BluetoothSocket client = null;
                    try {
                        client = serverSocket.accept(5000);
                        if (client == null) {
                            continue;
                        }
                        synchronized (socketLock_) {
                            if (gen != generation_.get()) {
                                closeQuietly(client);
                                break;
                            }
                            activeRfcommSockets_.add(client);
                        }
                        exchangeWaaControlFrames(gen, client, startFrame, infoResponseFrame);
                    } catch (Throwable t) {
                        if (gen == generation_.get() && Log.isDebug()) {
                            Log.d(TAG, "RFCOMM accept/exchange iteration: " + t.getMessage());
                        }
                        if (!isAdapterEnabled(adapter)) {
                            break;
                        }
                        SystemClock.sleep(250);
                    } finally {
                        synchronized (socketLock_) {
                            activeRfcommSockets_.remove(client);
                        }
                        closeQuietly(client);
                    }
                }
            } catch (Throwable t) {
                if (gen == generation_.get() && Log.isDebug()) {
                    Log.d(TAG, "RFCOMM listener ended: " + t.getMessage());
                }
            } finally {
                synchronized (socketLock_) {
                    if (rfcommServerSocket_ == serverSocket) {
                        rfcommServerSocket_ = null;
                    }
                }
                closeQuietly(serverSocket);
            }
        }, "ODA-WAA-rfcomm-srv-" + gen);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private boolean tryRfcommClientTrigger(int gen, byte[] startFrame, byte[] infoResponseFrame) {
        BluetoothAdapter adapter = getBluetoothAdapterSafe();
        if (adapter == null || !isAdapterEnabled(adapter)) {
            return false;
        }

        List<BluetoothDevice> candidates;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                return false;
            }
            candidates = prioritizePhoneDevices(bonded);
        } catch (Throwable t) {
            if (Log.isWarn()) Log.w(TAG, "Unable to query bonded BT devices: " + t.getMessage());
            return false;
        }

        for (BluetoothDevice device : candidates) {
            if (gen != generation_.get()) {
                return false;
            }
            if (connectAndSendRfcomm(gen, adapter, device, startFrame, infoResponseFrame)) {
                return true;
            }
        }
        return false;
    }

    private boolean connectAndSendRfcomm(int gen, BluetoothAdapter adapter, BluetoothDevice device,
                                         byte[] startFrame, byte[] infoResponseFrame) {
        BluetoothSocket socket = null;
        try {
            try {
                if (adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
            } catch (Throwable ignored) {
            }
            socket = device.createRfcommSocketToServiceRecord(WAA_UUID);
            synchronized (socketLock_) {
                if (gen != generation_.get()) {
                    closeQuietly(socket);
                    return false;
                }
                activeRfcommSockets_.add(socket);
            }
            socket.connect();
            return exchangeWaaControlFrames(gen, socket, startFrame, infoResponseFrame);
        } catch (Throwable t) {
            if (Log.isDebug()) {
                Log.d(TAG, "RFCOMM connect failed for " + safeDeviceAddress(device) + ": " + t.getMessage());
            }
            return false;
        } finally {
            synchronized (socketLock_) {
                activeRfcommSockets_.remove(socket);
            }
            closeQuietly(socket);
        }
    }

    private boolean exchangeWaaControlFrames(int gen, BluetoothSocket socket,
                                             byte[] startFrame, byte[] infoResponseFrame) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(startFrame);
        out.flush();
        if (Log.isInfo()) Log.i(TAG, "Sent WAA RFCOMM WifiStartRequest");

        boolean started = false;
        long deadline = SystemClock.uptimeMillis() + 2500L;
        byte[] header = new byte[4];
        while (gen == generation_.get() && SystemClock.uptimeMillis() < deadline) {
            if (!readFullyWithDeadline(gen, in, header, 4, deadline)) {
                break;
            }
            int len = readMessageLength(header);
            int msgId = readMessageId(header);
            if (len < 0 || len > MAX_FRAME_PAYLOAD_BYTES) {
                return started;
            }
            byte[] payload = new byte[len];
            if (len > 0 && !readFullyWithDeadline(gen, in, payload, len, deadline)) {
                break;
            }
            if (Log.isDebug()) Log.d(TAG, "Received WAA RFCOMM msgId=" + msgId + " len=" + len);
            if (msgId == MSG_WIFI_INFO_REQUEST && infoResponseFrame != null) {
                out.write(infoResponseFrame);
                out.flush();
                if (Log.isInfo()) Log.i(TAG, "Sent WAA RFCOMM WifiInfoResponse");
                deadline = SystemClock.uptimeMillis() + 2500L;
            } else if (msgId == MSG_WIFI_START_RESPONSE) {
                started = true;
                deadline = SystemClock.uptimeMillis() + 2500L;
            } else if (msgId == MSG_WIFI_CONNECT_STATUS) {
                return true;
            } else {
                deadline = SystemClock.uptimeMillis() + 1500L;
            }
        }
        return started;
    }

    private void sendUdpTriggers(byte[] frame, String gatewayIp, int serverPort) {
        DatagramSocket udp = null;
        try {
            udp = new DatagramSocket();
            udp.setBroadcast(true);
            sendUdpTarget(udp, frame, gatewayIp, DEFAULT_UDP_PORT);
            sendUdpTarget(udp, frame, gatewayIp, serverPort);
            sendUdpTarget(udp, frame, "255.255.255.255", serverPort);
        } catch (Throwable t) {
            if (Log.isDebug()) Log.d(TAG, "UDP WAA trigger failed: " + t.getMessage());
        } finally {
            if (udp != null) {
                try {
                    udp.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void sendUdpTarget(DatagramSocket udp, byte[] payload, String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0) {
            return;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            DatagramPacket packet = new DatagramPacket(payload, payload.length, addr, port);
            udp.send(packet);
        } catch (Throwable ignored) {
        }
    }

    private static List<BluetoothDevice> prioritizePhoneDevices(Set<BluetoothDevice> bonded) {
        List<BluetoothDevice> phones = new ArrayList<>();
        List<BluetoothDevice> others = new ArrayList<>();
        for (BluetoothDevice device : bonded) {
            if (device == null) {
                continue;
            }
            BluetoothClass btClass = device.getBluetoothClass();
            if (btClass != null && btClass.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) {
                phones.add(device);
            } else if (btClass == null || btClass.getMajorDeviceClass() == BluetoothClass.Device.Major.UNCATEGORIZED) {
                others.add(device);
            }
        }
        if (!phones.isEmpty()) {
            phones.addAll(others);
            return phones;
        }
        // Fall back to remaining bonded devices only if no phone major class is declared.
        for (BluetoothDevice device : bonded) {
            if (device != null && !others.contains(device)) {
                others.add(device);
            }
        }
        return others;
    }

    private static boolean isAdapterEnabled(BluetoothAdapter adapter) {
        try {
            return adapter.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String safeDeviceAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void closeSockets() {
        BluetoothServerSocket server;
        List<BluetoothSocket> clients;
        synchronized (socketLock_) {
            server = rfcommServerSocket_;
            rfcommServerSocket_ = null;
            clients = new ArrayList<>(activeRfcommSockets_);
            activeRfcommSockets_.clear();
        }
        for (BluetoothSocket client : clients) {
            closeQuietly(client);
        }
        closeQuietly(server);
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(BluetoothServerSocket serverSocket) {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // Poll available() since API 15 BluetoothSocket lacks SO_RCVTIMEO and a stalled peer blocks read() past deadline.
    private boolean readFullyWithDeadline(int gen, InputStream in, byte[] dst, int length, long deadline) throws IOException {
        int read = 0;
        while (read < length) {
            if (gen != generation_.get() || SystemClock.uptimeMillis() >= deadline) {
                return false;
            }
            int avail = in.available();
            if (avail > 0) {
                int toRead = Math.min(length - read, avail);
                int n = in.read(dst, read, toRead);
                if (n < 0) {
                    return false;
                }
                read += n;
            } else {
                SystemClock.sleep(50);
            }
        }
        return true;
    }

    static String normalizeBssid(String bssid) {
        return bssid == null ? "" : bssid.trim().toUpperCase(Locale.US);
    }

    public static byte[] buildWifiStartRequestPayload(String ipAddress, int port) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("ipAddress must not be empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        writeStringField(out, 1, ipAddress.trim());
        writeInt32Field(out, 2, port);
        return out.toByteArray();
    }

    public static byte[] buildWifiInfoResponsePayload(String ssid, String key, String bssid,
                                                      int securityMode, int apType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeRequiredStringField(out, 1, ssid);
        writeRequiredStringField(out, 2, key);
        writeRequiredStringField(out, 3, bssid);
        writeInt32Field(out, 4, securityMode);
        writeInt32Field(out, 5, apType);
        return out.toByteArray();
    }

    public static byte[] wrapRfcommFrame(int messageId, byte[] payload) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        int len = safePayload.length;
        byte[] frame = new byte[4 + len];
        frame[0] = (byte) ((len >> 8) & 0xff);
        frame[1] = (byte) (len & 0xff);
        frame[2] = (byte) ((messageId >> 8) & 0xff);
        frame[3] = (byte) (messageId & 0xff);
        System.arraycopy(safePayload, 0, frame, 4, len);
        return frame;
    }

    public static int readMessageLength(byte[] header) {
        if (header == null || header.length < 4) {
            return -1;
        }
        return ((header[0] & 0xff) << 8) | (header[1] & 0xff);
    }

    public static int readMessageId(byte[] header) {
        if (header == null || header.length < 4) {
            return -1;
        }
        return ((header[2] & 0xff) << 8) | (header[3] & 0xff);
    }

    public static String stripSsidQuotes(String ssid) {
        if (ssid == null) {
            return "";
        }
        String trimmed = ssid.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static String formatIpv4Address(int ipv4) {
        if (ipv4 == 0) {
            return null;
        }
        return (ipv4 & 0xff) + "."
                + ((ipv4 >> 8) & 0xff) + "."
                + ((ipv4 >> 16) & 0xff) + "."
                + ((ipv4 >> 24) & 0xff);
    }

    public static void writeRequiredStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        String safe = value != null ? value : "";
        byte[] utf8 = safe.getBytes(UTF_8);
        writeVarint(out, (fieldNumber << 3) | 2);
        writeVarint(out, utf8.length);
        out.write(utf8, 0, utf8.length);
    }

    public static void writeStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        byte[] utf8 = value.getBytes(UTF_8);
        writeVarint(out, (fieldNumber << 3) | 2);
        writeVarint(out, utf8.length);
        out.write(utf8, 0, utf8.length);
    }

    public static void writeInt32Field(ByteArrayOutputStream out, int fieldNumber, int value) {
        writeVarint(out, (fieldNumber << 3));
        writeVarint(out, value);
    }

    public static void writeVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }
}
