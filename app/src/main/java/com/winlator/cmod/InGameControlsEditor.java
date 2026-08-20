package com.winlator.cmod;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
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

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.NumberPicker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

public final class InGameControlsEditor implements View.OnClickListener {
    private final XServerDisplayActivity activity;
    private final FrameLayout container;
    private final InputControlsView inputControlsView;
    private final ControlsProfile profile;
    private final Runnable onDone;
    private final FrameLayout root;
    private PopupWindow currentPopup;
    private LinearLayout currentIconListLayout;

    public InGameControlsEditor(
            XServerDisplayActivity activity,
            FrameLayout container,
            InputControlsView inputControlsView,
            ControlsProfile profile,
            Runnable onDone) {
        this.activity = activity;
        this.container = container;
        this.inputControlsView = inputControlsView;
        this.profile = profile;
        this.onDone = onDone;

        root = (FrameLayout) LayoutInflater.from(activity).inflate(
                R.layout.in_game_controls_editor_overlay, container, false);
        container.addView(root);
        root.bringToFront();

        TextView tvProfileName = root.findViewById(R.id.TVProfileName);
        if (tvProfileName != null && profile != null) {
            tvProfileName.setText(profile.getName());
        }

        root.findViewById(R.id.BTAddElement).setOnClickListener(this);
        root.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        root.findViewById(R.id.BTElementSettings).setOnClickListener(this);
        root.findViewById(R.id.BTReset).setOnClickListener(this);
        root.findViewById(R.id.BTRadialWheel).setOnClickListener(this);
        root.findViewById(R.id.BTDone).setOnClickListener(this);

        inputControlsView.setDrawOpaqueBackground(false);
        inputControlsView.setEditMode(true);
        inputControlsView.invalidate();
    }

    public boolean isOpen() {
        return root.getParent() != null;
    }

    public void save() {
        if (profile != null) profile.save();
    }

    public void dispose() {
        if (currentPopup != null && currentPopup.isShowing()) {
            currentPopup.dismiss();
            currentPopup = null;
        }
        save();
        inputControlsView.setDrawOpaqueBackground(true);
        inputControlsView.setEditMode(false);
        inputControlsView.invalidate();
        if (root.getParent() instanceof FrameLayout) {
            ((FrameLayout) root.getParent()).removeView(root);
        }
    }

    public boolean handleBack() {
        if (currentPopup != null && currentPopup.isShowing()) {
            currentPopup.dismiss();
            currentPopup = null;
            return true;
        }
        dispose();
        if (onDone != null) onDone.run();
        return true;
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.BTAddElement) {
            if (!inputControlsView.addElement()) {
                AppUtils.showToast(activity, R.string.no_profile_selected);
            }
        } else if (id == R.id.BTRemoveElement) {
            if (!inputControlsView.removeElement()) {
                AppUtils.showToast(activity, R.string.no_control_element_selected);
            }
        } else if (id == R.id.BTElementSettings) {
            ControlElement selectedElement = inputControlsView.getSelectedElement();
            if (selectedElement != null) {
                showControlElementSettings(v);
            } else {
                AppUtils.showToast(activity, R.string.no_control_element_selected);
            }
        } else if (id == R.id.BTReset) {
            ContentDialog.confirm(activity, "Reset all buttons to original positions?", () -> {
                if (profile != null) {
                    boolean resetDefault = profile.resetToDefaultTemplate(inputControlsView);
                    if (!resetDefault) {
                        profile.loadElements(inputControlsView);
                    }
                    inputControlsView.invalidate();
                    AppUtils.showToast(activity, "Buttons reset to original layout");
                }
            });
        } else if (id == R.id.BTRadialWheel) {
            if (profile != null) {
                RadialWheelsDialog.show(activity, profile, () -> {
                    inputControlsView.invalidate();
                });
            }
        } else if (id == R.id.BTDone) {
            dispose();
            if (onDone != null) onDone.run();
        }
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        if (element == null) return;

