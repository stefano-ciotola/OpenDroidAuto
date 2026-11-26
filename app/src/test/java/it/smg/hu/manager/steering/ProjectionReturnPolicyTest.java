package it.smg.hu.manager.steering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.Test;

public class ProjectionReturnPolicyTest {

    private static final int HOME = 120;
    private static final int RADIO = 96;
    private static final int PROJECTION = 197;

    /** A main thread: scheduled tasks run when the clock passes their time. */
    private static final class FakeLooper implements ProjectionReturnPolicy.Actions, ProjectionReturnPolicy.Clock {
        long now = 50_000;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> restores = new ArrayList<>();

        @Override
        public void restoreProjection(String cause) {
            restores.add(cause);
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            due.add(new long[]{now + delayMs});
            tasks.add(task);
        }

        @Override
        public void cancel(Runnable task) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (tasks.get(i) == task) {
                    tasks.remove(i);
                    due.remove(i);
                }
            }
        }

        @Override
        public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
            boolean ran = true;
            while (ran) {
                ran = false;
                Iterator<long[]> d = due.iterator();
                Iterator<Runnable> t = tasks.iterator();
                while (d.hasNext()) {
                    long[] when = d.next();
                    Runnable task = t.next();
                    if (when[0] <= now) {
                        d.remove();
                        t.remove();
                        task.run();
                        ran = true;
                        break;
                    }
                }
            }
        }
    }

    private final FakeLooper looper = new FakeLooper();
    private final ProjectionReturnPolicy policy = new ProjectionReturnPolicy(looper, looper);

    private void state(boolean siri, boolean voice, boolean call, int video) {
        policy.onHeadUnitState(siri, voice, call, video);
    }

    private void onScreen() {
        policy.onProjectionRunning(true);
        policy.onPlayerVisible(true);
        state(false, false, false, PROJECTION);
    }

    @Test
    public void comesBackAfterSiriTookTheScreen() {
        onScreen();
        state(true, false, false, PROJECTION);
        policy.onPlayerVisible(false);
        state(true, false, false, ProjectionReturnPolicy.VIDEO_SIRI);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
        state(false, false, false, HOME);
        looper.advance(ProjectionReturnPolicy.SETTLE_MS - 1);
        assertTrue(looper.restores.isEmpty());
        looper.advance(1);
        assertEquals(1, looper.restores.size());
        assertEquals("siri", looper.restores.get(0));
        // Once only.
        looper.advance(60_000);
        assertEquals(1, looper.restores.size());
    }

    @Test
    public void theHeadUnitPausesThePlayerBeforeItSaysWhy() {
        onScreen();
        policy.onPlayerVisible(false);
        looper.advance(800);
        state(false, false, true, ProjectionReturnPolicy.VIDEO_INCOMING_CALL);
        looper.advance(30_000);
        state(false, false, false, HOME);
        looper.advance(1000);
        assertEquals(1, looper.restores.size());
        assertEquals("call", looper.restores.get(0));
    }

    @Test
    public void aScreenTheUserChoseIsLeftAlone() {
        onScreen();
        policy.onPlayerVisible(false);
        state(false, false, false, RADIO);
        looper.advance(10_000);
        // A call long after the user left the projection.
        state(false, false, true, ProjectionReturnPolicy.VIDEO_INCOMING_CALL);
        state(false, false, false, RADIO);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
    }

    @Test
    public void leavingThroughTheOffAddressIsNotAnInterruption() {
        onScreen();
        policy.onPlayerVisible(false);
        state(false, false, false, ProjectionReturnPolicy.VIDEO_OFF);
        state(false, false, false, HOME);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
        assertFalse(policy.isArmed());
    }

    @Test
    public void theOffAddressBetweenCameraAndHomeKeepsWaiting() {
        onScreen();
        state(false, false, false, ProjectionReturnPolicy.VIDEO_CAMERA);
        policy.onPlayerVisible(false);
        looper.advance(20_000);
        state(false, false, false, ProjectionReturnPolicy.VIDEO_OFF);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
        state(false, false, false, HOME);
        looper.advance(1000);
        assertEquals(1, looper.restores.size());
        assertEquals("camera", looper.restores.get(0));
    }

    @Test
    public void noRestoreWhenTheProjectionCameBackByItself() {
        onScreen();
        state(false, true, false, PROJECTION);
        policy.onPlayerVisible(false);
        state(false, false, false, HOME);
        looper.advance(200);
        policy.onPlayerVisible(true);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
        assertFalse(policy.isArmed());
    }

    @Test
    public void anInterruptionThatNeverHidTheProjectionChangesNothing() {
        onScreen();
        state(false, false, true, PROJECTION);
        state(false, false, false, PROJECTION);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
        assertFalse(policy.isArmed());
    }

    @Test
    public void aNewInterruptionDuringTheSettleDelayWaitsForItsEnd() {
        onScreen();
        state(false, false, true, ProjectionReturnPolicy.VIDEO_INCOMING_CALL);
        policy.onPlayerVisible(false);
        state(false, false, false, HOME);
        looper.advance(300);
        state(false, false, false, ProjectionReturnPolicy.VIDEO_CAMERA);
        looper.advance(10_000);
        assertTrue(looper.restores.isEmpty());
        state(false, false, false, HOME);
        looper.advance(1000);
        assertEquals(1, looper.restores.size());
    }

    @Test
    public void nothingAfterTheSessionEnded() {
        onScreen();
        state(true, false, false, ProjectionReturnPolicy.VIDEO_SIRI);
        policy.onPlayerVisible(false);
        policy.onProjectionRunning(false);
        state(false, false, false, HOME);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
    }

    @Test
    public void switchedOff() {
        policy.setEnabled(false);
        onScreen();
        state(true, false, false, ProjectionReturnPolicy.VIDEO_SIRI);
        policy.onPlayerVisible(false);
        state(false, false, false, HOME);
        looper.advance(5000);
        assertTrue(looper.restores.isEmpty());
        assertEquals("off", policy.describe());
    }

    @Test
    public void describesItsState() {
        onScreen();
        assertEquals("idle", policy.describe());
        state(true, false, false, ProjectionReturnPolicy.VIDEO_SIRI);
        assertEquals("armed: siri", policy.describe());
        policy.onPlayerVisible(false);
        state(false, false, false, HOME);
        looper.advance(1000);
        assertEquals("idle x1", policy.describe());
    }
}
