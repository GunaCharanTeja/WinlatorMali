package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.SharedPreferences;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.StorageInfoDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ContainersFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ThemeManager.applyThemeToView(view, getContext());
        manager = new ContainerManager(getContext());
        loadContainersList();
        androidx.appcompat.app.ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.containers);
        }

        checkFirstLaunchWineSetup();
    }

    private void checkFirstLaunchWineSetup() {
        if (getActivity() != null) {
            com.winlator.cmod.contents.WineDownloader.checkFirstLaunchWineSetup(getActivity());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        recyclerView.setClipToPadding(false);
        return frameLayout;
    }

    private void loadContainersList() {
        ArrayList<Container> containers = manager.getContainers();
        recyclerView.setAdapter(new ContainersAdapter(containers));
        if (containers.isEmpty()) {
            emptyTextView.setText("No containers yet\n\nTap + above to create one");
            emptyTextView.setTextSize(16);
            if (getContext() != null) emptyTextView.setTextColor((ThemeManager.getOnSurfaceTextColor(getContext()) & 0x00FFFFFF) | 0xB3000000);
            emptyTextView.setVisibility(View.VISIBLE);
        }
        else emptyTextView.setVisibility(View.GONE);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        // Clear any existing menu items to prevent duplication
        menu.clear();
        menuInflater.inflate(R.menu.containers_menu, menu);
        if (getContext() != null) {
            int accent = ThemeManager.getAccentColor(getContext());
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (item.getIcon() != null) {
                    Drawable icon = item.getIcon().mutate();
                    icon.setTint(accent);
                    item.setIcon(icon);
                }
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.containers_menu_add:
                if (!ImageFs.find(getContext()).isValid()) return false;
                List<String> installed = com.winlator.cmod.contents.WineDownloader.getInstalledWineVersions(getContext());
                if (installed.isEmpty()) {
                    new AlertDialog.Builder(getContext())
                        .setTitle("Wine Runtime Required")
                        .setMessage("No Wine or Proton runtimes are installed yet.\n\nWould you like to download Proton 10 arm64ec (Recommended) now?")
                        .setPositiveButton("Download Runtime", (dialog, which) -> {
                            com.winlator.cmod.contents.WineDownloader.showDownloadDialog(getActivity(), () -> {
                                FragmentManager fm = getParentFragmentManager();
                                fm.beginTransaction()
                                    .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                                    .addToBackStack(null)
                                    .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                                    .commit();
                            });
                        })
                        .setNegativeButton("Configure Manually", (dialog, which) -> {
                            FragmentManager fm = getParentFragmentManager();
                            fm.beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                                .commit();
                        })
                        .show();
                    return true;
                }
                FragmentManager fragmentManager = getParentFragmentManager();
                fragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                        .addToBackStack(null)
                        .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                        .commit();
                return true;

            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }


    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton; // Changed to ImageButton
            private final ImageView menuButton; // Changed to ImageButton
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;
            private final TextView archChip;

            private ViewHolder(View view) {
                super(view);
                this.runButton = view.findViewById(R.id.BTRun); // Find by correct ID
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.archChip = view.findViewById(R.id.TVArchChip);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.runButton.setOnClickListener(null); // Remove listeners
            holder.menuButton.setOnClickListener(null); // Remove listeners
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position); // Use 'item' instead of undefined 'container'
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getName());

            if (getContext() != null) {
                Context context = getContext();
                int accent = ThemeManager.getAccentColor(context);
                int onSurface = ThemeManager.getOnSurfaceTextColor(context);
                float density = context.getResources().getDisplayMetrics().density;
                if (holder.itemView instanceof androidx.cardview.widget.CardView)
                    ((androidx.cardview.widget.CardView) holder.itemView).setCardBackgroundColor(ThemeManager.getSurfaceColor(context));
                holder.title.setTextColor(onSurface);
                holder.imageView.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
                holder.menuButton.setImageTintList(android.content.res.ColorStateList.valueOf((onSurface & 0x00FFFFFF) | 0xB3000000));

                // Run: a round filled accent button, its icon dark or light by the accent's brightness.
                android.graphics.drawable.GradientDrawable runBg = new android.graphics.drawable.GradientDrawable();
                runBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                runBg.setColor(accent);
                holder.runButton.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), runBg, null));
                holder.runButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.5 ? 0xFF101010 : 0xFFFFFFFF));

                // What the container runs: arch chip, then Wine/Proton version and screen size.
                String wine = item.getWineVersion() != null ? item.getWineVersion() : "";
                boolean arm64ec = wine.toLowerCase().contains("arm64ec");
                holder.archChip.setVisibility(wine.isEmpty() ? View.GONE : View.VISIBLE);
                holder.archChip.setText(arm64ec ? "ARM64EC" : "X86_64");
                android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
                chipBg.setCornerRadius(6 * density);
                chipBg.setColor((accent & 0x00FFFFFF) | (0x40 << 24));
                holder.archChip.setBackground(chipBg);
                holder.archChip.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(accent, onSurface, 0.5f));
                boolean proton = new java.io.File(context.getFilesDir(), "contents/proton/" + wine).exists();
                String version = wine.replaceAll("-\\d+$", "").replaceAll("(?i)-?(arm64ec|x86_64)", "");
                String sub = wine.isEmpty() ? "" : (proton ? "Proton " : "Wine ") + version;
                String screen = item.getScreenSize();
                if (screen != null && !screen.isEmpty()) sub += (sub.isEmpty() ? "" : " · ") + screen;
                holder.subtitle.setText(sub);
                holder.subtitle.setTextColor((onSurface & 0x00FFFFFF) | 0xA6000000);
            }

            holder.runButton.setOnClickListener(view -> runContainer(item)); // Correct item reference

            holder.menuButton.setOnClickListener(view -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void runContainer(Container container) {
            Intent intent = new Intent(getContext(), XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            requireActivity().startActivity(intent);
        }

        private void showListItemMenu(View anchorView, Container container) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            listItemMenu.inflate(R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            Menu menu = listItemMenu.getMenu();
            int accent = ThemeManager.getAccentColor(context);
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (item.getIcon() != null) {
                    Drawable icon = item.getIcon().mutate();
                    icon.setTint(accent);
                    item.setIcon(icon);
                }
            }

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.container_edit:
                        FragmentManager fragmentManager = getParentFragmentManager();
                        fragmentManager.beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment(container.id))
                                .commit();
                        break;
                    case R.id.container_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            for (Shortcut shortcut : manager.loadShortcuts()) {
                                if (shortcut.container == container)
                                    ShortcutsFragment.disableShortcutOnScreen(context, shortcut);
                            }
                            manager.removeContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_save_manager:
                        new com.winlator.cmod.saves.SaveManagerDialog(getActivity(), container).show();
                        break;
                    case R.id.container_info:
                        (new StorageInfoDialog(getActivity(), container)).show();
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }
    }



}
