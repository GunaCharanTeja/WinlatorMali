package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StreamUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ManageGraphicsDriversFragment extends Fragment {
    private String selectedDriverFile;
    private RecyclerView recyclerView;
    private final List<DriverInfo> driverList = new ArrayList<>();

    private static class DriverInfo {
        final String fileName;
        String version = "Unknown";
        String notes = "";
        boolean hasInfo = false;
        boolean isUpdated = false;

        public DriverInfo(String fileName) {
            this.fileName = fileName;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        MenuItem resetAllItem = menu.add(Menu.NONE, 1, Menu.NONE, R.string.reset_all);
        resetAllItem.setIcon(R.drawable.ic_reset_all);
        resetAllItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            ContentDialog.confirm(getContext(), R.string.reset_all_drivers_confirm, () -> {
                File internalDriversDir = new File(getContext().getFilesDir(), "graphics_driver");
                if (internalDriversDir.exists()) {
                    FileUtils.delete(internalDriversDir);
                    AppUtils.showToast(getContext(), R.string.driver_reset_success);
                    loadDrivers();
                }
            });
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.manage_graphics_drivers);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.manage_graphics_drivers_fragment, container, false);

        TextView tvDescription = view.findViewById(R.id.TVDescription);
        if (tvDescription != null) {
            String desc = "Manage and update internal graphics driver components. <b>Updated files</b> are stored in internal storage and <b>prioritized</b> over bundled versions.";
            tvDescription.setText(Html.fromHtml(desc, Html.FROM_HTML_MODE_LEGACY));
        }

        recyclerView = view.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        loadDrivers();

        return view;
    }

    private void loadDrivers() {
        final Activity activity = getActivity();
        if (activity == null) return;

        final PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.showOnUiThread(R.string.loading);

        new Thread(() -> {
            driverList.clear();
            driverList.add(new DriverInfo("wrapper.tzst"));
            driverList.add(new DriverInfo("wrapper-leegao.tzst"));
            driverList.add(new DriverInfo("wrapper-v2.tzst"));
            driverList.add(new DriverInfo("wrapper-gamenative.tzst"));
            driverList.add(new DriverInfo("leegao_bcn.tzst"));
            driverList.add(new DriverInfo("extra_libs.tzst"));

            android.content.Context context = getContext();
            if (context != null) {
                for (DriverInfo info : driverList) {
                    fetchDriverInfo(context, info);
                }
            }

            activity.runOnUiThread(() -> {
                preloaderDialog.closeOnUiThread();
                if (isAdded()) {
                    recyclerView.setAdapter(new DriverAdapter(driverList));
                }
            });
        }).start();
    }

    private void fetchDriverInfo(android.content.Context context, DriverInfo info) {
        File internalFile = new File(context.getFilesDir(), "graphics_driver/" + info.fileName);
        info.isUpdated = internalFile.exists();
        
        String content;
        if (info.isUpdated) {
            content = TarCompressorUtils.readTextFile(TarCompressorUtils.Type.ZSTD, internalFile, "version.txt");
        } else {
            content = TarCompressorUtils.readTextFile(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/" + info.fileName, "version.txt");
        }

        if (content != null) {
            info.hasInfo = true;
            String[] lines = content.split("\n");
            boolean parsingNotes = false;
            StringBuilder notesBuilder = new StringBuilder();

            for (String line : lines) {
                String trimmedLine = line.trim();
                String lowerLine = trimmedLine.toLowerCase();
                if (lowerLine.startsWith("version:")) {
                    info.version = trimmedLine.substring(8).trim();
                } else if (lowerLine.startsWith("notes:")) {
                    parsingNotes = true;
                    notesBuilder.append(trimmedLine.substring(6).trim());
                } else if (parsingNotes) {
                    if (notesBuilder.length() > 0) notesBuilder.append("\n");
                    notesBuilder.append(trimmedLine);
                }
            }
            info.notes = notesBuilder.toString().trim();
        }
    }

    private class DriverAdapter extends RecyclerView.Adapter<DriverAdapter.ViewHolder> {
        private final List<DriverInfo> data;

        public DriverAdapter(List<DriverInfo> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.manage_graphics_drivers_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DriverInfo info = data.get(position);
            holder.tvName.setText(info.fileName.replace(".tzst", ""));
            String status = getString(info.isUpdated ? R.string.updated : R.string.bundled);
            holder.tvVersion.setText(getString(R.string.version) + ": " + info.version + " (" + status + ")");

            holder.btInfo.setOnClickListener(v -> {
                String details = "<b>File:</b> " + info.fileName + "<br/>" +
                                 "<b>Version:</b> " + info.version + "<br/><br/>" +
                                 "<b>Notes:</b><br/>" + (info.notes.isEmpty() ? getString(R.string.no_notes_available) : info.notes.replace("\n", "<br/>"));
                
                ContentDialog dialog = new ContentDialog(getContext());
                dialog.setTitle(R.string.driver_details);
                dialog.setIcon(R.drawable.ic_driver_info);
                dialog.setMessage(details);
                dialog.getContentView().setMinimumWidth(AppUtils.getPreferredDialogWidth(getContext()));
                dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
                dialog.show();
            });

            holder.btUpdate.setOnClickListener(v -> {
                String message = getString(R.string.update_driver_initial_confirm, info.fileName);
                ContentDialog.confirm(getContext(), message, () -> {
                    selectedDriverFile = info.fileName;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    getActivity().startActivityFromFragment(ManageGraphicsDriversFragment.this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
                });
            });

            holder.btRemove.setVisibility(info.isUpdated ? View.VISIBLE : View.GONE);
            holder.btRemove.setOnClickListener(v -> {
                ContentDialog.confirm(getContext(), R.string.reset_driver_confirm, () -> {
                    File file = new File(getContext().getFilesDir(), "graphics_driver/" + info.fileName);
                    if (file.exists() && file.delete()) {
                        AppUtils.showToast(getContext(), R.string.driver_reset_success);
                        loadDrivers();
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvName, tvVersion;
            final ImageButton btInfo, btUpdate, btRemove;
            final ImageView ivIcon;

            ViewHolder(View view) {
                super(view);
                tvName = view.findViewById(R.id.TVName);
                tvVersion = view.findViewById(R.id.TVVersion);
                btInfo = view.findViewById(R.id.BTInfo);
                btUpdate = view.findViewById(R.id.BTUpdate);
                btRemove = view.findViewById(R.id.BTRemove);
                ivIcon = view.findViewById(R.id.IVIcon);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            String fileName = FileUtils.getUriFileName(getContext(), uri);
            if (fileName != null && fileName.equals(selectedDriverFile)) {
                String message = getString(R.string.update_driver_confirm, selectedDriverFile);
                ContentDialog.confirm(getContext(), message, () -> updateDriver(uri));
            } else {
                String errorMsg = fileName != null ? getString(R.string.driver_file_mismatch, fileName, selectedDriverFile) : getString(R.string.unable_to_determine_filename);
                AppUtils.showToast(getContext(), errorMsg);
            }
        }
    }

    private void updateDriver(Uri uri) {
        final PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
        preloaderDialog.showOnUiThread(R.string.updating_system_files);

        new Thread(() -> {
            try {
                File internalDriversDir = new File(getContext().getFilesDir(), "graphics_driver");
                if (!internalDriversDir.exists()) internalDriversDir.mkdirs();

                File finalFile = new File(internalDriversDir, selectedDriverFile);
                try (InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
                     FileOutputStream outputStream = new FileOutputStream(finalFile)) {
                    byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                }

                getActivity().runOnUiThread(() -> {
                    preloaderDialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), R.string.driver_updated_success);
                    loadDrivers();
                });
            } catch (Exception e) {
                getActivity().runOnUiThread(() -> {
                    preloaderDialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), R.string.driver_update_failed);
                });
            }
        }).start();
    }
}
