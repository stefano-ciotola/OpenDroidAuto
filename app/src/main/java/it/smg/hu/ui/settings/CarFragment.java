package it.smg.hu.ui.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import it.smg.hu.R;
import it.smg.hu.config.AppLocale;
import it.smg.hu.config.Settings;
import it.smg.hu.ui.Ui;

public class CarFragment extends BaseSettingsFragment {

    private static final String TAG = "CarFragment";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.car_fragment, container, false);

        settings = Settings.instance();

        EditText huName = view.findViewById(R.id.hu_name);
        initEditText(huName, settings.car, Settings.Car.CAR_SETTINGS_HU_NAME, Settings.Car.CAR_SETTINGS_HU_NAME_DEFAULT_VALUE, null);

        EditText carModel = view.findViewById(R.id.car_model);
        initEditText(carModel, settings.car, Settings.Car.CAR_SETTINGS_MODEL, Settings.Car.CAR_SETTINGS_MODEL_DEFAULT_VALUE, null);

        EditText carYear = view.findViewById(R.id.car_year);
        initEditText(carYear, settings.car, Settings.Car.CAR_SETTINGS_YEAR, Settings.Car.CAR_SETTINGS_YEAR_DEFAULT_VALUE, null);

        EditText huMaker = view.findViewById(R.id.hu_make);
        initEditText(huMaker, settings.car, Settings.Car.CAR_SETTINGS_HU_MAKE, Settings.Car.CAR_SETTINGS_HU_MAKE_DEFAULT_VALUE, null);

        EditText huModel = view.findViewById(R.id.hu_model);
        initEditText(huModel, settings.car, Settings.Car.CAR_SETTINGS_HU_MODEL, Settings.Car.CAR_SETTINGS_HU_MODEL_DEFAULT_VALUE, null);

        EditText swBuild = view.findViewById(R.id.sw_build);
        initEditText(swBuild, settings.car, Settings.Car.CAR_SETTINGS_SW_BUILD, Settings.Car.CAR_SETTINGS_SW_BUILD_DEFAULT_VALUE, null);

        Spinner language = view.findViewById(R.id.app_language);
        initSpinner(language, R.array.app_language_labels, R.array.app_language_values, settings.appearance,
                Settings.Appearance.LANGUAGE, Settings.Appearance.LANGUAGE_DEFAULT_VALUE, () -> {
                    // Also called once when the spinner is first laid out: apply()
                    // reports no change then, so the screen is not rebuilt in a loop.
                    Activity activity = getActivity();
                    if (activity != null && AppLocale.apply(activity)) {
                        activity.recreate();
                    }
                    return null;
                });

        view.findViewById(R.id.theme_dark).setOnClickListener(v -> chooseTheme(true));
        view.findViewById(R.id.theme_light).setOnClickListener(v -> chooseTheme(false));

        EditText swVersion = view.findViewById(R.id.sw_version);
        initEditText(swVersion, settings.car, Settings.Car.CAR_SETTINGS_SW_VERSION, Settings.Car.CAR_SETTINGS_SW_VERSION_DEFAULT_VALUE, null);

        CheckBox playMediaDuringVR = view.findViewById(R.id.play_media_during_vr);
        initCheckBox(playMediaDuringVR, settings.car, Settings.Car.CAR_SETTINGS_PLAY_MEDIA_DURING_VR, Settings.Car.CAR_SETTINGS_PLAY_MEDIA_DURING_VR_DEFAULT_VALUE);

        CheckBox hideClock = view.findViewById(R.id.hide_clock);
        initCheckBox(hideClock, settings.car, Settings.Car.CAR_SETTINGS_HIDE_CLOCK, Settings.Car.CAR_SETTINGS_HIDE_CLOCK_DEFAULT_VALUE);

        CheckBox hideHeadUnitClock = view.findViewById(R.id.hide_hu_clock);
        initCheckBox(hideHeadUnitClock, settings.car, Settings.Car.CAR_SETTINGS_HIDE_HU_CLOCK, Settings.Car.CAR_SETTINGS_HIDE_HU_CLOCK_DEFAULT_VALUE);

        CheckBox leftDrive = view.findViewById(R.id.left_drive);
        initCheckBox(leftDrive, settings.car, Settings.Car.CAR_SETTINGS_LEFT_HAND_DRIVE, Settings.Car.CAR_SETTINGS_LEFT_HAND_DRIVE_DEFAULT_VALUE);

        return view;

    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        renderThemeSegment(view);
    }

    /** Two-option segmented control: Dark | Light. */
    @SuppressWarnings("deprecation")
    private void renderThemeSegment(View view) {
        Ui.Palette p = Ui.palette(requireContext());
        view.findViewById(R.id.theme_segment).setBackgroundDrawable(
                Ui.rounded(requireContext(), p.dark ? p.bg : p.rail, 11, p.hairline));
        styleSegment(view.findViewById(R.id.theme_dark), p, p.dark);
        styleSegment(view.findViewById(R.id.theme_light), p, !p.dark);
    }

    @SuppressWarnings("deprecation")
    private void styleSegment(Button button, Ui.Palette p, boolean selected) {
        if (selected) {
            button.setBackgroundDrawable(Ui.rounded(requireContext(), p.dark ? p.trackOff : p.surface, 8, 0));
        } else {
            button.setBackgroundDrawable(Ui.pressable(Ui.rounded(requireContext(), 0x00000000, 8, 0),
                    Ui.rounded(requireContext(), p.selected, 8, 0)));
        }
        button.setTextColor(selected ? p.text : p.muted);
        button.setSelected(selected);
    }

    private void chooseTheme(boolean dark) {
        if (dark == settings.appearance.darkTheme()) {
            return;
        }
        settings.appearance.darkTheme(dark);
        // Every screen reads the palette when it is built: rebuild this one,
        // the home screen restyles itself when settings close.
        Activity activity = getActivity();
        if (activity != null) {
            activity.recreate();
        }
    }

    @Override
    protected String tag() {
        return TAG;
    }
}
