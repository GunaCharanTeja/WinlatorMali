package com.winlator.cmod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.RadialWheelConfig;
import com.winlator.cmod.inputcontrols.RadialWheelSlice;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 3: Steam Deck-Style Radial Action Wheels configuration dialog.
 * Supports standalone external controller mode when no touchscreen profile is selected.
 */
public class RadialWheelsDialog {
    private static final int MAX_WHEELS = 4;

    public static void show(Context context, ControlsProfile profile, Runnable onSaveCallback) {
        ArrayList<RadialWheelConfig> wheels = (profile != null)
                ? profile.getWheels()
                : RadialWheelConfig.loadGlobal(context);

        if (wheels.isEmpty()) {
            RadialWheelConfig defaultWheel = new RadialWheelConfig(1);
            defaultWheel.name = "Weapon Wheel";
            defaultWheel.triggerBinding = Binding.GAMEPAD_BUTTON_L2;
            wheels.add(defaultWheel);
        }

        ContentDialog dialog = new ContentDialog(context, R.layout.radial_wheels_dialog);
        dialog.setTitle(profile != null ? "Radial Wheels (" + profile.getName() + ")" : "Radial Wheels (Controller)");
        dialog.setIcon(R.drawable.icon_radial_wheel);

        android.content.SharedPreferences preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = preferences.getBoolean("dark_mode", true);
        int textColor = isDarkMode ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
        int hintColor = isDarkMode ? 0x88FFFFFF : 0x88000000;
        int editBgRes = isDarkMode ? R.drawable.edit_text_dark : R.drawable.edit_text;
        int popupBgRes = isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background;

        // Apply dark/light styling to labels
        TextView tvWheelLabel = dialog.findViewById(R.id.TVWheelLabel);
        TextView tvPropertiesLabel = dialog.findViewById(R.id.TVPropertiesLabel);
        TextView tvSlicesLabel = dialog.findViewById(R.id.TVSlicesLabel);
        if (isDarkMode) {
            if (tvWheelLabel != null) { tvWheelLabel.setTextColor(android.graphics.Color.parseColor("#cccccc")); tvWheelLabel.setBackgroundColor(android.graphics.Color.parseColor("#424242")); }
            if (tvPropertiesLabel != null) { tvPropertiesLabel.setTextColor(android.graphics.Color.parseColor("#cccccc")); tvPropertiesLabel.setBackgroundColor(android.graphics.Color.parseColor("#424242")); }
            if (tvSlicesLabel != null) { tvSlicesLabel.setTextColor(android.graphics.Color.parseColor("#cccccc")); tvSlicesLabel.setBackgroundColor(android.graphics.Color.parseColor("#424242")); }
        }

        Spinner spWheelSelect = dialog.findViewById(R.id.SPWheelSelect);
        spWheelSelect.setPopupBackgroundResource(popupBgRes);

        View btAddWheel = dialog.findViewById(R.id.BTAddWheel);
        View btRemoveWheel = dialog.findViewById(R.id.BTRemoveWheel);

        EditText etWheelName = dialog.findViewById(R.id.ETWheelName);
        etWheelName.setBackgroundResource(editBgRes);
        etWheelName.setTextColor(textColor);
        etWheelName.setHintTextColor(hintColor);

        Spinner spTriggerBinding = dialog.findViewById(R.id.SPTriggerBinding);
        spTriggerBinding.setPopupBackgroundResource(popupBgRes);

        Spinner spTriggerBinding2 = dialog.findViewById(R.id.SPTriggerBinding2);
        spTriggerBinding2.setPopupBackgroundResource(popupBgRes);

        TextView tvWheelIconScale = dialog.findViewById(R.id.TVWheelIconScale);
        android.widget.SeekBar sbWheelIconScale = dialog.findViewById(R.id.SBWheelIconScale);
        LinearLayout llSlicesContainer = dialog.findViewById(R.id.LLSlicesContainer);

        // Detect if external controller is connected to show only gamepad buttons
        ArrayList<ExternalController> controllers = ExternalController.getControllers();
        boolean hasExternalController = !controllers.isEmpty() || profile == null;

        List<Binding> availableBindingsList = new ArrayList<>();
        availableBindingsList.add(Binding.NONE);
        for (Binding b : Binding.values()) {
            if (b == Binding.NONE) continue;
            if (hasExternalController) {
                if (b.isGamepad()) availableBindingsList.add(b);
            } else {
                availableBindingsList.add(b);
            }
        }
        Binding[] allBindings = availableBindingsList.toArray(new Binding[0]);
        String[] bindingNames = new String[allBindings.length];
        for (int i = 0; i < allBindings.length; i++) {
            bindingNames[i] = allBindings[i].toString();
        }

        ArrayAdapter<String> triggerAdapter = createThemedAdapter(context, bindingNames, isDarkMode);
        spTriggerBinding.setAdapter(triggerAdapter);

        ArrayAdapter<String> triggerAdapter2 = createThemedAdapter(context, bindingNames, isDarkMode);
        spTriggerBinding2.setAdapter(triggerAdapter2);

        sbWheelIconScale.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                tvWheelIconScale.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        final int[] currentWheelIndex = {0};

        // Method to save current UI state into the active wheel config
        final Runnable saveCurrentWheelFromUI = () -> {
            if (currentWheelIndex[0] < 0 || currentWheelIndex[0] >= wheels.size()) return;
            RadialWheelConfig cfg = wheels.get(currentWheelIndex[0]);
            cfg.name = etWheelName.getText().toString().trim();
            cfg.iconScale = sbWheelIconScale.getProgress() / 100.0f;

            int trigPos = spTriggerBinding.getSelectedItemPosition();
            if (trigPos >= 0 && trigPos < allBindings.length) {
                cfg.triggerBinding = allBindings[trigPos];
            }

            int trigPos2 = spTriggerBinding2.getSelectedItemPosition();
            if (trigPos2 >= 0 && trigPos2 < allBindings.length) {
                cfg.triggerBinding2 = allBindings[trigPos2];
            }

            int childCount = llSlicesContainer.getChildCount();
            for (int i = 0; i < childCount && i < cfg.slices.size(); i++) {
                View sliceView = llSlicesContainer.getChildAt(i);
                EditText etLabel = sliceView.findViewById(R.id.ETSliceLabel);
                Spinner spBinding = sliceView.findViewById(R.id.SPSliceBinding);
                Spinner spBinding2 = sliceView.findViewById(R.id.SPSliceBinding2);
                Spinner spBinding3 = sliceView.findViewById(R.id.SPSliceBinding3);
                RadialWheelSlice slice = cfg.slices.get(i);

                if (etLabel != null) {
                    slice.label = etLabel.getText().toString().trim();
                }
                if (spBinding != null) {
                    int bPos = spBinding.getSelectedItemPosition();
                    if (bPos >= 0 && bPos < allBindings.length) {
                        slice.binding = allBindings[bPos];
                    }
                }
                if (spBinding2 != null) {
                    int bPos2 = spBinding2.getSelectedItemPosition();
                    if (bPos2 >= 0 && bPos2 < allBindings.length) {
                        slice.binding2 = allBindings[bPos2];
                    }
                }
                if (spBinding3 != null) {
                    int bPos3 = spBinding3.getSelectedItemPosition();
                    if (bPos3 >= 0 && bPos3 < allBindings.length) {
                        slice.binding3 = allBindings[bPos3];
                    }
                }
            }
        };

        final Runnable updateWheelSelector = () -> {
            List<String> wheelLabels = new ArrayList<>();
            for (int i = 0; i < wheels.size(); i++) {
                RadialWheelConfig w = wheels.get(i);
                wheelLabels.add((i + 1) + ". " + (w.name != null && !w.name.isEmpty() ? w.name : "Wheel " + (i + 1)));
            }
            ArrayAdapter<String> adapter = createThemedAdapter(context, wheelLabels.toArray(new String[0]), isDarkMode);
            spWheelSelect.setAdapter(adapter);
            if (currentWheelIndex[0] < wheels.size()) {
                spWheelSelect.setSelection(currentWheelIndex[0]);
            }
        };

        final Runnable populateCurrentWheel = () -> {
            if (currentWheelIndex[0] >= wheels.size()) currentWheelIndex[0] = 0;
            if (wheels.isEmpty()) return;

            RadialWheelConfig cfg = wheels.get(currentWheelIndex[0]);
            etWheelName.setText(cfg.name != null ? cfg.name : "");

            int scalePct = (int)((cfg.iconScale > 0 ? cfg.iconScale : 1.0f) * 100);
            sbWheelIconScale.setProgress(scalePct);
            tvWheelIconScale.setText(scalePct + "%");

            // Set trigger 1 binding selection
            int triggerIdx = 0;
            if (cfg.triggerBinding != null) {
                for (int i = 0; i < allBindings.length; i++) {
                    if (allBindings[i] == cfg.triggerBinding) {
                        triggerIdx = i;
                        break;
                    }
                }
            }
            spTriggerBinding.setSelection(triggerIdx);

            // Set trigger 2 binding selection
            int triggerIdx2 = 0;
            if (cfg.triggerBinding2 != null) {
                for (int i = 0; i < allBindings.length; i++) {
                    if (allBindings[i] == cfg.triggerBinding2) {
                        triggerIdx2 = i;
                        break;
                    }
                }
            }
            spTriggerBinding2.setSelection(triggerIdx2);

            // Populate slices
            llSlicesContainer.removeAllViews();
            for (int i = 0; i < RadialWheelConfig.MAX_SLICES; i++) {
                RadialWheelSlice slice = cfg.slices.get(i);
                View sliceView = LayoutInflater.from(context).inflate(R.layout.radial_wheel_slice_item, llSlicesContainer, false);

                TextView tvIndex = sliceView.findViewById(R.id.TVSliceIndex);
                ImageView ivIcon = sliceView.findViewById(R.id.IVSliceIcon);
                EditText etLabel = sliceView.findViewById(R.id.ETSliceLabel);
                etLabel.setBackgroundResource(editBgRes);
                etLabel.setTextColor(textColor);
                etLabel.setHintTextColor(hintColor);

                Spinner spBinding = sliceView.findViewById(R.id.SPSliceBinding);
                spBinding.setPopupBackgroundResource(popupBgRes);

                ImageView btComboExpand = sliceView.findViewById(R.id.BTComboExpand);
                LinearLayout llComboRow = sliceView.findViewById(R.id.LLComboRow);

                Spinner spBinding2 = sliceView.findViewById(R.id.SPSliceBinding2);
                spBinding2.setPopupBackgroundResource(popupBgRes);

                Spinner spBinding3 = sliceView.findViewById(R.id.SPSliceBinding3);
                spBinding3.setPopupBackgroundResource(popupBgRes);

                tvIndex.setText(String.valueOf(i + 1));
                etLabel.setText(slice.label != null ? slice.label : "");

                updateSliceIconView(context, ivIcon, slice.iconId);
                ivIcon.setOnClickListener(v -> showIconPickerDialog(context, slice, () -> updateSliceIconView(context, ivIcon, slice.iconId)));

                ArrayAdapter<String> sliceBindingAdapter = createThemedAdapter(context, bindingNames, isDarkMode);
                spBinding.setAdapter(sliceBindingAdapter);
                spBinding2.setAdapter(sliceBindingAdapter);
                spBinding3.setAdapter(sliceBindingAdapter);

                int bIdx = 0;
                if (slice.binding != null) {
                    for (int j = 0; j < allBindings.length; j++) {
                        if (allBindings[j] == slice.binding) {
                            bIdx = j;
                            break;
                        }
                    }
                }
                spBinding.setSelection(bIdx);

                int bIdx2 = 0;
                if (slice.binding2 != null) {
                    for (int j = 0; j < allBindings.length; j++) {
                        if (allBindings[j] == slice.binding2) {
                            bIdx2 = j;
                            break;
                        }
                    }
                }
                spBinding2.setSelection(bIdx2);

                int bIdx3 = 0;
                if (slice.binding3 != null) {
                    for (int j = 0; j < allBindings.length; j++) {
                        if (allBindings[j] == slice.binding3) {
                            bIdx3 = j;
                            break;
                        }
                    }
                }
                spBinding3.setSelection(bIdx3);

                boolean hasCombo = (slice.binding2 != null && slice.binding2 != Binding.NONE) || (slice.binding3 != null && slice.binding3 != Binding.NONE);
                llComboRow.setVisibility(hasCombo ? View.VISIBLE : View.GONE);
                btComboExpand.setImageResource(hasCombo ? R.drawable.icon_remove : R.drawable.icon_add);

                btComboExpand.setOnClickListener(v -> {
                    boolean isVisible = llComboRow.getVisibility() == View.VISIBLE;
                    llComboRow.setVisibility(isVisible ? View.GONE : View.VISIBLE);
                    btComboExpand.setImageResource(isVisible ? R.drawable.icon_add : R.drawable.icon_remove);
                    if (isVisible) {
                        spBinding2.setSelection(0);
                        spBinding3.setSelection(0);
                    }
                });

                llSlicesContainer.addView(sliceView);
            }
        };

        spWheelSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != currentWheelIndex[0]) {
                    saveCurrentWheelFromUI.run();
                    currentWheelIndex[0] = position;
                    populateCurrentWheel.run();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btAddWheel.setOnClickListener(v -> {
            if (wheels.size() >= MAX_WHEELS) {
                AppUtils.showToast(context, "Maximum 4 radial wheels per profile");
                return;
            }
            saveCurrentWheelFromUI.run();
            RadialWheelConfig newWheel = new RadialWheelConfig(wheels.size() + 1);
            newWheel.name = "Wheel " + (wheels.size() + 1);
            wheels.add(newWheel);
            currentWheelIndex[0] = wheels.size() - 1;
            updateWheelSelector.run();
            populateCurrentWheel.run();
        });

        btRemoveWheel.setOnClickListener(v -> {
            if (wheels.size() <= 1) {
                AppUtils.showToast(context, "Must have at least one radial wheel");
                return;
            }
            wheels.remove(currentWheelIndex[0]);
            if (currentWheelIndex[0] >= wheels.size()) currentWheelIndex[0] = wheels.size() - 1;
            updateWheelSelector.run();
            populateCurrentWheel.run();
        });

        updateWheelSelector.run();
        populateCurrentWheel.run();

        dialog.setOnConfirmCallback(() -> {
            saveCurrentWheelFromUI.run();
            if (profile != null) {
                profile.save();
            } else {
                RadialWheelConfig.saveGlobal(context, wheels);
            }
            AppUtils.showToast(context, "Radial Wheels Saved");
            if (onSaveCallback != null) onSaveCallback.run();
        });

        dialog.show();
    }

