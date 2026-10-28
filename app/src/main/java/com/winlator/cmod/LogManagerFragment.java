package com.winlator.cmod;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.ArrayUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.logs.LogFiles;
import com.winlator.cmod.logs.SessionLog;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Log Manager, after Bannerlator's: where logs go, what is recorded (each switch explained), housekeeping, and the
 * logs on disk per game with a viewer (find, wrap, copy), share and delete. Opened from Settings › Advanced, and from
 * "View logs" on a shortcut or container, which opens that game's logs directly ({@link #ARG_GAME}).
 */
public class LogManagerFragment extends Fragment {
    public static final String ARG_GAME = "game";
    private static final int REQUEST_CUSTOM_FOLDER = 4711;
    private static final int[] KEEP_OPTIONS = {0, 1, 3, 5, 10, 20};
    private static final long VIEW_TAIL_BYTES = 512 * 1024;
    private static final String[] COMMON_WINE_CHANNELS = {"err", "warn", "fixme", "seh", "module", "loaddll", "process", "thread",
        "sync", "file", "reg", "heap", "ntdll", "d3d", "dxgi", "vulkan", "opengl", "relay"};

    private SharedPreferences prefs;
    private LinearLayout content;
    private int onSurface, muted, accent, surface;

    public static LogManagerFragment forGame(String gameName) {
        LogManagerFragment fragment = new LogManagerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_GAME, gameName);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.log_manager_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = requireContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        onSurface = ThemeManager.getOnSurfaceTextColor(context);
        muted = (onSurface & 0x00FFFFFF) | 0xA6000000;
        accent = ThemeManager.getAccentColor(context);
        surface = ThemeManager.getSurfaceColor(context);
        content = view.findViewById(R.id.LLLogContent);
        if (getActivity() instanceof AppCompatActivity && ((AppCompatActivity) getActivity()).getSupportActionBar() != null)
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle("Log Manager");
        build();

