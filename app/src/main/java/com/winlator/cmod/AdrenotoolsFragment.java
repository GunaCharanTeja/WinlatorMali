package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import com.winlator.cmod.R;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import java.util.ArrayList;

public class AdrenotoolsFragment extends Fragment {
    
    private AdrenotoolsManager adrenotoolsManager;
    private RecyclerView recyclerView;
    private TextView tvEmpty;

    private void updateEmptyState() {
        if (tvEmpty == null || recyclerView.getAdapter() == null) return;
        tvEmpty.setVisibility(recyclerView.getAdapter().getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }
    
    @Override 
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.adrenotoolsManager = new AdrenotoolsManager(getActivity());
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup)inflater.inflate(R.layout.adrenotools_fragment, container, false);
        recyclerView = layout.findViewById(R.id.RecyclerView);
        tvEmpty = layout.findViewById(R.id.TVEmpty);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.setAdapter(new DriversAdapter(adrenotoolsManager.enumarateInstalledDrivers()));
        updateEmptyState();
        View btInstallDriver = layout.findViewById(R.id.BTInstallDriver);
        btInstallDriver.setOnClickListener((v) -> {
            ContentDialog.confirm(getContext(), getString(R.string.install_drivers_message) + " " + getString(R.string.install_drivers_warning), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);               
            });
        });
        return layout;
    }
    
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ThemeManager.applyThemeToView(view, getContext());
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.adrenotools_gpu_drivers);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            String driver = adrenotoolsManager.installDriver(uri);
            if (!driver.isEmpty())
                ((DriversAdapter)recyclerView.getAdapter()).addItem(driver);
        }
     }       
    
    private class DriversAdapter extends RecyclerView.Adapter<DriversAdapter.ViewHolder> {
        private ArrayList<String> driversList;

        public class ViewHolder extends RecyclerView.ViewHolder {
            private TextView tvName;
            private TextView tvVersion;
            private ImageButton btMenu;

            public ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.TVName);
                tvVersion = v.findViewById(R.id.TVVersion);
                btMenu = v.findViewById(R.id.BTMenu);
            }
        }
        
        public DriversAdapter(ArrayList<String> driversList) {
            this.driversList = driversList;
        }
        
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.adrenotools_list_item, viewGroup, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder viewHolder, final int position) {
            String driver = driversList.get(position);
            String name = adrenotoolsManager.getDriverName(driver);
            viewHolder.tvName.setText(name);
            viewHolder.tvVersion.setText(adrenotoolsManager.getDriverVersion(driver));
            viewHolder.btMenu.setOnClickListener((v) -> {
                // Looked up at click time: the position captured at bind time is stale after an earlier removal.
                int current = viewHolder.getBindingAdapterPosition();
                if (current == RecyclerView.NO_POSITION) return;
                ContentDialog.confirm(getContext(), "Remove the driver \"" + name + "\"?", () -> removeAtIndex(current));
            });
        }

        public void addItem(String item) {
            driversList.add(item);
            notifyItemInserted(getItemCount() - 1);
            updateEmptyState();
        }

        public void removeAtIndex(int index) {
            if (index < 0 || index >= driversList.size()) return;
            String deletedDriver = driversList.remove(index);
            adrenotoolsManager.removeDriver(deletedDriver);
            notifyItemRemoved(index);
            notifyItemRangeChanged(index, getItemCount());
            updateEmptyState();
        }
        
        @Override
        public int getItemCount() {
            return driversList.size();
        }
    }
}
