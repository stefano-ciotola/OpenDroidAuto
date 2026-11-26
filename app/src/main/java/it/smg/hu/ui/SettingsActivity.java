package it.smg.hu.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import it.smg.hu.R;
import it.smg.hu.config.AppLocale;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.ui.settings.AdvancedFragment;
import it.smg.hu.ui.settings.CarFragment;
import it.smg.hu.ui.settings.ConnectivityFragment;
import it.smg.hu.ui.settings.KeymapFragment;
import it.smg.hu.ui.settings.VideoFragment;
import it.smg.libs.common.Log;

/**
 * Settings with a labelled rail on the left, so the current section is always
 * named. Switching section is instant on purpose: it happens tens of times in
 * a row, and any transition would only make it feel slower.
 */
public class SettingsActivity extends FragmentActivity implements InputDevice.OnKeyHolder {
    private static final String TAG = "SettingsActivity";
    private static final String SELECTED_NAVIGATION_ID = "selectedNavigationId";
    private static final int[] NAVIGATION_IDS = {
            R.id.car_settings, R.id.video_settings, R.id.conn_settings,
            R.id.keymap_settings, R.id.advanced_settings
    };
    private static final int[] NAVIGATION_ICONS = {
            R.drawable.car_settings, R.drawable.video_settings, R.drawable.connectivity_settings,
            R.drawable.keymap_settings, R.drawable.advanced_settings
    };
    private View.OnKeyListener keyListener_;
    private int selectedNavigationId_ = R.id.car_settings;
    private Ui.Palette palette_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLocale.apply(this);
        setContentView(R.layout.activity_settings);

        Button back = findViewById(R.id.settingsBackBtn);
        back.setOnClickListener(v -> onBackPressed());
        Ui.pressFeedback(back);
        findViewById(R.id.car_settings).setOnClickListener(v -> showSettings(new CarFragment(), R.string.car_settings, R.id.car_settings));
        findViewById(R.id.video_settings).setOnClickListener(v -> showSettings(new VideoFragment(), R.string.video_settings, R.id.video_settings));
        findViewById(R.id.conn_settings).setOnClickListener(v -> showSettings(new ConnectivityFragment(), R.string.conn_settings, R.id.conn_settings));
        findViewById(R.id.keymap_settings).setOnClickListener(v -> showSettings(new KeymapFragment(), R.string.keymap_settings, R.id.keymap_settings));
        findViewById(R.id.advanced_settings).setOnClickListener(v -> showSettings(new AdvancedFragment(), R.string.advanced_settings, R.id.advanced_settings));

        if (savedInstanceState != null) {
            selectedNavigationId_ = savedInstanceState.getInt(SELECTED_NAVIGATION_ID, R.id.car_settings);
        }
        applyTheme();
        if (savedInstanceState == null) {
            showSettings(new CarFragment(), R.string.car_settings, R.id.car_settings);
        } else {
            ((TextView) findViewById(R.id.settingsTitle)).setText(titleForNavigation(selectedNavigationId_));
            renderNavigation();
        }
    }

    private void showSettings(Fragment fragment, int titleResource, int selectedNavigationId) {
        selectedNavigationId_ = selectedNavigationId;
        ((TextView) findViewById(R.id.settingsTitle)).setText(titleResource);
        renderNavigation();
        findViewById(R.id.settingsScroll).scrollTo(0, 0);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_content, fragment)
                .commit();
    }

    @SuppressWarnings("deprecation")
    private void applyTheme() {
        palette_ = Ui.palette(this);
        Ui.Palette p = palette_;
        findViewById(R.id.settingsRoot).setBackgroundColor(p.bg);
        findViewById(R.id.settingsNav).setBackgroundColor(p.rail);
        findViewById(R.id.settingsNavDivider).setBackgroundColor(p.hairline);
        findViewById(R.id.settingsContent).setBackgroundColor(p.bg);
        findViewById(R.id.main_content).setBackgroundColor(p.bg);
        ((TextView) findViewById(R.id.settingsTitle)).setTextColor(p.text);
        ((TextView) findViewById(R.id.settingsSubtitle)).setTextColor(p.muted);

        Button back = findViewById(R.id.settingsBackBtn);
        back.setBackgroundDrawable(Ui.quietButton(this, p, 12));
        back.setTextColor(p.text);
        renderNavigation();
    }

    @SuppressWarnings("deprecation")
    private void renderNavigation() {
        if (palette_ == null) {
            return;
        }
        Ui.Palette p = palette_;
        int iconSize = Ui.dp(this, 22);
        for (int i = 0; i < NAVIGATION_IDS.length; i++) {
            boolean selected = NAVIGATION_IDS[i] == selectedNavigationId_;
            TextView item = findViewById(NAVIGATION_IDS[i]);
            if (selected) {
                item.setBackgroundDrawable(Ui.rounded(this, p.selected, 12, 0));
            } else {
                GradientDrawable idle = new GradientDrawable();
                idle.setColor(0x00000000);
                item.setBackgroundDrawable(Ui.pressable(idle, Ui.rounded(this, p.surface, 12, 0)));
            }
            item.setTextColor(selected ? p.text : p.body);
            item.setSelected(selected);
            Drawable icon = getResources().getDrawable(NAVIGATION_ICONS[i]).mutate();
            icon.setColorFilter(selected ? p.accent : p.body, PorterDuff.Mode.SRC_IN);
            icon.setBounds(0, 0, iconSize, iconSize);
            item.setCompoundDrawables(icon, null, null, null);
        }
    }

    private int titleForNavigation(int navigationId) {
        if (navigationId == R.id.video_settings) return R.string.video_settings;
        if (navigationId == R.id.conn_settings) return R.string.conn_settings;
        if (navigationId == R.id.keymap_settings) return R.string.keymap_settings;
        if (navigationId == R.id.advanced_settings) return R.string.advanced_settings;
        return R.string.car_settings;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(SELECTED_NAVIGATION_ID, selectedNavigationId_);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        setResult(Activity.RESULT_OK, new Intent());
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Log.isDebug()) Log.d(TAG, "onKeyDown: " + keyCode);
        return keyListener_ != null ? keyListener_.onKey(null, keyCode, event) : super.onKeyDown(keyCode, event);
    }

    @Override
    public void setOnKeyListener(View.OnKeyListener listener) {
        keyListener_ = listener;
    }
}
