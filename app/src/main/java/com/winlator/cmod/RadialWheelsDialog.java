package com.winlator.cmod;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.RadialWheelConfig;
import com.winlator.cmod.inputcontrols.RadialWheelSlice;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3: Steam Deck-Style Radial Action Wheels configuration dialog.
 */
public class RadialWheelsDialog {
    private static final int MAX_WHEELS = 4;

    public static void show(Context context, ControlsProfile profile, Runnable onSaveCallback) {
        if (profile == null) return;

        ArrayList<RadialWheelConfig> wheels = profile.getWheels();
        if (wheels.isEmpty()) {
            RadialWheelConfig defaultWheel = new RadialWheelConfig(1);
            defaultWheel.name = "Weapon Wheel";
            defaultWheel.triggerBinding = Binding.GAMEPAD_BUTTON_L2;
            wheels.add(defaultWheel);
        }

        View root = LayoutInflater.from(context).inflate(R.layout.radial_wheels_dialog, null);
        Spinner spWheelSelect = root.findViewById(R.id.SPWheelSelect);
        Button btAddWheel = root.findViewById(R.id.BTAddWheel);
        Button btRemoveWheel = root.findViewById(R.id.BTRemoveWheel);
        EditText etWheelName = root.findViewById(R.id.ETWheelName);
        Spinner spTriggerBinding = root.findViewById(R.id.SPTriggerBinding);
        LinearLayout llSlicesContainer = root.findViewById(R.id.LLSlicesContainer);

        // Binding names list
        Binding[] allBindings = Binding.values();
        String[] bindingNames = new String[allBindings.length];
        for (int i = 0; i < allBindings.length; i++) {
            bindingNames[i] = allBindings[i].toString();
        }

        ArrayAdapter<String> triggerAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, bindingNames);
        triggerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTriggerBinding.setAdapter(triggerAdapter);

        final int[] currentWheelIndex = {0};

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

        final Runnable[] populateCurrentWheel = new Runnable[1];
        populateCurrentWheel[0] = () -> {
            if (currentWheelIndex[0] >= wheels.size()) currentWheelIndex[0] = 0;
            if (wheels.isEmpty()) return;

            RadialWheelConfig cfg = wheels.get(currentWheelIndex[0]);
            etWheelName.setText(cfg.name != null ? cfg.name : "");

            // Set trigger binding
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
                final int sliceIdx = i;
                RadialWheelSlice slice = cfg.slices.get(i);
                View sliceView = LayoutInflater.from(context).inflate(R.layout.radial_wheel_slice_item, llSlicesContainer, false);

                TextView tvIndex = sliceView.findViewById(R.id.TVSliceIndex);
                EditText etLabel = sliceView.findViewById(R.id.ETSliceLabel);
                Spinner spBinding = sliceView.findViewById(R.id.SPSliceBinding);

                tvIndex.setText(String.valueOf(i + 1));
                etLabel.setText(slice.label != null ? slice.label : "");

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

                etLabel.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        slice.label = s.toString();
                    }
                    @Override public void afterTextChanged(Editable s) {}
                });

                spBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        slice.binding = allBindings[position];
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });

                llSlicesContainer.addView(sliceView);
            }
        };

        etWheelName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (currentWheelIndex[0] < wheels.size()) {
                    wheels.get(currentWheelIndex[0]).name = s.toString();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        spTriggerBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (currentWheelIndex[0] < wheels.size()) {
                    wheels.get(currentWheelIndex[0]).triggerBinding = allBindings[position];
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spWheelSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentWheelIndex[0] = position;
                populateCurrentWheel[0].run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btAddWheel.setOnClickListener(v -> {
            if (wheels.size() >= MAX_WHEELS) {
                AppUtils.showToast(context, "Maximum 4 radial wheels per profile");
                return;
            }
            RadialWheelConfig newWheel = new RadialWheelConfig(wheels.size() + 1);
            newWheel.name = "Wheel " + (wheels.size() + 1);
            wheels.add(newWheel);
            currentWheelIndex[0] = wheels.size() - 1;
            updateWheelSelector.run();
            populateCurrentWheel[0].run();
        });

        btRemoveWheel.setOnClickListener(v -> {
            if (wheels.size() <= 1) {
                AppUtils.showToast(context, "Must have at least one radial wheel");
                return;
            }
            wheels.remove(currentWheelIndex[0]);
            if (currentWheelIndex[0] >= wheels.size()) currentWheelIndex[0] = wheels.size() - 1;
            updateWheelSelector.run();
            populateCurrentWheel[0].run();
        });

        updateWheelSelector.run();
        populateCurrentWheel[0].run();

        new AlertDialog.Builder(context)
                .setTitle("Radial Action Wheels")
                .setView(root)
                .setPositiveButton("Save", (dialog, which) -> {
                    profile.save();
                    AppUtils.showToast(context, "Radial Wheels Saved");
                    if (onSaveCallback != null) onSaveCallback.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}