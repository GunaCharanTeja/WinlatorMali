package com.winlator.cmod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.CommunityConfigManager;
import com.winlator.cmod.core.CommunityConfigUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ImageUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.winlator.cmod.core.steamgrid.SteamGridDBApi;
import com.winlator.cmod.core.steamgrid.SteamGridGameDetailsResponse;
import com.winlator.cmod.core.steamgrid.SteamGridSearchResponse;

public class ShortcutsFragment extends Fragment {
    private ContainerManager manager;
    private RecyclerView recyclerView;
    private Shortcut currentShortcut;
    private static final int REQUEST_CODE_CUSTOM_COVER_ART = 1;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager = new ContainerManager(getContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.shortcuts_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.shortcuts);

        recyclerView = view.findViewById(R.id.RecyclerView);
        updateGridLayout();

        loadShortcutsList();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateGridLayout();
    }

    @Override
    public void onPause() {
        super.onPause();
        com.winlator.cmod.container.Shortcut.setOnShortcutLoadedListener(null);
    }

    private void updateGridLayout() {
        if (recyclerView == null) return;
        int columns = 2;
        Configuration config = getResources().getConfiguration();
        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (8 * density);

        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            columns = 5;
        }

        recyclerView.setPadding(padding, 0, padding, 0);
        recyclerView.setClipToPadding(false);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), columns));
        recyclerView.invalidateItemDecorations();
    }

    public void loadShortcutsList() {
        com.winlator.cmod.container.Shortcut.setOnShortcutLoadedListener(shortcut -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (recyclerView != null && recyclerView.getAdapter() != null) {
                        recyclerView.getAdapter().notifyDataSetChanged();
                    }
                });
            }
        });
        recyclerView.setAdapter(new ShortcutsAdapter(manager.loadShortcuts()));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_CUSTOM_COVER_ART && resultCode == Activity.RESULT_OK && data != null && currentShortcut != null) {
            Uri selectedImage = data.getData();
            try {
                android.graphics.Bitmap bitmap = android.provider.MediaStore.Images.Media.getBitmap(requireContext().getContentResolver(), selectedImage);
                currentShortcut.saveCustomCoverArt(bitmap);
                loadShortcutsList();
                Toast.makeText(getContext(), "Cover art updated.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(getContext(), "Failed to update cover art.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.shortcut_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Shortcut item = data.get(position);
            holder.title.setText(item.name);
            holder.subtitle.setText(item.container.getName());

            String remoteUrl = item.getCoverArtUrl();
            if (item.getCustomCoverArtPath().isEmpty() && remoteUrl != null) {
                Glide.with(getContext())
                    .load(remoteUrl)
                    .placeholder(R.drawable.cover_art_placeholder)
                    .centerCrop()
                    .into(holder.coverArt);
            } else {
                Glide.with(getContext())
                    .load(item.getCoverArt())
                    .placeholder(R.drawable.cover_art_placeholder)
                    .centerCrop()
                    .into(holder.coverArt);
            }

            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
            holder.innerArea.setOnClickListener((v) -> runFromShortcut(item));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.shortcut_popup_menu);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.shortcut_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.shortcut_remove) {
                    ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_shortcut, () -> {
                        if (shortcut.file.delete()) {
                            File lnkFile = new File(shortcut.file.getPath().substring(0, shortcut.file.getPath().lastIndexOf(".")) + ".lnk");
                            if (lnkFile.exists()) lnkFile.delete();
                            disableShortcutOnScreen(requireContext(), shortcut);
                            loadShortcutsList();
                            Toast.makeText(context, "Shortcut removed successfully.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Failed to remove the shortcut.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                else if (itemId == R.id.shortcut_clone_to_container) {
                    showContainerSelectionDialog(new ArrayList<>(manager.getContainers()), (selectedContainer) -> {
                        if (shortcut.cloneToContainer(selectedContainer)) {
                            Toast.makeText(context, "Shortcut cloned successfully.", Toast.LENGTH_SHORT).show();
                            loadShortcutsList();
                        } else {
                            Toast.makeText(context, "Failed to clone shortcut.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                else if (itemId == R.id.shortcut_add_to_home_screen) {
                    shortcut.genUUID();
                    addShortcutToScreen(shortcut);
                }
                else if (itemId == R.id.shortcut_export) {
                    exportShortcut(shortcut);
                }
                else if (itemId == R.id.shortcut_share_community) {
                    shareShortcutToCommunity(shortcut);
                }
                else if (itemId == R.id.shortcut_properties) {
                    showShortcutProperties(shortcut);
                }
                else if (itemId == R.id.shortcut_manage_cover_art) {
                    String[] options = {getString(R.string.search_cover_art), getString(R.string.change_cover_art), getString(R.string.reset_cover_art)};
                    ContentDialog.showSingleChoiceList(context, R.string.manage_cover_art, options, (index) -> {
                        if (index == 0) {
                            showCoverArtSelectionDialog(shortcut);
                        }
                        else if (index == 1) {
                            currentShortcut = shortcut;
                            Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                            startActivityForResult(intent, REQUEST_CODE_CUSTOM_COVER_ART);
                        }
                        else if (index == 2) {
                            shortcut.removeCustomCoverArt();
                            loadShortcutsList();
                            Toast.makeText(context, "Cover art reset.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                return true;
            });

            listItemMenu.show();
        }

        private void showContainerSelectionDialog(ArrayList<Container> containers, OnContainerSelectedListener listener) {
            String[] containerNames = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();
            ContentDialog.showSingleChoiceList(getContext(), R.string.containers, containerNames, (index) -> {
                listener.onContainerSelected(containers.get(index));
            });
        }

        private void runFromShortcut(Shortcut shortcut) {
            Intent intent = new Intent(getContext(), XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            intent.putExtra("shortcut_path", shortcut.file.getPath());
            intent.putExtra("shortcut_name", shortcut.name);
            getContext().startActivity(intent);
        }

        private void exportShortcut(Shortcut shortcut) {
            File exportDir = new File(com.winlator.cmod.SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH);
            if (!exportDir.exists()) exportDir.mkdirs();
            File exportFile = new File(exportDir, shortcut.file.getName());
            if (FileUtils.copy(shortcut.file, exportFile)) {
                Toast.makeText(getContext(), "Shortcut exported to " + exportFile.getPath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(), "Failed to export shortcut.", Toast.LENGTH_SHORT).show();
            }
        }

        private void shareShortcutToCommunity(Shortcut shortcut) {
            final Context context = getContext();
            if (context == null) return;

            if (!AppUtils.isNetworkAvailable(context)) {
                ContentDialog.alert(context, R.string.no_internet_connection, null);
                return;
            }

            final String currentName = shortcut.name;
            final String exeName = shortcut.getExecutable();
            final String steamId = shortcut.getExtra("steam_id");
            final String communityImage = shortcut.getExtra("community_image");

            // Step 1: If it's already tagged with a Steam ID, ask to confirm
            if (!steamId.isEmpty()) {
                ContentDialog dialog = new ContentDialog(context);
                dialog.setTitle("Confirm Game Info");
                dialog.setMessage("This configuration is tagged as:\n\n<b>" + currentName + "</b>\n\nIs this correct for the community?");
                ((TextView)dialog.findViewById(R.id.BTConfirm)).setText("YES");
                ((TextView)dialog.findViewById(R.id.BTCancel)).setText("NO, SEARCH");
                dialog.setOnConfirmCallback(() -> performCommunityUpload(shortcut, currentName, steamId, communityImage));
                dialog.setOnCancelCallback(() -> {
                    showCommunitySearchPrompt(shortcut, currentName);
                });
                dialog.show();
                return;
            }

            // Step 2: Try to find info automatically via PCGamingWiki (Searching by EXE)
            android.app.Dialog loadingDialog = new android.app.Dialog(context);
            loadingDialog.setContentView(new android.widget.ProgressBar(context));
            loadingDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            loadingDialog.setCancelable(false);
            loadingDialog.show();

            com.winlator.cmod.core.pcgw.PCGamingWikiAPI pcgwApi = com.winlator.cmod.core.CoverArtManager.getPCGWRetrofit().create(com.winlator.cmod.core.pcgw.PCGamingWikiAPI.class);
            String where = "Executable.File LIKE \"%" + exeName + "%\" OR Executable.File LIKE \"%" + exeName.toLowerCase() + "%\"";
            
            pcgwApi.searchByExecutable("cargoquery", "Executable", "Executable._pageName=GameTitle", where, "json").enqueue(new retrofit2.Callback<com.winlator.cmod.core.pcgw.PCGWResponse>() {
                @Override
                public void onResponse(retrofit2.Call<com.winlator.cmod.core.pcgw.PCGWResponse> call, retrofit2.Response<com.winlator.cmod.core.pcgw.PCGWResponse> response) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        if (response.isSuccessful() && response.body() != null && response.body().cargoquery != null && !response.body().cargoquery.isEmpty()) {
                            if (loadingDialog.isShowing()) loadingDialog.dismiss();
                            
                            // Get best name from PCGW
                            String pcgwName = response.body().cargoquery.get(0).title.gameTitle;
                            int bestScore = Integer.MAX_VALUE;
                            for (com.winlator.cmod.core.pcgw.PCGWResponse.CargoItem item : response.body().cargoquery) {
                                int score = calculateMatchScore(currentName, item.title.gameTitle);
                                if (score < bestScore) {
                                    bestScore = score;
                                    pcgwName = item.title.gameTitle;
                                }
                            }
                            final String finalSuggestedName = cleanGameName(currentName, pcgwName);
                            
                            ContentDialog dialog = new ContentDialog(context);
                            dialog.setTitle("Confirm Game Info");
                            dialog.setMessage("Identified via EXE:\n\n<b>" + finalSuggestedName + "</b>\n\nIs this correct?");
                            ((TextView)dialog.findViewById(R.id.BTConfirm)).setText("YES");
                            ((TextView)dialog.findViewById(R.id.BTCancel)).setText("NO, SEARCH");
                            dialog.setOnConfirmCallback(() -> searchGameForSharing(shortcut, finalSuggestedName, true));
                            dialog.setOnCancelCallback(() -> {
                                showCommunitySearchPrompt(shortcut, currentName);
                            });
                            dialog.show();
                        } else {
                            // Fallback to Steam Search if PCGW fails
                            trySteamSearchForSharing(shortcut, currentName, exeName, loadingDialog);
                        }
                    });
                }

                @Override
                public void onFailure(retrofit2.Call<com.winlator.cmod.core.pcgw.PCGWResponse> call, Throwable t) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> trySteamSearchForSharing(shortcut, currentName, exeName, loadingDialog));
                }
            });
        }

        private void trySteamSearchForSharing(Shortcut shortcut, String currentName, String exeName, android.app.Dialog loadingDialog) {
            Context context = getContext();
            if (context == null) return;

            com.winlator.cmod.core.steam.SteamStoreAPI steamApi = com.winlator.cmod.core.CoverArtManager.getSteamRetrofit().create(com.winlator.cmod.core.steam.SteamStoreAPI.class);
            String folderName = shortcut.getParentFolderName();
            String cleanedExe = exeName.toLowerCase().replace(".exe", "")
                    .replaceAll("(?i)(_)?(x64|x86|win64|win32|shipping|launcher|setup|installer)$", "")
                    .replaceAll("[^a-z0-9]", " ").trim();
            
            String searchTerm = currentName;
            if (!folderName.isEmpty() && folderName.length() > 2) searchTerm = folderName;
            else if (cleanedExe.length() > 3) searchTerm = cleanedExe;

            final String finalSearchTerm = searchTerm;
            steamApi.search(finalSearchTerm, "english", "US").enqueue(new retrofit2.Callback<com.winlator.cmod.core.steam.SteamSearchResponse>() {
                @Override
                public void onResponse(retrofit2.Call<com.winlator.cmod.core.steam.SteamSearchResponse> call, retrofit2.Response<com.winlator.cmod.core.steam.SteamSearchResponse> response) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        if (loadingDialog.isShowing()) loadingDialog.dismiss();
                        String suggestedName = currentName;
                        if (response.isSuccessful() && response.body() != null && response.body().items != null && !response.body().items.isEmpty()) {
                            suggestedName = findBestSteamMatch(finalSearchTerm, response.body().items);
                        }
                        final String finalSuggestedName = suggestedName;

                        ContentDialog dialog = new ContentDialog(context);
                        dialog.setTitle("Confirm Game Info");
                        dialog.setMessage("Is this configuration for:\n\n<b>" + finalSuggestedName + "</b>?");
                        ((TextView)dialog.findViewById(R.id.BTConfirm)).setText("YES");
                        ((TextView)dialog.findViewById(R.id.BTCancel)).setText("NO, SEARCH");
                        dialog.setOnConfirmCallback(() -> searchGameForSharing(shortcut, finalSuggestedName, true));
                        dialog.setOnCancelCallback(() -> {
                            showCommunitySearchPrompt(shortcut, currentName);
                        });
                        dialog.show();
                    });
                }

                @Override
                public void onFailure(retrofit2.Call<com.winlator.cmod.core.steam.SteamSearchResponse> call, Throwable t) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        if (loadingDialog.isShowing()) loadingDialog.dismiss();
                        showCommunitySearchPrompt(shortcut, currentName);
                    });
                }
            });
        }

        private void showCommunitySearchPrompt(Shortcut shortcut, String initialValue) {
            Context context = getContext();
            if (context == null) return;

            ContentDialog dialog = new ContentDialog(context);
            dialog.setTitle(R.string.search_game_info);
            dialog.setMessage(getString(R.string.community_search_instruction));
            
            final android.widget.EditText editText = dialog.findViewById(R.id.EditText);
            editText.setVisibility(View.VISIBLE);
            
            // Apply independent styling to ensure visibility in both light and dark themes
            boolean isDarkMode = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean("dark_mode", false);
            if (isDarkMode) {
                editText.setTextColor(android.graphics.Color.WHITE);
                editText.setHintTextColor(android.graphics.Color.GRAY);
                editText.setBackgroundResource(R.drawable.edit_text_dark);
            } else {
                editText.setTextColor(android.graphics.Color.BLACK);
                editText.setHintTextColor(android.graphics.Color.GRAY);
                editText.setBackgroundResource(R.drawable.edit_text);
            }

            if (initialValue != null) editText.setText(initialValue);
            
            dialog.setOnConfirmCallback(() -> {
                String query = editText.getText().toString().trim();
                if (!query.isEmpty()) {
                    searchGameForSharing(shortcut, query, false);
                } else {
                    Toast.makeText(context, "Please enter a game name", Toast.LENGTH_SHORT).show();
                }
            });
            dialog.show();
        }

        private void searchGameForSharing(Shortcut shortcut, String query, boolean autoSelect) {
            Context context = getContext();
            if (context == null) return;

            if (!AppUtils.isNetworkAvailable(context)) {
                ContentDialog.alert(context, R.string.no_internet_connection, null);
                return;
            }

            final String apiKey = "0324c52513634547a7b32d6d323635d0"; // Reusing Winlator default SGDB key
            SteamGridDBApi api = com.winlator.cmod.core.CoverArtManager.getRetrofit().create(SteamGridDBApi.class);

            api.searchGame("Bearer " + apiKey, query).enqueue(new retrofit2.Callback<SteamGridSearchResponse>() {
                @Override
                public void onResponse(retrofit2.Call<SteamGridSearchResponse> call, retrofit2.Response<SteamGridSearchResponse> response) {
                    if (response.isSuccessful() && response.body() != null && response.body().data != null && !response.body().data.isEmpty()) {
                        List<SteamGridSearchResponse.GameData> games = response.body().data;

                        // If autoSelect is enabled and we have a match, skip the list dialog
                        if (autoSelect && !games.isEmpty()) {
                            fetchSgdbDetailsAndUpload(shortcut, games.get(0), api, apiKey);
                            return;
                        }

                        String[] names = new String[games.size()];
                        for (int i = 0; i < games.size(); i++) names[i] = games.get(i).name;

                        Activity activity = getActivity();
                        if (activity != null) activity.runOnUiThread(() -> {
                            ContentDialog.showSingleChoiceList(context, "Select Game", names, index -> {
                                fetchSgdbDetailsAndUpload(shortcut, games.get(index), api, apiKey);
                            });
                        });
                    } else {
                        Activity activity = getActivity();
                        if (activity != null) activity.runOnUiThread(() -> {
                            Toast.makeText(context, "No games found on SteamGridDB. Uploading with current info.", Toast.LENGTH_SHORT).show();
                            performCommunityUpload(shortcut, shortcut.name, "", "");
                        });
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<SteamGridSearchResponse> call, Throwable t) {
                    Activity activity = getActivity();
                    if (activity != null) activity.runOnUiThread(() -> performCommunityUpload(shortcut, shortcut.name, "", ""));
                }
            });
        }

        private void fetchSgdbDetailsAndUpload(Shortcut shortcut, SteamGridSearchResponse.GameData selected, SteamGridDBApi api, String apiKey) {
            api.getGameDetails("Bearer " + apiKey, selected.id).enqueue(new retrofit2.Callback<SteamGridGameDetailsResponse>() {
                @Override
                public void onResponse(retrofit2.Call<SteamGridGameDetailsResponse> call, retrofit2.Response<SteamGridGameDetailsResponse> response) {
                    String foundSteamId = "";
                    if (response.isSuccessful() && response.body() != null && response.body().data != null) {
                        if (response.body().data.externalIds != null && response.body().data.externalIds.steam != null) {
                            foundSteamId = response.body().data.externalIds.steam.id;
                        }
                    }

                    final String finalSteamId = foundSteamId;
                    if (finalSteamId.isEmpty()) {
                        api.getGridsByGameId("Bearer " + apiKey, selected.id, "no_logo", null, "static").enqueue(new retrofit2.Callback<com.winlator.cmod.core.steamgrid.SteamGridGridsResponse>() {
                            @Override
                            public void onResponse(retrofit2.Call<com.winlator.cmod.core.steamgrid.SteamGridGridsResponse> call, retrofit2.Response<com.winlator.cmod.core.steamgrid.SteamGridGridsResponse> response) {
                                String fallbackImage = "";
                                if (response.isSuccessful() && response.body() != null && response.body().data != null && !response.body().data.isEmpty()) {
                                    fallbackImage = response.body().data.get(0).url;
                                }
                                final String finalFallback = fallbackImage;
                                Activity activity2 = getActivity();
                                if (activity2 != null) activity2.runOnUiThread(() -> performCommunityUpload(shortcut, selected.name, "", finalFallback));
                            }

                            @Override public void onFailure(retrofit2.Call<com.winlator.cmod.core.steamgrid.SteamGridGridsResponse> call, Throwable t) {
                                Activity activity2 = getActivity();
                                if (activity2 != null) activity2.runOnUiThread(() -> performCommunityUpload(shortcut, selected.name, "", ""));
                            }
                        });
                    } else {
                        Activity activity2 = getActivity();
                        if (activity2 != null) activity2.runOnUiThread(() -> performCommunityUpload(shortcut, selected.name, finalSteamId, ""));
                    }
                }

                @Override public void onFailure(retrofit2.Call<SteamGridGameDetailsResponse> call, Throwable t) {
                    Activity activity2 = getActivity();
                    if (activity2 != null) activity2.runOnUiThread(() -> performCommunityUpload(shortcut, selected.name, "", ""));
                }
            });
        }

        private void performCommunityUpload(Shortcut shortcut, String gameName, String steamId, String communityImage) {
            final Context context = getContext();
            if (context == null) return;

            ContentDialog uploadDialog = new ContentDialog(context, R.layout.community_upload_dialog);
            uploadDialog.setTitle("Community Upload");
            
            final android.widget.EditText etTitle = uploadDialog.findViewById(R.id.ETConfigTitle);
            final android.widget.EditText etNotes = uploadDialog.findViewById(R.id.ETConfigNotes);
            
            boolean isDarkMode = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean("dark_mode", false);
            int textColor = isDarkMode ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
            int hintColor = android.graphics.Color.GRAY;
            int bgRes = isDarkMode ? R.drawable.edit_text_dark : R.drawable.edit_text;

            etTitle.setTextColor(textColor); etTitle.setHintTextColor(hintColor); etTitle.setBackgroundResource(bgRes);
            etNotes.setTextColor(textColor); etNotes.setHintTextColor(hintColor); etNotes.setBackgroundResource(bgRes);

            uploadDialog.setOnConfirmCallback(() -> {
                String title = etTitle.getText().toString().trim();
                String notes = etNotes.getText().toString().trim();
                if (title.isEmpty()) {
                    Toast.makeText(context, "Config title is required", Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmAndUpload(shortcut, gameName, steamId, communityImage, notes, title);
            });
            uploadDialog.show();
        }

        private void confirmAndUpload(Shortcut shortcut, String gameName, String steamId, String communityImage, String notes, String configTitle) {
            final Context context = getContext();
            ContentDialog.confirm(context, "Share configuration for '" + gameName + "'?", () -> {
                org.json.JSONObject config = CommunityConfigUtils.exportConfig(context, shortcut, gameName, steamId, communityImage, notes, configTitle);
                if (config != null) {
                    final MainActivity activity = (MainActivity) getActivity();
                    if (activity != null) activity.preloaderDialog.show("Uploading Configuration...");
                    CommunityConfigManager.uploadConfig(config, error -> {
                        if (activity == null) return;
                        activity.runOnUiThread(() -> {
                            activity.preloaderDialog.close();
                            if (error == null) {
                                Toast.makeText(context, "Uploaded successfully!", Toast.LENGTH_LONG).show();
                            } else {
                                ContentDialog.alert(context, "Upload failed:\n\n" + error, null);
                            }
                        });
                    });
                } else {
                    ContentDialog.alert(context, "Only standard Wine versions (Proton 9.0/10) can be shared.", null);
                }
            });
        }

        private void showShortcutProperties(Shortcut shortcut) {
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(shortcut.name).append("\n");
            sb.append("Container: ").append(shortcut.container.getName()).append("\n");
            sb.append("Path: ").append(shortcut.path).append("\n");
            sb.append("File: ").append(shortcut.file.getPath());
            ContentDialog dialog = new ContentDialog(getContext());
            dialog.setTitle("Shortcut Properties");
            dialog.setMessage(sb.toString());
            dialog.show();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView coverArt;
            private final TextView title;
            private final TextView subtitle;
            private final ImageButton menuButton;
            private final View innerArea;

            public ViewHolder(View view) {
                super(view);
                coverArt = view.findViewById(R.id.ImageView);
                title = view.findViewById(R.id.TVTitle);
                subtitle = view.findViewById(R.id.TVSubtitle);
                menuButton = view.findViewById(R.id.BTMenu);
                innerArea = view.findViewById(R.id.LLInnerArea);
            }
        }
    }

    private interface OnContainerSelectedListener {
        void onContainerSelected(Container container);
    }

    public static void addShortcutToScreen(Shortcut shortcut) {
        Context context = shortcut.container.getManager().getContext();
        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            Icon icon = shortcut.icon != null ? Icon.createWithBitmap(shortcut.icon) : Icon.createWithResource(context, R.drawable.icon_shortcut);
            ShortcutInfo shortcutInfo = buildScreenShortCut(context, shortcut.name, shortcut.name, shortcut.container.id, shortcut.file.getPath(), icon, shortcut.name);
            shortcutManager.requestPinShortcut(shortcutInfo, null);
        }
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        if (shortcutManager != null) {
            ArrayList<String> ids = new ArrayList<>();
            ids.add(shortcut.name);
            shortcutManager.disableShortcuts(ids);
        }
    }

    public void updateShortcutOnScreen(String id, String label, int containerId, String shortcutPath, Icon icon, String shortcutName) {
        Context context = getContext();
        if (context == null) return;
        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        if (shortcutManager != null) {
            ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
            shortcuts.add(buildScreenShortCut(context, id, label, containerId, shortcutPath, icon, shortcutName));
            shortcutManager.updateShortcuts(shortcuts);
        }
    }

    public static ShortcutInfo buildScreenShortCut(Context context, String id, String label, int containerId, String shortcutPath, Icon icon, String shortcutName) {
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);
        intent.putExtra("shortcut_name", shortcutName);

        return new ShortcutInfo.Builder(context, id)
                .setShortLabel(label)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void showCoverArtSelectionDialog(Shortcut shortcut) {
        Context context = getContext();
        if (context == null) return;

        String defaultName = shortcut.name.replaceAll("\\(.*?\\)", "").replaceAll("\\[.*?\\]", "").trim();
        String exeName = shortcut.getExecutable();
        String folderName = shortcut.getParentFolderName();

        if (AppUtils.isNetworkAvailable(context)) {
            android.app.Dialog loadingDialog = new android.app.Dialog(context);
            loadingDialog.setContentView(new android.widget.ProgressBar(context));
            loadingDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            loadingDialog.setCancelable(false);
            loadingDialog.show();

            com.winlator.cmod.core.pcgw.PCGamingWikiAPI pcgwApi = com.winlator.cmod.core.CoverArtManager.getPCGWRetrofit().create(com.winlator.cmod.core.pcgw.PCGamingWikiAPI.class);
            String where = "Executable.File LIKE \"%" + exeName + "%\" OR Executable.File LIKE \"%" + exeName.toLowerCase() + "%\"";
            
            pcgwApi.searchByExecutable("cargoquery", "Executable", "Executable._pageName=GameTitle", where, "json").enqueue(new retrofit2.Callback<com.winlator.cmod.core.pcgw.PCGWResponse>() {
                @Override
                public void onResponse(retrofit2.Call<com.winlator.cmod.core.pcgw.PCGWResponse> call, retrofit2.Response<com.winlator.cmod.core.pcgw.PCGWResponse> response) {
                    if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
                    if (response.isSuccessful() && response.body() != null && response.body().cargoquery != null && !response.body().cargoquery.isEmpty()) {
                        if (loadingDialog.isShowing()) loadingDialog.dismiss();
                        
                        String bestName = response.body().cargoquery.get(0).title.gameTitle;
                        int bestScore = Integer.MAX_VALUE;
                        
                        for (com.winlator.cmod.core.pcgw.PCGWResponse.CargoItem item : response.body().cargoquery) {
                            int score = calculateMatchScore(defaultName, item.title.gameTitle);
                            if (score < bestScore) {
                                bestScore = score;
                                bestName = item.title.gameTitle;
                            }
                        }
                        
                        String suggestedName = cleanGameName(defaultName, bestName);
                        Toast.makeText(context, "Matched via PCGamingWiki: " + suggestedName, Toast.LENGTH_SHORT).show();
                        showSearchPrompt(shortcut, suggestedName);
                    } else {
                        trySteamSearch(shortcut, defaultName, folderName, loadingDialog);
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<com.winlator.cmod.core.pcgw.PCGWResponse> call, Throwable t) {
                    if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
                    trySteamSearch(shortcut, defaultName, folderName, loadingDialog);
                }
            });
        } else {
            showSearchPrompt(shortcut, defaultName);
        }
    }

    private void trySteamSearch(Shortcut shortcut, String defaultName, String folderName, android.app.Dialog loadingDialog) {
        Context context = getContext();
        if (context == null) return;
        
        com.winlator.cmod.core.steam.SteamStoreAPI steamApi = com.winlator.cmod.core.CoverArtManager.getSteamRetrofit().create(com.winlator.cmod.core.steam.SteamStoreAPI.class);
        
        // Improve fallback: try folder name first, then cleaned exe name, finally the shortcut name
        String exeName = shortcut.getExecutable();
        String cleanedExe = exeName.toLowerCase().replace(".exe", "")
                .replaceAll("(?i)(_)?(x64|x86|win64|win32|shipping|launcher|setup|installer)$", "")
                .replaceAll("[^a-z0-9]", " ").trim();
        
        String searchTerm = defaultName;
        if (!folderName.isEmpty() && folderName.length() > 2) {
            searchTerm = folderName;
        } else if (cleanedExe.length() > 3) {
            searchTerm = cleanedExe;
        }
        
        final String finalSearchTerm = searchTerm;
        steamApi.search(finalSearchTerm, "english", "US").enqueue(new retrofit2.Callback<com.winlator.cmod.core.steam.SteamSearchResponse>() {
            @Override
            public void onResponse(retrofit2.Call<com.winlator.cmod.core.steam.SteamSearchResponse> call, retrofit2.Response<com.winlator.cmod.core.steam.SteamSearchResponse> response) {
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
                if (loadingDialog.isShowing()) loadingDialog.dismiss();
                String suggestedName = defaultName;
                if (response.isSuccessful() && response.body() != null && response.body().items != null && !response.body().items.isEmpty()) {
                    suggestedName = findBestSteamMatch(finalSearchTerm, response.body().items);
                    Toast.makeText(context, "Matched via Steam: " + suggestedName, Toast.LENGTH_SHORT).show();
                } else if (!folderName.isEmpty() && folderName.length() > 2) {
                    suggestedName = folderName;
                } else if (cleanedExe.length() > 3) {
                    suggestedName = cleanedExe;
                }
                showSearchPrompt(shortcut, suggestedName);
            }

            @Override
            public void onFailure(retrofit2.Call<com.winlator.cmod.core.steam.SteamSearchResponse> call, Throwable t) {
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
                if (loadingDialog.isShowing()) loadingDialog.dismiss();
                String suggestedName = !folderName.isEmpty() && folderName.length() > 2 ? folderName : defaultName;
                showSearchPrompt(shortcut, suggestedName);
            }
        });
    }

    private int calculateMatchScore(String query, String target) {
        String q = query.toLowerCase().replaceAll("[^a-z0-9]", "");
        String t = target.toLowerCase().replaceAll("[^a-z0-9]", "");
        
        // Base score is the difference in length
        int score = Math.abs(q.length() - t.length());
        
        // Heavy penalty for "Remastered" if query doesn't have it
        if (t.contains("remaster") && !q.contains("remaster")) score += 30;
        
        // Penalty for "Edition" or "Pack" if query is basic
        if ((t.contains("edition") || t.contains("pack") || t.contains("bundle")) && 
            !(q.contains("edition") || q.contains("pack") || q.contains("bundle"))) {
            score += 15;
        }

        // Penalty for very long names (usually subtitles/DLCs)
        if (t.length() > q.length() + 10) score += 10;
        
        // Bonus for containing the exact query
        if (t.contains(q)) score -= 5;
        
        return score;
    }

    private String cleanGameName(String query, String foundName) {
        String resultName = foundName;
        boolean queryIsBasic = !query.toLowerCase().contains("remaster") && !query.toLowerCase().contains("edition");
        
        if (queryIsBasic) {
            String lowercaseName = resultName.toLowerCase();
            // Order is important: more specific first
            if (lowercaseName.contains("remastered")) {
                resultName = resultName.replaceAll("(?i)\\s*[:-]?\\s*remastered\\s*", " ").trim();
            } else if (lowercaseName.contains("remaster")) {
                resultName = resultName.replaceAll("(?i)\\s*[:-]?\\s*remaster\\s*", " ").trim();
            }
            
            if (!query.toLowerCase().contains("edition")) {
                resultName = resultName.replaceAll("(?i)\\s*[:-]?\\s*(standard|gold|ultimate|complete|deluxe|game of the year|goty|director's cut)\\s+edition\\s*", " ").trim();
                resultName = resultName.replaceAll("(?i)\\s*[:-]?\\s*edition\\s*", " ").trim();
            }
        }
        return resultName.replaceAll("\\s+", " ").trim();
    }

    private String findBestSteamMatch(String query, List<com.winlator.cmod.core.steam.SteamSearchResponse.SteamItem> items) {
        com.winlator.cmod.core.steam.SteamSearchResponse.SteamItem bestMatch = items.get(0);
        int bestScore = Integer.MAX_VALUE;

        for (com.winlator.cmod.core.steam.SteamSearchResponse.SteamItem item : items) {
            int score = calculateMatchScore(query, item.name);
            if (score < bestScore) {
                bestScore = score;
                bestMatch = item;
            }
        }
        
        return cleanGameName(query, bestMatch.name);
    }

    private void showSearchPrompt(Shortcut shortcut, String initialValue) {
        ContentDialog.prompt(getContext(), R.string.search_cover_art, initialValue, (query) -> {
            performCoverArtSearch(shortcut, query);
        });
    }

    private void performCoverArtSearch(Shortcut shortcut, String query) {
        Context context = getContext();
        if (context == null) return;

        android.app.Dialog loadingDialog = new android.app.Dialog(context);
        loadingDialog.setContentView(new android.widget.ProgressBar(context));
        loadingDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        loadingDialog.setCancelable(false);
        loadingDialog.show();

        com.winlator.cmod.core.CoverArtManager.fetchCoverArtOptions(context, shortcut, query, new com.winlator.cmod.core.CoverArtManager.GridOptionsCallback() {
            @Override
            public void onOptionsAvailable(java.util.List<com.winlator.cmod.core.steamgrid.SteamGridGridsResponse.GridData> options) {
                Activity activity = getActivity();
                if (activity != null && !activity.isFinishing() && isAdded()) {
                    activity.runOnUiThread(() -> {
                        if (loadingDialog.isShowing()) loadingDialog.dismiss();
                        ContentDialog dialog = new ContentDialog(context, R.layout.cover_art_selection_dialog);
                        dialog.setTitle(R.string.search_cover_art);
                        dialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);

                        RecyclerView recyclerView = dialog.findViewById(R.id.RecyclerView);
                        recyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
                        recyclerView.setVisibility(View.VISIBLE);
                        dialog.findViewById(R.id.ProgressBar).setVisibility(View.GONE);

                        recyclerView.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                            @NonNull
                            @Override
                            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
                                View view = LayoutInflater.from(context).inflate(R.layout.cover_art_selection_item, parent, false);
                                return new RecyclerView.ViewHolder(view) {};
                            }

                            @Override
                            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                                com.winlator.cmod.core.steamgrid.SteamGridGridsResponse.GridData data = options.get(position);
                                ImageView imageView = holder.itemView.findViewById(R.id.ImageView);
                                Glide.with(context)
                                        .load(data.thumb != null ? data.thumb : data.url)
                                        .placeholder(R.drawable.cover_art_placeholder)
                                        .thumbnail(0.1f)
                                        .centerCrop()
                                        .into(imageView);

                                holder.itemView.setOnClickListener(v -> {
                                    dialog.dismiss();
                                    Toast.makeText(context, "Applying cover art...", Toast.LENGTH_SHORT).show();
                                    com.winlator.cmod.core.CoverArtManager.downloadSelectedCoverArt(data.url, shortcut, new com.winlator.cmod.core.CoverArtManager.DownloadCallback() {
                                        @Override
                                        public void onCompleted(android.graphics.Bitmap bitmap) {
                                            Activity a = getActivity();
                                            if (a != null && !a.isFinishing() && isAdded()) a.runOnUiThread(() -> {
                                                loadShortcutsList();
                                                Toast.makeText(context, "Cover art updated.", Toast.LENGTH_SHORT).show();
                                            });
                                        }

                                        @Override
                                        public void onFailed(com.winlator.cmod.core.CoverArtManager.ErrorReason reason) {
                                            Activity a = getActivity();
                                            if (a != null && !a.isFinishing() && isAdded()) a.runOnUiThread(() -> Toast.makeText(context, "Failed to download cover art.", Toast.LENGTH_SHORT).show());
                                        }
                                    });
                                });
                            }

                            @Override
                            public int getItemCount() {
                                return options.size();
                            }
                        });
                        if (!activity.isFinishing()) dialog.show();
                    });
                }
            }

            @Override
            public void onFailed(com.winlator.cmod.core.CoverArtManager.ErrorReason reason) {
                Activity activity = getActivity();
                if (activity != null && !activity.isFinishing() && isAdded()) {
                    activity.runOnUiThread(() -> {
                        if (loadingDialog.isShowing()) loadingDialog.dismiss();
                        String message;
                        if (reason == com.winlator.cmod.core.CoverArtManager.ErrorReason.NETWORK_UNAVAILABLE) {
                            message = "Connection Error\n\nPlease check your internet and try again. Searching requires an active connection to SteamGridDB.";
                        } else if (reason == com.winlator.cmod.core.CoverArtManager.ErrorReason.NOT_FOUND) {
                            message = "Game Not Found\n\nCould not find \"" + query + "\".\n\n" +
                                      "Try these tips:\n" +
                                      "• Check for typos\n" +
                                      "• Use the full title (e.g., 'Grand Theft Auto V')\n" +
                                      "• Try adding the year: 'God of War (2018)'\n" +
                                      "• Click 'RETRY' to try a different name.";
                        } else if (reason == com.winlator.cmod.core.CoverArtManager.ErrorReason.UNAUTHORIZED) {
                            message = "Invalid API Key\n\nThe custom SteamGridDB API key in Settings is invalid.";
                        } else if (reason == com.winlator.cmod.core.CoverArtManager.ErrorReason.RATE_LIMITED) {
                            message = "Too Many Requests\n\nPlease wait a few seconds before trying again.";
                        } else {
                            message = "Service Unavailable\n\nSteamGridDB is currently busy. Please try again later.";
                        }
                        
                        ContentDialog errorDialog = new ContentDialog(context);
                        errorDialog.setTitle(R.string.search_cover_art);
                        errorDialog.setMessage(message);
                        if (reason == com.winlator.cmod.core.CoverArtManager.ErrorReason.NOT_FOUND || reason == com.winlator.cmod.core.CoverArtManager.ErrorReason.NETWORK_UNAVAILABLE) {
                            errorDialog.setOnConfirmCallback(() -> showCoverArtSelectionDialog(shortcut));
                            ((TextView)errorDialog.findViewById(R.id.BTConfirm)).setText("RETRY");
                        } else {
                            errorDialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
                        }
                        errorDialog.show();
                    });
                }
            }
        });
    }
}
