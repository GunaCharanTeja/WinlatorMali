package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.PowerManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.GraphicsEnhancementsDialog;
import com.winlator.cmod.contentdialog.ScreenEffectDialog;
import com.winlator.cmod.contentdialog.WineD3DConfigDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineRequestHandler;
import com.winlator.cmod.core.WineStartMenuCreator;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.inputcontrols.UnifiedInputState;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.effects.CRTEffect;
import com.winlator.cmod.renderer.effects.ColorEffect;
import com.winlator.cmod.renderer.effects.FXAAEffect;
import com.winlator.cmod.renderer.effects.NTSCCombinedEffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.widget.HudDataSource;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.TaskManagerDialog;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener, SensorEventListener {
    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private WinlatorHUD frameRating = null;
    private HudDataSource hudDataSource = null;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private String startupSelection;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    private WinHandler winHandler;
    private WineRequestHandler wineRequestHandler;
    private float globalCursorSpeed = 1.0f;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private int frameRatingWindowId = -1;
    private boolean cursorLock; // Flag to track if pointer capture was requested
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private boolean navigationFocused = false;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement = false;
    private boolean isMouseDisabled = false;

    private boolean isGyroEnabled = false;
    private float gyroSensitivity = 1.0f;
    private int gyroActivationMode = 0;
    private int gyroTarget = 0;
    private float gyroSmoothing = 0.5f;
    private float gyroDeadzone = 0.05f;
    private float gyroBiasX = 0;
    private float gyroBiasY = 0;
    private float filteredGyroX = 0;
    private float filteredGyroY = 0;

    // Inside the XServerDisplayActivity class
    private SensorManager sensorManager;

    // Playtime stats tracking
    private long startTime;
    private SharedPreferences playtimePrefs;
    private String shortcutName;
    private Handler handler;
    private Runnable savePlaytimeRunnable;
    private static final long SAVE_INTERVAL_MS = 1000;

    private Handler  timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;

    private boolean isDarkMode;

    private String screenEffectProfile;

    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

    private PowerManager.WakeLock wakeLock;
    private EmulationService emulationService;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            EmulationService.EmulationBinder binder = (EmulationService.EmulationBinder) service;
            emulationService = binder.getService();
            if (environment != null) emulationService.setEnvironment(environment);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            emulationService = null;
        }
    };

    private void createNotifcationChannel() {
        String name = "Winlator";
        String description = "Winlator XServer Messages";
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }


    private float pickHighestRefreshRate() {
    	android.view.Display display = getWindowManager().getDefaultDisplay();
    	android.view.Display.Mode[] modes = display.getSupportedModes();
    	
    	float maxRefresh = 0f;
    	
    	for (android.view.Display.Mode mode : modes) {
			if (mode.getRefreshRate() > maxRefresh)
    	    	maxRefresh = mode.getRefreshRate();
    	}

    	Log.d("XServerDisplayActivity", "Picking refresh rate " + maxRefresh);

    	return maxRefresh;
    }


    private void requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.Display display = getDisplay();
            if (display != null) {
                android.view.Display.Mode[] modes = display.getSupportedModes();
                android.view.Display.Mode bestMode = display.getMode();
                float maxRate = 0;
                for (android.view.Display.Mode mode : modes) {
                    if (mode.getRefreshRate() > maxRate) {
                        maxRate = mode.getRefreshRate();
                        bestMode = mode;
                    }
                }
                android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
                params.preferredDisplayModeId = bestMode.getModeId();
                getWindow().setAttributes(params);
            }
        } else {
            android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
            params.preferredRefreshRate = pickHighestRefreshRate();
            getWindow().setAttributes(params);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);

        Intent serviceIntent = new Intent(this, EmulationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Winlator:WakeLock");
        wakeLock.acquire(1000 * 60 * 60 * 24);

        requestHighRefreshRate();
        
        setContentView(R.layout.xserver_display_activity);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        cursorLock = preferences.getBoolean("cursor_lock", true);

        // Check for Dark Mode
        isDarkMode = preferences.getBoolean("dark_mode", false);

        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);



        // Check if xinputDisabled extra is passed
        boolean xinputDisabledFromShortcut = false;




        // Initialize SensorManager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        isGyroEnabled = preferences.getBoolean("gyro_enabled", false);
        gyroSensitivity = preferences.getFloat("gyro_sensitivity", 1.0f);
        gyroActivationMode = preferences.getInt("gyro_activation_mode", 0);
        gyroTarget = preferences.getInt("gyro_target", 0);
        gyroSmoothing = preferences.getFloat("gyro_smoothing", 0.5f);
        gyroDeadzone = preferences.getFloat("gyro_deadzone", 0.05f);
        gyroBiasX = preferences.getFloat("gyro_bias_x", 0);
        gyroBiasY = preferences.getFloat("gyro_bias_y", 0);
        if (isGyroEnabled) registerGyroscope();



        // Record the start time
        startTime = System.currentTimeMillis();

        // Initialize handler for periodic saving
        handler = new Handler(Looper.getMainLooper());
        savePlaytimeRunnable = new Runnable() {
            @Override
            public void run() {
                savePlaytimeData();
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        };
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);


        // Handler and Runnable to manage timeout for hiding controls

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", true);

        hideControlsRunnable = () -> {
            if (isTimeoutEnabled) {
                inputControlsView.setVisibility(View.GONE);
                Log.d("XServerDisplayActivity", "Touchscreen controls hidden after timeout.");
            }
        };


        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);

        if (isDarkMode) {
            navigationView.setItemTextColor(ContextCompat.getColorStateList(this, R.color.white));
            navigationView.setBackgroundResource(R.color.content_dialog_background_dark);
        }

        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getBoolean("enable_box64_logs", false);
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.main_menu_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);
        navigationView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_ARROW));
        navigationView.setOnFocusChangeListener((v, hasFocus) -> navigationFocused = hasFocus);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                navigationView.requestFocus();
            }
        });

        imageFs = ImageFs.find(this);

        // Prepare dev/input directory - actual event files created after shortcut is loaded
        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, "event" + i);
                if (eventFile.exists()) eventFile.delete();
            }
        }

        // Initialize the WinHandler
        winHandler = new WinHandler(this);
        winHandler.setFakeInputPath(devInputDir.getAbsolutePath());

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));

        // Log shortcut_path
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);


        // Determine container ID
        int containerId = getIntent().getIntExtra("container_id", 0);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");
            // Proceed with .desktop file parsing
        }


        // If container_id is 0, read from the .desktop file
        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }

        // Initialize playtime tracking
        playtimePrefs = getSharedPreferences("playtime_stats", MODE_PRIVATE);
        shortcutName = getIntent().getStringExtra("shortcut_name");

        // Ensure shortcutPath is not null before proceeding
        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                if (shortcutPath.toLowerCase().endsWith(".exe")) {
                    shortcutName = FileUtils.getBasename(shortcutPath);
                } else {
                    shortcutName = parseShortcutNameFromDesktopFile(new File(shortcutPath));
                }
                Log.d("XServerDisplayActivity", "Shortcut Name: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }

        // Increment play count at the start of a session
        incrementPlayCount();

        // Log the final container_id
        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        // Retrieve the container and check if it's null
        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();  // Gracefully exit the activity to avoid crashing
            return;
        }

        containerManager.activateContainer(container);

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }


        taskAffinityMask = (short) ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short) ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));

        if (shortcut != null) {
            taskAffinityMask = (short) ProcessHelper.getAffinityMask(shortcut.getExtra("cpuList", container.getCPUList(true)));
            taskAffinityMaskWoW64 = taskAffinityMask;
        }

        // Determine the class name for the startup workarounds
        String wmClass = shortcut != null ? shortcut.getExtra("wmClass", "") : "";
        Log.d("XServerDisplayActivity", "Startup wmClass: " + wmClass);

        firstTimeBoot = container.getExtra("appVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        ProcessHelper.removeAllDebugCallbacks();
        if (enableLogs) {
            LogView.setFilename(getExecutable());
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        }

        graphicsDriver = container.getGraphicsDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = container.getAudioDriver();
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        winHandler.setEmulationMode(container.getEmulationMode());
        winHandler.setInputType((byte) container.getInputType());
        lc_all = container.getLC_ALL();

        // Log the entire intent to verify the extras
        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
            emulator = shortcut.getExtra("emulator", container.getEmulator());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());
            String inputType = shortcut.getExtra("inputType");
            if (!inputType.isEmpty()) winHandler.setInputType(Byte.parseByte(inputType));
            String emulationMode = shortcut.getExtra("emulationMode");
            if (!emulationMode.isEmpty()) {
                try {
                    winHandler.setEmulationMode(UnifiedInputState.EmulationMode.valueOf(emulationMode));
                } catch (IllegalArgumentException e) {
                    winHandler.setEmulationMode(UnifiedInputState.EmulationMode.GAME_CONTROLLER);
                }
            }
            String xinputDisabledString = shortcut.getExtra("disableXinput", "false");
            xinputDisabledFromShortcut = parseBoolean(xinputDisabledString);
            // Pass the value to WinHandler
            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            String sharpnessEffect = shortcut.getExtra("sharpnessEffect", "None");
            if (!sharpnessEffect.equals("None")) {
                double sharpnessLevel = Double.parseDouble(shortcut.getExtra("sharpnessLevel", "100"));
                double sharpnessDenoise = Double.parseDouble(shortcut.getExtra("sharpnessDenoise", "100"));
                vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";" + "casSharpness=" + sharpnessLevel / 100 + ";" + "dlsSharpness=" + sharpnessLevel / 100  + ";" + "dlsDenoise=" + sharpnessDenoise / 100 + ";" + "enableOnLaunch=True";
            }
            Log.d("XServerDisplayActivity", "XInput Disabled from Shortcut: " + xinputDisabledFromShortcut);
        }

        this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
        this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);

        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/")) return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(new ScreenInfo(screenSize));
        xServer.setWinHandler(winHandler);

        boolean[] winStarted = {false};

        // Add the OnWindowModificationListener for dynamic workarounds
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }
                if (frameRating != null && window.getWidth() > 200 && window.getHeight() > 200) frameRating.onFrame();
            }
           
            @Override
            public void onMapWindow(Window window) {
                // Log the class name of the mapped window
                Log.d("XServerDisplayActivity", "onMapWindow: Mapping window: " + window.getClassName());
                assignTaskAffinity(window);
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                String name = (property != null) ? property.nameAsString() : "";
                Log.d("XServerDisplayActivity", "onModifyWindowProperty: Changed property " + name + " for window " + window.id);
                changeFrameRatingVisibility(window, property);
            }    

            @Override
            public void onDestroyWindow(Window window) {
                Log.d("XServerDisplayActivity", "onDestroyWindow: Destroying window " + window.getClassName());
                changeFrameRatingVisibility(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            InputStream in = null;
            InputStream finalIn = in;
            MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                @Override
                public void onSuccess(SF2Soundbank soundbank) {
                    midiHandler = new MidiHandler();
                    midiHandler.setSoundBank(soundbank);
                    midiHandler.start();
                }

                @Override
                public void onFailed(Exception e) {
                    try {
                        finalIn.close();
                    } catch (Exception e2) {}
                }
            };
            try {
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                    MidiManager.load(in, callback);
                } else
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
            } catch (Exception e) {}
        }

        // Check if a profile is defined by the shortcut
        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        // Notification is handled in foreground service (EmulationService)

        Runnable runnable = () -> {
            setupUI();
            if (controlsProfile.isEmpty()) {
                // No profile defined, run the simulated dialog confirmation for input controls
                simulateConfirmInputControlsDialog();
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        };

        if (xServer.screenInfo.height > xServer.screenInfo.width) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            configChangedCallback = runnable;
        } else
              runnable.run();
    }

    // Method to parse container_id from .desktop file
    private int parseContainerIdFromDesktopFile(File desktopFile) {
        int containerId = 0;
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id:")) {
                        containerId = Integer.parseInt(line.split(":")[1].trim());
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Error parsing container_id from .desktop file", e);
            }
        }
        return containerId;
    }

    private boolean parseBoolean(String value) {
        // Return true for "true", "1", "yes" (case-insensitive)
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        // Return false for any other value, including "false", "0", "no"
        return false;
    }

    // Inside XServerDisplayActivity class
    private void handleCapturedPointer(MotionEvent event) {
        boolean handled = false;

        int actionButton = event.getActionButton();
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button press
                }
                handled = true;
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button release
                }
                handled = true;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                if (xServer.isRelativeMouseMovement())
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                else
                    xServer.injectPointerMoveDelta((int)transformedPoint[0], (int)transformedPoint[1]);
                handled = true;
                break;
            case MotionEvent.ACTION_SCROLL:
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY <= -1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                } else if (scrollY >= 1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0,(int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                }
                handled = true;
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }


    @Override
    public void onResume() {
        super.onResume();

        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(1000 * 60 * 60 * 24);

        if (isGyroEnabled) registerGyroscope();

        startTime = System.currentTimeMillis();
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        unregisterGyroscope();

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
    }


    private void savePlaytimeData() {
        long endTime = System.currentTimeMillis();
        long playtime = endTime - startTime;

        // Ensure that playtime is not negative
        if (playtime < 0) {
            playtime = 0;
        }

        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playtimeKey = shortcutName + "_playtime";

        // Accumulate the playtime into totalPlaytime
        long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0) + playtime;
        editor.putLong(playtimeKey, totalPlaytime);
        editor.apply();

        // Reset startTime to the current time for the next interval
        startTime = System.currentTimeMillis();
    }


    private void incrementPlayCount() {
        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playCountKey = shortcutName + "_play_count";
        int playCount = playtimePrefs.getInt(playCountKey, 0) + 1;
        editor.putInt(playCountKey, playCount);
        editor.apply();
    }

    private void exit() {
        preloaderDialog.showOnUiThread(R.string.shutdown);
        Executors.newSingleThreadExecutor().execute(() -> {
            savePlaytimeData();
            handler.removeCallbacks(savePlaytimeRunnable);
            if (midiHandler != null) midiHandler.stop();
            if (environment != null) environment.stopEnvironmentComponents();
            if (winHandler != null) winHandler.stop();
            if (wineRequestHandler != null) wineRequestHandler.stop();

            /* Gracefully terminate all running wine processes */
            ProcessHelper.terminateAllWineProcesses();

            /* Wait until all processes have gracefully terminated, forcefully killing them only after a certain amount of time */
            long start = System.currentTimeMillis();
            while (!ProcessHelper.listRunningWineProcesses().isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= 2000) {
                    ProcessHelper.killAllWineProcesses();
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {}
            }

            runOnUiThread(() -> {
                preloaderDialog.closeOnUiThread();
                AppUtils.restartApplication(getApplicationContext());
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (hudDataSource != null) {
            hudDataSource.stop();
            hudDataSource = null;
        }
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {}
        if (isFinishing()) {
            stopService(new Intent(this, EmulationService.class));
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.main_menu_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_mouse_settings:
                showMouseSettingsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_task_manager:
                new TaskManagerDialog(this).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_display_session:
                showDisplaySessionDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_hud:
                showHUDConfigDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_graphics_enhancements:
                new GraphicsEnhancementsDialog(this).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_exit:
                drawerLayout.closeDrawers();
                exit();
                break;
        }
        return true;
    }

    private void showHUDConfigDialog() {
        if (container == null || !container.isShowFPS()) {
            AppUtils.showToast(this, "Turn on 'Show FPS' in Container Settings");
            return;
        }

        final GLRenderer renderer = xServerView.getRenderer();
        if (frameRating == null) {
            FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
            hudDataSource = new HudDataSource(this);
            frameRating = new WinlatorHUD(this);
            frameRating.setDataSource(hudDataSource);
            frameRating.setWrapperName(graphicsDriver);
            renderer.setWinlatorHUD(frameRating);
            rootView.addView(frameRating);
        }
        frameRating.enableByUser();

        final ContentDialog dialog = new ContentDialog(this, R.layout.hud_config_dialog);
        dialog.setTitle("HUD Settings");
        dialog.setIcon(R.drawable.ic_hud);

        CheckBox cbEnable = dialog.findViewById(R.id.CBHudEnable);
        CheckBox cbFps = dialog.findViewById(R.id.CBHudFPS);
        CheckBox cbGpu = dialog.findViewById(R.id.CBHudGPU);
        CheckBox cbCpu = dialog.findViewById(R.id.CBHudCPU);
        CheckBox cbRam = dialog.findViewById(R.id.CBHudRAM);
        CheckBox cbBatt = dialog.findViewById(R.id.CBHudBattery);
        CheckBox cbBattPct = dialog.findViewById(R.id.CBHudBatteryPct);
        CheckBox cbRend = dialog.findViewById(R.id.CBHudRenderer);
        CheckBox cbGraph = dialog.findViewById(R.id.CBHudGraph);
        CheckBox cbVert = dialog.findViewById(R.id.CBHudVertical);
        CheckBox cbMono = dialog.findViewById(R.id.CBHudMono);
        CheckBox cbBorder = dialog.findViewById(R.id.CBHudBorder);
        CheckBox cbCompact = dialog.findViewById(R.id.CBHudCompact);
        CheckBox cbWrapper = dialog.findViewById(R.id.CBHudWrapper);
        CheckBox cbLocked = dialog.findViewById(R.id.CBHudLocked);
        CheckBox cbCpuTemp = dialog.findViewById(R.id.CBHudCPUTemp);
        SeekBar sbAlpha = dialog.findViewById(R.id.SBHudAlpha);
        SeekBar sbScale = dialog.findViewById(R.id.SBHudScale);
        TextView tvAlpha = dialog.findViewById(R.id.TVHudAlpha);
        TextView tvScale = dialog.findViewById(R.id.TVHudScale);
        Spinner spPreset = dialog.findViewById(R.id.SPHudPreset);

        frameRating.syncCheckboxes(cbFps, cbGpu, cbCpu, cbBatt, cbGraph, cbRend, cbRam, cbBattPct, cbMono, cbBorder, cbCompact, cbWrapper, cbLocked, cbCpuTemp);
        cbEnable.setChecked(frameRating.isUserEnabled());
        cbVert.setChecked(frameRating.isVertical());

        int initialAlpha = (int)(frameRating.getHudAlpha() * 100);
        sbAlpha.setProgress(initialAlpha);
        tvAlpha.setText(initialAlpha + "%");

        float initialScaleValue = frameRating.getHudScale();
        sbScale.setProgress((int)((initialScaleValue - 0.5f) / 1.5f * 100));
        tvScale.setText(String.format(Locale.US, "%.1fx", initialScaleValue));

        String[] presets = {"Custom", "Top Left", "Top Center", "Top Right", "Middle Left", "Center", "Middle Right", "Bottom Left", "Bottom Center", "Bottom Right"};
        spPreset.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presets));

        cbEnable.setOnCheckedChangeListener((v, isChecked) -> {
            if (isChecked) frameRating.enableByUser();
            else frameRating.disableByUser();
        });

        cbFps.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(0, isChecked));
        cbGpu.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(2, isChecked));
        cbCpu.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(3, isChecked));
        cbBatt.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(4, isChecked));
        cbGraph.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(5, isChecked));
        cbRend.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(6, isChecked));
        cbRam.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(7, isChecked));
        cbBattPct.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(8, isChecked));
        cbMono.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(9, isChecked));
        cbBorder.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(10, isChecked));
        cbCompact.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(11, isChecked));
        cbWrapper.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(12, isChecked));
        cbLocked.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(13, isChecked));
        cbCpuTemp.setOnCheckedChangeListener((v, isChecked) -> frameRating.toggleElement(14, isChecked));
        cbVert.setOnCheckedChangeListener((v, isChecked) -> frameRating.setVertical(isChecked));

        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) frameRating.setHudAlpha(progress / 100f);
                tvAlpha.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = 0.5f + (progress / 100f) * 1.5f;
                if (fromUser) frameRating.setHudScale(scale);
                tvScale.setText(String.format(Locale.US, "%.1fx", scale));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        spPreset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) frameRating.setPositionPreset(position - 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        dialog.findViewById(R.id.BTHudReset).setOnClickListener(v -> {
            frameRating.forceReset();
            
            // Re-sync UI after a short delay to ensure forceReset's posted runnable has executed
            v.postDelayed(() -> {
                frameRating.syncCheckboxes(cbFps, cbGpu, cbCpu, cbBatt, cbGraph, cbRend, cbRam, cbBattPct, cbMono, cbBorder, cbCompact, cbWrapper, cbLocked, cbCpuTemp);
                cbEnable.setChecked(true);
                cbVert.setChecked(false);
                
                int alphaVal = (int)(frameRating.getHudAlpha() * 100);
                sbAlpha.setProgress(alphaVal);
                tvAlpha.setText(alphaVal + "%");

                float scaleValue = frameRating.getHudScale();
                sbScale.setProgress((int)((scaleValue - 0.5f) / 1.5f * 100));
                tvScale.setText(String.format(Locale.US, "%.1fx", scaleValue));
                
                spPreset.setSelection(0);
            }, 50);
        });

        dialog.setOnConfirmCallback(null);
        dialog.show();
    }

    private void showVibrationDialog() {
        if (winHandler == null) return;

        Context context = this;
        int maxControllers = winHandler.getMaxControllers();
        boolean[] checkedItems = new boolean[maxControllers];
        String[] items = new String[maxControllers];

        for (int i = 0; i < maxControllers; i++) {
            items[i] = getString(R.string.vibration_slot, i + 1);
            checkedItems[i] = winHandler.isVibrationEnabledForSlot(i);
        }

        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.vibration)
                .setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> {
                    winHandler.setVibrationEnabledForSlot(which, isChecked);
                })
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && cursorLock)
            touchpadView.requestPointerCapture();
        else if (!hasFocus)
            touchpadView.releasePointerCapture();
    }

    // private void extractInputDLLs() {
    //     String inputAsset = "input_dlls.tzst";
    //     File wineFolder = new File(imageFs.getWinePath() + "/lib/wine/");
    //     boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, inputAsset, wineFolder);
    //     if (!success)
    //         Log.d("XServerDisplayActivity", "Failed to extract input dlls");
    // }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
        }

        String dxwrapper = this.dxwrapper;

        if (dxwrapper.contains("dxvk")) {
            String dxvkWrapper = "dxvk-" + dxwrapperConfig.get("version");
            String vkd3dWrapper = "vkd3d-" + dxwrapperConfig.get("vkd3dVersion");
            String ddrawrapper = dxwrapperConfig.get("ddrawrapper");
            dxwrapper = dxvkWrapper + ";" + vkd3dWrapper + ";" + ddrawrapper;
        }

        if (!dxwrapper.equals(container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles(dxwrapper);
            container.putExtra("dxwrapper", dxwrapper);
            containerDataChanged = true;
        }

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);
        // Configure Wine joystick registry keys based on Automated Emulation Mode
        UnifiedInputState.EmulationMode mode = container.getEmulationMode();
        if (shortcut != null) {
            String extra = shortcut.getExtra("emulationMode");
            if (!extra.isEmpty()) {
                try {
                    mode = UnifiedInputState.EmulationMode.valueOf(extra);
                } catch (IllegalArgumentException e) {
                    mode = UnifiedInputState.EmulationMode.GAME_CONTROLLER;
                }
            }
        }

        // Automated Logic: Single "Game Controller" mode handles everything.
        // We enable DInput support but REMOVE registry overrides (Exclusive OFF).
        // Software Exclusivity in WinHandler now handles "Double Input" prevention.
        // This makes AC 1, 2, and 3 work while keeping modern games conflict-free.
        boolean isControllerMode = (mode == UnifiedInputState.EmulationMode.GAME_CONTROLLER);
        
        WineUtils.setJoystickRegistryKeys(container, isControllerMode, false);

        if (shortcut != null)
            startupSelection = shortcut.getExtra("startupSelection", String.valueOf(container.getStartupSelection()));
        else
            startupSelection = String.valueOf(container.getStartupSelection());

        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, startupSelection);
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }
        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        // Set environment variables
        envVars.put("LC_ALL", lc_all);
        envVars.put("WINEPREFIX", imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty()
                ? "+" + wineDebugChannels.replace(",", ",+")
                : "-all"
        );

        // Clear any temporary directory
        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());


        guestProgramLauncherComponent = new GuestProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut
        );

        // Additional container checks and environment configuration
        if (container != null) {
            if (Byte.parseByte(startupSelection) == Container.STARTUP_SELECTION_AGGRESSIVE) {
                // winHandler.killProcess("services.exe"); 
            }
            guestProgramLauncherComponent.setContainer(this.container);
            guestProgramLauncherComponent.setWineInfo(this.wineInfo);

            String guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + getWineStartCommand();

            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());

            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));

            if (!envVars.has("WINEESYNC")) {
                envVars.put("WINEESYNC", "1");
            }

            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) {
                bindingPaths.add(drive[1]);
            }

            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            guestProgramLauncherComponent.setBox64Preset(
                    shortcut != null
                            ? shortcut.getExtra("box64Preset", container.getBox64Preset())
                            : container.getBox64Preset()
            );

            guestProgramLauncherComponent.setFEXCorePreset(
                    shortcut != null
                            ? shortcut.getExtra("fexcorePreset", container.getFEXCorePreset())
                            : container.getFEXCorePreset()
            );
        }

        // Merge overrideEnvVars if present
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear(); // Clear overrideEnvVars as per smali logic
        }

        // Create our overall XEnvironment with various components
        environment = new XEnvironment(this, imageFs);
        if (emulationService != null) emulationService.setEnvironment(environment);
        environment.addComponent(
                new SysVSharedMemoryComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)
                )
        );
        environment.addComponent(
                new XServerComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)
                )
        );

        // Audio driver logic
        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(
                    new ALSAServerComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)
                    )
            );
        } else if (audioDriver.equals("pulseaudio")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(
                    new PulseAudioComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)
                    )
            );
        }

        // Pass final envVars to the launcher
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());

        // Add the launcher to our environment
        environment.addComponent(guestProgramLauncherComponent);

        // Initialize fake input for controller emulation - MUST be before Wine starts! Deleting old ones should also be done here ofc.
        // Initialize fake input for controller emulation - MUST be before Wine starts!
        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
             // Cleanup moved to onCreate
        }

        // Start all environment components (XServer, Audio, Wine, etc.)
        environment.startEnvironmentComponents();

        // Start the WinHandler (writes events to the file)
        winHandler.start();

        if (wineRequestHandler != null) wineRequestHandler.start();

        // Reset dxwrapper config
        dxwrapperConfig = null;
        
    }

    private void createWrapperScript(String path, String content) {
        File scriptFile = new File(path);
        FileUtils.writeString(scriptFile, content);
        scriptFile.setExecutable(true);
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);

        if (shortcut != null) {
            renderer.setUnviewableWMClasses("explorer.exe");
        }

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMouseEnabled(!isMouseDisabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        View.OnCapturedPointerListener capturedPointerListener = new View.OnCapturedPointerListener() {
        	@Override
            public boolean onCapturedPointer(View view, MotionEvent event) {
            	handleCapturedPointer(event);
                return true;
            }
        };
        touchpadView.setOnCapturedPointerListener(cursorLock ? capturedPointerListener : null);
        touchpadView.setFocusable(true);
        touchpadView.setFocusableInTouchMode(true);
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);


        startTouchscreenTimeout();

        // Inside onCreate(), after initializing controls
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }

        if (container != null && container.isShowFPS()) {
            hudDataSource = new HudDataSource(this);
            frameRating = new WinlatorHUD(this);
            frameRating.setDataSource(hudDataSource);
            frameRating.setWrapperName(graphicsDriver);

            renderer.setWinlatorHUD(frameRating);
            frameRating.enableByUser();
            rootView.addView(frameRating);
        }

        // Get the fullscreen stretched extra from the shortcut if available
        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : null;

        // Proceed based on container and shortcut settings
        boolean shouldStretch = false;

        if (shortcut != null && shortcutFullscreenStretched != null) {
            // Shortcut exists and has a valid setting
            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (container != null && container.isFullscreenStretched()) {
            // No shortcut or shortcut doesn't override, use the container's setting
            shouldStretch = true;
        }

        if (shouldStretch) {
            // Toggle fullscreen mode based on the final decision
            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }



    private ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            }
    );

    private String parseShortcutNameFromDesktopFile(File desktopFile) {
        String shortcutName = "";
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Name=")) {
                        shortcutName = line.split("=")[1].trim();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e("XServerDisplayActivity", "Error reading shortcut name from .desktop file", e);
            }
        }
        return shortcutName;
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                // If the child is a ViewGroup, recursively apply the color
                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                // If the child is a TextView, set its text color
                ((TextView) child).setTextColor(color);
            }
        }
    }

    private void showDisplaySessionDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.display_session_dialog);
        dialog.setTitle(R.string.display_and_session);
        dialog.setIcon(R.drawable.ic_display_session);

        dialog.findViewById(R.id.BTScreenEffects).setOnClickListener(v -> {
            dialog.dismiss();
            ScreenEffectDialog screenEffectDialog = new ScreenEffectDialog(this);
            screenEffectDialog.setOnConfirmCallback(() -> {
                GLRenderer currentRenderer = xServerView.getRenderer();
                ColorEffect colorEffect = (ColorEffect) currentRenderer.getEffectComposer().getEffect(ColorEffect.class);
                FXAAEffect fxaaEffect = (FXAAEffect) currentRenderer.getEffectComposer().getEffect(FXAAEffect.class);
                CRTEffect crtEffect = (CRTEffect) currentRenderer.getEffectComposer().getEffect(CRTEffect.class);
                ToonEffect toonEffect = (ToonEffect) currentRenderer.getEffectComposer().getEffect(ToonEffect.class);
                NTSCCombinedEffect ntscEffect = (NTSCCombinedEffect) currentRenderer.getEffectComposer().getEffect(NTSCCombinedEffect.class);
                screenEffectDialog.applyEffects(colorEffect, currentRenderer, fxaaEffect, crtEffect, toonEffect, ntscEffect);
                xServerView.requestRender();
            });
            screenEffectDialog.show();
        });

        dialog.findViewById(R.id.BTToggleFullscreen).setOnClickListener(v -> {
            xServerView.getRenderer().toggleFullscreen();
            touchpadView.toggleFullscreen();
            dialog.dismiss();
        });

        dialog.findViewById(R.id.BTPipMode).setOnClickListener(v -> {
            enterPictureInPictureMode();
            dialog.dismiss();
        });

        final ImageView ivPauseResume = dialog.findViewById(R.id.IVPauseResume);
        final TextView tvPauseResume = dialog.findViewById(R.id.TVPauseResume);
        tvPauseResume.setText(isPaused ? R.string.resume : R.string.pause);
        ivPauseResume.setImageResource(isPaused ? R.drawable.icon_play : R.drawable.icon_pause);
        dialog.findViewById(R.id.BTPauseResume).setOnClickListener(v -> {
            if (isPaused) ProcessHelper.resumeAllWineProcesses();
            else ProcessHelper.pauseAllWineProcesses();
            isPaused = !isPaused;
            dialog.dismiss();
        });

        dialog.findViewById(R.id.BTMagnifier).setOnClickListener(v -> {
            if (magnifierView == null) {
                FrameLayout container = findViewById(R.id.FLXServerDisplay);
                magnifierView = new MagnifierView(this);
                magnifierView.setZoomButtonCallback(value -> {
                    xServerView.getRenderer().setMagnifierZoom(Mathf.clamp(xServerView.getRenderer().getMagnifierZoom() + value, 1.0f, 3.0f));
                    magnifierView.setZoomValue(xServerView.getRenderer().getMagnifierZoom());
                });
                magnifierView.setZoomValue(xServerView.getRenderer().getMagnifierZoom());
                magnifierView.setHideButtonCallback(() -> {
                    container.removeView(magnifierView);
                    magnifierView = null;
                });
                container.addView(magnifierView);
            }
            dialog.dismiss();
        });

        dialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        dialog.show();
    }

    private void showMouseSettingsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.mouse_settings_dialog);
        dialog.setTitle(R.string.mouse_settings);
        dialog.setIcon(R.drawable.ic_mouse);

        int textColor = ContextCompat.getColor(this, isDarkMode ? R.color.white : R.color.black);
        ViewGroup dialogViewGroup = (ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        setTextColorForDialog(dialogViewGroup, textColor);

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(isRelativeMouseMovement);

        final CheckBox cbMouseEnabled = dialog.findViewById(R.id.CBMouseEnabled);
        cbMouseEnabled.setChecked(!isMouseDisabled);

        dialog.setOnConfirmCallback(() -> {
            isRelativeMouseMovement = cbRelativeMouseMovement.isChecked();
            xServer.setRelativeMouseMovement(isRelativeMouseMovement);

            isMouseDisabled = !cbMouseEnabled.isChecked();
            touchpadView.setMouseEnabled(!isMouseDisabled);
        });

        dialog.show();
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        View contentView = dialog.getContentView();
        contentView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(this, 0.7f, 0.5f);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);

        dialog.getWindow().setBackgroundDrawableResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sProfile.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

        // Set text color for all TextViews in the dialog to white or black based on dark mode
        int textColor = ContextCompat.getColor(this, isDarkMode ? R.color.white : R.color.black);
        ViewGroup dialogViewGroup = (ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        setTextColorForDialog(dialogViewGroup, textColor);

        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                    selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        final CheckBox cbEnableTimeout = dialog.findViewById(R.id.CBEnableTimeout);
        cbEnableTimeout.setChecked(preferences.getBoolean("touchscreen_timeout_enabled", false));

        final CheckBox cbEnableHaptics = dialog.findViewById(R.id.CBEnableHaptics);
        cbEnableHaptics.setChecked(preferences.getBoolean("touchscreen_haptics_enabled", true));

        final CheckBox cbGyroView = dialog.findViewById(R.id.CBGyroView);
        cbGyroView.setChecked(isGyroEnabled);

        final Spinner sGyroActivationMode = dialog.findViewById(R.id.SGyroActivationMode);
        String[] activationModes = {"Always", "Touchpad", "LT"};
        sGyroActivationMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, activationModes));
        sGyroActivationMode.setSelection(gyroActivationMode);

        final Spinner sGyroTarget = dialog.findViewById(R.id.SGyroTarget);
        String[] gyroTargets = {"Mouse", "Right Stick", "Arrows"};
        sGyroTarget.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, gyroTargets));
        sGyroTarget.setSelection(gyroTarget);

        final TextView tvGyroSensitivity = dialog.findViewById(R.id.TVGyroSensitivity);
        final SeekBar sbGyroSensitivity = dialog.findViewById(R.id.SBGyroSensitivity);
        sbGyroSensitivity.setProgress((int)(gyroSensitivity * 50));
        tvGyroSensitivity.setText(String.format(Locale.US, "Gyro Sensitivity: %.1fx", gyroSensitivity));

        final TextView tvGyroSmoothing = dialog.findViewById(R.id.TVGyroSmoothing);
        final SeekBar sbGyroSmoothing = dialog.findViewById(R.id.SBGyroSmoothing);
        sbGyroSmoothing.setProgress((int)(gyroSmoothing * 100));
        tvGyroSmoothing.setText(String.format(Locale.US, "Gyro Smoothing: %d%%", (int)(gyroSmoothing * 100)));

        final TextView tvGyroDeadzone = dialog.findViewById(R.id.TVGyroDeadzone);
        final SeekBar sbGyroDeadzone = dialog.findViewById(R.id.SBGyroDeadzone);
        sbGyroDeadzone.setProgress((int)(gyroDeadzone * 200));
        tvGyroDeadzone.setText(String.format(Locale.US, "Gyro Deadzone: %d%%", (int)(gyroDeadzone * 100)));

        sbGyroSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = progress / 50.0f;
                tvGyroSensitivity.setText(String.format(Locale.US, "Gyro Sensitivity: %.1fx", val));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbGyroSmoothing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvGyroSmoothing.setText(String.format(Locale.US, "Gyro Smoothing: %d%%", progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbGyroDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvGyroDeadzone.setText(String.format(Locale.US, "Gyro Deadzone: %d%%", progress / 2));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        dialog.findViewById(R.id.BTVibration).setOnClickListener(v -> showVibrationDialog());

        dialog.findViewById(R.id.BTGyroCalibrate).setOnClickListener(v -> {
            gyroBiasX += filteredGyroX;
            gyroBiasY += filteredGyroY;
            preferences.edit()
                .putFloat("gyro_bias_x", gyroBiasX)
                .putFloat("gyro_bias_y", gyroBiasY)
                .apply();
            AppUtils.showToast(this, "Gyro Calibrated");
        });

        dialog.findViewById(R.id.BTGyroReset).setOnClickListener(v -> {
            cbGyroView.setChecked(false);
            sGyroActivationMode.setSelection(0);
            sGyroTarget.setSelection(0);
            sbGyroSensitivity.setProgress(50);
            sbGyroSmoothing.setProgress(50);
            sbGyroDeadzone.setProgress(10);
            gyroBiasX = 0;
            gyroBiasY = 0;
            preferences.edit()
                .putFloat("gyro_bias_x", 0)
                .putFloat("gyro_bias_y", 0)
                .apply();
            AppUtils.showToast(this, "Gyro Settings Reset");
        });

        final Runnable updateProfile = () -> {
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        };

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
                updateProfile.run();
            };
            controlsEditorActivityResultLauncher.launch(intent);
        });

        dialog.setOnConfirmCallback(() -> {
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            boolean isTimeoutEnabled = cbEnableTimeout.isChecked();
            boolean isHapticsEnabled = cbEnableHaptics.isChecked();

            isGyroEnabled = cbGyroView.isChecked();
            gyroActivationMode = sGyroActivationMode.getSelectedItemPosition();
            gyroTarget = sGyroTarget.getSelectedItemPosition();
            gyroSensitivity = sbGyroSensitivity.getProgress() / 50.0f;
            gyroSmoothing = sbGyroSmoothing.getProgress() / 100.0f;
            gyroDeadzone = sbGyroDeadzone.getProgress() / 200.0f;

            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
            editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
            editor.putBoolean("gyro_enabled", isGyroEnabled);
            editor.putInt("gyro_activation_mode", gyroActivationMode);
            editor.putInt("gyro_target", gyroTarget);
            editor.putFloat("gyro_sensitivity", gyroSensitivity);
            editor.putFloat("gyro_smoothing", gyroSmoothing);
            editor.putFloat("gyro_deadzone", gyroDeadzone);
            editor.apply();

            if (isGyroEnabled) registerGyroscope();
            else unregisterGyroscope();

            if (isTimeoutEnabled) {
                startTouchscreenTimeout(); // Start the timeout functionality if enabled
            } else {
                touchpadView.setOnTouchListener(null); // Disable the listener if timeout is disabled
            }
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
            updateProfile.run();
        });

        dialog.setOnCancelCallback(updateProfile::run);

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void simulateConfirmInputControlsDialog() {
        // Simulate setting the relative mouse movement and touchscreen controls from preferences

        boolean isShowTouchscreenControls = preferences.getBoolean("show_touchscreen_controls_enabled", true);
        inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        boolean isHapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", true);

        // Apply these settings as if the user confirmed the dialog
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();

        // If no profile is selected, hide the controls
        int selectedProfileIndex = preferences.getInt("selected_profile_index", -1); // Default to -1 for no profile

        if (selectedProfileIndex >= 0 && selectedProfileIndex < inputControlsManager.getProfiles().size()) {
            // A profile is selected, show the controls
            ControlsProfile profile = inputControlsManager.getProfiles().get(selectedProfileIndex);
            showInputControls(profile);
        } else {
            // No profile selected, ensure the controls are hidden
            hideInputControls();
        }

        // Timeout logic should only apply if the controls are visible
        if (isTimeoutEnabled && inputControlsView.getVisibility() == View.VISIBLE) {
            startTouchscreenTimeout(); // Start timeout if enabled and controls are visible
        } else {
            touchpadView.setOnTouchListener(null); // Disable the timeout listener if not needed
        }

        Log.d("XServerDisplayActivity", "Input controls simulated confirmation executed.");
    }

    private void startTouchscreenTimeout() {
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);

        if (isTimeoutEnabled) {
            // Show controls initially and set up touch event listeners
            inputControlsView.setVisibility(View.VISIBLE);
            Log.d("XServerDisplayActivity", "Timeout is enabled, setting up timeout logic.");

            // Attach the OnTouchListener to reset the timeout on touch events
            touchpadView.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    // Reset the timeout on any touch event
                    //Log.d("XServerDisplayActivity", "Touch detected, resetting timeout.");

                    // Keep the controls visible
                    inputControlsView.setVisibility(View.VISIBLE);

                    // Remove any pending hide callbacks and reset the timeout
                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                    timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Reset timeout
                }

                return false; // Allow the touch event to propagate
            });

            // Reset the timeout when the controls are initially displayed
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Hide after 5 seconds of inactivity
        } else {
            // If timeout is disabled, keep the controls always visible
            Log.d("XServerDisplayActivity", "Timeout is disabled, controls will stay visible.");

            inputControlsView.setVisibility(View.VISIBLE); // Ensure controls are visible
            timeoutHandler.removeCallbacks(hideControlsRunnable); // Remove any existing hide callbacks
            touchpadView.setOnTouchListener(null); // Remove the touch listener
        }
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private void extractGraphicsDriverFiles() {
        String adrenoToolsDriverId = graphicsDriverConfig.get("version");

        Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId);

        File rootDir = imageFs.getRootDir();

        if (dxwrapper.contains("dxvk")) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
            String version = dxwrapperConfig.get("version");
            if (version.equals("1.11.1-sarek")) {
                Log.d("GraphicsDriverExtraction", "Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass");
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            }
        }
        else {
            WineD3DConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
        }

        boolean useDRI3 = preferences.getBoolean("use_dri3", true);
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "sw");
        }

        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");
        envVars.put("GALLIUM_DRIVER", "zink");

        Log.d("XServerDisplayActivity", "Extracting graphics driver files");
        String driverFile = "graphics_driver/wrapper.tzst";
        String graphicsDriverLower = graphicsDriver.toLowerCase();
        if (graphicsDriverLower.startsWith("wrapper-leegao")) {
            driverFile = "graphics_driver/wrapper-leegao.tzst";
        } else if (graphicsDriverLower.startsWith("wrapper-v2")) {
            driverFile = "graphics_driver/wrapper-v2.tzst";
        } else if (graphicsDriverLower.startsWith("wrapper-gamenative")) {
            driverFile = "graphics_driver/wrapper-gamenative.tzst";
        }

        File internalDriverFile = new File(getFilesDir(), driverFile);
        if (internalDriverFile.exists()) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, internalDriverFile, rootDir);
        } else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, driverFile, rootDir);
        }

        String astcTranscode = graphicsDriverConfig.get("astcTranscode");
        String etc2Transcode = graphicsDriverConfig.get("etc2Transcode");
        boolean transcodeEnabled = "1".equals(astcTranscode) || "1".equals(etc2Transcode);

        if (firstTimeBoot) {
            Log.d("XServerDisplayActivity", "First time container boot, re-extracting layers and extra libs");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers.tzst", rootDir);
            
            File internalExtraLibs = new File(getFilesDir(), "graphics_driver/extra_libs.tzst");
            if (internalExtraLibs.exists()) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, internalExtraLibs, rootDir);
            else TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);

            if (transcodeEnabled) {
                File internalLeegaoBcn = new File(getFilesDir(), "graphics_driver/leegao_bcn.tzst");
                if (internalLeegaoBcn.exists()) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, internalLeegaoBcn, rootDir);
                else TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/leegao_bcn.tzst", rootDir);
            }
            container.putExtra("transcodeEnabled", transcodeEnabled ? "1" : "0");
        }
        else {
            boolean lastTranscodeEnabled = container.getExtra("transcodeEnabled", "0").equals("1");
            if (transcodeEnabled != lastTranscodeEnabled) {
                File internalExtraLibs = new File(getFilesDir(), "graphics_driver/extra_libs.tzst");
                if (internalExtraLibs.exists()) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, internalExtraLibs, rootDir);
                else TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);

                if (transcodeEnabled) {
                    File internalLeegaoBcn = new File(getFilesDir(), "graphics_driver/leegao_bcn.tzst");
                    if (internalLeegaoBcn.exists()) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, internalLeegaoBcn, rootDir);
                    else TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/leegao_bcn.tzst", rootDir);
                }
                container.putExtra("transcodeEnabled", transcodeEnabled ? "1" : "0");
                container.saveData();
            }
        }

        if (adrenoToolsDriverId != "System") {
            AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId);
        }

        String vulkanVersion = graphicsDriverConfig.get("vulkanVersion");
        String vulkanVersionPatch = GPUInformation.getVulkanVersion(adrenoToolsDriverId, this).split("\\.")[2];
        vulkanVersion = vulkanVersion + "." + vulkanVersionPatch;
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion);

        String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);

        String gpuName = graphicsDriverConfig.get("gpuName");
        String dxvkVersion = dxwrapperConfig.get("version");
        if (!gpuName.equals("Device") && !dxvkVersion.equals("1.11.1-sarek")) {
            envVars.put("WRAPPER_DEVICE_NAME", gpuName);
            envVars.put("WRAPPER_DEVICE_ID", WineD3DConfigDialog.getDeviceIdFromGPUName(this, gpuName));
            envVars.put("WRAPPER_VENDOR_ID", WineD3DConfigDialog.getVendorIdFromGPUName(this, gpuName));
        }

        String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);
        
        String presentMode = graphicsDriverConfig.get("presentMode");
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1");
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        String resourceType = graphicsDriverConfig.get("resourceType");
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType);

        String syncFrame = graphicsDriverConfig.get("syncFrame");
        if (syncFrame.equals("1"))
            envVars.put("MESA_VK_WSI_DEBUG", "forcesync");

        String disablePresentWait = graphicsDriverConfig.get("disablePresentWait");
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);

        String bcnEmulation = graphicsDriverConfig.get("bcnEmulation");
        String bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType");

        switch (bcnEmulation) {
            case "auto" -> {
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "3");
            }
            case "full" -> {
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "2");
            }
            case "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0");
            default -> envVars.put("WRAPPER_EMULATE_BCN", "1");
        }

        String bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache");
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache);

        if ("1".equals(astcTranscode))
            envVars.put("BCN_TRANSCODE_TO_ASTC", "1");

        if ("1".equals(etc2Transcode))
            envVars.put("BCN_TRANSCODE_TO_ETC2", "1");

        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1");
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean handledByWinHandler = false;
        boolean handledByTouchpadView = false;

        // Let winHandler process the event if available
        if (winHandler != null) {
            handledByWinHandler = winHandler.onGenericMotionEvent(event);
            if (handledByWinHandler) {
                //Log.d("XServerDisplayActivity", "Event handled by winHandler");
            }
        }

        // Let touchpadView process the event if available
        if (touchpadView != null) {
            handledByTouchpadView = touchpadView.onExternalMouseEvent(event);
            if (handledByTouchpadView) {
                //Log.d("XServerDisplayActivity", "Event handled by touchpadView");
            }
        }

        // Pass the event to the super method to ensure system-level handling
        boolean handledBySuper = super.dispatchGenericMotionEvent(event);
        if (!handledBySuper) {
            //Log.d("XServerDisplayActivity", "Event not handled by super");
        }

        // Combine the results: any handler consuming the event indicates it was handled
        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }


    private static final int RECAPTURE_DELAY_MS = 10000; // 10 seconds

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        // Handle the PlayStation or Xbox Home button to open the drawer
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE || event.getKeyCode() == KeyEvent.KEYCODE_HOME || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT) {
                boolean handled = inputControlsView.onKeyEvent(event) || (winHandler != null && winHandler.onKeyEvent(event)) && (xServer != null && xServer.keyboard.onKeyEvent(event));
                return true;
            }
        }

        // Fallback to existing input handling
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
                (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private static final String TAG = "DXWrapperExtraction";

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files, version: " + dxwrapper);

            String dxvkWrapper = dxwrapper.split(";")[0];
            String vkd3dWrapper = dxwrapper.split(";")[1];
            String ddrawrapper = dxwrapper.split(";")[2];
            
            ContentProfile dxvkProfile = contentsManager.getProfileByEntryName(dxvkWrapper);
            if (dxvkProfile != null) {
                Log.d(TAG, "Applying user-defined DXVK content profile: " + dxvkWrapper);
                contentsManager.applyContent(dxvkProfile);
            } else {
                Log.d(TAG, "Extracting fallback DXVK .tzst archive: " + dxvkWrapper);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxvkWrapper + ".tzst", windowsDir, onExtractFileListener);

                if (compareVersion(dxvkWrapper, "2.4") < 0) {
                    Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxvkWrapper);
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            if (vkd3dWrapper.contains("None")) {
                Log.d(TAG, "No VKD3D has been selected, restoring original d3d12");
                restoreOriginalDllFiles(new String[]{"d3d12.dll", "d3d12core.dll"});
            }
            else {
                ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
                if (vkd3dProfile != null) {
                    Log.d(TAG, "Applying user-defined VKD3D content profile: " + vkd3dWrapper);
                    contentsManager.applyContent(vkd3dProfile);
                } else {
                    Log.d(TAG, "Extracting fallback VKD3D .tzst archive: " + vkd3dWrapper);
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + vkd3dWrapper + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            Log.d(TAG, "Extracting nglide wrapper");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);

            if (ddrawrapper.contains("None")) {
                Log.d(TAG, "No DDRaw wrapper has been selected, restoring original ddraw files");
                restoreOriginalDllFiles(new String[]{ "ddraw.dll", "d3dimm.dll" });
            }
            else {
                if (ddrawrapper.equals("cnc-ddraw"))
                    envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

                Log.d(TAG, "Extracting ddrawrapper " + ddrawrapper);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
            }

            Log.d(TAG, "Finished extraction of DXVK wrapper files, version: " + dxwrapper);
        } else if (dxwrapper.contains("wined3d")) {
            Log.d(TAG, "Restoring original DLL files for wined3d.");
            restoreOriginalDllFiles(dlls);
        }
    }

    private static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);

        if (a[0] != b[0]) return a[0] - b[0];
        if (a[1] != b[1]) return a[1] - b[1];
        return a[2] - b[2];
    }

    private static final Pattern SEMVER_LOOSE =
            Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static int[] parseSemverLoose(String s) {
        if (s == null) return new int[]{0, 0, 0};

        Matcher m = SEMVER_LOOSE.matcher(s);

        String g1 = null, g2 = null, g3 = null;
        while (m.find()) {
            g1 = m.group(1);
            g2 = m.group(2);
            g3 = m.group(3);
        }

        if (g1 == null || g2 == null) {
            return new int[]{0, 0, 0};
        }

        int major = safeParseInt(g1);
        int minor = safeParseInt(g2);
        int patch = safeParseInt(g3);
        return new int[]{major, minor, patch};
    }

    private static int safeParseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
    
    private void extractWinComponentFiles() {
        Log.d("XServerDisplayActivity", "Extracting WinComponents");
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, onExtractFileListener);
                }
                else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }
                Log.d("XServerDisplayActivity", "Setting wincomponent " + identifier + " to " + String.valueOf(useNative));
                WineUtils.overrideWinComponentDlls(this, container, identifier, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
        }
        catch (JSONException e) {}
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File system32dlls = null;
        File syswow64dlls = null;

        if (wineInfo.isArm64EC())
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/aarch64-windows");
        else
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/x86_64-windows");

        syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");


        for (String dll : dlls) {
            File srcFile = new File(system32dlls, dll);
            File dstFile = new File(windowsDir, "system32/" + dll);
            FileUtils.copy(srcFile, dstFile);
            srcFile = new File(syswow64dlls, dll);
            dstFile = new File(windowsDir, "syswow64/" + dll);
            FileUtils.copy(srcFile, dstFile);
        }
   }

    private String getWineStartCommand() {
        // Initialize overrideEnvVars if not already done
        EnvVars envVars = getOverrideEnvVars();

        // Define default arguments
        String args = "";

        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            boolean gtaOpt = shortcut.getExtra("gtaOptimization", "0").equals("1") || (container != null && container.getExtra("gtaOptimization", "0").equals("1"));
            String gtaArgs = " -fullscreen -DX10 -novsync -forcehighpriority -noprecache -noShaderCache -nopostfx -nomemrestrict -norestrictions -anisotropicQualityLevel 0 -shaderQuality 0 -postFX 0 -reflectionQuality 0 -grassQuality 0 -particleQuality 0 -noInGameDOF -cityDensity 0.2 -lodScale 0.0 -pedLodBias 0.0 -vehicleLodBias 0.0";
            if (gtaOpt && !execArgs.contains("-nomemrestrict")) {
                execArgs = execArgs + gtaArgs;
            }
            execArgs = !execArgs.isEmpty() ? " " + execArgs : "";

            if (shortcut.path.endsWith(".lnk")) {
                args += "\"" + shortcut.path + "\"" + execArgs;
            } else {
                String exeDir = FileUtils.getDirname(shortcut.path);
                String filename = FileUtils.getName(shortcut.path);

                int dotIndex = filename.lastIndexOf(".");
                int spaceIndex = (dotIndex != -1) ? filename.indexOf(" ", dotIndex) : -1;

                if (spaceIndex != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }

                args += "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs;
            }
        } else {
            // Append EXTRA_EXEC_ARGS from overrideEnvVars if it exists
            if (envVars.has("EXTRA_EXEC_ARGS")) {
                args += " " + envVars.get("EXTRA_EXEC_ARGS");
                envVars.remove("EXTRA_EXEC_ARGS"); // Remove the key after use
            } else {
                args += "\"wfm.exe\"";
            }
        }
        // Construct the final command
        String command = "winhandler.exe " + args;

        return command;
    }

    private String getExecutable() {
        String filename = "";
        if (shortcut != null) {
            filename = FileUtils.getName(shortcut.path);
        }
        else
            filename = "wfm.exe";
        return filename;
    }


    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = imageFs.getRootDir();
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals("pulseaudio")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void assignTaskAffinity(Window window) {
        if (taskAffinityMask == 0 || taskAffinityMaskWoW64 == 0) return;
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    private void changeFrameRatingVisibility(Window window, Property property) {
        if (frameRating == null) return;

        if (property != null) {
            String propName = property.nameAsString();
            if (propName.contains("_MESA_DRV")) {
                if (frameRatingWindowId == -1 || window.isApplicationWindow()) {
                    if (frameRatingWindowId != window.id) {
                        frameRatingWindowId = window.id;
                        Log.d("XServerDisplayActivity", "HUD owner is now " + window.getName());
                    }
                }
            }
            if (propName.contains("_MESA_DRV_ENGINE_NAME")) {
                runOnUiThread(() -> frameRating.onRendererDetected(property.toString()));
            }
        }
        else if (frameRatingWindowId != -1 && frameRatingWindowId == window.id) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.onRendererGone());
        }
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void registerGyroscope() {
        if (sensorManager != null) {
            Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyroscope != null) {
                sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    private void unregisterGyroscope() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            if (winHandler != null) {
                winHandler.setGyroStick(0, 0);
                xServer.keyboard.setKeyRelease(Binding.KEY_UP.keycode.id);
                xServer.keyboard.setKeyRelease(Binding.KEY_DOWN.keycode.id);
                xServer.keyboard.setKeyRelease(Binding.KEY_LEFT.keycode.id);
                xServer.keyboard.setKeyRelease(Binding.KEY_RIGHT.keycode.id);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE && isGyroEnabled) {
            if (winHandler == null) return;

            if (gyroActivationMode == 1) { // Touchpad
                if (touchpadView != null && !touchpadView.isFingerDown()) {
                    if (gyroTarget == 1) winHandler.setGyroStick(0, 0);
                    return;
                }
            } else if (gyroActivationMode == 2) { // LT
                boolean ltPressed = false;
                if (inputControlsView != null && inputControlsView.getProfile() != null) {
                    GamepadState vState = inputControlsView.getProfile().getGamepadState();
                    if (vState.triggerL >= 0.5f || vState.isPressed((int)ExternalController.IDX_BUTTON_L2)) ltPressed = true;
                }
                
                if (!ltPressed) {
                    for (ExternalController controller : winHandler.getControllers().values()) {
                        if (controller.state.triggerL >= 0.5f || controller.state.isPressed((int)ExternalController.IDX_BUTTON_L2) ||
                            controller.remappedState.triggerL >= 0.5f || controller.remappedState.isPressed((int)ExternalController.IDX_BUTTON_L2)) {
                            ltPressed = true;
                            break;
                        }
                    }
                }

                if (!ltPressed) {
                    if (gyroTarget == 1) winHandler.setGyroStick(0, 0);
                    return;
                }
            }

            float axisX = event.values[0] - gyroBiasX;
            float axisY = event.values[1] - gyroBiasY;

            // Accidental protection: ignore extreme spikes
            if (Math.abs(axisX) > 10.0f || Math.abs(axisY) > 10.0f) return;

            // Apply Deadzone
            if (Math.abs(axisX) < gyroDeadzone) axisX = 0;
            if (Math.abs(axisY) < gyroDeadzone) axisY = 0;

            // Apply Smoothing (Low-pass filter)
            float alpha = 1.0f - gyroSmoothing;
            filteredGyroX = filteredGyroX * (1.0f - alpha) + axisX * alpha;
            filteredGyroY = filteredGyroY * (1.0f - alpha) + axisY * alpha;

            if (gyroTarget == 0) { // Mouse
                int dx = (int) (-filteredGyroX * gyroSensitivity * 30);
                int dy = (int) (filteredGyroY * gyroSensitivity * 30);

                if (dx != 0 || dy != 0) {
                    if (xServer.isRelativeMouseMovement()) {
                        if (winHandler != null) winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                    } else {
                        xServer.injectPointerMoveDelta(dx, dy);
                    }
                }
            } else if (gyroTarget == 1) { // Right Stick
                float rx = -filteredGyroX * gyroSensitivity * 1.5f;
                float ry = filteredGyroY * gyroSensitivity * 1.5f;
                if (winHandler != null) winHandler.setGyroStick(rx, ry);
            } else if (gyroTarget == 2) { // Arrows
                float threshold = 0.3f / Math.max(gyroSensitivity, 0.1f);
                if (-filteredGyroX > threshold) xServer.keyboard.setKeyPress(Binding.KEY_RIGHT.keycode.id, 0);
                else if (-filteredGyroX < -threshold) xServer.keyboard.setKeyPress(Binding.KEY_LEFT.keycode.id, 0);
                else {
                    xServer.keyboard.setKeyRelease(Binding.KEY_LEFT.keycode.id);
                    xServer.keyboard.setKeyRelease(Binding.KEY_RIGHT.keycode.id);
                }

                if (filteredGyroY > threshold) xServer.keyboard.setKeyPress(Binding.KEY_DOWN.keycode.id, 0);
                else if (filteredGyroY < -threshold) xServer.keyboard.setKeyPress(Binding.KEY_UP.keycode.id, 0);
                else {
                    xServer.keyboard.setKeyRelease(Binding.KEY_UP.keycode.id);
                    xServer.keyboard.setKeyRelease(Binding.KEY_DOWN.keycode.id);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}




