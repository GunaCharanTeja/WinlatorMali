package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.widget.FileProgressDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class FileManagerFragment extends Fragment {
    private File currentDir;
    private RecyclerView recyclerViewFiles;
    private TextView tvCurrentPath;
    private TextView tvDriveName;
    private TextView tvNoItems;
    private ImageView ivDriveIcon;
    private ContainerManager containerManager;
    private FileProgressDialog fileProgressDialog;
    private static final List<File> clipboardFiles = new ArrayList<>();
    private static boolean isCutOperation;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabPaste;
    private final java.util.Set<File> selectedFiles = new java.util.HashSet<>();
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private long lastUIUpdateTime = 0;
    private String searchQuery = "";
    private SortOrder sortOrder = SortOrder.NAME_ASC;
    private boolean showHiddenFiles = false;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Thread searchThread;
    private View pbSearch;

    private enum SortOrder { NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        containerManager = new ContainerManager(getContext());
        currentDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.file_manager_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fileProgressDialog = new FileProgressDialog(getActivity());
        fileProgressDialog.setOnCancelListener(() -> isCancelled.set(true));
        
        if (getActivity() instanceof AppCompatActivity activity) {
            if (activity.getSupportActionBar() != null) {
                activity.getSupportActionBar().setTitle(R.string.file_manager);
            }
        }

        tvCurrentPath = view.findViewById(R.id.TVCurrentPath);
        tvDriveName = view.findViewById(R.id.TVDriveName);
        tvNoItems = view.findViewById(R.id.TVNoItems);
        ivDriveIcon = view.findViewById(R.id.IVDriveIcon);
        recyclerViewFiles = view.findViewById(R.id.RecyclerViewFiles);
        recyclerViewFiles.setLayoutManager(new LinearLayoutManager(getContext()));

        view.findViewById(R.id.BTUpDir).setOnClickListener(v -> navigateUp());
        view.findViewById(R.id.LLDriveSelect).setOnClickListener(v -> showDriveMenu());
        view.findViewById(R.id.BTNewFolder).setOnClickListener(v -> createNewFolder());
        view.findViewById(R.id.BTInfo).setOnClickListener(v -> showHelpDialog());

        View searchArea = view.findViewById(R.id.LLSearchArea);
        EditText etSearch = view.findViewById(R.id.ETSearch);
        pbSearch = view.findViewById(R.id.PBSearch);
        view.findViewById(R.id.BTSearch).setOnClickListener(v -> {
            searchArea.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
            AppUtils.showKeyboard((AppCompatActivity) getActivity());
        });

        view.findViewById(R.id.BTCloseSearch).setOnClickListener(v -> {
            searchArea.setVisibility(View.GONE);
            etSearch.setText("");
            searchQuery = "";
            cancelSearch();
            loadFiles();
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString();
                searchHandler.removeCallbacksAndMessages(null);
                searchHandler.postDelayed(() -> loadFiles(), 300);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        fabPaste = view.findViewById(R.id.fabPaste);
        fabPaste.setOnClickListener(v -> pasteFiles());
        fabPaste.setOnLongClickListener(v -> {
            clipboardFiles.clear();
            fabPaste.setVisibility(View.GONE);
            Toast.makeText(getContext(), "Clipboard cleared", Toast.LENGTH_SHORT).show();
            return true;
        });
        fabPaste.setVisibility(!clipboardFiles.isEmpty() ? View.VISIBLE : View.GONE);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                View searchArea = view.findViewById(R.id.LLSearchArea);
                if (searchArea != null && searchArea.getVisibility() == View.VISIBLE) {
                    view.findViewById(R.id.BTCloseSearch).performClick();
                } else if (!navigateUp()) {
                    setEnabled(false);
                    requireActivity().onBackPressed();
                }
            }
        });

        updateDriveButtonLabel(currentDir);
        loadFiles();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelSearch();
        searchHandler.removeCallbacksAndMessages(null);
    }

    private void loadFiles() {
        if (!selectedFiles.isEmpty()) {
            selectedFiles.clear();
            updateSelectionUI();
        }
        tvCurrentPath.setText(currentDir.getAbsolutePath());
        cancelSearch();
        pbSearch.setVisibility(View.VISIBLE);
        
        final File dirToLoad = currentDir;
        final String query = searchQuery.trim().toLowerCase();
        final boolean finalShowHidden = showHiddenFiles;
        
        searchThread = new Thread(() -> {
            List<File> fileList = new ArrayList<>();
            if (query.isEmpty()) {
                File[] files = dirToLoad.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!finalShowHidden && file.getName().startsWith(".")) continue;
                        fileList.add(file);
                    }
                }
            } else {
                searchDeep(dirToLoad, query, fileList, finalShowHidden, new java.util.HashSet<>());
            }
            
            if (Thread.interrupted()) return;
            
            sortFiles(fileList);
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    pbSearch.setVisibility(View.GONE);
                    recyclerViewFiles.setAdapter(new FileAdapter(fileList, !query.isEmpty()));
                    tvNoItems.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }
        });
        searchThread.start();
    }

    private void cancelSearch() {
        if (searchThread != null) {
            searchThread.interrupt();
            searchThread = null;
        }
    }

    private void searchDeep(File directory, String query, List<File> results, boolean showHidden, java.util.Set<String> visited) {
        if (Thread.interrupted()) return;
        File[] files = directory.listFiles();
        if (files == null) return;

        // First pass: local directory items
        for (File file : files) {
            if (Thread.interrupted()) return;
            String name = file.getName();
            if (!showHidden && name.startsWith(".")) continue;
            
            if (name.toLowerCase().contains(query)) {
                results.add(file);
            }
            if (results.size() >= 5000) return;
        }

        // Second pass: recursion
        for (File file : files) {
            if (Thread.interrupted()) return;
            if (file.isDirectory()) {
                String name = file.getName();
                if (!showHidden && name.startsWith(".")) continue;
                
                try {
                    String canonicalPath = file.getCanonicalPath();
                    if (!visited.contains(canonicalPath)) {
                        visited.add(canonicalPath);
                        searchDeep(file, query, results, showHidden, visited);
                    }
                } catch (IOException ignored) {}
            }
            if (results.size() >= 5000) return;
        }
    }

    private void sortFiles(List<File> fileList) {
        Collections.sort(fileList, (f1, f2) -> {
            // Folders always at top
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            
            int result = 0;
            switch (sortOrder) {
                case NAME_ASC: result = f1.getName().compareToIgnoreCase(f2.getName()); break;
                case NAME_DESC: result = f2.getName().compareToIgnoreCase(f1.getName()); break;
                case DATE_ASC: result = Long.compare(f1.lastModified(), f2.lastModified()); break;
                case DATE_DESC: result = Long.compare(f2.lastModified(), f1.lastModified()); break;
                case SIZE_ASC: 
                    if (f1.isDirectory()) result = f1.getName().compareToIgnoreCase(f2.getName());
                    else result = Long.compare(f1.length(), f2.length()); 
                    break;
                case SIZE_DESC: 
                    if (f1.isDirectory()) result = f2.getName().compareToIgnoreCase(f1.getName());
                    else result = Long.compare(f2.length(), f1.length()); 
                    break;
            }
            
            if (result == 0) result = f1.getName().compareToIgnoreCase(f2.getName());
            return result;
        });
    }

    private void navigateTo(File dir) {
        if (dir != null && dir.isDirectory() && dir.canRead()) {
            currentDir = dir;
            loadFiles();
        }
    }

    private boolean isDriveRoot(File dir) {
        if (dir == null) return true;
        String path = dir.getAbsolutePath();
        String downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        String internalStoragePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        String rootFsPath = com.winlator.cmod.xenvironment.ImageFs.find(requireContext()).getRootDir().getAbsolutePath();

        return path.equals(downloadsPath) || path.equals(internalStoragePath) || path.equals(rootFsPath) || path.endsWith(".wine/drive_c") || path.equals("/");
    }

    private boolean navigateUp() {
        if (isDriveRoot(currentDir)) return false;

        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            navigateTo(parent);
            return true;
        }
        return false;
    }

    private void showDriveMenu() {
        PopupMenu popupMenu = new PopupMenu(requireContext(), tvDriveName);
        popupMenu.getMenu().add(0, 1, 0, "Drive D: (Downloads)");
        popupMenu.getMenu().add(0, 6, 0, "Internal Storage");
        popupMenu.getMenu().add(0, 2, 0, "Drive C: (Wine System)");
        popupMenu.getMenu().add(0, 3, 0, "Drive Z: (RootFS)");
        popupMenu.getMenu().add(0, 4, 0, R.string.sort);
        popupMenu.getMenu().add(0, 5, 0, showHiddenFiles ? "Hide Hidden Files" : "Show Hidden Files");

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) {
                navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
                updateDriveButtonLabel(currentDir);
                return true;
            } else if (itemId == 6) {
                navigateTo(Environment.getExternalStorageDirectory());
                updateDriveButtonLabel(currentDir);
                return true;
            } else if (itemId == 2) {
                handleDriveCSelection();
                return true;
            } else if (itemId == 3) {
                navigateTo(com.winlator.cmod.xenvironment.ImageFs.find(requireContext()).getRootDir());
                updateDriveButtonLabel(currentDir);
                return true;
            } else if (itemId == 4) {
                showSortMenu();
                return true;
            } else if (itemId == 5) {
                showHiddenFiles = !showHiddenFiles;
                loadFiles();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void showSortMenu() {
        PopupMenu popupMenu = new PopupMenu(requireContext(), tvDriveName);
        popupMenu.getMenu().add(0, 1, 0, R.string.by_name);
        popupMenu.getMenu().add(0, 2, 0, R.string.by_date);
        popupMenu.getMenu().add(0, 3, 0, R.string.by_size);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) sortOrder = sortOrder == SortOrder.NAME_ASC ? SortOrder.NAME_DESC : SortOrder.NAME_ASC;
            else if (itemId == 2) sortOrder = sortOrder == SortOrder.DATE_ASC ? SortOrder.DATE_DESC : SortOrder.DATE_ASC;
            else if (itemId == 3) sortOrder = sortOrder == SortOrder.SIZE_ASC ? SortOrder.SIZE_DESC : SortOrder.SIZE_ASC;
            loadFiles();
            return true;
        });
        popupMenu.show();
    }

    private void handleDriveCSelection() {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers.isEmpty()) {
            ContentDialog.alert(getContext(), "You need to create a container first to access Drive C:.", null);
            return;
        }

        String[] containerNames = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(getContext(), R.string.select_container, containerNames, index -> {
            Container container = containers.get(index);
            File driveC = new File(container.getRootDir(), ".wine/drive_c");
            if (driveC.exists()) {
                navigateTo(driveC);
                updateDriveButtonLabel(currentDir);
            } else {
                ContentDialog.alert(getContext(), "The Wine system files for '" + container.getName() + "' have not been initialized yet.", null);
            }
        });
    }

    private void updateDriveButtonLabel(File file) {
        String path = file.getAbsolutePath();
        if (path.contains(".wine/drive_c")) {
            tvDriveName.setText(R.string.drive_c);
            ivDriveIcon.setImageResource(R.drawable.ic_drive_wine);
        } else if (Objects.equals(path, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath())) {
            tvDriveName.setText("Drive D:");
            ivDriveIcon.setImageResource(R.drawable.ic_drive_storage);
        } else if (Objects.equals(path, Environment.getExternalStorageDirectory().getAbsolutePath())) {
            tvDriveName.setText("Internal Storage");
            ivDriveIcon.setImageResource(R.drawable.ic_drive_storage);
        } else if (path.startsWith(com.winlator.cmod.xenvironment.ImageFs.find(requireContext()).getRootDir().getAbsolutePath())) {
            tvDriveName.setText("Drive Z:");
            ivDriveIcon.setImageResource(R.drawable.ic_x11_icon);
        } else {
            tvDriveName.setText("Storage");
            ivDriveIcon.setImageResource(R.drawable.ic_drive_storage);
        }
    }

    private void showFileOptions(File file, View anchor) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchor);
        String nameLower = file.getName().toLowerCase();
        if (file.isDirectory()) {
            popupMenu.getMenu().add(0, 8, 0, R.string.new_folder);
        } else {
            if (nameLower.endsWith(".exe")) {
                popupMenu.getMenu().add(0, 1, 0, R.string.run);
                popupMenu.getMenu().add(0, 10, 0, "Create Shortcut");
            }
            if (nameLower.endsWith(".tar") || nameLower.endsWith(".tar.xz") || nameLower.endsWith(".tar.zst") ||
                nameLower.endsWith(".txz") || nameLower.endsWith(".tzst") || nameLower.endsWith(".zip")) {
                popupMenu.getMenu().add(0, 9, 0, "Extract");
            }
        }
        popupMenu.getMenu().add(0, 2, 0, R.string.rename);
        popupMenu.getMenu().add(0, 3, 0, "Delete");
        popupMenu.getMenu().add(0, 4, 0, R.string.copy);
        popupMenu.getMenu().add(0, 5, 0, R.string.cut);
        if (!selectedFiles.isEmpty()) popupMenu.getMenu().add(0, 11, 0, "Apply to selected (" + selectedFiles.size() + ")");
        popupMenu.getMenu().add(0, 6, 0, R.string.share);
        popupMenu.getMenu().add(0, 7, 0, R.string.properties);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) {
                selectContainerForFile(file);
                return true;
            } else if (itemId == 10) {
                selectContainerForShortcut(file);
                return true;
            } else if (itemId == 2) {
                renameFile(file);
                return true;
            } else if (itemId == 3) {
                removeFiles(Collections.singletonList(file));
                return true;
            } else if (itemId == 4) {
                copyFiles(Collections.singletonList(file));
                return true;
            } else if (itemId == 5) {
                cutFiles(Collections.singletonList(file));
                return true;
            } else if (itemId == 11) {
                showSelectionOptions(anchor);
                return true;
            } else if (itemId == 6) {
                shareFile(file);
                return true;
            } else if (itemId == 7) {
                showProperties(file);
                return true;
            } else if (itemId == 8) {
                createNewFolder(file);
                return true;
            } else if (itemId == 9) {
                extractArchive(file);
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void selectContainerForFile(File file) {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers.isEmpty()) {
            ContentDialog.alert(getContext(), "You need to create a container first to run .exe files.", null);
            return;
        }

        String[] containerNames = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(getContext(), R.string.select_container, containerNames, index -> {
            Container container = containers.get(index);
            runFileDirectly(file, container);
        });
    }

    private void selectContainerForShortcut(File file) {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers.isEmpty()) {
            ContentDialog.alert(getContext(), "You need to create a container first to create a shortcut.", null);
            return;
        }

        String[] containerNames = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(getContext(), R.string.select_container, containerNames, index -> {
            Container container = containers.get(index);
            createShortcut(file, container);
        });
    }

    private void createShortcut(File file, Container container) {
        try {
            String displayName = getSmartDisplayName(file);
            String execPath = toDesktopWindowsPath(file, container);
            String workDir = toDesktopPath(file, container);
            
            File desktopDir = container.getDesktopDir();
            if (!desktopDir.exists() && !desktopDir.mkdirs()) {
                Toast.makeText(getContext(), "Could not create desktop directory", Toast.LENGTH_SHORT).show();
                return;
            }
            
            File desktopFile = new File(desktopDir, displayName + ".desktop");
            try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(desktopFile))) {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + displayName);
                String escapedExecPath = com.winlator.cmod.core.StringUtils.escapeFileDOSPath(execPath);
                String winePrefix = getContainerWineHome(container) + "/.wine";
                writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine " + escapedExecPath);
                writer.println("Type=Application");
                if (!workDir.isEmpty()) writer.println("Path=" + workDir);
                writer.println("Icon=" + displayName);
                writer.println("container_id:" + container.id);
            }
            Toast.makeText(getContext(), "Shortcut created on container desktop!", Toast.LENGTH_SHORT).show();

            if (file.getName().toLowerCase().endsWith(".exe")) {
                File iconDir64 = container.getIconsDir(64);
                if (!iconDir64.exists()) iconDir64.mkdirs();
                File iconDest = new File(iconDir64, displayName + ".png");
                ExeIconExtractor.extractAsync(file, iconDest, false, null);
            }
        } catch (IOException e) {
            Toast.makeText(getContext(), "Failed to create shortcut", Toast.LENGTH_SHORT).show();
        }
    }

    private String getSmartDisplayName(File file) {
        String name = file.getName();
        int extIndex = name.lastIndexOf(".");
        if (extIndex > 0) name = name.substring(0, extIndex);
        return name;
    }

    private String getContainerWineHome(Container container) {
        File imagefs = new File(requireContext().getFilesDir(), "imagefs");
        String rootPath = container.getRootDir().getAbsolutePath();
        String imagefsPath = imagefs.getAbsolutePath();
        if (rootPath.startsWith(imagefsPath)) {
            return rootPath.substring(imagefsPath.length());
        }
        return "/home/" + com.winlator.cmod.xenvironment.ImageFs.USER;
    }

    private String toDesktopWindowsPath(File file, Container container) {
        String filePath = file.getAbsolutePath();
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        String driveCPath = driveC.getAbsolutePath();
        
        if (filePath.startsWith(driveCPath)) {
            String rel = filePath.substring(driveCPath.length()).replace('/', '\\');
            if (rel.startsWith("\\")) rel = rel.substring(1);
            return "C:\\" + rel;
        }

        for (String[] drive : container.drivesIterator()) {
            String driveLetter = drive[0].toUpperCase();
            String drivePath = drive[1];
            if (filePath.startsWith(drivePath)) {
                String rel = filePath.substring(drivePath.length()).replace('/', '\\');
                if (rel.startsWith("\\")) rel = rel.substring(1);
                return driveLetter + ":\\" + rel;
            }
        }
        
        return "Z:" + filePath.replace('/', '\\');
    }

    private String toDesktopPath(File file, Container container) {
        File parent = file.getParentFile();
        if (parent == null) return "";
        String parentPath = parent.getAbsolutePath();

        for (String[] drive : container.drivesIterator()) {
            String driveLetter = drive[0].toLowerCase();
            String drivePath = drive[1];
            if (parentPath.startsWith(drivePath)) {
                String rel = parentPath.substring(drivePath.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                String basePath = getContainerWineHome(container) + "/.wine/dosdevices/" + driveLetter + ":";
                return rel.isEmpty() ? basePath : basePath + "/" + rel;
            }
        }
        return parentPath;
    }

    private void runFileDirectly(File file, Container container) {
        Context context = getContext();
        if (context != null) {
            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("shortcut_path", file.getPath());
            context.startActivity(intent);
        }
    }

    private void renameFile(File file) {
        ContentDialog.prompt(getContext(), R.string.rename, file.getName(), newName -> {
            File newFile = new File(file.getParentFile(), newName);
            if (file.renameTo(newFile)) {
                loadFiles();
            } else {
                Toast.makeText(getContext(), "Rename failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean deleteRecursiveSafe(File file, AtomicLong deletedCount, long totalItems) {
        if (isCancelled.get()) return false;

        if (!com.winlator.cmod.core.FileUtils.isSymlink(file) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursiveSafe(child, deletedCount, totalItems)) return false;
                }
            }
        }

        String fileName = file.getName();
        boolean deleted = file.delete();
        if (deleted) {
            long count = deletedCount.incrementAndGet();
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUIUpdateTime > 100) {
                fileProgressDialog.update(fileName, count, totalItems, count + " / " + totalItems + " items");
                lastUIUpdateTime = currentTime;
            }
        }
        return deleted;
    }

    private void copyFiles(java.util.Collection<File> files) {
        clipboardFiles.clear();
        clipboardFiles.addAll(files);
        isCutOperation = false;
        fabPaste.setVisibility(View.VISIBLE);
        Toast.makeText(getContext(), "Copied " + files.size() + " items", Toast.LENGTH_SHORT).show();
        clearSelection();
    }

    private void cutFiles(java.util.Collection<File> files) {
        clipboardFiles.clear();
        clipboardFiles.addAll(files);
        isCutOperation = true;
        fabPaste.setVisibility(View.VISIBLE);
        Toast.makeText(getContext(), "Cut " + files.size() + " items", Toast.LENGTH_SHORT).show();
        clearSelection();
    }

    private void clearSelection() {
        selectedFiles.clear();
        updateSelectionUI();
        if (recyclerViewFiles.getAdapter() != null) {
            recyclerViewFiles.getAdapter().notifyDataSetChanged();
        }
    }

    private void updateSelectionUI() {
        if (getActivity() instanceof AppCompatActivity activity && activity.getSupportActionBar() != null) {
            if (selectedFiles.isEmpty()) {
                activity.getSupportActionBar().setTitle(R.string.file_manager);
            } else {
                activity.getSupportActionBar().setTitle(selectedFiles.size() + " selected");
            }
        }
    }

    private void pasteFiles() {
        if (clipboardFiles.isEmpty()) {
            fabPaste.setVisibility(View.GONE);
            return;
        }

        executePaste(currentDir);
    }

    private void executePaste(File destinationDir) {
        fileProgressDialog.show(isCutOperation ? R.string.moving_file : R.string.copying_file);
        isCancelled.set(false);
        if (getActivity() != null) AppUtils.keepScreenOn(getActivity());

        new Thread(() -> {
            AtomicLong totalSize = new AtomicLong(0);
            AtomicLong copiedSize = new AtomicLong(0);

            List<File> sources = new ArrayList<>(clipboardFiles);
            for (File file : sources) {
                if (file.isDirectory()) {
                    totalSize.addAndGet(getDirectoryInfo(file, new java.util.HashSet<>())[0]);
                } else {
                    totalSize.addAndGet(file.length());
                }
            }

            boolean allSuccess = true;
            for (File file : sources) {
                if (isCancelled.get()) break;
                File destination = new File(destinationDir, file.getName());
                
                // Simple conflict handling: rename if exists
                if (destination.exists()) {
                    destination = new File(destinationDir, "Copy_of_" + file.getName());
                }

                boolean success = copyWithProgress(file, destination, copiedSize, totalSize.get());
                if (success && isCutOperation && !isCancelled.get()) {
                    deleteRecursiveSafe(file, new AtomicLong(0), 1);
                }
                if (!success) allSuccess = false;
            }

            final boolean finalSuccess = allSuccess;
            final boolean finalCancelled = isCancelled.get();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (getActivity() != null) getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    fileProgressDialog.dismiss();
                    if (finalCancelled) {
                        Toast.makeText(getContext(), "Operation cancelled", Toast.LENGTH_SHORT).show();
                    } else if (finalSuccess) {
                        if (isCutOperation) {
                            clipboardFiles.clear();
                            fabPaste.setVisibility(View.GONE);
                        }
                        loadFiles();
                    } else {
                        Toast.makeText(getContext(), "Paste completed with some errors", Toast.LENGTH_SHORT).show();
                        loadFiles();
                    }
                });
            }
        }).start();
    }

    private void removeFiles(java.util.Collection<File> files) {
        ContentDialog.confirm(getContext(), "Do you want to delete " + files.size() + " items?", () -> {
            fileProgressDialog.show(R.string.deleting_file);
            isCancelled.set(false);
            if (getActivity() != null) AppUtils.keepScreenOn(getActivity());

            new Thread(() -> {
                AtomicLong deletedCount = new AtomicLong(0);
                long totalItems = 0;
                List<File> sources = new ArrayList<>(files);
                
                for (File file : sources) {
                    if (file.isDirectory() && !com.winlator.cmod.core.FileUtils.isSymlink(file)) {
                        long[] info = getDirectoryInfo(file, new java.util.HashSet<>());
                        totalItems += info[1] + info[2] + 1;
                    } else {
                        totalItems += 1;
                    }
                }

                final long finalTotal = totalItems;
                boolean allSuccess = true;
                for (File file : sources) {
                    if (isCancelled.get()) {
                        allSuccess = false;
                        break;
                    }
                    if (!deleteRecursiveSafe(file, deletedCount, finalTotal)) allSuccess = false;
                }

                final boolean finalSuccess = allSuccess;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (getActivity() != null) getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        fileProgressDialog.dismiss();
                        clearSelection();
                        if (isCancelled.get()) {
                            Toast.makeText(getContext(), "Operation cancelled", Toast.LENGTH_SHORT).show();
                        } else if (finalSuccess) {
                            loadFiles();
                        } else {
                            Toast.makeText(getContext(), "Remove failed for some items", Toast.LENGTH_SHORT).show();
                            loadFiles();
                        }
                    });
                }
            }).start();
        });
    }

    private void showSelectionOptions(View anchor) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchor);
        popupMenu.getMenu().add(0, 1, 0, R.string.copy);
        popupMenu.getMenu().add(0, 2, 0, R.string.cut);
        popupMenu.getMenu().add(0, 3, 0, "Delete");
        popupMenu.getMenu().add(0, 4, 0, "Clear Selection");

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) copyFiles(new ArrayList<>(selectedFiles));
            else if (itemId == 2) cutFiles(new ArrayList<>(selectedFiles));
            else if (itemId == 3) removeFiles(new ArrayList<>(selectedFiles));
            else if (itemId == 4) clearSelection();
            return true;
        });
        popupMenu.show();
    }

    private boolean copyWithProgress(File src, File dst, AtomicLong copiedSize, long totalSize) {
        if (isCancelled.get()) return false;
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] files = src.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!copyWithProgress(file, new File(dst, file.getName()), copiedSize, totalSize)) return false;
                }
            }
            return true;
        } else {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                FileChannel inChannel = in.getChannel();
                FileChannel outChannel = out.getChannel();
                long size = inChannel.size();
                long position = 0;
                long bufferSize = 1024 * 1024;

                while (position < size) {
                    if (isCancelled.get()) return false;
                    long remain = size - position;
                    long count = Math.min(remain, bufferSize);
                    long transferred = inChannel.transferTo(position, count, outChannel);
                    if (transferred <= 0) break;
                    position += transferred;
                    copiedSize.addAndGet(transferred);

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUIUpdateTime > 100) {
                        fileProgressDialog.update(src.getName(), copiedSize.get(), totalSize);
                        lastUIUpdateTime = currentTime;
                    }
                }
                fileProgressDialog.update(src.getName(), copiedSize.get(), totalSize);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private void createNewFolder() {
        createNewFolder(currentDir);
    }

    private void createNewFolder(File parentDir) {
        ContentDialog.prompt(getContext(), R.string.new_folder, "", folderName -> {
            File newDir = new File(parentDir, folderName);
            if (!newDir.exists() && newDir.mkdirs()) {
                loadFiles();
            } else {
                Toast.makeText(getContext(), "Failed to create folder", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void shareFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(requireContext(), "com.winlator.cmod.tileprovider", file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(getContext(), "Failed to share file", Toast.LENGTH_SHORT).show();
        }
    }

    private void showProperties(File file) {
        if (file.isFile()) {
            displayProperties(file, file.length(), 0, 0);
        } else {
            Toast.makeText(getContext(), "Calculating folder size...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                long[] info = getDirectoryInfo(file, new java.util.HashSet<>());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> displayProperties(file, info[0], info[1], info[2]));
                }
            }).start();
        }
    }

    private void showHelpDialog() {
        String html = "<b>Navigation:</b><br/>" +
                "• <b>Tap Folder:</b> Open and navigate into it.<br/>" +
                "• <b>Tap File:</b> Open actions menu (Run, Rename, Delete, etc.).<br/>" +
                "• <b>Back Gesture:</b> Go up one folder level.<br/><br/>" +
                "<b>Selection & Clipboard:</b><br/>" +
                "• <b>Long Press:</b> Start multi-selecting items.<br/>" +
                "• <b>Hold Selected:</b> Open menu for all selected items.<br/>" +
                "• <b>Hold Paste Button:</b> Clear the clipboard.<br/><br/>" +
                "<b>Features:</b><br/>" +
                "• <b>Drive Selector:</b> Switch between D: (Downloads), Internal Storage, C: (Wine), and Z: (System).<br/>" +
                "• <b>Sorting & View:</b> Change sort order or toggle hidden files in the Drive menu.<br/>" +
                "• <b>Archives:</b> Extract files into a new folder named after the archive.<br/>" +
                "• <b>Search:</b> Deep search files in the current folder.";

        ContentDialog dialog = new ContentDialog(requireContext());
        dialog.setTitle("Usage Instructions");
        dialog.setMessage(html);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void extractArchive(File file) {
        String nameLower = file.getName().toLowerCase();
        TarCompressorUtils.Type type = null;
        boolean isZip = nameLower.endsWith(".zip");
        if (nameLower.endsWith(".tar.xz") || nameLower.endsWith(".txz")) type = TarCompressorUtils.Type.XZ;
        else if (nameLower.endsWith(".tar.zst") || nameLower.endsWith(".tzst")) type = TarCompressorUtils.Type.ZSTD;

        String folderName = file.getName().replaceAll("(?i)\\.(tar\\.xz|tar\\.zst|tar|txz|tzst|zip)$", "");
        if (folderName.equals(file.getName())) folderName += "_extracted";
        File outputDir = new File(currentDir, folderName);
        if (!outputDir.exists()) outputDir.mkdirs();

        Toast.makeText(getContext(), "Extracting to " + folderName + "...", Toast.LENGTH_SHORT).show();
        
        TarCompressorUtils.Type finalType = type;
        new Thread(() -> {
            boolean success;
            if (isZip) {
                success = TarCompressorUtils.extractZip(file, outputDir);
            } else if (finalType != null) {
                success = TarCompressorUtils.extract(finalType, file, outputDir);
            } else {
                success = TarCompressorUtils.extractTar(file, outputDir, null);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(getContext(), "Extraction complete", Toast.LENGTH_SHORT).show();
                        loadFiles();
                    } else {
                        Toast.makeText(getContext(), "Extraction failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void displayProperties(File file, long totalSize, long totalDirs, long totalFiles) {
        if (getContext() == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.name)).append(": ").append(file.getName()).append("\n");
        sb.append("Path: ").append(file.getAbsolutePath()).append("\n");

        sb.append(getString(R.string.size)).append(": ").append(Formatter.formatFileSize(getContext(), totalSize));
        sb.append(" (").append(java.text.NumberFormat.getInstance().format(totalSize)).append(" bytes)\n");

        if (file.isDirectory()) {
            sb.append("Contents: ").append(totalDirs).append(" folders, ").append(totalFiles).append(" files\n");
        } else {
            sb.append("Executable: ").append(file.canExecute() ? "Yes" : "No").append("\n");
        }

        sb.append("Last Modified: ").append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(file.lastModified())));

        ContentDialog dialog = new ContentDialog(requireContext());
        dialog.setTitle(R.string.properties);
        dialog.setMessage(sb.toString());
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private long[] getDirectoryInfo(File directory, java.util.Set<String> visited) {
        long totalSize = 0;
        long totalDirs = 0;
        long totalFiles = 0;

        try {
            String canonicalPath = directory.getCanonicalPath();
            if (visited.contains(canonicalPath)) return new long[]{0, 0, 0};
            visited.add(canonicalPath);
        } catch (java.io.IOException e) {
            return new long[]{0, 0, 0};
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    totalDirs++;
                    long[] subDirInfo = getDirectoryInfo(file, visited);
                    totalSize += subDirInfo[0];
                    totalDirs += subDirInfo[1];
                    totalFiles += subDirInfo[2];
                } else {
                    totalSize += file.length();
                    totalFiles++;
                }
            }
        }
        return new long[]{totalSize, totalDirs, totalFiles};
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        private final List<File> files;
        private final boolean isSearchMode;

        public FileAdapter(List<File> files) {
            this(files, false);
        }

        public FileAdapter(List<File> files, boolean isSearchMode) {
            this.files = files;
            this.isSearchMode = isSearchMode;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.file_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File file = files.get(position);
            holder.tvFileName.setText(file.getName());
            
            String nameLower = file.getName().toLowerCase();
            if (file.isDirectory()) {
                holder.ivIcon.setImageResource(R.drawable.ic_folder);
                if (isSearchMode) {
                    holder.tvFileDetails.setText(getRelativePath(file));
                } else {
                    String[] list = file.list();
                    int itemCount = list != null ? list.length : 0;
                    holder.tvFileDetails.setText(getString(R.string.num_items, itemCount));
                }
            } else {
                if (nameLower.endsWith(".exe")) holder.ivIcon.setImageResource(R.drawable.ic_drive_wine);
                else if (nameLower.endsWith(".so") || nameLower.contains(".so."))
                    holder.ivIcon.setImageResource(R.drawable.ic_settings);
                else if (nameLower.endsWith(".txz") || nameLower.endsWith(".tzst") || nameLower.endsWith(".tar") || nameLower.endsWith(".zip"))
                    holder.ivIcon.setImageResource(R.drawable.ic_picture_in_picture_alt);
                else holder.ivIcon.setImageResource(R.drawable.ic_file);

                if (isSearchMode) {
                    holder.tvFileDetails.setText(getRelativePath(file));
                } else {
                    holder.tvFileDetails.setText(Formatter.formatFileSize(getContext(), file.length()));
                }
            }

            if (com.winlator.cmod.core.FileUtils.isSymlink(file)) {
                holder.ivIcon.setAlpha(0.5f);
            } else {
                holder.ivIcon.setAlpha(1.0f);
            }

            if (selectedFiles.contains(file)) {
                holder.itemView.setBackgroundColor(0x332196f3);
            } else {
                holder.itemView.setBackgroundColor(0);
            }

            holder.btFileMenu.setVisibility(View.GONE);

            holder.itemView.setOnClickListener(v -> {
                if (!selectedFiles.isEmpty()) {
                    toggleSelection(file, position);
                } else if (file.isDirectory()) {
                    navigateTo(file);
                } else {
                    showFileOptions(file, v);
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                if (selectedFiles.contains(file)) {
                    showFileOptions(file, v);
                } else {
                    toggleSelection(file, position);
                }
                return true;
            });
        }

        private void toggleSelection(File file, int position) {
            if (selectedFiles.contains(file)) {
                selectedFiles.remove(file);
            } else {
                selectedFiles.add(file);
            }
            notifyItemChanged(position);
            updateSelectionUI();
        }

        private String getRelativePath(File file) {
            String path = file.getAbsolutePath();
            String rootPath = currentDir.getAbsolutePath();
            if (path.startsWith(rootPath)) {
                String rel = path.substring(rootPath.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                return rel.isEmpty() ? "./" : rel;
            }
            return path;
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvFileName;
            TextView tvFileDetails;
            ImageView btFileMenu;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.IVIcon);
                tvFileName = itemView.findViewById(R.id.TVFileName);
                tvFileDetails = itemView.findViewById(R.id.TVFileDetails);
                btFileMenu = itemView.findViewById(R.id.BTFileMenu);
            }
        }
    }
}
