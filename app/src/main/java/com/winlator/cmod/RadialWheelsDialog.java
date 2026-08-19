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
 * Styled with native Winlator ContentDialog theme, light/dark mode support,
 * and adaptive gamepad binding filtering when external controllers are connected.
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

        ContentDialog dialog = new ContentDialog(context, R.layout.radial_wheels_dialog);
        dialog.setTitle("Radial Action Wheels");
        dialog.setIcon(R.drawable.icon_radial_wheel);

        Spinner spWheelSelect = dialog.findViewById(R.id.SPWheelSelect);
        View btAddWheel = dialog.findViewById(R.id.BTAddWheel);
        View btRemoveWheel = dialog.findViewById(R.id.BTRemoveWheel);
        EditText etWheelName = dialog.findViewById(R.id.ETWheelName);
        Spinner spTriggerBinding = dialog.findViewById(R.id.SPTriggerBinding);
        LinearLayout llSlicesContainer = dialog.findViewById(R.id.LLSlicesContainer);

        // Detect if external controller is connected to show only gamepad buttons
        ArrayList<ExternalController> controllers = ExternalController.getControllers();
        boolean hasExternalController = !controllers.isEmpty();

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
            profile.save();
            AppUtils.showToast(context, "Radial Wheels Saved");
            if (onSaveCallback != null) onSaveCallback.run();
        });

        dialog.show();
    }

    private static void updateSliceIconView(Context context, ImageView iv, int iconId) {
        if (iv == null) return;
        if (iconId > 0) {
            try (InputStream is = context.getAssets().open("inputcontrols/icons/" + iconId + ".png")) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                iv.setImageBitmap(bmp);
            } catch (IOException e) {
                iv.setImageResource(R.drawable.icon_radial_wheel);
            }
        } else {
            iv.setImageResource(R.drawable.icon_radial_wheel);
        }
    }

    private static void showIconPickerDialog(Context context, RadialWheelSlice slice, Runnable onSelected) {
        byte[] iconIds = new byte[0];
        try {
            String[] filenames = context.getAssets().list("inputcontrols/icons/");
            if (filenames != null) {
                iconIds = new byte[filenames.length];
                for (int i = 0; i < filenames.length; i++) {
                    iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
                }
            }
        } catch (Exception e) {}
        Arrays.sort(iconIds);

        ContentDialog iconDialog = new ContentDialog(context);
        iconDialog.setTitle("Select Slice Icon");
        iconDialog.setIcon(R.drawable.icon_radial_wheel);

        FrameLayout frameLayout = iconDialog.findViewById(R.id.FrameLayout);
        if (frameLayout == null) return;
        frameLayout.setVisibility(View.VISIBLE);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout llList = new LinearLayout(context);
        llList.setOrientation(LinearLayout.VERTICAL);
        llList.setPadding(16, 16, 16, 16);
        scrollView.addView(llList);
        frameLayout.addView(scrollView);

        int size = (int) UnitUtils.dpToPx(44);
        int margin = (int) UnitUtils.dpToPx(4);
        int padding = (int) UnitUtils.dpToPx(6);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, margin, margin, margin);

        // "No Icon (Text Only)" button
        Button btNoIcon = new Button(context);
        btNoIcon.setText("None (Text / Emoji Only)");
        btNoIcon.setTextSize(12);
        btNoIcon.setOnClickListener(v -> {
            slice.iconId = 0;
            if (onSelected != null) onSelected.run();
            iconDialog.dismiss();
        });
        llList.addView(btNoIcon);

        // Grid rows of icons
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int count = 0;
        for (final byte id : iconIds) {
            ImageView iv = new ImageView(context);
            iv.setLayoutParams(params);
            iv.setPadding(padding, padding, padding, padding);
            iv.setBackgroundResource(R.drawable.icon_background);
            iv.setSelected(slice.iconId == id);

            try (InputStream is = context.getAssets().open("inputcontrols/icons/" + id + ".png")) {
                iv.setImageBitmap(BitmapFactory.decodeStream(is));
            } catch (IOException e) {}

            iv.setOnClickListener(v -> {
                slice.iconId = id;
                if (onSelected != null) onSelected.run();
                iconDialog.dismiss();
            });

            row.addView(iv);
            count++;
            if (count % 5 == 0) {
                llList.addView(row);
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
            }
        }
        if (row.getChildCount() > 0) {
            llList.addView(row);
        }

        iconDialog.show();
    }
}