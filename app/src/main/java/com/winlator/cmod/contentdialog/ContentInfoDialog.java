package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;

import java.util.List;

public class ContentInfoDialog extends ContentDialog {
    public ContentInfoDialog(Context context, ContentProfile profile) {
        super(context, R.layout.content_info_dialog);
        setIcon(R.drawable.icon_about);
        setTitle(R.string.content_info);

        TextView tvType = findViewById(R.id.TVType);
        TextView tvVersion = findViewById(R.id.TVVersion);
        TextView tvVersionCode = findViewById(R.id.TVVersionCode);
        TextView tvSize = findViewById(R.id.TVSize);
        TextView tvStatus = findViewById(R.id.TVStatus);
        TextView tvDescription = findViewById(R.id.TVDesc);
        TextView tvUrl = findViewById(R.id.TVUrl);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);

        View llVersionCode = findViewById(R.id.LLVersionCode);
        View llSize = findViewById(R.id.LLSize);
        View llStatus = findViewById(R.id.LLStatus);
        View llDesc = findViewById(R.id.LLDesc);
        View llUrl = findViewById(R.id.LLUrl);
        View llFileList = findViewById(R.id.LLFileList);

        if (profile != null) {
            tvType.setText(profile.type != null ? profile.type.toString() : "Wine");
            tvVersion.setText(profile.verName != null ? profile.verName : "Unknown");

            if (profile.verCode > 0) {
                llVersionCode.setVisibility(View.VISIBLE);
                tvVersionCode.setText(String.valueOf(profile.verCode));
            } else {
                llVersionCode.setVisibility(View.GONE);
            }

            if (profile.sizeFormatted != null && !profile.sizeFormatted.isEmpty()) {
                llSize.setVisibility(View.VISIBLE);
                tvSize.setText(profile.sizeFormatted);
            } else if (profile.size > 0) {
                llSize.setVisibility(View.VISIBLE);
                tvSize.setText(com.winlator.cmod.contents.Downloader.formatFileSize(profile.size));
            } else {
                llSize.setVisibility(View.GONE);
            }

            boolean isCloud = profile.remoteUrl != null && !profile.remoteUrl.isEmpty();
            llStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(isCloud ? "Cloud (Ready to Download)" : "Installed on Device");
            tvStatus.setTextColor(isCloud ? 0xFF00A8FF : 0xFF2ECC71);

            if (profile.desc != null && !profile.desc.isEmpty()) {
                llDesc.setVisibility(View.VISIBLE);
                tvDescription.setText(profile.desc);
            } else {
                llDesc.setVisibility(View.GONE);
            }

            if (isCloud) {
                llUrl.setVisibility(View.VISIBLE);
                tvUrl.setText(profile.remoteUrl);
            } else {
                llUrl.setVisibility(View.GONE);
            }

            if (profile.fileList != null && !profile.fileList.isEmpty()) {
                llFileList.setVisibility(View.VISIBLE);
                recyclerView.setAdapter(new ContentInfoFileAdapter(profile.fileList));
                recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
                recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
            } else {
                llFileList.setVisibility(View.GONE);
            }
        }
    }

    public static class ContentInfoFileAdapter extends RecyclerView.Adapter<ContentInfoFileAdapter.ViewHolder> {
        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvSource;
            private final TextView tvtarget;

            private ViewHolder(View view) {
                super(view);
                tvSource = view.findViewById(R.id.TVFileSource);
                tvtarget = view.findViewById(R.id.TVFileTarget);
            }
        }

        private final List<ContentProfile.ContentFile> data;

        public ContentInfoFileAdapter(List<ContentProfile.ContentFile> data) {
            this.data = data != null ? data : new java.util.ArrayList<>();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ContentInfoFileAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.content_file_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            if (data != null && position < data.size()) {
                ContentProfile.ContentFile file = data.get(position);
                holder.tvSource.setText((file.source != null ? file.source : "") + " ->");
                holder.tvtarget.setText('\t' + (file.target != null ? file.target : ""));
            }
        }

        @Override
        public int getItemCount() {
            return data != null ? data.size() : 0;
        }
    }
}