    private static void updateSliceIconView(Context context, ImageView iv, int iconId) {
        if (iv == null) return;
        if (iconId > 0) {
            Bitmap bmp = com.winlator.cmod.inputcontrols.CustomIconManager.getInstance(context).getIcon(iconId);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                if (iconId <= com.winlator.cmod.inputcontrols.CustomIconManager.BUILTIN_ICON_MAX) {
                    iv.setColorFilter(context.getResources().getColor(R.color.colorAccent));
                } else {
                    iv.setColorFilter(null);
                }
            } else {
                iv.setImageResource(R.drawable.icon_radial_wheel);
                iv.setColorFilter(null);
            }
        } else {
            iv.setImageResource(R.drawable.icon_radial_wheel);
            iv.setColorFilter(null);
        }
    }

    private static void showIconPickerDialog(Context context, RadialWheelSlice slice, Runnable onSelected) {
        com.winlator.cmod.inputcontrols.IconPickerDialog.show(context, slice.iconId, (newIconId) -> {
            slice.iconId = newIconId;
            if (onSelected != null) onSelected.run();
        });
    }

    private static ArrayAdapter<String> createThemedAdapter(Context context, String[] items, boolean isDarkMode) {
        int itemTextColor = isDarkMode ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
        return new ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(itemTextColor);
                    ((TextView) v).setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(itemTextColor);
                }
                return v;
            }
        };
    }
}