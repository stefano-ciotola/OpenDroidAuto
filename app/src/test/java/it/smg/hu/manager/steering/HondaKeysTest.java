package it.smg.hu.manager.steering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import it.smg.hu.manager.steering.HondaKeys.Key;

public class HondaKeysTest {

    @Test
    public void keyEventCodesMatchTheKeyMapDefaults() {
        // KeymapFragment.KeyMap defaults: LEFT, RIGHT, SOURCE, PLUS, MINUS, TALK.
        assertEquals(-65532, HondaKeys.keymapCode(Key.PREVIOUS));
        assertEquals(-65533, HondaKeys.keymapCode(Key.NEXT));
        assertEquals(-65531, HondaKeys.keymapCode(Key.SOURCE));
        assertEquals(-65535, HondaKeys.keymapCode(Key.VOLUME_UP));
        assertEquals(-65534, HondaKeys.keymapCode(Key.VOLUME_DOWN));
        assertEquals(-65526, HondaKeys.keymapCode(Key.TALK));
    }

    @Test
    public void everySteeringCodeRoundTrips() {
        int[] codes = {HondaKeys.STRG_VOLUME_UP, HondaKeys.STRG_VOLUME_DOWN, HondaKeys.STRG_CH_UP,
                HondaKeys.STRG_CH_DOWN, HondaKeys.STRG_SOURCE, HondaKeys.STRG_DISP, HondaKeys.STRG_MENU,
                HondaKeys.STRG_PICKUP, HondaKeys.STRG_BACK, HondaKeys.STRG_TALK, HondaKeys.STRG_BACK_LONG,
                HondaKeys.STRG_TALK_LONG};
        for (int code : codes) {
            Key key = HondaKeys.fromStrgCode(code);
            assertFalse(key == Key.UNKNOWN);
            assertEquals(code, HondaKeys.strgCode(key));
        }
        assertEquals(Key.UNKNOWN, HondaKeys.fromStrgCode(0));
        assertEquals(Key.UNKNOWN, HondaKeys.fromStrgCode(-65525));
    }

    @Test
    public void smartphoneCommandsAreNotAndroidKeyCodes() {
        assertEquals(Key.PICKUP, HondaKeys.fromPhoneCommand(1));
        assertEquals(Key.PICKUP_LONG, HondaKeys.fromPhoneCommand(2));
        assertEquals(Key.HANGUP, HondaKeys.fromPhoneCommand(3));
        assertEquals(Key.HANGUP_LONG, HondaKeys.fromPhoneCommand(4));
        assertEquals(Key.HANGUP_LONG, HondaKeys.fromPhoneCommand(5));
        assertEquals(Key.TALK, HondaKeys.fromPhoneCommand(6));
        assertEquals(Key.REDIAL, HondaKeys.fromPhoneCommand(7));
        assertEquals(Key.SIRI, HondaKeys.fromPhoneCommand(8));
        assertEquals(Key.TALK_LONG, HondaKeys.fromPhoneCommand(9));
        assertEquals(Key.UNKNOWN, HondaKeys.fromPhoneCommand(0));
        assertEquals(Key.UNKNOWN, HondaKeys.fromPhoneCommand(10));
        // Every one of them is sent on as a steering code, never as itself.
        for (int command = 1; command <= 9; command++) {
            assertTrue(HondaKeys.strgCode(HondaKeys.fromPhoneCommand(command)) < -65000);
        }
    }

    @Test
    public void inspectionKeysUseTheirStatus() {
        assertEquals(Key.TALK, HondaKeys.fromInspection(10, 0));
        assertEquals(Key.TALK_LONG, HondaKeys.fromInspection(10, 1));
        assertEquals(Key.HANGUP_LONG, HondaKeys.fromInspection(12, 1));
        assertEquals(Key.VOLUME_UP, HondaKeys.fromInspection(4, 0));
        assertEquals(Key.SOURCE, HondaKeys.fromInspection(3, 0));
        // Front panel and hard keys are not steering wheel keys.
        assertEquals(Key.UNKNOWN, HondaKeys.fromInspection(15, 0));
        assertEquals(Key.UNKNOWN, HondaKeys.fromInspection(0, 0));
    }

    @Test
    public void everyVoiceKeyMeansTheAssistant() {
        assertEquals(HondaKeys.STRG_TALK, HondaKeys.keymapCode(Key.TALK_LONG));
        assertEquals(HondaKeys.STRG_TALK, HondaKeys.keymapCode(Key.SIRI));
        assertTrue(HondaKeys.isVoice(Key.TALK) && HondaKeys.isVoice(Key.TALK_LONG) && HondaKeys.isVoice(Key.SIRI));
        assertFalse(HondaKeys.isVoice(Key.PICKUP));
    }

    @Test
    public void keysWithoutAnAndroidAutoActionHaveNoKeyMapCode() {
        for (Key key : new Key[]{Key.PICKUP, Key.PICKUP_LONG, Key.REDIAL, Key.HANGUP, Key.HANGUP_LONG,
                Key.BACK, Key.BACK_LONG, Key.MENU, Key.DISP, Key.UNKNOWN}) {
            assertEquals(0, HondaKeys.keymapCode(key));
        }
        assertEquals(0, HondaKeys.strgCode(Key.UNKNOWN));
    }

    @Test
    public void eventLogKeepsTheNewestFirst() {
        HondaEventLog log = new HondaEventLog(3);
        assertEquals("", log.joined());
        log.add("a");
        log.add("b");
        log.add("c");
        log.add("d");
        assertEquals("d | c | b", log.joined());
        log.clear();
        assertEquals("", log.joined());
    }
}
