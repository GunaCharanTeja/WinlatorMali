package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ContentInfoDialog;
import com.winlator.cmod.contentdialog.ContentUntrustedDialog;
import com.winlator.cmod.contents.ContentDownloads;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ContentsFragment extends Fragment {
    private RecyclerView recyclerView;
    private View emptyText;
    private ContentsManager manager;
    private SharedPreferences sp;
    private ContentProfile.ContentType currentContentType = ContentProfile.ContentType.CONTENT_TYPE_WINE;
    private Spinner sContentType;
    private Spinner sCatalogSource;
    private Spinner sSortBy;
    private ProgressBar pbLoading;
    private boolean isDarkMode;

    private int currentSortOrder = 0;
    private String currentCatalogUrl = ContentsManager.BANNERLATOR_PROFILES;
    private boolean isInstalledFilter = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        manager = new ContentsManager(getContext());
        sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        isDarkMode = sp.getBoolean("dark_mode", true);
    }

    @Override
    public void onDestroy() {
        if (getContext() != null) {
            FileUtils.clear(getContext().getCacheDir());
        }
        super.onDestroy();
    }

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (recyclerView == null) return;
            if (recyclerView.getAdapter() instanceof ContentItemAdapter)
                ((ContentItemAdapter) recyclerView.getAdapter()).refreshDownloadStates();
            recyclerView.postDelayed(this, 300);
        }
    };

    private final ContentDownloads.Listener downloadListener = task -> {
        if (getContext() == null) return;
        AppUtils.showToast(getContext(), task.message);
        if (task.state == ContentDownloads.State.DONE) {
            // The installed copy replaces the catalog entry in the list; keep the user's place in it.
            seenInstalledCount = ContentDownloads.installedCount;
            manager.syncContents();
            loadContentList(true);
        }
    };

    private int seenInstalledCount = ContentDownloads.installedCount;

    @Override
    public void onResume() {
        super.onResume();
        ContentDownloads.addListener(downloadListener);
        if (seenInstalledCount != ContentDownloads.installedCount) {
            seenInstalledCount = ContentDownloads.installedCount;
            manager.syncContents();
            loadContentList(true);
        }
        if (recyclerView != null) recyclerView.post(progressUpdater);
    }

    @Override
    public void onPause() {
        ContentDownloads.removeListener(downloadListener);
        if (recyclerView != null) recyclerView.removeCallbacks(progressUpdater);
        super.onPause();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ThemeManager.applyThemeToView(view, getContext());
        // The screen's main action as a filled accent button (the theme pass makes every button an outline), with text
        // dark or light by the accent's brightness so it reads on every preset.
        android.widget.Button btInstall = view.findViewById(R.id.BTInstallContent);
        if (btInstall != null && getContext() != null) {
            int accent = ThemeManager.getAccentColor(getContext());
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(getResources().getDisplayMetrics().density * 12);
            bg.setColor(accent);
            btInstall.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), bg, null));
            btInstall.setTextColor(androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.5 ? 0xFF101010 : Color.WHITE);
            int padH = (int) (getResources().getDisplayMetrics().density * 22);
            btInstall.setPadding(padH, btInstall.getPaddingTop(), padH, btInstall.getPaddingBottom());
        }
        if (getActivity() != null && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.contents);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(R.layout.contents_fragment, container, false);
        pbLoading = layout.findViewById(R.id.PBLoading);

        sContentType = layout.findViewById(R.id.SContentType);
        updateContentTypeSpinner(sContentType);
        sContentType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentContentType = ContentProfile.ContentType.values()[position];
                loadContentList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sCatalogSource = layout.findViewById(R.id.SCatalogSource);
        if (sCatalogSource != null) {
            String[] catalogs = {
                "📁 Installed on Device",
                "☁️ Bannerlator Nightlies",
                "☁️ WinNative Components",
                "☁️ StevenMXZ Contents",
                "🌐 Custom Catalog URL..."
            };
            ArrayAdapter<String> catalogAdapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_dropdown_item, catalogs) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    View v = super.getView(position, convertView, parent);
                    if (v instanceof TextView) {
                        TextView tv = (TextView) v;
                        tv.setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
                        // The closed picker shows the short name ("Bannerlator", not "☁️ Bannerlator Nightlies"), which fits.
                        String full = getItem(position);
                        String name = full != null ? full.replaceFirst("^\\S+\\s+", "") : "";
                        int space = name.indexOf(' ');
                        tv.setText(position < catalogs.length - 1 && space > 0 ? name.substring(0, space) : name);
                        tv.setSingleLine(true);
                        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    }
                    return v;
                }

                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    View v = super.getDropDownView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
                        v.setBackgroundColor(ThemeManager.getSurfaceColor(getContext()));
                    }
                    return v;
                }
            };
            sCatalogSource.setAdapter(catalogAdapter);
            sCatalogSource.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
            
            // Default to Bannerlator Cloud (position 1)
            sCatalogSource.setSelection(1, false);

            sCatalogSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position == 0) {
                        isInstalledFilter = true;
                        loadContentList();
                    } else if (position == 1) {
                        isInstalledFilter = false;
                        currentCatalogUrl = ContentsManager.BANNERLATOR_PROFILES;
                        fetchCatalog();
                    } else if (position == 2) {
                        isInstalledFilter = false;
                        currentCatalogUrl = ContentsManager.WINNATIVE_PROFILES;
                        fetchCatalog();
                    } else if (position == 3) {
                        isInstalledFilter = false;
                        currentCatalogUrl = ContentsManager.STEVENMXZ_PROFILES;
                        fetchCatalog();
                    } else if (position == 4) {
                        isInstalledFilter = false;
                        promptCustomCatalogUrl();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        View btRefresh = layout.findViewById(R.id.BTRefresh);
        if (btRefresh != null) {
            btRefresh.setOnClickListener(v -> {
                if (getContext() != null) {
                    AppUtils.showToast(getContext(), "Refreshing...");
                }
                if (isInstalledFilter) {
                    manager.syncContents();
                    loadContentList();
                } else {
                    fetchCatalog();
                }
            });
        }

        sSortBy = layout.findViewById(R.id.SSortBy);
        if (sSortBy != null) {
            String[] sortOptions = {
                "Sort: Latest Releases (Newest)",
                "Sort: Name (A-Z)",
                "Sort: Name (Z-A)",
                "Sort: Size (Largest)",
                "Sort: Size (Smallest)",
                "Sort: Build / Version"
            };
            ArrayAdapter<String> sortAdapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_dropdown_item, sortOptions) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    View v = super.getView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
                    }
                    return v;
                }

                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    View v = super.getDropDownView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
                        v.setBackgroundColor(ThemeManager.getSurfaceColor(getContext()));
                    }
                    return v;
                }
            };
            sSortBy.setAdapter(sortAdapter);
            sSortBy.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
            sSortBy.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    currentSortOrder = position;
                    loadContentList();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        emptyText = layout.findViewById(R.id.TVEmptyText);

        View btInstallContent = layout.findViewById(R.id.BTInstallContent);
        btInstallContent.setOnClickListener(v -> {
            ContentDialog.confirm(getContext(), getString(R.string.do_you_want_to_install_content) + " " + getString(R.string.pls_make_sure_content_trustworthy) + " "
                    + getString(R.string.content_suffix_is_wcp_packed_xz_zst) + '\n' + getString(R.string.get_more_contents_form_github), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (getActivity() != null) {
                    getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
                }
            });
        });

        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        
        // Initial fetch
        fetchCatalog();

        return layout;
    }

    private void fetchCatalog() {
        if (currentCatalogUrl == null || currentCatalogUrl.isEmpty()) return;
        new Thread(() -> {
            String json = Downloader.downloadString(currentCatalogUrl);
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (json != null && !json.isEmpty()) {
                        manager.setRemoteProfiles(json);
                    }
                    loadContentList();
                });
            }
        }).start();
    }

    private void promptCustomCatalogUrl() {
        if (getContext() == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String savedCustomUrl = prefs.getString("custom_catalog_url", "https://");

        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setText(savedCustomUrl);
        input.setSingleLine(true);
        input.setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
        input.setHint("https://.../contents.json");

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext(), isDarkMode ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        builder.setTitle("Custom Catalog Source URL");
        builder.setMessage("Enter the direct URL to a valid contents.json catalog:");
        builder.setView(input);

        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty() && url.startsWith("http")) {
                prefs.edit().putString("custom_catalog_url", url).apply();
                currentCatalogUrl = url;
                fetchCatalog();
            } else {
                AppUtils.showToast(getContext(), "Invalid URL");
            }
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private static final boolean HAS_NTSYNC = new java.io.File("/dev/ntsync").exists();

    // What a Wine/Proton build needs on this phone, from its name: arm64ec runs x86 code with FEXCore
    // (WOWBox64 optional for 32-bit), x86_64 runs under Box64. NTSync builds need /dev/ntsync in the kernel.
    private static String requirementBadge(ContentProfile profile) {
        String name = (profile.verName != null ? profile.verName : "").toLowerCase();
        switch (profile.type) {
            case CONTENT_TYPE_DXVK:
            case CONTENT_TYPE_VKD3D:
                // Both work in arm64ec Wine; the arm64ec build runs natively there, the x86 one is emulated.
                return name.contains("arm64ec") ? "arm64ec build: native on arm64ec Wine" : "x86 build: any Wine";
            case CONTENT_TYPE_FEXCORE:
                return name.contains("unix") ? "unixlib build (DLLs + native .so)" : "DLL build";
            case CONTENT_TYPE_WOWBOX64:
                return "arm64ec Wine: 32-bit programs (optional)";
            case CONTENT_TYPE_BOX64:
                return "x86_64 Wine";
            case CONTENT_TYPE_D7VK:
                return "DirectDraw/D3D7 games: pick it in DXVK settings → DDraw Wrapper";
            case CONTENT_TYPE_WINE:
            case CONTENT_TYPE_PROTON:
                break;
            default:
                return "";
        }
        // arm64ec builds always say so in the name; the rest (wine-9.22_ref, ...) are x86_64 builds.
        String badge = name.contains("arm64ec") ? "arm64ec: FEXCore" : "x86_64: Box64";
        if (name.contains("ntsync") && !HAS_NTSYNC) badge += (badge.isEmpty() ? "" : " • ") + "NTSync not on this phone (runs without it)";
        return badge;
    }

    // The architecture a build is for, shown as a chip: Wine/Proton arm64ec or x86_64, DXVK/VKD3D arm64ec or x86.
    private static String archChip(ContentProfile profile) {
        String name = (profile.verName != null ? profile.verName : "").toLowerCase();
        switch (profile.type) {
            case CONTENT_TYPE_WINE:
            case CONTENT_TYPE_PROTON:
                return name.contains("arm64ec") ? "ARM64EC" : "X86_64";
            case CONTENT_TYPE_DXVK:
            case CONTENT_TYPE_VKD3D:
                return name.contains("arm64ec") ? "ARM64EC" : "X86";
            default:
                return "";
        }
    }

    // Second line of a row: what the build needs (the architecture is in the chip), its size, its description.
    private static String rowSubtitle(ContentProfile profile) {
        String name = (profile.verName != null ? profile.verName : "").toLowerCase();
        String need;
        switch (profile.type) {
            case CONTENT_TYPE_WINE:
            case CONTENT_TYPE_PROTON:
                need = name.contains("arm64ec") ? "FEXCore" : "Box64";
                if (name.contains("ntsync") && !HAS_NTSYNC) need += " • NTSync not on this phone (runs without it)";
                break;
            case CONTENT_TYPE_DXVK:
            case CONTENT_TYPE_VKD3D:
                need = name.contains("arm64ec") ? "native on arm64ec Wine" : "any Wine";
                break;
            default:
                need = requirementBadge(profile);
        }
        StringBuilder sb = new StringBuilder(need);
        if (profile.sizeFormatted != null && !profile.sizeFormatted.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(profile.sizeFormatted);
        }
        String desc = profile.desc != null ? profile.desc.trim() : "";
        if (!desc.isEmpty() && (profile.sizeFormatted == null || !desc.contains(profile.sizeFormatted))) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(desc);
        } else if (desc.isEmpty() && sb.length() == 0 && profile.verCode > 0) {
            sb.append("Build ").append(profile.verCode);
        }
        return sb.toString();
    }

    private void sortProfiles(List<ContentProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) return;
        if (sSortBy != null) {
            currentSortOrder = sSortBy.getSelectedItemPosition();
        }
        switch (currentSortOrder) {
            case 0 -> profiles.sort((a, b) -> {
                long scoreA = a.releaseDate > 0 ? a.releaseDate : ContentsManager.estimateReleaseDate(a.verName, a.remoteUrl);
                long scoreB = b.releaseDate > 0 ? b.releaseDate : ContentsManager.estimateReleaseDate(b.verName, b.remoteUrl);
                if (scoreA > 0 && scoreB > 0 && scoreA != scoreB) {
                    return Long.compare(scoreB, scoreA);
                }
                return compareVersionsDesc(a, b);
            });
            case 1 -> profiles.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.verName != null ? a.verName : "",
                b.verName != null ? b.verName : ""
            ));
            case 2 -> profiles.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                b.verName != null ? b.verName : "",
                a.verName != null ? a.verName : ""
            ));
            case 3 -> profiles.sort((a, b) -> Long.compare(b.size, a.size));
            case 4 -> profiles.sort((a, b) -> Long.compare(a.size, b.size));
            case 5 -> profiles.sort((a, b) -> Integer.compare(b.verCode, a.verCode));
        }
    }

    private static int compareVersionsDesc(ContentProfile p1, ContentProfile p2) {
        if (p1 == null && p2 == null) return 0;
        if (p1 == null) return 1;
        if (p2 == null) return -1;

        if (p1.verCode > 1 || p2.verCode > 1) {
            if (p1.verCode != p2.verCode) {
                return Integer.compare(p2.verCode, p1.verCode);
            }
        }

        String v1 = p1.verName != null ? p1.verName : "";
        String v2 = p2.verName != null ? p2.verName : "";

        String s1 = v1.replaceAll("(?i)^(proton|wine|dxvk|d7vk|d8vk|d9vk|vkd3d|box64|wowbox64|fexcore)[-_]?", "").trim();
        String s2 = v2.replaceAll("(?i)^(proton|wine|dxvk|d7vk|d8vk|d9vk|vkd3d|box64|wowbox64|fexcore)[-_]?", "").trim();

        String[] tokens1 = s1.split("[.\\-_+ ]+");
        String[] tokens2 = s2.split("[.\\-_+ ]+");

        int maxLen = Math.max(tokens1.length, tokens2.length);
        for (int i = 0; i < maxLen; i++) {
            String t1 = i < tokens1.length ? tokens1[i] : "";
            String t2 = i < tokens2.length ? tokens2[i] : "";

            Long n1 = extractNumber(t1);
            Long n2 = extractNumber(t2);

            if (n1 != null && n2 != null && !n1.equals(n2)) {
                return Long.compare(n2, n1);
            } else if (n1 != null && n2 == null) {
                return -1;
            } else if (n1 == null && n2 != null) {
                return 1;
            } else if (!t1.equalsIgnoreCase(t2)) {
                return t2.compareToIgnoreCase(t1);
            }
        }

        return v2.compareToIgnoreCase(v1);
    }

    private static Long extractNumber(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\d+").matcher(s);
            if (m.find()) {
                return Long.parseLong(m.group());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void updateContentTypeSpinner(Spinner spinner) {
        List<String> typeList = new ArrayList<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values())
            typeList.add(type.toString());

        ArrayAdapter<String> typeAdapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_dropdown_item, typeList) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
                    v.setBackgroundColor(ThemeManager.getSurfaceColor(getContext()));
                }
                return v;
            }
        };
        spinner.setAdapter(typeAdapter);
        spinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
    }

    private boolean isExtracting = false;

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
            try {
                preloaderDialog.show(R.string.loading);
                isExtracting = true;
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    ContentsManager.OnInstallFinishedCallback callback = new ContentsManager.OnInstallFinishedCallback() {
                        @Override
                        public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                            if (!isAdded() || getContext() == null || getActivity() == null) return;
                            String msg = switch (reason) {
                                case ERROR_BADTAR -> "Corrupted archive file";
                                case ERROR_NOSPACE -> "No space left on device";
                                case ERROR_NOPROFILE -> "Profile not found in package";
                                case ERROR_BADPROFILE -> "Profile cannot be recognized";
                                case ERROR_EXIST -> "Content already exists";
                                case ERROR_MISSINGFILES -> "Content is incomplete";
                                case ERROR_UNTRUSTPROFILE -> "Content cannot be trusted";
                                default -> "Unable to install content";
                            };
                            requireActivity().runOnUiThread(() -> ContentDialog.alert(getContext(), "Installation failed: " + msg, preloaderDialog::closeOnUiThread));
                        }

                        @Override
                        public void onSucceed(ContentProfile profile) {
                            if (!isAdded() || getContext() == null || getActivity() == null) return;
                            if (isExtracting) {
                                ContentsManager.OnInstallFinishedCallback callback1 = this;
                                requireActivity().runOnUiThread(() -> {
                                    ContentInfoDialog dialog = new ContentInfoDialog(getContext(), profile);
                                    ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string._continue);
                                    dialog.setOnConfirmCallback(() -> {
                                        isExtracting = false;
                                        List<ContentProfile.ContentFile> untrustedFiles = manager.getUnTrustedContentFiles(profile);
                                        if (!untrustedFiles.isEmpty()) {
                                            ContentUntrustedDialog untrustedDialog = new ContentUntrustedDialog(getContext(), untrustedFiles);
                                            untrustedDialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                            untrustedDialog.setOnConfirmCallback(() -> manager.finishInstallContent(profile, callback1));
                                            untrustedDialog.show();
                                        } else manager.finishInstallContent(profile, callback1);
                                    });
                                    dialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                    dialog.show();
                                });
                            } else {
                                manager.syncContents();
                                requireActivity().runOnUiThread(() -> {
                                    preloaderDialog.closeOnUiThread();
                                    loadContentList();
                                });
                            }
                        }
                    };

                    manager.extraContentFile(data.getData(), callback);
                });
            } catch (Exception e) {
                preloaderDialog.closeOnUiThread();
                if (getContext() != null) {
                    AppUtils.showToast(getContext(), R.string.unable_to_import_profile);
                }
            }
        }
    }

    private void loadContentList() {
        loadContentList(false);
    }

    private synchronized void loadContentList(boolean keepScroll) {
        if (!isAdded() || getContext() == null) return;
        android.os.Parcelable scroll = keepScroll && recyclerView.getLayoutManager() != null ? recyclerView.getLayoutManager().onSaveInstanceState() : null;
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        List<ContentProfile> rawProfiles = isInstalledFilter ? manager.getInstalledProfiles(currentContentType) : manager.getProfiles(currentContentType);
        List<ContentProfile> profiles = new ArrayList<>(rawProfiles);
        sortProfiles(profiles);

        if (profiles.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new ContentItemAdapter(profiles));
            if (scroll != null)
                recyclerView.getLayoutManager().onRestoreInstanceState(scroll);
            else
                recyclerView.scrollToPosition(0);
        }
        if (pbLoading != null) {
            pbLoading.postDelayed(() -> {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            }, 200);
        }
    }

    private class ContentItemAdapter extends RecyclerView.Adapter<ContentItemAdapter.ViewHolder> {
        private final List<ContentProfile> data;

        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView ivIcon;
            private final TextView tvVersionName;
            private final TextView tvVersionCode;
            private final TextView tvArchChip;
            private final ImageButton ibMenu;
            private final ImageButton ibDownload;
            private final View llDownloadProgress;
            private final ProgressBar horizontalProgressBar;
            private final TextView tvProgressDetails;
            private ContentProfile profile;

            public ViewHolder(@NonNull View view) {
                super(view);

                ivIcon = view.findViewById(R.id.IVIcon);
                tvVersionName = view.findViewById(R.id.TVVersionName);
                tvVersionCode = view.findViewById(R.id.TVVersionCode);
                tvArchChip = view.findViewById(R.id.TVArchChip);
                ibMenu = view.findViewById(R.id.BTMenu);
                ibDownload = view.findViewById(R.id.BTDownload);
                llDownloadProgress = view.findViewById(R.id.LLDownloadProgress);
                horizontalProgressBar = view.findViewById(R.id.HorizontalProgressBar);
                tvProgressDetails = view.findViewById(R.id.TVProgressDetails);
            }
        }

        public ContentItemAdapter(List<ContentProfile> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ContentItemAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.content_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.ibMenu.setOnClickListener(null);
            holder.ibDownload.setOnClickListener(null);
            super.onViewRecycled(holder);
        }

        @SuppressLint("StringFormatInvalid")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final ContentProfile profile = data.get(position);

            // Each kind of component has its own icon: the project's own logo where it has one (Wine, Box64, FEX; shown in
            // its colours), else a drawn one in the accent colour (DXVK, VKD3D and Proton publish no icon).
            int iconId = switch (profile.type) {
                case CONTENT_TYPE_PROTON -> R.drawable.icon_content_proton;
                case CONTENT_TYPE_DXVK, CONTENT_TYPE_D7VK -> R.drawable.icon_content_dxvk;
                case CONTENT_TYPE_VKD3D -> R.drawable.icon_content_vkd3d;
                case CONTENT_TYPE_BOX64, CONTENT_TYPE_WOWBOX64 -> R.drawable.content_logo_box64;
                case CONTENT_TYPE_FEXCORE -> R.drawable.content_logo_fex;
                default -> R.drawable.content_logo_wine;
            };
            boolean logo = iconId == R.drawable.content_logo_box64 || iconId == R.drawable.content_logo_fex || iconId == R.drawable.content_logo_wine;
            if (getContext() != null) {
                holder.ivIcon.setBackground(null);
                holder.ivIcon.setImageResource(iconId);
                int accent = ThemeManager.getAccentColor(getContext());
                androidx.core.widget.ImageViewCompat.setImageTintList(holder.ivIcon, logo ? null : android.content.res.ColorStateList.valueOf(accent));
                holder.ibMenu.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
                holder.ibDownload.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
                holder.horizontalProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
            }

            String title = (profile.verName != null && !profile.verName.isEmpty()) ? profile.verName : "Component";
            holder.tvVersionName.setText(title);
            if (getContext() != null) holder.tvVersionName.setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));

            // Architecture chip, then what the build needs, its size and its description.
            String chip = archChip(profile);
            holder.tvArchChip.setVisibility(chip.isEmpty() ? View.GONE : View.VISIBLE);
            if (!chip.isEmpty() && getContext() != null) {
                int accent = ThemeManager.getAccentColor(getContext());
                int onSurface = ThemeManager.getOnSurfaceTextColor(getContext());
                android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
                chipBg.setCornerRadius(getResources().getDisplayMetrics().density * 6);
                chipBg.setColor((accent & 0x00FFFFFF) | (0x40 << 24));
                holder.tvArchChip.setBackground(chipBg);
                holder.tvArchChip.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(accent, onSurface, 0.5f));
                holder.tvArchChip.setText(chip);
            }
            String subtitle = rowSubtitle(profile);
            holder.tvVersionCode.setText(subtitle);
            holder.tvVersionCode.setVisibility(subtitle.isEmpty() && chip.isEmpty() ? View.GONE : View.VISIBLE);

            // Safe async size loader: updates text view directly if still bound, NO notifyItemChanged!
            if (profile.remoteUrl != null && (profile.sizeFormatted == null || profile.sizeFormatted.isEmpty())) {
                ContentsManager.fetchRemoteSizeAsync(profile, () -> {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (holder.getBindingAdapterPosition() == position && profile.sizeFormatted != null) {
                                holder.tvVersionCode.setText(rowSubtitle(profile));
                                holder.tvVersionCode.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                });
            }

            // Click card to open full info
            holder.itemView.setOnClickListener(v -> {
                if (getContext() != null) {
                    new ContentInfoDialog(getContext(), profile).show();
                }
            });

            // 3-Dot menu
            holder.ibMenu.setVisibility(View.VISIBLE);
            holder.ibMenu.setOnClickListener(v -> {
                if (getContext() == null) return;
                PopupMenu selectionMenu = new PopupMenu(getContext(), holder.ibMenu);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    selectionMenu.setForceShowIcon(true);
                selectionMenu.inflate(R.menu.content_popup_menu);

                Menu menu = selectionMenu.getMenu();
                int accent = ThemeManager.getAccentColor(getContext());
                for (int i = 0; i < menu.size(); i++) {
                    MenuItem item = menu.getItem(i);
                    if (item.getIcon() != null) {
                        Drawable icon = item.getIcon().mutate();
                        icon.setTint(accent);
                        item.setIcon(icon);
                    }
                }

                if (profile.remoteUrl != null) {
                    selectionMenu.getMenu().findItem(R.id.remove_content).setVisible(false);
                }
                selectionMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.content_info) {
                        new ContentInfoDialog(getContext(), profile).show();
                    } else if (itemId == R.id.remove_content) {
                        // Strict container in-use validation!
                        String containerInUse = ContentsManager.getContainerUsingWine(getContext(), profile);
                        if (containerInUse != null) {
                            ContentDialog.alert(getContext(), "Cannot remove '" + profile.verName + "' because Container '" + containerInUse + "' is currently using this Wine version.\n\nPlease switch the container to another Wine version first.", null);
                            return true;
                        }

                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_content, () -> {
                            manager.removeContent(profile);
                            if (getContext() != null) {
                                AppUtils.showToast(getContext(), "Removed " + profile.verName);
                            }
                            loadContentList();
                        });
                    }
                    return true;
                });
                selectionMenu.show();
            });

            // Download button & live progress: the download runs in ContentDownloads, this only shows its state.
            holder.profile = profile;
            bindDownloadState(holder);

            holder.ibDownload.setOnClickListener(v -> {
                if (getContext() == null) return;
                ContentDownloads.start(getContext(), profile);
                bindDownloadState(holder);
            });

            holder.llDownloadProgress.setOnClickListener(v -> {
                ContentDownloads.Task task = ContentDownloads.get(profile.remoteUrl);
                if (getContext() == null || task == null || task.state != ContentDownloads.State.DOWNLOADING) return;
                ContentDialog.confirm(getContext(), "Cancel downloading " + task.name + "?", () -> ContentDownloads.cancel(profile.remoteUrl));
            });
        }

        private void bindDownloadState(ViewHolder holder) {
            ContentProfile profile = holder.profile;
            ContentDownloads.Task task = profile != null ? ContentDownloads.get(profile.remoteUrl) : null;
            if (task == null || !task.isActive()) {
                holder.ibDownload.setVisibility(profile != null && profile.remoteUrl != null ? View.VISIBLE : View.GONE);
                holder.llDownloadProgress.setVisibility(View.GONE);
                return;
            }

            holder.ibDownload.setVisibility(View.GONE);
            holder.llDownloadProgress.setVisibility(View.VISIBLE);
            if (task.state == ContentDownloads.State.INSTALLING) {
                holder.horizontalProgressBar.setIndeterminate(true);
                holder.tvProgressDetails.setText("Installing...");
            } else if (task.percent >= 0) {
                holder.horizontalProgressBar.setIndeterminate(false);
                holder.horizontalProgressBar.setProgress(task.percent);
                holder.tvProgressDetails.setText(String.format(java.util.Locale.US, "%s / %s (%d%%) • %.1f MB/s",
                    Downloader.formatFileSize(task.currentBytes), Downloader.formatFileSize(task.totalBytes), task.percent, task.speedMBs));
            } else {
                holder.horizontalProgressBar.setIndeterminate(true);
                holder.tvProgressDetails.setText(task.currentBytes > 0 ? String.format(java.util.Locale.US, "%s • %.1f MB/s",
                    Downloader.formatFileSize(task.currentBytes), task.speedMBs) : "Connecting...");
            }
        }

        // Refreshes the progress of the visible rows.
        private void refreshDownloadStates() {
            if (recyclerView == null) return;
            for (int i = 0; i < recyclerView.getChildCount(); i++) {
                RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
                if (holder instanceof ViewHolder) bindDownloadState((ViewHolder) holder);
            }
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}
