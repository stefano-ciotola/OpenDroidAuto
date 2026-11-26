package it.smg.hu.manager;

import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.widget.Toast;

import com.fujitsu_ten.displayaudio.android.content.CustomContext;
import com.fujitsu_ten.displayaudio.bluetooth.vr.BluetoothVr;
import com.fujitsu_ten.displayaudio.bluetooth.vr.BluetoothVrListener;
import com.fujitsu_ten.displayaudio.ecncservice.EcNcServiceNative;
import com.fujitsu_ten.displayaudio.modemanagement.IModeMgrInspectionCallBack;
import com.fujitsu_ten.displayaudio.modemanagement.IModeMgrServiceCallBack;
import com.fujitsu_ten.displayaudio.modemanagement.IModeMgrServiceSWKeyEventCallBack;
import com.fujitsu_ten.displayaudio.modemanagement.IModeMgrServiceSWSmartPhoneCallBack;
import com.fujitsu_ten.displayaudio.modemanagement.ModeMgrManager;
import com.fujitsu_ten.displayaudio.oom.OomManager;
import com.fujitsu_ten.displayaudio.statemanagement.IStateMgrServiceCallBack;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrChangeInfo;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrInfo;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrManager;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrServiceConst;
import com.fujitsu_ten.displayaudio.statusbarexservice.StatusBarExConst;
import com.fujitsu_ten.displayaudio.statusbarexservice.StatusBarExManager;
import com.fujitsu_ten.displayaudio.statusbarexservice.StatusBarExManagerData;
import com.fujitsu_ten.displayaudio.steeringmenuservice.service.ISteeringMenuService;
import com.fujitsu_ten.displayaudio.steeringmenuservice.service.ISteeringMenuServiceCallback;
import com.fujitsu_ten.displayaudio.whitelist.common.Constants;
import com.fujitsu_ten.displayaudio.whitelist.common.IWhiteList;
import com.fujitsu_ten.displayaudio.whitelist.common.ProcessControl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import it.smg.hu.config.Settings;
import it.smg.hu.manager.steering.HondaEventLog;
import it.smg.hu.manager.steering.ProjectionReturnPolicy;
import it.smg.hu.manager.steering.SteeringKeyRouter;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.ui.PlayerActivity;
import it.smg.hu.ui.settings.KeymapFragment;
import it.smg.libs.aasdk.messenger.ChannelId;
import it.smg.libs.aasdk.projection.ISensor;
import it.smg.libs.common.Log;

public class HondaConnectManager {

    public static class SWMode {
        public static final String SW_SERVICE = "SW SERVICE";
//        public static final String MODEMGR_KEY_CALLBACK = "MODEMGR KEY CALLBACK";
    }

    public static class  AudioStreamType {
        public static final int ADA_NORMAL = 11;
        public static final int ADA_INTERRUPT = 12;
        public static final int ADA_INTERRUPT_LP = 13;
        public static final int ADA_INTERRUPT_VR = 14;
        public static final int ADA_INTERRUPT_NAVI = 15;
    }

    static class ModeMgrMode {
        public static final int REQUEST_BOTH = Integer.parseInt("011111", 2); //31
        public static final int REQUEST_AUDIO = Integer.parseInt("011101", 2); //29
        public static final int REQUEST_VIDEO = Integer.parseInt("011110", 2); //30
        public static final int CONFIRM_BOTH = Integer.parseInt("111", 2); //7
        public static final int CONFIRM_AUDIO = Integer.parseInt("101", 2); //5
        public static final int CONFIRM_VIDEO = Integer.parseInt("110", 2); //6
        public static final int NOTIFY_BOTH = Integer.parseInt("11", 2); //3
        public static final int NOTIFY_AUDIO = Integer.parseInt("01", 2); //1
        public static final int NOTIFY_VIDEO = Integer.parseInt("10", 2); //2
    }

    private static final String TAG = "HondaConnectManager";

    private static final long HEARTBEAT_INTERVAL_MS = 4000;
    /** ModeMgrProperty.MODE_LADDR_OFF: no source. */
    private static final int MODE_LADDR_OFF = 255;
    private static final int FOCUS_WAIT_TIMEOUT_MS = 250;
    private static final String MODE_MGR_THREAD_NAME = "ODA-Honda-ModeMgr";

    private static HondaConnectManager instance_;

    private /*final*/ ModeMgrManager modeMgrManager_;
    private /*final*/ StateMgrManager stateMgrManager_;
    private StatusBarExManager statusBarExManager_;
    private static final long[] CLOCK_HIDE_RETRY_MS = {300, 1000, 3000};
    private volatile boolean hideFullScreenClock_;
    private volatile String clockStatus_;
    private int clockCalls_;

    private IModeMgrServiceCallBack modeMgrServiceAudioVideoCallBack_;
    private IModeMgrServiceCallBack modeMgrServiceVideoCallBack_;

    private IStateMgrServiceCallBack stateMgrServiceCallBack_;

    // SteeringWheel service
    private ISteeringMenuService steeringMenuServiceIface_;
    private final ServiceConnection steeringMenuServiceConnection_;
    private ISteeringMenuServiceCallback steeringMenuServiceCallback_;
    private boolean boundToSteeringMenuService_;

    private IModeMgrServiceSWKeyEventCallBack modeMgrServiceSWKeyEventCallBack_;
    private IModeMgrServiceSWSmartPhoneCallBack modeMgrServiceSWSmartPhoneCallBack_;
    private IModeMgrInspectionCallBack modeMgrInspectionCallBack_;

    private volatile boolean micVrStarted_;

    // Bluetooth
    private BluetoothVr bluetoothVR_;
    private BluetoothVrListener bluetoothVrListener_;
    private volatile boolean btVoiceControlRights_;

    private final Context context_;
    private final Settings settings_;

    private volatile boolean isRunning_;
    private volatile boolean hasAudioFocus_;
    private volatile boolean hasAudioFocusForMic_;
    private ProcessControl pControl_;
    private final Object focusLock_ = new Object();
    private final Object callbackLock_ = new Object();
    private final Object micLock_ = new Object();
    private final Object heartbeatLock_ = new Object();
    private volatile boolean inForeground_;
    private volatile CountDownLatch waitCondOn_;
    private volatile CountDownLatch waitCondOff_;

    private final ExecutorService modeMgrExecutor_;
    private ScheduledExecutorService heartbeatExecutor_;
    private ScheduledFuture<?> heartbeatFuture_;
    private volatile int currentModeState_;
    private volatile int currentIdx_;

    private final Handler mainHandler_;
    private ISensor.Listener dayNightListener_;

    // Steering wheel keys, the return to the projection after the head unit
    // took the screen, and what the statistics overlay shows about both.
    private final HondaEventLog keyEvents_ = new HondaEventLog(4);
    private final HondaEventLog headUnitEvents_ = new HondaEventLog(4);
    private final SteeringKeyRouter steeringKeys_;
    private final ProjectionReturnPolicy returnPolicy_;
    private final Object headUnitLock_ = new Object();
    // Last head unit state reported by the state manager; -1 until known.
    private int siriState_ = -1;
    private int voiceTagState_ = -1;
    private int hftState_ = -1;
    private int hftPopup_ = -1;
    private int videoAddress_ = -1;
    private int audioAddress_ = -1;
    private final Object btVoiceReceiverLock_ = new Object();
    private BroadcastReceiver btVoiceReceiver_;
    // Media is playing (between MediaAudioOutput start and stop).
    private volatile boolean mediaPlaying_;

    // Optional (Advanced > Honda, off by default): hold the Bluetooth voice
    // control rights while the projection is on screen, in case the head unit
    // needs them to start Siri over Bluetooth hands-free.
    private BluetoothVrListener voiceGuardListener_;
    private boolean voiceRightsHeld_;
    private volatile String voiceGuardStatus_ = "off";

    public static void init(Context context){
        instance_ = new HondaConnectManager(context);
    }

    public static HondaConnectManager instance(){
        return instance_;
    }

    @SuppressLint("WrongConstant")
    private HondaConnectManager(Context context){
        if (Log.isDebug()) Log.d(TAG, "init");
        context_ = context;
        settings_ = Settings.instance();
        hasAudioFocus_ = false;
        hasAudioFocusForMic_ = false;
        micVrStarted_ = false;
        isRunning_ = false;
        mainHandler_ = new Handler(Looper.getMainLooper());
        steeringKeys_ = new SteeringKeyRouter(new SteeringKeyRouter.Output() {
            @Override
            public boolean projectionRunning() {
                // The key map screen learns keys from its window, even while
                // a projection runs in the background.
                return InputDevice.projectionRunning() && !KeymapFragment.isOnScreen();
            }

            @Override
            public boolean mapped(int keymapCode) {
                return InputDevice.isMapped(keymapCode);
            }

            @Override
            public boolean toProjection(int keymapCode) {
                return InputDevice.sendSteeringKey(keymapCode);
            }

            @Override
            public boolean injectIntoWindow(int strgCode) {
                return injectKey(strgCode);
            }
        }, SystemClock::elapsedRealtime);
        returnPolicy_ = new ProjectionReturnPolicy(new ProjectionReturnPolicy.Actions() {
            @Override
            public void restoreProjection(String cause) {
                mainHandler_.post(() -> bringProjectionToFront(cause));
            }

            @Override
            public void schedule(Runnable task, long delayMs) {
                mainHandler_.postDelayed(task, delayMs);
            }

            @Override
            public void cancel(Runnable task) {
                mainHandler_.removeCallbacks(task);
            }
        }, SystemClock::elapsedRealtime);
        modeMgrExecutor_ = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, MODE_MGR_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        currentModeState_ = 0;
        currentIdx_ = 255;

        try {
            modeMgrManager_ = (ModeMgrManager) context.getSystemService(CustomContext.MODE_MGR_SERVICE);
            if (modeMgrManager_ == null) {
                if (Log.isWarn()) Log.w(TAG, "modeMgrManager null");
            }

            stateMgrManager_ = (StateMgrManager) context.getSystemService(CustomContext.STATE_MGR_SERVICE);
            if (stateMgrManager_ == null) {
                if (Log.isWarn()) Log.w(TAG, "stateMgrManager null");
            }

            statusBarExManager_ = (StatusBarExManager) context.getSystemService(CustomContext.STATUS_BAR_EX_SERVICE);
            if (statusBarExManager_ == null) {
                if (Log.isWarn()) Log.w(TAG, "statusBarExManager null");
            }

        } catch (Error e){
            Log.e(TAG, "error", e);
            modeMgrManager_ = null;
            stateMgrManager_ = null;
        }

        steeringMenuServiceConnection_ = new ServiceConnection() {
            private static final String TAG = "HondaConnectManager-steeringServiceConnection";

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (Log.isDebug()) Log.d(TAG, "Honda Wheel Service connected");
                boundToSteeringMenuService_ = true;
                steeringMenuServiceIface_ = ISteeringMenuService.Stub.asInterface(service);

                registerSteeringMenuCallback();

                if (!isPreinstall()){
                    notifySteeringMenuDispMode(1);
                }

            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (Log.isDebug()) Log.d(TAG, "Honda Wheel Service disconnected");
                boundToSteeringMenuService_ = false;
                steeringMenuServiceIface_ = null;
            }
        };

