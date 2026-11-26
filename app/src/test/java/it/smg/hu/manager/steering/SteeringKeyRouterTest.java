package it.smg.hu.manager.steering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import it.smg.hu.manager.steering.SteeringKeyRouter.Outcome;
import it.smg.hu.manager.steering.SteeringKeyRouter.Source;

public class SteeringKeyRouterTest {

    /** A projection with the default key map, or the key map screen when not running. */
    private static final class FakeOutput implements SteeringKeyRouter.Output {
        boolean running = true;
        boolean sendFails;
        final Set<Integer> keymap = new HashSet<>();
        final List<Integer> sent = new ArrayList<>();
        final List<Integer> injected = new ArrayList<>();

        FakeOutput() {
            keymap.add(HondaKeys.STRG_CH_DOWN);
            keymap.add(HondaKeys.STRG_CH_UP);
            keymap.add(HondaKeys.STRG_SOURCE);
            keymap.add(HondaKeys.STRG_VOLUME_UP);
            keymap.add(HondaKeys.STRG_VOLUME_DOWN);
            keymap.add(HondaKeys.STRG_TALK);
        }

        @Override
        public boolean projectionRunning() {
            return running;
        }

        @Override
        public boolean mapped(int keymapCode) {
            return keymap.contains(keymapCode);
        }

        @Override
        public boolean toProjection(int keymapCode) {
            if (sendFails) {
                return false;
            }
            sent.add(keymapCode);
            return true;
        }

        @Override
        public boolean injectIntoWindow(int strgCode) {
            injected.add(strgCode);
            return true;
        }
    }

    private static final class FakeClock implements SteeringKeyRouter.Clock {
        long now = 100_000;

        @Override
        public long nowMs() {
            return now;
        }
    }

    private final FakeOutput output = new FakeOutput();
    private final FakeClock clock = new FakeClock();
    private final SteeringKeyRouter router = new SteeringKeyRouter(output, clock);

    private Outcome key(Source source, int first, int second, long afterMs) {
        clock.now += afterMs;
        return router.onKey(source, first, second).outcome;
    }