        View view = LayoutInflater.from(activity).inflate(R.layout.control_element_settings, null);

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
            } else if (type == ControlElement.Type.RANGE_BUTTON) {
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
            element.setOrientation((byte) (checkedId == R.id.RBVertical ? 1 : 0));
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
                tvScale.setText(progress + "%");
                if (fromUser) {
                    progress = (int) Mathf.roundTo(progress, 5);
                    seekBar.setProgress(progress);
                    element.setScale(progress / 100.0f);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbScale.setProgress((int) (element.getScale() * 100));

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setToggleSwitch(isChecked);
            profile.save();
        });

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        etCustomText.setText(element.getText());
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
                    profile.save();
                    inputControlsView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
                    profile.save();
                    inputControlsView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
                    profile.save();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        updateLayout.run();

        currentPopup = AppUtils.showPopupWindow(anchorView, view, 340, 0);
        currentPopup.setOnDismissListener(() -> {
            currentIconListLayout = null;
            String text = etCustomText.getText().toString().trim();
            int iconId = 0;
            for (int i = 0; i < llIconList.getChildCount(); i++) {
                View child = llIconList.getChildAt(i);
                if (child.isSelected() && child.getTag() instanceof Integer) {
                    iconId = (Integer) child.getTag();
                    break;
                }
            }

            element.setText(text);
            element.setIconId(iconId);
            profile.save();
            inputControlsView.invalidate();
            currentPopup = null;
        });
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, final Runnable callback) {
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setType(ControlElement.Type.values()[position]);
                callback.run();
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ControlElement.Shape shape = ControlElement.Shape.values()[position];
                if (shape != element.getShape()) {
                    element.setShape(shape);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public void addCustomIcon(android.net.Uri uri) {
        int newId = com.winlator.cmod.inputcontrols.CustomIconManager.getInstance(activity).importCustomIcon(uri);
        if (newId > 0) {
            ControlElement el = inputControlsView.getSelectedElement();
            if (el != null) {
                el.setIconId(newId);
                save();
                inputControlsView.invalidate();
                if (currentIconListLayout != null) {
                    loadIcons(currentIconListLayout, newId);
                }
                AppUtils.showToast(activity, "Custom icon imported and applied!");
            }
        }
    }

    public void pickCustomIcon() {
        activity.launchInGameIconPicker();
    }

    private void loadIcons(final LinearLayout parent, int selectedId) {
        parent.removeAllViews();
        com.winlator.cmod.inputcontrols.CustomIconManager iconManager = com.winlator.cmod.inputcontrols.CustomIconManager.getInstance(activity);
        List<Integer> iconIds = iconManager.getAllIconIds();

        int size = (int) UnitUtils.dpToPx(40);
        int margin = (int) UnitUtils.dpToPx(2);
        int padding = (int) UnitUtils.dpToPx(4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        // 1. Add [+] Add from Gallery button
        ImageView addImageView = new ImageView(activity);
        addImageView.setLayoutParams(params);
        addImageView.setPadding(padding, padding, padding, padding);
        addImageView.setBackgroundResource(R.drawable.icon_background);
        addImageView.setImageResource(R.drawable.icon_add);
        addImageView.setColorFilter(activity.getResources().getColor(R.color.colorAccent));
        addImageView.setOnClickListener((v) -> pickCustomIcon());
        parent.addView(addImageView);

        for (final int id : iconIds) {
            ImageView imageView = new ImageView(activity);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setTag(Integer.valueOf(id));
            imageView.setSelected(id == selectedId);
            if (id <= com.winlator.cmod.inputcontrols.CustomIconManager.BUILTIN_ICON_MAX) {
                imageView.setColorFilter(activity.getResources().getColor(R.color.colorAccent));
            } else {
                imageView.setColorFilter(null);
            }
            imageView.setOnClickListener((v) -> {
                for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
                imageView.setSelected(true);
            });

            Bitmap bmp = iconManager.getIcon(id);
            if (bmp != null) imageView.setImageBitmap(bmp);

            parent.addView(imageView);
        }
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        container.removeAllViews();

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            loadBindingSpinner(element, container, 0, R.string.binding);
            loadBindingSpinner(element, container, 1, R.string.binding_secondary);
        } else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_right);
            loadBindingSpinner(element, container, 2, R.string.binding_down);
            loadBindingSpinner(element, container, 3, R.string.binding_left);
        }
    }

    private void loadBindingSpinner(final ControlElement element, LinearLayout container, final int index, int titleResId) {
        View view = LayoutInflater.from(activity).inflate(R.layout.binding_field, container, false);
        ((TextView) view.findViewById(R.id.TVTitle)).setText(titleResId);
        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        Runnable update = () -> {
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

            sBinding.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
        };

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        Binding selectedBinding = element.getBindingAt(index);
        if (selectedBinding.isKeyboard()) {
            sBindingType.setSelection(0, false);
        } else if (selectedBinding.isMouse()) {
            sBindingType.setSelection(1, false);
        } else if (selectedBinding.isGamepad()) {
            sBindingType.setSelection(2, false);
        }

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        update.run();
        container.addView(view);
    }
}
