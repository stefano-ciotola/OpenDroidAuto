package it.smg.hu.ui.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import it.smg.hu.R;
import it.smg.hu.config.AppLocale;
import it.smg.hu.config.Settings;

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
    protected String tag() {
        return TAG;
    }
}
