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

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ThemeManager.applyThemeToView(view, getContext());
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
                        ((TextView) v).setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
                    }
                    return v;
                }

                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    View v = super.getDropDownView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
                        v.setBackgroundColor(isDarkMode ? 0xFF2A2A2A : 0xFFFFFFFF);
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
                        ((TextView) v).setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
                    }
                    return v;
                }

                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    View v = super.getDropDownView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
                        v.setBackgroundColor(isDarkMode ? 0xFF2A2A2A : 0xFFFFFFFF);
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
        input.setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
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
                    ((TextView) v).setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
                    v.setBackgroundColor(isDarkMode ? 0xFF2A2A2A : 0xFFFFFFFF);
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

    private synchronized void loadContentList() {
        if (!isAdded() || getContext() == null) return;
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
            private final ImageButton ibMenu;
            private final ImageButton ibDownload;
            private final View llDownloadProgress;
            private final ProgressBar horizontalProgressBar;
            private final TextView tvProgressDetails;

            public ViewHolder(@NonNull View view) {
                super(view);

                ivIcon = view.findViewById(R.id.IVIcon);
                tvVersionName = view.findViewById(R.id.TVVersionName);
                tvVersionCode = view.findViewById(R.id.TVVersionCode);
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

            int iconId = switch (profile.type) {
                case CONTENT_TYPE_WINE, CONTENT_TYPE_PROTON -> R.drawable.icon_wine;
                default -> R.drawable.icon_settings;
            };
            if (getContext() != null) {
                holder.ivIcon.setBackground(getContext().getDrawable(iconId));
                int accent = ThemeManager.getAccentColor(getContext());
                holder.ivIcon.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
                holder.ibMenu.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
                holder.ibDownload.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
                holder.horizontalProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
            }

            String title = (profile.verName != null && !profile.verName.isEmpty()) ? profile.verName : "Component";
            holder.tvVersionName.setText(title);

            // Subtitle formatting
            if (profile.sizeFormatted != null && !profile.sizeFormatted.isEmpty()) {
                String desc = (profile.desc != null && !profile.desc.isEmpty()) ? profile.desc : ("Cloud Package • " + profile.type);
                if (desc.contains(profile.sizeFormatted)) {
                    holder.tvVersionCode.setText(desc);
                } else {
                    holder.tvVersionCode.setText(profile.sizeFormatted + " • " + desc);
                }
                holder.tvVersionCode.setVisibility(View.VISIBLE);
            } else if (profile.desc != null && !profile.desc.isEmpty()) {
                holder.tvVersionCode.setVisibility(View.VISIBLE);
                holder.tvVersionCode.setText(profile.desc);
            } else if (profile.verCode > 0) {
                holder.tvVersionCode.setVisibility(View.VISIBLE);
                holder.tvVersionCode.setText("Build " + profile.verCode);
            } else if (profile.remoteUrl != null) {
                holder.tvVersionCode.setVisibility(View.VISIBLE);
                holder.tvVersionCode.setText("Cloud Package • " + profile.type);
            } else {
                holder.tvVersionCode.setVisibility(View.GONE);
            }

            // Safe async size loader: updates text view directly if still bound, NO notifyItemChanged!
            if (profile.remoteUrl != null && (profile.sizeFormatted == null || profile.sizeFormatted.isEmpty())) {
                ContentsManager.fetchRemoteSizeAsync(profile, () -> {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (holder.getBindingAdapterPosition() == position && profile.sizeFormatted != null) {
                                String desc = (profile.desc != null && !profile.desc.isEmpty()) ? profile.desc : ("Cloud Package • " + profile.type);
                                holder.tvVersionCode.setText(profile.sizeFormatted + " • " + desc);
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

            // Download button & live progress
            holder.ibDownload.setVisibility((profile.remoteUrl != null) ? View.VISIBLE : View.GONE);
            holder.llDownloadProgress.setVisibility(View.GONE);

            holder.ibDownload.setOnClickListener(v -> {
                holder.ibDownload.setVisibility(View.GONE);
                holder.llDownloadProgress.setVisibility(View.VISIBLE);
                holder.horizontalProgressBar.setProgress(0);
                holder.tvProgressDetails.setText("Connecting...");

                Intent intent = new Intent();
                intent.setData(Uri.parse(profile.remoteUrl));
                new Thread(() -> {
                    long timestamp = System.currentTimeMillis();
                    File cacheDir = getContext() != null ? getContext().getCacheDir() : (getActivity() != null ? getActivity().getCacheDir() : null);
                    if (cacheDir == null) return;
                    File output = new File(cacheDir, "temp_" + timestamp);
                    boolean success = Downloader.downloadFile(profile.remoteUrl, output, (currentBytes, totalBytes, percent, speedMBs) -> {
                        if (isAdded() && getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (percent >= 0) {
                                    holder.horizontalProgressBar.setIndeterminate(false);
                                    holder.horizontalProgressBar.setProgress(percent);
                                    holder.tvProgressDetails.setText(String.format(java.util.Locale.US, "%s / %s (%d%%) • %.1f MB/s",
                                        Downloader.formatFileSize(currentBytes), Downloader.formatFileSize(totalBytes), percent, speedMBs));
                                } else {
                                    holder.horizontalProgressBar.setIndeterminate(true);
                                    holder.tvProgressDetails.setText(String.format(java.util.Locale.US, "%s • %.1f MB/s",
                                        Downloader.formatFileSize(currentBytes), speedMBs));
                                }
                            });
                        }
                    });

                    if (success) {
                        intent.setData(Uri.parse(output.getAbsolutePath()));
                    }

                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            holder.llDownloadProgress.setVisibility(View.GONE);
                            holder.ibDownload.setVisibility(View.VISIBLE);
                            if (success) {
                                onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_OK, intent);
                            } else {
                                if (getContext() != null) {
                                    AppUtils.showToast(getContext(), "Download failed. Please check network connection.");
                                }
                            }
                        });
                    }
                }).start();
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}
