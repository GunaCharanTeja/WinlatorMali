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

        Spinner spWheelSelect = dialog.findViewById(R.id.SPWheelSelect);
        View btAddWheel = dialog.findViewById(R.id.BTAddWheel);
        View btRemoveWheel = dialog.findViewById(R.id.BTRemoveWheel);
        EditText etWheelName = dialog.findViewById(R.id.ETWheelName);
        Spinner spTriggerBinding = dialog.findViewById(R.id.SPTriggerBinding);
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

        ArrayAdapter<String> triggerAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, bindingNames);
        triggerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTriggerBinding.setAdapter(triggerAdapter);

        final int[] currentWheelIndex = {0};

        // Method to save current UI state into the active wheel config
        final Runnable saveCurrentWheelFromUI = () -> {
            if (currentWheelIndex[0] < 0 || currentWheelIndex[0] >= wheels.size()) return;
            RadialWheelConfig cfg = wheels.get(currentWheelIndex[0]);
            cfg.name = etWheelName.getText().toString().trim();

            int trigPos = spTriggerBinding.getSelectedItemPosition();
            if (trigPos >= 0 && trigPos < allBindings.length) {
                cfg.triggerBinding = allBindings[trigPos];
            }

            int childCount = llSlicesContainer.getChildCount();
            for (int i = 0; i < childCount && i < cfg.slices.size(); i++) {
                View sliceView = llSlicesContainer.getChildAt(i);
                EditText etLabel = sliceView.findViewById(R.id.ETSliceLabel);
                Spinner spBinding = sliceView.findViewById(R.id.SPSliceBinding);
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
            }
        };

        final Runnable updateWheelSelector = () -> {
            List<String> wheelLabels = new ArrayList<>();
            for (int i = 0; i < wheels.size(); i++) {
                RadialWheelConfig w = wheels.get(i);
                wheelLabels.add((i + 1) + ". " + (w.name != null && !w.name.isEmpty() ? w.name : "Wheel " + (i + 1)));
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, wheelLabels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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

            // Set trigger binding selection
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

            // Populate slices
            llSlicesContainer.removeAllViews();
            for (int i = 0; i < RadialWheelConfig.MAX_SLICES; i++) {
                RadialWheelSlice slice = cfg.slices.get(i);
                View sliceView = LayoutInflater.from(context).inflate(R.layout.radial_wheel_slice_item, llSlicesContainer, false);

                TextView tvIndex = sliceView.findViewById(R.id.TVSliceIndex);
                ImageView ivIcon = sliceView.findViewById(R.id.IVSliceIcon);
                EditText etLabel = sliceView.findViewById(R.id.ETSliceLabel);
                Spinner spBinding = sliceView.findViewById(R.id.SPSliceBinding);

                tvIndex.setText(String.valueOf(i + 1));
                etLabel.setText(slice.label != null ? slice.label : "");

                updateSliceIconView(context, ivIcon, slice.iconId);
                ivIcon.setOnClickListener(v -> showIconPickerDialog(context, slice, () -> updateSliceIconView(context, ivIcon, slice.iconId)));

                ArrayAdapter<String> sliceBindingAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, bindingNames);
                sliceBindingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spBinding.setAdapter(sliceBindingAdapter);

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
            } else {
                iv.setImageResource(R.drawable.icon_radial_wheel);
            }
        } else {
            iv.setImageResource(R.drawable.icon_radial_wheel);
        }
    }

    private static void showIconPickerDialog(Context context, RadialWheelSlice slice, Runnable onSelected) {
        com.winlator.cmod.inputcontrols.IconPickerDialog.show(context, slice.iconId, (newIconId) -> {
            slice.iconId = newIconId;
            if (onSelected != null) onSelected.run();
        });
    }
}