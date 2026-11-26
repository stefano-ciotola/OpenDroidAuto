package it.smg.hu.ui.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.graphics.drawable.GradientDrawable;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import java.util.concurrent.Callable;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.ui.Ui;
import it.smg.libs.common.Log;

public abstract class BaseSettingsFragment extends Fragment {
    protected Settings settings;

    protected abstract String tag();

    @Override
    public void onViewCreated(View view, android.os.Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Ui.Palette palette = Ui.palette(requireContext());
        view.setBackgroundColor(palette.bg);
        styleControls(view, palette, false);
    }

    /**
     * Turns the tagged layouts into the grouped look: tag="group" containers
     * get the rounded surface, "divider" the hairline, "section" and "hint"
     * the muted colour, checkboxes become rows with a switch on the right,
     * fields and spinners get the inset field. Buttons keep their own style
     * (keymap hotspots, theme segments).
     */
    @SuppressWarnings("deprecation")
    private void styleControls(View view, Ui.Palette p, boolean inGroup) {
        Context context = requireContext();
        Object tag = view.getTag();
        if ("divider".equals(tag)) {
            view.setBackgroundColor(p.hairline);
            return;
        }
        if (view instanceof Spinner) {
            view.setBackgroundDrawable(Ui.field(context, p, true));
            view.setPadding(0, 0, 0, 0);
            return;
        }
        if (view instanceof ViewGroup) {
            boolean group = "group".equals(tag);
            if (group) {
                view.setBackgroundDrawable(Ui.rounded(context, p.surface, 14, p.hairline));
            }
            ViewGroup children = (ViewGroup) view;
            for (int i = 0; i < children.getChildCount(); i++) {
                styleControls(children.getChildAt(i), p, inGroup || group);
            }
            return;
        }
        if (view instanceof EditText) {
            EditText field = (EditText) view;
            field.setBackgroundDrawable(Ui.field(context, p, false));
            field.setPadding(dp(12), 0, dp(12), 0);
            field.setTextColor(p.text);
            field.setHintTextColor(p.muted);
            field.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        } else if (view instanceof CheckBox) {
            CheckBox row = (CheckBox) view;
            GradientDrawable none = new GradientDrawable();
            none.setSize(0, 0);
            row.setButtonDrawable(none);
            row.setCompoundDrawablesWithIntrinsicBounds(null, null, Ui.switchDrawable(context, p), null);
            row.setCompoundDrawablePadding(dp(16));
            row.setBackgroundDrawable(inGroup ? Ui.rowBackground(context, p)
                    : Ui.pressable(Ui.rounded(context, p.surface, 14, p.hairline),
                    Ui.rounded(context, p.selected, 14, p.hairline)));
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.setTextColor(p.text);
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            alignToStart(row, true);
        } else if (view instanceof Button) {
            // CheckBox is a Button too, so this comes after it: plain buttons
            // (keymap hotspots, theme segments) keep the style they were given.
            return;
        } else if (view instanceof TextView) {
            TextView text = (TextView) view;
            boolean quiet = "section".equals(tag) || "hint".equals(tag);
            text.setTextColor(quiet ? p.muted : p.text);
            text.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        }
    }

    private void alignToStart(View view, boolean fillWidth) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) {
            return;
        }
        if (fillWidth) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).gravity = Gravity.LEFT;
        }
        view.setLayoutParams(params);
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, String defaultValue){
        initEditText(editText, base, settingsKey, defaultValue, null);
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, int defaultValue){
        initEditText(editText, base, settingsKey, defaultValue, null);
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, int defaultValue, Callable<Void> custonCheck){
        editText.setText(String.valueOf(base.get(settingsKey, defaultValue)));

        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE){
                String rawValue = v.getText().toString().trim();
                try {
                    base.set(settingsKey, Integer.parseInt(rawValue));
                    v.setError(null);
                } catch (NumberFormatException error) {
                    v.setError(getString(R.string.settings_invalid_number));
                    return true;
                }

                InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }

                return true;
            }
            return false;
        });
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, String defaultValue, Callable<Void> custonCheck){
        editText.setText(base.get(settingsKey, defaultValue));
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE){
                base.set(settingsKey, v.getText().toString());

                InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }

                return true;
            }
            return false;
        });
    }

    protected void initCheckBox(CheckBox checkBox, Settings.Base base, String settingsKey, boolean defaultValue){
        initCheckBox(checkBox, base, settingsKey, defaultValue, null);
    }

    protected void initCheckBox(CheckBox checkBox, Settings.Base base, String settingsKey, boolean defaultValue, Callable<Void> custonCheck){
        checkBox.setChecked(base.get(settingsKey, defaultValue));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            base.set(settingsKey, isChecked);
            if (custonCheck != null){
                try {
                    custonCheck.call();
                } catch (Exception e) {
                    Log.e(tag(), "errore in custom check", e);
                }
            }
        });
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, String defaultValue){
        initSpinner(spinner, data, dataValue, base, settingsKey, defaultValue, null);
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, int defaultValue){
        initSpinner(spinner, data, dataValue, base, settingsKey, defaultValue, null);
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, int defaultValue, Callable<Void> custonCheck){
        ArrayAdapter<CharSequence> adapter = createSpinnerAdapter(data);
        spinner.setAdapter(adapter);

        int value = base.get(settingsKey, defaultValue);
        int[] elements = getContext().getResources().getIntArray(dataValue);

        for (int i = 0; i < elements.length; i++){
            int e = elements[i];
            if (value == e){
                spinner.setSelection(i);
                break;
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                base.set(settingsKey, elements[position]);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                base.set(settingsKey, defaultValue);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }
        });
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, String defaultValue, Callable<Void> custonCheck){
        ArrayAdapter<CharSequence> adapter = createSpinnerAdapter(data);
        spinner.setAdapter(adapter);

        String value = base.get(settingsKey, defaultValue);
        String[] elements = getContext().getResources().getStringArray(dataValue);

        for (int i = 0; i < elements.length; i++){
            String e = elements[i];
            if (value.equalsIgnoreCase(e)){
                spinner.setSelection(i);
                break;
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                base.set(settingsKey, elements[position]);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                base.set(settingsKey, defaultValue);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }
        });
    }

    private ArrayAdapter<CharSequence> createSpinnerAdapter(int data) {
        boolean dark = Settings.instance().appearance.darkTheme();
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(getContext(), data,
                dark ? R.layout.spinner_item_dark : R.layout.spinner_item);
        adapter.setDropDownViewResource(dark
                ? R.layout.spinner_dropdown_item_dark : R.layout.spinner_dropdown_item);
        return adapter;
    }
}
