package it.smg.hu.manager.steering;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides what a steering wheel press does once the Honda framework has
 * delivered it.
 *
 * The same press can reach the app through more than one path (key event,
 * smartphone, inspection and steering menu callbacks, and the window's key
 * events), so a press of a key that already went out a moment earlier through
 * another path is dropped. The voice button is
 * stricter: Talk, Talk held down and "Siri start" are one press for the user,
 * and a second voice key would close the assistant the first one opened. A new
 * short press of Talk still goes through, so the assistant can be stopped from
 * the wheel.
 *
 * While a projection is running every key the key map knows goes straight to
 * the phone, whichever window has focus. Keys it does not know (phone, menu,
 * display) are left to the head unit. Without a projection (the key map screen
 * learning a key) the key is injected into this app's window as before, and
 * always as a KEYCODE_STRG_* value: never as the raw smartphone or inspection
 * number, which Android reads as HOME, BACK or ENDCALL.
 *
 * Sending happens without holding this object's lock: volume keys make a
 * binder call into the head unit and window injection waits for the window,
 * and neither may block the next callback.
 */
public final class SteeringKeyRouter {

    public enum Source {
        KEY_EVENT("key"), PHONE("phone"), INSPECTION("insp"), STEERING_MENU("menu"), WINDOW("win");

        final String label;

        Source(String label) {
            this.label = label;
        }
    }

    public interface Output {
        /** True while a projection is running and can take keys. */
        boolean projectionRunning();

        /** True when the projection's key map has an action for this code. No side effects. */
        boolean mapped(int keymapCode);

        /** Sends a key map code to the projection; false when it could not (the projection just stopped). */
        boolean toProjection(int keymapCode);

        /** Injects a KEYCODE_STRG_* value into this app's window; false when it could not. */
        boolean injectIntoWindow(int strgCode);
    }

    public interface Clock {
        long nowMs();
    }

    public enum Outcome {
        PROJECTION("-> AA"), WINDOW("-> app"), DUPLICATE("dup"), HEAD_UNIT("honda"), UNKNOWN("?"), IGNORED("ignored");

        final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    public static final class Result {
        public final HondaKeys.Key key;
        public final Outcome outcome;
        private final String raw_;

        Result(HondaKeys.Key key, Outcome outcome, String raw) {
            this.key = key;
            this.outcome = outcome;
            raw_ = raw;
        }

        /** True when the press was used (or was a copy of one that was): the head unit should not act on it. */
        public boolean consumed() {
            return outcome == Outcome.PROJECTION || outcome == Outcome.WINDOW || outcome == Outcome.DUPLICATE;
        }

        @Override
        public String toString() {
            return (key == HondaKeys.Key.UNKNOWN ? "" : key + " ") + raw_ + " " + outcome.label;
        }
    }

    /** Copies of one press from different callbacks arrive within a few milliseconds. */
    static final long VOLUME_WINDOW_MS = 150;
    static final long KEY_WINDOW_MS = 300;
    /** After a voice key, other voice keys are the same press for this long... */
    static final long VOICE_WINDOW_MS = 2000;
    /** ...except a new short Talk that comes after this much silence on the voice button. */
    static final long VOICE_REPRESS_GAP_MS = 400;

    private static final long NEVER = Long.MIN_VALUE;
    private static final int VOICE_FAMILY = HondaKeys.STRG_TALK;

    private final Output output_;
    private final Clock clock_;

    // Guarded by this.
    private final Map<Integer, Long> lastSentMs_ = new HashMap<>();
    private final Map<Integer, Source> lastSource_ = new HashMap<>();
    private long voiceSentMs_ = NEVER;
    private long voiceSeenMs_ = NEVER;
    private long sequence_;

    public SteeringKeyRouter(Output output, Clock clock) {
        output_ = output;
        clock_ = clock;
    }

    /** Handles one callback. */
    public Result onKey(Source source, int first, int second) {
        final HondaKeys.Key key = normalize(source, first, second);
        final boolean twoArgs = source == Source.KEY_EVENT || source == Source.INSPECTION || source == Source.WINDOW;
        final String raw = source.label + "(" + first + (twoArgs ? "," + second : "") + ")";
        if (key == HondaKeys.Key.UNKNOWN) {
            return new Result(key, Outcome.UNKNOWN, raw);
        }

        if (source == Source.WINDOW && second > 0 && !HondaKeys.isVolume(key)) {
            // The window repeats a held key; only the volume steps with it.
            return new Result(key, Outcome.DUPLICATE, raw);
        }

        final int family = familyOf(key);
        final boolean toProjection;
        final int code;
        final Mark mark;
        synchronized (this) {
            toProjection = output_.projectionRunning();
            code = toProjection ? projectionCode(key, first) : HondaKeys.strgCode(key);
            if (code == 0) {
                // Not counted as a press: a copy of it from another callback
                // may still have a mapping.
                return new Result(key, toProjection ? Outcome.HEAD_UNIT : Outcome.IGNORED, raw);
            }
            final long now = clock_.nowMs();
            if (isDuplicate(family, key, source, now)) {
                if (family == VOICE_FAMILY) {
                    voiceSeenMs_ = now;
                }
                return new Result(key, Outcome.DUPLICATE, raw);
            }
            mark = mark(family, source, now);
        }

        final boolean sent;
        if (toProjection) {
            sent = output_.toProjection(code);
        } else if (source == Source.WINDOW) {
            // Already in the app's window; injecting it would bring it back
            // here. Counted, so a callback copy is not injected either.
            sent = true;
        } else {
            sent = output_.injectIntoWindow(code);
        }
        if (!sent) {
            synchronized (this) {
                unmark(mark);
            }
            return new Result(key, Outcome.IGNORED, raw);
        }
        return new Result(key, toProjection ? Outcome.PROJECTION : Outcome.WINDOW, raw);
    }

