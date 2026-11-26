package it.smg.hu.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConnectionPolicyTest {
    @Test
    public void errorsKeepTheConnectionModeForManualRetry() {
        ConnectionPolicy policy = new ConnectionPolicy();
        policy.transportAvailable("modeUSB");

        policy.failed();
        assertEquals("modeUSB", policy.mode());
        assertEquals(ConnectionState.ERROR, policy.state());
    }

    @Test
    public void userExitKeepsManualRestartAvailable() {
        ConnectionPolicy policy = new ConnectionPolicy();
        policy.transportAvailable("modeUSB");
        policy.userExited();

        assertTrue(policy.isManualStartAllowed());
        policy.failed();

        policy.userExited();
        assertTrue(policy.isManualStartAllowed());
        assertEquals(ConnectionState.EXITED, policy.state());

        policy.detached();
        assertTrue(policy.isManualStartAllowed());
        assertEquals(ConnectionState.IDLE, policy.state());
    }

    @Test
    public void startIsBlockedWhileTransportIsPreparingOrConnecting() {
        ConnectionPolicy policy = new ConnectionPolicy();

        policy.permissionPending();
        assertFalse(policy.isManualStartAllowed());
        policy.switchingToAoap();
        assertFalse(policy.isManualStartAllowed());
        policy.connecting("modeUSB");
        assertFalse(policy.isManualStartAllowed());
        policy.disconnecting();
        assertFalse(policy.isManualStartAllowed());
        policy.failed();
        assertFalse(policy.isManualStartAllowed());
    }

    @Test
    public void passiveTransportAvailableDoesNotClobberInFlightOrErrorStates() {
        ConnectionPolicy policy = new ConnectionPolicy();
        policy.transportAvailable("modeUSB");

        policy.permissionPending();
        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals(ConnectionState.PERMISSION_PENDING, policy.state());
        assertEquals("modeUSB", policy.mode());

        policy.switchingToAoap();
        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals(ConnectionState.AOAP_SWITCHING, policy.state());
        assertEquals("modeUSB", policy.mode());

        policy.connecting("modeUSB");
        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals(ConnectionState.CONNECTING, policy.state());
        assertEquals("modeUSB", policy.mode());

        policy.active();
        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals(ConnectionState.ACTIVE, policy.state());
        assertEquals("modeUSB", policy.mode());

        policy.disconnecting();
        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals(ConnectionState.DISCONNECTING, policy.state());
        assertEquals("modeUSB", policy.mode());

        policy.failed();
        assertFalse(policy.transportAvailable("modeWifi"));
        assertFalse(policy.transportAvailable("modeUSB"));
        assertEquals(ConnectionState.ERROR, policy.state());
        assertEquals("modeUSB", policy.mode());
    }

    @Test
    public void usbTakesPrecedenceOverPassiveWifiUntilDetached() {
        ConnectionPolicy policy = new ConnectionPolicy();
        assertTrue(policy.transportAvailable("modeUSB"));
        assertEquals("modeUSB", policy.mode());

        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals("modeUSB", policy.mode());
        assertEquals(ConnectionState.IDLE, policy.state());

        policy.userExited();
        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals("modeUSB", policy.mode());
        assertEquals(ConnectionState.EXITED, policy.state());

        policy.detached();
        assertEquals(null, policy.mode());
        assertTrue(policy.transportAvailable("modeWifi"));
        assertEquals("modeWifi", policy.mode());
        assertEquals(ConnectionState.IDLE, policy.state());
    }

    @Test
    public void prepareRetryTransitionsErrorToIdleOnly() {
        ConnectionPolicy policy = new ConnectionPolicy();
        assertFalse(policy.prepareRetry("modeWifi"));

        policy.transportAvailable("modeWifi");
        policy.connecting("modeWifi");
        assertFalse(policy.prepareRetry("modeWifi"));

        policy.failed();
        assertFalse(policy.isManualStartAllowed());
        assertTrue(policy.prepareRetry("modeWifi"));
        assertEquals(ConnectionState.IDLE, policy.state());
        assertEquals("modeWifi", policy.mode());
        assertTrue(policy.isManualStartAllowed());
    }

    @Test
    public void usbPreemptsWifiErrorWithoutClearingUsbError() {
        ConnectionPolicy policy = new ConnectionPolicy();
        assertTrue(policy.transportAvailable("modeWifi"));
        policy.connecting("modeWifi");
        policy.failed();

        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals(ConnectionState.ERROR, policy.state());
        assertEquals("modeWifi", policy.mode());

        assertTrue(policy.transportAvailable("modeUSB"));
        assertEquals(ConnectionState.IDLE, policy.state());
        assertEquals("modeUSB", policy.mode());
        assertTrue(policy.isManualStartAllowed());

        policy.failed();
        assertFalse(policy.transportAvailable("modeUSB"));
        assertFalse(policy.transportAvailable("modeWifi"));
        assertEquals(ConnectionState.ERROR, policy.state());
        assertEquals("modeUSB", policy.mode());
    }

    @Test
    public void usbReadyTransitionsPermissionPendingAndAoapSwitchingToIdle() {
        ConnectionPolicy policy = new ConnectionPolicy();
        policy.permissionPending();
        assertEquals("modeUSB", policy.mode());
        assertEquals(ConnectionState.PERMISSION_PENDING, policy.state());
        assertFalse(policy.isManualStartAllowed());

        assertTrue(policy.usbReady());
        assertEquals(ConnectionState.IDLE, policy.state());
        assertEquals("modeUSB", policy.mode());
        assertTrue(policy.isManualStartAllowed());

        policy.switchingToAoap();
        assertEquals("modeUSB", policy.mode());
        assertEquals(ConnectionState.AOAP_SWITCHING, policy.state());
        assertFalse(policy.isManualStartAllowed());

        assertTrue(policy.usbReady());
        assertEquals(ConnectionState.IDLE, policy.state());
        assertEquals("modeUSB", policy.mode());
        assertTrue(policy.isManualStartAllowed());

        policy.connecting("modeUSB");
        assertFalse(policy.usbReady());
        assertEquals(ConnectionState.CONNECTING, policy.state());

        policy.active();
        assertFalse(policy.usbReady());
        assertEquals(ConnectionState.ACTIVE, policy.state());

        policy.disconnecting();
        assertFalse(policy.usbReady());
        assertEquals(ConnectionState.DISCONNECTING, policy.state());
    }

    @Test
    public void explicitModeFailureUpdatesModeAndRecoveryCompletesToIdle() {
        ConnectionPolicy policy = new ConnectionPolicy();
        assertTrue(policy.transportAvailable("modeWifi"));
        policy.connecting("modeWifi");
        policy.failed("modeWifi");
        assertEquals(ConnectionState.ERROR, policy.state());
        assertEquals("modeWifi", policy.mode());

        policy.failed("modeUSB");
        assertEquals(ConnectionState.ERROR, policy.state());
        assertEquals("modeUSB", policy.mode());
        assertFalse(policy.transportAvailable("modeWifi"));
        assertFalse(policy.transportAvailable("modeUSB"));

        policy.switchingToAoap();
        assertEquals(ConnectionState.AOAP_SWITCHING, policy.state());
        assertEquals("modeUSB", policy.mode());
        assertTrue(policy.usbReady());
        assertEquals(ConnectionState.IDLE, policy.state());
        assertEquals("modeUSB", policy.mode());
        assertTrue(policy.isManualStartAllowed());
    }
}
