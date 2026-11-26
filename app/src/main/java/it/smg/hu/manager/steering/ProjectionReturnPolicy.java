package it.smg.hu.manager.steering;

/**
 * Brings the projection back to the front after the head unit took the screen
 * for a moment: a call, the reverse camera, Siri over Bluetooth or the Honda
 * voice commands.
 *
 * An interruption counts only if it started while the projection was on
 * screen, or within a moment of it leaving the screen (the head unit pauses the
 * player before it reports why). Once nothing interrupts any more and the
 * screen has settled, the projection is brought back once, unless it is
 * already back. A screen the user opened while nothing was interrupting is
 * left alone.
 */
public final class ProjectionReturnPolicy {

    public interface Actions {
        void restoreProjection(String cause);

        void schedule(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    public interface Clock {
        long nowMs();
    }

    /** Head unit video addresses (ModeMgrProperty.MODE_LADDR_*) that are temporary by nature. */
    public static final int VIDEO_CAMERA = 92;
    public static final int VIDEO_PHONE = 87;
    public static final int VIDEO_INCOMING_CALL = 198;
    public static final int VIDEO_SIRI = 136;
    /** No owner: seen between two screens, so it neither starts nor ends anything. */
    public static final int VIDEO_OFF = 255;

    static final long LEFT_SCREEN_GRACE_MS = 2000;
    static final long SETTLE_MS = 700;

    private static final long NEVER = Long.MIN_VALUE;

    private final Actions actions_;
    private final Clock clock_;

    private boolean enabled_ = true;
    private boolean running_;
    private boolean visible_;
    private long hiddenSinceMs_ = NEVER;
    private boolean videoInterrupted_;
    private boolean interrupted_;
    private boolean armed_;
    private String cause_ = "";
    private int restores_;

    private final Runnable restoreTask_ = this::restoreIfStillDue;

    public ProjectionReturnPolicy(Actions actions, Clock clock) {
        actions_ = actions;
        clock_ = clock;
    }

    public synchronized void setEnabled(boolean enabled) {
        enabled_ = enabled;
        if (!enabled) {
            disarm();
        }
    }

    /** A projection session is open (true) or over (false). */
    public synchronized void onProjectionRunning(boolean running) {
        running_ = running;
        if (!running) {
            disarm();
        }
    }

    public synchronized void onPlayerVisible(boolean visible) {
        if (visible_ && !visible) {
            hiddenSinceMs_ = clock_.nowMs();
        }
        visible_ = visible;
        if (visible) {
            // Back on screen by itself: nothing left to restore.
            actions_.cancel(restoreTask_);
            if (!interrupted_) {
                armed_ = false;
            }
        }
    }

    /**
     * The head unit state that matters: Siri over Bluetooth or the Honda voice
     * commands running, a call (ringing, in progress or its pop-up), and the
     * video address the head unit is showing.
     */
    public synchronized void onHeadUnitState(boolean siri, boolean voiceTag, boolean call, int videoAddress) {
        if (videoAddress != VIDEO_OFF) {
            videoInterrupted_ = videoAddress == VIDEO_CAMERA || videoAddress == VIDEO_PHONE
                    || videoAddress == VIDEO_INCOMING_CALL || videoAddress == VIDEO_SIRI;
        }
        final boolean interrupted = siri || voiceTag || call || videoInterrupted_;

        if (interrupted) {
            if (siri || voiceTag || call) {
                cause_ = siri ? "siri" : voiceTag ? "voice" : "call";
            } else if (videoAddress != VIDEO_OFF) {
                cause_ = videoAddress == VIDEO_CAMERA ? "camera" : "video " + videoAddress;
            }
            actions_.cancel(restoreTask_);
            if (!interrupted_) {
                final boolean wasOnScreen = visible_
                        || (hiddenSinceMs_ != NEVER && clock_.nowMs() - hiddenSinceMs_ < LEFT_SCREEN_GRACE_MS);
                if (enabled_ && running_ && wasOnScreen) {
                    armed_ = true;
                }
            }
        } else if (interrupted_ && armed_) {
            actions_.cancel(restoreTask_);
            actions_.schedule(restoreTask_, SETTLE_MS);
        }
        interrupted_ = interrupted;
    }

    /** "off", "idle", "armed: siri", followed by " x<restores>" once it brought the projection back. */
    public synchronized String describe() {
        final String state = !enabled_ ? "off" : armed_ ? "armed: " + cause_ : "idle";
        return restores_ > 0 ? state + " x" + restores_ : state;
    }

    synchronized boolean isArmed() {
        return armed_;
    }

    synchronized int restores() {
        return restores_;
    }

    private synchronized void restoreIfStillDue() {
        if (!armed_ || interrupted_ || !running_ || !enabled_) {
            return;
        }
        armed_ = false;
        if (visible_) {
            return;
        }
        restores_++;
        actions_.restoreProjection(cause_);
    }

    private void disarm() {
        armed_ = false;
        actions_.cancel(restoreTask_);
    }
}
