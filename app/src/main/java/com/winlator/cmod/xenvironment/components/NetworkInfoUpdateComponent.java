package com.winlator.cmod.xenvironment.components;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.NetworkHelper;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.List;

public class NetworkInfoUpdateComponent extends EnvironmentComponent {
    private static final String TAG = "NetworkInfoUpdate";
    private BroadcastReceiver broadcastReceiver;
    private final Container container;

    public NetworkInfoUpdateComponent() {
        this(null);
    }

    public NetworkInfoUpdateComponent(Container container) {
        this.container = container;
    }

    @Override
    public void start() {
        Log.i(TAG, "Starting NetworkInfoUpdateComponent...");
        Context context = environment.getContext();
        final NetworkHelper networkHelper = new NetworkHelper(context);

        // Apply immediately on start
        updateAll(networkHelper);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                updateAll(networkHelper);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(broadcastReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register connectivity receiver: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        Log.i(TAG, "Stopping NetworkInfoUpdateComponent...");
        if (broadcastReceiver != null) {
            try {
                environment.getContext().unregisterReceiver(broadcastReceiver);
            } catch (Exception ignored) {}
            broadcastReceiver = null;
        }
    }

    private void updateAll(NetworkHelper networkHelper) {
        String ipv4 = networkHelper.getIPv4Address();
        String netmask = networkHelper.getNetmask();
        String gateway = networkHelper.getGateway();
        List<NetworkHelper.IFAddress> ifAddresses = networkHelper.getIFAddresses();

        Log.i(TAG, "Updating network info: IPv4=" + ipv4 + ", Netmask=" + netmask + ", Gateway=" + gateway);

        updateEtcHostsFiles(ipv4);
        updateAdapterInfoFile(ipv4, netmask, gateway);
        updateIFAddrsFile(ifAddresses, ipv4, netmask);
    }

    private void updateEtcHostsFiles(String ipAddress) {
        String ip = (ipAddress != null && !ipAddress.isEmpty()) ? ipAddress : "127.0.0.1";

        // Hosts file format: loopback first, then the actual LAN IP for localhost resolution
        String hostsContent;
        if (!ip.equals("127.0.0.1")) {
            hostsContent = "127.0.0.1\tlocalhost\n" + ip + "\tlocalhost\n";
        } else {
            hostsContent = "127.0.0.1\tlocalhost\n";
        }

        ImageFs imageFs = environment.getImageFs();
        File rootDir = imageFs.getRootDir();

        // 1. Linux rootfs etc/hosts
        File etcDir = new File(rootDir, "etc");
        if (!etcDir.exists()) etcDir.mkdirs();
        FileUtils.writeString(new File(etcDir, "hosts"), hostsContent);

        // 2. Linux rootfs usr/etc/hosts
        File usrEtcDir = new File(rootDir, "usr/etc");
        if (!usrEtcDir.exists()) usrEtcDir.mkdirs();
        FileUtils.writeString(new File(usrEtcDir, "hosts"), hostsContent);

        // 3. Active container prefix (symlinked /home/xuser/.wine)
        File activeWineEtc = new File(rootDir, "home/" + ImageFs.USER + "/.wine/drive_c/windows/system32/drivers/etc");
        if (activeWineEtc.exists() || activeWineEtc.mkdirs()) {
            FileUtils.writeString(new File(activeWineEtc, "hosts"), hostsContent);
        }

        // 4. Direct container directory if container reference is available
        if (container != null && container.getRootDir() != null) {
            File directWineEtc = new File(container.getRootDir(), ".wine/drive_c/windows/system32/drivers/etc");
            if (directWineEtc.exists() || directWineEtc.mkdirs()) {
                FileUtils.writeString(new File(directWineEtc, "hosts"), hostsContent);
            }
        }
    }

    private void updateAdapterInfoFile(String ipAddress, String netmask, String gateway) {
        String ip = (ipAddress != null && !ipAddress.isEmpty()) ? ipAddress : "127.0.0.1";
        String nm = (netmask != null && !netmask.isEmpty()) ? netmask : "255.255.255.0";
        String gw = (gateway != null && !gateway.isEmpty()) ? gateway : "192.168.1.1";

        String content = "Android Wi-Fi Adapter," + ip + "," + nm + "," + gw + "\n";

        ImageFs imageFs = environment.getImageFs();
        File tmpDir = imageFs.getTmpDir(); // usr/tmp
        if (tmpDir.exists() || tmpDir.mkdirs()) {
            FileUtils.writeString(new File(tmpDir, "adapterinfo"), content);
        }

        // Also write to rootDir/tmp in case any wrapper checks /tmp/adapterinfo
        File rootTmpDir = new File(imageFs.getRootDir(), "tmp");
        if (rootTmpDir.exists() || rootTmpDir.mkdirs()) {
            FileUtils.writeString(new File(rootTmpDir, "adapterinfo"), content);
        }
    }

    private void updateIFAddrsFile(List<NetworkHelper.IFAddress> ifAddresses, String ipv4, String netmask) {
        StringBuilder sb = new StringBuilder();
        if (ifAddresses != null && !ifAddresses.isEmpty()) {
            for (NetworkHelper.IFAddress ifAddress : ifAddresses) {
                sb.append(ifAddress.toString()).append("\n");
            }
        } else if (ipv4 != null && !ipv4.isEmpty()) {
            NetworkHelper.IFAddress ifAddress = new NetworkHelper.IFAddress();
            ifAddress.name = "wlan0";
            ifAddress.address = ipv4;
            ifAddress.netmask = (netmask != null) ? netmask : "255.255.255.0";
            sb.append(ifAddress.toString()).append("\n");
        } else {
            sb.append(new NetworkHelper.IFAddress().toString()).append("\n");
        }

        ImageFs imageFs = environment.getImageFs();
        File tmpDir = imageFs.getTmpDir();
        if (tmpDir.exists() || tmpDir.mkdirs()) {
            FileUtils.writeString(new File(tmpDir, "ifaddrs"), sb.toString());
        }

        File rootTmpDir = new File(imageFs.getRootDir(), "tmp");
        if (rootTmpDir.exists() || rootTmpDir.mkdirs()) {
            FileUtils.writeString(new File(rootTmpDir, "ifaddrs"), sb.toString());
        }
    }
}
