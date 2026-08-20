package com.winlator.cmod.inputcontrols;

import android.content.Context;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ControlsProfile implements Comparable<ControlsProfile> {
    public final int id;
    private String name;
    private float cursorSpeed = 1.0f;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final ArrayList<RadialWheelConfig> wheels = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean wheelsLoaded = false;
    private boolean virtualGamepad = false;
    private final Context context;
    private GamepadState gamepadState;

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return cursorSpeed;
    }

    public void setCursorSpeed(float cursorSpeed) {
        this.cursorSpeed = cursorSpeed;
    }

    public boolean isVirtualGamepad() {
        return virtualGamepad;
    }

    public GamepadState getGamepadState() {
        if (gamepadState == null) gamepadState = new GamepadState();
        return gamepadState;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller == null) controllers.add(controller = ExternalController.getController(id));
        controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!controllersLoaded) loadControllers();
        controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!controllersLoaded) loadControllers();
        
        // First try direct deviceId match
        for (ExternalController controller : controllers) {
            if (controller.getDeviceId() == deviceId) return controller;
        }
        
        // If no match, try to find by descriptor
        android.view.InputDevice device = android.view.InputDevice.getDevice(deviceId);
        if (device != null) {
            String descriptor = device.getDescriptor();
            for (ExternalController controller : controllers) {
                if (controller.getId().equals(descriptor)) {
                    return controller;
                }
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(ControlsProfile o) {
        return Integer.compare(id, o.id);
    }

    public boolean isElementsLoaded() {
        return elementsLoaded;
    }

    public void save() {
        File file = getProfileFile(context, id);

        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));

            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                elementsJSONArray = profileJSONObject.getJSONArray("elements");
            }
            else for (ControlElement element : elements) elementsJSONArray.put(element.toJSONObject());
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("controllers")) controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);

            JSONArray wheelsJSONArray = new JSONArray();
            if (!wheelsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("wheels")) wheelsJSONArray = profileJSONObject.getJSONArray("wheels");
            }
            else {
                for (RadialWheelConfig wheel : wheels) {
                    JSONObject wheelJSONObject = wheel.toJSONObject();
                    if (wheelJSONObject != null) wheelsJSONArray.put(wheelJSONObject);
                }
            }
            if (wheelsJSONArray.length() > 0) data.put("wheels", wheelsJSONArray);

            // Embed custom icons for 100% portable sharing (Bannerlator .icpx + Winlator .icp formats)
            CustomIconManager iconManager = CustomIconManager.getInstance(context);
            JSONArray embeddedIcons = new JSONArray();
            JSONArray customIconsArr = new JSONArray();
            java.util.Set<Integer> customIds = new java.util.HashSet<>();
            for (ControlElement element : elements) {
                int iId = element.getIconId();
                if (iId > CustomIconManager.BUILTIN_ICON_MAX) customIds.add(iId);
            }
            for (RadialWheelConfig wheel : wheels) {
                for (RadialWheelSlice slice : wheel.slices) {
                    if (slice.iconId > CustomIconManager.BUILTIN_ICON_MAX) customIds.add(slice.iconId);
                }
            }
            for (int cid : customIds) {
                String b64 = iconManager.encodeIconBase64(cid);
                if (b64 != null) {
                    embeddedIcons.put(b64);
                    JSONObject cObj = new JSONObject();
                    cObj.put("id", cid);
                    cObj.put("png", b64);
                    customIconsArr.put(cObj);
                }
            }
            if (customIconsArr.length() > 0) data.put("customIcons", customIconsArr);
            if (embeddedIcons.length() > 0) data.put("embeddedIcons", embeddedIcons);

            FileUtils.writeString(file, data.toString());
        }
        catch (JSONException e) {}
    }

    public ArrayList<RadialWheelConfig> getWheels() {
        if (!wheelsLoaded) loadWheels();
        return wheels;
    }

    public void addWheel(RadialWheelConfig wheel) {
        wheels.add(wheel);
        wheelsLoaded = true;
    }

    public void removeWheel(RadialWheelConfig wheel) {
        if (!wheelsLoaded) loadWheels();
        wheels.remove(wheel);
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-"+id+".icp");
    }

    public void addElement(ControlElement element) {
        elements.add(element);
        elementsLoaded = true;
    }

    public void removeElement(ControlElement element) {
        elements.remove(element);
        elementsLoaded = true;
    }

    public List<ControlElement> getElements() {
        return immutableElements;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        controllers.clear();
        controllersLoaded = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return controllers;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (!profileJSONObject.has("controllers")) return controllers;
            JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            for (int i = 0; i < controllersJSONArray.length(); i++) {
                JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
                String id = controllerJSONObject.getString("id");
                ExternalController controller = new ExternalController();
                controller.setId(id);
                controller.setName(controllerJSONObject.getString("name"));

                JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
                for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                    JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                    ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                    controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                    controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                    controller.addControllerBinding(controllerBinding);
                }
                controllers.add(controller);
            }
            controllersLoaded = true;
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
        return controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        elements.clear();
        elementsLoaded = false;
        virtualGamepad = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (profileJSONObject.has("customIcons")) {
                CustomIconManager iconManager = CustomIconManager.getInstance(context);
                JSONArray customIcons = profileJSONObject.getJSONArray("customIcons");
                for (int ci = 0; ci < customIcons.length(); ci++) {
                    JSONObject obj = customIcons.optJSONObject(ci);
                    if (obj != null) {
                        int sourceId = obj.optInt("id", 0);
                        String pngBase64 = obj.optString("png", null);
                        if (pngBase64 != null && !pngBase64.isEmpty()) {
                            iconManager.decodeAndSaveBase64(pngBase64, sourceId);
                        }
                    }
                }
            }
            if (profileJSONObject.has("embeddedIcons")) {
                CustomIconManager iconManager = CustomIconManager.getInstance(context);
                JSONArray embeddedIcons = profileJSONObject.getJSONArray("embeddedIcons");
                for (int ei = 0; ei < embeddedIcons.length(); ei++) {
                    iconManager.decodeAndSaveBase64(embeddedIcons.getString(ei));
                }
            }
            JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
                ControlElement element = new ControlElement(inputControlsView);
                element.setType(ControlElement.Type.parse(elementJSONObject.optString("type", "BUTTON")));
                element.setShape(ControlElement.Shape.parse(elementJSONObject.optString("shape", "CIRCLE")));
                element.setToggleSwitch(elementJSONObject.optBoolean("toggleSwitch", false));
                element.setX((int)(elementJSONObject.optDouble("x", 0.5) * inputControlsView.getMaxWidth()));
                element.setY((int)(elementJSONObject.optDouble("y", 0.5) * inputControlsView.getMaxHeight()));
                element.setScale((float)elementJSONObject.optDouble("scale", 1.0));
                element.setText(elementJSONObject.optString("text", ""));
                int rawIconId = elementJSONObject.optInt("iconId", 0);
                if (rawIconId < 0 && rawIconId >= Byte.MIN_VALUE) rawIconId = rawIconId & 0xFF;
                element.setIconId(rawIconId);
                element.setCustomIconAsButton(elementJSONObject.optBoolean("customIconAsButton", true));
                if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.parse(elementJSONObject.optString("range", "FROM_A_TO_Z")));
                if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.optInt("orientation", 0));

                boolean hasGamepadBinding = true;
                JSONArray bindingsJSONArray = elementJSONObject.optJSONArray("bindings");
                if (bindingsJSONArray != null) {
                    for (int j = 0; j < bindingsJSONArray.length(); j++) {
                        Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
                        element.setBindingAt(j, binding);
                        if (!binding.isGamepad()) hasGamepadBinding = false;
                    }
                }

                if (!virtualGamepad && hasGamepadBinding) virtualGamepad = true;
                elements.add(element);
            }
            elementsLoaded = true;
            loadWheels();
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public ArrayList<RadialWheelConfig> loadWheels() {
        wheels.clear();
        wheelsLoaded = true;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return wheels;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (!profileJSONObject.has("wheels")) return wheels;
            JSONArray wheelsJSONArray = profileJSONObject.getJSONArray("wheels");
            for (int i = 0; i < wheelsJSONArray.length(); i++) {
                JSONObject wheelJSONObject = wheelsJSONArray.getJSONObject(i);
                RadialWheelConfig wheel = RadialWheelConfig.fromJSON(wheelJSONObject);
                if (wheel != null) wheels.add(wheel);
            }
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
        return wheels;
    }

    public boolean resetToDefaultTemplate(InputControlsView inputControlsView) {
        try {
            android.content.res.AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            if (assetFiles != null) {
                for (String assetFile : assetFiles) {
                    String assetPath = "inputcontrols/profiles/" + assetFile;
                    ControlsProfile originProfile = InputControlsManager.loadProfile(context, assetManager.open(assetPath));
                    if (originProfile.getName().equalsIgnoreCase(this.getName()) || originProfile.id == this.id) {
                        File targetFile = getProfileFile(context, id);
                        FileUtils.copy(context, assetPath, targetFile);
                        loadElements(inputControlsView);
                        return true;
                    }
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
