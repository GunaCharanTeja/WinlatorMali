package com.winlator.cmod.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.core.graphics.ColorUtils;

import com.winlator.cmod.core.CubicBezierInterpolator;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 2.0f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD, DYNAMIC_STICK, MOUSE_AREA, BUTTON_GRID, EXPANDABLE_BUTTON;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }

        public static Type parse(String name) {
            if (name == null || name.isEmpty()) return BUTTON;
            try {
                return valueOf(name.trim().toUpperCase().replace("-", "_"));
            } catch (IllegalArgumentException e) {
                String upper = name.trim().toUpperCase();
                if (upper.contains("DYNAMIC") || upper.contains("STICK")) return DYNAMIC_STICK;
                if (upper.contains("MOUSE") || upper.contains("TRACK")) return TRACKPAD;
                if (upper.contains("PAD") || upper.contains("DPAD")) return D_PAD;
                if (upper.contains("RANGE")) return RANGE_BUTTON;
                if (upper.contains("GRID")) return BUTTON_GRID;
                if (upper.contains("EXPAND")) return EXPANDABLE_BUTTON;
                return BUTTON;
            }
        }
    }
    public enum Shape {
        CIRCLE, CAPSULE, OVAL, ROUND_RECT, RECT, SQUARE, HEXAGON, DIAMOND, OCTAGON;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }

        public static Shape parse(String name) {
            if (name == null || name.isEmpty()) return CIRCLE;
            try {
                return valueOf(name.trim().toUpperCase().replace(" ", "_").replace("-", "_"));
            } catch (IllegalArgumentException e) {
                return CIRCLE;
            }
        }
    }
    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);
        public final byte max;

        Range(int max) {
            this.max = (byte)max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }

        public static Range parse(String name) {
            if (name == null || name.isEmpty()) return FROM_A_TO_Z;
            try {
                return valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return FROM_A_TO_Z;
            }
        }
    }
    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private short x;
    private short y;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";
    private int iconId = 0;
    private boolean customIconAsButton = false;
    private float widthScale = 1.0f;
    private float heightScale = 1.0f;
    private int touchPadding = 0;
    private Range range;
    private byte orientation;
    private PointF currentPosition;
    private RangeScroller scroller;
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        setBinding(Binding.NONE);
        scroller = null;

        if (type == Type.STICK || type == Type.DYNAMIC_STICK) {
            bindings[0] = Binding.KEY_W;
            bindings[1] = Binding.KEY_D;
            bindings[2] = Binding.KEY_S;
            bindings[3] = Binding.KEY_A;
        }
        else if (type == Type.D_PAD) {
            bindings[0] = Binding.GAMEPAD_DPAD_UP;
            bindings[1] = Binding.GAMEPAD_DPAD_RIGHT;
            bindings[2] = Binding.GAMEPAD_DPAD_DOWN;
            bindings[3] = Binding.GAMEPAD_DPAD_LEFT;
        }
        else if (type == Type.TRACKPAD || type == Type.MOUSE_AREA) {
            bindings[0] = Binding.GAMEPAD_RIGHT_THUMB_UP;
            bindings[1] = Binding.GAMEPAD_RIGHT_THUMB_RIGHT;
            bindings[2] = Binding.GAMEPAD_RIGHT_THUMB_DOWN;
            bindings[3] = Binding.GAMEPAD_RIGHT_THUMB_LEFT;
        }
        else if (type == Type.RANGE_BUTTON) {
            scroller = new RangeScroller(inputControlsView, this);
        }

        text = "";
        iconId = 0;
        customIconAsButton = false;
        range = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public int getBindingCount() {
        return bindings.length;
    }

    public void setBindingCount(int bindingCount) {
        bindings = new Binding[bindingCount];
        setBinding(Binding.NONE);
        states = new boolean[bindingCount];
        boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    public Binding getBindingAt(int index) {
        return index < bindings.length ? bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= bindings.length) {
            int oldLength = bindings.length;
            bindings = Arrays.copyOf(bindings, index+1);
            Arrays.fill(bindings, oldLength-1, bindings.length, Binding.NONE);
            states = new boolean[bindings.length];
            boundingBoxNeedsUpdate = true;
        }
        bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(bindings, binding);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        boundingBoxNeedsUpdate = true;
    }

    private int cachedHalfWidth = 0;
    private int cachedHalfHeight = 0;
    private final Rect dstRect = new Rect();

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short)x;
        if (!boundingBoxNeedsUpdate && cachedHalfWidth > 0 && cachedHalfHeight > 0) {
            boundingBox.set(this.x - cachedHalfWidth, this.y - cachedHalfHeight, this.x + cachedHalfWidth, this.y + cachedHalfHeight);
        } else {
            boundingBoxNeedsUpdate = true;
        }
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short)y;
        if (!boundingBoxNeedsUpdate && cachedHalfWidth > 0 && cachedHalfHeight > 0) {
            boundingBox.set(this.x - cachedHalfWidth, this.y - cachedHalfHeight, this.x + cachedHalfWidth, this.y + cachedHalfHeight);
        } else {
            boundingBoxNeedsUpdate = true;
        }
    }

    public boolean isSelected() {
        return selected;
    }

    public int getCurrentPointerId() {
        return currentPointerId;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public int getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = iconId;
    }

    public boolean isCustomIconAsButton() {
        return customIconAsButton;
    }

    public void setCustomIconAsButton(boolean customIconAsButton) {
        this.customIconAsButton = customIconAsButton;
        boundingBoxNeedsUpdate = true;
    }

    public float getWidthScale() {
        return widthScale;
    }

    public void setWidthScale(float widthScale) {
        this.widthScale = widthScale;
        boundingBoxNeedsUpdate = true;
    }

    public float getHeightScale() {
        return heightScale;
    }

    public void setHeightScale(float heightScale) {
        this.heightScale = heightScale;
        boundingBoxNeedsUpdate = true;
    }

    public int getTouchPadding() {
        return touchPadding;
    }

    public void setTouchPadding(int touchPadding) {
        this.touchPadding = touchPadding;
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
            case EXPANDABLE_BUTTON:
            case BUTTON_GRID:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case CAPSULE:
                        halfWidth = (int)(snappingSize * 3.5f);
                        halfHeight = (int)(snappingSize * 2.2f);
                        break;
                    case OVAL:
                        halfWidth = (int)(snappingSize * 3.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case SQUARE:
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                    case HEXAGON:
                        halfWidth = (int)(snappingSize * 3.2f);
                        halfHeight = (int)(snappingSize * 2.8f);
                        break;
                    case DIAMOND:
                    case OCTAGON:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                if (customIconAsButton && iconId > 0) {
                    Bitmap icon = inputControlsView.getIcon(iconId);
                    if (icon != null && icon.getWidth() > 0 && icon.getHeight() > 0) {
                        float aspect = (float)icon.getWidth() / (float)icon.getHeight();
                        if (aspect > 1.0f) {
                            halfWidth = (int)(halfHeight * aspect);
                        } else if (aspect < 1.0f) {
                            halfHeight = (int)(halfWidth / aspect);
                        }
                    }
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            }
            case TRACKPAD:
            case MOUSE_AREA:
            case STICK:
            case DYNAMIC_STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                halfWidth = snappingSize * ((bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
        }

        cachedHalfWidth = (int)(halfWidth * scale * widthScale);
        cachedHalfHeight = (int)(halfHeight * scale * heightScale);
        boundingBox.set(x - cachedHalfWidth, y - cachedHalfHeight, x + cachedHalfWidth, y + cachedHalfHeight);
        boundingBoxNeedsUpdate = false;
        return boundingBox;
    }



    public String getDisplayText() {
        ControlStylePreset stylePreset = ControlStylePreset.WINLATOR_MALI;
        if (inputControlsView != null && inputControlsView.getProfile() != null) {
            stylePreset = inputControlsView.getProfile().getStylePreset();
        }

        if (text != null && !text.isEmpty()) {
            if (stylePreset == ControlStylePreset.XBOX) {
                if (text.equalsIgnoreCase("L1")) return "LB";
                if (text.equalsIgnoreCase("R1")) return "RB";
                if (text.equalsIgnoreCase("L2")) return "LT";
                if (text.equalsIgnoreCase("R2")) return "RT";
                if (text.equalsIgnoreCase("L3")) return "LS";
                if (text.equalsIgnoreCase("R3")) return "RS";
                if (text.equalsIgnoreCase("SELECT") || text.equalsIgnoreCase("BACK")) return "VIEW";
                if (text.equalsIgnoreCase("START")) return "MENU";
            } else if (stylePreset == ControlStylePreset.PLAYSTATION) {
                if (text.equalsIgnoreCase("LB")) return "L1";
                if (text.equalsIgnoreCase("RB")) return "R1";
                if (text.equalsIgnoreCase("LT")) return "L2";
                if (text.equalsIgnoreCase("RT")) return "R2";
                if (text.equalsIgnoreCase("LS")) return "L3";
                if (text.equalsIgnoreCase("RS")) return "R3";
                if (text.equalsIgnoreCase("VIEW")) return "SHARE";
                if (text.equalsIgnoreCase("MENU")) return "OPTIONS";
            } else if (stylePreset == ControlStylePreset.RETRO_ARCADE) {
                if (text.equalsIgnoreCase("SELECT") || text.equalsIgnoreCase("BACK") || text.equalsIgnoreCase("VIEW") || text.equalsIgnoreCase("SHARE")) return "COIN";
                if (text.equalsIgnoreCase("START") || text.equalsIgnoreCase("MENU") || text.equalsIgnoreCase("OPTIONS")) return "1P";
            } else if (stylePreset == ControlStylePreset.CYBERPUNK) {
                if (text.equalsIgnoreCase("SELECT") || text.equalsIgnoreCase("BACK") || text.equalsIgnoreCase("VIEW") || text.equalsIgnoreCase("SHARE")) return "SYS";
                if (text.equalsIgnoreCase("START") || text.equalsIgnoreCase("MENU") || text.equalsIgnoreCase("OPTIONS")) return "RUN";
            }
            return text;
        }
        else {
            Binding binding = getBindingAt(0);
            if (stylePreset == ControlStylePreset.XBOX) {
                switch (binding) {
                    case GAMEPAD_BUTTON_L1: return "LB";
                    case GAMEPAD_BUTTON_R1: return "RB";
                    case GAMEPAD_BUTTON_L2: return "LT";
                    case GAMEPAD_BUTTON_R2: return "RT";
                    case GAMEPAD_BUTTON_L3: return "LS";
                    case GAMEPAD_BUTTON_R3: return "RS";
                    case GAMEPAD_BUTTON_SELECT: return "VIEW";
                    case GAMEPAD_BUTTON_START: return "MENU";
                }
            } else if (stylePreset == ControlStylePreset.PLAYSTATION) {
                switch (binding) {
                    case GAMEPAD_BUTTON_L1: return "L1";
                    case GAMEPAD_BUTTON_R1: return "R1";
                    case GAMEPAD_BUTTON_L2: return "L2";
                    case GAMEPAD_BUTTON_R2: return "R2";
                    case GAMEPAD_BUTTON_L3: return "L3";
                    case GAMEPAD_BUTTON_R3: return "R3";
                    case GAMEPAD_BUTTON_SELECT: return "SHARE";
                    case GAMEPAD_BUTTON_START: return "OPTIONS";
                }
            } else if (stylePreset == ControlStylePreset.RETRO_ARCADE) {
                switch (binding) {
                    case GAMEPAD_BUTTON_SELECT: return "COIN";
                    case GAMEPAD_BUTTON_START: return "1P";
                }
            } else if (stylePreset == ControlStylePreset.CYBERPUNK) {
                switch (binding) {
                    case GAMEPAD_BUTTON_SELECT: return "SYS";
                    case GAMEPAD_BUTTON_START: return "RUN";
                }
            }
            String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
            if (text.length() > 7) {
                String[] parts = text.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) sb.append(part.charAt(0));
                return (binding.isMouse() ? "M" : "")+ sb;
            }
            else return text;
        }
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        String text = "";
        switch (range) {
            case FROM_A_TO_Z:
                text = String.valueOf((char)(65 + index));
                break;
            case FROM_0_TO_9:
                text = String.valueOf((index + 1) % 10);
                break;
            case FROM_F1_TO_F12:
                text = "F"+(index + 1);
                break;
            case FROM_NP0_TO_NP9:
                text = "NP"+((index + 1) % 10);
                break;
        }
        return text;
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        float overlayOpacity = inputControlsView.getOverlayOpacity();

        boolean pressed = false;
        for (boolean state : states) {
            if (state) {
                pressed = true;
                break;
            }
        }

        float alpha = overlayOpacity * 255.0f;

        ControlStylePreset stylePreset = ControlStylePreset.WINLATOR_MALI;
        if (inputControlsView != null && inputControlsView.getProfile() != null) {
            stylePreset = inputControlsView.getProfile().getStylePreset();
        }

        int customBg = 0xFF000000;
        int customStroke = 0xFFE6E6E6;
        int customContent = Color.WHITE;
        int customShadow = Color.WHITE;

        Binding b0 = getBindingAt(0);
        String label = getDisplayText().toUpperCase();

        switch (stylePreset) {
            case XBOX: {
                if (b0 == Binding.GAMEPAD_BUTTON_A || label.equals("A")) {
                    customStroke = 0xFF00E676; // Emerald Green
                    customBg = 0xFF003814;
                    customContent = 0xFF00E676;
                } else if (b0 == Binding.GAMEPAD_BUTTON_B || label.equals("B")) {
                    customStroke = 0xFFFF1744; // Crimson Red
                    customBg = 0xFF3E000A;
                    customContent = 0xFFFF1744;
                } else if (b0 == Binding.GAMEPAD_BUTTON_X || label.equals("X")) {
                    customStroke = 0xFF2979FF; // Royal Blue
                    customBg = 0xFF00194A;
                    customContent = 0xFF2979FF;
                } else if (b0 == Binding.GAMEPAD_BUTTON_Y || label.equals("Y")) {
                    customStroke = 0xFFFFEA00; // Amber Yellow
                    customBg = 0xFF383300;
                    customContent = 0xFFFFEA00;
                } else if (b0.isGamepad() && (b0.name().contains("THUMB") || b0.name().contains("DPAD"))) {
                    customStroke = 0xFF42A5F5;
                    customBg = 0xFF0D1B2A;
                    customContent = 0xFFE0E0E0;
                } else {
                    customStroke = 0xFFB0BEC5;
                    customBg = 0xFF1C242B;
                    customContent = 0xFFECEFF1;
                }
                customShadow = customStroke;
                break;
            }
            case PLAYSTATION: {
                if (b0 == Binding.GAMEPAD_BUTTON_Y || label.equals("Y") || label.equals("△") || label.equals("TRI") || label.equals("TRIANGLE")) {
                    customStroke = 0xFF00E676; // Sony Mint Green (Triangle)
                    customBg = 0xFF002E11;
                    customContent = 0xFF00E676;
                } else if (b0 == Binding.GAMEPAD_BUTTON_B || label.equals("B") || label.equals("◯") || label.equals("O") || label.equals("CIRCLE")) {
                    customStroke = 0xFFFF3D00; // Sony Crimson Red (Circle)
                    customBg = 0xFF330A00;
                    customContent = 0xFFFF3D00;
                } else if (b0 == Binding.GAMEPAD_BUTTON_A || label.equals("A") || label.equals("✕") || label.equals("CROSS")) {
                    customStroke = 0xFF00B0FF; // Sony Electric Blue (Cross)
                    customBg = 0xFF001A33;
                    customContent = 0xFF00B0FF;
                } else if (b0 == Binding.GAMEPAD_BUTTON_X || label.equals("X") || label.equals("▢") || label.equals("SQ") || label.equals("SQUARE")) {
                    customStroke = 0xFFFF4081; // Sony Neon Pink (Square)
                    customBg = 0xFF330018;
                    customContent = 0xFFFF4081;
                } else {
                    customStroke = 0xFF78909C;
                    customBg = 0xFF0B0E14;
                    customContent = 0xFFECEFF1;
                }
                customShadow = customStroke;
                break;
            }
            case CYBERPUNK: {
                if (label.matches("(?i)L[12]|R[12]|LT|RT|LB|RB") || b0 == Binding.GAMEPAD_BUTTON_Y || b0 == Binding.GAMEPAD_BUTTON_B) {
                    customStroke = 0xFFFF007F; // Neon Magenta
                    customBg = 0xFF2A0015;
                    customContent = 0xFFFF007F;
                } else {
                    customStroke = 0xFF00E5FF; // Electric Cyan
                    customBg = 0xFF001F24;
                    customContent = 0xFF00E5FF;
                }
                customShadow = customStroke;
                break;
            }
            case RETRO_ARCADE: {
                if (b0 == Binding.GAMEPAD_BUTTON_A || label.equals("A") || label.equals("1")) {
                    customStroke = 0xFFE53935; // Arcade Red
                    customBg = 0xFFB71C1C;
                    customContent = Color.WHITE;
                } else if (b0 == Binding.GAMEPAD_BUTTON_B || label.equals("B") || label.equals("2")) {
                    customStroke = 0xFF1E88E5; // Arcade Blue
                    customBg = 0xFF0D47A1;
                    customContent = Color.WHITE;
                } else if (b0 == Binding.GAMEPAD_BUTTON_X || label.equals("X") || label.equals("3")) {
                    customStroke = 0xFFFDD835; // Arcade Yellow
                    customBg = 0xFFF57F17;
                    customContent = 0xFF212121;
                } else if (b0 == Binding.GAMEPAD_BUTTON_Y || label.equals("Y") || label.equals("4")) {
                    customStroke = 0xFF43A047; // Arcade Green
                    customBg = 0xFF1B5E20;
                    customContent = Color.WHITE;
                } else {
                    customStroke = 0xFFEEEEEE;
                    customBg = 0xFF37474F;
                    customContent = Color.WHITE;
                }
                customShadow = customStroke;
                break;
            }
            case STEALTH: {
                customBg = 0x00000000;
                customStroke = 0xFFFFFFFF;
                customContent = 0xFFFFFFFF;
                customShadow = 0x00000000;
                break;
            }
            case WINLATOR_MALI:
            default: {
                customBg = 0xFF000000;
                customStroke = selected ? inputControlsView.getSecondaryColor() : 0xFFE6E6E6;
                customContent = Color.WHITE;
                customShadow = Color.WHITE;
                break;
            }
        }

        int backgroundColor;
        if (stylePreset == ControlStylePreset.STEALTH) {
            backgroundColor = pressed ? ColorUtils.setAlphaComponent(0xFFFFFFFF, (int)(alpha * 0.25f)) : 0x00000000;
        } else {
            backgroundColor = ColorUtils.setAlphaComponent(customBg, (int)(alpha * (pressed ? 0.85f : 0.45f)));
        }

        int primaryColor = selected ? 0xFF00E5FF : customStroke;
        int strokeColor = ColorUtils.setAlphaComponent(primaryColor, (int)(alpha * (pressed ? 1.0f : (selected ? 1.0f : 0.8f))));

        int contentColor = ColorUtils.setAlphaComponent(customContent, (int)(alpha * 0.95f));
        int shadowColor = ColorUtils.setAlphaComponent(customShadow, (int)(alpha * (pressed ? 0.35f : 0.15f)));

        paint.setAntiAlias(true);
        float strokeWidth = snappingSize * 0.08f * scale;
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);

        Rect boundingBox = getBoundingBox();
        float cx = boundingBox.centerX();
        float cy = boundingBox.centerY();

        canvas.save();
        if (pressed) canvas.scale(0.96f, 0.96f, cx, cy);
        float shadowRadius = snappingSize * (pressed ? 0.4f : 0.2f) * scale;
        paint.setShadowLayer(shadowRadius, 0, 0, shadowColor);

        switch (type) {
            case BUTTON:
            case EXPANDABLE_BUTTON:
            case BUTTON_GRID: {
                boolean imageAsButton = customIconAsButton && iconId > 0;
                boolean iconDrawn = false;
                if (imageAsButton) {
                    paint.clearShadowLayer();
                    iconDrawn = drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, true);
                    if (iconDrawn) {
                        if (selected) {
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setStrokeWidth(Math.max(4f, snappingSize * 0.12f * scale));
                            drawShape(canvas, boundingBox, 0xFF00E5FF, paint);
                        }
                    }
                }

                if (!imageAsButton || !iconDrawn) {
                    if (getDisplayText().matches("(?i)L[12]|R[12]|LT|RT|LB|RB")) {
                        float radius = boundingBox.height() * 0.5f;
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(backgroundColor);
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(strokeColor);
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                    } else {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(backgroundColor);
                        drawShape(canvas, boundingBox, backgroundColor, paint);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(strokeColor);
                        drawShape(canvas, boundingBox, strokeColor, paint);
                    }

                    paint.clearShadowLayer();
                    if (iconId > 0) {
                        drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, false);
                    } else {
                        String text = getDisplayText();
                        boolean iconDrawnCustom = false;

                        if (stylePreset == ControlStylePreset.XBOX) {
                            if (text.equalsIgnoreCase("START") || text.equalsIgnoreCase("MENU") || b0 == Binding.GAMEPAD_BUTTON_START) {
                                drawXboxMenuIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (text.equalsIgnoreCase("SELECT") || text.equalsIgnoreCase("BACK") || text.equalsIgnoreCase("VIEW") || b0 == Binding.GAMEPAD_BUTTON_SELECT) {
                                drawXboxViewIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            }
                        } else if (stylePreset == ControlStylePreset.PLAYSTATION) {
                            if (b0 == Binding.GAMEPAD_BUTTON_Y || text.equalsIgnoreCase("Y") || text.equals("△") || text.equalsIgnoreCase("TRIANGLE")) {
                                drawPlayStationTriangle(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (b0 == Binding.GAMEPAD_BUTTON_B || text.equalsIgnoreCase("B") || text.equals("◯") || text.equalsIgnoreCase("CIRCLE")) {
                                drawPlayStationCircle(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (b0 == Binding.GAMEPAD_BUTTON_A || text.equalsIgnoreCase("A") || text.equals("✕") || text.equalsIgnoreCase("CROSS")) {
                                drawPlayStationCross(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (b0 == Binding.GAMEPAD_BUTTON_X || text.equalsIgnoreCase("X") || text.equals("▢") || text.equalsIgnoreCase("SQUARE")) {
                                drawPlayStationSquare(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (b0 == Binding.GAMEPAD_BUTTON_START || text.equalsIgnoreCase("START") || text.equalsIgnoreCase("OPTIONS") || text.equalsIgnoreCase("MENU")) {
                                drawPlayStationOptionsIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (b0 == Binding.GAMEPAD_BUTTON_SELECT || text.equalsIgnoreCase("SELECT") || text.equalsIgnoreCase("SHARE") || text.equalsIgnoreCase("BACK") || text.equalsIgnoreCase("CREATE")) {
                                drawPlayStationShareIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            }
                        } else if (stylePreset == ControlStylePreset.RETRO_ARCADE) {
                            if (text.equalsIgnoreCase("COIN") || b0 == Binding.GAMEPAD_BUTTON_SELECT) {
                                drawArcadeCoinIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (text.equalsIgnoreCase("1P") || text.equalsIgnoreCase("START") || b0 == Binding.GAMEPAD_BUTTON_START) {
                                drawArcadePlayerIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            }
                        } else if (stylePreset == ControlStylePreset.CYBERPUNK) {
                            if (text.equalsIgnoreCase("RUN") || text.equalsIgnoreCase("START") || b0 == Binding.GAMEPAD_BUTTON_START) {
                                drawCyberpunkPowerIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            } else if (text.equalsIgnoreCase("SYS") || text.equalsIgnoreCase("SELECT") || b0 == Binding.GAMEPAD_BUTTON_SELECT) {
                                drawCyberpunkHexGridIcon(canvas, cx, cy, boundingBox.width(), paint, contentColor);
                                iconDrawnCustom = true;
                            }
                        }

                        if (!iconDrawnCustom) {
                            if (stylePreset == ControlStylePreset.XBOX) {
                                paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                            } else {
                                paint.setTypeface(Typeface.DEFAULT);
                            }
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), snappingSize * 2 * scale));
                            paint.setTextAlign(Paint.Align.CENTER);
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(contentColor);
                            canvas.drawText(text, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                            paint.setTypeface(Typeface.DEFAULT);
                        }
                    }
                }
                break;
            }
            case D_PAD: {
                float offsetX = snappingSize * 2 * scale;
                float offsetY = snappingSize * 3 * scale;
                float start = snappingSize * scale;
                Path path = inputControlsView.getPath();
                path.reset();

                path.moveTo(cx, cy - start);
                path.lineTo(cx - offsetX, cy - offsetY);
                path.lineTo(cx - offsetX, boundingBox.top);
                path.lineTo(cx + offsetX, boundingBox.top);
                path.lineTo(cx + offsetX, cy - offsetY);
                path.close();

                path.moveTo(cx - start, cy);
                path.lineTo(cx - offsetY, cy - offsetX);
                path.lineTo(boundingBox.left, cy - offsetX);
                path.lineTo(boundingBox.left, cy + offsetX);
                path.lineTo(cx - offsetY, cy + offsetX);
                path.close();

                path.moveTo(cx, cy + start);
                path.lineTo(cx - offsetX, cy + offsetY);
                path.lineTo(cx - offsetX, boundingBox.bottom);
                path.lineTo(cx + offsetX, boundingBox.bottom);
                path.lineTo(cx + offsetX, cy + offsetY);
                path.close();

                path.moveTo(cx + start, cy);
                path.lineTo(cx + offsetY, cy - offsetX);
                path.lineTo(boundingBox.right, cy - offsetX);
                path.lineTo(boundingBox.right, cy + offsetX);
                path.lineTo(cx + offsetY, cy + offsetX);
                path.close();

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(backgroundColor);
                canvas.drawPath(path, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(strokeColor);
                canvas.drawPath(path, paint);

                if (stylePreset == ControlStylePreset.XBOX) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(ColorUtils.setAlphaComponent(customStroke, (int)(alpha * 0.35f)));
                    paint.setStrokeWidth(Math.max(2f, strokeWidth * 0.75f));
                    canvas.drawCircle(cx, cy, snappingSize * 2.2f * scale, paint);
                }
                break;
            }
            case RANGE_BUTTON: {
                Range range = getRange();
                float radius = snappingSize * 0.75f * scale;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Path path = inputControlsView.getPath();
                path.reset();

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(backgroundColor);
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(strokeColor);
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                paint.clearShadowLayer();
                canvas.save();
                path.addRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                canvas.clipPath(path);

                if (orientation == 0) {
                    float lineTop = boundingBox.top + strokeWidth * 0.5f;
                    float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
                    float startX = boundingBox.left - (scrollOffset % elementSize);

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        if (index < 0) index += range.max;
                        if (startX > boundingBox.left && startX < boundingBox.right) {
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setColor(strokeColor);
                            canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                        }
                        String text = getRangeTextForIndex(range, index);
                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(contentColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, startX + elementSize * 0.5f, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                        }
                        startX += elementSize;
                    }
                } else {
                    float lineLeft = boundingBox.left + strokeWidth * 0.5f;
                    float lineRight = boundingBox.right - strokeWidth * 0.5f;
                    float startY = boundingBox.top - (scrollOffset % elementSize);

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        if (index < 0) index += range.max;
                        if (startY > boundingBox.top && startY < boundingBox.bottom) {
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setColor(strokeColor);
                            canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                        }
                        String text = getRangeTextForIndex(range, index);
                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(contentColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, x, startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                        }
                        startY += elementSize;
                    }
                }
                canvas.restore();
                break;
            }
            case STICK:
            case DYNAMIC_STICK: {
                float outerRadius = boundingBox.height() * 0.5f;
                paint.setShadowLayer(0, 0, 0, 0);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, (int) (alpha * 0.35f)));
                canvas.drawCircle(cx, cy, outerRadius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ColorUtils.setAlphaComponent(customStroke, (int) (alpha * 0.75f)));
                canvas.drawCircle(cx, cy, outerRadius, paint);

                float thumbstickX = getCurrentPosition().x;
                float thumbstickY = getCurrentPosition().y;
                float thumbRadius = snappingSize * 3.0f * scale;

                paint.clearShadowLayer();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(backgroundColor);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(strokeColor);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);
                break;
            }
            case TRACKPAD:
            case MOUSE_AREA: {
                float radius = boundingBox.height() * 0.15f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(backgroundColor);
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(strokeColor);
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                break;
            }
        }
        canvas.restore();
    }

    private void drawShape(Canvas canvas, Rect rect, int color, Paint paint) {
        paint.setColor(color);
        float halfWidth = rect.width() * 0.5f;
        float halfHeight = rect.height() * 0.5f;
        float cx = rect.centerX();
        float cy = rect.centerY();

        switch (shape) {
            case CIRCLE:
                if (Math.abs(halfWidth - halfHeight) > 1.0f) {
                    canvas.drawOval(new RectF(rect), paint);
                } else {
                    canvas.drawCircle(cx, cy, halfWidth, paint);
                }
                break;
            case RECT:
                canvas.drawRect(rect, paint);
                break;
            case ROUND_RECT: {
                float radius = Math.min(halfWidth, halfHeight) * 0.5f;
                canvas.drawRoundRect(new RectF(rect), radius, radius, paint);
                break;
            }
            case CAPSULE: {
                float capRadius = Math.min(halfWidth, halfHeight);
                canvas.drawRoundRect(new RectF(rect), capRadius, capRadius, paint);
                break;
            }
            case OVAL:
                canvas.drawOval(new RectF(rect), paint);
                break;
            case SQUARE: {
                float squareRadius = Math.min(halfWidth, halfHeight) * 0.25f;
                canvas.drawRoundRect(new RectF(rect), squareRadius, squareRadius, paint);
                break;
            }
            case HEXAGON: {
                Path hexPath = inputControlsView.getPath();
                hexPath.reset();
                for (int i = 0; i < 6; i++) {
                    double angle = Math.PI / 3.0 * i - Math.PI / 6.0;
                    float px = cx + (float)(halfWidth * Math.cos(angle));
                    float py = cy + (float)(halfHeight * Math.sin(angle));
                    if (i == 0) hexPath.moveTo(px, py);
                    else hexPath.lineTo(px, py);
                }
                hexPath.close();
                canvas.drawPath(hexPath, paint);
                break;
            }
            case DIAMOND: {
                Path diamondPath = inputControlsView.getPath();
                diamondPath.reset();
                diamondPath.moveTo(cx, rect.top);
                diamondPath.lineTo(rect.right, cy);
                diamondPath.lineTo(cx, rect.bottom);
                diamondPath.lineTo(rect.left, cy);
                diamondPath.close();
                canvas.drawPath(diamondPath, paint);
                break;
            }
            case OCTAGON: {
                Path octPath = inputControlsView.getPath();
                octPath.reset();
                float cutX = halfWidth * 0.35f;
                float cutY = halfHeight * 0.35f;
                octPath.moveTo(rect.left + cutX, rect.top);
                octPath.lineTo(rect.right - cutX, rect.top);
                octPath.lineTo(rect.right, rect.top + cutY);
                octPath.lineTo(rect.right, rect.bottom - cutY);
                octPath.lineTo(rect.right - cutX, rect.bottom);
                octPath.lineTo(rect.left + cutX, rect.bottom);
                octPath.lineTo(rect.left, rect.bottom - cutY);
                octPath.lineTo(rect.left, rect.top + cutY);
                octPath.close();
                canvas.drawPath(octPath, paint);
                break;
            }
        }
    }


    private boolean drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId, boolean fitBoundingBox) {
        if (iconId <= 0) return false;
        Bitmap icon = inputControlsView.getIcon(iconId);
        if (icon == null || icon.getWidth() <= 0 || icon.getHeight() <= 0) return false;

        Paint paint = inputControlsView.getPaint();
        paint.setFilterBitmap(true);
        boolean isCustom = iconId > CustomIconManager.BUILTIN_ICON_MAX;
        if (isCustom) {
            paint.setColorFilter(null);
            paint.setAlpha(255);
        } else {
            paint.setColorFilter(inputControlsView.getColorFilter());
        }

        if (fitBoundingBox) {
            // Icon visual size is determined by base element dimension * scale (independent of widthScale/heightScale boundary)
            int snappingSize = inputControlsView.getSnappingSize();
            float baseDimension = snappingSize * (shape == Shape.CIRCLE || shape == Shape.DIAMOND || shape == Shape.OCTAGON ? 6.0f : (shape == Shape.HEXAGON ? 5.8f : (shape == Shape.SQUARE ? 5.0f : 5.0f)));
            float maxDim = Math.max(icon.getWidth(), icon.getHeight());
            float iconDrawScale = (baseDimension * scale) / (maxDim > 0 ? maxDim : 1.0f);

            float halfW = icon.getWidth() * iconDrawScale * 0.5f;
            float halfH = icon.getHeight() * iconDrawScale * 0.5f;
            dstRect.set((int)(cx - halfW), (int)(cy - halfH), (int)(cx + halfW), (int)(cy + halfH));
        } else {
            int margin = (int)(inputControlsView.getSnappingSize() * (shape == Shape.CIRCLE || shape == Shape.SQUARE || shape == Shape.DIAMOND ? 2.0f : 1.0f) * scale);
            int halfSize = (int)((Math.min(width, height) - margin) * 0.5f);
            if (halfSize <= 0) halfSize = (int)(Math.min(width, height) * 0.5f);
            dstRect.set((int)(cx - halfSize), (int)(cy - halfSize), (int)(cx + halfSize), (int)(cy + halfSize));
        }

        canvas.drawBitmap(icon, null, dstRect, paint);
        paint.setColorFilter(null);
        return true;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            elementJSONObject.put("x", (float)x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float)y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", toggleSwitch);
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);
            elementJSONObject.put("customIconAsButton", customIconAsButton);
            if (widthScale != 1.0f) elementJSONObject.put("widthScale", Float.valueOf(widthScale));
            if (heightScale != 1.0f) elementJSONObject.put("heightScale", Float.valueOf(heightScale));
            if (touchPadding != 0) elementJSONObject.put("touchPadding", touchPadding);

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
            }
            return elementJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        Rect box = getBoundingBox();
        float pad = touchPadding > 0 ? (touchPadding * inputControlsView.getSnappingSize() * 0.2f * scale) : 0f;

        float cx = box.centerX();
        float cy = box.centerY();
        float dx = x - cx;
        float dy = y - cy;
        float halfW = box.width() * 0.5f;
        float halfH = box.height() * 0.5f;

        switch (shape) {
            case CIRCLE: {
                if (Math.abs(halfW - halfH) > 1.0f) {
                    float a = halfW + pad;
                    float b = halfH + pad;
                    if (a <= 0 || b <= 0) return false;
                    return ((dx * dx) / (a * a) + (dy * dy) / (b * b)) <= 1.0f;
                } else {
                    float r = halfW + pad;
                    return (dx * dx + dy * dy) <= (r * r);
                }
            }
            case OVAL: {
                float a = halfW + pad;
                float b = halfH + pad;
                if (a <= 0 || b <= 0) return false;
                return ((dx * dx) / (a * a) + (dy * dy) / (b * b)) <= 1.0f;
            }
            case DIAMOND: {
                float w = halfW + pad;
                float h = halfH + pad;
                if (w <= 0 || h <= 0) return false;
                return (Math.abs(dx) / w + Math.abs(dy) / h) <= 1.0f;
            }
            case HEXAGON: {
                float w = halfW + pad;
                float h = halfH + pad;
                if (w <= 0 || h <= 0) return false;
                float q2x = Math.abs(dx) / w;
                float q2y = Math.abs(dy) / h;
                if (q2x > 1.0f || q2y > 1.0f) return false;
                return (2.0f * q2y + q2x) <= 2.0f;
            }
            case OCTAGON: {
                float w = halfW + pad;
                float h = halfH + pad;
                if (w <= 0 || h <= 0) return false;
                float ox = Math.abs(dx) / w;
                float oy = Math.abs(dy) / h;
                if (ox > 1.0f || oy > 1.0f) return false;
                return (ox + oy) <= 1.4f;
            }
            case ROUND_RECT:
            case CAPSULE: {
                if (halfW >= halfH) {
                    // Horizontal capsule / stadium
                    float r = halfH + pad;
                    float straightHalfW = Math.max(0, halfW - halfH);
                    if (Math.abs(dx) <= straightHalfW) {
                        return Math.abs(dy) <= r;
                    } else {
                        float capDx = Math.abs(dx) - straightHalfW;
                        return (capDx * capDx + dy * dy) <= (r * r);
                    }
                } else {
                    // Vertical capsule / stadium
                    float r = halfW + pad;
                    float straightHalfH = Math.max(0, halfH - halfW);
                    if (Math.abs(dy) <= straightHalfH) {
                        return Math.abs(dx) <= r;
                    } else {
                        float capDy = Math.abs(dy) - straightHalfH;
                        return (dx * dx + capDy * capDy) <= (r * r);
                    }
                }
            }
            case RECT:
            case SQUARE:
            default: {
                return (x >= box.left - pad && x <= box.right + pad && y >= box.top - pad && y <= box.bottom + pad);
            }
        }
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            if (type == Type.BUTTON || type == Type.EXPANDABLE_BUTTON || type == Type.BUTTON_GRID) {
                states[0] = true;
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
                if (!toggleSwitch || !selected) {
                    for (byte i = 0; i < 4; i++) {
                        Binding b = getBindingAt(i);
                        if (b != Binding.NONE) inputControlsView.handleInputEvent(b, true);
                    }
                }
                return true;
            }
            else if (type == Type.RANGE_BUTTON) {
                states[0] = true;
                scroller.handleTouchDown(x, y);
                return true;
            }
            else {
                if (type == Type.TRACKPAD || type == Type.MOUSE_AREA) {
                    if (currentPosition == null) currentPosition = new PointF();
                    currentPosition.set(x, y);
                }
                return handleTouchMove(pointerId, x, y);
            }
        }
        else return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.DYNAMIC_STICK || type == Type.TRACKPAD || type == Type.MOUSE_AREA)) {
            float deltaX, deltaY;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            TouchpadView touchpadView =  inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD || type == Type.MOUSE_AREA) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            }
            else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;
                float distance = (float)Math.sqrt(offsetX * offsetX + offsetY * offsetY);

                if (distance > radius) {
                    offsetX = (offsetX / distance) * radius;
                    offsetY = (offsetY / distance) * radius;
                }

                deltaX = offsetX / radius;
                deltaY = offsetY / radius;
            }

            if (type == Type.STICK || type == Type.DYNAMIC_STICK) {
                if (currentPosition == null) currentPosition = new PointF();
                currentPosition.x = boundingBox.left + deltaX * radius + radius;
                currentPosition.y = boundingBox.top + deltaY * radius + radius;

                Binding firstBinding = getBindingAt(0);
                float magnitude = (float)Math.sqrt(deltaX * deltaX + deltaY * deltaY);

                // Use unified stick handling for gamepad sticks
                if (firstBinding.isGamepad()) {
                    inputControlsView.handleStickInput(null, firstBinding, deltaX, deltaY);
                } else {
                    final boolean[] states = {false, false, false, false};

                    if (magnitude > STICK_DEAD_ZONE) {
                        float angle = (float)Math.toDegrees(Math.atan2(deltaY, deltaX));
                        if (angle < 0) angle += 360;

                        if (angle >= 202.5f && angle <= 337.5f) states[0] = true; // Up
                        if (angle >= 292.5f || angle <= 67.5f)  states[1] = true; // Right
                        if (angle >= 22.5f  && angle <= 157.5f) states[2] = true; // Down
                        if (angle >= 112.5f && angle <= 247.5f) states[3] = true; // Left
                    }

                    for (byte i = 0; i < 4; i++) {
                        Binding binding = getBindingAt(i);
                        inputControlsView.handleInputEvent(binding, states[i], magnitude);
                        this.states[i] = states[i];
                    }
                }

                inputControlsView.invalidate();
            }
            else if (type == Type.TRACKPAD || type == Type.MOUSE_AREA) {
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    if (interpolator == null) interpolator = new CubicBezierInterpolator();
                    interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);

                    float valueX = deltaX;
                    float valueY = deltaY;
                    if (Math.abs(valueX) > TRACKPAD_ACCELERATION_THRESHOLD) valueX *= STICK_SENSITIVITY;
                    if (Math.abs(valueY) > TRACKPAD_ACCELERATION_THRESHOLD) valueY *= STICK_SENSITIVITY;

                    float interpX = interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueX / TRACKPAD_MAX_SPEED)));
                    float interpY = interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueY / TRACKPAD_MAX_SPEED)));

                    float finalX = Mathf.clamp(interpX * Mathf.sign(valueX), -1, 1);
                    float finalY = Mathf.clamp(interpY * Mathf.sign(valueY), -1, 1);

                    inputControlsView.handleStickInput(null, firstBinding, finalX, finalY);

                    for (byte i = 0; i < 4; i++) {
                        this.states[i] = true;
                    }
                } else {
                    final boolean[] states = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                    int cursorDx = 0;
                    int cursorDy = 0;

                    for (byte i = 0; i < 4; i++) {
                        float value = (i == 1 || i == 3 ? deltaX : deltaY);
                        Binding binding = getBindingAt(i);
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorDx = Mathf.roundPoint(value);
                        }
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorDy = Mathf.roundPoint(value);
                        }
                        else {
                            inputControlsView.handleInputEvent(binding, states[i], value);
                            this.states[i] = states[i];
                        }
                    }

                    if (cursorDx != 0 || cursorDy != 0)  {
                        XServer xServer = inputControlsView.getXServer();
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, cursorDx, cursorDy, 0);
                        else
                            inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
                    }
                }
            }
            else {
                final boolean[] states = {deltaY <= -DPAD_DEAD_ZONE, deltaX >= DPAD_DEAD_ZONE, deltaY >= DPAD_DEAD_ZONE, deltaX <= -DPAD_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                    inputControlsView.handleInputEvent(binding, state, value);
                    this.states[i] = state;
                }
            }

            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            return true;
        }
        else return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (pointerId == currentPointerId) {
            if (type == Type.BUTTON || type == Type.EXPANDABLE_BUTTON || type == Type.BUTTON_GRID) {
                states[0] = false;
                if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                    selected = (System.currentTimeMillis() - (long)touchTime) > BUTTON_MIN_TIME_TO_KEEP_PRESSED;
                    if (!selected) {
                        for (byte i = 0; i < 4; i++) {
                            Binding b = getBindingAt(i);
                            if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
                        }
                    }
                    touchTime = null;
                    inputControlsView.invalidate();
                }
                else if (!toggleSwitch || selected) {
                    for (byte i = 0; i < 4; i++) {
                        Binding b = getBindingAt(i);
                        if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
                    }
                }

                if (toggleSwitch) {
                    selected = !selected;
                    inputControlsView.invalidate();
                }
            }
            else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.DYNAMIC_STICK || type == Type.TRACKPAD || type == Type.MOUSE_AREA) {
                for (byte i = 0; i < states.length; i++) {
                    if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                    states[i] = false;
                }

                Binding firstBinding = getBindingAt(0);
                if (firstBinding != null && firstBinding.isGamepad()) {
                    inputControlsView.handleStickInput(null, firstBinding, 0, 0);
                }
                for (byte i = 0; i < 4; i++) {
                    Binding b = getBindingAt(i);
                    if (b != null && b.isMouseMove()) {
                        inputControlsView.handleMouseMovement(0, 0);
                        break;
                    }
                }

                if (type == Type.RANGE_BUTTON) {
                    scroller.handleTouchUp();
                }
                else if (type == Type.STICK || type == Type.DYNAMIC_STICK) {
                    inputControlsView.invalidate();
                }

                if (currentPosition != null) currentPosition = null;
            }
            currentPointerId = -1;
            return true;
        }
        return false;
    }

    public PointF getCurrentPosition() {
        if (currentPosition == null) {
            currentPosition = new PointF(x, y); // Initialize to the center (same as outer circle)
        }
        return currentPosition;
    }

    // New setter for current position to allow resetting
    public void setCurrentPosition(float x, float y) {
        if (currentPosition == null) {
            currentPosition = new PointF();
        }
        currentPosition.set(x, y);
        // Optionally invalidate the view to trigger a redraw
        inputControlsView.invalidate();
    }

    private void drawXboxMenuIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float w = size * 0.22f;
        float h = size * 0.14f;
        float sw = Math.max(3.5f, size * 0.065f);
        paint.setStrokeWidth(sw);
        canvas.drawLine(cx - w, cy - h, cx + w, cy - h, paint);
        canvas.drawLine(cx - w, cy, cx + w, cy, paint);
        canvas.drawLine(cx - w, cy + h, cx + w, cy + h, paint);
    }

    private void drawXboxViewIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float sw = Math.max(3f, size * 0.06f);
        paint.setStrokeWidth(sw);
        float sqW = size * 0.32f;
        float sqH = size * 0.24f;
        float offX = size * 0.08f;
        float offY = size * 0.06f;
        float cr = size * 0.04f;

        // Background window/rectangle
        RectF r1 = new RectF(cx - offX - sqW * 0.5f, cy - offY - sqH * 0.5f, cx - offX + sqW * 0.5f, cy - offY + sqH * 0.5f);
        canvas.drawRoundRect(r1, cr, cr, paint);

        // Foreground window/rectangle
        RectF r2 = new RectF(cx + offX - sqW * 0.5f, cy + offY - sqH * 0.5f, cx + offX + sqW * 0.5f, cy + offY + sqH * 0.5f);
        canvas.drawRoundRect(r2, cr, cr, paint);
    }

    private void drawPlayStationTriangle(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float sw = Math.max(3.5f, size * 0.09f);
        paint.setStrokeWidth(sw);
        float r = size * 0.32f;
        Path p = new Path();
        p.moveTo(cx, cy - r);
        p.lineTo(cx + r * 0.866f, cy + r * 0.5f);
        p.lineTo(cx - r * 0.866f, cy + r * 0.5f);
        p.close();
        canvas.drawPath(p, paint);
    }

    private void drawPlayStationSquare(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float sw = Math.max(3.5f, size * 0.09f);
        paint.setStrokeWidth(sw);
        float half = size * 0.26f;
        canvas.drawRoundRect(new RectF(cx - half, cy - half, cx + half, cy + half), sw * 0.5f, sw * 0.5f, paint);
    }

    private void drawPlayStationCircle(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        float sw = Math.max(3.5f, size * 0.09f);
        paint.setStrokeWidth(sw);
        canvas.drawCircle(cx, cy, size * 0.28f, paint);
    }

    private void drawPlayStationCross(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float sw = Math.max(3.5f, size * 0.09f);
        paint.setStrokeWidth(sw);
        float half = size * 0.24f;
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, paint);
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, paint);
    }

    private void drawArcadeCoinIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float sw = Math.max(3f, size * 0.07f);
        paint.setStrokeWidth(sw);
        float r = size * 0.28f;
        canvas.drawCircle(cx, cy, r, paint);
        // Vertical coin slot
        paint.setStyle(Paint.Style.FILL);
        float slotW = Math.max(3f, size * 0.06f);
        float slotH = size * 0.26f;
        canvas.drawRoundRect(new RectF(cx - slotW * 0.5f, cy - slotH * 0.5f, cx + slotW * 0.5f, cy + slotH * 0.5f), slotW * 0.5f, slotW * 0.5f, paint);
    }

    private void drawArcadePlayerIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        // Player 1 head
        float headR = size * 0.09f;
        canvas.drawCircle(cx, cy - size * 0.10f, headR, paint);
        // Player 1 shoulders / torso
        RectF body = new RectF(cx - size * 0.18f, cy - size * 0.00f, cx + size * 0.18f, cy + size * 0.22f);
        canvas.drawRoundRect(body, size * 0.08f, size * 0.08f, paint);
    }

    private void drawCyberpunkPowerIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float sw = Math.max(3.5f, size * 0.08f);
        paint.setStrokeWidth(sw);
        float r = size * 0.24f;
        RectF arcRect = new RectF(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(arcRect, 135, 270, false, paint);
        // Vertical power line
        canvas.drawLine(cx, cy - r * 1.2f, cx, cy, paint);
    }

    private void drawCyberpunkHexGridIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float sw = Math.max(3f, size * 0.07f);
        paint.setStrokeWidth(sw);
        float r = size * 0.24f;
        Path p = new Path();
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(60 * i - 30);
            float px = cx + (float)(r * Math.cos(angle));
            float py = cy + (float)(r * Math.sin(angle));
            if (i == 0) p.moveTo(px, py);
            else p.lineTo(px, py);
        }
        p.close();
        canvas.drawPath(p, paint);
        // Inner dot
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, sw * 1.2f, paint);
    }

    private void drawPlayStationOptionsIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float sw = Math.max(3f, size * 0.065f);
        paint.setStrokeWidth(sw);
        float h = size * 0.20f;
        float spacing = size * 0.11f;
        // 3 vertical parallel bars (Official PlayStation Options icon)
        canvas.drawLine(cx - spacing, cy - h, cx - spacing, cy + h, paint);
        canvas.drawLine(cx, cy - h, cx, cy + h, paint);
        canvas.drawLine(cx + spacing, cy - h, cx + spacing, cy + h, paint);
    }

    private void drawPlayStationShareIcon(Canvas canvas, float cx, float cy, float size, Paint paint, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float sw = Math.max(3f, size * 0.06f);
        paint.setStrokeWidth(sw);
        float len = size * 0.16f;
        float off = size * 0.08f;
        // Center vertical ray
        canvas.drawLine(cx, cy - off, cx, cy - off - len, paint);
        // Left angled ray (45 deg)
        canvas.drawLine(cx - off * 0.7f, cy - off * 0.7f, cx - (off + len) * 0.7f, cy - (off + len) * 0.7f, paint);
        // Right angled ray (45 deg)
        canvas.drawLine(cx + off * 0.7f, cy - off * 0.7f, cx + (off + len) * 0.7f, cy - (off + len) * 0.7f, paint);
        // Base rounded pill dot
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy + size * 0.08f, sw * 1.3f, paint);
    }
}