    /**
     * The key map code to send, or 0 when the key map has no action for the
     * key. The key map stores KEYCODE_STRG_* values; a key learned by an older
     * build may be stored as the raw callback number instead.
     */
    private int projectionCode(HondaKeys.Key key, int raw) {
        final int[] candidates = {HondaKeys.keymapCode(key), HondaKeys.strgCode(key), raw};
        for (int code : candidates) {
            if (code != 0 && output_.mapped(code)) {
                return code;
            }
        }
        return 0;
    }

    private boolean isDuplicate(int family, HondaKeys.Key key, Source source, long now) {
        if (family == VOICE_FAMILY) {
            final boolean quiet = voiceSeenMs_ == NEVER || now - voiceSeenMs_ >= VOICE_REPRESS_GAP_MS;
            final boolean recent = voiceSentMs_ != NEVER && now - voiceSentMs_ < VOICE_WINDOW_MS;
            return !quiet || (recent && key != HondaKeys.Key.TALK);
        }
        final Long last = lastSentMs_.get(family);
        final long window = HondaKeys.isVolume(key) ? VOLUME_WINDOW_MS : KEY_WINDOW_MS;
        // The same callback repeating a key is a held key or a new press.
        return last != null && lastSource_.get(family) != source && now - last < window;
    }

    /** What a press changed, so it can be taken back if it went nowhere. */
    private static final class Mark {
        final int family;
        final long sequence;
        final Long previousSentMs;
        final Source previousSource;
        final long previousVoiceSentMs;
        final long previousVoiceSeenMs;

        Mark(int family, long sequence, Long previousSentMs, Source previousSource,
             long previousVoiceSentMs, long previousVoiceSeenMs) {
            this.family = family;
            this.sequence = sequence;
            this.previousSentMs = previousSentMs;
            this.previousSource = previousSource;
            this.previousVoiceSentMs = previousVoiceSentMs;
            this.previousVoiceSeenMs = previousVoiceSeenMs;
        }
    }

    private Mark mark(int family, Source source, long now) {
        final Mark mark = new Mark(family, ++sequence_, lastSentMs_.get(family), lastSource_.get(family),
                voiceSentMs_, voiceSeenMs_);
        lastSentMs_.put(family, now);
        lastSource_.put(family, source);
        if (family == VOICE_FAMILY) {
            voiceSentMs_ = now;
            voiceSeenMs_ = now;
        }
        return mark;
    }

    private void unmark(Mark mark) {
        if (mark.sequence != sequence_) {
            // Another press went out meanwhile and owns the state now.
            return;
        }
        if (mark.previousSentMs == null) {
            lastSentMs_.remove(mark.family);
            lastSource_.remove(mark.family);
        } else {
            lastSentMs_.put(mark.family, mark.previousSentMs);
            lastSource_.put(mark.family, mark.previousSource);
        }
        if (mark.family == VOICE_FAMILY) {
            voiceSentMs_ = mark.previousVoiceSentMs;
            voiceSeenMs_ = mark.previousVoiceSeenMs;
        }
    }

    static HondaKeys.Key normalize(Source source, int first, int second) {
        switch (source) {
            case PHONE:
                return HondaKeys.fromPhoneCommand(first);
            case INSPECTION:
                return HondaKeys.fromInspection(first, second);
            case KEY_EVENT:
                final HondaKeys.Key key = HondaKeys.fromStrgCode(first);
                // Which argument carries the code is not documented.
                return key != HondaKeys.Key.UNKNOWN ? key : HondaKeys.fromStrgCode(second);
            case STEERING_MENU:
            default:
                return HondaKeys.fromStrgCode(first);
        }
    }

    /** Keys that are one physical button share a family. */
    private static int familyOf(HondaKeys.Key key) {
        return HondaKeys.isVoice(key) ? VOICE_FAMILY : HondaKeys.strgCode(key);
    }
}
