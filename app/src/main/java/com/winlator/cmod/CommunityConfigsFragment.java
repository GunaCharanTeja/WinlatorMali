package com.winlator.cmod;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.CommunityConfigManager;
import com.winlator.cmod.core.CommunityConfigUtils;
import com.winlator.cmod.contents.WineDownloader;
import android.text.Html;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.widget.ListView;
import androidx.appcompat.widget.SearchView;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

public class CommunityConfigsFragment extends Fragment {
    private RecyclerView recyclerView;
    private View llEmptyState;
    private TextView tvEmptyText;
    private View progressBar;
    private JSONArray fullGameList;
    private GameAdapter adapter;
    private JSONObject votesMap = new JSONObject();
    private ConfigAdapter configSelectionAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.community_configs_fragment, container, false);
        recyclerView = view.findViewById(R.id.RecyclerView);
        llEmptyState = view.findViewById(R.id.LLEmptyState);
        tvEmptyText = view.findViewById(R.id.TVEmptyText);
        progressBar = view.findViewById(R.id.ProgressBar);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
        
        if (getActivity() != null) {
            ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle("Community Configs");
        }
        
        ThemeManager.applyThemeToView(view, getContext());
        loadGames();
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        menu.add(0, 1, 0, "Refresh").setIcon(R.drawable.ic_nav_refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        
        MenuItem searchItem = menu.add(0, 2, 0, "Search");
        searchItem.setIcon(R.drawable.ic_nav_search);
        searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        
        SearchView searchView = new SearchView(getContext());
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filterGames(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterGames(newText);
                return true;
            }
        });
        searchItem.setActionView(searchView);
        
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            loadGames();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isNetworkAvailable() {
        Context context = getContext();
        if (context == null) return false;
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private void loadGames() {
        if (!isNetworkAvailable()) {
            progressBar.setVisibility(View.GONE);
            tvEmptyText.setText("No internet connection.\nPlease check your network settings.");
            llEmptyState.setVisibility(View.VISIBLE);
            return;
        }

        tvEmptyText.setText("Loading games...");
        llEmptyState.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        CommunityConfigManager.fetchGameList((games, maintenance) -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (maintenance != null) {
                    fullGameList = null;
                    recyclerView.setAdapter(null);
                    tvEmptyText.setText(maintenance);
                    llEmptyState.setVisibility(View.VISIBLE);
                } else if (games != null && games.length() > 0) {
                    fullGameList = mergeSameNamedGames(games);
                    adapter = new GameAdapter(fullGameList);
                    recyclerView.setAdapter(adapter);
                    llEmptyState.setVisibility(View.GONE);
                } else if (games != null && games.length() == 0) {
                    fullGameList = games;
                    recyclerView.setAdapter(null);
                    tvEmptyText.setText("No community configurations shared yet.\nBe the first to share your config from the Shortcuts menu!");
                    llEmptyState.setVisibility(View.VISIBLE);
                } else {
                    fullGameList = null;
                    recyclerView.setAdapter(null);
                    tvEmptyText.setText("Failed to retrieve games list.\nPlease check your connection or server status.");
                    llEmptyState.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void filterGames(String query) {
        if (fullGameList == null) return;
        if (query.isEmpty()) {
            llEmptyState.setVisibility(View.GONE);
            adapter.updateData(fullGameList);
            return;
        }

        JSONArray filtered = new JSONArray();
        String lowerQuery = query.toLowerCase();

        for (int i = 0; i < fullGameList.length(); i++) {
            try {
                JSONObject game = fullGameList.getJSONObject(i);
                String name = game.getString("name");
                if (name.toLowerCase().contains(lowerQuery)) {
                    filtered.put(game);
                }
            } catch (JSONException e) {}
        }

        if (filtered.length() == 0) {
            tvEmptyText.setText("No games found matching \"" + query + "\"");
            llEmptyState.setVisibility(View.VISIBLE);
        } else {
            llEmptyState.setVisibility(View.GONE);
        }
        adapter.updateData(filtered);
    }

    // The same game uploaded under two ids ("Black Flag" twice) is one card: counts summed, all its ids kept in "ids".
    private static JSONArray mergeSameNamedGames(JSONArray games) {
        java.util.LinkedHashMap<String, JSONObject> byName = new java.util.LinkedHashMap<>();
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.optJSONObject(i);
            if (game == null) continue;
            String key = game.optString("name", game.optString("id")).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
            try {
                JSONObject merged = byName.get(key);
                if (merged == null) {
                    merged = new JSONObject(game.toString());
                    merged.put("ids", new JSONArray().put(game.optString("id")));
                    byName.put(key, merged);
                    continue;
                }
                merged.getJSONArray("ids").put(game.optString("id"));
                for (String count : new String[]{"config_count", "adreno_count", "mali_count"})
                    merged.put(count, merged.optInt(count, 0) + game.optInt(count, 0));
                for (String art : new String[]{"community_image", "steam_id"})
                    if (merged.optString(art, "").isEmpty() && !game.optString(art, "").isEmpty()) merged.put(art, game.optString(art));
            } catch (JSONException ignored) {}
        }
        JSONArray result = new JSONArray();
        for (JSONObject game : byName.values()) result.put(game);
        return result;
    }

    // A merged card: the configs of all its ids in one list (each config keeps its own "game" id for download and votes).
    private void showConfigsForGames(String gameName, java.util.List<String> ids) {
        if (ids.size() <= 1) {
            showConfigsForGame(ids.isEmpty() ? gameName : ids.get(0));
            return;
        }
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) activity.preloaderDialog.show("Fetching Configurations...");
        JSONArray all = new JSONArray();
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(ids.size());
        for (String id : ids) {
            CommunityConfigManager.fetchConfigsForGame(id, (configs, maintenance) -> {
                synchronized (all) {
                    if (configs != null) for (int i = 0; i < configs.length(); i++) all.put(configs.opt(i));
                }
                if (pending.decrementAndGet() > 0 || activity == null) return;
                activity.runOnUiThread(() -> {
                    activity.preloaderDialog.close();
                    if (all.length() > 0) showConfigSelectionDialog(ids.get(0), all);
                    else Toast.makeText(getContext(), "No configurations found for this game.", Toast.LENGTH_SHORT).show();
                });
            });
        }
    }

    private void showConfigsForGame(String gameName) {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) activity.preloaderDialog.show("Fetching Configurations...");
        CommunityConfigManager.fetchConfigsForGame(gameName, (configs, maintenance) -> {
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                activity.preloaderDialog.close();
                if (maintenance != null) {
                    ContentDialog.alert(getContext(), maintenance, null);
                } else if (configs != null) {
                    if (configs.length() > 0) {
                        showConfigSelectionDialog(gameName, configs);
                    } else {
                        Toast.makeText(getContext(), "No configurations found for this game.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    ContentDialog.alert(getContext(), "Failed to fetch configurations. Please check your network connection.", null);
                }
            });
        });
    }

    private void showConfigSelectionDialog(String gameName, JSONArray configs) {
        ContentDialog dialog = new ContentDialog(getContext());
        dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        dialog.setTitle("Select Config");

        ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = com.winlator.cmod.core.AppUtils.getPreferredDialogWidth(getContext());
        listView.setVisibility(View.VISIBLE);

        configSelectionAdapter = new ConfigAdapter(getContext(), configs);
        listView.setAdapter(configSelectionAdapter);

        CommunityConfigManager.fetchVotes(votes -> {
            if (votes != null && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    votesMap = votes;
                    adapter.notifyDataSetChanged();
                });
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                JSONObject configRef = configs.getJSONObject(position);
                showConfigDetailsDialog(configRef.optString("game", gameName), configRef);
                dialog.dismiss();
            } catch (JSONException e) {}
        });

        dialog.show();
    }

    private void showConfigDetailsDialog(String gameName, JSONObject configRef) {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.community_config_details_dialog);
        dialog.setTitle("Configuration Details");
        
        try {
            String title = configRef.optString("config_title", "");
            String model = configRef.optString("device_model", "Unknown Device");
            String gpu = configRef.optString("gpu", "");
            String ram = configRef.optString("ram", "");
            String storage = configRef.optString("storage", "");
            String notes = configRef.optString("notes", "No notes provided.");
            long ts = configRef.optLong("timestamp", 0);

            TextView tvTitle = dialog.findViewById(R.id.TVDetailTitle);
            TextView tvHardware = dialog.findViewById(R.id.TVDetailHardware);
            TextView tvTime = dialog.findViewById(R.id.TVDetailTime);
            TextView tvNotes = dialog.findViewById(R.id.TVDetailNotes);

            tvTitle.setText(!title.isEmpty() ? title : model);
            
            StringBuilder hwInfo = new StringBuilder(model);
            if (!gpu.isEmpty()) hwInfo.append(" • ").append(gpu);
            if (!ram.isEmpty()) hwInfo.append(" • ").append(ram).append(" RAM");
            if (!storage.isEmpty()) hwInfo.append(" • ").append(storage).append(" Storage");
            tvHardware.setText(hwInfo.toString());
            
            if (ts > 0) {
                long now = System.currentTimeMillis() / 1000;
                long diff = now - ts;
                String timeText = diff < 86400 ? (diff / 3600) + "h ago" : (diff / 86400) + "d ago";
                tvTime.setText(timeText);
            }

            tvNotes.setText(notes);

            android.widget.Button btLike = dialog.findViewById(R.id.BTDetailLike);
            android.widget.Button btDislike = dialog.findViewById(R.id.BTDetailDislike);

            String filename = configRef.optString("filename", "");
            String configKey = gameName + "/" + filename;

            JSONObject vObj = votesMap.optJSONObject(configKey);
            final int[] counts = new int[]{
                vObj != null ? vObj.optInt("likes", 0) : 0,
                vObj != null ? vObj.optInt("dislikes", 0) : 0
            };

            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext());
            String prefKey = "cfg_vote_" + configKey;
            final int[] userVote = new int[]{ prefs.getInt(prefKey, 0) };

            int defaultTextColor = tvNotes.getCurrentTextColor();
            int colorLike = android.graphics.Color.parseColor("#4CAF50");
            int colorDislike = android.graphics.Color.parseColor("#F44336");

            Runnable updateVoteUi = () -> {
                if (userVote[0] == 1) {
                    btLike.setText("👍 Helpful (" + counts[0] + ") ✓");
                    btLike.setTextColor(colorLike);
                    btDislike.setText("👎 Didn't Work (" + counts[1] + ")");
                    btDislike.setTextColor(defaultTextColor);
                } else if (userVote[0] == -1) {
                    btLike.setText("👍 Helpful (" + counts[0] + ")");
                    btLike.setTextColor(defaultTextColor);
                    btDislike.setText("👎 Didn't Work (" + counts[1] + ") ✓");
                    btDislike.setTextColor(colorDislike);
                } else {
                    btLike.setText("👍 Helpful (" + counts[0] + ")");
                    btLike.setTextColor(defaultTextColor);
                    btDislike.setText("👎 Didn't Work (" + counts[1] + ")");
                    btDislike.setTextColor(defaultTextColor);
                }
            };
            updateVoteUi.run();

            final boolean[] isVoting = new boolean[]{false};

            btLike.setOnClickListener(v -> {
                if (isVoting[0]) return;
                isVoting[0] = true;
                btLike.postDelayed(() -> isVoting[0] = false, 600);

                int target = (userVote[0] == 1) ? 0 : 1;
                if (userVote[0] == 1) counts[0] = Math.max(0, counts[0] - 1);
                if (userVote[0] == -1) counts[1] = Math.max(0, counts[1] - 1);
                if (target == 1) counts[0]++;
                userVote[0] = target;
                prefs.edit().putInt(prefKey, target).apply();
                updateVoteUi.run();

                CommunityConfigManager.submitVote(getContext(), configKey, target, res -> {
                    if (res != null && getActivity() != null) {
                        counts[0] = res.optInt("likes", counts[0]);
                        counts[1] = res.optInt("dislikes", counts[1]);
                        userVote[0] = res.optInt("user_vote", userVote[0]);
                        getActivity().runOnUiThread(() -> {
                            updateVoteUi.run();
                            try {
                                JSONObject updated = new JSONObject();
                                updated.put("likes", counts[0]);
                                updated.put("dislikes", counts[1]);
                                votesMap.put(configKey, updated);
                                if (configSelectionAdapter != null) configSelectionAdapter.notifyDataSetChanged();
                            } catch (Exception ignored) {}
                        });
                    }
                });
            });

            btDislike.setOnClickListener(v -> {
                if (isVoting[0]) return;
                isVoting[0] = true;
                btDislike.postDelayed(() -> isVoting[0] = false, 600);

                int target = (userVote[0] == -1) ? 0 : -1;
                if (userVote[0] == 1) counts[0] = Math.max(0, counts[0] - 1);
                if (userVote[0] == -1) counts[1] = Math.max(0, counts[1] - 1);
                if (target == -1) counts[1]++;
                userVote[0] = target;
                prefs.edit().putInt(prefKey, target).apply();
                updateVoteUi.run();

                CommunityConfigManager.submitVote(getContext(), configKey, target, res -> {
                    if (res != null && getActivity() != null) {
                        counts[0] = res.optInt("likes", counts[0]);
                        counts[1] = res.optInt("dislikes", counts[1]);
                        userVote[0] = res.optInt("user_vote", userVote[0]);
                        getActivity().runOnUiThread(() -> {
                            updateVoteUi.run();
                            try {
                                JSONObject updated = new JSONObject();
                                updated.put("likes", counts[0]);
                                updated.put("dislikes", counts[1]);
                                votesMap.put(configKey, updated);
                                if (configSelectionAdapter != null) configSelectionAdapter.notifyDataSetChanged();
                            } catch (Exception ignored) {}
                        });
                    }
                });
            });

            dialog.setOnConfirmCallback(() -> {
                try {
                    downloadAndImportConfig(gameName, configRef.getString("filename"), notes);
                } catch (JSONException e) {}
            });
            
            ((TextView)dialog.findViewById(R.id.BTConfirm)).setText("IMPORT");
            dialog.show();
            
        } catch (Exception e) {}
    }

    private class ConfigAdapter extends android.widget.BaseAdapter {
        private final Context context;
        private final JSONArray data;

        public ConfigAdapter(Context context, JSONArray data) {
            this.context = context;
            this.data = data;
        }

        @Override public int getCount() { return data.length(); }
        @Override public Object getItem(int position) { return null; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.community_config_selection_item, parent, false);
            }

            try {
                JSONObject item = data.getJSONObject(position);
                TextView tvTitle = convertView.findViewById(R.id.TVConfigTitle);
                TextView tvModel = convertView.findViewById(R.id.TVDeviceModel);
                TextView tvGPU = convertView.findViewById(R.id.TVGPUInfo);
                TextView tvTime = convertView.findViewById(R.id.TVTimestamp);
                ImageView ivIcon = convertView.findViewById(R.id.IVDeviceIcon);

                String title = item.optString("config_title", "");
                String model = item.optString("device_model", "Unknown Device");
                String gpu = item.optString("gpu", "");
                long timestamp = item.optLong("timestamp", 0);

                if (!title.isEmpty()) {
                    tvTitle.setText(title);
                    tvTitle.setVisibility(View.VISIBLE);
                    tvModel.setText(model);
                    tvModel.setTextSize(13);
                    tvModel.setTypeface(null, android.graphics.Typeface.NORMAL);
                } else {
                    tvTitle.setVisibility(View.GONE);
                    tvModel.setText(model);
                    tvModel.setTextSize(15);
                    tvModel.setTypeface(null, android.graphics.Typeface.BOLD);
                }

                tvGPU.setText(gpu);
                tvGPU.setVisibility(gpu.isEmpty() ? View.GONE : View.VISIBLE);

                if (timestamp > 0) {
                    tvTime.setText(getRelativeTime(timestamp));
                    tvTime.setVisibility(View.VISIBLE);
                } else tvTime.setVisibility(View.GONE);

                String filename = item.optString("filename", "");
                String game = item.optString("game", "");
                String configKey = (game.isEmpty() ? "" : game + "/") + filename;
                JSONObject voteObj = votesMap.optJSONObject(configKey);
                View llRating = convertView.findViewById(R.id.LLRatingBadge);
                TextView tvLike = convertView.findViewById(R.id.TVLikeBadge);
                TextView tvDislike = convertView.findViewById(R.id.TVDislikeBadge);

                if (voteObj != null && llRating != null && tvLike != null && tvDislike != null) {
                    int likes = voteObj.optInt("likes", 0);
                    int dislikes = voteObj.optInt("dislikes", 0);
                    if (likes > 0 || dislikes > 0) {
                        tvLike.setText("👍 " + likes);
                        tvDislike.setText("👎 " + dislikes);
                        llRating.setVisibility(View.VISIBLE);
                    } else {
                        llRating.setVisibility(View.GONE);
                    }
                } else if (llRating != null) {
                    llRating.setVisibility(View.GONE);
                }

                ivIcon.setImageResource(android.R.drawable.ic_menu_info_details);
                boolean isDarkMode = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean("dark_mode", true);
                ivIcon.setColorFilter(isDarkMode ? android.graphics.Color.WHITE : android.graphics.Color.BLACK);

            } catch (JSONException e) {}

            return convertView;
        }

        private String getRelativeTime(long timestampSeconds) {
            long now = System.currentTimeMillis() / 1000;
            long diff = now - timestampSeconds;
            if (diff < 60) return "just now";
            if (diff < 3600) return (diff / 60) + "m ago";
            if (diff < 86400) return (diff / 3600) + "h ago";
            return (diff / 86400) + "d ago";
        }
    }

    private void downloadAndImportConfig(String gameName, String filename, String notes) {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) activity.preloaderDialog.show("Downloading Configuration...");
        CommunityConfigManager.downloadConfig(gameName, filename, (root, maintenance) -> {
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                activity.preloaderDialog.close();
                if (maintenance != null) {
                    ContentDialog.alert(getContext(), maintenance, null);
                } else if (root != null) {
                    checkWineAndConfirmImport(activity, root, notes);
                } else {
                    ContentDialog.alert(getContext(), "Failed to download configuration. Please check your internet connection.", null);
                }
            });
        });
    }

    private void checkWineAndConfirmImport(MainActivity activity, JSONObject root, String notes) {
        try {
            JSONObject container = root.getJSONObject("container");
            String gameName = root.getJSONObject("meta").getString("game_name");
            String requiredWine = container.optString("wineVersion", "Default");

            List<String> installed = WineDownloader.getInstalledWineVersions(activity);
            boolean isInstalled = false;
            for (String w : installed) {
                if (w.equalsIgnoreCase(requiredWine)) {
                    isInstalled = true;
                    break;
                }
            }

            if (!isInstalled) {
                // The recommended runtime is not installed!
                ContentDialog dialog = new ContentDialog(activity);
                dialog.setTitle("Wine Runtime Notice");

                dialog.getContentView().setMinimumWidth((int) com.winlator.cmod.core.UnitUtils.dpToPx(330));
                dialog.setCanceledOnTouchOutside(true);

                if (installed.isEmpty()) {
                    String msg = "This configuration recommends:<br><br>" +
                            "<b><font color='#4CAF50'>" + requiredWine + "</font></b><br><br>" +
                            "No Wine or Proton runtimes are installed on your device yet.<br><br>" +
                            "Please download <b>" + requiredWine + "</b> from Contents to use this setup.";
                    dialog.setMessage(msg);
                    TextView btConfirm = dialog.findViewById(R.id.BTConfirm);
                    btConfirm.setText("DOWNLOAD");
                    dialog.setOnConfirmCallback(() -> {
                        WineDownloader.showDownloadDialog(activity, () -> {
                            checkWineAndConfirmImport(activity, root, notes);
                        });
                    });
                } else {
                    String fallbackWine = installed.get(0);
                    boolean isArm64EC = requiredWine.toLowerCase(Locale.ENGLISH).contains("arm64ec");
                    for (String w : installed) {
                        if (isArm64EC && w.toLowerCase(Locale.ENGLISH).contains("arm64ec")) {
                            fallbackWine = w;
                            break;
                        } else if (!isArm64EC && !w.toLowerCase(Locale.ENGLISH).contains("arm64ec")) {
                            fallbackWine = w;
                            break;
                        }
                    }
                    final String chosenFallback = fallbackWine;
                    String msg = "This configuration recommends:<br><br>" +
                            "<b><font color='#4CAF50'>" + requiredWine + "</font></b><br><br>" +
                            "It is not currently installed on your device.<br><br>" +
                            (installed.size() > 1
                                    ? "Would you like to download <b>" + requiredWine + "</b> now, or select one of your installed runtimes?"
                                    : "Would you like to download <b>" + requiredWine + "</b> now, or continue using your installed runtime (<b>" + chosenFallback + "</b>)?");
                    dialog.setMessage(msg);

                    int padH = (int) com.winlator.cmod.core.UnitUtils.dpToPx(12);
                    TextView btConfirm = dialog.findViewById(R.id.BTConfirm);
                    btConfirm.setText("DOWNLOAD");
                    btConfirm.setPadding(padH, 0, padH, 0);

                    TextView btCancel = dialog.findViewById(R.id.BTCancel);
                    btCancel.setText("USE INSTALLED");
                    btCancel.setPadding(padH, 0, padH, 0);

                    dialog.setOnConfirmCallback(() -> {
                        WineDownloader.showDownloadDialog(activity, () -> {
                            checkWineAndConfirmImport(activity, root, notes);
                        });
                    });
                    dialog.setOnCancelCallback(() -> {
                        if (installed.size() > 1) {
                            ContentDialog.showSingleChoiceList(activity, "Select Wine Runtime", installed.toArray(new String[0]), position -> {
                                try {
                                    String selectedWine = installed.get(position);
                                    root.getJSONObject("container").put("wineVersion", selectedWine);
                                } catch (Exception ignored) {}
                                executeImport(activity, root);
                            });
                        } else if (!installed.isEmpty()) {
                            try {
                                root.getJSONObject("container").put("wineVersion", installed.get(0));
                            } catch (Exception ignored) {}
                            executeImport(activity, root);
                        } else {
                            executeImport(activity, root);
                        }
                    });
                }
                dialog.show();
            } else {
                // Wine is installed! Show standard confirmation
                String confirmMsg = "Import configuration for <b>" + gameName + "</b>?<br><br>" +
                        "Wine runtime: <b>" + requiredWine + "</b>";
                if (notes != null && !notes.isEmpty()) {
                    confirmMsg += "<br><br><b>Notes:</b><br>" + notes.replace("\n", "<br>");
                }

                ContentDialog dialog = new ContentDialog(activity);
                dialog.setTitle("Import Configuration");
                dialog.setMessage(confirmMsg);
                dialog.setOnConfirmCallback(() -> executeImport(activity, root));
                dialog.show();
            }
        } catch (Exception e) {
            executeImport(activity, root);
        }
    }

    private void executeImport(MainActivity activity, JSONObject root) {
        activity.preloaderDialog.show("Importing Configuration...");
        CommunityConfigUtils.importConfig(getContext(), root, activity.getContainerManager(), success -> {
            activity.runOnUiThread(() -> {
                activity.preloaderDialog.close();
                if (success) {
                    try {
                        JSONObject container = root.getJSONObject("container");
                        String wine = container.optString("wineVersion", "Default");
                        String driver = container.optString("graphicsDriver", "Default");
                        String dx = container.optString("dxwrapper", "Default");

                        String message = "<b>Imported successfully!</b><br><br>" +
                                "A new container has been created with community settings.<br><br>" +
                                "<b>Imported Setup:</b><br>" +
                                "• Wine: " + wine + "<br>" +
                                "• GPU Driver: " + driver + "<br>" +
                                "• DX Wrapper: " + dx + "<br><br>" +
                                "<b>How to use:</b><br>" +
                                "1. Go to <b>Containers</b>.<br>" +
                                "2. Run <b>[Community] " + root.getJSONObject("meta").getString("game_name") + "</b>.<br>" +
                                "3. Find your game EXE in Drive D: and play!";
                        ContentDialog.alert(getContext(), message, null);
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "Imported with minor errors.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(), "Import failed.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private class GameAdapter extends RecyclerView.Adapter<GameAdapter.ViewHolder> {
        private JSONArray games;
        public GameAdapter(JSONArray games) { this.games = games; }

        public void updateData(JSONArray newGames) {
            this.games = newGames;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.community_game_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                Object item = games.get(position);
                final String gameId;
                final String displayName;
                final String steamId;
                final int configCount;

                if (item instanceof JSONObject) {
                    JSONObject game = (JSONObject) item;
                    gameId = game.getString("id");
                    displayName = game.getString("name");
                    steamId = game.optString("steam_id", "");
                    configCount = game.optInt("config_count", 0);
                } else {
                    gameId = games.getString(position);
                    displayName = gameId.replace("_", " ").toUpperCase();
                    steamId = "";
                    configCount = 0;
                }

                holder.tvGameName.setText(displayName);
                // Cards on the preset's surface color, so they stand out from the page on every theme.
                if (holder.itemView instanceof androidx.cardview.widget.CardView && getContext() != null) {
                    ((androidx.cardview.widget.CardView) holder.itemView).setCardBackgroundColor(ThemeManager.getSurfaceColor(getContext()));
                    holder.tvGameName.setTextColor(ThemeManager.getOnSurfaceTextColor(getContext()));
                }

                if (configCount > 0) {
                    holder.tvConfigCount.setText(configCount + (configCount == 1 ? " CONFIG" : " CONFIGS"));
                    holder.tvConfigCount.setVisibility(View.VISIBLE);
                    
                    int adrenoCount = item instanceof JSONObject ? ((JSONObject)item).optInt("adreno_count", 0) : 0;
                    int maliCount = item instanceof JSONObject ? ((JSONObject)item).optInt("mali_count", 0) : 0;
                    
                    if (adrenoCount > 0) {
                        holder.tvAdrenoCount.setText("Adreno " + adrenoCount);
                        holder.tvAdrenoCount.setVisibility(View.VISIBLE);
                    } else holder.tvAdrenoCount.setVisibility(View.GONE);

                    if (maliCount > 0) {
                        holder.tvMaliCount.setText("Mali " + maliCount);
                        holder.tvMaliCount.setVisibility(View.VISIBLE);
                    } else holder.tvMaliCount.setVisibility(View.GONE);
                    
                } else {
                    holder.tvConfigCount.setVisibility(View.GONE);
                    holder.tvAdrenoCount.setVisibility(View.GONE);
                    holder.tvMaliCount.setVisibility(View.GONE);
                }

                String customImageUrl = item instanceof JSONObject ? ((JSONObject)item).optString("community_image", "") : "";

                if (!customImageUrl.isEmpty()) {
                    holder.tvSteamId.setVisibility(View.GONE);
                    Glide.with(holder.itemView.getContext())
                        .load(customImageUrl)
                        .placeholder(R.drawable.cover_art_placeholder)
                        .centerCrop()
                        .into(holder.ivCoverArt);
                } else if (!steamId.isEmpty()) {
                    holder.tvSteamId.setText("Steam ID: " + steamId);
                    holder.tvSteamId.setVisibility(View.VISIBLE);
                    
                    String imageUrl = "https://cdn.akamai.steamstatic.com/steam/apps/" + steamId + "/header.jpg";
                    Glide.with(holder.itemView.getContext())
                        .load(imageUrl)
                        .placeholder(R.drawable.cover_art_placeholder)
                        .centerCrop()
                        .into(holder.ivCoverArt);
                } else {
                    holder.tvSteamId.setVisibility(View.GONE);
                    holder.ivCoverArt.setImageResource(R.drawable.cover_art_placeholder);
                }

                java.util.List<String> ids = new java.util.ArrayList<>();
                JSONArray idArray = item instanceof JSONObject ? ((JSONObject) item).optJSONArray("ids") : null;
                if (idArray != null) for (int i = 0; i < idArray.length(); i++) ids.add(idArray.optString(i));
                if (ids.isEmpty()) ids.add(gameId);
                holder.itemView.setOnClickListener(v -> showConfigsForGames(displayName, ids));
            } catch (JSONException e) { holder.tvGameName.setText("Error"); }
        }

        @Override public int getItemCount() { return games.length(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivCoverArt;
            TextView tvGameName;
            TextView tvSteamId;
            TextView tvConfigCount;
            TextView tvAdrenoCount;
            TextView tvMaliCount;
            ViewHolder(View itemView) {
                super(itemView);
                ivCoverArt = itemView.findViewById(R.id.IVCoverArt);
                tvGameName = itemView.findViewById(R.id.TVGameName);
                tvSteamId = itemView.findViewById(R.id.TVSteamId);
                tvConfigCount = itemView.findViewById(R.id.TVConfigCount);
                tvAdrenoCount = itemView.findViewById(R.id.TVAdrenoCount);
                tvMaliCount = itemView.findViewById(R.id.TVMaliCount);
            }
        }
    }
}
