package it.smg.hu.projection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Keep;

import java.util.HashMap;
import java.util.Map;

import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.hu.ui.settings.KeymapFragment;
import it.smg.libs.common.Log;

public class InputDevice extends it.smg.libs.aasdk.projection.InputDevice implements View.OnTouchListener, View.OnKeyListener {
    private static final String TAG = "InputDevice";

    private final Rect screenGeometry_;
    private final Rect touchScreenGeometry_;
    private final boolean isTouchScreenAvailable_;
    private Map<Integer, Integer> supportedButtonCodes_;

    private SurfaceView surfaceView_;
    private InputDevice.OnKeyHolder keyHolder_;
    private Context context_;

    // The input device of the running projection, for steering wheel keys
    // that arrive from Honda callbacks rather than through a window. Native
    // sends and teardown take the lock, so a key never reaches a deleted
    // device; nothing slow (no binder call) runs while it is held.
    private static final Object ACTIVE_LOCK = new Object();
    private static InputDevice active_;

    private static final int NOT_MAPPED = Integer.MIN_VALUE;
    private static final int HONDA_VOLUME_UP = Integer.MIN_VALUE + 1;
    private static final int HONDA_VOLUME_DOWN = Integer.MIN_VALUE + 2;

    public InputDevice(Context ctx, SurfaceView surfaceView, InputDevice.OnKeyHolder keyHolder){
        surfaceView_ = surfaceView;
        keyHolder_ = keyHolder;
        context_ = ctx;

        Settings settings = Settings.instance();
        switch (settings.video.resolution()){
            case 2: // 720p
                screenGeometry_ = new Rect(1280, 720);
                break;
            case 3: // 1080p
                screenGeometry_ = new Rect(1920, 1080);;
                break;
            case 4: // 1440p
                screenGeometry_ = new Rect(2560, 1440);;
                break;
            default: // 480p
                screenGeometry_ = new Rect(800, 480);
                break;
        }

        isTouchScreenAvailable_ = ctx.getPackageManager().hasSystemFeature("android.hardware.touchscreen");
        if (Log.isDebug()) Log.d(TAG, "isAvailable: " + isTouchScreenAvailable_);

        if (isTouchScreenAvailable_) {
            DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
            touchScreenGeometry_ = new Rect(metrics.widthPixels, metrics.heightPixels);
        } else {
            touchScreenGeometry_ = new Rect(0,0);
        }
        if (Log.isDebug()) Log.d(TAG, "using toucscreen geometry: " + touchScreenGeometry_);

        supportedButtonCodes_ = new HashMap<>();
        for (KeymapFragment.KeyMap c : KeymapFragment.KeyMap.values()){
            int k = settings.keymap.key(c.keyName() + "_code");
            if (k == 0){
                settings.keymap.key(c.keyName() + "_code", c.defaultValue());
                k = c.defaultValue();
            }

            if (k != -1){
                int v = settings.keymap.key(c.keyName());
                if (v == 0){
                    settings.keymap.key(c.keyName(), c.defaultAction());
                    v = c.defaultAction();
                }
                supportedButtonCodes_.put(k, v);
            }
        }

        if (Log.isDebug()) Log.d(TAG, "supported button codes: " + supportedButtonCodes_);
    }

    @Keep
    @Override
    public void start() {
        synchronized (ACTIVE_LOCK) {
            active_ = this;
        }
        gainFocus();
    }

    @Keep
    @Override
    public void stop() {
        if (Log.isInfo()) Log.i(TAG, "stop");
        synchronized (ACTIVE_LOCK) {
            if (active_ == this) {
                active_ = null;
            }
        }
        releaseFocus();
        surfaceView_ = null;
        keyHolder_ = null;
    }

    @Override
    public void delete() {
        synchronized (ACTIVE_LOCK) {
            if (active_ == this) {
                active_ = null;
            }
        }
        super.delete();
    }

    /** True while a projection is running and can take keys. */
    public static boolean projectionRunning() {
        synchronized (ACTIVE_LOCK) {
            return active_ != null;
        }
    }

    /** True when the running projection's key map has an action for this key code. */
    public static boolean isMapped(int keyCode) {
        synchronized (ACTIVE_LOCK) {
            return active_ != null && active_.resolve(keyCode) != NOT_MAPPED;
        }
    }

