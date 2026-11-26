package com.winlator.cmod.saves;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.winlator.cmod.FileManagerFragment;
import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class SaveManagerDialog extends ContentDialog {
    public static final int REQUEST_CODE_RESTORE_ZIP = 1005;
    private static SaveManagerDialog activeInstance;

    private final Activity activity;
    private final Shortcut shortcut;
    private final Container container;
    private final List<CheckBox> checkBoxes = new ArrayList<>();

    public SaveManagerDialog(Activity activity, Shortcut shortcut) {
        super(activity, R.layout.save_manager_dialog);
        this.activity = activity;
        this.shortcut = shortcut;
        this.container = shortcut != null ? shortcut.container : null;
        activeInstance = this;
        initView();
    }

    public SaveManagerDialog(Activity activity, Container container) {
        super(activity, R.layout.save_manager_dialog);
        this.activity = activity;
        this.shortcut = null;
        this.container = container;
        activeInstance = this;
        initView();
    }

    public static SaveManagerDialog getActiveInstance() {
        return activeInstance;
    }

    private void initView() {
        setTitle(shortcut != null ? "Game Saves: " + shortcut.name : "Container Saves: " + container.getName());
        setIcon(R.drawable.icon_save_manager);

        TextView tvSubtitle = findViewById(R.id.TVSaveSubtitle);
        Button btnToggleAll = findViewById(R.id.BTToggleSelectAll);
        Button btnAddFolder = findViewById(R.id.BTAddSaveFolder);
        Button btnRestore = findViewById(R.id.BTRestoreSaves);
        LinearLayout llSaveLocations = findViewById(R.id.LLSaveLocations);

        Button btnConfirm = findViewById(R.id.BTConfirm);
        Button btnCancel = findViewById(R.id.BTCancel);
        if (btnConfirm != null) btnConfirm.setText("Export Backup");
        if (btnCancel != null) btnCancel.setText("Close");
        // The three icons have different intrinsic sizes, which made one pill taller than the others.
        sizeStartIcon(btnRestore, 18);
        sizeStartIcon(btnAddFolder, 18);
        limitListHeight();

        if (shortcut != null) {
            tvSubtitle.setText("Save folders");
            btnAddFolder.setOnClickListener(v -> openFolderPicker());
            populateGameList(llSaveLocations, btnToggleAll);

            if (btnToggleAll != null) {
                btnToggleAll.setOnClickListener(v -> {
                    boolean allChecked = true;
                    for (CheckBox cb : checkBoxes) {
                        if (!cb.isChecked()) {
                            allChecked = false;
                            break;
                        }
                    }
                    for (CheckBox cb : checkBoxes) cb.setChecked(!allChecked);
                    btnToggleAll.setText(!allChecked ? "Deselect all" : "Select all");
                });
            }

            setOnConfirmCallback(() -> {
                List<File> selected = new ArrayList<>();
                for (CheckBox cb : checkBoxes) {
                    if (cb.isChecked()) selected.add((File) cb.getTag());
                }
                if (selected.isEmpty()) {
                    Toast.makeText(activity, "Please select at least one save location to export", Toast.LENGTH_SHORT).show();
                    return;
                }

                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        File zip = GameSaveBackup.backupGameSaves(shortcut, selected);
                        activity.runOnUiThread(() -> AppUtils.showToast(activity, "Exported successfully to:\n" + zip.getName()));
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> AppUtils.showToast(activity, "Backup failed: " + e.getMessage()));
                    }
                });
            });

        } else if (container != null) {
            tvSubtitle.setText("Container profile saves");
            if (btnToggleAll != null) btnToggleAll.setVisibility(View.GONE);
            btnAddFolder.setVisibility(View.GONE);
            ((LinearLayout.LayoutParams) btnRestore.getLayoutParams()).setMarginEnd(0);

            TextView tvDesc = new TextView(activity);
            tvDesc.setText("Creates a full portable snapshot of Documents, Saved Games, AppData, Public Documents and ProgramData\\Steam (where cracked games keep saves) for container: " + container.getName());
            tvDesc.setTextColor(0xFF888888);
            tvDesc.setPadding(0, 16, 0, 16);
            llSaveLocations.addView(tvDesc);

            setOnConfirmCallback(() -> Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    File zip = GameSaveBackup.backupContainerSaves(container);
                    activity.runOnUiThread(() -> AppUtils.showToast(activity, "Container backup exported to:\n" + zip.getName()));
                } catch (Exception e) {
                    activity.runOnUiThread(() -> AppUtils.showToast(activity, "Backup failed: " + e.getMessage()));
                }
            }));
        }

        if (btnRestore != null) btnRestore.setOnClickListener(v -> showRestorePicker());
    }

    private void populateGameList(LinearLayout llSaveLocations, Button btnToggleAll) {
        llSaveLocations.removeAllViews();
        checkBoxes.clear();

        List<GameSaveBackup.SaveLocationInfo> discovered = GameSaveBackup.getSaveLocations(shortcut);
        if (discovered.isEmpty()) {
            if (btnToggleAll != null) btnToggleAll.setVisibility(View.GONE);
            TextView tvEmpty = new TextView(activity);
            tvEmpty.setText("No save folders found for this game yet.\nIf you know where it saves, tap Add Folder to pick it.");
            tvEmpty.setTextColor(0xFF888888);
            tvEmpty.setPadding(0, 24, 0, 24);
            llSaveLocations.addView(tvEmpty);
            return;
        }

        if (btnToggleAll != null) btnToggleAll.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(activity);

        for (GameSaveBackup.SaveLocationInfo info : discovered) {
            View itemView = inflater.inflate(R.layout.save_location_list_item, llSaveLocations, false);
            CheckBox cb = itemView.findViewById(R.id.CBSaveItem);
            TextView tvTitle = itemView.findViewById(R.id.TVSaveTitle);
            TextView tvPath = itemView.findViewById(R.id.TVSavePath);
            TextView tvTag = itemView.findViewById(R.id.TVSaveTag);
            TextView tvMeta = itemView.findViewById(R.id.TVSaveMeta);
            android.widget.ImageButton btRemove = itemView.findViewById(R.id.BTRemoveSave);

            tvTitle.setText(info.title);
            tvPath.setText(info.friendlyPath);

            if (info.missing) {
                showTag(tvTag, "Not found", 0xFFE5534B);
                tvMeta.setText("Folder no longer exists");
            } else {
                if (info.custom) showTag(tvTag, "Added by you", 0xFF3D82FF);
                else if (!info.suggested) showTag(tvTag, "Possible match", 0xFFD29922);
                String files = info.fileCount == 1 ? "1 file" : info.fileCount + " files";
                tvMeta.setText(files + " • " + info.getFormattedSize() + (info.lastModified > 0 ? " • " + info.getFormattedLastModified() : ""));
            }

            cb.setChecked(info.suggested && !info.missing);
            cb.setEnabled(!info.missing);
            cb.setTag(info.directory);
            if (!info.missing) checkBoxes.add(cb);

            itemView.setOnClickListener(v -> {
                if (cb.isEnabled()) cb.setChecked(!cb.isChecked());
            });
            if (info.custom) {
                Runnable confirmRemove = () -> new AlertDialog.Builder(activity)
                    .setTitle("Remove folder?")
                    .setMessage("Stop including this folder in this game's save backups?\n\n" + info.friendlyPath + "\n\nNothing is deleted from storage.")
                    .setPositiveButton("Remove", (d, w) -> {
                        GameSaveBackup.removeCustomSaveDir(shortcut, info.directory);
                        populateGameList(llSaveLocations, btnToggleAll);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                btRemove.setVisibility(View.VISIBLE);
                btRemove.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF8B949E));
                btRemove.setOnClickListener(v -> confirmRemove.run());
                itemView.setOnLongClickListener(v -> {
                    confirmRemove.run();
                    return true;
                });
            }
            llSaveLocations.addView(itemView);
        }
    }

    private static void showTag(TextView tag, String text, int color) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x33000000);
        bg.setCornerRadius(com.winlator.cmod.core.UnitUtils.dpToPx(8));
        tag.setBackground(bg);
        tag.setTextColor(color);
        tag.setText(text);
        tag.setVisibility(View.VISIBLE);
    }

    private static void sizeStartIcon(Button button, int dp) {
        if (button == null) return;
        android.graphics.drawable.Drawable icon = button.getCompoundDrawablesRelative()[0];
        if (icon == null) return;
        int px = (int) com.winlator.cmod.core.UnitUtils.dpToPx(dp);
        icon.setBounds(0, 0, px, px);
        button.setCompoundDrawablesRelative(icon, null, null, null);
    }

    // Keeps the export/close buttons on screen when a game has many save folders.
    private void limitListHeight() {
        View scroll = findViewById(R.id.SVSaveLocations);
        if (scroll == null) return;
        int max = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.42f);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t > max) {
                android.view.ViewGroup.LayoutParams params = v.getLayoutParams();
                params.height = max;
                v.setLayoutParams(params);
            }
        });
    }

    private void openFolderPicker() {
        if (!(activity instanceof FragmentActivity)) return;
        FragmentActivity fragmentActivity = (FragmentActivity) activity;
        FragmentManager fm = fragmentActivity.getSupportFragmentManager();
        final Shortcut target = shortcut;

        fm.setFragmentResultListener(FileManagerFragment.PICK_FOLDER_RESULT, fragmentActivity, (key, bundle) -> {
            fm.clearFragmentResultListener(FileManagerFragment.PICK_FOLDER_RESULT);
            String path = bundle.getString(FileManagerFragment.PICKED_PATH);
            if (path != null) {
                if (GameSaveBackup.addCustomSaveDir(target, new File(path))) {
                    AppUtils.showToast(fragmentActivity, "Save folder added");
                } else {
                    AppUtils.showToast(fragmentActivity, "Pick a folder inside this game's container, its game folder, or your phone storage.");
                }
            }
            new SaveManagerDialog(fragmentActivity, target).show();
        });

        File startDir = new File(GameSaveBackup.getDriveC(container), "users/xuser");
        if (!startDir.isDirectory()) startDir = GameSaveBackup.getDriveC(container);

        dismiss();
        fm.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
            .replace(R.id.FLFragmentContainer, FileManagerFragment.newFolderPicker(startDir))
            .addToBackStack("save_folder_picker")
            .commit();
    }

    private void showRestorePicker() {
        File backupDir = new File(GameSaveBackup.SAVES_BACKUP_DIR);
        File[] detectedZips = backupDir.exists() ? backupDir.listFiles((dir, name) -> name.endsWith(".zip")) : null;
        if (detectedZips != null) java.util.Arrays.sort(detectedZips, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        List<String> displayItems = new ArrayList<>();
        final List<File> zipFiles = new ArrayList<>();

        displayItems.add("📁 Browse Storage / Pick .zip File...");
        zipFiles.add(null);

        if (detectedZips != null) {
            for (File z : detectedZips) {
                displayItems.add("📦 " + z.getName() + " (" + GameSaveBackup.formatBytes(z.length()) + ")");
                zipFiles.add(z);
            }
        }

        new AlertDialog.Builder(activity)
            .setTitle("Select Save Archive to Restore")
            .setItems(displayItems.toArray(new String[0]), (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                    activity.startActivityForResult(intent, REQUEST_CODE_RESTORE_ZIP);
                } else {
                    File selectedFile = zipFiles.get(which);
                    if (selectedFile != null) inspectAndPromptRestore(selectedFile, null);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    public void handlePickedUri(Uri uri) {
        if (uri == null) return;
        inspectAndPromptRestore(null, uri);
    }

    private GameSaveBackup.StreamOpener openerFor(File file, Uri uri) {
        return () -> {
            if (file != null) return new FileInputStream(file);
            java.io.InputStream is = activity.getContentResolver().openInputStream(uri);
            if (is == null) throw new java.io.IOException("Cannot open the selected file");
            return is;
        };
    }

    private void inspectAndPromptRestore(File file, Uri uri) {
        GameSaveBackup.StreamOpener opener = openerFor(file, uri);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                GameSaveBackup.ArchiveInspectionResult inspection = GameSaveBackup.inspectArchive(opener.open());
                activity.runOnUiThread(() -> {
                    switch (inspection.status) {
                        case VALID_WINLATOR_MANIFEST: {
                            StringBuilder msg = new StringBuilder();
                            msg.append("🎮 Game: ").append(inspection.gameName).append("\n");
                            if (!inspection.createdAt.isEmpty()) msg.append("📅 Backed Up: ").append(inspection.createdAt).append("\n");
                            msg.append("📦 Contents: ").append(inspection.fileCount).append(" files (").append(GameSaveBackup.formatBytes(inspection.totalBytes)).append(")\n");
                            if (!inspection.detectedPaths.isEmpty()) {
                                msg.append("\n📍 Restores to the same places:\n");
                                for (String p : inspection.detectedPaths) msg.append(" • ").append(p).append("\n");
                            }
                            msg.append("\nRestore these saves into container '").append(container.getName()).append("'?");

                            new AlertDialog.Builder(activity)
                                .setTitle("Verified Save Backup")
                                .setMessage(msg.toString())
                                .setPositiveButton("Continue", (d, w) -> planAndRestore(opener))
                                .setNegativeButton("Cancel", null)
                                .show();
                            break;
                        }
                        case GENERIC_SAVE_ARCHIVE: {
                            StringBuilder msg = new StringBuilder();
                            msg.append("⚠️ This archive wasn't made by Winlator Mali, but it contains save files.\n");
                            msg.append("User folders (e.g. steamuser) are remapped to this container's user.\n\n");
                            msg.append("Detected save paths:\n");
                            for (String p : inspection.detectedPaths) msg.append(" • ").append(p).append("\n");
                            msg.append("\nDo you want to continue?");

                            new AlertDialog.Builder(activity)
                                .setTitle("Third-Party Save Archive")
                                .setMessage(msg.toString())
                                .setPositiveButton("Continue", (d, w) -> planAndRestore(opener))
                                .setNegativeButton("Cancel", null)
                                .show();
                            break;
                        }
                        case INVALID_NON_SAVE_ARCHIVE: {
                            new AlertDialog.Builder(activity)
                                .setTitle("❌ Invalid Save Archive")
                                .setMessage(inspection.errorMessage != null ? inspection.errorMessage : "The selected archive does not contain valid Windows or Wine save data.\n\nRestoration was cancelled to prevent container corruption.")
                                .setPositiveButton("OK", null)
                                .show();
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> AppUtils.showToast(activity, "Failed to read archive: " + e.getMessage()));
            }
        });
    }

    private void planAndRestore(GameSaveBackup.StreamOpener opener) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                GameSaveBackup.RestorePlan plan = GameSaveBackup.planRestore(container, shortcut, opener);
                activity.runOnUiThread(() -> {
                    if (plan.fileCount() == 0) {
                        new AlertDialog.Builder(activity)
                            .setTitle("Nothing to restore")
                            .setMessage(plan.rejected > 0
                                ? plan.rejected + " file(s) in this backup belong outside this container (or to a game folder that couldn't be found), so they were not restored.\n\nIf they belong to a game installed outside drive C:, restore from that game's own Save Manager instead."
                                : "This backup has no files to restore.")
                            .setPositiveButton("OK", null)
                            .show();
                        return;
                    }

                    if (plan.conflicts == 0) {
                        runRestore(plan, GameSaveBackup.ConflictMode.OVERWRITE, opener);
                        return;
                    }

                    String[] options = {
                        "Back up my current saves first, then overwrite (recommended)",
                        "Overwrite all",
                        "Only overwrite where the backup is newer",
                        "Keep existing files, only add missing ones"
                    };
                    GameSaveBackup.ConflictMode[] modes = {
                        GameSaveBackup.ConflictMode.BACKUP_THEN_OVERWRITE,
                        GameSaveBackup.ConflictMode.OVERWRITE,
                        GameSaveBackup.ConflictMode.NEWER_ONLY,
                        GameSaveBackup.ConflictMode.SKIP_EXISTING
                    };
                    new AlertDialog.Builder(activity)
                        .setTitle(plan.conflicts + " of " + plan.fileCount() + " files already exist")
                        .setItems(options, (d, which) -> runRestore(plan, modes[which], opener))
                        .setNegativeButton("Cancel", null)
                        .show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> AppUtils.showToast(activity, "Failed to read archive: " + e.getMessage()));
            }
        });
    }

    private void runRestore(GameSaveBackup.RestorePlan plan, GameSaveBackup.ConflictMode mode, GameSaveBackup.StreamOpener opener) {
        String label = shortcut != null ? shortcut.name : (!plan.gameName.isEmpty() ? plan.gameName : container.getName());
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                GameSaveBackup.RestoreResult result = GameSaveBackup.executeRestore(plan, mode, label, opener);
                activity.runOnUiThread(() -> {
                    StringBuilder msg = new StringBuilder();
                    msg.append("✅ Restored: ").append(result.written).append(" file(s)\n");
                    if (result.skipped > 0) msg.append("⏭ Kept existing: ").append(result.skipped).append(" file(s)\n");
                    if (result.rejected > 0) msg.append("⛔ Not restored: ").append(result.rejected).append(" file(s) outside this container or game folder\n");
                    if (!plan.locations.isEmpty()) {
                        msg.append("\n📍 Restored to:\n");
                        for (String loc : plan.locations) msg.append(" • ").append(loc).append("\n");
                    }
                    if (result.preRestoreBackup != null) {
                        msg.append("\n💾 Your previous saves were backed up first:\n").append(result.preRestoreBackup.getName())
                           .append("\n(restore it the same way to undo)");
                    }
                    dismiss();
                    new AlertDialog.Builder(activity)
                        .setTitle("Restore complete")
                        .setMessage(msg.toString())
                        .setPositiveButton("OK", null)
                        .show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> AppUtils.showToast(activity, "Restore failed: " + e.getMessage()));
            }
        });
    }
}
