package it.smg.hu.manager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.Charset;

public class WirelessAutoTriggerTest {

    @Test
    public void buildWifiStartRequestPayloadEncodesIpAndPortAsExpected() {
        byte[] payload = WirelessAutoTrigger.buildWifiStartRequestPayload("192.168.43.100", 5288);
        byte[] ipBytes = "192.168.43.100".getBytes(Charset.forName("UTF-8"));

        byte[] expected = new byte[2 + ipBytes.length + 3];
        expected[0] = 0x0A; // field 1, wire type 2 (length-delimited)
        expected[1] = (byte) ipBytes.length;
        System.arraycopy(ipBytes, 0, expected, 2, ipBytes.length);
        expected[2 + ipBytes.length] = 0x10; // field 2, wire type 0 (varint)
        expected[3 + ipBytes.length] = (byte) 0xA8; // 5288 varint byte 0
        expected[4 + ipBytes.length] = 0x29; // 5288 varint byte 1

        assertArrayEquals(expected, payload);
    }

    @Test
    public void wrapRfcommFrameAddsFourByteBigEndianHeader() {
        byte[] payload = WirelessAutoTrigger.buildWifiStartRequestPayload("192.168.43.100", 5288);
        byte[] frame = WirelessAutoTrigger.wrapRfcommFrame(WirelessAutoTrigger.MSG_WIFI_START_REQUEST, payload);

        assertEquals(4 + payload.length, frame.length);
        assertEquals(payload.length, WirelessAutoTrigger.readMessageLength(frame));
        assertEquals(WirelessAutoTrigger.MSG_WIFI_START_REQUEST, WirelessAutoTrigger.readMessageId(frame));
        assertEquals(0x00, frame[0] & 0xff);
        assertEquals(payload.length, frame[1] & 0xff);
        assertEquals(0x00, frame[2] & 0xff);
        assertEquals(0x01, frame[3] & 0xff);
    }

    @Test
    public void buildWifiStartRequestRejectsInvalidInputs() {
        try {
            WirelessAutoTrigger.buildWifiStartRequestPayload("", 5288);
            fail("Expected IllegalArgumentException for empty IP");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ipAddress"));
        }

        try {
            WirelessAutoTrigger.buildWifiStartRequestPayload("192.168.43.100", 0);
            fail("Expected IllegalArgumentException for port 0");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("port"));
        }

        try {
            WirelessAutoTrigger.buildWifiStartRequestPayload("192.168.43.100", 70000);
            fail("Expected IllegalArgumentException for port > 65535");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("port"));
        }
    }

    @Test
    public void buildWifiInfoResponsePayloadIncludesSsidBssidAndSecurity() {
        byte[] payload = WirelessAutoTrigger.buildWifiInfoResponsePayload(
                "MyPhoneAP", "", "aa:bb:cc:dd:ee:ff",
                WirelessAutoTrigger.SECURITY_WPA2_PERSONAL,
                WirelessAutoTrigger.AP_TYPE_STATIC);
        byte[] frame = WirelessAutoTrigger.wrapRfcommFrame(WirelessAutoTrigger.MSG_WIFI_INFO_RESPONSE, payload);

        assertEquals(WirelessAutoTrigger.MSG_WIFI_INFO_RESPONSE, WirelessAutoTrigger.readMessageId(frame));
        assertEquals(payload.length, WirelessAutoTrigger.readMessageLength(frame));
        // Starts with field 1 tag (0x0A) + len("MyPhoneAP") == 9
        assertEquals(0x0A, payload[0] & 0xff);
        assertEquals(9, payload[1] & 0xff);
        // Field 2 (key) must still be emitted as empty string (0x12, 0x00) for strict proto2 parsers
        int field2Offset = 2 + 9;
        assertEquals(0x12, payload[field2Offset] & 0xff);
        assertEquals(0x00, payload[field2Offset + 1] & 0xff);
        // Field 3 (bssid) follows immediately with len 17
        int field3Offset = field2Offset + 2;
        assertEquals(0x1A, payload[field3Offset] & 0xff);
        assertEquals(17, payload[field3Offset + 1] & 0xff);
    }

    @Test
    public void stripSsidQuotesAndFormatIpv4HandleAndroidFormats() {
        assertEquals("CarHotspot", WirelessAutoTrigger.stripSsidQuotes("\"CarHotspot\""));
        assertEquals("CarHotspot", WirelessAutoTrigger.stripSsidQuotes("CarHotspot"));
        assertEquals("", WirelessAutoTrigger.stripSsidQuotes(null));

        assertNull(WirelessAutoTrigger.formatIpv4Address(0));
        // Android DhcpInfo stores IPv4 in little-endian int order: 100.43.168.192 -> 192.168.43.100
        int leIp = (192) | (168 << 8) | (43 << 16) | (100 << 24);
        assertEquals("192.168.43.100", WirelessAutoTrigger.formatIpv4Address(leIp));
    }

    @Test
    public void normalizeBssidHandlesNullAndLowercase() {
        assertEquals("", WirelessAutoTrigger.normalizeBssid(null));
        assertEquals("AA:BB:CC:DD:EE:FF", WirelessAutoTrigger.normalizeBssid(" aa:bb:cc:dd:ee:ff "));
    }
}