        String game = getArguments() != null ? getArguments().getString(ARG_GAME) : null;
        if (game != null && savedInstanceState == null) {
            LogFiles.Group group = LogFiles.forGame(context, game);
            if (group != null) showGroup(group);
            else toast(LogFiles.isOn(context, LogFiles.PREF_PER_GAME)
                ? "No logs for " + game + " yet. Turn on what to record below, then play it."
                : "\"Folder for each game\" is off, so logs aren't kept per game.");
        }
    }

    private void build() {
        Context context = requireContext();
        content.removeAllViews();
        boolean enabled = LogFiles.isOn(context, LogFiles.PREF_ENABLED);

        // Master switch
        LinearLayout master = card();
        master.setPadding(dp(14), dp(14), dp(14), dp(14));
        toggle(master, "Logs", enabled ? "On — the switches below choose what is recorded" : "Off — nothing is recorded at all",
            LogFiles.PREF_ENABLED, INFO_MASTER, null);

        // Where logs go
        section("Where logs go");
        LinearLayout where = card();
        String mode = prefs.getString(LogFiles.PREF_LOCATION, LogFiles.LOCATION_DOCUMENTS);
        String modeLabel = LogFiles.LOCATION_DOWNLOAD.equals(mode) ? "Download" : LogFiles.LOCATION_CUSTOM.equals(mode) ? "Custom folder" : "Documents";
        pickRow(where, "Save logs to", modeLabel, LogFiles.root(context).getAbsolutePath(), () ->
            ContentDialog.showSingleChoiceList(context, "Save logs to", new String[]{"Documents", "Download", "Choose folder…"}, index -> {
                if (index == 2) {
                    try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CUSTOM_FOLDER); }
                    catch (Exception e) { toast("No folder picker on this device."); }
                    return;
                }
                prefs.edit().putString(LogFiles.PREF_LOCATION, index == 1 ? LogFiles.LOCATION_DOWNLOAD : LogFiles.LOCATION_DOCUMENTS).apply();
                build();
            }));
        toggle(where, "Folder for each game", "One folder per shortcut or container", LogFiles.PREF_PER_GAME, INFO_PER_GAME, null);

        // What to record
        section("What to record");
        LinearLayout what = card();
        if (!enabled) what.setAlpha(0.45f);
        toggle(what, "Wine debug", "Slows games down — for diagnosing a problem", LogFiles.PREF_WINE, INFO_WINE, null);
        wineChannels(what);
        toggle(what, "Box64 / FEXCore", "Slows games down at higher verbosity", LogFiles.PREF_BOX64, INFO_BOX64, null);
        toggle(what, "DXVK & VKD3D", "Safe to leave on — mostly written at startup", LogFiles.PREF_DXVK, INFO_DXVK, null);
        toggle(what, "Android logcat", "Saved when a game exits — no cost while playing", LogFiles.PREF_LOGCAT, INFO_LOGCAT, null);
        toggle(what, "Crash reports", "Saved automatically if the app crashes", LogFiles.PREF_CRASH, INFO_CRASH, null);
        toggle(what, "Exit reasons (auto-save on launch)", "Android's record of why the app last closed", LogFiles.PREF_EXIT_AUTOSAVE, INFO_EXIT,
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ? null : "Needs Android 11 or newer");

        // Housekeeping
        section("Housekeeping");
        LinearLayout house = card();
        int keep = LogFiles.keepRuns(context);
        pickRow(house, "Keep last", keep == 0 ? "Latest run only" : keep + " past runs per game", "Older runs are deleted when a game starts", () -> {
            String[] labels = new String[KEEP_OPTIONS.length];
            for (int i = 0; i < KEEP_OPTIONS.length; i++) labels[i] = KEEP_OPTIONS[i] == 0 ? "Latest run only" : KEEP_OPTIONS[i] + " past runs";
            ContentDialog.showSingleChoiceList(context, "Keep last", labels, index -> {
                prefs.edit().putInt(LogFiles.PREF_KEEP_RUNS, KEEP_OPTIONS[index]).apply();
                build();
            });
        });
        List<LogFiles.Group> groups = LogFiles.scan(context);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(action("Logcat now", () -> runInBackground("Capturing logcat…", () -> {
            File out = SessionLog.captureAppLogcat(context);
            return () -> { build(); toast("Saved " + out.getName()); };
        })), weight());
        actions.addView(action("Exit reasons", () -> runInBackground("Reading Android's records…", () -> {
            String text = SessionLog.exitReasons(context, true);
            return () -> { build(); showText("Last crashes / exit reasons", text, null); };
        })), weight());
        actions.addView(action("Clear all", () -> {
            if (groups.isEmpty()) { toast("No logs to delete."); return; }
            ContentDialog.confirm(context, "Delete all logs (" + LogFiles.humanBytes(LogFiles.totalBytes(groups)) + ")?\n\n"
                + "Only files WinlatorMali wrote are removed. Anything else in your log folder is left alone.", () -> {
                for (LogFiles.Group g : groups) LogFiles.deleteGroup(context, g);
                build();
            });
        }), weight());
        house.addView(actions);

        // Logs by game
        section("Logs by game  ·  " + LogFiles.humanBytes(LogFiles.totalBytes(groups)));
        if (groups.isEmpty()) {
            LinearLayout empty = card();
            TextView tv = text("No logs yet.\n\nTurn on what to record above, then play a game: its logs appear here.", 13.5f, muted);
            tv.setGravity(Gravity.CENTER);
            empty.addView(tv);
        }
        for (LogFiles.Group g : groups) {
            LinearLayout row = card();
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showGroup(g));
            TextView title = text(g.isApp() ? "App logs (logcat, crash reports)" : g.name, 15.5f, onSurface);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title);
            row.addView(text((g.fileCount > 0 ? g.fileCount + (g.fileCount == 1 ? " file" : " files") : "no current files")
                + (g.archivedRuns > 0 ? " · " + g.archivedRuns + " past run" + (g.archivedRuns == 1 ? "" : "s") : "")
                + " · " + LogFiles.humanBytes(g.bytes)
                + " · " + DateUtils.getRelativeTimeSpanString(g.lastModified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), 12.5f, muted));
        }

        LinearLayout note = new LinearLayout(context);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        note.addView(text("E-mail addresses, tokens and keys are removed as logs are written. File paths are kept so the logs stay "
            + "useful — glance over them before sharing if a folder name identifies you. Logcat holds this app's output only.", 11.5f, muted));
        content.addView(note);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (content != null) build();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CUSTOM_FOLDER || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        String path = FileUtils.getFilePathFromUri(requireContext(), data.getData());
        if (path == null || !new File(path).canWrite()) {
            toast("That folder can't be written to. Pick another one.");
            return;
        }
        prefs.edit().putString(LogFiles.PREF_LOCATION, LogFiles.LOCATION_CUSTOM).putString(LogFiles.PREF_CUSTOM_PATH, path).apply();
        build();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Wine debug channels

    private void wineChannels(LinearLayout parent) {
        Context context = requireContext();
        List<String> channels = new ArrayList<>(Arrays.asList(prefs.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS).split(",")));
        channels.removeIf(String::isEmpty);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), 0, 0, dp(6));
        box.addView(text("Channels", 11.5f, accent));
        HorizontalScrollView hs = new HorizontalScrollView(context);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(4), 0, 0);
        for (String channel : channels) {
            TextView chip = chip(channel + "  ×", true);
            chip.setOnClickListener(v -> {
                channels.remove(channel);
                saveChannels(channels);
            });
            chips.addView(chip);
        }
        TextView add = chip("+ Add", false);
        add.setOnClickListener(v -> {
            List<String> options = new ArrayList<>();
            for (String c : COMMON_WINE_CHANNELS) if (!channels.contains(c)) options.add(c);
            options.add("All channels…");
            ContentDialog.showSingleChoiceList(context, "Add a channel", options.toArray(new String[0]), index -> {
                if (index == options.size() - 1) {
                    String[] all;
                    try { all = ArrayUtils.toStringArray(new JSONArray(FileUtils.readString(context, "wine_debug_channels.json"))); }
                    catch (Exception e) { all = new String[0]; }
                    final String[] items = all;
                    ContentDialog.showMultipleChoiceList(context, R.string.wine_debug_channel, items, selected -> {
                        for (int i : selected) if (!channels.contains(items[i])) channels.add(items[i]);
                        saveChannels(channels);
                    });
                }
                else {
                    channels.add(options.get(index));
                    saveChannels(channels);
                }
            });
        });
        chips.addView(add);
        TextView reset = chip("Reset", false);
        reset.setOnClickListener(v -> saveChannels(new ArrayList<>(Arrays.asList(SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS.split(",")))));
        chips.addView(reset);
        hs.addView(chips);
        box.addView(hs);
        parent.addView(box);
    }

    private void saveChannels(List<String> channels) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(channels));
        if (unique.isEmpty()) prefs.edit().remove("wine_debug_channels").apply();
        else prefs.edit().putString("wine_debug_channels", TextUtils.join(",", unique)).apply();
        build();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A game's logs

    /** A game's runs and their files; pick a file to view it, or share / delete them all. */
    private void showGroup(LogFiles.Group group) {
        Context context = requireContext();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (LogFiles.Run run : LogFiles.runs(group.dir)) {
            String when = run.current ? "Latest run" : DateUtils.formatDateTime(context, run.millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
            for (File f : LogFiles.filesIn(run.dir)) {
                labels.add(when + "  ·  " + f.getName() + "  (" + LogFiles.humanBytes(f.length()) + ")");
                actions.add(() -> viewFile(f));
            }
        }
        String what = group.isApp() ? "the app logs" : group.name;
        labels.add("Share all of " + what + " (.zip)");
        actions.add(() -> shareZip(group.name, group.dir));
        labels.add("Delete all of " + what);
        actions.add(() -> ContentDialog.confirm(context, "Delete " + LogFiles.humanBytes(group.bytes) + " of logs of " + what + "?\n\nOnly files WinlatorMali wrote are removed.", () -> {
            LogFiles.deleteGroup(context, group);
            build();
        }));
        ContentDialog.showSingleChoiceList(context, group.isApp() ? "App logs" : group.name, labels.toArray(new String[0]), index -> {
            if (index >= 0 && index < actions.size()) actions.get(index).run();
        });
    }

    /** The end of a log (the last 512 KB, where a crash is) in the viewer. */
    private void viewFile(File file) {
        runInBackground("Opening " + file.getName() + "…", () -> {
            String text;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long length = raf.length();
                long start = Math.max(0, length - VIEW_TAIL_BYTES);
                byte[] data = new byte[(int) (length - start)];
                raf.seek(start);
                raf.readFully(data);
                text = (start > 0 ? "… showing the last " + LogFiles.humanBytes(VIEW_TAIL_BYTES) + " of " + LogFiles.humanBytes(length) + "\n\n" : "")
                    + new String(data, java.nio.charset.StandardCharsets.UTF_8);
            }
            catch (Exception e) {
                text = "Could not read " + file.getName() + ": " + e.getMessage();
            }
            final String body = text;
            return () -> showText(file.getName(), body, file);
        });
    }

    /** Monospace viewer with find (next match highlighted), wrap, copy and share. */
    private void showText(String title, String body, @Nullable File file) {
        Context context = requireContext();
        TextView tv = text(body, 10.5f, onSurface);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        HorizontalScrollView hs = new HorizontalScrollView(context);
        hs.addView(tv);
        ScrollView sv = new ScrollView(context);
        sv.addView(hs);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        EditText find = new EditText(context);
        find.setHint("Find");
        find.setSingleLine(true);
        find.setTextSize(13);
        find.setTextColor(onSurface);
        find.setHintTextColor(muted);
        bar.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final int[] from = {0};
        TextView next = chip("Next", false);
        next.setOnClickListener(v -> {
            String query = find.getText().toString();
            if (query.isEmpty()) return;
            String lower = body.toLowerCase(Locale.ROOT);
            int at = lower.indexOf(query.toLowerCase(Locale.ROOT), from[0]);
            if (at < 0) at = lower.indexOf(query.toLowerCase(Locale.ROOT));
            if (at < 0) { toast("Not found"); return; }
            from[0] = at + query.length();
            SpannableString span = new SpannableString(body);
            span.setSpan(new BackgroundColorSpan((accent & 0x00FFFFFF) | 0x99000000), at, at + query.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(span);
            final int match = at;
            tv.post(() -> {
                if (tv.getLayout() == null) return;
                int line = tv.getLayout().getLineForOffset(match);
                sv.smoothScrollTo(0, Math.max(0, tv.getLayout().getLineTop(line) - dp(60)));
                hs.smoothScrollTo((int) tv.getLayout().getPrimaryHorizontal(match) - dp(40), 0);
            });
        });
        bar.addView(next);
        final boolean[] wrapped = {false};
        TextView wrap = chip("Wrap", false);
        wrap.setOnClickListener(v -> {
            wrapped[0] = !wrapped[0];
            if (tv.getParent() instanceof ViewGroup) ((ViewGroup) tv.getParent()).removeView(tv);
            sv.removeAllViews();
            if (wrapped[0]) sv.addView(tv);
            else { hs.addView(tv); sv.addView(hs); }
            wrap.setText(wrapped[0] ? "No wrap" : "Wrap");
        });
        bar.addView(wrap);
        TextView copy = chip("Copy", false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(title, body));
            toast("Copied");
        });
        bar.addView(copy);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(bar);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (context.getResources().getDisplayMetrics().heightPixels * 0.55f)));

        ContentDialog dialog = new ContentDialog(context);
        dialog.setTitle(title);
        ViewGroup slot = dialog.getContentView().findViewById(R.id.FrameLayout);
        slot.addView(panel);
        slot.setVisibility(View.VISIBLE);
        if (file != null) dialog.setOnConfirmCallback(() -> share(file, "text/plain"));
        dialog.show();
        TextView confirm = dialog.findViewById(R.id.BTConfirm);
        if (confirm != null) {
            if (file != null) confirm.setText("Share");
            else confirm.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sharing

    /** Zip a game's (or the app's) logs, all kept runs, and share the zip. */
    private void shareZip(String name, File dir) {
        Context context = requireContext();
        runInBackground("Packing the logs…", () -> {
            File zip = new File(context.getCacheDir(), "logs/" + LogFiles.folderName(name) + "_logs_" + LogFiles.timestamp(System.currentTimeMillis()) + ".zip");
            zip.getParentFile().mkdirs();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
                for (LogFiles.Run run : LogFiles.runs(dir)) {
                    String prefix = run.current ? "" : LogFiles.ARCHIVE_DIR + "/" + run.dir.getName() + "/";
                    for (File f : LogFiles.filesIn(run.dir)) {
                        zos.putNextEntry(new ZipEntry(prefix + f.getName()));
                        try (FileInputStream in = new FileInputStream(f)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                        }
                        zos.closeEntry();
                    }
                }
            }
            catch (Exception e) {
                return () -> toast("Could not pack the logs: " + e.getMessage());
            }
            return () -> share(zip, "application/zip");
        });
    }

    private void share(File file, String mime) {
        Context context = getContext();
        if (context == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".tileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "WinlatorMali log: " + file.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share log"));
        }
        catch (Exception e) {
            toast("Could not share: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Building blocks (all colors from the theme preset)

    private void section(String title) {
        TextView tv = text(title, 13.5f, accent);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(4), dp(16), dp(4), dp(6));
        content.addView(tv);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(surface);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        content.addView(card, lp);
        return card;
    }

    /** A switch with its one-line hint and an ⓘ for the full explanation; {@code disabledWhy} greys it out. */
    private void toggle(LinearLayout parent, String title, String hint, String pref, String info, @Nullable String disabledWhy) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 14.5f, onSurface));
        texts.addView(text(disabledWhy != null ? disabledWhy : hint, 11.5f, muted));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView infoButton = new ImageView(context);
        infoButton.setImageResource(R.drawable.icon_about);
        infoButton.setColorFilter(muted);
        infoButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        infoButton.setOnClickListener(v -> showText(title, info, null));
        row.addView(infoButton, new LinearLayout.LayoutParams(dp(36), dp(36)));
        CheckBox box = new CheckBox(context);
        box.setChecked(LogFiles.PREF_ENABLED.equals(pref) ? LogFiles.isOn(context, pref) : LogFiles.savedValue(context, pref));
        box.setEnabled(disabledWhy == null);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        box.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(pref, checked).apply();
            if (LogFiles.PREF_ENABLED.equals(pref)) content.post(this::build);
        });
        row.addView(box);
        row.setOnClickListener(v -> { if (box.isEnabled()) box.toggle(); });
        if (disabledWhy != null) row.setAlpha(0.5f);
        parent.addView(row);
    }

    /** A setting that opens a choice: title, current value (accent), and a detail line. */
    private void pickRow(LinearLayout parent, String title, String value, String detail, Runnable onClick) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(text(title, 14.5f, onSurface), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = text(value + "  ▾", 14f, accent);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(v);
        row.addView(top);
        row.addView(text(detail, 11.5f, muted));
        parent.addView(row);
    }

    private TextView action(String label, Runnable onClick) {
        TextView tv = chip(label, false);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private TextView chip(String label, boolean filled) {
        TextView tv = text(label, 12.5f, filled ? onSurface : accent);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (filled) bg.setColor((accent & 0x00FFFFFF) | 0x40000000);
        else bg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x99000000);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private TextView text(String s, float sp, int color) {
        TextView tv = new TextView(requireContext());
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private interface Work {
        Runnable run();
    }

    /** Disk and process work off the main thread, with the app's loading dialog; the result runs on the UI thread. */
    private void runInBackground(String message, Work work) {
        MainActivity activity = getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
        if (activity != null) activity.preloaderDialog.show(message);
        Executors.newSingleThreadExecutor().execute(() -> {
            Runnable onDone;
            try { onDone = work.run(); }
            catch (Exception e) { onDone = () -> toast(e.getMessage()); }
            final Runnable done = onDone;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (activity != null) activity.preloaderDialog.close();
                if (isAdded() && done != null) done.run();
            });
        });
    }

    private void toast(String text) {
        if (getContext() != null) Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What each switch does (after Bannerlator's LogCopy)

    private static final String INFO_MASTER = "One switch for all logging.\n\nOff, nothing at all is recorded: no Wine or "
        + "Box64 / FEXCore output, no DXVK or VKD3D logs (they are kept silent), no logcat, no crash reports and no exit reasons, "
        + "and no folders are created. Your choices below are kept and come back when you turn it on again.\n\nLogs already "
        + "on disk stay listed here, so you can still read, share or delete them.";
    private static final String INFO_PER_GAME ="No performance cost.\n\nGives each game its own folder inside your log location, named "
        + "after the shortcut (or the container, when you start one without a shortcut), so one game's logs don't get mixed up with "
        + "another's. Each folder keeps the last runs under \"previous\".\n\nTurn it off to keep everything in one folder; past runs "
        + "are then not kept. Logs already written are left where they are.";
    private static final String INFO_WINE = "⚠️ SLOWS GAMES DOWN. Only turn this on to diagnose a problem, then turn it back off.\n\n"
        + "Records what Wine itself is doing, as wine_debug.log in the game's folder. Every message is written as the game runs, and "
        + "Wine produces a lot of them — the \"seh\" channel alone logs an entry every time the game raises an exception. Heavier "
        + "channels such as \"relay\" can make a game many times slower.\n\nThis is the log to enable when a game won't start, crashes, "
        + "or dies at launch. The channels below choose what Wine reports.";
    private static final String INFO_BOX64 = "⚠️ SLOWS GAMES DOWN at higher verbosity.\n\nRecords what the x86 translator (Box64 for "
        + "x86_64 Wine, FEXCore or WOWBox64 for arm64ec) is doing while it runs the game's PC code, into the same wine_debug.log. Useful "
        + "for a game that crashes in a way Wine's own log doesn't explain, or misbehaves under only one translator.\n\nLeave off for "
        + "normal play.";
    private static final String INFO_DXVK = "Little to no performance cost.\n\nRecords what the graphics layers report — DXVK for "
        + "DirectX 8/9/10/11 (<game>_d3d11.log, <game>_dxgi.log…), VKD3D for DirectX 12 (vkd3d-proton.log). Almost everything is "
        + "written while the game starts: which GPU and driver were picked, which features are missing, why a shader failed.\n\nThe "
        + "log to check for black screens, missing textures or a refused graphics feature. Safe to leave on. Off, DXVK and VKD3D are "
        + "kept silent.";
    private static final String INFO_LOGCAT = "No performance cost.\n\nAndroid's own log, as it relates to WinlatorMali: the renderer, "
        + "the X server, input and audio. It is saved once, when a game exits (logcat.log in its folder), or when you tap \"Capture "
        + "logcat\" — never continuously, so it costs no frames.\n\nIt contains THIS APP'S OUTPUT ONLY: Android doesn't let an app read "
        + "other apps' logs, and nothing system-wide is captured, so other apps' data never ends up in a file you share.";
    private static final String INFO_CRASH = "No performance cost.\n\nIf the app itself crashes, the error and the moments before it are "
        + "saved as crash_<time>.txt in the app logs, before Android closes it. Send that file when you report a crash.";
    private static final String INFO_EXIT = "No performance cost.\n\nAndroid keeps a short record of why the app's last processes ended: "
        + "a crash, a native crash, \"not responding\", killed for low memory, or closed normally. With this on, a new record is saved "
        + "to the app logs each time the app starts — useful when a game closes with no error at all, which is usually Android "
        + "killing it for memory. \"Exit reasons\" below shows the records any time.";
}