    @Test
    public void onePressDeliveredByThreeCallbacksIsOneVoicePress() {
        assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 0));
        assertEquals(Outcome.DUPLICATE, key(Source.PHONE, HondaKeys.PHONE_TALK, 0, 4));
        assertEquals(Outcome.DUPLICATE, key(Source.PHONE, HondaKeys.PHONE_SIRI_START, 0, 6));
        assertEquals(1, output.sent.size());
        assertEquals(HondaKeys.STRG_TALK, (int) output.sent.get(0));
    }

    @Test
    public void aSecondShortTalkStopsTheAssistant() {
        key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 0);
        key(Source.PHONE, HondaKeys.PHONE_TALK, 0, 5);
        // The user presses again to stop the assistant, even from another callback first.
        assertEquals(Outcome.PROJECTION, key(Source.PHONE, HondaKeys.PHONE_TALK, 0, 900));
        assertEquals(Outcome.DUPLICATE, key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 3));
        assertEquals(2, output.sent.size());
    }

    @Test
    public void talkHeldDownAfterTalkIsTheSamePress() {
        assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 0));
        assertEquals(Outcome.DUPLICATE, key(Source.KEY_EVENT, HondaKeys.STRG_TALK_LONG, 2, 1000));
        assertEquals(1, output.sent.size());
    }

    @Test
    public void aHeldVoiceKeyRepeatingStaysOnePress() {
        assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, HondaKeys.STRG_TALK_LONG, 2, 0));
        for (int i = 0; i < 30; i++) {
            assertEquals(Outcome.DUPLICATE, key(Source.KEY_EVENT, HondaKeys.STRG_TALK_LONG, 4, 200));
        }
        assertEquals(1, output.sent.size());
        // After a pause it is a new press.
        assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, HondaKeys.STRG_TALK_LONG, 2, 3000));
    }

    @Test
    public void quickBounceOfTalkIsDropped() {
        key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 0);
        assertEquals(Outcome.DUPLICATE, key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 300));
        assertEquals(1, output.sent.size());
    }

    @Test
    public void heldVolumeRepeatsButCopiesFromOtherCallbacksDoNot() {
        for (int i = 0; i < 5; i++) {
            assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, HondaKeys.STRG_VOLUME_UP, 4, 200));
            assertEquals(Outcome.DUPLICATE, key(Source.INSPECTION, HondaKeys.INSPECT_VOL_UP, 0, 3));
        }
        assertEquals(5, output.sent.size());
        for (int code : output.sent) {
            assertEquals(HondaKeys.STRG_VOLUME_UP, code);
        }
    }

    @Test
    public void nextAndPreviousGoToTheProjection() {
        assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, HondaKeys.STRG_CH_UP, 1, 0));
        assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, HondaKeys.STRG_CH_DOWN, 1, 500));
        assertEquals(HondaKeys.STRG_CH_UP, (int) output.sent.get(0));
        assertEquals(HondaKeys.STRG_CH_DOWN, (int) output.sent.get(1));
    }

    @Test
    public void phoneKeysStayWithTheHeadUnit() {
        SteeringKeyRouter.Result pickup = router.onKey(Source.PHONE, HondaKeys.PHONE_OFFHOOK, 0);
        assertEquals(Outcome.HEAD_UNIT, pickup.outcome);
        assertFalse(pickup.consumed());
        assertEquals(Outcome.HEAD_UNIT, key(Source.PHONE, HondaKeys.PHONE_ONHOOK, 0, 500));
        assertEquals(Outcome.HEAD_UNIT, key(Source.STEERING_MENU, HondaKeys.STRG_MENU, 0, 500));
        assertTrue(output.sent.isEmpty());
        assertTrue(output.injected.isEmpty());
    }

    @Test
    public void anUnmappedPressDoesNotHideACopyThatIsMapped() {
        // A key map learned by an older build, which injected the raw
        // smartphone number: Talk is stored as "Siri start" (8).
        output.keymap.remove(HondaKeys.STRG_TALK);
        output.keymap.add(HondaKeys.PHONE_SIRI_START);
        assertEquals(Outcome.HEAD_UNIT, key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 0));
        assertEquals(Outcome.PROJECTION, key(Source.PHONE, HondaKeys.PHONE_SIRI_START, 0, 5));
        assertEquals(HondaKeys.PHONE_SIRI_START, (int) output.sent.get(0));
    }

    @Test
    public void withoutAProjectionOnlySteeringCodesReachTheWindow() {
        output.running = false;
        assertEquals(Outcome.WINDOW, key(Source.PHONE, HondaKeys.PHONE_TALK, 0, 0));
        assertEquals(Outcome.WINDOW, key(Source.PHONE, HondaKeys.PHONE_ONHOOK, 0, 3000));
        assertEquals(Outcome.WINDOW, key(Source.INSPECTION, HondaKeys.INSPECT_VOL_UP, 0, 3000));
        assertEquals(Outcome.WINDOW, key(Source.PHONE, HondaKeys.PHONE_ONHOOK_LONG, 0, 3000));
        // Never 6 (ENDCALL), 3 (HOME) or 4 (BACK).
        assertEquals(HondaKeys.STRG_TALK, (int) output.injected.get(0));
        assertEquals(HondaKeys.STRG_BACK, (int) output.injected.get(1));
        assertEquals(HondaKeys.STRG_VOLUME_UP, (int) output.injected.get(2));
        assertEquals(HondaKeys.STRG_BACK, (int) output.injected.get(3));
        assertTrue(output.sent.isEmpty());
    }

    @Test
    public void unknownValuesAreNeverSentOrInjected() {
        assertEquals(Outcome.UNKNOWN, key(Source.PHONE, 42, 0, 0));
        assertEquals(Outcome.UNKNOWN, key(Source.INSPECTION, 15, 0, 0));
        assertEquals(Outcome.UNKNOWN, key(Source.KEY_EVENT, 3, 4, 0));
        output.running = false;
        assertEquals(Outcome.UNKNOWN, key(Source.STEERING_MENU, 4, 0, 0));
        assertTrue(output.sent.isEmpty());
        assertTrue(output.injected.isEmpty());
    }

    @Test
    public void keyEventCodeMayArriveInTheSecondArgument() {
        assertEquals(Outcome.PROJECTION, key(Source.KEY_EVENT, 1, HondaKeys.STRG_CH_UP, 0));
        assertEquals(HondaKeys.STRG_CH_UP, (int) output.sent.get(0));
    }

    @Test
    public void aPressThatCouldNotBeSentDoesNotCount() {
        output.sendFails = true;
        assertEquals(Outcome.IGNORED, key(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1, 0));
        output.sendFails = false;
        assertEquals(Outcome.PROJECTION, key(Source.PHONE, HondaKeys.PHONE_TALK, 0, 5));
    }

    @Test
    public void describesWhatHappened() {
        assertEquals("TALK key(-65526,1) -> AA", router.onKey(Source.KEY_EVENT, HondaKeys.STRG_TALK, 1).toString());
        clock.now += 5;
        assertEquals("TALK phone(6) dup", router.onKey(Source.PHONE, HondaKeys.PHONE_TALK, 0).toString());
        assertEquals("phone(42) ?", router.onKey(Source.PHONE, 42, 0).toString());
    }
}
