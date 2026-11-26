package com.winlator.cmod;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.content.ActivityNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;


import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    public static final byte OPEN_IMAGE_REQUEST_CODE = 5;
    private DrawerLayout drawerLayout;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private SharedPreferences sharedPreferences;
    private ContainerManager containerManager;
    private boolean isDarkMode;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get shared preferences
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.edit().putBoolean("first_wine_prompt_shown", true).apply();

        isDarkMode = true;
        setTheme(R.style.AppTheme_Dark);
        ThemeManager.applyTheme(this);

        setContentView(R.layout.main_activity);
        initializeConsoleLauncher();

        com.winlator.cmod.core.ControllerNavigation.attach(getWindow());
        com.winlator.cmod.logs.SessionLog.installCrashHandler(this);
        if (savedInstanceState == null) com.winlator.cmod.logs.SessionLog.autosaveExitReasons(this);

        drawerLayout = findViewById(R.id.DrawerLayout);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);

        boolean forceShowAdreno = sharedPreferences.getBoolean("show_adrenotools_unsupported", false);
        if (!forceShowAdreno && !com.winlator.cmod.core.GPUInformation.isAdrenoGPU(this)) {
            navigationView.getMenu().findItem(R.id.main_menu_adrenotools_gpu_drivers).setVisible(false);
        } else {
            navigationView.getMenu().findItem(R.id.main_menu_adrenotools_gpu_drivers).setVisible(true);
        }

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.Toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        int accentColor = ThemeManager.getAccentColor(this);
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            setActionBarHomeIcon(R.drawable.icon_action_bar_menu);
        }
        if (toolbar != null) {
            // AppCompat's Toolbar style blocks D-pad focus on touchscreen devices and makes itself a separate
            // keyboard cluster, so a controller could never reach the hamburger or toolbar actions (e.g. +).
            toolbar.setTouchscreenBlocksFocus(false);
            toolbar.setKeyboardNavigationCluster(false);
            toolbar.setTitleTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
            if (toolbar.getOverflowIcon() != null) {
                Drawable overflow = toolbar.getOverflowIcon().mutate();
                overflow.setTint(accentColor);
                toolbar.setOverflowIcon(overflow);
            }
        }

        applyDrawerStyling(navigationView);

        // Create Winlator folder if not present
        File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
        if (!winlatorDir.exists())
            winlatorDir.mkdirs();

        if (containerManager == null) containerManager = new ContainerManager(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            setActionBarHomeIcon(R.drawable.icon_action_bar_back);
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.main_menu_input_controls));
            navigationView.setCheckedItem(R.id.main_menu_input_controls);
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            setActionBarHomeIcon(R.drawable.icon_action_bar_menu);
            if (selectedMenuItemId > 0) {
                onNavigationItemSelected(navigationView.getMenu().findItem(selectedMenuItemId));
                navigationView.setCheckedItem(selectedMenuItemId);
            } else {
                View consoleRoot = findViewById(R.id.consoleRoot);
                if (consoleRoot != null) consoleRoot.setVisibility(View.VISIBLE);
                View fragmentContainer = findViewById(R.id.FLFragmentContainer);
                if (fragmentContainer != null) fragmentContainer.setVisibility(View.GONE);
            }

            if (checkStoragePermissions()) {
                ImageFsInstaller.installIfNeeded(this);
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    showAllFilesAccessDialog();
                } else {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                }
            }
        }
    }

    private void showAllFilesAccessDialog() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle("All Files Access Required")
                    .setMessage("In order to grant access to additional storage devices such as USB storage device, the All Files Access permission must be granted. Press Okay to grant All Files Access in your Android Settings.")
                    .setPositiveButton("Okay", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivity(intent);
                        }
                    })
                    .setNegativeButton("Later", (dialog, which) -> {
                        ImageFsInstaller.installIfNeeded(this);
                    })
                    .show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ImageFsInstaller.installIfNeeded(this);
            } else {
                AppUtils.showToast(this, "Storage permission is recommended for container drives.");
                ImageFsInstaller.installIfNeeded(this);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConsoleUi();
        if (checkStoragePermissions()) {
            ImageFsInstaller.installIfNeeded(this);
        }
        ensureConsoleContainer();
        NavigationView navigationView = findViewById(R.id.NavigationView);
        if (navigationView != null) {
            boolean forceShowAdreno = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("show_adrenotools_unsupported", false);
            MenuItem item = navigationView.getMenu().findItem(R.id.main_menu_adrenotools_gpu_drivers);
            if (item != null) {
                item.setVisible(forceShowAdreno || com.winlator.cmod.core.GPUInformation.isAdrenoGPU(this));
            }
        }
    }

    // Main sections L1/R1 cycle through, in drawer order (About is a dialog, not a section).
    private static final int[] CONTROLLER_SECTIONS = {
        R.id.main_menu_shortcuts, R.id.main_menu_containers, R.id.main_menu_input_controls,
        R.id.main_menu_contents, R.id.main_menu_community_configs, R.id.main_menu_file_manager,
        R.id.main_menu_manage_graphics_drivers, R.id.main_menu_adrenotools_gpu_drivers, R.id.main_menu_settings
    };

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        boolean up = event.getAction() == android.view.KeyEvent.ACTION_UP;
        switch (event.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_BUTTON_START:
                if (up) toggleDrawerForController();
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_SELECT: {
                if (up) {
                    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.Toolbar);
                    if (toolbar != null && !toolbar.showOverflowMenu()) toggleToolbarFocus();
                }
                return true;
            }
            case android.view.KeyEvent.KEYCODE_BUTTON_Y:
                if (up) toggleToolbarFocus();
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_L1:
                if (up) switchSectionForController(-1);
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_R1:
                if (up) switchSectionForController(1);
                return true;
        }
        return com.winlator.cmod.core.ControllerNavigation.dispatch(event, getWindow(), super::dispatchKeyEvent);
    }

    private void toggleDrawerForController() {
        if (drawerLayout == null) return;
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        drawerLayout.openDrawer(GravityCompat.START);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        if (navigationView != null) navigationView.post(() -> focusDrawerItem(navigationView));
    }

    // Focus a real row (the current section) rather than the drawer's list, so the highlight shows at once.
    @android.annotation.SuppressLint("RestrictedApi")
    private void focusDrawerItem(NavigationView navigationView) {
        if (!(navigationView.getChildAt(0) instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup list = (android.view.ViewGroup) navigationView.getChildAt(0);
        View first = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!(child instanceof com.google.android.material.internal.NavigationMenuItemView)) continue;
            if (first == null) first = child;
            androidx.appcompat.view.menu.MenuItemImpl item = ((com.google.android.material.internal.NavigationMenuItemView) child).getItemData();
            if (item != null && item.isChecked()) {
                child.requestFocusFromTouch();
                return;
            }
        }
        if (first != null) first.requestFocusFromTouch();
    }

    // Y: jump between the toolbar (hamburger, + and other actions) and the screen content.
    private void toggleToolbarFocus() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.Toolbar);
        if (toolbar == null) return;
        View focused = getCurrentFocus();
        boolean inToolbar = false;
        for (View v = focused; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == toolbar) {
                inToolbar = true;
                break;
            }
        }
        if (inToolbar) {
            View content = findViewById(R.id.FLFragmentContainer);
            View target = content != null ? content.focusSearch(View.FOCUS_DOWN) : null;
            if (content != null && !content.requestFocus(View.FOCUS_DOWN) && target != null) target.requestFocus();
            return;
        }
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof android.widget.ImageButton && child.isShown()) {
                child.requestFocusFromTouch();
                return;
            }
        }
        toolbar.requestFocusFromTouch();
    }

    private android.hardware.input.InputManager.InputDeviceListener controllerListener;

    private void updateControllerHints() {
        View bar = findViewById(R.id.HSVControllerHints);
        if (bar == null) return;
        boolean connected = com.winlator.cmod.core.ControllerNavigation.isControllerConnected();
        LinearLayout hintContainer = findViewById(R.id.LLControllerHints);
        if (connected && hintContainer != null && hintContainer.getChildCount() == 0) {
            com.winlator.cmod.core.ControllerNavigation.buildHints(hintContainer, new String[][]{
                {"A", "Select", "#3DA35D"},
                {"B", "Back", "#D9453B"},
                {"X", "Item menu", "#2F6FD6"},
                {"Y", "Top bar", "#C9A227"},
                {"LB RB", "Section", "#5A6270"},
                {"LT RT", "Page", "#5A6270"},
                {"START", "Menu", "#5A6270"}
            });
        }
        bar.setVisibility(connected ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        android.hardware.input.InputManager inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null && controllerListener == null) {
            controllerListener = new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) { updateControllerHints(); }
                @Override public void onInputDeviceRemoved(int deviceId) { updateControllerHints(); }
                @Override public void onInputDeviceChanged(int deviceId) { updateControllerHints(); }
            };
            inputManager.registerInputDeviceListener(controllerListener, null);
        }
        updateControllerHints();
    }

    @Override
    protected void onStop() {
        super.onStop();
        android.hardware.input.InputManager inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null && controllerListener != null) {
            inputManager.unregisterInputDeviceListener(controllerListener);
            controllerListener = null;
        }
    }

    private void switchSectionForController(int direction) {
        // Not while a sub-screen (e.g. the save folder picker) is stacked on top, or the editor flow is open.
        if (editInputControls || getSupportFragmentManager().getBackStackEntryCount() > 0) return;
        NavigationView navigationView = findViewById(R.id.NavigationView);
        if (navigationView == null) return;

        MenuItem checked = navigationView.getCheckedItem();
        int current = -1;
        for (int i = 0; i < CONTROLLER_SECTIONS.length; i++) {
            if (checked != null && checked.getItemId() == CONTROLLER_SECTIONS[i]) current = i;
        }
        if (current < 0) current = direction > 0 ? -1 : 0;

        int count = CONTROLLER_SECTIONS.length;
        for (int step = 1; step <= count; step++) {
            int index = ((current + direction * step) % count + count) % count;
            MenuItem item = navigationView.getMenu().findItem(CONTROLLER_SECTIONS[index]);
            if (item != null && item.isVisible()) {
                navigationView.setCheckedItem(item);
                onNavigationItemSelected(item);
                return;
            }
        }
    }

    @Override
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment = fragmentManager.findFragmentById(R.id.FLFragmentContainer);

        if (currentFragment instanceof FileManagerFragment && getOnBackPressedDispatcher().hasEnabledCallbacks()) {
            super.onBackPressed();
            return;
        }

        List<Fragment> fragments = fragmentManager.getFragments();
        for (Fragment fragment : fragments) {
            if (fragment instanceof ContainersFragment && fragment.isVisible()) {
                finish();
                return;
            }
        }
        if (!editInputControls)
            show(new ContainersFragment(), true);  // Pass `true` to trigger the reverse animation
        else
            super.onBackPressed();
    }

    private boolean checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            if (editInputControls) {
                onBackPressed();
                return true;
            }

            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }

    public void setActionBarHomeIcon(int resId) {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            Drawable icon = ContextCompat.getDrawable(this, resId);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(ThemeManager.getAccentColor(this));
                actionBar.setHomeAsUpIndicator(icon);
            } else {
                actionBar.setHomeAsUpIndicator(resId);
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        int accent = ThemeManager.getAccentColor(this);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (item.getIcon() != null) {
                Drawable icon = item.getIcon().mutate();
                icon.setTint(accent);
                item.setIcon(icon);
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    public void toggleDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        View consoleRoot = findViewById(R.id.consoleRoot);
        if (consoleRoot != null) consoleRoot.setVisibility(View.GONE);
        View fragmentContainer = findViewById(R.id.FLFragmentContainer);
        if (fragmentContainer != null) fragmentContainer.setVisibility(View.VISIBLE);
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.main_menu_shortcuts:
                show(new ShortcutsFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_containers:
                show(new ContainersFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_input_controls:
                show(new InputControlsFragment(selectedProfileId), false);  // Forward animation
                break;
            case R.id.main_menu_contents:
                show(new ContentsFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_community_configs:
                show(new CommunityConfigsFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_file_manager:
                show(new FileManagerFragment(), false);
                break;
            case R.id.main_menu_manage_graphics_drivers:
                show(new ManageGraphicsDriversFragment(), false);
                break;
            case R.id.main_menu_adrenotools_gpu_drivers:
                show(new AdrenotoolsFragment(), false);
                break;
            case R.id.main_menu_settings:
                show(new SettingsFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_about:
                showAboutDialog();
                break;
        }

        if (item.getItemId() != R.id.main_menu_about) {
            NavigationView navigationView = findViewById(R.id.NavigationView);
            if (navigationView != null) {
                navigationView.setCheckedItem(item.getItemId());
            }
        }
        return true;
    }


//    private void show(Fragment fragment) {
//        FragmentManager fragmentManager = getSupportFragmentManager();
//        fragmentManager.beginTransaction()
//                .replace(R.id.FLFragmentContainer, fragment)
//                .commit();
//
//        drawerLayout.closeDrawer(GravityCompat.START);
//    }

    private void show(Fragment fragment, boolean reverse) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (reverse) {
            fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_down, R.anim.slide_out_up)  // Reverse animation
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        } else {
            fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)  // Forward animation
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        }

        drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void showAboutDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.about_dialog);
        dialog.findViewById(R.id.LLBottomBar).setVisibility(View.GONE);

        if (isDarkMode) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background_dark);
        } else {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background);
        }

        try {
            final PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);

            TextView tvWebpage = dialog.findViewById(R.id.TVWebpage);
            tvWebpage.setText(Html.fromHtml("<a href=\"https://github.com/GunaCharanTeja/WinlatorMali\">github.com/GunaCharanTeja/WinlatorMali</a>", Html.FROM_HTML_MODE_LEGACY));
            tvWebpage.setMovementMethod(LinkMovementMethod.getInstance());

            ((TextView) dialog.findViewById(R.id.TVAppVersion)).setText(getString(R.string.version) + " " + pInfo.versionName + " (egl-renderer)");

            String branchFeaturesHTML = String.join("<br />",
                    "<b>• BCn Texture Emulation &amp; Disk Cache:</b> Hardware-accelerated Vulkan BCn layer with ASTC/ETC2 transcode, Native Direct toggle, and persistent disk caching for Mali GPUs.",
                    "<b>• ZeroFG Frame Generation:</b> Custom Mali-optimized frame interpolation engine for higher perceived framerates.",
                    "<b>• Winlator Telemetry HUD:</b> Direct Mali GPU load and clock telemetry via sysfs, real CPU usage, thermal status, battery watts, 1% and 0.1% lows, frame-time graph, and Madeira Glass styling.",
                    "<b>• USB Gamepad &amp; Vibration:</b> True USB controller attachment, auto-grant permission persistence, rumble feedback, and full D-Pad/controller UI navigation.",
                    "<b>• Community Configs &amp; Ratings:</b> Cloud config sync, automated Mali/Adreno badges, unbundled Wine downloader flow, and real-time community Helpful / Didn't Work ratings.",
                    "<b>• Save Manager &amp; Hero Art:</b> 16:9 SteamGridDB hero art, fullscreen launch screens, and automatic save detection &amp; restoration for standard and cracked games."
            );
            TextView tvBranchFeatures = dialog.findViewById(R.id.TVBranchFeatures);
            if (tvBranchFeatures != null) {
                tvBranchFeatures.setText(Html.fromHtml(branchFeaturesHTML, Html.FROM_HTML_MODE_LEGACY));
            }

            String creditsHTML = String.join("<br />",
                    "<b>Winlator Mali</b> by <a href=\"https://github.com/GunaCharanTeja\">GunaCharanTeja (Fcharan)</a>",
                    "<b>Winlator &amp; Gladio</b> by <a href=\"https://github.com/brunodev85\">Bruno (brunodev85)</a>",
                    "Fork of <b>Winlator Bionic</b> by <a href=\"https://github.com/Pipetto-crypto\">Pipetto Cripto</a>",
                    "<b>BCN Layer &amp; Transcode</b> by <a href=\"https://github.com/leegao\">leegao</a>",
                    "Winlator HUD &amp; File Manager from <b>Winlator Ludashi</b> by <a href=\"https://github.com/StevenMXZ\">stevenmxz</a>",
                    "Controller fixes &amp; Navigation by <a href=\"https://github.com/Vivsi1\">vivsi1</a>",
                    "Theme system &amp; CI contributions by <a href=\"https://github.com/Noysz\">Noysz</a>",
                    "Original Community Configs Concept by <a href=\"https://github.com/The412Banner\">The412Banner</a>",
                    "GLIBC Fork contributions by <a href=\"https://github.com/longjunyu2\">longjunyu2</a>"
            );
            TextView tvCredits = dialog.findViewById(R.id.TVCreditsAndThirdPartyApps);
            if (tvCredits != null) {
                tvCredits.setText(Html.fromHtml(creditsHTML, Html.FROM_HTML_MODE_LEGACY));
                tvCredits.setMovementMethod(LinkMovementMethod.getInstance());
            }

            String licensesHTML = String.join("<br />",
                    "<b>Wine / Proton:</b> LGPL v2.1+ (<a href=\"https://www.winehq.org\">winehq.org</a>)",
                    "<b>Box64 &amp; FEX-Emu:</b> MIT (<a href=\"https://github.com/ptitSeb/box64\">ptitSeb</a> / <a href=\"https://github.com/FEX-Emu/FEX\">FEX-Emu</a>)",
                    "<b>Mesa (Turnip / Zink / Wrapper):</b> MIT / LGPL (<a href=\"https://github.com/xMeM/mesa\">xMeM</a>)",
                    "<b>DXVK &amp; VKD3D:</b> zlib / LGPL v2.1 (<a href=\"https://github.com/doitsujin/dxvk\">doitsujin</a> / <a href=\"https://gitlab.winehq.org/wine/vkd3d\">WineHQ</a>)",
                    "<b>D8VK &amp; dxwrapper &amp; CNC DDraw:</b> zlib / GPL / MIT",
                    "<b>libadrenotools:</b> GPL v3 (<a href=\"https://github.com/bylaws/libadrenotools\">bylaws</a>)",
                    "<b>ZeroFG Frame Generation:</b> Apache 2.0 / BSD 3-Clause",
                    "<b>Inter Font:</b> SIL Open Font License 1.1",
                    "<b>Android Open Source Project:</b> Apache 2.0"
            );
            TextView tvLicenses = dialog.findViewById(R.id.TVLicenses);
            if (tvLicenses != null) {
                tvLicenses.setText(Html.fromHtml(licensesHTML, Html.FROM_HTML_MODE_LEGACY));
                tvLicenses.setMovementMethod(LinkMovementMethod.getInstance());
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        dialog.show();
    }

    private void applyDrawerStyling(NavigationView navigationView) {
        int accentColor = ThemeManager.getAccentColor(this);
        int onSurfaceColor = ThemeManager.getOnSurfaceTextColor(this);
        int surfaceColor = ThemeManager.getSurfaceColor(this);

        navigationView.setBackgroundColor(surfaceColor);

        int[][] states = new int[][]{
            new int[]{android.R.attr.state_checked},
            new int[]{android.R.attr.state_pressed},
            new int[]{}
        };
        // The selected item reads brighter than the rest: its text is the accent lifted towards the normal text color
        // (plain accent on an accent pill was darker than the unselected items), its icon the full accent.
        int checkedIcon = androidx.core.graphics.ColorUtils.blendARGB(accentColor, onSurfaceColor, 0.35f);
        int normalText = (onSurfaceColor & 0x00FFFFFF) | (0xD9 << 24);
        android.content.res.ColorStateList textStateList = new android.content.res.ColorStateList(states,
            new int[]{onSurfaceColor, onSurfaceColor, normalText});
        android.content.res.ColorStateList iconStateList = new android.content.res.ColorStateList(states,
            new int[]{checkedIcon, checkedIcon, (onSurfaceColor & 0x00FFFFFF) | (0xA8 << 24)});
        navigationView.setItemTextColor(textStateList);
        navigationView.setItemIconTintList(iconStateList);

        // Section titles and dividers: Material 1.4 draws them from the theme's fixed colors (white), which vanish on a
        // light custom palette. They are recolored from the preset's readable text and divider colors on every layout.
        int subheaderColor = (onSurfaceColor & 0x00FFFFFF) | (0xA6 << 24);
        int dividerColor = ThemeManager.getDividerColor(this);
        View menuList = navigationView.getChildAt(0);
        if (menuList instanceof ViewGroup) {
            ViewGroup list = (ViewGroup) menuList;
            list.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                for (int i = 0; i < list.getChildCount(); i++) {
                    View child = list.getChildAt(i);
                    if (child instanceof TextView) ((TextView) child).setTextColor(subheaderColor);
                    else if (child instanceof ViewGroup && child.getClass().getSimpleName().contains("Separator")) {
                        View line = ((ViewGroup) child).getChildCount() > 0 ? ((ViewGroup) child).getChildAt(0) : null;
                        if (line != null) line.setBackgroundColor(dividerColor);
                    }
                }
            });
        }

        // The app version under the title.
        View header = navigationView.getHeaderCount() > 0 ? navigationView.getHeaderView(0) : null;
        // Header text follows the preset's readable text color (a custom palette can have a light background).
        TextView tvTitle = header != null ? header.findViewById(R.id.TVHeaderTitle) : null;
        if (tvTitle != null) tvTitle.setTextColor(onSurfaceColor);
        TextView tvVersion = header != null ? header.findViewById(R.id.TVHeaderVersion) : null;
        if (tvVersion != null) tvVersion.setTextColor(onSurfaceColor);
        if (tvVersion != null) {
            try {
                tvVersion.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                tvVersion.setVisibility(View.VISIBLE);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        // Dynamic theme accent pill highlight
        int pillAlpha = 0x55; // ~33% alpha
        int pillColor = (accentColor & 0x00FFFFFF) | (pillAlpha << 24);
        int rippleColor = (accentColor & 0x00FFFFFF) | (0x33 << 24);

        android.graphics.drawable.GradientDrawable checkedDrawable = new android.graphics.drawable.GradientDrawable();
        checkedDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        checkedDrawable.setCornerRadius(dpToPx(24));
        checkedDrawable.setColor(pillColor);

        android.graphics.drawable.GradientDrawable transparentDrawable = new android.graphics.drawable.GradientDrawable();
        transparentDrawable.setColor(android.graphics.Color.TRANSPARENT);

        android.graphics.drawable.StateListDrawable stateListDrawable = new android.graphics.drawable.StateListDrawable();
        stateListDrawable.addState(new int[]{android.R.attr.state_checked}, checkedDrawable);
        stateListDrawable.addState(new int[]{}, transparentDrawable);

        // The pill (and its ripple) floats inside the drawer: a custom item background ignores itemShapeInset*.
        android.graphics.drawable.GradientDrawable rippleMask = new android.graphics.drawable.GradientDrawable();
        rippleMask.setCornerRadius(dpToPx(24));
        rippleMask.setColor(Color.WHITE);
        android.graphics.drawable.RippleDrawable rippleDrawable = new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleColor),
            stateListDrawable,
            rippleMask
        );
        int insetH = (int) dpToPx(12), insetV = (int) dpToPx(2);
        navigationView.setItemBackground(new android.graphics.drawable.InsetDrawable(rippleDrawable, insetH, insetV, insetH, insetV));
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    public ContainerManager getContainerManager() {
        // Fragments restored after process death call this from super.onCreate(), before onCreate assigns it.
        if (containerManager == null) containerManager = new ContainerManager(this);
        return containerManager;
    }

    
    private File consoleGameFile;
    private static final String CONSOLE_PREFS = "naruto_console_launcher";
    private static final String KEY_GAME_PATH = "game_path";
    private static final String KEY_RESOLUTION = "resolution_hd";
    private static final String KEY_DXVK = "dxvk_1103";
    private static final String KEY_FPS = "fps_60";
    private static final String KEY_HUD = "hud_enabled";
    private static final String KEY_CPU_FULL = "cpu_full";
    private boolean consoleContainerCreationPending = false;
    private boolean consoleImageFsInstallPending = false;
    private boolean consoleRuntimeInstallAttempted = false;
    private void initializeConsoleLauncher() {
        View toolbar = findViewById(R.id.Toolbar); if (toolbar != null) toolbar.setVisibility(View.GONE);
        NavigationView nav = findViewById(R.id.NavigationView); if (nav != null) nav.setVisibility(View.GONE);
        SharedPreferences prefs = getSharedPreferences(CONSOLE_PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_GAME_PATH, "");
        if (!saved.isEmpty()) {
            File selected = new File(saved);
            if (selected.isFile() && selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                consoleGameFile = selected;
            } else {
                prefs.edit().remove(KEY_GAME_PATH).apply();
                consoleGameFile = null;
            }
        }
        refreshConsoleUi();
        TextView runtimeStatus = findViewById(R.id.txtConsoleRuntimeStatus);
        if (runtimeStatus != null) {
            runtimeStatus.setClickable(true);
            runtimeStatus.setFocusable(true);
            runtimeStatus.setContentDescription("Buka Runtime Manager");
            runtimeStatus.setOnClickListener(v -> showConsoleRuntimeManager());
        }
        View diagnosticsButton = findViewById(R.id.btnConsoleDiagnostics);
        if (diagnosticsButton != null) diagnosticsButton.setOnClickListener(v -> showConsoleDiagnosticsDialog());
        findViewById(R.id.btnConsoleGear).setOnClickListener(v -> {
            View root = findViewById(R.id.consoleRoot); if (root != null) root.setVisibility(View.GONE);
            View fc = findViewById(R.id.FLFragmentContainer); if (fc != null) fc.setVisibility(View.VISIBLE);
            if (nav != null) nav.setVisibility(View.VISIBLE); drawerLayout.openDrawer(GravityCompat.START);
        });
        findViewById(R.id.btnConsolePick).setOnClickListener(v -> {
            if (!checkStoragePermissions()) {
                Toast.makeText(this, "Izin akses penyimpanan diperlukan.", Toast.LENGTH_LONG).show();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) showAllFilesAccessDialog();
                else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
                return;
            }
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-msdownload","application/octet-stream"});
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(pick, OPEN_FILE_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "File picker Android tidak tersedia di perangkat ini.", Toast.LENGTH_LONG).show();
                android.util.Log.e("ConsoleLauncher", "Could not open EXE picker", e);
            }
        });
        findViewById(R.id.btnConsoleResolution).setOnClickListener(v -> { prefs.edit().putBoolean(KEY_RESOLUTION,!prefs.getBoolean(KEY_RESOLUTION,false)).apply(); refreshConsoleUi(); });
        findViewById(R.id.btnConsoleDxvk).setOnClickListener(v -> { prefs.edit().putBoolean(KEY_DXVK,!prefs.getBoolean(KEY_DXVK,false)).apply(); refreshConsoleUi(); });
        findViewById(R.id.btnConsoleFps).setOnClickListener(v -> { prefs.edit().putBoolean(KEY_FPS,!prefs.getBoolean(KEY_FPS,false)).apply(); refreshConsoleUi(); });
        findViewById(R.id.btnConsoleControls).setOnClickListener(v -> { prefs.edit().putBoolean(KEY_HUD,!prefs.getBoolean(KEY_HUD,true)).apply(); refreshConsoleUi(); });
        findViewById(R.id.btnConsoleCpu).setOnClickListener(v -> { prefs.edit().putBoolean(KEY_CPU_FULL,!prefs.getBoolean(KEY_CPU_FULL,false)).apply(); refreshConsoleUi(); });
        findViewById(R.id.btnConsolePlay).setOnClickListener(v -> launchConsoleGame());
    }

    private String buildConsoleRuntimeReport() {
        StringBuilder report = new StringBuilder();
        boolean storageReady = checkStoragePermissions();
        ImageFs imageFs = ImageFs.find(this);
        boolean imageFsReady = imageFs.isValid();
        report.append("Penyimpanan: ").append(storageReady ? "OK" : "IZIN DIPERLUKAN").append('\n');
        report.append("ImageFS: ").append(imageFsReady ? "VALID" : "BELUM VALID");
        if (imageFsReady) {
            try { report.append(" (versi ").append(imageFs.getVersion()).append(')'); }
            catch (Exception e) { report.append(" (versi tidak terbaca)"); }
        }
        report.append('\n');

        if (containerManager == null) containerManager = new ContainerManager(this);
        Container consoleContainer = null;
        for (Container candidate : containerManager.getContainers()) {
            if ("Naruto Storm Console".equals(candidate.getName())) {
                consoleContainer = candidate;
                break;
            }
        }
        boolean runtimeReady = false;
        report.append("Container: ").append(consoleContainer == null ? "BELUM DIBUAT" : "DITEMUKAN").append('\n');
        if (consoleContainer != null) {
            report.append("Runtime ID: ").append(consoleContainer.getWineVersion()).append('\n');
            try {
                ContentsManager contents = new ContentsManager(this);
                contents.syncContents();
                WineInfo info = WineInfo.fromIdentifier(this, contents, consoleContainer.getWineVersion());
                File runtimeRoot = info.path == null ? null : new File(info.path);
                boolean binReady = runtimeRoot != null && new File(runtimeRoot, "bin").isDirectory();
                boolean libReady = runtimeRoot != null && new File(runtimeRoot, "lib").isDirectory();
                runtimeReady = binReady && libReady;
                report.append("Folder runtime: ").append(runtimeRoot == null ? "TIDAK DITEMUKAN" : runtimeRoot.getAbsolutePath()).append('\n');
                report.append("Direktori bin: ").append(binReady ? "OK" : "HILANG").append('\n');
                report.append("Direktori lib: ").append(libReady ? "OK" : "HILANG").append('\n');
                report.append("Validasi runtime: ").append(runtimeReady ? "LULUS" : "GAGAL").append('\n');
                if (!runtimeReady) report.append("Catatan: runtime perlu dipasang ulang atau dipilih dari Contents.\n");
            } catch (Exception e) {
                report.append("Validasi runtime: ERROR - ").append(e.getMessage()).append('\n');
                android.util.Log.e("ConsoleLauncher", "Runtime Manager validation failed", e);
            }
        }
        report.append("\nFile game: ").append(consoleGameFile != null && consoleGameFile.isFile() ? "DIPILIH" : "BELUM DIPILIH").append('\n');
        if (consoleGameFile != null) report.append("EXE: ").append(consoleGameFile.getName()).append('\n');
        report.append("\nStatus pekerjaan: ")
                .append(consoleImageFsInstallPending ? "ImageFS sedang dipasang; " : "")
                .append(consoleRuntimeInstallPending ? "Runtime sedang diunduh/dipasang; " : "")
                .append(consoleContainerCreationPending ? "Container sedang dibuat; " : "");
        boolean pending = consoleImageFsInstallPending || consoleRuntimeInstallPending || consoleContainerCreationPending;
        if (!pending) report.append("tidak ada proses berjalan");
        boolean overallReady = storageReady && imageFsReady && consoleContainer != null && runtimeReady && !pending;
        report.insert(0, "STATUS: " + (pending ? "SEDANG DISIAPKAN" : (overallReady ? "SIAP" : "BELUM SIAP")) + "\n\n");
        return report.toString();
    }

    private void showConsoleRuntimeManager() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("RUNTIME MANAGER")
                .setMessage(buildConsoleRuntimeReport())
                .setPositiveButton("SIAPKAN / COBA LAGI", (d, which) -> {
                    if (!checkStoragePermissions()) {
                        Toast.makeText(this, "Berikan izin penyimpanan terlebih dahulu.", Toast.LENGTH_LONG).show();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) showAllFilesAccessDialog();
                        else ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                                PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
                        return;
                    }
                    if (ImageFsInstaller.installIfNeeded(this)) {
                        Toast.makeText(this, "ImageFS sedang dipasang. Status akan diperbarui setelah selesai.", Toast.LENGTH_LONG).show();
                    } else {
                        consoleRuntimeInstallAttempted = false;
                        ensureConsoleContainer();
                        refreshConsoleUi();
                        Toast.makeText(this, "Pemeriksaan dan penyiapan runtime dimulai.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("PERIKSA ULANG", (d, which) -> showConsoleRuntimeManager())
                .setNegativeButton("TUTUP", null)
                .create();
        dialog.show();
    }

    private String buildConsoleDiagnostics() {
        StringBuilder report = new StringBuilder();
        report.append("NARUTO STORM CONSOLE - DIAGNOSTICS\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Waktu: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date())).append("\n\n");
        report.append("=== RUNTIME CHECK ===\n").append(buildConsoleRuntimeReport()).append("\n\n");
        report.append("=== GAME SELECTION ===\n");
        if (consoleGameFile != null && consoleGameFile.isFile()) {
            report.append("Nama: ").append(consoleGameFile.getName()).append('\n');
            report.append("Path: ").append(consoleGameFile.getAbsolutePath()).append('\n');
            report.append("Ukuran: ").append(consoleGameFile.length()).append(" bytes\n");
        } else {
            report.append("Belum ada file EXE yang dipilih.\n");
        }
        report.append("\n=== APP LOGCAT (250 BARIS TERAKHIR) ===\n");
        report.append(com.winlator.cmod.logs.SessionLog.logcat(250));
        return report.toString();
    }

    private void showConsoleDiagnosticsDialog() {
        final String diagnostics = buildConsoleDiagnostics();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("DIAGNOSTICS & LOG")
                .setMessage(diagnostics)
                .setPositiveButton("SALIN LOG", (d, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Naruto Storm diagnostics", diagnostics));
                        Toast.makeText(this, "Diagnostics disalin.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("BAGIKAN LOG", (d, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_SUBJECT, "Naruto Storm Console diagnostics");
                    share.putExtra(Intent.EXTRA_TEXT, diagnostics);
                    try { startActivity(Intent.createChooser(share, "Bagikan diagnostics")); }
                    catch (ActivityNotFoundException e) { Toast.makeText(this, "Tidak ada aplikasi untuk membagikan log.", Toast.LENGTH_SHORT).show(); }
                })
                .setNegativeButton("SIMPAN LOG", (d, which) -> {
                    try {
                        File directory = com.winlator.cmod.logs.LogFiles.appDir(this);
                        File output = new File(directory, "console_diagnostics_" + System.currentTimeMillis() + ".txt");
                        try (FileOutputStream stream = new FileOutputStream(output)) {
                            stream.write(diagnostics.getBytes(StandardCharsets.UTF_8));
                        }
                        Toast.makeText(this, "Log tersimpan: " + output.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        android.util.Log.e("ConsoleLauncher", "Could not save diagnostics log", e);
                        Toast.makeText(this, "Log tidak dapat disimpan: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .create();
        dialog.show();
    }

    private String resolvePickedExePath(Uri uri) {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) return uri.getPath();
        if (!"content".equalsIgnoreCase(uri.getScheme())) return null;
        try {
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
            String documentId = android.provider.DocumentsContract.getDocumentId(uri);
            int colon = documentId.indexOf(':');
            if (colon < 0) return null;
            String volume = documentId.substring(0, colon);
            String relative = Uri.decode(documentId.substring(colon + 1));
            if ("primary".equalsIgnoreCase(volume))
                return new File(Environment.getExternalStorageDirectory(), relative).getAbsolutePath();
            return new File("/storage/" + volume + "/" + relative).getAbsolutePath();
        } catch (IllegalArgumentException e) {
            android.util.Log.w("ConsoleLauncher", "Cannot resolve selected URI to a direct storage path", e);
            return null;
        }
    }

    private void refreshConsoleUi() {
        SharedPreferences p = getSharedPreferences(CONSOLE_PREFS, MODE_PRIVATE);
        boolean hasGame = consoleGameFile != null && consoleGameFile.isFile();
        TextView name = findViewById(R.id.txtConsoleGame);
        TextView path = findViewById(R.id.txtConsoleGamePath);
        if (name != null) name.setText(hasGame ? consoleGameFile.getName() : "NSUNSR.exe belum dipilih");
        if (path != null) path.setText(hasGame ? consoleGameFile.getParent() : "Pilih folder game dari penyimpanan internal");
        Button resolution = findViewById(R.id.btnConsoleResolution);
        Button dxvk = findViewById(R.id.btnConsoleDxvk);
        Button fps = findViewById(R.id.btnConsoleFps);
        Button cpu = findViewById(R.id.btnConsoleCpu);
        Button hud = findViewById(R.id.btnConsoleControls);
        if (resolution != null) resolution.setText("RESOLUSI : " + (p.getBoolean(KEY_RESOLUTION, false) ? "1280x720" : "960x544"));
        if (dxvk != null) dxvk.setText("DXVK : " + (p.getBoolean(KEY_DXVK, false) ? "1.10.3" : "1.11"));
        if (fps != null) fps.setText("TARGET : " + (p.getBoolean(KEY_FPS, false) ? "60 FPS" : "30 FPS"));
        if (cpu != null) cpu.setText("MODE CPU : " + (p.getBoolean(KEY_CPU_FULL, false) ? "Penuh (8 Core)" : "Adem (4 Core)"));
        if (hud != null) hud.setText("HUD LAYAR : " + (p.getBoolean(KEY_HUD, true) ? "ON" : "OFF"));

        boolean installing = consoleImageFsInstallPending || consoleRuntimeInstallPending || consoleContainerCreationPending;
        ProgressBar progress = findViewById(R.id.progressConsoleRuntime);
        if (progress != null) progress.setVisibility(installing ? View.VISIBLE : View.GONE);
        TextView badge = findViewById(R.id.txtConsoleRuntimeStatus);
        TextView status = findViewById(R.id.txtConsoleGameStatus);
        boolean ready = false;
        if (containerManager != null && containerManager.isInitialized()) {
            for (Container c : containerManager.getContainers()) {
                if ("Naruto Storm Console".equals(c.getName())) {
                    ready = isValidConsoleRuntime(c.getWineVersion(), new ContentsManager(this));
                    break;
                }
            }
        }
        if (badge != null) badge.setText(!checkStoragePermissions() ? "RUNTIME • IZIN DIPERLUKAN" :
                (installing ? "RUNTIME • MENYIAPKAN" : (!ImageFs.find(this).isValid() ?
                "RUNTIME • IMAGEFS BELUM SIAP" : (ready ? "RUNTIME • SIAP" : "RUNTIME • MENCARI"))));
        if (status != null) {
            status.setText(!hasGame ? "● PILIH FILE GAME" : (ready && !installing ?
                    "● SIAP DIMAINKAN" : "● RUNTIME SEDANG DISIAPKAN"));
            status.setTextColor(ready && hasGame && !installing ? Color.parseColor("#00D4FF") : Color.parseColor("#FFB36B"));
        }
    }

    public void onImageFsInstallationStarted() {
        consoleImageFsInstallPending = true;
        runOnUiThread(() -> {
            ProgressBar progress = findViewById(R.id.progressConsoleRuntime);
            if (progress != null) progress.setVisibility(View.VISIBLE);
            TextView badge = findViewById(R.id.txtConsoleRuntimeStatus);
            if (badge != null) badge.setText("RUNTIME • MEMASANG IMAGEFS");
            TextView status = findViewById(R.id.txtConsoleGameStatus);
            if (status != null) status.setText("● MEMASANG IMAGEFS");
        });
    }

    public void onImageFsInstallationFinished() {
        consoleImageFsInstallPending = false;
        if (isFinishing() || isDestroyed()) return;
        ProgressBar progress = findViewById(R.id.progressConsoleRuntime);
        if (progress != null) progress.setVisibility(View.GONE);
        if (!ImageFs.find(this).isValid()) {
            TextView badge = findViewById(R.id.txtConsoleRuntimeStatus);
            if (badge != null) badge.setText("RUNTIME • IMAGEFS GAGAL");
            refreshConsoleUi();
            return;
        }
        ensureConsoleContainer();
        refreshConsoleUi();
    }

    private volatile boolean consoleRuntimeInstallPending = false;

    private String getConsoleRuntimeIdentifier(ContentProfile profile) {
        if (profile == null || profile.verName == null || profile.verName.isEmpty()) return "";
        if (profile.wineBinPath != null && new File(profile.wineBinPath).isAbsolute()) return profile.verName;
        return ContentsManager.getEntryName(profile);
    }

    private boolean isValidConsoleRuntime(String identifier, ContentsManager cm) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        WineInfo info = WineInfo.fromIdentifier(this, cm, identifier);
        if (info.path == null || info.path.trim().isEmpty()) return false;
        File root = new File(info.path);
        return root.isDirectory() && new File(root, "bin").isDirectory() && new File(root, "lib").isDirectory();
    }

    private ContentProfile findConsoleProtonProfile(ContentsManager cm) {
        for (ContentProfile profile : cm.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            if (profile == null || profile.verName == null) continue;
            String name = profile.verName.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("11.0-2") &&
                    (name.contains("x86_box64") || name.contains("x86_64") || name.contains("x86-64")) &&
                    isValidConsoleRuntime(getConsoleRuntimeIdentifier(profile), cm)) return profile;
        }
        return null;
    }

    private ContentProfile findConsoleFallbackProfile(ContentsManager cm) {
        ContentProfile best = null;
        int bestScore = Integer.MAX_VALUE;
        ContentProfile.ContentType[] types = {
                ContentProfile.ContentType.CONTENT_TYPE_PROTON,
                ContentProfile.ContentType.CONTENT_TYPE_WINE
        };
        for (ContentProfile.ContentType type : types) {
            for (ContentProfile profile : cm.getInstalledProfiles(type)) {
                if (profile == null || profile.verName == null || profile.type == null) continue;
                String name = profile.verName.toLowerCase(java.util.Locale.ROOT);
                if (!(name.contains("bionic") || name.startsWith("wine-") || name.startsWith("proton-") ||
                        name.contains("x86_64") || name.contains("x86_box64"))) continue;
                String id = getConsoleRuntimeIdentifier(profile);
                if (!isValidConsoleRuntime(id, cm)) continue;
                int score = name.contains("bionic") ? 0 : 10;
                if (!(name.contains("x86_64") || name.contains("x86_box64") || name.contains("x86-64"))) score += 2;
                if (type == ContentProfile.ContentType.CONTENT_TYPE_WINE) score++;
                if (score < bestScore) { best = profile; bestScore = score; }
            }
        }
        return best;
    }

    private void ensureConsoleContainer() {
        if (consoleContainerCreationPending || consoleRuntimeInstallPending || consoleImageFsInstallPending) return;
        if (containerManager == null) containerManager = new ContainerManager(this);
        if (!containerManager.isInitialized() || !ImageFs.find(this).isValid()) return;
        ContentsManager cm = new ContentsManager(this);
        cm.syncContents();

        Container existing = null;
        for (Container c : containerManager.getContainers()) {
            if ("Naruto Storm Console".equals(c.getName())) { existing = c; break; }
        }
        if (existing != null && isValidConsoleRuntime(existing.getWineVersion(), cm)) {
            refreshConsoleUi();
            return;
        }
        ContentProfile proton = findConsoleProtonProfile(cm);
        if (proton != null) { createConsoleContainer(cm, proton); return; }
        if (!consoleRuntimeInstallAttempted) { installConsoleProtonRuntime(); return; }
        ContentProfile fallback = findConsoleFallbackProfile(cm);
        if (fallback != null) { createConsoleContainer(cm, fallback); return; }

        ProgressBar progress = findViewById(R.id.progressConsoleRuntime);
        if (progress != null) progress.setVisibility(View.GONE);
        TextView badge = findViewById(R.id.txtConsoleRuntimeStatus);
        if (badge != null) badge.setText("RUNTIME • TIDAK TERSEDIA");
        refreshConsoleUi();
    }

    private String findConsoleProtonDownloadUrl(String catalogJson) {
        try {
            JSONArray entries = new JSONArray(catalogJson);
            String fallbackUrl = null;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String verName = entry.optString("verName", entry.optString("versionName", ""));
                String versionName = entry.optString("versionName", "");
                String url = entry.optString("remoteUrl", "");
                String identity = (verName + " " + versionName + " " + url).toLowerCase(java.util.Locale.ROOT);
                if (!identity.contains("11.0-2")) continue;
                if (!(identity.contains("x86_box64") || identity.contains("x86_64") || identity.contains("x86-64"))) continue;
                if (!url.startsWith("https://github.com/The412Banner/")) continue;
                if (identity.contains("x86_box64")) return url;
                if (fallbackUrl == null && !identity.contains("arm64ec")) fallbackUrl = url;
            }
            return fallbackUrl;
        } catch (JSONException e) {
            android.util.Log.e("ConsoleLauncher", "Invalid Proton catalog JSON", e);
            return null;
        }
    }

    private void installConsoleProtonRuntime() {
        if (consoleRuntimeInstallPending || consoleRuntimeInstallAttempted) return;
        consoleRuntimeInstallAttempted = true;
        consoleRuntimeInstallPending = true;
        new Thread(() -> {
            File archive = new File(getCacheDir(), "proton-11.0-2-x86_64.wcp");
            File stagingDir = new File(ContentsManager.getContentDir(this), ".naruto-proton-staging");
            try {
                String catalogJson = com.winlator.cmod.contents.Downloader.downloadString(ContentsManager.BANNERLATOR_PROFILES);
                String downloadUrl = catalogJson != null ? findConsoleProtonDownloadUrl(catalogJson) : null;
                if (downloadUrl == null) {
                    finishConsoleProtonInstall(false, "Paket Proton 11.0-2 x86_64 tidak ditemukan di katalog.");
                    return;
                }
                if (!com.winlator.cmod.contents.Downloader.downloadFile(downloadUrl, archive) || !archive.isFile() || archive.length() < 1024L * 1024L) {
                    finishConsoleProtonInstall(false, "Unduhan Proton 11.0-2 gagal atau arsip tidak lengkap.");
                    return;
                }

                ContentsManager installer = new ContentsManager(this);
                installer.extraContentFile(Uri.fromFile(archive), stagingDir, new ContentsManager.OnInstallFinishedCallback() {
                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        android.util.Log.e("ConsoleLauncher", "Proton runtime extraction failed: " + reason, e);
                        finishConsoleProtonInstall(false, "Ekstraksi Proton 11.0-2 gagal: " + reason);
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        if (profile == null || profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON ||
                                profile.verName == null || !profile.verName.toLowerCase(java.util.Locale.ROOT).contains("11.0-2")) {
                            finishConsoleProtonInstall(false, "Arsip yang diunduh bukan Proton 11.0-2.");
                            return;
                        }
                        if (profile.installedDirect) {
                            installer.syncContents();
                            finishConsoleProtonInstall(true, null);
                            return;
                        }
                        installer.finishInstallContent(profile, stagingDir, new ContentsManager.OnInstallFinishedCallback() {
                            @Override
                            public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                                android.util.Log.e("ConsoleLauncher", "Proton runtime install failed: " + reason, e);
                                finishConsoleProtonInstall(false, "Instalasi Proton 11.0-2 gagal: " + reason);
                            }

                            @Override
                            public void onSucceed(ContentProfile installedProfile) {
                                installer.syncContents();
                                finishConsoleProtonInstall(true, null);
                            }
                        });
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("ConsoleLauncher", "Automatic Proton installation failed", e);
                finishConsoleProtonInstall(false, "Runtime Proton tidak dapat dipasang: " + e.getMessage());
            } finally {
                archive.delete();
                FileUtils.delete(stagingDir);
            }
        }, "ConsoleProtonInstaller").start();
    }

    private void finishConsoleProtonInstall(boolean success, String message) {
        consoleRuntimeInstallPending = false;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            ContentsManager cm = new ContentsManager(this);
            cm.syncContents();
            if (success) {
                ensureConsoleContainer();
                return;
            }
            ContentProfile fallback = findConsoleFallbackProfile(cm);
            if (fallback != null) {
                Toast.makeText(this, "Proton 11.0-2 tidak tersedia. Menggunakan runtime cadangan " +
                        fallback.verName + ".", Toast.LENGTH_LONG).show();
                createConsoleContainer(cm, fallback);
                return;
            }
            ProgressBar progress = findViewById(R.id.progressConsoleRuntime);
            if (progress != null) progress.setVisibility(View.GONE);
            TextView badge = findViewById(R.id.txtConsoleRuntimeStatus);
            if (badge != null) badge.setText("RUNTIME • TIDAK TERSEDIA");
            Toast.makeText(this, (message == null ? "Instalasi Proton gagal." : message) +
                    " Runtime Wine/Proton cadangan yang valid tidak ditemukan.", Toast.LENGTH_LONG).show();
            refreshConsoleUi();
        });
    }

    private void createConsoleContainer(ContentsManager cm, ContentProfile profile) {
        String wineVersion = getConsoleRuntimeIdentifier(profile);
        if (!isValidConsoleRuntime(wineVersion, cm)) {
            Toast.makeText(this, "Runtime tidak valid: folder bin/lib tidak lengkap.", Toast.LENGTH_LONG).show();
            return;
        }
        Container old = null;
        for (Container c : containerManager.getContainers()) {
            if ("Naruto Storm Console".equals(c.getName())) { old = c; break; }
        }
        if (old != null) {
            consoleContainerCreationPending = true;
            refreshConsoleUi();
            containerManager.removeContainerAsync(old, () -> {
                consoleContainerCreationPending = false;
                createConsoleContainer(cm, profile);
            });
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("name", "Naruto Storm Console");
            data.put("wineVersion", wineVersion);
            data.put("screenSize", "960x544");
            data.put("graphicsDriver", "wrapper");
            data.put("dxwrapper", "dxvk");
            data.put("dxwrapperConfig", "version=1.11,framerate=0,async=0,asyncCache=0,ddrawrapper=none,csmt=3,gpuName=NVIDIA GeForce GTX 480,videoMemorySize=2048,strict_shader_math=1,OffscreenRenderingMode=fbo,renderer=gl,dxvkConfig=1");
            data.put("drives", "D:" + Environment.getExternalStorageDirectory().getAbsolutePath());
            data.put("cpuList", "4,5,6,7");
            data.put("envVars", Container.DEFAULT_ENV_VARS + " DXVK_SHADER_COMPILATION_METHOD=dyasync");
            data.put("emulator", Container.DEFAULT_EMULATOR);
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
            consoleContainerCreationPending = true;
            refreshConsoleUi();
            containerManager.createContainerAsync(data, cm, container -> {
                consoleContainerCreationPending = false;
                ProgressBar progress = findViewById(R.id.progressConsoleRuntime);
                if (progress != null) progress.setVisibility(View.GONE);
                if (container == null) {
                    Toast.makeText(this, "Container Naruto Storm Console gagal dibuat.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Naruto Storm Console siap digunakan.", Toast.LENGTH_SHORT).show();
                }
                refreshConsoleUi();
            });
        } catch (JSONException e) {
            consoleContainerCreationPending = false;
            android.util.Log.e("ConsoleLauncher", "Container config failed", e);
            Toast.makeText(this, "Konfigurasi container tidak dapat dibuat.", Toast.LENGTH_LONG).show();
        }
    }

    private String toWindowsDriveDPath(File file) throws IOException {
        String storageRoot = Environment.getExternalStorageDirectory().getCanonicalPath();
        String absolute = file.getCanonicalPath();
        String prefix = storageRoot.endsWith(File.separator) ? storageRoot : storageRoot + File.separator;
        if (!absolute.startsWith(prefix)) {
            throw new IOException("Folder game harus berada di penyimpanan internal utama.");
        }
        return "D:" + absolute.substring(storageRoot.length()).replace(File.separatorChar, '\\');
    }

    private File createConsoleDesktopShortcut(Container container, File exe) throws IOException {
        File desktopDir = container.getDesktopDir();
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) {
            throw new IOException("Folder shortcut resmi container tidak dapat dibuat.");
        }
        String windowsExe = toWindowsDriveDPath(exe);
        String windowsFolder = toWindowsDriveDPath(exe.getParentFile());
        if (windowsExe.indexOf('"') >= 0 || windowsExe.indexOf('\n') >= 0 || windowsExe.indexOf('\r') >= 0) {
            throw new IOException("Path game mengandung karakter yang tidak didukung.");
        }
        String escapedExe = windowsExe.replace("\\", "\\\\");
        String escapedFolder = windowsFolder.replace("\\", "\\\\");
        File desktopFile = new File(desktopDir, "Naruto Storm Console.desktop");
        String shortcutText = "[Desktop Entry]\n" +
                "Name=Naruto Storm Console\n" +
                "Type=Application\n" +
                "Exec=wine \"" + escapedExe + "\"\n" +
                "Path=" + escapedFolder + "\n" +
                "Terminal=false\n" +
                "StartupWMClass=NSUNSR\n" +
                "container_id:" + container.id + "\n";
        try (FileOutputStream out = new FileOutputStream(desktopFile)) {
            out.write(shortcutText.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (!desktopFile.isFile() || desktopFile.length() == 0) {
            throw new IOException("File shortcut .desktop gagal ditulis.");
        }
        return desktopFile;
    }

    private void launchConsoleGame() {
        if (!checkStoragePermissions()) {
            Toast.makeText(this, "Izin akses penyimpanan belum diberikan.", Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) showAllFilesAccessDialog();
            else ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
            return;
        }
        if (consoleGameFile == null || !consoleGameFile.isFile()) {
            consoleGameFile = null;
            refreshConsoleUi();
            Toast.makeText(this, "File EXE belum dipilih. Tekan PILIH GAME (.EXE).", Toast.LENGTH_LONG).show();
            return;
        }
        if (containerManager == null) containerManager = new ContainerManager(this);
        if (!containerManager.isInitialized() || !ImageFs.find(this).isValid()) {
            ensureConsoleContainer();
            Toast.makeText(this, "Runtime sedang disiapkan. Tekan MAIN SEKARANG lagi setelah status siap.", Toast.LENGTH_LONG).show();
            return;
        }

        ContentsManager cm = new ContentsManager(this);
        cm.syncContents();
        Container target = null;
        for (Container candidate : containerManager.getContainers()) {
            if ("Naruto Storm Console".equals(candidate.getName())) {
                target = candidate;
                break;
            }
        }
        if (target == null || !isValidConsoleRuntime(target.getWineVersion(), cm)) {
            ensureConsoleContainer();
            Toast.makeText(this, "Runtime Wine/Proton belum siap. Menyiapkan runtime yang valid.", Toast.LENGTH_LONG).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(CONSOLE_PREFS, MODE_PRIVATE);
        boolean hd = prefs.getBoolean(KEY_RESOLUTION, false);
        boolean dxvk1103 = prefs.getBoolean(KEY_DXVK, false);
        boolean fps60 = prefs.getBoolean(KEY_FPS, false);
        boolean hud = prefs.getBoolean(KEY_HUD, true);
        try {
            target.setGraphicsDriver("wrapper");
            target.setScreenSize(hd ? "1280x720" : "960x544");
            target.setDXWrapper("dxvk");
            target.setCPUList(prefs.getBoolean(KEY_CPU_FULL, false) ?
                    "0,1,2,3,4,5,6,7" : "4,5,6,7");
            target.setDrives("D:" + Environment.getExternalStorageDirectory().getAbsolutePath());
            target.setEnvVars(Container.DEFAULT_ENV_VARS.replace(
                    "DXVK_HUD=devinfo,fps,memory,gpuload,version,api",
                    hud ? "DXVK_HUD=devinfo,fps,memory,gpuload,version,api" : "DXVK_HUD=0") +
                    " DXVK_SHADER_COMPILATION_METHOD=dyasync");
            target.setDXWrapperConfig("version=" + (dxvk1103 ? "1.10.3-async" : "1.11") +
                    ",framerate=0,async=0,asyncCache=0,ddrawrapper=none,csmt=3,gpuName=NVIDIA GeForce GTX 480," +
                    "videoMemorySize=2048,strict_shader_math=1,OffscreenRenderingMode=fbo,renderer=gl,dxvkConfig=1");
            target.saveData();

            writeConsoleConf(new File(consoleGameFile.getParentFile(), "dxvk.conf"), fps60 ? 60 : 30);
            writeConsoleConf(new File(target.getRootDir(), ".wine/dxvk.conf"), fps60 ? 60 : 30);
            updateStormIni(new File(consoleGameFile.getParentFile(), "NSUNSR.ini"), hd);

            File shortcutFile = createConsoleDesktopShortcut(target, consoleGameFile);
            Intent launch = new Intent(this, XServerDisplayActivity.class);
            launch.putExtra("container_id", target.id);
            launch.putExtra("shortcut_path", shortcutFile.getAbsolutePath());
            launch.putExtra("shortcut_name", "Naruto Storm Console");
            launch.putExtra("console_touch_controls", true);
            startActivity(launch);
        } catch (IOException e) {
            Toast.makeText(this, "Game tidak dapat dijalankan: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.util.Log.e("ConsoleLauncher", "Game launch failed", e);
            Toast.makeText(this, "Shortcut game gagal dibuat atau dibuka: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeConsoleConf(File f,int fps){try{File p=f.getParentFile();if(p!=null)p.mkdirs();String s="dxgi.maxFrameRate = "+fps+"\nd3d9.maxFrameRate = "+fps+"\n";try(FileOutputStream o=new FileOutputStream(f)){o.write(s.getBytes(StandardCharsets.UTF_8));}}catch(IOException e){android.util.Log.w("ConsoleLauncher","dxvk.conf write failed",e);}}
    private void updateStormIni(File file, boolean hd) {
        try {
            String text = file.isFile()
                    ? new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    : "";
            text = setIniValue(text, "width", hd ? "1280" : "960");
            text = setIniValue(text, "height", hd ? "720" : "544");
            text = setIniValue(text, "vsync", "0");
            text = setIniValue(text, "shadow", "0");
            text = setIniValue(text, "fxaa", "0");
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Folder game tidak dapat ditulis.");
            }
            java.nio.file.Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            android.util.Log.w("ConsoleLauncher", "NSUNSR.ini patch failed", e);
        }
    }

    private String setIniValue(String text, String key, String value) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?im)^\\s*" +
                java.util.regex.Pattern.quote(key) + "\\s*=.*$");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        String replacement = key + "=" + value;
        if (matcher.find()) return matcher.replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
        return text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + replacement + "\n";
    }

@Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_FILE_REQUEST_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri picked = data.getData();
            try {
                String name = FileUtils.getUriFileName(this, picked);
                if (name == null) name = picked.getLastPathSegment();
                if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) { Toast.makeText(this, "Pilih file .exe", Toast.LENGTH_SHORT).show(); return; }
                String realPath = resolvePickedExePath(picked);
                if (realPath == null) { Toast.makeText(this, "URI ini bukan file lokal yang dapat dipetakan. Pilih EXE dari penyimpanan internal melalui Documents.", Toast.LENGTH_LONG).show(); return; }
                File selectedExe = new File(realPath);
                if (!selectedExe.isFile()) { Toast.makeText(this, "File EXE tidak ditemukan atau izin penyimpanan belum aktif.", Toast.LENGTH_LONG).show(); return; }
                consoleGameFile = selectedExe;
                getSharedPreferences(CONSOLE_PREFS, MODE_PRIVATE).edit().putString(KEY_GAME_PATH, selectedExe.getAbsolutePath()).apply();
                android.util.Log.i("ConsoleLauncher", "User selected EXE: " + selectedExe.getName());
                refreshConsoleUi();
            } catch (Exception e) { Toast.makeText(this, "File EXE tidak dapat dibaca", Toast.LENGTH_LONG).show(); }
            return;
        }

        if (requestCode == OPEN_IMAGE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Bitmap bitmap = ImageUtils.getBitmapFromUri(this, data.getData(), 1280);
            if (bitmap == null) return;
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(this);
            ImageUtils.save(bitmap, userWallpaperFile, Bitmap.CompressFormat.PNG, 100);
        } else if (requestCode == com.winlator.cmod.saves.SaveManagerDialog.REQUEST_CODE_RESTORE_ZIP && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                com.winlator.cmod.saves.SaveManagerDialog dialog = com.winlator.cmod.saves.SaveManagerDialog.getActiveInstance();
                if (dialog != null) {
                    dialog.handlePickedUri(uri);
                }
            }
        }
    }
}