        if (settings_.advanced.useBtMicVr()) {
            bluetoothVR_ = BluetoothVr.getInstance();
            if (bluetoothVR_ != null) {
                if (Log.isDebug()) Log.d(TAG, "bluetoothVR " + bluetoothVR_);
                bluetoothVrListener_ = new BTVrListener();
            }
        }

        try {
            // Keyed by package: a build with another applicationId has its own
            // Honda Permissions entry (and Honda enforces that one).
            pControl_ = IWhiteList.getProcessControl(context_.getPackageName(), null);
            if (pControl_ != null) {
                if (Log.isDebug()) {
                    Log.d(TAG, "ProcessControl [ ");
                    Log.d(TAG, "appType= " + pControl_.appType);
                    Log.d(TAG, "authType= " + pControl_.authType);
                    Log.d(TAG, "lastMode= " + pControl_.lastMode);
                    Log.d(TAG, "oomSetPerm= " + pControl_.oomSetPerm);
                    Log.d(TAG, "result= " + pControl_.result);
                    Log.d(TAG, "soundInterrupt= " + pControl_.soundInterrupt);
                    Log.d(TAG, "soundInterruptMute= " + pControl_.soundInterruptMute);
                    Log.d(TAG, "soundOut= " + pControl_.soundOut);
                    Log.d(TAG, "videoOut= " + pControl_.videoOut);
                    Log.d(TAG, "]");
                }
            }
        } catch (Throwable t){
            Log.e(TAG, "process control error", t);
        }

    }

    public int mediaAudioStream(ChannelId audioChannel){
        if (isPreinstall()){
            switch (audioChannel) {
                case SPEECH_AUDIO:
                case SYSTEM_AUDIO:
                    return AudioStreamType.ADA_INTERRUPT_NAVI;
                case MEDIA_AUDIO:
                default:
                    return AudioStreamType.ADA_NORMAL;
            }
        }

        return AudioTrack.MODE_STREAM;
    }

    public void adjustPermission(){
        try {
            int ret = OomManager.getOomAdjustment(context_);
            if (Log.isDebug()) Log.d(TAG, "getOomAdjustment " + ret);
            ret = OomManager.setOomAdjustmentCoreServer(context_);
            if (Log.isDebug()) Log.d(TAG, "setOomAdjustment " + ret);
        } catch (Exception e){
            Log.e(TAG, "error in adjustPermission", e);
        }
    }

    private void runOnModeMgrExecutor(Runnable action) {
        if (MODE_MGR_THREAD_NAME.equals(Thread.currentThread().getName())) {
            action.run();
        } else {
            modeMgrExecutor_.execute(action);
        }
    }

    public void requestAudioFocus(){
        // If AUTH_TYPE <> preinstall the app has already audio focus.
        // Dispatch off the caller thread and check hasAudioFocus_ serially on modeMgrExecutor_
        // so native aasdk io_service workers never block and rapid start/suspend transitions stay ordered.
        if (isPreinstall() && modeMgrManager_ != null) {
            runOnModeMgrExecutor(() -> {
                if (!isRunning_ || !inForeground_ || hasAudioFocus_ || modeMgrManager_ == null) {
                    return;
                }
                requestFocus(settings_.advanced.modeMgrAudioVideoIdx(), ModeMgrMode.REQUEST_BOTH);
                if (!isRunning_ || !inForeground_) {
                    return;
                }

                if (statusBarExManager_ != null) {
                    if (Log.isDebug()) Log.d(TAG, "requestAudioFocus statusBarExManager_ audioSourceStartUp");
                    statusBarExManager_.audioSourceStartUp(StatusBarExConst.SOURCE_NAME_BLUETOOTH);
                    StatusBarExManagerData data = new StatusBarExManagerData();

                    if (settings_.advanced.modeMgrAudioVideoIdx() == 197) {
                        data.mSourceName = StatusBarExConst.SOURCE_NAME_BLUETOOTH;
                    } else {
                        data.mSourceName = StatusBarExConst.SOURCE_NAME_3RD_PARTY_AUTH;
                    }

                    statusBarExManager_.requestInterruptDraw(0, data);
                    reapplyFullScreenClockHidden();
                }

                registerSteeringMenuCallback();
                registerModeMgrSWCallbacks();
                notifySteeringMenuDispMode(1);
            });
        }
    }

    public void releaseAudioFocus(){
        // If AUTH_TYPE <> preinstall the app has already audio focus.
        if (isPreinstall() && modeMgrManager_ != null) {
            runOnModeMgrExecutor(() -> {
                if (!isRunning_ || !hasAudioFocus_ || modeMgrManager_ == null) {
                    return;
                }
                if (inForeground_ && settings_.advanced.keepSourceOnScreen()) {
                    // Media paused while the projection is on screen: it stays
                    // the head unit's source, as on a CarPlay or Android Auto
                    // head unit. The key callbacks are registered on the
                    // address of that source, and the head unit seems to
                    // deliver the steering wheel keys to the current source
                    // (not documented): keeping it should keep Talk reaching
                    // the phone instead of starting the head unit's own Siri.
                    // Released when the player leaves the screen
                    // (sendToBackground).
                    if (Log.isDebug()) Log.d(TAG, "releaseAudioFocus -> kept: projection on screen");
                    return;
                }
                releaseFocus(settings_.advanced.modeMgrAudioVideoIdx(), ModeMgrMode.REQUEST_AUDIO);
                if (isRunning_) {
                    requestFocus(settings_.advanced.modeMgrVideoIdx(), ModeMgrMode.REQUEST_VIDEO);
                }

                unregisterSteeringMenuCallback();
                // Media paused does not mean the user stopped using the
                // projection: while it is on screen the steering wheel keys
                // must keep reaching it.
                if (!inForeground_) {
                    unregisterModeMgrSWCallbacks();
                }
                notifySteeringMenuDispMode(0);
            });
        }
    }

    /** Android Auto media started (MediaAudioOutput.start). */
    public void onMediaStarted(){
        mediaPlaying_ = true;
        requestAudioFocus();
    }

    /** Android Auto media stopped or was suspended (MediaAudioOutput.stop/suspend). */
    public void onMediaStopped(){
        mediaPlaying_ = false;
        releaseAudioFocus();
    }

    private void requestFocus(int idx, int mode){
        if (Log.isDebug()) Log.d(TAG, "requestFocus -> app with auth " + authType());
        if (Log.isDebug()) Log.d(TAG, "requestFocus modeMgr hasAudioFocus= " + hasAudioFocus_);
        if (Log.isDebug()) Log.d(TAG, "requestFocus modeMgr idx= " + idx + ", mode= " + mode);

        // If AUTH_TYPE <> preinstall the app has already audio focus
        if (isPreinstall() && modeMgrManager_ != null) {
            synchronized (focusLock_) {
                if (!isRunning_) {
                    return;
                }
                if (idx == currentIdx_ && mode == currentModeState_){
                    if (Log.isWarn()) Log.w(TAG, "idx == currentIdx && mode == currentModeState -> do nothing");
                    return;
                }

                int sound_param = mode & ModeMgrMode.NOTIFY_AUDIO;
                int image_param = mode & ModeMgrMode.NOTIFY_VIDEO;
                if (Log.isDebug()) Log.d(TAG, "requestFocus sound_param= " + sound_param + ", image_param= " + image_param);

                if (Log.isDebug()) Log.d(TAG, "requestFocus sendModeMgrOnReqForceVideo idx= " + idx + ", mode= " + mode);
                CountDownLatch latch = new CountDownLatch(1);
                waitCondOn_ = latch;
                try {
                    int ret = modeMgrManager_.sendModeMgrOnReqForceVideo(idx, mode);
                    if (Log.isDebug()) Log.d(TAG, "requestFocus sendModeMgrOnReqForceVideo result= " + ret);

                    if (Log.isDebug()) Log.d(TAG, "requestFocus -> wait for cond");
                    waitForCond(FOCUS_WAIT_TIMEOUT_MS, latch);
                } finally {
                    if (waitCondOn_ == latch) {
                        waitCondOn_ = null;
                    }
                }
            }
        }
    }

    private void releaseFocus(int idx, int mode){
        if (Log.isDebug()) Log.d(TAG, "releaseFocus -> app with auth " + authType());
        if (Log.isDebug()) Log.d(TAG, "releaseFocus modeMgr audio hasAudioFocus= " + hasAudioFocus_);

        // If AUTH_TYPE <> preinstall the app has already audio focus
        if (isPreinstall() && modeMgrManager_ != null) {
            synchronized (focusLock_) {
                if (Log.isDebug()) Log.d(TAG, "releaseFocus sendModeMgrOffReq idx= " + idx + ", state = " + mode);
                CountDownLatch latch = new CountDownLatch(1);
                waitCondOff_ = latch;
                try {
                    int ret = modeMgrManager_.sendModeMgrOffReq(idx, mode);
                    if (Log.isDebug()) Log.d(TAG, "releaseFocus sendModeMgrOffReq ret= " + ret);

                    if (Log.isDebug()) Log.d(TAG, "releaseFocus -> wait for cond");
                    waitForCond(FOCUS_WAIT_TIMEOUT_MS, latch);
                } finally {
                    if (waitCondOff_ == latch) {
                        waitCondOff_ = null;
                    }
                }
            }
        }
    }

    public void increaseVolume(){
        if (Log.isDebug()) Log.d(TAG, "increaseVolume");
        if (modeMgrManager_ != null) {
            modeMgrManager_.reqModeMgrSteeringVolCmd(true);
        }
    }

    public void decreaseVolume(){
        if (Log.isDebug()) Log.d(TAG, "decreaseVolume");
        if (modeMgrManager_ != null) {
            modeMgrManager_.reqModeMgrSteeringVolCmd(false);
        }
    }

    // Used in onCreate
    public void initialize(){
        if (Log.isDebug()) Log.d(TAG, "initialize -> app with auth " + authType());

        bindToWheelService();

        if (isPreinstall()) {
            registerModeMgrCallback();
            registerStateMgrCallback();

            if (settings_.advanced.hondaImidEnabled()) {
                try {
                    if (Log.isDebug()) Log.d(TAG, "requestFocus setImidConnectStatus 1");
                    int ret = modeMgrManager_.setImidConnectStatus(1);
                    if (Log.isDebug()) Log.d(TAG, "requestFocus setImidConnectStatus ret= " + ret);
                } catch (Exception e) {
                    Log.w(TAG, "Could not set i-MID connect status", e);
                }
            }
        }
    }

    // Used in onResume
    public void initAudioBinding(){
        if (Log.isDebug()) Log.d(TAG, "initAudioBinding -> app with auth " + authType());
        inForeground_ = true;
        // if app has THIRD_PARTY auth will have exclusive audio focus, only bind wheel service
        if (isPreinstall()){
            if (Log.isDebug()) Log.d(TAG, "initAudioBinding -> app auth = preinstall -> register ModeMgr and SW callback");

            isRunning_ = true;

            if (Log.isDebug()) Log.d(TAG, "initAudioBinding -> hasAudioFocus= " + hasAudioFocus_);
            if (hasAudioFocus_){
                requestFocus(settings_.advanced.modeMgrAudioVideoIdx(), ModeMgrMode.REQUEST_BOTH);

                registerSteeringMenuCallback();
                registerModeMgrSWCallbacks();
                notifySteeringMenuDispMode(1);
            } else if (settings_.advanced.keepSourceOnScreen() && !otherSourcePlaying()) {
                // Nothing else is playing: make the projection the head
                // unit's source now (see releaseAudioFocus), so the steering
                // wheel keys reach it before any music has played. With the
                // radio or another source on, the projection only takes the
                // source when its media starts, as before.
                registerModeMgrSWCallbacks();
                requestAudioFocus();
            } else {
                requestFocus(settings_.advanced.modeMgrVideoIdx(), ModeMgrMode.REQUEST_VIDEO);
                registerModeMgrSWCallbacks();
            }
        } else {
            // THIRD_PARTY
            if (Log.isDebug()) Log.d(TAG, "initAudioBinding -> using authType not PREINSTALL -> register SW callback and notify");
            registerSteeringMenuCallback();
            notifySteeringMenuDispMode(1);
        }
    }

    /**
     * Hides the clock that the Honda status bar service draws over full-screen
     * apps, while the projection is in front. StatusBarExManager
     * .setFullScreenClockDisplay() is part of the head unit framework. Calling
     * it once from onResume was not enough: the clock came back. The service
     * probably redraws it when the window gains focus or an audio source is
     * announced (not confirmed), so the hide is re-applied after those events
     * and a few times after it was requested, and the outcome is kept for the
     * statistics overlay.
     */
    public void hideFullScreenClock(){
        hideFullScreenClock_ = true;
        mainHandler_.removeCallbacks(clockHideTask_);
        mainHandler_.post(clockHideTask_);
        for (long delay : CLOCK_HIDE_RETRY_MS) {
            mainHandler_.postDelayed(clockHideTask_, delay);
        }
    }

    /** Re-applies the hide after something may have redrawn the status bar. */
    public void reapplyFullScreenClockHidden(){
        if (!hideFullScreenClock_) {
            return;
        }
        mainHandler_.removeCallbacks(clockHideTask_);
        mainHandler_.post(clockHideTask_);
        mainHandler_.postDelayed(clockHideTask_, CLOCK_HIDE_RETRY_MS[1]);
    }

    public void restoreFullScreenClock(){
        if (!hideFullScreenClock_) {
            return;
        }
        hideFullScreenClock_ = false;
        mainHandler_.removeCallbacks(clockHideTask_);
        runOnModeMgrExecutor(() -> setFullScreenClockDisplay(true));
    }

    /** Last outcome of the clock call, for the statistics overlay; null when never called. */
    public String fullScreenClockStatus(){
        return clockStatus_;
    }

    private final Runnable clockHideTask_ = () -> {
        if (hideFullScreenClock_) {
            // A binder call into the status bar service: keep it off the UI thread.
            runOnModeMgrExecutor(() -> {
                if (hideFullScreenClock_) {
                    setFullScreenClockDisplay(false);
                }
            });
        }
    };

    private void setFullScreenClockDisplay(boolean visible){
        final String action = visible ? "show" : "hide";
        if (statusBarExManager_ == null) {
            clockStatus_ = action + ": no status bar service";
            if (Log.isWarn()) Log.w(TAG, "setFullScreenClockDisplay: no status bar service");
            return;
        }
        try {
            int ret = statusBarExManager_.setFullScreenClockDisplay(visible);
            clockCalls_++;
            clockStatus_ = action + " ret " + ret + " x" + clockCalls_;
            if (Log.isInfo()) Log.i(TAG, "setFullScreenClockDisplay(" + visible + ") ret= " + ret);
        } catch (Throwable t) {
            // NoSuchMethodError on firmware without the call, RuntimeException
            // from a service that refuses it: either way keep the session going.
            clockStatus_ = action + " failed: " + t.getClass().getSimpleName();
            Log.w(TAG, "setFullScreenClockDisplay not available", t);
        }
    }

    // Used in onPause (app in background)
    public void sendToBackground(){
        if (Log.isDebug()) Log.d(TAG, "sendToBackground -> app with auth " + authType() + " unregister SW callback");
        inForeground_ = false;
        unregisterSteeringMenuCallback();
        unregisterModeMgrSWCallbacks();
        notifySteeringMenuDispMode(0);

        // The source was kept only because the projection was on screen:
        // give it back, so the head unit's previous source plays again, as it
        // did when the media paused.
        if (isPreinstall() && modeMgrManager_ != null && !mediaPlaying_ && settings_.advanced.keepSourceOnScreen()) {
            runOnModeMgrExecutor(() -> {
                if (!isRunning_ || inForeground_ || mediaPlaying_ || !hasAudioFocus_ || modeMgrManager_ == null) {
                    return;
                }
                if (Log.isDebug()) Log.d(TAG, "sendToBackground -> release the source kept while on screen");
                releaseFocus(settings_.advanced.modeMgrAudioVideoIdx(), ModeMgrMode.REQUEST_AUDIO);
            });
        }
    }

    /**
     * The player screen came to the front (true) or left it (false). Drives
     * the return to the projection after a head unit interruption and the
     * optional Bluetooth voice guard.
     */
    public void onPlayerVisible(boolean visible){
        returnPolicy_.setEnabled(settings_.advanced.returnToProjection());
        if (visible) {
            returnPolicy_.onProjectionRunning(true);
        }
        returnPolicy_.onPlayerVisible(visible);
        if (visible) {
            registerBtVoiceReceiver();
            runOnModeMgrExecutor(this::refreshHeadUnitState);
        }
        runOnModeMgrExecutor(() -> updateVoiceGuard(visible));
    }

    public void endAudioBinding(){
        if (Log.isDebug()) Log.d(TAG, "endAudioBinding -> app with auth " + authType());

        inForeground_ = false;
        isRunning_ = false;
        mediaPlaying_ = false;
        returnPolicy_.onProjectionRunning(false);
        unregisterBtVoiceReceiver();
        runOnModeMgrExecutor(() -> updateVoiceGuard(false));
        stopMicSessionInternal();

        if (isPreinstall()){
            if (Log.isDebug()) Log.d(TAG, "endAudioBinding -> auth PREINSTALL -> release audio and unregister modemgr callback");

            int ret;

            if ( currentIdx_ != 255 ) {
                releaseFocus(currentIdx_, currentModeState_);
            }

            if (hasAudioFocus_){
                notifySteeringMenuDispMode(0);
            }

            unregisterModeMgrCallback();
            unregisterStateMgrCallback();
            stopHeartbeat();
            currentModeState_ = 0;
            currentIdx_ = 255;
            hasAudioFocus_ = false;

            if (settings_.advanced.hondaImidEnabled() && modeMgrManager_ != null) {
                try {
                    if (Log.isDebug()) Log.d(TAG, "releaseFocus setImidConnectStatus 0");
                    ret = modeMgrManager_.setImidConnectStatus(0);
                    if (Log.isDebug()) Log.d(TAG, "releaseFocus setImidConnectStatus ret= " + ret);
                } catch (Exception e) {
                    Log.w(TAG, "Could not set i-MID connect status", e);
                }
            }

        }

        unregisterSteeringMenuCallback();
        unregisterModeMgrSWCallbacks();
        unbindToWheelService();
    }

    private void notifySteeringMenuDispMode(int mode){
        synchronized (callbackLock_) {
            if (Log.isDebug()) Log.d(TAG, "notifySteeringMenuDispMode -> boundToSteeringMenuService= " + boundToSteeringMenuService_);
            if (boundToSteeringMenuService_ && steeringMenuServiceIface_ != null) {
                try {
                    int idx = settings_.advanced.steeringWheelIdx();
                    if (idx > 0) {
                        if (Log.isDebug()) Log.d(TAG, "notifySteeringMenuDispMode " + mode + " addr " + idx);
                        steeringMenuServiceIface_.notifySteeringMenuDispMode(idx, mode);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error registering", e);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Unexpected error notifySteeringMenuDispMode", e);
                }
            }
        }
    }

    private void registerSteeringMenuCallback(){
        synchronized (callbackLock_) {
            if (Log.isDebug()) Log.d(TAG, "registerSteeringMenuCallback -> boundToSteeringMenuService= " + boundToSteeringMenuService_ + ", steeringMenuServiceCallback= " + (steeringMenuServiceCallback_ == null ? "null" : "not null"));
            try {
                if (boundToSteeringMenuService_ && steeringMenuServiceCallback_ == null) {
                    int idx = settings_.advanced.steeringWheelIdx();
                    if (Log.isDebug()) Log.d(TAG, "registerCallbackEx swaddr " + idx);
                    steeringMenuServiceCallback_ = new SteeringMenuServiceCallback();
                    steeringMenuServiceIface_.registerCallbackEx(steeringMenuServiceCallback_, idx);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error registerCallbackEx", e);
                steeringMenuServiceCallback_ = null;
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected error registerCallbackEx", e);
                steeringMenuServiceCallback_ = null;
            }
        }
    }

    private void registerModeMgrSWCallbacks(){
        synchronized (callbackLock_) {
            if (modeMgrManager_ == null) {
                return;
            }
            if (Log.isDebug()) Log.d(TAG, "registerModeMgrSWCallbacks modeMgrServiceSWSmartPhoneCallBack= " + (modeMgrServiceSWSmartPhoneCallBack_ == null ? "null" : "not null"));
            if (modeMgrServiceSWSmartPhoneCallBack_ == null){
                modeMgrServiceSWSmartPhoneCallBack_ = new ModeMgrServiceSWSmartPhoneCallBack();
                int ret = modeMgrManager_.registerModeMgrSWSmartPhoneCallback(settings_.advanced.modeMgrAudioVideoIdx(), modeMgrServiceSWSmartPhoneCallBack_);
                if (Log.isDebug()) Log.d(TAG, "registerModeMgrSWSmartPhoneCallback ret= " + ret);
            }

            if (Log.isDebug()) Log.d(TAG, "registerModeMgrSWCallbacks modeMgrServiceSWKeyEventCallBack= " + (modeMgrServiceSWKeyEventCallBack_ == null ? "null" : "not null"));
            if (modeMgrServiceSWKeyEventCallBack_ == null){
                modeMgrServiceSWKeyEventCallBack_ = new ModeMgrServiceSWKeyEventCallBack();
                int ret = modeMgrManager_.registerModeMgrSWKeyEventCallback(settings_.advanced.modeMgrAudioVideoIdx(), modeMgrServiceSWKeyEventCallBack_);
                if (Log.isDebug()) Log.d(TAG, "registerModeMgrSWKeyEventCallback ret= " + ret);
            }

            if (Log.isDebug()) Log.d(TAG, "registerModeMgrInspectionCallback modeMgrInspectionCallBack= " + (modeMgrInspectionCallBack_ == null ? "null" : "not null"));
            if (modeMgrInspectionCallBack_ == null){
                modeMgrInspectionCallBack_ = new ModeMgrInspectionCallBack();
                int ret = modeMgrManager_.registerModeMgrInspectionCallback(modeMgrInspectionCallBack_);
                if (Log.isDebug()) Log.d(TAG, "registerModeMgrInspectionCallback ret= " + ret);
            }
        }
    }

    private void unregisterModeMgrSWCallbacks(){
        synchronized (callbackLock_) {
            if (modeMgrManager_ == null) {
                return;
            }
            if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrSWCallbacks modeMgrServiceSWSmartPhoneCallBack= " + (modeMgrServiceSWSmartPhoneCallBack_ == null ? "null" : "not null"));
            if (modeMgrServiceSWSmartPhoneCallBack_ != null){
                int ret = modeMgrManager_.unregisterModeMgrSWSmartPhoneCallback(settings_.advanced.modeMgrAudioVideoIdx());
                if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrSWSmartPhoneCallback ret= " + ret);
                modeMgrServiceSWSmartPhoneCallBack_ = null;
            }

            if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrSWCallbacks modeMgrServiceSWKeyEventCallBack= " + (modeMgrServiceSWKeyEventCallBack_ == null ? "null" : "not null"));
            if (modeMgrServiceSWKeyEventCallBack_ != null){
                int ret = modeMgrManager_.unregisterModeMgrSWKeyEventCallback(settings_.advanced.modeMgrAudioVideoIdx());
                if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrSWKeyEventCallback ret= " + ret);
                modeMgrServiceSWKeyEventCallBack_ = null;
            }

            if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrInspectionCallback modeMgrInspectionCallBack= " + (modeMgrInspectionCallBack_ == null ? "null" : "not null"));
            if (modeMgrInspectionCallBack_ != null){
                int ret = modeMgrManager_.unregisterModeMgrInspectionCallback();
                if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrInspectionCallback ret= " + ret);
                modeMgrInspectionCallBack_ = null;
            }
        }
    }

    private void unregisterSteeringMenuCallback(){
        synchronized (callbackLock_) {
            if (Log.isDebug()) Log.d(TAG, "unregisterSteeringMenuCallback -> boundToSteeringMenuService= " + boundToSteeringMenuService_ + ", steeringMenuServiceCallback= " + (steeringMenuServiceCallback_ == null ? "null" : "not null"));
            try {
                if (boundToSteeringMenuService_ && steeringMenuServiceCallback_ != null) {
                    int idx = settings_.advanced.steeringWheelIdx();
                    if (Log.isDebug()) Log.d(TAG, "unregisterCallbackEx swaddr " + idx);
                    steeringMenuServiceIface_.unregisterCallbackEx(steeringMenuServiceCallback_, idx);
                    steeringMenuServiceCallback_ = null;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error unregisterCallbackEx", e);
                steeringMenuServiceCallback_ = null;
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected error unregisterCallbackEx", e);
                steeringMenuServiceCallback_ = null;
            }
        }
    }

    public void startMicSession() {
        runOnModeMgrExecutor(this::startMicSessionInternal);
    }

    private void startMicSessionInternal() {
        synchronized (micLock_) {
            if (Log.isDebug()) Log.d(TAG, "starting MicSession -> hasAudioFocus= " + hasAudioFocus_);
            if (!settings_.advanced.hondaMicVrEnabled() || !isRunning_){
                if (Log.isDebug()) Log.d(TAG, "mic disabled or session stopped");
                return;
            }

            if (micVrStarted_) {
                if (Log.isWarn()) Log.w(TAG, "mic session already started");
                return;
            }

            int ret = -1;
            if (settings_.advanced.useBtMicVr()) {
                if (bluetoothVR_ != null) {
                    if (isPreinstall() && !hasAudioFocus_) {
                        if (Log.isDebug()) Log.d(TAG, "startMicSession hasAudioFocus_ false -> request audio focus");
                        requestFocus(settings_.advanced.modeMgrAudioVideoIdx(), ModeMgrMode.REQUEST_BOTH);
                        hasAudioFocusForMic_ = true;
                    }
                    if (!isRunning_) {
                        return;
                    }

                    if (Log.isDebug()) Log.d(TAG, "bluetoothVR -> getVoiceControlRights");
                    ret = bluetoothVR_.getVoiceControlRights(bluetoothVrListener_);
                    if (Log.isDebug()) Log.d(TAG, "bluetoothVR -> getVoiceControlRights ret = " + ret);
                    if (ret == 0){
                        btVoiceControlRights_ = true;
                    } else {
                        if (Log.isWarn()) Log.w(TAG, "btVoiceControlRights= false");
                        btVoiceControlRights_ = false;
                        if (Log.isDebug()) {
                            Log.d(TAG, "Error in get Voice Control Rights");
                            printToast("Error in get Voice Control Rights");
                        }
                        return;
                    }

                    if (Log.isDebug()) Log.d(TAG, "requestVoiceRecognitionActivation START");
                    ret = bluetoothVR_.requestVoiceRecognitionActivation(BluetoothVrListener.REQUEST_VBRA_START); // invia AT+BVRA=1
                    if (Log.isDebug()) Log.d(TAG, "requestVoiceRecognitionActivation START ret=" + ret);
                    if (ret == 0){
                        micVrStarted_ = true;
                    } else {
                        if (Log.isWarn()) Log.w(TAG, "micVrStarted= false");
                        micVrStarted_ = false;
                        if (Log.isDebug()) {
                            Log.d(TAG, "Error in activating VR");
                            printToast("Error in activating VR");
                        }
                        return;
                    }
                } else {
                    if (Log.isWarn()) Log.w(TAG, "bluetoothVR_ null");
                    micVrStarted_ = false;
                }
            } else {
                try {
                    if (isPreinstall() && !hasAudioFocus_) {
                        if (Log.isDebug()) Log.d(TAG, "startMicSession hasAudioFocus_ false -> request audio focus");
                        requestFocus(settings_.advanced.modeMgrVideoIdx(), ModeMgrMode.REQUEST_BOTH);
                        hasAudioFocusForMic_ = true;
                    }
                    if (!isRunning_) {
                        return;
                    }

                    ret = EcNcServiceNative.startVR(true, true);
                    if (Log.isDebug()) {
                        Log.d(TAG, "ecNcServiceIface_ startVR ret= " + ret);
                        printToast("ecNcServiceIface_ startVR ret= " + ret);
                    }

                    if (ret == 0) {
                        micVrStarted_ = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "startVR exception", e);
                    micVrStarted_ = false;
                }
            }
        }
    }

    public void stopMicSession() {
        runOnModeMgrExecutor(this::stopMicSessionInternal);
    }

    private void stopMicSessionInternal() {
        synchronized (micLock_) {
            if (Log.isDebug()) Log.d(TAG, "stopping MicSession -> hasAudioFocus= " + hasAudioFocus_);
            if (!settings_.advanced.hondaMicVrEnabled()){
                if (Log.isDebug()) Log.d(TAG, "mic disabled");
                return;
            }

            int ret = -1;
            if (settings_.advanced.useBtMicVr()) {
                if (bluetoothVR_ != null) {
                    if (Log.isDebug()) Log.d(TAG, "stopMicSession micVrStarted_= " + micVrStarted_ + " btVoiceControlRights_= " + btVoiceControlRights_);
                    if (micVrStarted_) {
                        if (Log.isDebug()) Log.d(TAG, "stopMicSession -> requestVoiceRecognitionActivation STOP");
                        ret = bluetoothVR_.requestVoiceRecognitionActivation(BluetoothVrListener.REQUEST_VBRA_STOP); // invia AT+BVRA=1
                        if (Log.isDebug()) Log.d(TAG, "stopMicSession -> requestVoiceRecognitionActivation STOP ret=" + ret);
                        micVrStarted_ = false;
                    }

                    if (btVoiceControlRights_) {
                        if (Log.isDebug()) Log.d(TAG, "stopMicSession -> releaseVoiceControlRights");
                        ret = bluetoothVR_.releaseVoiceControlRights(bluetoothVrListener_);
                        if (Log.isDebug()) Log.d(TAG, "stopMicSession -> releaseVoiceControlRights ret = " + ret);
                        btVoiceControlRights_ = false;
                    }

                    if (isPreinstall() && hasAudioFocusForMic_ && isRunning_) {
                        if (Log.isDebug()) Log.d(TAG, "stopMicSession hasAudioFocusForMic_ true -> release audio focus");
                        releaseFocus(settings_.advanced.modeMgrAudioVideoIdx(), ModeMgrMode.REQUEST_AUDIO);
                        if (isRunning_) {
                            requestFocus(settings_.advanced.modeMgrVideoIdx(), ModeMgrMode.REQUEST_VIDEO);
                        }
                    }

                    hasAudioFocusForMic_ = false;

                } else {
                    if (Log.isWarn()) Log.w(TAG, "stopMicSession bluetoothVR_ null");
                }
            } else {
                if (micVrStarted_) {
                    try {
                        ret = EcNcServiceNative.endVr(true);
                        if (Log.isDebug()) {
                            Log.d(TAG, "ecNcServiceIface_ endVr ret= " + ret);
                            printToast("ecNcServiceIface_ endVr ret= " + ret);
                        }

                        ret = EcNcServiceNative.driverClose();
                        if (Log.isDebug()) {
                            Log.d(TAG, "ecNcServiceIface_ driverClose ret= " + ret);
                            printToast("ecNcServiceIface_ driverClose ret= " + ret);
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "startVR exception", e);
                    }
                    micVrStarted_ = false;
                }

                if (isPreinstall() && hasAudioFocusForMic_ && isRunning_) {
                    if (Log.isDebug()) Log.d(TAG, "stopMicSession hasAudioFocusForMic_ true -> release audio focus");
                    releaseFocus(settings_.advanced.modeMgrVideoIdx(), ModeMgrMode.REQUEST_AUDIO);
                }
                hasAudioFocusForMic_ = false;
            }
        }
    }

    private void bindToWheelService(){
        if (!boundToSteeringMenuService_) {
            if (Log.isDebug()) Log.d(TAG, "Request binding to service " + ISteeringMenuService.class.getName());
            Intent intent = new Intent(ISteeringMenuService.class.getName());
            context_.bindService(intent, steeringMenuServiceConnection_, Context.BIND_AUTO_CREATE);
        }
    }

    private void unbindToWheelService(){
        if (boundToSteeringMenuService_) {
            if (Log.isDebug()) Log.d(TAG, "Request unbinding to service " + ISteeringMenuService.class.getName());
            context_.unbindService(steeringMenuServiceConnection_);

            boundToSteeringMenuService_ = false;
            steeringMenuServiceIface_ = null;
        }
    }

    private void unregisterModeMgrCallback() {
        if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrCallback");

        if (modeMgrManager_ != null) {
            int idx = settings_.advanced.modeMgrAudioVideoIdx();
            if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrCallback idx " + idx);
            int ret = modeMgrManager_.unregisterModeMgrCallback(idx);
            if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrCallback ret " + ret);
            modeMgrServiceAudioVideoCallBack_ = null;

            idx = settings_.advanced.modeMgrVideoIdx();
            if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrCallback idx " + idx);
            ret = modeMgrManager_.unregisterModeMgrCallback(idx);
            if (Log.isDebug()) Log.d(TAG, "unregisterModeMgrCallback ret " + ret);
            modeMgrServiceVideoCallBack_ = null;
        }
    }

    private void registerModeMgrCallback(){
        if (Log.isDebug()) Log.d(TAG, "registerModeMgrCallback");
        if (modeMgrManager_ != null) {
            if (modeMgrServiceAudioVideoCallBack_ == null) {
                int idx = settings_.advanced.modeMgrAudioVideoIdx();
                if (Log.isDebug()) Log.d(TAG, "registerModeMgrCallback idx " + idx);
                modeMgrServiceAudioVideoCallBack_ = new ModeMgrServiceVideoAudioCallBack();
                int ret = modeMgrManager_.registerModeMgrCallback(idx, modeMgrServiceAudioVideoCallBack_);
                if (Log.isDebug()) Log.d(TAG, "registerModeMgrCallback ret " + ret);
            }

            if (modeMgrServiceVideoCallBack_ == null) {
                int idx = settings_.advanced.modeMgrVideoIdx();
                if (Log.isDebug()) Log.d(TAG, "registerModeMgrCallback idx " + idx);
                modeMgrServiceVideoCallBack_ = new ModeMgrServiceVideoCallBack();
                int ret = modeMgrManager_.registerModeMgrCallback(idx, modeMgrServiceVideoCallBack_);
                if (Log.isDebug()) Log.d(TAG, "registerModeMgrCallback ret " + ret);
            }
        } else {
            Log.w(TAG, "modeMgrManager_ null -> do nothing");
        }
    }

    private boolean isPreinstall() {
        return pControl_ != null && pControl_.authType == Constants.AUTH_TYPE_PREINSTALL;
    }

    private String authType() {
        return pControl_ == null ? "unavailable" : String.valueOf(pControl_.authType);
    }
  
    private void registerStateMgrCallback() {
        if (Log.isDebug()) Log.d(TAG, "registerStateMgrCallback");
        if (stateMgrManager_ != null){
            if (stateMgrServiceCallBack_ == null) {
                StateMgrChangeInfo changeInfo = createStateMgrChangeInfo();
                stateMgrServiceCallBack_ = new StateMgrServiceCallBack();
                int ret = stateMgrManager_.registCallBack(stateMgrServiceCallBack_, changeInfo);
                if (Log.isDebug()) Log.d(TAG, "registerStateMgrCallback ret " + ret);
            }
        } else {
            Log.w(TAG, "stateMgrManager_ null -> do nothing");
        }
    }

    private void unregisterStateMgrCallback() {
        if (Log.isDebug()) Log.d(TAG, "registerStateMgrCallback");
        if (stateMgrManager_ != null){
            if (Log.isDebug()) Log.d(TAG, "stateMgrManager_ unRegistCallBack");
            int ret = stateMgrManager_.unRegistCallBack(stateMgrServiceCallBack_);
            if (Log.isDebug()) Log.d(TAG, "unRegistCallBack ret " + ret);
            stateMgrServiceCallBack_ = null;
        } else {
            Log.w(TAG, "stateMgrManager_ null -> do nothing");
        }
    }

    private StateMgrChangeInfo createStateMgrChangeInfo(){
        StateMgrChangeInfo changeInfo = new StateMgrChangeInfo();
        changeInfo.audioAddressC = true;
        changeInfo.dayNightStateC = true;
        changeInfo.rdsInterruptC = false;
        changeInfo.rdsAlarmInterruptC = false;
        changeInfo.videoAddressC = true;
        changeInfo.functionInfoC = false;
        changeInfo.openingScreenC = false;
        changeInfo.disclaimerC = false;
        changeInfo.languageC = false;
        changeInfo.antitheftC = false;
        changeInfo.inlineDiagC = false;
        changeInfo.volumePanelC = false;
        changeInfo.brightnessBarC = false;
        changeInfo.sourceFlowC = false;
        changeInfo.steeringMenuC = false;
        changeInfo.steeringPopUpC = false;
        changeInfo.audioLauncherC = false;
        changeInfo.iMidC = false;
        changeInfo.hftPopupC = true;
        changeInfo.screenOffC = true;
        changeInfo.usbPopupC = false;
        changeInfo.parkingSensorC = false;
        changeInfo.highTemperatureDetectionC = false;
        changeInfo.usbOvercurrentC = false;
        changeInfo.keyOffTimerAdvanceC = false;
        changeInfo.keyOffTimerExpirationC = false;
        changeInfo.lastVideoAddressC = true;
        changeInfo.videoResumeCompletedC = false;
        changeInfo.lastAudioAddressC = false;
        changeInfo.audioResumeCompletedC = false;
        changeInfo.hftStateC = true;
        changeInfo.siriStateC = true;
        changeInfo.voiceTagStateC = true;
        changeInfo.steeringDispKeyC = false;
        changeInfo.headerInterruptC = false;

        return changeInfo;
    }

    private boolean waitForCond(int timeout, CountDownLatch waitCond){
        boolean res = false;
        try {
//            waitCond = new CountDownLatch(1);
            res = waitCond.await(timeout, TimeUnit.MILLISECONDS);
            if (!res) {
                if (Log.isWarn()) Log.w(TAG, "timeout in waiting condition");
            } else {
                if (Log.isDebug()) Log.d(TAG, "received conf/notify condition");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "error in wait condition", e);
        }

//        waitCond = null;

        return res;
    }

    private void heartbeat(){
        if (Log.isDebug()) Log.d(TAG, "heartbeat currentModeState_= " + currentModeState_ + " currentIdx_= " + currentIdx_);
        if (modeMgrManager_ != null && currentModeState_ != 0) {
            int idx = currentIdx_;

            int sound_param = currentModeState_ & ModeMgrMode.NOTIFY_AUDIO;
            int image_param = currentModeState_ & ModeMgrMode.NOTIFY_VIDEO;
            if (Log.isDebug()) Log.d(TAG, "heartbeat sound_param= " + sound_param + " image_param= " + image_param);

            int notifyMode;
            if (sound_param != 0 && image_param != 0){
                notifyMode = ModeMgrMode.NOTIFY_BOTH;
            } else if (sound_param == 0 && image_param != 0){
                notifyMode = ModeMgrMode.NOTIFY_VIDEO;
            } else if (sound_param != 0){
                notifyMode = ModeMgrMode.NOTIFY_AUDIO;
            } else {
                if (Log.isWarn()) Log.w(TAG, "mode unknown");
                return;
            }

            if (Log.isDebug()) Log.d(TAG, "heartbeat notifyModeMgrStatus idx= " + idx + ", state = " + notifyMode);

            int ret = modeMgrManager_.notifyModeMgrStatus(idx, notifyMode);
            if (Log.isDebug()) Log.d(TAG, "heartbeat notifyModeMgrStatus ret = " + ret);
        }
    }

    private void startHeartbeat() {
        synchronized (heartbeatLock_) {
            if (Log.isDebug()) Log.d(TAG, "startHeartbeat currentModeState_ " + currentModeState_);

            if (!isRunning_ || currentModeState_ == 0) {
                if (Log.isDebug()) Log.d(TAG, "startHeartbeat currentState 0 or stopped -> stop heartbeat");
                stopHeartbeat();
                return;
            }

            heartbeat();

            if (heartbeatExecutor_ != null){
                if (Log.isDebug()) Log.d(TAG, "startHeartbeat heartbeat already running -> do nothing");
                return;
            }

            heartbeatExecutor_ = Executors.newSingleThreadScheduledExecutor();

            heartbeatFuture_ = heartbeatExecutor_.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void stopHeartbeat() {
        synchronized (heartbeatLock_) {
            if (Log.isDebug()) Log.d(TAG, "stopHeartbeat currentModeState_ " + currentModeState_);

            if (heartbeatFuture_ != null) {
                if (Log.isDebug()) Log.d(TAG, "stopHeartbeat cancel future");
                heartbeatFuture_.cancel(false);
                heartbeatFuture_ = null;
            }
            if (heartbeatExecutor_ != null) {
                if (Log.isDebug()) Log.d(TAG, "stopHeartbeat shutdown executor");
                heartbeatExecutor_.shutdownNow();
                heartbeatExecutor_ = null;
            }
        }
    }

    private void dumpStateMgr(StateMgrInfo info){
        if (Log.isDebug()) {
            if (info != null) {
                if (info.updateState != null) {
                    if (info.updateState.antitheftC) Log.d(TAG, "StateMgrChangeInfo updateState.antitheftC " + info.updateState.antitheftC);
                    if (info.updateState.audioAddressC) Log.d(TAG, "StateMgrChangeInfo updateState.audioAddressC " + info.updateState.audioAddressC);
                    if (info.updateState.audioLauncherC) Log.d(TAG, "StateMgrChangeInfo updateState.audioLauncherC " + info.updateState.audioLauncherC);
                    if (info.updateState.audioResumeCompletedC) Log.d(TAG, "StateMgrChangeInfo updateState.audioResumeCompletedC " + info.updateState.audioResumeCompletedC);
                    if (info.updateState.brightnessBarC) Log.d(TAG, "StateMgrChangeInfo updateState.brightnessBarC " + info.updateState.brightnessBarC);
                    if (info.updateState.dayNightStateC) Log.d(TAG, "StateMgrChangeInfo updateState.dayNightStateC " + info.updateState.dayNightStateC);
                    if (info.updateState.disclaimerC) Log.d(TAG, "StateMgrChangeInfo updateState.disclaimerC " + info.updateState.disclaimerC);
                    if (info.updateState.functionInfoC) Log.d(TAG, "StateMgrChangeInfo updateState.functionInfoC " + info.updateState.functionInfoC);
                    if (info.updateState.headerInterruptC) Log.d(TAG, "StateMgrChangeInfo updateState.headerInterruptC " + info.updateState.headerInterruptC);
                    if (info.updateState.hftPopupC) Log.d(TAG, "StateMgrChangeInfo updateState.hftPopupC " + info.updateState.hftPopupC);
                    if (info.updateState.hftStateC) Log.d(TAG, "StateMgrChangeInfo updateState.hftStateC " + info.updateState.hftStateC);
                    if (info.updateState.highTemperatureDetectionC) Log.d(TAG, "StateMgrChangeInfo updateState.highTemperatureDetectionC " + info.updateState.highTemperatureDetectionC);
                    if (info.updateState.iMidC) Log.d(TAG, "StateMgrChangeInfo updateState.iMidC " + info.updateState.iMidC);
                    if (info.updateState.inlineDiagC) Log.d(TAG, "StateMgrChangeInfo updateState.inlineDiagC " + info.updateState.inlineDiagC);
                    if (info.updateState.keyOffTimerAdvanceC) Log.d(TAG, "StateMgrChangeInfo updateState.keyOffTimerAdvanceC " + info.updateState.keyOffTimerAdvanceC);
                    if (info.updateState.keyOffTimerExpirationC) Log.d(TAG, "StateMgrChangeInfo updateState.keyOffTimerExpirationC " + info.updateState.keyOffTimerExpirationC);
                    if (info.updateState.languageC) Log.d(TAG, "StateMgrChangeInfo updateState.languageC " + info.updateState.languageC);
                    if (info.updateState.lastAudioAddressC) Log.d(TAG, "StateMgrChangeInfo updateState.lastAudioAddressC " + info.updateState.lastAudioAddressC);
                    if (info.updateState.lastVideoAddressC) Log.d(TAG, "StateMgrChangeInfo updateState.lastVideoAddressC " + info.updateState.lastVideoAddressC);
                    if (info.updateState.lcdAdjustStateC) Log.d(TAG, "StateMgrChangeInfo updateState.lcdAdjustStateC " + info.updateState.lcdAdjustStateC);
                    if (info.updateState.openingScreenC) Log.d(TAG, "StateMgrChangeInfo updateState.openingScreenC " + info.updateState.openingScreenC);
                    if (info.updateState.parkingSensorC) Log.d(TAG, "StateMgrChangeInfo updateState.parkingSensorC " + info.updateState.parkingSensorC);
                    if (info.updateState.rdsAlarmInterruptC) Log.d(TAG, "StateMgrChangeInfo updateState.rdsAlarmInterruptC " + info.updateState.rdsAlarmInterruptC);
                    if (info.updateState.rdsInterruptC) Log.d(TAG, "StateMgrChangeInfo updateState.rdsInterruptC " + info.updateState.rdsInterruptC);
                    if (info.updateState.screenOffC) Log.d(TAG, "StateMgrChangeInfo updateState.screenOffC " + info.updateState.screenOffC);
                    if (info.updateState.siriStateC) Log.d(TAG, "StateMgrChangeInfo updateState.siriStateC " + info.updateState.siriStateC);
                    if (info.updateState.sourceFlowC) Log.d(TAG, "StateMgrChangeInfo updateState.sourceFlowC " + info.updateState.sourceFlowC);
                    if (info.updateState.steeringDispKeyC) Log.d(TAG, "StateMgrChangeInfo updateState.steeringDispKeyC " + info.updateState.steeringDispKeyC);
                    if (info.updateState.steeringMenuC) Log.d(TAG, "StateMgrChangeInfo updateState.steeringMenuC " + info.updateState.steeringMenuC);
                    if (info.updateState.steeringPopUpC) Log.d(TAG, "StateMgrChangeInfo updateState.steeringPopUpC " + info.updateState.steeringPopUpC);
                    if (info.updateState.usbOvercurrentC) Log.d(TAG, "StateMgrChangeInfo updateState.usbOvercurrentC " + info.updateState.usbOvercurrentC);
                    if (info.updateState.usbPopupC) Log.d(TAG, "StateMgrChangeInfo updateState.usbPopupC " + info.updateState.usbPopupC);
                    if (info.updateState.videoAddressC) Log.d(TAG, "StateMgrChangeInfo updateState.videoAddressC " + info.updateState.videoAddressC);
                    if (info.updateState.videoResumeCompletedC) Log.d(TAG, "StateMgrChangeInfo updateState.videoResumeCompletedC " + info.updateState.videoResumeCompletedC);
                    if (info.updateState.voiceTagStateC) Log.d(TAG, "StateMgrChangeInfo updateState.voiceTagStateC " + info.updateState.voiceTagStateC);
                    if (info.updateState.volumePanelC) Log.d(TAG, "StateMgrChangeInfo updateState.volumePanelC " + info.updateState.volumePanelC);
                }
                Log.d(TAG, "StateMgrInfo info.antitheft " + info.antitheft);
                Log.d(TAG, "StateMgrInfo info.audioAddress " + info.audioAddress);
                Log.d(TAG, "StateMgrInfo info.audioLauncher " + info.audioLauncher);
                Log.d(TAG, "StateMgrInfo info.functionInfo " + info.functionInfo);
                Log.d(TAG, "StateMgrInfo info.brightnessBar " + info.brightnessBar);
                Log.d(TAG, "StateMgrInfo info.audioResumeCompleted " + info.audioResumeCompleted);
                Log.d(TAG, "StateMgrInfo info.dayNightState " + info.dayNightState);
                Log.d(TAG, "StateMgrInfo info.disclaimer " + info.disclaimer);
                Log.d(TAG, "StateMgrInfo info.headerInterrupt " + info.headerInterrupt);
                Log.d(TAG, "StateMgrInfo info.hftPopup " + info.hftPopup);
                Log.d(TAG, "StateMgrInfo info.hftState " + info.hftState);
                Log.d(TAG, "StateMgrInfo info.highTemperatureDetection " + info.highTemperatureDetection);
                Log.d(TAG, "StateMgrInfo info.iMid " + info.iMid);
                Log.d(TAG, "StateMgrInfo info.inlineDiag " + info.inlineDiag);
                Log.d(TAG, "StateMgrInfo info.keyOffTimerAdvance " + info.keyOffTimerAdvance);
                Log.d(TAG, "StateMgrInfo info.keyOffTimerExpiration " + info.keyOffTimerExpiration);
                Log.d(TAG, "StateMgrInfo info.language " + info.language);
                Log.d(TAG, "StateMgrInfo info.lastAudioAddress " + info.lastAudioAddress);
                Log.d(TAG, "StateMgrInfo info.lastVideoAddress " + info.lastVideoAddress);
                Log.d(TAG, "StateMgrInfo info.lcdAdjustState " + info.lcdAdjustState);
                Log.d(TAG, "StateMgrInfo info.openingScreen " + info.openingScreen);
                Log.d(TAG, "StateMgrInfo info.parkingSensor " + info.parkingSensor);
                Log.d(TAG, "StateMgrInfo info.rdsAlarmInterrupt " + info.rdsAlarmInterrupt);
                Log.d(TAG, "StateMgrInfo info.rdsInterrupt " + info.rdsInterrupt);
                Log.d(TAG, "StateMgrInfo info.screenOff " + info.screenOff);
                Log.d(TAG, "StateMgrInfo info.siriState " + info.siriState);
                Log.d(TAG, "StateMgrInfo info.sourceFlow " + info.sourceFlow);
                Log.d(TAG, "StateMgrInfo info.steeringDispKey " + info.steeringDispKey);
                Log.d(TAG, "StateMgrInfo info.steeringMenu " + info.steeringMenu);
                Log.d(TAG, "StateMgrInfo info.steeringPopUp " + info.steeringPopUp);
                Log.d(TAG, "StateMgrInfo info.usbOvercurrent " + info.usbOvercurrent);
                Log.d(TAG, "StateMgrInfo info.usbPopup " + info.usbPopup);
                Log.d(TAG, "StateMgrInfo info.videoAddress " + info.videoAddress);
                Log.d(TAG, "StateMgrInfo info.videoResumeCompleted " + info.videoResumeCompleted);
                Log.d(TAG, "StateMgrInfo info.voiceTagState " + info.voiceTagState);
                Log.d(TAG, "StateMgrInfo info.volumePanel " + info.volumePanel);
            }
        }
    }

    public boolean isNight(){
        StateMgrInfo currentState = stateMgrManager_.getAllState();
        if (currentState != null) {
            return currentState.dayNightState == StateMgrServiceConst.STATE_NIGHT;
        }

        return false;
    }

    public void setDayNightListener(ISensor.Listener dayNightListener){
        dayNightListener_ = dayNightListener;
    }

    private void printToast(String message){
        mainHandler_.post(() -> {
            Toast.makeText(context_, message, Toast.LENGTH_SHORT).show();
        });
    }

    // ---- Steering wheel keys

    private SteeringKeyRouter.Result onSteeringKey(SteeringKeyRouter.Source source, int first, int second) {
        final SteeringKeyRouter.Result result = steeringKeys_.onKey(source, first, second);
        keyEvents_.add(result.toString());
        if (Log.isInfo()) Log.i(TAG, "steering key " + result);
        return result;
    }

    /** Injects a key into this app's window, which is how the key map screen learns keys. */
    private boolean injectKey(int keyCode) {
        if (!inForeground_) {
            // The focused window belongs to another app: it must not get it.
            return false;
        }
        try {
            new Instrumentation().sendKeyDownUpSync(keyCode);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not inject key " + keyCode, e);
            return false;
        }
    }

    // ---- Head unit state, the return to the projection and the overlay

    /**
     * True when the head unit plays another source (radio, DAB, CD, USB...):
     * anything but nothing (MODE_LADDR_OFF) or the address this app plays on.
     * Also true when the state cannot be read, so nothing is taken from it.
     */
    private boolean otherSourcePlaying() {
        if (stateMgrManager_ == null) {
            return true;
        }
        try {
            StateMgrInfo info = stateMgrManager_.getAllState();
            if (info == null) {
                return true;
            }
            int audio = info.audioAddress;
            return audio != MODE_LADDR_OFF && audio != settings_.advanced.modeMgrAudioVideoIdx();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the head unit audio source", e);
            return true;
        }
    }

    private void refreshHeadUnitState() {
        if (stateMgrManager_ == null) {
            return;
        }
        try {
            StateMgrInfo info = stateMgrManager_.getAllState();
            if (info != null) {
                onHeadUnitState(info, null);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the head unit state", e);
        }
    }

    /** A state manager report; changed == null takes every field (a full read). */
    private void onHeadUnitState(StateMgrInfo info, StateMgrChangeInfo changed) {
        synchronized (headUnitLock_) {
            if (changed == null || changed.siriStateC) siriState_ = noteState("siri", siriState_, info.siriState);
            if (changed == null || changed.voiceTagStateC) voiceTagState_ = noteState("voice", voiceTagState_, info.voiceTagState);
            if (changed == null || changed.hftStateC) hftState_ = noteState("call", hftState_, info.hftState);
            if (changed == null || changed.hftPopupC) hftPopup_ = noteState("popup", hftPopup_, info.hftPopup);
            if (changed == null || changed.videoAddressC) videoAddress_ = noteState("v", videoAddress_, info.videoAddress);
            if (changed == null || changed.audioAddressC) audioAddress_ = noteState("a", audioAddress_, info.audioAddress);

            returnPolicy_.onHeadUnitState(
                    siriState_ == StateMgrServiceConst.STATE_SIRI_ON,
                    voiceTagState_ == StateMgrServiceConst.STATE_VOICETAG_ON,
                    hftState_ > 0 || hftPopup_ > 0,
                    videoAddress_);
        }
    }

    private int noteState(String name, int previous, int value) {
        // Changes only: the first read is the starting point, not an event.
        if (previous != -1 && previous != value) {
            headUnitEvents_.add(name + " " + value);
            if (Log.isInfo()) Log.i(TAG, "head unit " + name + " " + previous + " -> " + value);
        }
        return value;
    }

    private void bringProjectionToFront(String cause) {
        if (!isRunning_) {
            return;
        }
        headUnitEvents_.add("back to AA (" + cause + ")");
        if (Log.isInfo()) Log.i(TAG, "bringing the projection back after " + cause);
        try {
            Intent i = new Intent(context_, PlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context_.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not bring the projection back", e);
        }
    }

    /**
     * Three lines for the statistics overlay: the head unit state and what
     * this app does with it, the last steering wheel keys (newest first, with
     * the callback they came on and where they went), the last head unit
     * events.
     */
    public String steeringStatus() {
        final StringBuilder text = new StringBuilder("hu ");
        synchronized (headUnitLock_) {
            text.append('a').append(audioAddress_).append(" v").append(videoAddress_);
            if (siriState_ == StateMgrServiceConst.STATE_SIRI_ON) text.append(" siri");
            if (voiceTagState_ == StateMgrServiceConst.STATE_VOICETAG_ON) text.append(" voice");
            if (hftState_ > 0) text.append(" call").append(hftState_);
            if (hftPopup_ > 0) text.append(" popup");
        }
        text.append(" | src ").append(!hasAudioFocus_ ? "-" : mediaPlaying_ ? "playing" : "kept");
        text.append(" | keys ").append(modeMgrServiceSWKeyEventCallBack_ != null ? "on" : "off");
        text.append(" | back ").append(returnPolicy_.describe());
        text.append(" | mic ").append(!settings_.advanced.hondaMicVrEnabled() ? "off"
                : settings_.advanced.useBtMicVr() ? "bt" : "ecnc");
        text.append(" | bt-guard ").append(voiceGuardStatus_);
        final String keys = keyEvents_.joined();
        text.append("\nhu-keys ").append(keys.isEmpty() ? "-" : keys);
        final String events = headUnitEvents_.joined();
        text.append("\nhu-evt ").append(events.isEmpty() ? "-" : events);
        return text.toString();
    }

    // ---- Bluetooth voice: what the head unit does, and the optional guard

    // From the head unit framework: SiriAtCommandListener.ACTION_SIRI_STATUS,
    // EXTRA_SIRI_STATUS, SIRI_ACTIVE, SIRI_NOT_ACTIVE; BluetoothVr
    // .ACTION_BVRA_START, API_OK.
    private static final String ACTION_SIRI_STATUS = "com.fujitsu_ten.displayaudio.bluetooth.handsfree.action.SIRI_STATUS";
    private static final String EXTRA_SIRI_STATUS = "com.fujitsu_ten.displayaudio.bluetooth.handsfree.extra.SIRI_STATUS";
    private static final int SIRI_ACTIVE = 1;
    private static final int SIRI_NOT_ACTIVE = 2;
    private static final String ACTION_BVRA_START = "com.fujitsu_ten.displayaudio.bluetooth.vr.action.ACTION_BVRA_START";
    private static final int BT_VR_API_OK = 0;

    /** Notes when the head unit reports Siri over Bluetooth or a voice recognition start. Diagnostics only. */
    private void registerBtVoiceReceiver() {
        synchronized (btVoiceReceiverLock_) {
            if (btVoiceReceiver_ != null) {
                return;
            }
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String event;
                    if (ACTION_SIRI_STATUS.equals(intent.getAction())) {
                        final int status = intent.getIntExtra(EXTRA_SIRI_STATUS, -1);
                        event = "bt-siri " + (status == SIRI_ACTIVE ? "on" : status == SIRI_NOT_ACTIVE ? "off" : String.valueOf(status));
                    } else {
                        event = "bvra";
                    }
                    headUnitEvents_.add(event);
                    if (Log.isInfo()) Log.i(TAG, "bluetooth voice: " + event);
                }
            };
            final IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SIRI_STATUS);
            filter.addAction(ACTION_BVRA_START);
            try {
                context_.registerReceiver(receiver, filter);
                btVoiceReceiver_ = receiver;
            } catch (RuntimeException e) {
                Log.w(TAG, "could not listen for the Bluetooth voice status", e);
            }
        }
    }

    private void unregisterBtVoiceReceiver() {
        synchronized (btVoiceReceiverLock_) {
            if (btVoiceReceiver_ == null) {
                return;
            }
            try {
                context_.unregisterReceiver(btVoiceReceiver_);
            } catch (RuntimeException e) {
                Log.w(TAG, "Bluetooth voice receiver was not registered", e);
            }
            btVoiceReceiver_ = null;
        }
    }

    /**
     * Advanced > Honda, experimental and off by default: while the projection
     * is on screen, hold the Bluetooth voice control rights, in case the head
     * unit needs them to start Siri over Bluetooth hands-free when it handles
     * Talk itself. Not used with the Bluetooth microphone, which needs the
     * rights for its own voice session. Runs on the mode manager thread.
     */
    private void updateVoiceGuard(boolean visible) {
        final boolean enabled = settings_.advanced.blockHondaBtVoice();
        final boolean btMic = settings_.advanced.useBtMicVr();
        final boolean wanted = visible && isRunning_ && enabled && !btMic;
        synchronized (micLock_) {
            if (wanted == voiceRightsHeld_) {
                if (!wanted) {
                    voiceGuardStatus_ = !enabled ? "off" : btMic ? "off: bt mic" : "idle";
                }
                return;
            }
            try {
                final BluetoothVr vr = BluetoothVr.getInstance();
                if (vr == null) {
                    voiceGuardStatus_ = "no bt vr";
                    return;
                }
                if (voiceGuardListener_ == null) {
                    voiceGuardListener_ = new VoiceGuardListener();
                }
                if (wanted) {
                    final int ret = vr.getVoiceControlRights(voiceGuardListener_);
                    voiceRightsHeld_ = ret == BT_VR_API_OK;
                    voiceGuardStatus_ = (voiceRightsHeld_ ? "holding" : "refused") + " ret " + ret;
                } else {
                    final int ret = vr.releaseVoiceControlRights(voiceGuardListener_);
                    voiceRightsHeld_ = false;
                    voiceGuardStatus_ = "released ret " + ret;
                }
            } catch (Throwable t) {
                // Firmware without the call, or a service that refuses it.
                voiceRightsHeld_ = false;
                voiceGuardStatus_ = "failed: " + t.getClass().getSimpleName();
                Log.w(TAG, "Bluetooth voice guard", t);
            }
            headUnitEvents_.add("bt-guard " + voiceGuardStatus_);
            if (Log.isInfo()) Log.i(TAG, "Bluetooth voice guard: " + voiceGuardStatus_);
        }
    }

    private final class VoiceGuardListener implements BluetoothVrListener {

        @Override
        public void onErrorOccurred(int type, int code) {
            note("error " + type + "/" + code);
        }

        @Override
        public void onRequestSamplingRateChange(int rate) {
            // Whoever holds the rights confirms the change, or the audio link
            // waits for it.
            try {
                BluetoothVr vr = BluetoothVr.getInstance();
                if (vr != null) {
                    vr.completeSamplingRateChange(rate);
                }
            } catch (Throwable t) {
                Log.w(TAG, "completeSamplingRateChange", t);
            }
            note("rate " + rate);
        }

        @Override
        public void onScoChanged(int scoStatus, int samplingRate) {
            note("sco " + scoStatus);
        }

        @Override
        public void onVoiceControlRightsNotification(int value) {
            note("rights " + value);
        }

        @Override
        public void onVoiceControlRightsResponse(int value) {
            note("rights resp " + value);
        }

        private void note(String event) {
            headUnitEvents_.add("bt-guard " + event);
            if (Log.isInfo()) Log.i(TAG, "Bluetooth voice guard: " + event);
        }
    }

    private class ModeMgrServiceSWKeyEventCallBack extends IModeMgrServiceSWKeyEventCallBack.Stub {

        private static final String TAG = "HondaConnectManager-ModeMgrServiceSWKeyEventCallBack";

        @Override
        public void rcvStrgKeyEvent(int i, int i1) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "rcvStrgKeyEvent  " + i + "/" + i1);
            onSteeringKey(SteeringKeyRouter.Source.KEY_EVENT, i, i1);
        }
    };

    private class ModeMgrInspectionCallBack extends IModeMgrInspectionCallBack.Stub {

        private static final String TAG = "HondaConnectManager-ModeMgrInspectionCallBack";

        @Override
        public void rcvKeyInspecCmd(int i, int i1) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "rcvKeyInspecCmd  " + i + "/" + i1);
            onSteeringKey(SteeringKeyRouter.Source.INSPECTION, i, i1);
        }
    }

    private class ModeMgrServiceSWSmartPhoneCallBack extends IModeMgrServiceSWSmartPhoneCallBack.Stub {
        private static final String TAG = "HondaConnectManager-ModeMgrServiceSWSmartPhoneCallBack";

        @Override
        public void rcvStrgKeyCmd(int i) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "rcvStrgKeyCmd  " + i);
            onSteeringKey(SteeringKeyRouter.Source.PHONE, i, 0);
        }
    };

    private class SteeringMenuServiceCallback extends ISteeringMenuServiceCallback.Stub {
        private static final String TAG = "HondaConnectManager-SteeringMenuServiceCallback";

        public void onShowView() {
            if (Log.isDebug()) Log.d(TAG, "onShowView isRunning_ " + isRunning_);
            if (isRunning_) {
                notifySteeringMenuDispMode(1);
            }
        }

        public boolean onFinishView(boolean flg, boolean anime) {
            if (Log.isDebug()) Log.d(TAG, "onFinishView");
            return true;
        }

        public boolean onSteeringSWDown(int keytype) {
            if (Log.isDebug()) Log.d(TAG, "onSteeringSWDown " + keytype);
            // True tells the steering menu service the key was used; keys
            // Android Auto has no use for stay with the head unit.
            return onSteeringKey(SteeringKeyRouter.Source.STEERING_MENU, keytype, 0).consumed();
        }
    };

    private class StateMgrServiceCallBack extends IStateMgrServiceCallBack.Stub {
        private static final String TAG = "HondaConnectManager-StateMgrServiceCallBack";

        public void onChangeState(StateMgrInfo info) {
            if (Log.isDebug()) Log.d(TAG, "onChangeState");

            StateMgrChangeInfo changed = info.updateState;
            if (changed == null) return;

            dumpStateMgr(info);

            if (changed.videoAddressC || changed.audioAddressC || changed.siriStateC
                    || changed.voiceTagStateC || changed.hftStateC || changed.hftPopupC) {
                onHeadUnitState(info, changed);
            }

            if (changed.dayNightStateC) {
                boolean isNight = info.dayNightState == StateMgrServiceConst.STATE_NIGHT;
                if (Log.isDebug()) Log.d(TAG, "DayNight state changed, isNight: " + isNight);
                if (dayNightListener_ != null){
                    dayNightListener_.onDayNightUpdate(isNight);
                }
            }
        }
    };

    private class ModeMgrServiceVideoCallBack extends IModeMgrServiceCallBack.Stub {

        private static final String TAG = "HondaConnectManager-ModeMgrServiceVideoCallBack";

        public void rcvOnInsCmd(int modestate) {
            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd modestate = " + modestate);
            int idx = settings_.advanced.modeMgrVideoIdx();

            int sound_param = modestate & ModeMgrMode.NOTIFY_AUDIO;
            int image_param = modestate & ModeMgrMode.NOTIFY_VIDEO;
            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sound_param= " + sound_param + " image_param= " + image_param);

            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sendModeMgrOnCnf idx= " + idx + ",state = " + modestate);
            int ret = modeMgrManager_.sendModeMgrOnCnf(idx, modestate);
            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sendModeMgrOnCnf ret = " + ret);

            if (image_param != 0){
                if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sendModeMgrCompDisp idx= " + idx);
                modeMgrManager_.sendModeMgrCompDisp(idx, 1);
            }

            // Aggiorna il modestate e avvia/aggiorna il heartbeat
            if (currentIdx_ == idx){
                currentModeState_ |= modestate;
            } else {
                currentModeState_ = modestate;
            }

            hasAudioFocus_ = (currentModeState_ & ModeMgrMode.NOTIFY_AUDIO) != 0;
            currentIdx_ = idx;

            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd currentModeState_ = " + currentModeState_ + ", currentIdx_= " + currentIdx_ + " hasAudioFocus_= " + hasAudioFocus_);

            startHeartbeat();

            CountDownLatch onLatch = waitCondOn_;
            if (onLatch != null) {
                if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd notify waitCondOn");
                onLatch.countDown();
            }

        }

        public void rcvOffInsCmd(int modestate) {
            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd modestate = " + modestate);

            int sound_param = modestate & ModeMgrMode.NOTIFY_AUDIO;
            int image_param = modestate & ModeMgrMode.NOTIFY_VIDEO;
            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd sound_param= " + sound_param + " image_param= " + image_param);

            int idx = settings_.advanced.modeMgrVideoIdx();
            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd sendModeMgrOffCnf idx= " + idx + ", state = " + modestate);
            int ret = modeMgrManager_.sendModeMgrOffCnf(idx, modestate);
            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd sendModeMgrOffCnf ret = " + ret);

            // Rimuovi i bit revocati dallo stato corrente
            if (currentIdx_ == idx) {
                currentModeState_ &= ( (~modestate) + 4) ;
                hasAudioFocus_ = (currentModeState_ & ModeMgrMode.NOTIFY_AUDIO) != 0;
            } else {
                if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd currentIdx_ <> idx");
            }

            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd hasAudioFocus_ " + hasAudioFocus_ + ", currentModeState_ = " + currentModeState_ + ", currentIdx_= " + currentIdx_);
            if (currentModeState_ == 0) {
                if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd currentModeState_ == 0");
                stopHeartbeat();
                currentIdx_ = 255;
            } else {
                startHeartbeat();
            }

            CountDownLatch offLatch = waitCondOff_;
            if (offLatch != null) {
                if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd notify waitCondOff");
                offLatch.countDown();
            }
        }

        public void rcvOnReqCmdFailed(int audioaddr, int videoaddr, int reason) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "rcvOnReqCmdFailed audioaddr = " + audioaddr + " , videoaddr = " + videoaddr + " , reason = " + reason);
            CountDownLatch onLatch = waitCondOn_;
            if (onLatch != null) {
                onLatch.countDown();
            }
        }

        public void rcvVideoPwrCmd(int addr) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "rcvVideoPwrCmd  addr = " + addr);
        }

        public void rcvAudioPwrONCmd(int addr) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "rcvAudioPwrONCmd  addr = " + addr);
        }

        public void rcvAudioPwrOFFCmd() throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "rcvAudioPwrOFFCmd -S");
        }

        public void insDispApl(int disp, int extInfo1, int extInfo2) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "insDispApl " + disp + "/" + extInfo1 + "/" + extInfo2);
        }
    };

    private class ModeMgrServiceVideoAudioCallBack extends IModeMgrServiceCallBack.Stub {

        private static final String TAG = "HondaConnectManager-ModeMgrServiceVideoAudioCallBack";

        public void rcvOnInsCmd(int modestate) {
            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd modestate = " + modestate);
            int idx = settings_.advanced.modeMgrAudioVideoIdx();

            int sound_param = modestate & ModeMgrMode.NOTIFY_AUDIO;
            int image_param = modestate & ModeMgrMode.NOTIFY_VIDEO;
            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sound_param= " + sound_param + " image_param= " + image_param);

            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sendModeMgrOnCnf idx= " + idx + ",state = " + modestate);
            int ret = modeMgrManager_.sendModeMgrOnCnf(idx, modestate);
            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sendModeMgrOnCnf ret = " + ret);

            if (image_param != 0){
                if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd sendModeMgrCompDisp idx= " + idx);
                modeMgrManager_.sendModeMgrCompDisp(idx, 1);
            }

            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd iAudioAddr = " + modeMgrManager_.getModeMgrOnAudioAddr());
            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd iVideoAddr = " + modeMgrManager_.getModeMgrOnVideoAddr());

            // Aggiorna il modestate e avvia/aggiorna il heartbeat
            if (currentIdx_ == idx){
                currentModeState_ |= modestate;
            } else {
                currentModeState_ = modestate;
            }

            hasAudioFocus_ = (currentModeState_ & ModeMgrMode.NOTIFY_AUDIO) != 0;
            currentIdx_ = idx;

            if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd currentModeState_ = " + currentModeState_ + ", currentIdx_= " + currentIdx_ + " hasAudioFocus_= " + hasAudioFocus_);

            startHeartbeat();

            CountDownLatch onLatch = waitCondOn_;
            if (onLatch != null) {
                if (Log.isDebug()) Log.d(TAG, "rcvOnInsCmd notify waitCondOn");
                onLatch.countDown();
            }

        }

        public void rcvOffInsCmd(int modestate) {
            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd modestate = " + modestate);

            int idx = settings_.advanced.modeMgrAudioVideoIdx();

            int sound_param = modestate & ModeMgrMode.NOTIFY_AUDIO;
            int image_param = modestate & ModeMgrMode.NOTIFY_VIDEO;

            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd sound_param= " + sound_param + " image_param= " + image_param);

            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd sendModeMgrOffCnf idx= " + idx + ", state = " + modestate);
            int ret = modeMgrManager_.sendModeMgrOffCnf(idx, modestate);
            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd sendModeMgrOffCnf ret = " + ret);

            // Rimuovi i bit revocati dallo stato corrente
            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd currentIdx_ = " + currentIdx_ + ", idx= " + idx);
            if (currentIdx_ == idx) {
                currentModeState_ &= ( (~modestate) + 4) ;
                hasAudioFocus_ = (currentModeState_ & ModeMgrMode.NOTIFY_AUDIO) != 0;
            } else {
                if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd currentIdx_ <> idx");
            }

            if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd hasAudioFocus_ " + hasAudioFocus_ + ", currentModeState_ = " + currentModeState_ + ", currentIdx_= " + currentIdx_);
            if (currentModeState_ == 0) {
                if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd currentModeState_ == 0");
                stopHeartbeat();
                currentIdx_ = 255;
            } else {
                startHeartbeat();
            }

            CountDownLatch offLatch = waitCondOff_;
            if (offLatch != null) {
                if (Log.isDebug()) Log.d(TAG, "rcvOffInsCmd notify waitCondOff");
                offLatch.countDown();
            }
        }

        public void rcvOnReqCmdFailed(int audioaddr, int videoaddr, int reason) {
            if (Log.isDebug()) Log.d(TAG, "rcvOnReqCmdFailed audioaddr = " + audioaddr + " , videoaddr = " + videoaddr + " , reason = " + reason);
            CountDownLatch onLatch = waitCondOn_;
            if (onLatch != null) {
                onLatch.countDown();
            }
        }

        public void rcvVideoPwrCmd(int addr) {
            if (Log.isDebug()) Log.d(TAG, "rcvVideoPwrCmd  addr = " + addr);
        }

        public void rcvAudioPwrONCmd(int addr) {
            if (Log.isDebug()) Log.d(TAG, "rcvAudioPwrONCmd  addr = " + addr);
        }

        public void rcvAudioPwrOFFCmd() {
            if (Log.isDebug()) Log.d(TAG, "rcvAudioPwrOFFCmd -S");
        }

        public void insDispApl(int disp, int extInfo1, int extInfo2) {
            if (Log.isDebug()) Log.d(TAG, "insDispApl " + disp + "/" + extInfo1 + "/" + extInfo2);
        }
    };

    private final class BTVrListener implements BluetoothVrListener {

        private static final String TAG = "HondaConnectManager-BTVrListener";

        @Override
        public void onErrorOccurred(int i, int i1) {
            if (Log.isWarn()) Log.w(TAG, "onErrorOccurred " + i + "/" + i1);
        }

        @Override
        public void onRequestSamplingRateChange(int rate) {
            if (Log.isDebug()) Log.d(TAG, "onRequestSamplingRateChange rate= " + rate);
            bluetoothVR_.completeSamplingRateChange(rate);
        }

        @Override
        public void onScoChanged(int scoStatus, int samplingRate) {
            if (Log.isDebug()) Log.d(TAG, "onScoChanged scoStatus= " + scoStatus + ", samplingRate= " + samplingRate);
        }

        @Override
        public void onVoiceControlRightsNotification(int i) {
            if (Log.isDebug()) Log.d(TAG, "onVoiceControlRightsNotification " + i);
        }

        @Override
        public void onVoiceControlRightsResponse(int i) {
            if (Log.isDebug()) Log.d(TAG, "onVoiceControlRightsResponse " + i);
            if (i == 1){
               stopMicSession();
            }
        }
    }

}
