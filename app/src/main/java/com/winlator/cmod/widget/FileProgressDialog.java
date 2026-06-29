package com.winlator.cmod.widget;

import android.app.Activity;
import android.app.Dialog;
import android.text.format.Formatter;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.winlator.cmod.R;

public class FileProgressDialog {
    private final Activity activity;
    private Dialog dialog;
    private TextView tvTitle;
    private TextView tvFileName;
    private TextView tvProgressPercentage;
    private TextView tvProgressSize;
    private ProgressBar progressBar;
    private Button btCancel;
    private Runnable onCancelListener;

    public FileProgressDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.file_progress_dialog);

        tvTitle = dialog.findViewById(R.id.TVTitle);
        tvFileName = dialog.findViewById(R.id.TVFileName);
        tvProgressPercentage = dialog.findViewById(R.id.TVProgressPercentage);
        tvProgressSize = dialog.findViewById(R.id.TVProgressSize);
        progressBar = dialog.findViewById(R.id.ProgressBar);
        btCancel = dialog.findViewById(R.id.BTCancel);
        btCancel.setOnClickListener(v -> {
            if (onCancelListener != null) onCancelListener.run();
            dismiss();
        });
    }

    public void setOnCancelListener(Runnable onCancelListener) {
        this.onCancelListener = onCancelListener;
    }

    public void show(int titleResId) {
        activity.runOnUiThread(() -> {
            if (dialog == null) create();
            tvTitle.setText(titleResId);
            tvFileName.setText("");
            tvProgressPercentage.setText("0%");
            tvProgressSize.setText("");
            progressBar.setProgress(0);
            if (!dialog.isShowing()) dialog.show();
        });
    }

    public void update(String fileName, long currentBytes, long totalBytes) {
        String currentStr = Formatter.formatFileSize(activity, currentBytes);
        String totalStr = Formatter.formatFileSize(activity, totalBytes);
        update(fileName, currentBytes, totalBytes, currentStr + " / " + totalStr);
    }

    public void update(String fileName, long current, long total, String customProgressText) {
        activity.runOnUiThread(() -> {
            if (dialog == null || !dialog.isShowing()) return;
            tvFileName.setText(fileName);
            int progress = total > 0 ? (int) ((current * 100) / total) : 0;
            progressBar.setProgress(progress);
            tvProgressPercentage.setText(progress + "%");
            tvProgressSize.setText(customProgressText);
        });
    }

    public void dismiss() {
        activity.runOnUiThread(() -> {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        });
    }
}
