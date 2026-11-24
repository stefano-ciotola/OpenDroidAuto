package it.smg.hu.ui.main;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.ConnectionManager;
import it.smg.hu.manager.ConnectionState;
import it.smg.hu.manager.USBManager;
import it.smg.hu.manager.WIFIManager;
import it.smg.hu.service.ODAService;
import it.smg.hu.ui.MainActivity;
import it.smg.hu.ui.SettingsActivity;
import it.smg.hu.ui.Ui;
import it.smg.hu.ui.widget.GlyphView;

/**
 * The stationary connection screen: the state in words on the left (with
 * USB, Wi-Fi and video like an instrument readout) and a start panel that
 * stays in the same place on the right. The panel is a dashed placeholder
 * until a start is possible, so the action always appears where the eye
 * already is. Starting a session always remains a deliberate tap.
 */
public class HomeFragment extends Fragment {

    private enum Panel { WAITING, READY, BUSY, ACTIVE, RECOVER, RETRY }

    private View root_;
    private TextView homeTitle_;
    private TextView homeVersion_;
    private TextView statusTitle_;
    private TextView statusDetail_;
    private TextView usbStatus_;
    private TextView wifiStatus_;
    private TextView videoStatus_;
    private View usbDot_;
    private View startPanel_;
    private GlyphView startGlyph_;
    private TextView startTitle_;
    private TextView startDetail_;
    private Button settingsButton_;
    private Button exitButton_;
    private final View[] dividers_ = new View[3];
    private final TextView[] rowLabels_ = new TextView[3];

    private USBManager usbManager_;
    private WIFIManager wifiManager_;
    private LocalBroadcastManager broadcasts_;
    private Ui.Palette palette_;
    private ConnectionState renderedState_;
    private String renderedMode_;
    private Panel renderedPanel_;
    private boolean usbPresent_;
    private ObjectAnimator busySpin_;

