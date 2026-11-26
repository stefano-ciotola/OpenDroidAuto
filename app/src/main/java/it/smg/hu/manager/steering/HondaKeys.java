package it.smg.hu.manager.steering;

/**
 * Steering wheel keys as the Honda framework reports them, and what they mean.
 *
 * The values come from the head unit framework (ada-ext-api.jar):
 * ModeMgrManager.KEYCODE_STRG_* (the same numbers as CommonSteeringKeyCode)
 * for the key event callbacks, ModeMgrManager.EXTRA_STRG_KEY_* for the
 * "smartphone" callback and ModeMgrManager.INSPECT_KEY_* for the inspection
 * callback. The key map in the settings stores KEYCODE_STRG_* values, so every
 * key is brought back to one of those before it is sent anywhere.
 */
public final class HondaKeys {

    private HondaKeys() {}

    // ModeMgrManager.KEYCODE_STRG_*
    public static final int STRG_VOLUME_UP = -65535;
    public static final int STRG_VOLUME_DOWN = -65534;
    public static final int STRG_CH_UP = -65533;
    public static final int STRG_CH_DOWN = -65532;
    public static final int STRG_SOURCE = -65531;
    public static final int STRG_DISP = -65530;
    public static final int STRG_MENU = -65529;
    public static final int STRG_PICKUP = -65528;
    public static final int STRG_BACK = -65527;
    public static final int STRG_TALK = -65526;
    public static final int STRG_BACK_LONG = -65524;
    public static final int STRG_TALK_LONG = -65522;

    // ModeMgrManager.EXTRA_STRG_KEY_*
    public static final int PHONE_OFFHOOK = 1;
    public static final int PHONE_OFFHOOK_LONG = 2;
    public static final int PHONE_ONHOOK = 3;
    public static final int PHONE_ONHOOK_LONG = 4;
    public static final int PHONE_ONHOOK_VERY_LONG = 5;
    public static final int PHONE_TALK = 6;
    public static final int PHONE_OFFHOOK_REDIAL = 7;
    public static final int PHONE_SIRI_START = 8;
    public static final int PHONE_TALK_LONG = 9;

    // ModeMgrManager.INSPECT_KEY_SS_* and INSPECT_KEY_STATUS_*
    public static final int INSPECT_SOURCE = 3;
    public static final int INSPECT_VOL_UP = 4;
    public static final int INSPECT_VOL_DOWN = 5;
    public static final int INSPECT_CH_UP = 6;
    public static final int INSPECT_CH_DOWN = 7;
    public static final int INSPECT_MENU = 8;
    public static final int INSPECT_DISP = 9;
    public static final int INSPECT_TALK = 10;
    public static final int INSPECT_PICKUP = 11;
    public static final int INSPECT_HANGUP = 12;
    public static final int INSPECT_STATUS_LONG = 1;

    public enum Key {
        VOLUME_UP, VOLUME_DOWN, NEXT, PREVIOUS, SOURCE, DISP, MENU,
        PICKUP, PICKUP_LONG, REDIAL, HANGUP, HANGUP_LONG, BACK, BACK_LONG,
        TALK, TALK_LONG, SIRI, UNKNOWN
    }

    /** A key event callback value (KEYCODE_STRG_*). */
    public static Key fromStrgCode(int code) {
        switch (code) {
            case STRG_VOLUME_UP: return Key.VOLUME_UP;
            case STRG_VOLUME_DOWN: return Key.VOLUME_DOWN;
            case STRG_CH_UP: return Key.NEXT;
            case STRG_CH_DOWN: return Key.PREVIOUS;
            case STRG_SOURCE: return Key.SOURCE;
            case STRG_DISP: return Key.DISP;
            case STRG_MENU: return Key.MENU;
            case STRG_PICKUP: return Key.PICKUP;
            case STRG_BACK: return Key.BACK;
            case STRG_TALK: return Key.TALK;
            case STRG_BACK_LONG: return Key.BACK_LONG;
            case STRG_TALK_LONG: return Key.TALK_LONG;
            default: return Key.UNKNOWN;
        }
    }

    /** A smartphone callback value (EXTRA_STRG_KEY_*). */
    public static Key fromPhoneCommand(int command) {
        switch (command) {
            case PHONE_OFFHOOK: return Key.PICKUP;
            case PHONE_OFFHOOK_LONG: return Key.PICKUP_LONG;
            case PHONE_ONHOOK: return Key.HANGUP;
            case PHONE_ONHOOK_LONG:
            case PHONE_ONHOOK_VERY_LONG: return Key.HANGUP_LONG;
            case PHONE_TALK: return Key.TALK;
            case PHONE_OFFHOOK_REDIAL: return Key.REDIAL;
            case PHONE_SIRI_START: return Key.SIRI;
            case PHONE_TALK_LONG: return Key.TALK_LONG;
            default: return Key.UNKNOWN;
        }
    }

    /** An inspection callback value (INSPECT_KEY_SS_*) with its short/long status. */
    public static Key fromInspection(int key, int status) {
        final boolean longPress = status == INSPECT_STATUS_LONG;
        switch (key) {
            case INSPECT_SOURCE: return Key.SOURCE;
            case INSPECT_VOL_UP: return Key.VOLUME_UP;
            case INSPECT_VOL_DOWN: return Key.VOLUME_DOWN;
            case INSPECT_CH_UP: return Key.NEXT;
            case INSPECT_CH_DOWN: return Key.PREVIOUS;
            case INSPECT_MENU: return Key.MENU;
            case INSPECT_DISP: return Key.DISP;
            case INSPECT_TALK: return longPress ? Key.TALK_LONG : Key.TALK;
            case INSPECT_PICKUP: return longPress ? Key.PICKUP_LONG : Key.PICKUP;
            case INSPECT_HANGUP: return longPress ? Key.HANGUP_LONG : Key.HANGUP;
            default: return Key.UNKNOWN;
        }
    }

    /**
     * The key map code a key is sent as. Talk held down and the Siri command
     * use the Talk entry: a press of the voice button always means "assistant".
     * Keys without an entry of their own in the key map answer 0.
     */
    public static int keymapCode(Key key) {
        switch (key) {
            case VOLUME_UP: return STRG_VOLUME_UP;
            case VOLUME_DOWN: return STRG_VOLUME_DOWN;
            case NEXT: return STRG_CH_UP;
            case PREVIOUS: return STRG_CH_DOWN;
            case SOURCE: return STRG_SOURCE;
            case TALK:
            case TALK_LONG:
            case SIRI: return STRG_TALK;
            default: return 0;
        }
    }

    /** The KEYCODE_STRG_* value of a key, for the key map screen that learns codes from the window. */
    public static int strgCode(Key key) {
        switch (key) {
            case PICKUP:
            case PICKUP_LONG:
            case REDIAL: return STRG_PICKUP;
            case HANGUP:
            case HANGUP_LONG:
            case BACK: return STRG_BACK;
            case BACK_LONG: return STRG_BACK_LONG;
            case DISP: return STRG_DISP;
            case MENU: return STRG_MENU;
            case TALK_LONG: return STRG_TALK_LONG;
            default: return keymapCode(key);
        }
    }

    public static boolean isVoice(Key key) {
        return key == Key.TALK || key == Key.TALK_LONG || key == Key.SIRI;
    }

    public static boolean isVolume(Key key) {
        return key == Key.VOLUME_UP || key == Key.VOLUME_DOWN;
    }
}
