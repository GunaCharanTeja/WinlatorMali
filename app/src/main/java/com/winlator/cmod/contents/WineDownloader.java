package com.winlator.cmod.contents;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.ThemeManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class WineDownloader {
    private static final String TAG = "WineDownloader";

    public static class CloudWineOption {
        public String title;
        public String runtimeUrl;
        public String targetVersionName;
        public String description;

        public CloudWineOption(String title, String targetVersionName, String runtimeUrl, String description) {
            this.title = title;
            this.targetVersionName = targetVersionName;
            this.runtimeUrl = runtimeUrl;
            this.description = description;
        }
    }

    public static List<String> getInstalledWineVersions(Context context) {
        List<String> installed = new ArrayList<>();
        File optDir = new File(context.getFilesDir(), "imagefs/opt");
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && (f.getName().startsWith("wine-") || f.getName().startsWith("proton-"))) {
                    if (new File(f, "bin").exists() || new File(f, "lib").exists()) {
                        installed.add(f.getName());
                    }
                }
            }
        }

        File wineContents = ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_WINE);
        File[] wineDirs = wineContents.listFiles();
        if (wineDirs != null) {
            for (File dir : wineDirs) {
                if (new File(dir, ContentsManager.PROFILE_NAME).exists() || (new File(dir, "bin").exists() && new File(dir, "lib").exists())) {
                    if (!installed.contains(dir.getName())) installed.add(dir.getName());
                }
            }
        }

        File protonContents = ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_PROTON);
        File[] protonDirs = protonContents.listFiles();
        if (protonDirs != null) {
            for (File dir : protonDirs) {
                if (new File(dir, ContentsManager.PROFILE_NAME).exists() || (new File(dir, "bin").exists() && new File(dir, "lib").exists())) {
                    if (!installed.contains(dir.getName())) installed.add(dir.getName());
                }
            }
        }

        return installed;
    }

    private static AlertDialog sActiveWelcomeDialog = null;
    private static boolean sPromptShownThisSession = false;

    public static void checkFirstLaunchWineSetup(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (sActiveWelcomeDialog != null && sActiveWelcomeDialog.isShowing()) return;
        if (sPromptShownThisSession) return;

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        if (sp.getBoolean("first_wine_prompt_shown", false)) {
            return;
        }

        com.winlator.cmod.xenvironment.ImageFs imageFs = com.winlator.cmod.xenvironment.ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < com.winlator.cmod.xenvironment.ImageFsInstaller.LATEST_VERSION) {
            return;
        }
        List<String> installed = getInstalledWineVersions(activity);
        if (!installed.isEmpty()) {
            sp.edit().putBoolean("first_wine_prompt_shown", true).apply();
            return;
        }

        sPromptShownThisSession = true;
        sp.edit().putBoolean("first_wine_prompt_shown", true).apply();

        boolean isModern = android.os.Build.VERSION.SDK_INT >= 34;
        String defaultTitle = "Proton 10.0-4 arm64ec (" + (isModern ? "SDK 35" : "SDK 28") + ")";

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("Welcome to Winlator Mali")
            .setMessage("No Wine or Proton runtimes are installed yet.\n\nTo run Windows games with highest performance, download the official Proton 10 runtime:")
            .setPositiveButton("Download " + defaultTitle, (dialog, which) -> {
                sActiveWelcomeDialog = null;
                showDownloadDialog(activity, null);
            })
            .setNeutralButton("Choose from Sources", (dialog, which) -> {
                sActiveWelcomeDialog = null;
                showDownloadDialog(activity, null);
            })
            .setNegativeButton("Later", (dialog, which) -> {
                sActiveWelcomeDialog = null;
            })
            .setOnDismissListener(dialog -> {
                sActiveWelcomeDialog = null;
            });

        sActiveWelcomeDialog = builder.create();
        sActiveWelcomeDialog.show();
    }

    public static void showDownloadDialog(Activity activity, Runnable onInstalledCallback) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean isDarkMode = ThemeManager.isDarkMode(activity);

        ContentDialog dialog = new ContentDialog(activity, R.layout.wine_downloader_dialog);
        dialog.setTitle("Wine & Proton Downloader");
        dialog.setIcon(R.drawable.icon_wine);

        View contentView = dialog.getContentView();
        Spinner sCatalogSource = contentView.findViewById(R.id.SCatalogSource);
        ImageButton btRefreshSources = contentView.findViewById(R.id.BTRefreshSources);
        ProgressBar pbLoading = contentView.findViewById(R.id.PBSourcesLoading);
        TextView tvEmptyList = contentView.findViewById(R.id.TVEmptyList);
        ListView lvWineList = contentView.findViewById(R.id.LVWineList);

        LinearLayout llProgress = contentView.findViewById(R.id.LLDownloadProgressPanel);
        TextView tvProgressTitle = contentView.findViewById(R.id.TVProgressTitle);
        ProgressBar pbDownload = contentView.findViewById(R.id.PBDownload);
        TextView tvProgressStatus = contentView.findViewById(R.id.TVProgressStatus);
        TextView tvProgressPercent = contentView.findViewById(R.id.TVProgressPercent);

        // Catalog sources in Container Wine Downloader
        String[] catalogs = {
            "⭐ Winlator Mali (Official)",
            "☁️ Bannerlator Nightlies",
            "☁️ WinNative Components",
            "☁️ StevenMXZ Contents",
            "🌐 Custom Catalog URL...",
            "📁 Installed on Device"
        };

        ArrayAdapter<String> catalogAdapter = new ArrayAdapter<String>(activity, R.layout.custom_spinner_item, catalogs) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
                }
                return v;
            }
        };
        catalogAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        sCatalogSource.setAdapter(catalogAdapter);
        sCatalogSource.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

        final String[] activeCatalogUrl = new String[]{""};

        Runnable[] loaderHolder = new Runnable[1];

        loaderHolder[0] = () -> {
            int pos = sCatalogSource.getSelectedItemPosition();
            if (pos == 5) {
                // Installed on device
                pbLoading.setVisibility(View.GONE);
                List<String> installed = getInstalledWineVersions(activity);
                if (installed.isEmpty()) {
                    tvEmptyList.setText("No Wine or Proton runtimes installed.");
                    tvEmptyList.setVisibility(View.VISIBLE);
                    lvWineList.setVisibility(View.GONE);
                } else {
                    tvEmptyList.setVisibility(View.GONE);
                    lvWineList.setVisibility(View.VISIBLE);
                    InstalledWineAdapter installedAdapter = new InstalledWineAdapter(activity, installed, (verName) -> {
                        new AlertDialog.Builder(activity)
                            .setTitle("Delete " + verName + "?")
                            .setMessage("Are you sure you want to remove this runtime from Winlator Mali?")
                            .setPositiveButton("Delete", (d, w) -> {
                                deleteWineVersion(activity, verName);
                                AppUtils.showToast(activity, verName + " removed.");
                                loaderHolder[0].run();
                                if (onInstalledCallback != null) onInstalledCallback.run();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    });
                    lvWineList.setAdapter(installedAdapter);
                }
            } else if (pos == 0) {
                // Official Winlator Mali Source
                pbLoading.setVisibility(View.VISIBLE);
                tvEmptyList.setVisibility(View.GONE);
                lvWineList.setVisibility(View.GONE);

                Executors.newSingleThreadExecutor().execute(() -> {
                    List<CloudWineOption> wineItems = fetchWinlatorMaliWineList();
                    activity.runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        if (wineItems.isEmpty()) {
                            tvEmptyList.setText("No Winlator Mali runtimes found.");
                            tvEmptyList.setVisibility(View.VISIBLE);
                            lvWineList.setVisibility(View.GONE);
                        } else {
                            tvEmptyList.setVisibility(View.GONE);
                            lvWineList.setVisibility(View.VISIBLE);
                            List<String> currentInstalled = getInstalledWineVersions(activity);
                            WinePackageAdapter adapter = new WinePackageAdapter(activity, wineItems, currentInstalled, (option) -> {
                                startDownload(activity, option.title, option.targetVersionName, option.runtimeUrl, dialog, llProgress, tvProgressTitle, pbDownload, tvProgressStatus, tvProgressPercent, onInstalledCallback);
                            });
                            lvWineList.setAdapter(adapter);
                        }
                    });
                });
            } else {
                // Online Content Source
                pbLoading.setVisibility(View.VISIBLE);
                tvEmptyList.setVisibility(View.GONE);
                lvWineList.setVisibility(View.GONE);

                String fetchUrl = activeCatalogUrl[0];
                Executors.newSingleThreadExecutor().execute(() -> {
                    List<CloudWineOption> wineItems = fetchRemoteWineList(fetchUrl);
                    activity.runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        if (wineItems.isEmpty()) {
                            tvEmptyList.setText("No Wine or Proton packages found in this source.");
                            tvEmptyList.setVisibility(View.VISIBLE);
                            lvWineList.setVisibility(View.GONE);
                        } else {
                            tvEmptyList.setVisibility(View.GONE);
                            lvWineList.setVisibility(View.VISIBLE);
                            List<String> currentInstalled = getInstalledWineVersions(activity);
                            WinePackageAdapter adapter = new WinePackageAdapter(activity, wineItems, currentInstalled, (option) -> {
                                startDownload(activity, option.title, option.targetVersionName, option.runtimeUrl, dialog, llProgress, tvProgressTitle, pbDownload, tvProgressStatus, tvProgressPercent, onInstalledCallback);
                            });
                            lvWineList.setAdapter(adapter);
                        }
                    });
                });
            }
        };

        sCatalogSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    loaderHolder[0].run();
                } else if (position == 1) {
                    activeCatalogUrl[0] = ContentsManager.BANNERLATOR_PROFILES;
                    loaderHolder[0].run();
                } else if (position == 2) {
                    activeCatalogUrl[0] = ContentsManager.WINNATIVE_PROFILES;
                    loaderHolder[0].run();
                } else if (position == 3) {
                    activeCatalogUrl[0] = ContentsManager.STEVENMXZ_PROFILES;
                    loaderHolder[0].run();
                } else if (position == 4) {
                    // Custom Catalog URL prompt
                    String saved = sp.getString("downloadable_contents_url", ContentsManager.BANNERLATOR_PROFILES);
                    final EditText input = new EditText(activity);
                    input.setHint("https://example.com/contents.json");
                    input.setText(saved);
                    new AlertDialog.Builder(activity)
                        .setTitle("Custom Catalog URL")
                        .setMessage("Enter the URL to a contents.json repository:")
                        .setView(input)
                        .setPositiveButton("Load", (d, w) -> {
                            String custom = input.getText().toString().trim();
                            if (!custom.isEmpty()) {
                                sp.edit().putString("downloadable_contents_url", custom).apply();
                                activeCatalogUrl[0] = custom;
                                loaderHolder[0].run();
                            }
                        })
                        .setNegativeButton("Cancel", (d, w) -> {
                            sCatalogSource.setSelection(0);
                        })
                        .show();
                } else if (position == 5) {
                    loaderHolder[0].run();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btRefreshSources.setOnClickListener(v -> loaderHolder[0].run());

        dialog.show();
    }

    private static void startDownload(Activity activity, String title, String versionName, String urlStr, ContentDialog dialog,
                                      LinearLayout llProgress, TextView tvProgressTitle, ProgressBar pbDownload,
                                      TextView tvProgressStatus, TextView tvProgressPercent, Runnable onInstalledCallback) {

        llProgress.setVisibility(View.VISIBLE);
        tvProgressTitle.setText("Downloading " + title);
        pbDownload.setIndeterminate(false);
        pbDownload.setProgress(0);
        tvProgressStatus.setText("Connecting to repository...");
        tvProgressPercent.setText("0%");

        Executors.newSingleThreadExecutor().execute(() -> {
            File wcpFile = null;
            try {
                File cacheDir = activity.getCacheDir();
                String ext = urlStr.endsWith(".tzst") ? ".tzst" : (urlStr.endsWith(".tar.xz") ? ".tar.xz" : ".wcp");
                wcpFile = new File(cacheDir, versionName + "_" + System.currentTimeMillis() + ext);

                File finalWcpFile = wcpFile;
                downloadFileWithDetailedProgress(urlStr, wcpFile, (progress, readBytes, totalBytes) -> {
                    activity.runOnUiThread(() -> {
                        pbDownload.setProgress(progress);
                        tvProgressPercent.setText(progress + "%");
                        String statusStr = String.format(Locale.US, "%.1f MB / %.1f MB", (readBytes / (1024.0 * 1024.0)), (totalBytes / (1024.0 * 1024.0)));
                        tvProgressStatus.setText("Downloading: " + statusStr);
                    });
                });

                activity.runOnUiThread(() -> {
                    pbDownload.setIndeterminate(true);
                    tvProgressTitle.setText("Installing " + title);
                    tvProgressStatus.setText("Extracting & registering runtime packages...");
                });

                ContentsManager contentsManager = new ContentsManager(activity);
                contentsManager.extraContentFile(Uri.fromFile(wcpFile), new ContentsManager.OnInstallFinishedCallback() {
                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        FileUtils.delete(finalWcpFile);
                        activity.runOnUiThread(() -> {
                            llProgress.setVisibility(View.GONE);
                            AppUtils.showToast(activity, "Installation failed: " + reason);
                        });
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        contentsManager.finishInstallContent(profile, new ContentsManager.OnInstallFinishedCallback() {
                            @Override
                            public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                                FileUtils.delete(finalWcpFile);
                                activity.runOnUiThread(() -> {
                                    llProgress.setVisibility(View.GONE);
                                    AppUtils.showToast(activity, "Installation failed: " + reason);
                                });
                            }

                            @Override
                            public void onSucceed(ContentProfile profile) {
                                FileUtils.delete(finalWcpFile);
                                contentsManager.syncContents();
                                activity.runOnUiThread(() -> {
                                    llProgress.setVisibility(View.GONE);
                                    AppUtils.showToast(activity, title + " installed successfully!");
                                    if (onInstalledCallback != null) onInstalledCallback.run();
                                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                                });
                            }
                        });
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                if (wcpFile != null) FileUtils.delete(wcpFile);
                activity.runOnUiThread(() -> {
                    llProgress.setVisibility(View.GONE);
                    AppUtils.showToast(activity, "Download failed: " + e.getMessage());
                });
            }
        });
    }

    private static List<CloudWineOption> fetchWinlatorMaliWineList() {
        List<CloudWineOption> list = new ArrayList<>();
        try {
            URL url = new URL("https://api.github.com/repos/GunaCharanTeja/Winlator-Extras/releases");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 WinlatorMali");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONArray releases = new JSONArray(sb.toString());
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject rel = releases.getJSONObject(i);
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets == null) continue;

                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.getJSONObject(j);
                        String assetName = asset.optString("name", "");
                        String downloadUrl = asset.optString("browser_download_url", "");
                        if (downloadUrl.isEmpty()) continue;

                        String lower = assetName.toLowerCase(Locale.US);
                        // Exclude non-wine packages
                        if (lower.contains("container_pattern") || lower.contains("imagefs") ||
                            lower.endsWith(".exe") || lower.endsWith(".msi") || lower.endsWith(".reg") ||
                            lower.contains("dll.7z") || lower.contains("fonts.7z")) {
                            continue;
                        }

                        if ((lower.contains("proton") || lower.contains("wine")) &&
                            (lower.endsWith(".tzst") || lower.endsWith(".txz") || lower.endsWith(".wcp") || lower.endsWith(".tar.xz"))) {

                            String verName = assetName.replace(".tzst", "").replace(".txz", "").replace(".wcp", "").replace(".tar.xz", "");
                            String title = verName.replace("-", " ");
                            if (title.startsWith("proton")) {
                                title = "Proton " + title.substring(6).trim();
                            } else if (title.startsWith("wine")) {
                                title = "Wine " + title.substring(4).trim();
                            }
                            title += " (Winlator Mali)";

                            String desc = "Official Winlator Mali " + verName + " runtime";
                            list.add(new CloudWineOption(title, verName, downloadUrl, desc));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "GitHub API fetch failed, using fallback list: " + e.getMessage());
        }

        // Fallback curated official Winlator Mali runtimes
        if (list.isEmpty()) {
            list.add(new CloudWineOption(
                "Proton 10.0 Arm64ec (Winlator Mali)",
                "proton-10-arm64ec",
                "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/proton-10-arm64ec.tzst",
                "Official Winlator Mali Proton 10 ARM64EC High Performance Runtime"
            ));
            list.add(new CloudWineOption(
                "Proton 9.0 Arm64ec (Winlator Mali)",
                "proton-9.0-arm64ec",
                "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/proton-9.0-arm64ec.tzst",
                "Official Winlator Mali Proton 9.0 ARM64EC Runtime"
            ));
            list.add(new CloudWineOption(
                "Proton 9.0 x86_64 (Winlator Mali)",
                "proton-9.0-x86_64",
                "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/proton-9.0-x86_64.tzst",
                "Official Winlator Mali Proton 9.0 64-bit Runtime"
            ));
            list.add(new CloudWineOption(
                "Proton 9.0 Arm64ec (TXZ) (Winlator Mali)",
                "proton-9.0-arm64ec",
                "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/proton-9-arm64ec/proton-9.0-arm64ec.txz",
                "Official Winlator Mali Proton 9.0 TXZ Package"
            ));
        }

        return list;
    }

    private static List<CloudWineOption> fetchRemoteWineList(String repoUrl) {
        List<CloudWineOption> list = new ArrayList<>();
        try {
            URL url = new URL(repoUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 WinlatorMali");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONArray array = new JSONArray(sb.toString());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String remoteUrl = obj.optString("remoteUrl", obj.optString("url", ""));
                    if (remoteUrl.isEmpty()) continue;

                    String type = obj.optString("type", "").toLowerCase(Locale.US);
                    String verName = obj.optString("verName", obj.optString("versionName", obj.optString("name", "")));
                    String desc = obj.optString("description", obj.optString("desc", ""));

                    String lowerName = verName.toLowerCase(Locale.US);
                    String lowerType = type.toLowerCase(Locale.US);
                    String lowerUrl = remoteUrl.toLowerCase(Locale.US);

                    // STRICT FILTER: Exclude graphics wrappers, drivers, utilities that contain "proton" in their name (e.g. vkd3d-proton)
                    if (lowerName.contains("vkd3d") || lowerName.contains("dxvk") || lowerName.contains("d8vk") ||
                        lowerName.contains("d9vk") || lowerName.contains("d7vk") || lowerName.contains("box64") ||
                        lowerName.contains("wowbox64") || lowerName.contains("turnip") || lowerName.contains("mesa") ||
                        lowerName.contains("adreno") || lowerName.contains("driver") || lowerName.contains("soundfont") ||
                        lowerName.contains("fex") || lowerType.contains("vkd3d") || lowerType.contains("dxvk") ||
                        lowerType.contains("driver") || lowerType.contains("box64") || lowerType.contains("fex")) {
                        continue;
                    }

                    // Must genuinely be Wine or Proton runtime
                    boolean isWineOrProton = lowerType.equals("wine") || lowerType.equals("proton") ||
                                            lowerName.startsWith("proton") || lowerName.startsWith("wine") ||
                                            lowerName.startsWith("ge-proton") || lowerUrl.contains("/proton-") ||
                                            lowerUrl.contains("/wine-");

                    if (isWineOrProton) {
                        String cleanTitle = verName;
                        String cleanDesc = desc.isEmpty() ? (lowerName.contains("proton") ? "Proton Compatibility Runtime" : "Wine Runtime Package") : desc;
                        list.add(new CloudWineOption(cleanTitle, verName, remoteUrl, cleanDesc));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch remote repository", e);
        }

        return list;
    }

    private static void deleteWineVersion(Context context, String verName) {
        File optDir = new File(context.getFilesDir(), "imagefs/opt/" + verName);
        if (optDir.exists()) FileUtils.delete(optDir);

        File wineDir = new File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_WINE), verName);
        if (wineDir.exists()) FileUtils.delete(wineDir);

        File protonDir = new File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_PROTON), verName);
        if (protonDir.exists()) FileUtils.delete(protonDir);
    }

    private interface DetailedProgressCallback {
        void onProgress(int percent, long readBytes, long totalBytes);
    }

    private static void downloadFileWithDetailedProgress(String fileUrl, File destination, DetailedProgressCallback callback) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 WinlatorMali");
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(60000);

        // Follow redirects
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 WinlatorMali");
        }

        long contentLength = conn.getContentLengthLong();
        try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int read;
            long totalRead = 0;
            long lastNotify = 0;

            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                totalRead += read;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 100 || totalRead == contentLength) {
                    lastNotify = now;
                    int percent = contentLength > 0 ? (int) ((totalRead * 100) / contentLength) : 0;
                    if (callback != null) callback.onProgress(percent, totalRead, contentLength);
                }
            }
        }
    }

    // Wine Packages Adapter
    private static class WinePackageAdapter extends ArrayAdapter<CloudWineOption> {
        private final List<String> installedVersions;
        private final CuratedCallback onAction;

        public interface CuratedCallback {
            void call(CloudWineOption item);
        }

        public WinePackageAdapter(Context context, List<CloudWineOption> items, List<String> installedVersions, CuratedCallback onAction) {
            super(context, 0, items);
            this.installedVersions = installedVersions;
            this.onAction = onAction;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.wine_downloader_list_item, parent, false);
            }

            CloudWineOption item = getItem(position);
            if (item == null) return convertView;

            TextView tvTitle = convertView.findViewById(R.id.TVWineItemTitle);
            TextView tvSub = convertView.findViewById(R.id.TVWineItemSubtitle);
            Button btAction = convertView.findViewById(R.id.BTWineItemAction);
            ImageButton btDelete = convertView.findViewById(R.id.BTWineItemDelete);
            ImageView ivIcon = convertView.findViewById(R.id.IVWineItemIcon);

            tvTitle.setText(item.title);
            tvSub.setText(item.description);

            boolean isInstalled = false;
            for (String inst : installedVersions) {
                if (inst.equalsIgnoreCase(item.targetVersionName) || inst.contains(item.targetVersionName) || item.targetVersionName.contains(inst)) {
                    isInstalled = true;
                    break;
                }
            }

            if (isInstalled) {
                btAction.setText("Installed");
                btAction.setEnabled(false);
                btAction.setAlpha(0.5f);
            } else {
                btAction.setText("Get");
                btAction.setEnabled(true);
                btAction.setAlpha(1.0f);
                btAction.setOnClickListener(v -> {
                    if (onAction != null) onAction.call(item);
                });
            }

            btDelete.setVisibility(View.GONE);
            return convertView;
        }
    }

    // Installed List Adapter
    private static class InstalledWineAdapter extends ArrayAdapter<String> {
        private final InstalledCallback onDelete;

        public interface InstalledCallback {
            void call(String item);
        }

        public InstalledWineAdapter(Context context, List<String> items, InstalledCallback onDelete) {
            super(context, 0, items);
            this.onDelete = onDelete;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.wine_downloader_list_item, parent, false);
            }

            String verName = getItem(position);
            if (verName == null) return convertView;

            TextView tvTitle = convertView.findViewById(R.id.TVWineItemTitle);
            TextView tvSub = convertView.findViewById(R.id.TVWineItemSubtitle);
            Button btAction = convertView.findViewById(R.id.BTWineItemAction);
            ImageButton btDelete = convertView.findViewById(R.id.BTWineItemDelete);

            tvTitle.setText(verName);
            tvSub.setText("Active runtime in imagefs/opt or contents folder");

            btAction.setVisibility(View.GONE);
            btDelete.setVisibility(View.VISIBLE);
            btDelete.setOnClickListener(v -> {
                if (onDelete != null) onDelete.call(verName);
            });

            return convertView;
        }
    }
}
