package com.winlator.cmod.contents;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.winlator.cmod.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads and installs catalog contents independently of the screen that started them: several at a time, each in
 * its own staging directory, kept running in the background by ContentDownloadService. The Contents screen only shows
 * the state of each task.
 */
public final class ContentDownloads {
    public enum State {DOWNLOADING, INSTALLING, DONE, FAILED}

    public static class Task {
        public final String url;
        public final String name;
        public volatile State state = State.DOWNLOADING;
        public volatile long currentBytes;
        public volatile long totalBytes;
        public volatile int percent = -1;
        public volatile float speedMBs;
        public volatile String message;

        Task(String url, String name) {
            this.url = url;
            this.name = name;
        }

        public boolean isActive() {
            return state == State.DOWNLOADING || state == State.INSTALLING;
        }
    }

    public interface Listener {
        // Called on the main thread when a task finishes (DONE or FAILED); progress is read from the tasks.
        void onTaskFinished(Task task);
    }

    private static final int MAX_PARALLEL = 3;
    private static final Map<String, Task> tasks = new LinkedHashMap<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ContentDownloads() {}

    public static synchronized Task get(String url) {
        return url != null ? tasks.get(url) : null;
    }

    public static synchronized List<Task> getActiveTasks() {
        List<Task> active = new ArrayList<>();
        for (Task task : tasks.values()) if (task.isActive()) active.add(task);
        return active;
    }

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Starts downloading and installing a catalog entry; returns false if it is already in progress. */
    public static boolean start(Context context, ContentProfile profile) {
        if (profile == null || profile.remoteUrl == null) return false;
        final Context appContext = context.getApplicationContext();
        final Task task;
        synchronized (ContentDownloads.class) {
            Task existing = tasks.get(profile.remoteUrl);
            if (existing != null && existing.isActive()) return false;
            task = new Task(profile.remoteUrl, profile.verName != null ? profile.verName : "Component");
            tasks.put(profile.remoteUrl, task);
        }

        ContextCompat.startForegroundService(appContext, new Intent(appContext, ContentDownloadService.class));
        executor.execute(() -> run(appContext, task));
        return true;
    }

    private static void run(Context context, Task task) {
        // Kept in the app's files, not its cache: the cache is cleared when the Contents screen closes.
        File workDir = new File(context.getFilesDir(), "content_downloads/" + Integer.toHexString(task.url.hashCode()) + "_" + System.nanoTime());
        File archive = new File(workDir, "package");
        File stagingDir = new File(workDir, "staging");
        workDir.mkdirs();
        try {
            boolean downloaded = Downloader.downloadFile(task.url, archive, (currentBytes, totalBytes, percent, speedMBs) -> {
                task.currentBytes = currentBytes;
                task.totalBytes = totalBytes;
                task.percent = percent;
                task.speedMBs = speedMBs;
            });
            if (!downloaded) {
                finish(task, State.FAILED, "Download failed. Please check network connection.");
                return;
            }

            task.state = State.INSTALLING;
            ContentsManager manager = new ContentsManager(context);
            final String[] error = {null};
            final boolean[] installed = {false};
            ContentsManager.OnInstallFinishedCallback finished = new ContentsManager.OnInstallFinishedCallback() {
                @Override
                public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                    error[0] = failureMessage(reason);
                }

                @Override
                public void onSucceed(ContentProfile profile) {
                    installed[0] = true;
                }
            };
            manager.extraContentFile(Uri.parse(archive.getAbsolutePath()), stagingDir, new ContentsManager.OnInstallFinishedCallback() {
                @Override
                public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                    error[0] = failureMessage(reason);
                }

                @Override
                public void onSucceed(ContentProfile profile) {
                    if (profile.installedDirect)
                        installed[0] = true;
                    else
                        manager.finishInstallContent(profile, stagingDir, finished);
                }
            });

            if (installed[0])
                finish(task, State.DONE, "Installed " + task.name);
            else
                finish(task, State.FAILED, "Installation failed: " + (error[0] != null ? error[0] : "Unable to install content"));
        } catch (Exception e) {
            finish(task, State.FAILED, "Installation failed: " + e.getMessage());
        } finally {
            FileUtils.delete(workDir);
        }
    }

    private static String failureMessage(ContentsManager.InstallFailedReason reason) {
        return switch (reason) {
            case ERROR_BADTAR -> "Corrupted archive file";
            case ERROR_NOSPACE -> "No space left on device";
            case ERROR_NOPROFILE -> "Profile not found in package";
            case ERROR_BADPROFILE -> "Profile cannot be recognized";
            case ERROR_EXIST -> "Content already exists";
            case ERROR_MISSINGFILES -> "Content is incomplete";
            case ERROR_UNTRUSTPROFILE -> "Content cannot be trusted";
            default -> "Unable to install content";
        };
    }

    private static void finish(Task task, State state, String message) {
        task.message = message;
        task.state = state;
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onTaskFinished(task);
        });
    }
}
