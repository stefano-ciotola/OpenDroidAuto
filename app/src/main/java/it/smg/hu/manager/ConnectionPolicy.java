package it.smg.hu.manager;

/** Pure connection policy so manual start and exit behavior can be tested without Android hardware. */
public final class ConnectionPolicy {
    private ConnectionState state = ConnectionState.IDLE;
    private String mode;

    public ConnectionState state() {
        return state;
    }

    public String mode() {
        return mode;
    }

    public boolean isManualStartAllowed() {
        // A start request during permission, AOAP negotiation or native
        // teardown creates an overlapping session.  Only a prepared transport
        // (IDLE) or a deliberately stopped, still-prepared transport (EXITED)
        // may launch PlayerActivity.
        return state == ConnectionState.IDLE || state == ConnectionState.EXITED;
    }

    public boolean transportAvailable(String connectionMode) {
        if (state == ConnectionState.PERMISSION_PENDING
                || state == ConnectionState.AOAP_SWITCHING
                || state == ConnectionState.CONNECTING
                || state == ConnectionState.ACTIVE
                || state == ConnectionState.DISCONNECTING) {
            return false;
        }
        if (state == ConnectionState.ERROR) {
            // Preserve USB errors so RECUPERAR USB stays actionable; let a plugged-in USB phone clear a Wi-Fi error.
            if (!("modeUSB".equals(connectionMode) && !"modeUSB".equals(mode))) {
                return false;
            }
        }
        // USB stays authoritative while attached so Wi-Fi broadcasts cannot preempt wired AA.
        if ("modeWifi".equals(connectionMode) && "modeUSB".equals(mode)) {
            return false;
        }
        mode = connectionMode;
        state = ConnectionState.IDLE;
        return true;
    }

    public boolean usbReady() {
        if (state == ConnectionState.CONNECTING
                || state == ConnectionState.ACTIVE
                || state == ConnectionState.DISCONNECTING) {
            return false;
        }
        mode = "modeUSB";
        state = ConnectionState.IDLE;
        return true;
    }

    public boolean prepareRetry(String connectionMode) {
        if (state != ConnectionState.ERROR) {
            return false;
        }
        if (connectionMode != null && !connectionMode.isEmpty()) {
            mode = connectionMode;
        }
        state = ConnectionState.IDLE;
        return true;
    }

    public void permissionPending() {
        mode = "modeUSB";
        state = ConnectionState.PERMISSION_PENDING;
    }

    public void switchingToAoap() {
        mode = "modeUSB";
        state = ConnectionState.AOAP_SWITCHING;
    }

    public void connecting(String connectionMode) {
        if (connectionMode != null) {
            mode = connectionMode;
        }
        state = ConnectionState.CONNECTING;
    }

    public void active() {
        state = ConnectionState.ACTIVE;
    }

    public void disconnecting() {
        state = ConnectionState.DISCONNECTING;
    }

    public void userExited() {
        state = ConnectionState.EXITED;
    }

    public void detached() {
        mode = null;
        state = ConnectionState.IDLE;
    }

    public void failed() {
        state = ConnectionState.ERROR;
    }

    public void failed(String connectionMode) {
        if (connectionMode != null && !connectionMode.isEmpty()) {
            mode = connectionMode;
        }
        state = ConnectionState.ERROR;
    }
}
