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
import android.widget.TextView;

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

        isDarkMode = true;
        setTheme(R.style.AppTheme_Dark);
        ThemeManager.applyTheme(this);

        setContentView(R.layout.main_activity);

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

        containerManager = new ContainerManager(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            setActionBarHomeIcon(R.drawable.icon_action_bar_back);
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.main_menu_input_controls));
            navigationView.setCheckedItem(R.id.main_menu_input_controls);
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : R.id.main_menu_containers;

            setActionBarHomeIcon(R.drawable.icon_action_bar_menu);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);

            if (checkStoragePermissions()) {
                if (!ImageFsInstaller.installIfNeeded(this)) {
                    com.winlator.cmod.contents.WineDownloader.checkFirstLaunchWineSetup(this);
                }
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
                        if (!ImageFsInstaller.installIfNeeded(this)) {
                            com.winlator.cmod.contents.WineDownloader.checkFirstLaunchWineSetup(this);
                        }
                    })
                    .show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (!ImageFsInstaller.installIfNeeded(this)) {
                    com.winlator.cmod.contents.WineDownloader.checkFirstLaunchWineSetup(this);
                }
            } else {
                AppUtils.showToast(this, "Storage permission is recommended for container drives.");
                if (!ImageFsInstaller.installIfNeeded(this)) {
                    com.winlator.cmod.contents.WineDownloader.checkFirstLaunchWineSetup(this);
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkStoragePermissions()) {
            if (!ImageFsInstaller.installIfNeeded(this)) {
                com.winlator.cmod.contents.WineDownloader.checkFirstLaunchWineSetup(this);
            }
        }
        NavigationView navigationView = findViewById(R.id.NavigationView);
        if (navigationView != null) {
            boolean forceShowAdreno = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("show_adrenotools_unsupported", false);
            MenuItem item = navigationView.getMenu().findItem(R.id.main_menu_adrenotools_gpu_drivers);
            if (item != null) {
                item.setVisible(forceShowAdreno || com.winlator.cmod.core.GPUInformation.isAdrenoGPU(this));
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

            ((TextView) dialog.findViewById(R.id.TVAppVersion)).setText(getString(R.string.version) + " " + pInfo.versionName);

            String creditsAndThirdPartyAppsHTML = String.join("<br />",
                    "<b>Winlator Mali</b> by Fcharan",
                    "Fork of <b>Winlator Bionic</b> by <a href=\"https://github.com/Pipetto-crypto\">Pipetto Cripto</a>",
                    "Controller fixes by <a href=\"https://github.com/Vivsi1\">vivsi1</a>",
                    "Winlator HUD & File Manager from <b>Winlator Ludashi</b> by <a href=\"https://github.com/StevenMXZ\">stevenmxz</a>",
                    "Theme system & CI contributions by <a href=\"https://github.com/Noysz\">Noysz</a>",
                    "Original idea for Community Configs by <a href=\"https://github.com/The412Banner\">The412Banner</a>",
                    "<b><a href=\"https://github.com/leegao\">leegao</a></b> (wrapper-leegao)",
                    "ASTC & ETC2 Transcode",
                    "---",
                    "Wine (<a href=\"https://www.winehq.org\">winehq.org</a>)",
                    "Box64 (<a href=\"https://github.com/ptitSeb/box64\">github.com/ptitSeb/box64</a>)",
                    "Mesa (Turnip/Zink/Wrapper) (<a href=\"https://github.com/xMeM/mesa/tree/wrapper\">github.com/xMeM/mesa</a>)",
                    "DXVK (<a href=\"https://github.com/doitsujin/dxvk\">github.com/doitsujin/dxvk</a>)",
                    "VKD3D (<a href=\"https://gitlab.winehq.org/wine/vkd3d\">gitlab.winehq.org/wine/vkd3d</a>)",
                    "D8VK (<a href=\"https://github.com/AlpyneDreams/d8vk\">github.com/AlpyneDreams/d8vk</a>)",
                    "CNC DDraw (<a href=\"https://github.com/FunkyFr3sh/cnc-ddraw\">github.com/FunkyFr3sh/cnc-ddraw</a>)",
                    "dxwrapper (<a href=\"https://github.com/elishacloud/dxwrapper\">github.com/elishacloud/dxwrapper</a>)",
                    "FEX-Emu (<a href=\"https://github.com/FEX-Emu/FEX\">github.com/FEX-Emu/FEX</a>)",
                    "libadrenotools (<a href=\"https://github.com/bylaws/libadrenotools\">github.com/bylaws/libadrenotools</a>)"
            );

            TextView tvCreditsAndThirdPartyApps = dialog.findViewById(R.id.TVCreditsAndThirdPartyApps);
            tvCreditsAndThirdPartyApps.setText(Html.fromHtml(creditsAndThirdPartyAppsHTML, Html.FROM_HTML_MODE_LEGACY));
            tvCreditsAndThirdPartyApps.setMovementMethod(LinkMovementMethod.getInstance());

            String glibcExpVersionForkHTML = String.join("<br />",
                    "longjunyu2's <a href=\"https://github.com/longjunyu2/winlator/tree/use-glibc-instead-of-proot\">(GLIBC Fork)</a>");
            TextView tvGlibcExpVersionFork = dialog.findViewById(R.id.TVGlibcExpVersionFork);
            tvGlibcExpVersionFork.setText(Html.fromHtml(glibcExpVersionForkHTML, Html.FROM_HTML_MODE_LEGACY));
            tvGlibcExpVersionFork.setMovementMethod(LinkMovementMethod.getInstance());
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
        int[] textColors = new int[]{
            accentColor,
            accentColor,
            onSurfaceColor
        };
        android.content.res.ColorStateList textStateList = new android.content.res.ColorStateList(states, textColors);
        navigationView.setItemTextColor(textStateList);
        navigationView.setItemIconTintList(textStateList);

        // Dynamic theme accent pill highlight
        int pillAlpha = 0x2A; // ~16.5% alpha
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

        android.graphics.drawable.RippleDrawable rippleDrawable = new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleColor),
            stateListDrawable,
            null
        );

        navigationView.setItemBackground(rippleDrawable);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    public ContainerManager getContainerManager() {
        return containerManager;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
