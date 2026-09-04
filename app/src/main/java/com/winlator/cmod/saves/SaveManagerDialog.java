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

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class SaveManagerDialog extends ContentDialog {
    public static final int REQUEST_CODE_RESTORE_ZIP = 1005;
    private static SaveManagerDialog activeInstance;

    private final Activity activity;
    private final Shortcut shortcut;
    private final Container container;

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
        Button btnRestore = findViewById(R.id.BTRestoreSaves);
        LinearLayout llSaveLocations = findViewById(R.id.LLSaveLocations);

        // Customize ContentDialog bottom bar buttons
        Button btnConfirm = findViewById(R.id.BTConfirm);
        Button btnCancel = findViewById(R.id.BTCancel);

        if (btnConfirm != null) btnConfirm.setText("Export Backup");
        if (btnCancel != null) btnCancel.setText("Close");

        final List<CheckBox> checkBoxes = new ArrayList<>();

        if (shortcut != null) {
            tvSubtitle.setText("Discovered Save Folders");
            List<GameSaveBackup.SaveLocationInfo> discovered = GameSaveBackup.getSaveLocations(shortcut);

            if (discovered.isEmpty()) {
                if (btnToggleAll != null) btnToggleAll.setVisibility(View.GONE);
                TextView tvEmpty = new TextView(activity);
                tvEmpty.setText("No active save files found for this game yet.\n(You can restore previous backups using the button above).");
                tvEmpty.setTextColor(0xFF888888);
                tvEmpty.setPadding(0, 24, 0, 24);
                llSaveLocations.addView(tvEmpty);
            } else {
                if (btnToggleAll != null) btnToggleAll.setVisibility(View.VISIBLE);
                LayoutInflater inflater = LayoutInflater.from(activity);

                for (GameSaveBackup.SaveLocationInfo info : discovered) {
                    View itemView = inflater.inflate(R.layout.save_location_list_item, llSaveLocations, false);
                    CheckBox cb = itemView.findViewById(R.id.CBSaveItem);
                    TextView tvTitle = itemView.findViewById(R.id.TVSaveTitle);
                    TextView tvPath = itemView.findViewById(R.id.TVSavePath);
                    TextView tvMeta = itemView.findViewById(R.id.TVSaveMeta);

                    tvTitle.setText(info.title);
                    tvPath.setText(info.friendlyPath);
                    tvMeta.setText(info.fileCount + " Files • " + info.getFormattedSize() + (info.lastModified > 0 ? " • " + info.getFormattedLastModified() : ""));

                    cb.setChecked(true);
                    cb.setTag(info.directory);
                    checkBoxes.add(cb);

                    itemView.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
                    llSaveLocations.addView(itemView);
                }

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
                        btnToggleAll.setText(!allChecked ? "Deselect All" : "Select All");
                    });
                }
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
                        activity.runOnUiThread(() -> {
                            AppUtils.showToast(activity, "Exported successfully to:\n" + zip.getName());
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> AppUtils.showToast(activity, "Backup failed: " + e.getMessage()));
                    }
                });
            });

        } else if (container != null) {
            tvSubtitle.setText("Container Profile Saves");
            if (btnToggleAll != null) btnToggleAll.setVisibility(View.GONE);

            TextView tvDesc = new TextView(activity);
            tvDesc.setText("Creates a full portable snapshot of all Documents, Saved Games, and AppData directories for container: " + container.getName());
            tvDesc.setTextColor(0xFF888888);
            tvDesc.setPadding(0, 16, 0, 16);
            llSaveLocations.addView(tvDesc);

            setOnConfirmCallback(() -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        File zip = GameSaveBackup.backupContainerSaves(container);
                        activity.runOnUiThread(() -> {
                            AppUtils.showToast(activity, "Container backup exported to:\n" + zip.getName());
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> AppUtils.showToast(activity, "Backup failed: " + e.getMessage()));
                    }
                });
            });
        }

        if (btnRestore != null) {
            btnRestore.setOnClickListener(v -> showRestorePicker());
        }
    }

    private void showRestorePicker() {
        File backupDir = new File(GameSaveBackup.SAVES_BACKUP_DIR);
        File[] detectedZips = backupDir.exists() ? backupDir.listFiles((dir, name) -> name.endsWith(".zip")) : null;

        List<String> displayItems = new ArrayList<>();
        final List<File> zipFiles = new ArrayList<>();

        // Option 0: Browse Custom File from Device Storage
        displayItems.add("📁 Browse Storage / Pick .zip File...");
        zipFiles.add(null);

        if (detectedZips != null) {
            for (File z : detectedZips) {
                long sizeKb = z.length() / 1024;
                String sizeStr = sizeKb > 1024 ? String.format("%.1f MB", sizeKb / 1024.0f) : sizeKb + " KB";
                displayItems.add("📦 " + z.getName() + " (" + sizeStr + ")");
                zipFiles.add(z);
            }
        }

        new AlertDialog.Builder(activity)
            .setTitle("Select Save Archive to Restore")
            .setItems(displayItems.toArray(new String[0]), (dialog, which) -> {
                if (which == 0) {
                    // Launch native storage document picker
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                    activity.startActivityForResult(intent, REQUEST_CODE_RESTORE_ZIP);
                } else {
                    File selectedFile = zipFiles.get(which);
                    if (selectedFile != null) {
                        inspectAndPromptRestore(selectedFile, null);
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Called when a custom .zip file URI is picked via storage picker.
     */
    public void handlePickedUri(Uri uri) {
        if (uri == null) return;
        inspectAndPromptRestore(null, uri);
    }

    private void inspectAndPromptRestore(File file, Uri uri) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                InputStream is = (file != null) ? new FileInputStream(file) : activity.getContentResolver().openInputStream(uri);
                GameSaveBackup.ArchiveInspectionResult inspection = GameSaveBackup.inspectArchive(is);

                activity.runOnUiThread(() -> {
                    switch (inspection.status) {
                        case VALID_WINLATOR_MANIFEST: {
                            StringBuilder msg = new StringBuilder();
                            msg.append("🎮 Game: ").append(inspection.gameName).append("\n");
                            if (!inspection.createdAt.isEmpty()) msg.append("📅 Backed Up: ").append(inspection.createdAt).append("\n");
                            msg.append("📦 Contents: ").append(inspection.fileCount).append(" files (").append(formatBytes(inspection.totalBytes)).append(")\n\n");
                            msg.append("Do you want to restore these saves into container '").append(container.getName()).append("'?");

                            new AlertDialog.Builder(activity)
                                .setTitle("Verified Save Backup")
                                .setMessage(msg.toString())
                                .setPositiveButton("Restore Saves", (d, w) -> executeRestore(file, uri))
                                .setNegativeButton("Cancel", null)
                                .show();
                            break;
                        }
                        case GENERIC_SAVE_ARCHIVE: {
                            StringBuilder msg = new StringBuilder();
                            msg.append("⚠️ Third-Party / Generic Save Archive Detected\n\n");
                            msg.append("This archive was not generated by Winlator Mali, but contains valid save files.\n");
                            msg.append("Winlator will automatically convert and remap paths (e.g. steamuser ↔ xuser) to fit this container.\n\n");
                            msg.append("Detected save paths:\n");
                            for (String p : inspection.detectedPaths) {
                                msg.append(" • ").append(p).append("\n");
                            }
                            msg.append("\nDo you want to proceed with restore?");

                            new AlertDialog.Builder(activity)
                                .setTitle("Third-Party Save Warning")
                                .setMessage(msg.toString())
                                .setPositiveButton("Proceed & Restore", (d, w) -> executeRestore(file, uri))
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

    private void executeRestore(File file, Uri uri) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                InputStream is = (file != null) ? new FileInputStream(file) : activity.getContentResolver().openInputStream(uri);
                GameSaveBackup.restoreSaves(container, is);
                activity.runOnUiThread(() -> {
                    dismiss();
                    AppUtils.showToast(activity, "Saves restored successfully into container!");
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> AppUtils.showToast(activity, "Restore failed: " + e.getMessage()));
            }
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0f);
        return String.format("%.2f MB", bytes / (1024.0f * 1024.0f));
    }
}
