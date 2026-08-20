package com.winlator.cmod;

import java.io.File;
import java.util.List;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;

import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.NumberPicker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private androidx.activity.result.ActivityResultLauncher<String> iconPickerLauncher;
    private LinearLayout currentIconListLayout;

    private String initialProfileSnapshot;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        iconPickerLauncher = registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                int newId = com.winlator.cmod.inputcontrols.CustomIconManager.getInstance(this).importCustomIcon(uri);
                if (newId > 0) {
                    ControlElement el = inputControlsView.getSelectedElement();
                    if (el != null) {
                        el.setIconId(newId);
                        profile.save();
                        inputControlsView.invalidate();
                        if (currentIconListLayout != null) {
                            loadIcons(currentIconListLayout, newId);
                        }
                        AppUtils.showToast(this, "Custom icon imported and applied!");
                    }
                }
            }
        });

        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(0.6f);

        File profileFile = ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0));
        initialProfileSnapshot = (profileFile != null && profileFile.isFile()) ? FileUtils.readString(profileFile) : null;
        profile = InputControlsManager.loadProfile(this, profileFile);
        ((TextView)findViewById(R.id.TVProfileName)).setText(profile.getName());
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.findViewById(R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(R.id.BTElementSettings).setOnClickListener(this);
        container.findViewById(R.id.BTStylePreset).setOnClickListener(this);
        container.findViewById(R.id.BTOpacity).setOnClickListener(this);
        container.findViewById(R.id.BTReset).setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Use a handler delay to ensure the activity is fully visible and interactive
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (!prefs.getBoolean("mix_warning_shown_v4", false)) {
                ContentDialog.alert(this, R.string.warning_gamepad_mouse_mix, () -> {
                    prefs.edit().putBoolean("mix_warning_shown_v4", true).apply();
                });
            }
        }, 500);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.BTAddElement:
                if (!inputControlsView.addElement()) {
                    AppUtils.showToast(this, R.string.no_profile_selected);
                }
                break;
            case R.id.BTRemoveElement:
                if (!inputControlsView.removeElement()) {
                    AppUtils.showToast(this, R.string.no_control_element_selected);
                }
                break;
            case R.id.BTElementSettings:
                ControlElement selectedElement = inputControlsView.getSelectedElement();
                if (selectedElement != null) {
                    showControlElementSettings(v);
                }
                else AppUtils.showToast(this, R.string.no_control_element_selected);
                break;
            case R.id.BTStylePreset:
                showStylePresetDialog();
                break;
            case R.id.BTOpacity:
                showGlobalOpacityDialog();
                break;
            case R.id.BTReset:
                ContentDialog.confirm(this, "Reset all buttons to original positions?", () -> {
                    if (profile != null) {
                        boolean resetDone = profile.resetToOriginal(inputControlsView);
                        if (!resetDone && initialProfileSnapshot != null && !initialProfileSnapshot.isEmpty()) {
                            File targetFile = ControlsProfile.getProfileFile(this, profile.id);
                            FileUtils.writeString(targetFile, initialProfileSnapshot);
                            profile.loadElements(inputControlsView);
                        }
                        inputControlsView.invalidate();
                        AppUtils.showToast(this, "Buttons reset to original layout");
                    }
                });
                break;
        }
    }

    private void showGlobalOpacityDialog() {
        if (profile == null) return;
        ContentDialog dialog = new ContentDialog(this);
        dialog.setTitle("Global Controls Opacity");
        dialog.setIcon(R.drawable.icon_opacity);

        FrameLayout frameLayout = dialog.findViewById(R.id.FrameLayout);
        if (frameLayout == null) return;
        frameLayout.setVisibility(View.VISIBLE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setMinimumWidth((int)UnitUtils.dpToPx(300));
        layout.setPadding((int)UnitUtils.dpToPx(16), (int)UnitUtils.dpToPx(16), (int)UnitUtils.dpToPx(16), (int)UnitUtils.dpToPx(16));

        TextView tvValue = new TextView(this);
        int currentPct = (int)(profile.getOverlayOpacity() * 100);
        tvValue.setText("Opacity: " + currentPct + "%");
        tvValue.setTextSize(14);
        tvValue.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvValue.setTextColor(getResources().getColor(R.color.colorAccent));
        layout.addView(tvValue);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(currentPct);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, (int)UnitUtils.dpToPx(12), 0, (int)UnitUtils.dpToPx(8));
        seekBar.setLayoutParams(params);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = Math.max(10, progress);
                tvValue.setText("Opacity: " + val + "%");
                float opacity = val / 100.0f;
                profile.setOverlayOpacity(opacity);
                inputControlsView.setOverlayOpacity(opacity);
                inputControlsView.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                profile.save();
            }
        });

        layout.addView(seekBar);
        frameLayout.addView(layout);

        dialog.setOnConfirmCallback(() -> {
            profile.save();
        });

        dialog.show();
    }

    private void showStylePresetDialog() {
        if (profile == null) return;
        final com.winlator.cmod.inputcontrols.ControlStylePreset[] presets = com.winlator.cmod.inputcontrols.ControlStylePreset.values();
        String[] presetTitles = com.winlator.cmod.inputcontrols.ControlStylePreset.titles();

        ContentDialog.showSingleChoiceList(this, "Control Style Presets", presetTitles, (position) -> {
            if (position >= 0 && position < presets.length) {
                com.winlator.cmod.inputcontrols.ControlStylePreset selected = presets[position];
                profile.applyStylePreset(selected, inputControlsView);
                AppUtils.showToast(this, "Applied " + selected.title);
            }
        });
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        View view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null);

        final Runnable updateLayout = () -> {
            ControlElement.Type type = element.getType();
            view.findViewById(R.id.LLShape).setVisibility(View.GONE);
            view.findViewById(R.id.CBToggleSwitch).setVisibility(View.GONE);
            view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.GONE);
            view.findViewById(R.id.LLRangeOptions).setVisibility(View.GONE);

            if (type == ControlElement.Type.BUTTON) {
                view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                view.findViewById(R.id.CBToggleSwitch).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
            }
            else if (type == ControlElement.Type.RANGE_BUTTON) {
                view.findViewById(R.id.LLRangeOptions).setVisibility(View.VISIBLE);
            }

            loadBindingSpinners(element, view);
        };

        loadTypeSpinner(element, view.findViewById(R.id.SType), updateLayout);
        loadShapeSpinner(element, view.findViewById(R.id.SShape));
        loadRangeSpinner(element, view.findViewById(R.id.SRange));

        RadioGroup rgOrientation = view.findViewById(R.id.RGOrientation);
        rgOrientation.check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
        rgOrientation.setOnCheckedChangeListener((group, checkedId) -> {
            element.setOrientation((byte)(checkedId == R.id.RBVertical ? 1 : 0));
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npColumns = view.findViewById(R.id.NPColumns);
        npColumns.setValue(element.getBindingCount());
        npColumns.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            profile.save();
            inputControlsView.invalidate();
        });

        final TextView tvScale = view.findViewById(R.id.TVScale);
        SeekBar sbScale = view.findViewById(R.id.SBScale);
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvScale.setText(progress+"%");
                if (fromUser) {
                    progress = (int)Mathf.roundTo(progress, 5);
                    seekBar.setProgress(progress);
                    element.setScale(progress / 100.0f);
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                profile.save();
            }
        });
        sbScale.setProgress((int)(element.getScale() * 100));

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setToggleSwitch(isChecked);
            profile.save();
        });

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        etCustomText.setText(element.getText());
        etCustomText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                element.setText(s.toString().trim());
                profile.save();
                inputControlsView.invalidate();
            }
        });

        final LinearLayout llIconList = view.findViewById(R.id.LLIconList);
        currentIconListLayout = llIconList;
        loadIcons(llIconList, element.getIconId());

        final CheckBox cbCustomIconAsButton = view.findViewById(R.id.CBCustomIconAsButton);
        cbCustomIconAsButton.setChecked(element.isCustomIconAsButton());
        cbCustomIconAsButton.setOnCheckedChangeListener((btn, isChecked) -> {
            element.setCustomIconAsButton(isChecked);
            profile.save();
            inputControlsView.invalidate();
        });

        final TextView tvWidthScale = view.findViewById(R.id.TVWidthScale);
        SeekBar sbWidthScale = view.findViewById(R.id.SBWidthScale);
        int currentW = (int)(element.getWidthScale() * 100);
        tvWidthScale.setText(currentW + "%");
        sbWidthScale.setProgress(currentW);
        sbWidthScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvWidthScale.setText(progress + "%");
                if (fromUser) {
                    element.setWidthScale(progress / 100.0f);
                    inputControlsView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                profile.save();
            }
        });

        final TextView tvHeightScale = view.findViewById(R.id.TVHeightScale);
        SeekBar sbHeightScale = view.findViewById(R.id.SBHeightScale);
        int currentH = (int)(element.getHeightScale() * 100);
        tvHeightScale.setText(currentH + "%");
        sbHeightScale.setProgress(currentH);
        sbHeightScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvHeightScale.setText(progress + "%");
                if (fromUser) {
                    element.setHeightScale(progress / 100.0f);
                    inputControlsView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                profile.save();
            }
        });

        final TextView tvTouchPadding = view.findViewById(R.id.TVTouchPadding);
        SeekBar sbTouchPadding = view.findViewById(R.id.SBTouchPadding);
        int currentPad = element.getTouchPadding();
        tvTouchPadding.setText("+" + currentPad);
        sbTouchPadding.setProgress(currentPad);
        sbTouchPadding.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTouchPadding.setText("+" + progress);
                if (fromUser) {
                    element.setTouchPadding(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                profile.save();
            }
        });

        updateLayout.run();

        int screenWidth = AppUtils.getScreenWidth();
        int screenHeight = AppUtils.getScreenHeight();
        int dialogWidth = (int)UnitUtils.dpToPx(340);
        int dialogHeight = Math.min((int)UnitUtils.dpToPx(480), screenHeight - (int)UnitUtils.dpToPx(32));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isDarkMode = preferences.getBoolean("dark_mode", true);
        int accentColor = getResources().getColor(isDarkMode ? R.color.colorAccentDark : R.color.colorAccent);

        InGameControlsEditor.applyThemeToViewHierarchy(view, isDarkMode);

        final PopupWindow popupWindow = new PopupWindow(this);
        popupWindow.setElevation(10.0f);
        popupWindow.setWidth(dialogWidth);
        popupWindow.setHeight(dialogHeight);
        popupWindow.setContentView(view);
        popupWindow.setFocusable(true);
        popupWindow.setOutsideTouchable(true);

        android.graphics.drawable.GradientDrawable bgDrawable = new android.graphics.drawable.GradientDrawable();
        bgDrawable.setColor(isDarkMode ? 0xFF181C24 : 0xFFFFFFFF);
        bgDrawable.setCornerRadius(UnitUtils.dpToPx(12));
        bgDrawable.setStroke((int)UnitUtils.dpToPx(1.5f), isDarkMode ? 0xFF333D4D : 0xFFCFD8DC);
        popupWindow.setBackgroundDrawable(bgDrawable);

        // Calculate initial spawn position: place on opposite side of screen from the selected element so it NEVER covers the button
        int initialX = (element.getX() > screenWidth / 2) ? (int)UnitUtils.dpToPx(24) : (screenWidth - dialogWidth - (int)UnitUtils.dpToPx(24));
        int initialY = (int)UnitUtils.dpToPx(36);
        final int[] windowPos = new int[]{initialX, initialY};

        View dragHeader = view.findViewById(R.id.LLDragHeader);
        if (dragHeader != null) {
            android.graphics.drawable.GradientDrawable headerDrawable = new android.graphics.drawable.GradientDrawable();
            headerDrawable.setColor(isDarkMode ? 0xFF242C38 : 0xFFECEFF1);
            float r = UnitUtils.dpToPx(12);
            headerDrawable.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            dragHeader.setBackground(headerDrawable);

            TextView tvDragTitle = dragHeader.findViewById(R.id.TVDragTitle);
            if (tvDragTitle != null) tvDragTitle.setTextColor(accentColor);

            dragHeader.setOnTouchListener(new View.OnTouchListener() {
                float startRawX, startRawY;
                int startWinX, startWinY;
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawX = event.getRawX();
                            startRawY = event.getRawY();
                            startWinX = windowPos[0];
                            startWinY = windowPos[1];
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - startRawX;
                            float dy = event.getRawY() - startRawY;
                            windowPos[0] = startWinX + (int)dx;
                            windowPos[1] = startWinY + (int)dy;
                            popupWindow.update(windowPos[0], windowPos[1], -1, -1);
                            return true;
                    }
                    return false;
                }
            });
        }

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, windowPos[0], windowPos[1]);
        popupWindow.setOnDismissListener(() -> {
            currentIconListLayout = null;
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, Runnable callback) {
        final boolean[] isInitializing = {true};
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.post(() -> isInitializing[0] = false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializing[0]) return;
                ControlElement.Type type = ControlElement.Type.values()[position];
                if (type != element.getType()) {
                    element.setType(type);
                    profile.save();
                    callback.run();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        final boolean[] isInitializing = {true};
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.post(() -> isInitializing[0] = false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializing[0]) return;
                ControlElement.Shape shape = ControlElement.Shape.values()[position];
                if (shape != element.getShape()) {
                    element.setShape(shape);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        container.removeAllViews();

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            loadBindingSpinner(element, container, 0, R.string.binding);
            loadBindingSpinner(element, container, 1, R.string.binding_secondary);
            loadBindingSpinner(element, container, 2, "Combo 3 (Optional)");
            loadBindingSpinner(element, container, 3, "Combo 4 (Optional)");
        }
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_right);
            loadBindingSpinner(element, container, 2, R.string.binding_down);
            loadBindingSpinner(element, container, 3, R.string.binding_left);
        }
    }

    private void loadBindingSpinner(final ControlElement element, LinearLayout container, final int index, Object title) {
        View view = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);
        TextView tvTitle = view.findViewById(R.id.TVTitle);
        if (title instanceof Integer) {
            tvTitle.setText((Integer)title);
        } else {
            tvTitle.setText(String.valueOf(title));
        }

        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        final boolean[] isInitializing = {true};

        Runnable update = () -> {
            isInitializing[0] = true;
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0:
                    bindingEntries = Binding.keyboardBindingLabels();
                    break;
                case 1:
                    bindingEntries = Binding.mouseBindingLabels();
                    break;
                case 2:
                    bindingEntries = Binding.gamepadBindingLabels();
                    break;
            }

            sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
            sBinding.post(() -> isInitializing[0] = false);
        };

        Binding selectedBinding = element.getBindingAt(index);
        int typeIndex = selectedBinding.isGamepad() ? 2 : (selectedBinding.isMouse() ? 1 : 0);
        sBindingType.setSelection(typeIndex, false);
        update.run();

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing[0]) {
                    update.run();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializing[0]) return;
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0:
                        binding = Binding.keyboardBindingValues()[position];
                        break;
                    case 1:
                        binding = Binding.mouseBindingValues()[position];
                        break;
                    case 2:
                        binding = Binding.gamepadBindingValues()[position];
                        break;
                }

                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isDarkMode = preferences.getBoolean("dark_mode", true);
        InGameControlsEditor.applyThemeToViewHierarchy(view, isDarkMode);

        container.addView(view);
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        final boolean[] isInitializing = {true};
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.post(() -> isInitializing[0] = false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializing[0]) return;
                ControlElement.Range range = ControlElement.Range.values()[position];
                if (range != element.getRange()) {
                    element.setRange(range);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadIcons(final LinearLayout parent, int selectedId) {
        parent.removeAllViews();
        final com.winlator.cmod.inputcontrols.CustomIconManager iconManager = com.winlator.cmod.inputcontrols.CustomIconManager.getInstance(this);
        final ControlElement element = inputControlsView.getSelectedElement();
        List<Integer> iconIds = iconManager.getAllIconIds();

        int size = (int)UnitUtils.dpToPx(40);
        int margin = (int)UnitUtils.dpToPx(2);
        int padding = (int)UnitUtils.dpToPx(4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        // 1. Clear / Reset to No Icon
        ImageView clearImageView = new ImageView(this);
        clearImageView.setLayoutParams(params);
        clearImageView.setPadding(padding, padding, padding, padding);
        clearImageView.setBackgroundResource(R.drawable.icon_background);
        clearImageView.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        clearImageView.setColorFilter(getResources().getColor(R.color.colorAccent));
        clearImageView.setSelected(selectedId == 0);
        clearImageView.setOnClickListener((v) -> {
            for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
            clearImageView.setSelected(true);
            if (element != null) {
                element.setIconId(0);
                profile.save();
                inputControlsView.invalidate();
            }
        });
        parent.addView(clearImageView);

        // 2. Add [+] Gallery image picker button
        ImageView addImageView = new ImageView(this);
        addImageView.setLayoutParams(params);
        addImageView.setPadding(padding, padding, padding, padding);
        addImageView.setBackgroundResource(R.drawable.icon_background);
        addImageView.setImageResource(R.drawable.icon_add);
        addImageView.setColorFilter(getResources().getColor(R.color.colorAccent));
        addImageView.setOnClickListener((v) -> {
            if (iconPickerLauncher != null) {
                iconPickerLauncher.launch("image/*");
            }
        });
        parent.addView(addImageView);

        for (final int id : iconIds) {
            final ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setTag(Integer.valueOf(id));
            imageView.setSelected(id == selectedId);
            if (id <= com.winlator.cmod.inputcontrols.CustomIconManager.BUILTIN_ICON_MAX) {
                imageView.setColorFilter(getResources().getColor(R.color.colorAccent));
            } else {
                imageView.setColorFilter(null);
            }

            imageView.setOnClickListener((v) -> {
                for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
                imageView.setSelected(true);
                if (element != null) {
                    element.setIconId(id);
                    profile.save();
                    inputControlsView.invalidate();
                }
            });

            imageView.setOnLongClickListener((v) -> {
                if (id > com.winlator.cmod.inputcontrols.CustomIconManager.BUILTIN_ICON_MAX) {
                    ContentDialog.confirm(ControlsEditorActivity.this, "Delete custom icon #" + id + "?", () -> {
                        iconManager.deleteCustomIcon(id);
                        if (element != null && element.getIconId() == id) {
                            element.setIconId(0);
                            profile.save();
                            inputControlsView.invalidate();
                        }
                        loadIcons(parent, element != null ? element.getIconId() : 0);
                        AppUtils.showToast(ControlsEditorActivity.this, "Custom icon deleted");
                    });
                    return true;
                }
                return false;
            });

            Bitmap bmp = iconManager.getIcon(id);
            if (bmp != null) imageView.setImageBitmap(bmp);

            parent.addView(imageView);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_down, R.anim.slide_out_up);  // Custom slide animations for exiting
    }

}