    private final BroadcastReceiver receiver_ = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ConnectionManager.ACTION_STATE_CHANGED.equals(action)) {
                String name = intent.getStringExtra(ConnectionManager.EXTRA_STATE);
                ConnectionState state = name == null ? ConnectionState.IDLE : ConnectionState.valueOf(name);
                renderState(state, intent.getStringExtra(ConnectionManager.EXTRA_MESSAGE),
                        intent.getStringExtra(ConnectionManager.EXTRA_MODE));
            } else if (USBManager.ATTACH_AOAP_DEVICE.equals(action)) {
                usbPresent_ = true;
                renderUsb();
            } else if (USBManager.DETACH_AOAP_DEVICE.equals(action)) {
                usbPresent_ = false;
                renderUsb();
            } else if (WIFIManager.CONNECT_WIFI.equals(action)) {
                String ssid = intent.getStringExtra(WIFIManager.EXTRA_SSID);
                String localIp = intent.getStringExtra(WIFIManager.EXTRA_LOCAL_IP);
                int port = intent.getIntExtra(WIFIManager.EXTRA_PORT, WIFIManager.DEFAULT_SERVER_PORT);
                if (localIp != null && !localIp.isEmpty()) {
                    String hostLabel = (ssid != null && !ssid.isEmpty()) ? ssid : localIp;
                    wifiStatus_.setText(getString(R.string.home_value_wifi_listening, hostLabel, localIp, port));
                } else {
                    wifiStatus_.setText(getString(R.string.home_value_wifi_ready, ssid == null ? "" : ssid));
                }
            } else if (WIFIManager.DISCONNECT_WIFI.equals(action)) {
                renderWifiAvailability();
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.home_fragment, container, false);
        // A new view: forget what the previous one showed (no fade on rebuild,
        // and the busy animation must target the new glyph).
        renderedState_ = null;
        renderedPanel_ = null;
        busySpin_ = null;
        usbManager_ = USBManager.instance();
        wifiManager_ = WIFIManager.instance();
        broadcasts_ = LocalBroadcastManager.getInstance(requireContext());

        root_ = view.findViewById(R.id.homeRoot);
        homeTitle_ = view.findViewById(R.id.homeTitle);
        homeVersion_ = view.findViewById(R.id.homeVersion);
        statusTitle_ = view.findViewById(R.id.statusTitle);
        statusDetail_ = view.findViewById(R.id.statusDetail);
        usbStatus_ = view.findViewById(R.id.usbStatus);
        wifiStatus_ = view.findViewById(R.id.wifiStatus);
        videoStatus_ = view.findViewById(R.id.videoStatus);
        usbDot_ = view.findViewById(R.id.usbDot);
        startPanel_ = view.findViewById(R.id.startPanel);
        startGlyph_ = view.findViewById(R.id.startGlyph);
        startTitle_ = view.findViewById(R.id.startTitle);
        startDetail_ = view.findViewById(R.id.startDetail);
        settingsButton_ = view.findViewById(R.id.settingsBtn);
        exitButton_ = view.findViewById(R.id.exitBtn);
        dividers_[0] = view.findViewById(R.id.rowDivider1);
        dividers_[1] = view.findViewById(R.id.rowDivider2);
        dividers_[2] = view.findViewById(R.id.rowDivider3);
        rowLabels_[0] = view.findViewById(R.id.usbLabel);
        rowLabels_[1] = view.findViewById(R.id.wifiLabel);
        rowLabels_[2] = view.findViewById(R.id.videoLabel);

        settingsButton_.setOnClickListener(v ->
                startActivityForResult(new Intent(getContext(), SettingsActivity.class), MainActivity.SETTINGS_ACTIVITY_REQUEST));
        exitButton_.setOnClickListener(v -> ((MainActivity) requireActivity()).exitApplication());
        startPanel_.setOnClickListener(v -> retryConnection());
        Ui.pressFeedback(settingsButton_);
        Ui.pressFeedback(exitButton_);
        Ui.pressFeedback(startPanel_);

        homeVersion_.setText(versionName());
        usbPresent_ = usbManager_ != null && usbManager_.aoapDevice() != null;
        applyTheme();
        renderVideo();
        renderWifiAvailability();
        renderState(ConnectionManager.instance().state(), ConnectionManager.instance().lastMessage(),
                ConnectionManager.instance().mode());
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectionManager.ACTION_STATE_CHANGED);
        filter.addAction(USBManager.ATTACH_AOAP_DEVICE);
        filter.addAction(USBManager.DETACH_AOAP_DEVICE);
        filter.addAction(WIFIManager.CONNECT_WIFI);
        filter.addAction(WIFIManager.DISCONNECT_WIFI);
        broadcasts_.registerReceiver(receiver_, filter);
        // State changes can happen while PlayerActivity covers this fragment.
        // Render the current snapshot on return so DISCONNECTING and its
        // completion are visible even if their broadcasts were missed.
        if (usbManager_ != null && usbManager_.aoapDevice() != null) {
            usbPresent_ = true;
        }
        renderVideo();
        renderWifiAvailability();
        renderState(ConnectionManager.instance().state(), ConnectionManager.instance().lastMessage(),
                ConnectionManager.instance().mode());
    }

    @Override
    public void onStop() {
        broadcasts_.unregisterReceiver(receiver_);
        // Never keep anything animating behind the projection.
        stopBusySpin();
        super.onStop();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.SETTINGS_ACTIVITY_REQUEST) {
            applyTheme();
            renderVideo();
            renderWifiAvailability();
            if (wifiManager_ != null) {
                wifiManager_.checkNetwork();
            }
        }
    }

    private void retryConnection() {
        String mode = ConnectionManager.instance().mode();
        if (ODAService.MODE_USB.equals(mode) && usbManager_ != null
                && (renderedState_ == ConnectionState.ERROR || usbManager_.aoapDevice() == null)) {
            // Recovery only prepares a fresh USB transport.  Starting a new
            // PlayerActivity before that preparation completes races the prior
            // TLS teardown and produces SSL_READ(5).
            usbManager_.recoverConnection();
        } else if (renderedState_ == ConnectionState.ERROR && mode != null) {
            // Move a Wi-Fi retry back to a startable state explicitly. ERROR is
            // otherwise blocked by ConnectionPolicy to prevent accidental
            // overlapping sessions from widgets or duplicate taps.
            ConnectionManager.instance().prepareRetry(mode,
                    ODAService.MODE_WIFI.equals(mode)
                            ? getString(R.string.connection_wifi_connecting)
                            : getString(R.string.connection_usb_connecting));
            if (ODAService.MODE_WIFI.equals(mode) && wifiManager_ != null
                    && !wifiManager_.hasPendingAcceptedSocket()) {
                wifiManager_.triggerWirelessStart();
            }
            ((MainActivity) requireActivity()).startConnectionManually(mode);
        } else if (mode != null && (!ODAService.MODE_USB.equals(mode)
                || (usbManager_ != null && usbManager_.aoapDevice() != null))) {
            if (ODAService.MODE_WIFI.equals(mode) && wifiManager_ != null
                    && !wifiManager_.hasPendingAcceptedSocket()) {
                wifiManager_.triggerWirelessStart();
            }
            ((MainActivity) requireActivity()).startConnectionManually(mode);
        }
    }

    private void renderWifiAvailability() {
        if (!Settings.instance().advanced.enableWiFi()) {
            wifiStatus_.setText(R.string.home_value_wifi_off);
            return;
        }
        if (wifiManager_ != null && wifiManager_.getLocalIpAddress() != null) {
            String localIp = wifiManager_.getLocalIpAddress();
            String ssid = wifiManager_.getNetworkSsid();
            String hostLabel = (ssid != null && !ssid.isEmpty()) ? ssid : localIp;
            wifiStatus_.setText(getString(R.string.home_value_wifi_listening,
                    hostLabel, localIp, wifiManager_.getServerPort()));
            return;
        }
        wifiStatus_.setText(R.string.home_value_wifi_waiting);
    }

    /** What Android Auto will be asked for, e.g. "480p · 30 fps". */
    private void renderVideo() {
        String[] resolutions = getResources().getStringArray(R.array.video_resolution_labels);
        String[] fps = getResources().getStringArray(R.array.video_fps_labels);
        int r = Settings.instance().video.resolution() - 1;
        int f = Settings.instance().video.fps() - 1;
        videoStatus_.setText(getString(R.string.home_value_video,
                resolutions[Math.max(0, Math.min(resolutions.length - 1, r))],
                fps[Math.max(0, Math.min(fps.length - 1, f))]));
    }

    private void applyTheme() {
        Context context = requireContext();
        palette_ = Ui.palette(context);
        Ui.Palette p = palette_;
        root_.setBackgroundColor(p.bg);
        homeTitle_.setTextColor(p.text);
        homeVersion_.setTextColor(p.muted);
        statusTitle_.setTextColor(p.text);
        statusDetail_.setTextColor(p.body);
        usbStatus_.setTextColor(p.text);
        wifiStatus_.setTextColor(p.text);
        videoStatus_.setTextColor(p.text);
        for (TextView label : rowLabels_) {
            label.setTextColor(p.muted);
        }
        for (View divider : dividers_) {
            divider.setBackgroundColor(p.hairline);
        }
        styleQuietButton(settingsButton_);
        styleQuietButton(exitButton_);
        if (renderedState_ != null) {
            // Same state, new colours: no fade.
            renderState(renderedState_, ConnectionManager.instance().lastMessage(), renderedMode_);
        }
    }

    @SuppressWarnings("deprecation")
    private void styleQuietButton(Button button) {
        button.setBackgroundDrawable(Ui.quietButton(requireContext(), palette_, 12));
        button.setTextColor(palette_.text);
    }

    private void renderState(ConnectionState state, String message, String mode) {
        ConnectionState previous = renderedState_;
        renderedState_ = state;
        renderedMode_ = mode;
        boolean loading = isLoading(state);
        boolean manualStartAvailable = state != ConnectionState.ERROR && mode != null
                && ConnectionManager.instance().isManualStartAllowed();
        String detail = state == ConnectionState.ERROR ? message : null;
        int title;

        switch (state) {
            case PERMISSION_PENDING:
                title = R.string.home_permission_title;
                detail = fallback(detail, R.string.home_permission_detail);
                break;
            case AOAP_SWITCHING:
                title = R.string.home_connecting_title;
                detail = fallback(detail, R.string.home_aoap_detail);
                break;
            case CONNECTING:
                title = R.string.home_connecting_title;
                detail = fallback(detail, R.string.home_connecting_detail);
                break;
            case DISCONNECTING:
                title = R.string.home_stopping_title;
                detail = fallback(detail, R.string.home_stopping_detail);
                break;
            case ACTIVE:
                title = R.string.home_connected_title;
                detail = fallback(detail, R.string.home_connected_detail);
                break;
            case EXITED:
                title = R.string.home_stopped_title;
                detail = fallback(detail, R.string.home_stopped_detail);
                break;
            case ERROR:
                title = R.string.home_error_title;
                detail = fallback(detail, R.string.home_error_detail);
                break;
            case IDLE:
            default:
                title = manualStartAvailable ? R.string.home_ready_title : R.string.home_waiting_title;
                detail = fallback(detail, manualStartAvailable
                        ? R.string.home_ready_detail : R.string.home_waiting_detail);
                break;
        }
        statusTitle_.setText(title);
        statusDetail_.setText(detail);

        Panel panel;
        if (state == ConnectionState.ERROR) {
            panel = ODAService.MODE_USB.equals(mode) ? Panel.RECOVER : Panel.RETRY;
        } else if (loading) {
            panel = Panel.BUSY;
        } else if (state == ConnectionState.ACTIVE) {
            panel = Panel.ACTIVE;
        } else if (manualStartAvailable) {
            panel = Panel.READY;
        } else {
            panel = Panel.WAITING;
        }
        if (usbManager_ != null && usbManager_.aoapDevice() != null) {
            usbPresent_ = true;
        }
        boolean changed = previous != null && (previous != state || panel != renderedPanel_);
        renderPanel(panel, state);
        renderUsb();
        if (changed) {
            // Same place, new content: a short fade, nothing moves.
            Ui.fadeIn(statusTitle_);
            Ui.fadeIn(statusDetail_);
            Ui.fadeIn(startGlyph_);
            Ui.fadeIn(startTitle_);
            Ui.fadeIn(startDetail_);
        }
    }

    private static boolean isLoading(ConnectionState state) {
        return state == ConnectionState.PERMISSION_PENDING
                || state == ConnectionState.AOAP_SWITCHING || state == ConnectionState.CONNECTING
                || state == ConnectionState.DISCONNECTING;
    }

    @SuppressWarnings("deprecation")
    private void renderPanel(Panel panel, ConnectionState state) {
        Context context = requireContext();
        Ui.Palette p = palette_;
        renderedPanel_ = panel;
        boolean actionable = panel == Panel.READY || panel == Panel.RECOVER || panel == Panel.RETRY;

        if (panel == Panel.WAITING) {
            startPanel_.setBackgroundDrawable(Ui.dashedOutline(context, p, 20));
        } else if (actionable) {
            startPanel_.setBackgroundDrawable(Ui.pressable(
                    Ui.rounded(context, p.selected, 20, p.hairline),
                    Ui.rounded(context, p.surface, 20, p.hairline)));
        } else {
            startPanel_.setBackgroundDrawable(Ui.rounded(context, p.surface, 20, p.hairline));
        }
        startPanel_.setClickable(actionable);
        startPanel_.setEnabled(actionable);

        stopBusySpin();
        switch (panel) {
            case WAITING:
                startGlyph_.setGlyph(GlyphView.USB, 0, p.muted);
                setPanelText(R.string.home_panel_wait_title, 22, p.body,
                        R.string.home_panel_wait_detail, 15, p.muted);
                break;
            case READY:
                startGlyph_.setGlyph(GlyphView.PLAY, p.accent, p.onAccent);
                setPanelText(R.string.home_panel_start, 34, p.text,
                        R.string.home_panel_start_detail, 19, p.muted);
                break;
            case BUSY:
                startGlyph_.setGlyph(GlyphView.BUSY, 0, p.accent);
                setPanelText(state == ConnectionState.DISCONNECTING ? R.string.home_panel_stopping
                        : (state == ConnectionState.PERMISSION_PENDING
                        ? R.string.home_panel_permission : R.string.home_panel_connecting),
                        26, p.text, 0, 0, 0);
                startBusySpin();
                break;
            case ACTIVE:
                startGlyph_.setGlyph(GlyphView.CHECK, p.ready, p.onAccent);
                setPanelText(R.string.home_panel_active, 26, p.text, 0, 0, 0);
                break;
            case RECOVER:
                startGlyph_.setGlyph(GlyphView.RECOVER, p.accent, p.onAccent);
                setPanelText(R.string.home_panel_recover, 32, p.text,
                        R.string.home_panel_recover_detail, 19, p.muted);
                break;
            case RETRY:
            default:
                startGlyph_.setGlyph(GlyphView.RECOVER, p.accent, p.onAccent);
                setPanelText(R.string.home_panel_retry, 32, p.text,
                        R.string.home_panel_retry_detail, 19, p.muted);
                break;
        }
    }

    private void setPanelText(int title, float titleSp, int titleColor, int detail, float detailSp, int detailColor) {
        startTitle_.setText(title);
        startTitle_.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        startTitle_.setTextColor(titleColor);
        if (detail == 0) {
            startDetail_.setVisibility(View.GONE);
        } else {
            startDetail_.setVisibility(View.VISIBLE);
            startDetail_.setText(detail);
            startDetail_.setTextSize(TypedValue.COMPLEX_UNIT_SP, detailSp);
            startDetail_.setTextColor(detailColor);
        }
    }

    @SuppressWarnings("deprecation")
    private void renderUsb() {
        if (palette_ == null) {
            return;
        }
        Ui.Palette p = palette_;
        boolean usbMode = ODAService.MODE_USB.equals(renderedMode_);
        int text;
        int color;
        if (renderedState_ == ConnectionState.ERROR && usbMode) {
            text = R.string.home_value_usb_error;
            color = p.error;
        } else if (usbMode && (renderedState_ == ConnectionState.ACTIVE || isLoading(renderedState_))) {
            text = R.string.home_value_usb_busy;
            color = p.accent;
        } else if (usbPresent_) {
            text = R.string.home_value_usb_ready;
            color = p.ready;
        } else {
            text = R.string.home_value_usb_waiting;
            color = p.waiting;
        }
        usbStatus_.setText(text);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        usbDot_.setBackgroundDrawable(dot);
    }

    private void startBusySpin() {
        if (busySpin_ == null) {
            busySpin_ = ObjectAnimator.ofFloat(startGlyph_, "rotation", 0f, 360f);
            busySpin_.setDuration(800);
            busySpin_.setInterpolator(new LinearInterpolator());
            busySpin_.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!busySpin_.isStarted()) {
            busySpin_.start();
        }
    }

    private void stopBusySpin() {
        if (busySpin_ != null) {
            busySpin_.cancel();
        }
        if (startGlyph_ != null) {
            startGlyph_.setRotation(0f);
        }
    }

    private String versionName() {
        try {
            Context context = requireContext();
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private String fallback(String detail, int fallbackResource) {
        return detail == null || detail.trim().isEmpty() ? getString(fallbackResource) : detail;
    }
}