    /**
     * Sends a steering wheel key (a key map code, KEYCODE_STRG_*) to the phone
     * as a press and a release, whichever window has focus. False when no
     * projection is running or the key map has no action for the key.
     */
    public static boolean sendSteeringKey(int keyCode) {
        final int target;
        synchronized (ACTIVE_LOCK) {
            final InputDevice device = active_;
            if (device == null) {
                return false;
            }
            target = device.resolve(keyCode);
            if (target == NOT_MAPPED) {
                return false;
            }
            if (target != HONDA_VOLUME_UP && target != HONDA_VOLUME_DOWN) {
                device.logButton(keyCode, KeyEvent.ACTION_DOWN, target);
                device.sendButtonEvent(KeyEvent.ACTION_DOWN, target);
                device.sendButtonEvent(KeyEvent.ACTION_UP, target);
                return true;
            }
        }
        // A binder call into the head unit: outside the lock.
        changeHondaVolume(target);
        return true;
    }

    @Keep
    @Override
    public void gainFocus() {
        Log.d(TAG, "gain focus");
        if (surfaceView_ != null) {
            if (Log.isDebug()) Log.d(TAG,"gainFocus -> add touchlistener to surfaceview");
            surfaceView_.setOnTouchListener(this);
        }
        if (keyHolder_ != null){
            keyHolder_.setOnKeyListener(this);
        }
    }

    @Keep
    @Override
    public void releaseFocus() {
        if (surfaceView_ != null) {
            if (Log.isDebug()) Log.d(TAG,"releaseFocus -> remove touchlistener from surfaceview");
            surfaceView_.setOnTouchListener(null);
        }
        if (keyHolder_ != null){
            keyHolder_.setOnKeyListener(null);
        }
    }

    @Keep
    @Override
    public int[] getSupportedButtonCodes() {
        int[] codes;
        if (supportedButtonCodes_ != null && !supportedButtonCodes_.isEmpty()){
            codes = new int[supportedButtonCodes_.size()];
            int i = 0;
            for (Integer c : supportedButtonCodes_.values() ) {
                codes[i] = c;
                i++;
            }
        } else {
            codes = new int[0];
        }

        return codes;
    }

    @Keep
    @Override
    public boolean hasTouchscreen() {
        return isTouchScreenAvailable_;
    }

    @Keep
    @Override
    public Rect getTouchscreenGeometry() {
        return touchScreenGeometry_;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int count = event.getPointerCount();
        if (count > 0) {
            TouchAction[] ta = new TouchAction[count];
            for (int i = 0; i < count; i++) {
                final int x = (int) ((event.getX(i) / touchScreenGeometry_.width) * screenGeometry_.width);
                final int y = (int) ((event.getY(i) / touchScreenGeometry_.height) * screenGeometry_.height);
                final int owner = event.getPointerId(i);
                ta[i] = new TouchAction(x, y, owner);
            }
            sendTouchEvent(event.getActionMasked(), event.getActionIndex(), ta);
        }
        return true;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        final int action = event.getAction(); // 1 - UP, 0 - DOWN
        final int target = resolve(keyCode);
        if (target == NOT_MAPPED) {
            return false;
        }
        if (target == HONDA_VOLUME_UP || target == HONDA_VOLUME_DOWN) {
            // Only on action down, so one press changes the volume by one step.
            if (action == KeyEvent.ACTION_DOWN) {
                changeHondaVolume(target);
            }
        } else {
            logButton(keyCode, action, target);
            sendButtonEvent(action, target);
        }
        return true;
    }

    /**
     * What a key map code does: an Android Auto button code, a head unit
     * volume step (the volume keys with the Honda integration on), or
     * NOT_MAPPED.
     */
    private int resolve(int keyCode) {
        final Map<Integer, Integer> codes = supportedButtonCodes_;
        if (codes == null) {
            return NOT_MAPPED;
        }
        final Integer button = codes.get(keyCode);
        if (button == null) {
            return NOT_MAPPED;
        }
        final Settings settings = Settings.instance();
        if (settings.advanced.hondaIntegrationEnabled()) {
            if (keyCode == settings.keymap.key(KeymapFragment.KeyMap.PLUS.keyName() + "_code")) {
                return HONDA_VOLUME_UP;
            }
            if (keyCode == settings.keymap.key(KeymapFragment.KeyMap.MINUS.keyName() + "_code")) {
                return HONDA_VOLUME_DOWN;
            }
        }
        return button;
    }

    private static void changeHondaVolume(int target) {
        final HondaConnectManager honda = HondaConnectManager.instance();
        if (honda == null) {
            return;
        }
        if (target == HONDA_VOLUME_UP) {
            honda.increaseVolume();
        } else {
            honda.decreaseVolume();
        }
    }

    private void logButton(int keyCode, int action, int button) {
        if (Log.isVerbose()) Log.v(TAG, "found supported button " + button + "/" + keyCode);
        if (Log.isDebug() && context_ != null) {
            final Context context = context_;
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, "event key: " + keyCode + " action: " + action + " -> button: " + button, Toast.LENGTH_SHORT).show();
            });
        }
    }

    public interface OnKeyHolder {
        void setOnKeyListener(View.OnKeyListener listener);
    }

}
