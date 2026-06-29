package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    public static final byte MOUSE_WHEEL_DELTA = 120;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ColorFilter colorFilter = new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private int snappingSize;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final Bitmap[] icons = new Bitmap[17];
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    private final Map<Binding, Float> keyPressures = new HashMap<>();
    private final Map<Binding, Boolean> keyStates = new HashMap<>();
    private boolean showTouchscreenControls = true;
    private Handler timeoutHandler;
    private Runnable hideControlsRunnable;
    private SharedPreferences preferences;
    private ControlElement stickElement;
    private boolean focusOnStick = false;

    public boolean isFocusedOnStick() {
        return focusOnStick;
    }

    public void setFocusOnStick(boolean focus) {
        this.focusOnStick = focus;
        invalidate();
    }

    @SuppressLint("ResourceType")
    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
    }

    @SuppressLint("ResourceType")
    public InputControlsView(Context context, Handler timeoutHandler, Runnable hideControlsRunnable) {
        super(context);
        this.timeoutHandler = timeoutHandler;
        this.hideControlsRunnable = hideControlsRunnable;
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width, height;
        if (stickElement != null && isFocusedOnStick()) {
            Rect boundingBox = stickElement.getBoundingBox();
            width = boundingBox.width();
            height = boundingBox.height();
        } else {
            width = getWidth();
            height = getHeight();
        }

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = width / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (stickElement != null) stickElement.draw(canvas);

        if (profile != null && showTouchscreenControls && !isFocusedOnStick()) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            for (ControlElement element : profile.getElements()) {
                element.draw(canvas);
            }
        }
        super.onDraw(canvas);
    }

    public void resetStickPosition() {
        if (stickElement != null) {
            Rect boundingBox = stickElement.getBoundingBox();
            stickElement.setCurrentPosition(boundingBox.centerX(), boundingBox.centerY());
            invalidate();
        }
    }

    public void initializeStickElement(float x, float y, float scale) {
        stickElement = new ControlElement(this);
        stickElement.setType(ControlElement.Type.STICK);
        stickElement.setX((int) x);
        stickElement.setY((int) y);
        stickElement.setScale(scale);
        invalidate();
    }

    public void updateStickPosition(float x, float y) {
        if (stickElement != null) {
            stickElement.getCurrentPosition().x = x;
            stickElement.getCurrentPosition().y = y;
            invalidate();
        }
    }

    public ControlElement getStickElement() {
        return stickElement;
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xff000000);
        canvas.drawColor(Color.BLACK);
        paint.setAntiAlias(false);
        paint.setColor(0xff303030);

        int width = getMaxWidth();
        int height = getMaxHeight();
        for (int i = 0; i < width; i += snappingSize) canvas.drawLine(i, 0, i, height, paint);
        for (int i = 0; i < height; i += snappingSize) canvas.drawLine(0, i, width, i, paint);

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(0xff424242);
        for (int i = 0; i < height; i += snappingSize * 2) canvas.drawLine(cx, i, cx, i + snappingSize, paint);
        for (int i = 0; i < width; i += snappingSize * 2) canvas.drawLine(i, cy, i + snappingSize, cy, paint);
        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xffc62828);
        paint.setAntiAlias(false);
        canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
        canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);
        paint.setAntiAlias(true);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            ControlElement element = new ControlElement(this);
            element.setX(cursor.x);
            element.setY(cursor.y);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        }
        else return false;
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) for (ControlElement element : profile.getElements()) element.setSelected(false);
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        }
        else this.profile = null;
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    public int getPrimaryColor() {
        return Color.argb((int)(overlayOpacity * 255), 255, 255, 255);
    }

    public int getSecondaryColor() {
        return Color.argb((int)(overlayOpacity * 255), 2, 119, 189);
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) for (ControlElement element : profile.getElements()) if (element.containsPoint(x, y)) return element;
        return null;
    }

    public Paint getPaint() { return paint; }
    public Path getPath() { return path; }
    public ColorFilter getColorFilter() { return colorFilter; }
    public TouchpadView getTouchpadView() { return touchpadView; }
    public void setTouchpadView(TouchpadView touchpadView) { this.touchpadView = touchpadView; }
    public XServer getXServer() { return xServer; }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() { return (int)Mathf.roundTo(getWidth(), snappingSize); }
    public int getMaxHeight() { return (int)Mathf.roundTo(getHeight(), snappingSize); }

    @Override
    protected void onDetachedFromWindow() {
        if (mouseMoveTimer != null) mouseMoveTimer.cancel();
        synchronized(keyPressures) {
            for (Map.Entry<Binding, Boolean> entry : keyStates.entrySet()) {
                if (entry.getValue()) xServer.injectKeyRelease(entry.getKey().keycode);
            }
            keyStates.clear();
            keyPressures.clear();
        }
        super.onDetachedFromWindow();
    }

    private void createMouseMoveTimer() {
        final WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (mouseMoveTimer == null && profile != null && winHandler != null) {
            final float cursorSpeed = profile.getCursorSpeed();
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                private float velocityX = 0;
                private float velocityY = 0;
                private static final float ACCELERATION = 0.2f;
                private int pulseCounter = 0;

                @Override
                public void run() {
                    if (mouseMoveOffset.x != 0 || mouseMoveOffset.y != 0 || velocityX != 0 || velocityY != 0) {
                        velocityX += (mouseMoveOffset.x - velocityX) * ACCELERATION;
                        velocityY += (mouseMoveOffset.y - velocityY) * ACCELERATION;
                        if (mouseMoveOffset.x == 0 && Math.abs(velocityX) < 0.05f) velocityX = 0;
                        if (mouseMoveOffset.y == 0 && Math.abs(velocityY) < 0.05f) velocityY = 0;

                        if (velocityX != 0 || velocityY != 0) {
                            float magnitude = (float) Math.sqrt(velocityX * velocityX + velocityY * velocityY);
                            float factor = (float) Math.pow(magnitude, 1.5f) * cursorSpeed * 15.0f;
                            int dx = Math.round((velocityX / magnitude) * factor);
                            int dy = Math.round((velocityY / magnitude) * factor);
                            if (dx != 0 || dy != 0) {
                                if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                                else xServer.injectPointerMoveDelta(dx, dy);
                            }
                        }
                    }

                    synchronized(keyPressures) {
                        // 1. Process active keys and update their pulse state
                        for (Map.Entry<Binding, Float> entry : keyPressures.entrySet()) {
                            Binding binding = entry.getKey();
                            float pressure = entry.getValue();
                            
                            // High-frequency distributed pulsing (Bresenham-based)
                            // Pattern: pressure=0.5 -> OFF, ON, OFF, ON...
                            boolean newState = (int)((pulseCounter + 1) * pressure) > (int)(pulseCounter * pressure);
                            
                            Boolean currentState = keyStates.get(binding);
                            if (currentState == null) currentState = false;
                            
                            if (newState != currentState) {
                                if (newState) xServer.injectKeyPress(binding.keycode);
                                else xServer.injectKeyRelease(binding.keycode);
                                keyStates.put(binding, newState);
                            }
                        }
                        
                        // 2. Cleanup: Release any keys that are no longer being pressed at all
                        if (!keyStates.isEmpty()) {
                            java.util.Iterator<Map.Entry<Binding, Boolean>> it = keyStates.entrySet().iterator();
                            while (it.hasNext()) {
                                Map.Entry<Binding, Boolean> entry = it.next();
                                Binding binding = entry.getKey();
                                if (!keyPressures.containsKey(binding)) {
                                    if (entry.getValue()) xServer.injectKeyRelease(binding.keycode);
                                    it.remove();
                                }
                            }
                        }
                    }
                    pulseCounter = (pulseCounter + 1) % 60;
                }
            }, 0, 1000 / 60);
        }
    }

    private void processJoystickInput(ExternalController controller) {
        processStickAxes(controller, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, controller.state.thumbLX, controller.state.thumbLY);
        processStickAxes(controller, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, controller.state.thumbRX, controller.state.thumbRY);

        final float dpadX = controller.state.getDPadX();
        final float dpadY = controller.state.getDPadY();
        final int[] dpadAxes = {MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        final float[] dpadValues = {dpadX, dpadY};
        for (int i = 0; i < 2; i++) {
            if (Math.abs(dpadValues[i]) > 0.5f) {
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(dpadAxes[i], Mathf.sign(dpadValues[i]));
                ExternalControllerBinding cb = controller.getControllerBinding(keyCode);
                if (cb != null) handleInputEvent(controller, cb.getBinding(), true, 1.0f, false);
            } else {
                for (byte sign = -1; sign <= 1; sign += 2) {
                    int keyCode = ExternalControllerBinding.getKeyCodeForAxis(dpadAxes[i], sign);
                    ExternalControllerBinding cb = controller.getControllerBinding(keyCode);
                    if (cb != null) handleInputEvent(controller, cb.getBinding(), false, 0, false);
                }
            }
        }
        processTriggerInput(controller, controller.state.triggerL, KeyEvent.KEYCODE_BUTTON_L2, false);
        processTriggerInput(controller, controller.state.triggerR, KeyEvent.KEYCODE_BUTTON_R2, false);
        if (xServer != null) xServer.getWinHandler().sendGamepadState(controller);
    }

    private void processStickAxes(ExternalController controller, int axisX, int axisY, float valX, float valY) {
        // Detect if this stick is used for Mouse Move or Gamepad for 360-degree analog support
        int keyCodeUp = ExternalControllerBinding.getKeyCodeForAxis(axisY, (byte)-1);
        ExternalControllerBinding cbUp = controller.getControllerBinding(keyCodeUp);
        Binding bUp = cbUp != null ? cbUp.getBinding() : Binding.NONE;

        if (bUp.isMouseMove()) {
            handleMouseMovement(valX, valY);
            return;
        }

        if (bUp.isGamepad() && bUp.name().contains("THUMB")) {
            handleStickInput(controller, bUp, valX, valY);
            return;
        }

        float magnitude = (float)Math.sqrt(valX * valX + valY * valY);
        if (magnitude > ControlElement.STICK_DEAD_ZONE) {
            float angle = (float)Math.toDegrees(Math.atan2(valY, valX));
            if (angle < 0) angle += 360;
            final boolean[] states = {angle >= 202.5f && angle <= 337.5f, angle >= 292.5f || angle <= 67.5f, angle >= 22.5f && angle <= 157.5f, angle >= 112.5f && angle <= 247.5f};
            final int[] axes = {axisY, axisX, axisY, axisX};
            final byte[] signs = {-1, 1, 1, -1};
            final float[] components = {Math.abs(valY), Math.abs(valX), Math.abs(valY), Math.abs(valX)};
            for (int i = 0; i < 4; i++) {
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], signs[i]);
                ExternalControllerBinding cb = controller.getControllerBinding(keyCode);
                if (cb != null) handleInputEvent(controller, cb.getBinding(), states[i], components[i], false);
            }
        } else {
            for (int axis : new int[]{axisY, axisX}) {
                for (byte sign = -1; sign <= 1; sign += 2) {
                    int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, sign);
                    ExternalControllerBinding cb = controller.getControllerBinding(keyCode);
                    if (cb != null) handleInputEvent(controller, cb.getBinding(), false, 0, false);
                }
            }
        }
    }

    private void processTriggerInput(ExternalController controller, float value, int keyCode, boolean sendUpdate) {
        ExternalControllerBinding binding = controller.getControllerBinding(keyCode);
        if (binding != null) handleInputEvent(controller, binding.getBinding(), value > ControlElement.STICK_DEAD_ZONE, value, sendUpdate);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                processJoystickInput(controller);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        resetTouchscreenTimeout();
        if (editMode && readyToDraw) {
            float x = event.getX(), y = event.getY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    ControlElement element = intersectElement(x, y);
                    moveCursor = element == null;
                    if (element != null) { offsetX = x - element.getX(); offsetY = y - element.getY(); }
                    selectElement(element);
                }
                case MotionEvent.ACTION_MOVE -> { if (selectedElement != null) { selectedElement.setX((int)Mathf.roundTo(x - offsetX, snappingSize)); selectedElement.setY((int)Mathf.roundTo(y - offsetY, snappingSize)); invalidate(); } }
                case MotionEvent.ACTION_UP -> { if (selectedElement != null) profile.save(); if (moveCursor) cursor.set((int)Mathf.roundTo(x, snappingSize), (int)Mathf.roundTo(y, snappingSize)); invalidate(); }
            }
        }
        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex(), pointerId = event.getPointerId(actionIndex), actionMasked = event.getActionMasked();
            boolean handled = false;
            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    float x = event.getX(actionIndex), y = event.getY(actionIndex);
                    touchpadView.setPointerButtonLeftEnabled(true);
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            handled = true;
                            if (preferences.getBoolean("touchscreen_haptics_enabled", true)) {
                                Vibrator v = (Vibrator)getContext().getSystemService(Context.VIBRATOR_SERVICE);
                                if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                            }
                        }
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) touchpadView.setPointerButtonLeftEnabled(false);
                    }
                    if (!handled) touchpadView.onTouchEvent(event);
                    invalidate();
                }
                case MotionEvent.ACTION_MOVE -> {
                    for (byte i = 0; i < event.getPointerCount(); i++) {
                        handled = false;
                        for (ControlElement element : profile.getElements()) if (element.handleTouchMove(event.getPointerId(i), event.getX(i), event.getY(i))) handled = true;
                        if (!handled) touchpadView.onTouchEvent(event);
                    }
                    invalidate();
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId)) handled = true;
                    if (!handled) touchpadView.onTouchEvent(event);
                    invalidate();
                }
            }
        }
        return true;
    }

    private void resetTouchscreenTimeout() {
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000);
        }
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null) {
                ExternalControllerBinding cb = controller.getControllerBinding(event.getKeyCode());
                if (cb != null) {
                    handleInputEvent(controller, cb.getBinding(), event.getAction() == KeyEvent.ACTION_DOWN);
                    return true;
                }
            }
        }
        return false;
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) { handleInputEvent(null, binding, isActionDown, 0); }
    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown) { handleInputEvent(controller, binding, isActionDown, 0); }

    public void handleStickInput(ExternalController controller, Binding firstBinding, float deltaX, float deltaY) {
        if (!firstBinding.isGamepad()) return;
        GamepadState state = (controller != null) ? controller.remappedState : profile.getGamepadState();
        if (firstBinding.name().contains("LEFT_THUMB")) {
            state.thumbLX = deltaX; state.thumbLY = deltaY;
        } else if (firstBinding.name().contains("RIGHT_THUMB")) {
            state.thumbRX = deltaX; state.thumbRY = deltaY;
        } else if (firstBinding.name().contains("DPAD")) {
            state.dpad[0] = deltaY <= -ControlElement.DPAD_DEAD_ZONE;
            state.dpad[1] = deltaX >= ControlElement.DPAD_DEAD_ZONE;
            state.dpad[2] = deltaY >= ControlElement.DPAD_DEAD_ZONE;
            state.dpad[3] = deltaX <= -ControlElement.DPAD_DEAD_ZONE;
        }
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) {
            if (controller != null) winHandler.sendGamepadState(controller);
            else winHandler.sendGamepadState();
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) { handleInputEvent(null, binding, isActionDown, offset); }

    public void handleMouseMovement(float deltaX, float deltaY) {
        mouseMoveOffset.set(deltaX, deltaY);
        if (deltaX != 0 || deltaY != 0) createMouseMoveTimer();
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(controller, binding, isActionDown, offset, true);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset, boolean sendUpdate) {
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (binding.isGamepad()) {
            GamepadState state = (controller != null) ? controller.remappedState : profile.getGamepadState();
            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= ExternalController.IDX_BUTTON_R2) {
                if (buttonIdx == ExternalController.IDX_BUTTON_L2) state.triggerL = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                else if (buttonIdx == ExternalController.IDX_BUTTON_R2) state.triggerR = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                else state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding.name().contains("THUMB")) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                if (binding.name().contains("LEFT_THUMB_U")) state.thumbLY = isActionDown ? -val : 0;
                else if (binding.name().contains("LEFT_THUMB_D")) state.thumbLY = isActionDown ? val : 0;
                else if (binding.name().contains("LEFT_THUMB_L")) state.thumbLX = isActionDown ? -val : 0;
                else if (binding.name().contains("LEFT_THUMB_R")) state.thumbLX = isActionDown ? val : 0;
                else if (binding.name().contains("RIGHT_THUMB_U")) state.thumbRY = isActionDown ? -val : 0;
                else if (binding.name().contains("RIGHT_THUMB_D")) state.thumbRY = isActionDown ? val : 0;
                else if (binding.name().contains("RIGHT_THUMB_L")) state.thumbRX = isActionDown ? -val : 0;
                else if (binding.name().contains("RIGHT_THUMB_R")) state.thumbRX = isActionDown ? val : 0;
            }
            else if (binding.name().contains("DPAD")) {
                if (binding == Binding.GAMEPAD_DPAD_UP) state.dpad[0] = isActionDown;
                else if (binding == Binding.GAMEPAD_DPAD_RIGHT) state.dpad[1] = isActionDown;
                else if (binding == Binding.GAMEPAD_DPAD_DOWN) state.dpad[2] = isActionDown;
                else if (binding == Binding.GAMEPAD_DPAD_LEFT) state.dpad[3] = isActionDown;
            }

            if (winHandler != null && sendUpdate) { if (controller != null) winHandler.sendGamepadState(controller); else winHandler.sendGamepadState(); }
        }
        else {
            if (binding.name().startsWith("MOUSE_MOVE")) {
                float value = (offset != 0) ? Math.abs(offset) : 1.0f;
                if (binding == Binding.MOUSE_MOVE_LEFT) mouseMoveOffset.x = isActionDown ? -value : 0;
                else if (binding == Binding.MOUSE_MOVE_RIGHT) mouseMoveOffset.x = isActionDown ? value : 0;
                else if (binding == Binding.MOUSE_MOVE_UP) mouseMoveOffset.y = isActionDown ? -value : 0;
                else if (binding == Binding.MOUSE_MOVE_DOWN) mouseMoveOffset.y = isActionDown ? value : 0;

                if (isActionDown) createMouseMoveTimer();
            }
            else {
                Pointer.Button pb = binding.getPointerButton();
                if (pb != null) {
                    if (isActionDown) { if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.getFlagFor(pb, true), 0, 0, pb == Pointer.Button.BUTTON_SCROLL_UP ? MOUSE_WHEEL_DELTA : (pb == Pointer.Button.BUTTON_SCROLL_DOWN ? -MOUSE_WHEEL_DELTA : 0)); else xServer.injectPointerButtonPress(pb); }
                    else { if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.getFlagFor(pb, false), 0, 0, 0); else xServer.injectPointerButtonRelease(pb); }
                }
                else {
                    synchronized(keyPressures) {
                        if (isActionDown && offset > 0 && offset < 0.95f) {
                            keyPressures.put(binding, offset);
                            createMouseMoveTimer();
                        } else {
                            keyPressures.remove(binding);
                            Boolean pulsedState = keyStates.remove(binding);
                            if (pulsedState != null && pulsedState) xServer.injectKeyRelease(binding.keycode);

                            if (isActionDown) xServer.injectKeyPress(binding.keycode);
                            else xServer.injectKeyRelease(binding.keycode);
                        }
                    }
                }
            }
        }
    }

    public Bitmap getIcon(byte id) {
        if (icons[id] == null) {
            try (InputStream is = getContext().getAssets().open("inputcontrols/icons/"+id+".png")) { icons[id] = BitmapFactory.decodeStream(is); }
            catch (IOException e) {}
        }
        return icons[id];
    }
}
