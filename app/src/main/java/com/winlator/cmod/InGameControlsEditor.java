package com.winlator.cmod;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
                    inputControlsView.invalidate();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                profile.save();
            }
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
        int dialogWidth = (int) UnitUtils.dpToPx(340);
        int dialogHeight = Math.min((int) UnitUtils.dpToPx(480), screenHeight - (int) UnitUtils.dpToPx(32));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean isDarkMode = preferences.getBoolean("dark_mode", true);
        int bgColor = isDarkMode ? 0xFF1E1E1E : 0xFFF5F5F5;
        int headerBgColor = isDarkMode ? 0xFF2A2A2A : 0xFFE0E0E0;
        int accentColor = activity.getResources().getColor(isDarkMode ? R.color.colorAccentDark : R.color.colorAccent);

        final PopupWindow popupWindow = new PopupWindow(activity);
        popupWindow.setElevation(8.0f);
        popupWindow.setWidth(dialogWidth);
        popupWindow.setHeight(dialogHeight);
        popupWindow.setContentView(view);
        popupWindow.setFocusable(true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));

        int initialX = (element.getX() > screenWidth / 2) ? (int) UnitUtils.dpToPx(24) : (screenWidth - dialogWidth - (int) UnitUtils.dpToPx(24));
        int initialY = (int) UnitUtils.dpToPx(36);
        final int[] windowPos = new int[]{initialX, initialY};

        View dragHeader = view.findViewById(R.id.LLDragHeader);
        if (dragHeader != null) {
            dragHeader.setBackgroundColor(headerBgColor);
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
                            windowPos[0] = startWinX + (int) dx;
                            windowPos[1] = startWinY + (int) dy;
                            popupWindow.update(windowPos[0], windowPos[1], -1, -1);
                            return true;
                    }
                    return false;
                }
            });
        }

        currentPopup = popupWindow;
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, windowPos[0], windowPos[1]);
        popupWindow.setOnDismissListener(() -> {
            currentIconListLayout = null;
            profile.save();
            inputControlsView.invalidate();
            currentPopup = null;
        });
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, final Runnable callback) {
        final boolean[] isInitializing = {true};
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal());
        spinner.post(() -> isInitializing[0] = false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializing[0]) return;
                ControlElement.Type type = ControlElement.Type.values()[position];
                if (type != element.getType()) {
                    element.setType(type);
                    callback.run();
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        final boolean[] isInitializing = {true};
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal());
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

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        final boolean[] isInitializing = {true};
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
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
        final com.winlator.cmod.inputcontrols.CustomIconManager iconManager = com.winlator.cmod.inputcontrols.CustomIconManager.getInstance(activity);
        final ControlElement element = inputControlsView.getSelectedElement();
        List<Integer> iconIds = iconManager.getAllIconIds();

        int size = (int) UnitUtils.dpToPx(40);
        int margin = (int) UnitUtils.dpToPx(2);
        int padding = (int) UnitUtils.dpToPx(4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        // 1. Clear / Reset to No Icon
        ImageView clearImageView = new ImageView(activity);
        clearImageView.setLayoutParams(params);
        clearImageView.setPadding(padding, padding, padding, padding);
        clearImageView.setBackgroundResource(R.drawable.icon_background);
        clearImageView.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        clearImageView.setColorFilter(activity.getResources().getColor(R.color.colorAccent));
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

        // 2. Add [+] Add from Gallery button
        ImageView addImageView = new ImageView(activity);
        addImageView.setLayoutParams(params);
        addImageView.setPadding(padding, padding, padding, padding);
        addImageView.setBackgroundResource(R.drawable.icon_background);
        addImageView.setImageResource(R.drawable.icon_add);
        addImageView.setColorFilter(activity.getResources().getColor(R.color.colorAccent));
        addImageView.setOnClickListener((v) -> pickCustomIcon());
        parent.addView(addImageView);

        for (final int id : iconIds) {
            final ImageView imageView = new ImageView(activity);
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
                if (element != null) {
                    element.setIconId(id);
                    profile.save();
                    inputControlsView.invalidate();
                }
            });

            imageView.setOnLongClickListener((v) -> {
                if (id > com.winlator.cmod.inputcontrols.CustomIconManager.BUILTIN_ICON_MAX) {
                    ContentDialog.confirm(activity, "Delete custom icon #" + id + "?", () -> {
                        iconManager.deleteCustomIcon(id);
                        if (element != null && element.getIconId() == id) {
                            element.setIconId(0);
                            profile.save();
                            inputControlsView.invalidate();
                        }
                        loadIcons(parent, element != null ? element.getIconId() : 0);
                        AppUtils.showToast(activity, "Custom icon deleted");
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

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        container.removeAllViews();

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            loadBindingSpinner(element, container, 0, R.string.binding);
            loadBindingSpinner(element, container, 1, R.string.binding_secondary);
            loadBindingSpinner(element, container, 2, "Combo 3 (Optional)");
            loadBindingSpinner(element, container, 3, "Combo 4 (Optional)");
        } else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_right);
            loadBindingSpinner(element, container, 2, R.string.binding_down);
            loadBindingSpinner(element, container, 3, R.string.binding_left);
        }
    }

    private void loadBindingSpinner(final ControlElement element, LinearLayout container, final int index, Object title) {
        View view = LayoutInflater.from(activity).inflate(R.layout.binding_field, container, false);
        TextView tvTitle = view.findViewById(R.id.TVTitle);
        if (title instanceof Integer) {
            tvTitle.setText((Integer) title);
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

            sBinding.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
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

            @Override public void onNothingSelected(AdapterView<?> parent) {}
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

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        container.addView(view);
    }
}
