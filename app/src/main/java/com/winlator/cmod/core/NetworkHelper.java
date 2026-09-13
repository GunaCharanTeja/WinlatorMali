package com.winlator.cmod.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.system.OsConstants;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class NetworkHelper {
    private final ConnectivityManager connectivityManager;

    public static class IFAddress {
        public String name = "wlan0";
        public int flags = OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
        public int family = OsConstants.AF_INET;
        public int scopeId = 0;
        public String address = "127.0.0.1";
        public String netmask = "255.255.255.0";

        public String toString() {
            return name + "," + flags + "," + family + "," + scopeId + "," + address + "," + netmask;
        }
    }

    public NetworkHelper(Context context) {
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public String getIPv4Address() {
        if (connectivityManager == null) return null;
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return null;
            LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
            if (linkProperties == null) return null;

            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public String getNetmask() {
        if (connectivityManager == null) return "255.255.255.0";
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return "255.255.255.0";
            LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
            if (linkProperties == null) return "255.255.255.0";

            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return formatNetmask(linkAddress.getPrefixLength());
                }
            }
        } catch (Exception ignored) {}
        return "255.255.255.0";
    }

    public String getGateway() {
        if (connectivityManager == null) return "192.168.1.1";
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return "192.168.1.1";
            LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
            if (linkProperties == null) return "192.168.1.1";

            for (RouteInfo route : linkProperties.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() != null && route.getGateway() instanceof Inet4Address) {
                    return route.getGateway().getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.1";
    }

    public List<IFAddress> getIFAddresses() {
        ArrayList<IFAddress> result = new ArrayList<>();
        if (connectivityManager == null) return result;
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return result;
            LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
            if (linkProperties == null) return result;

            String interfaceName = linkProperties.getInterfaceName();
            if (interfaceName == null || interfaceName.isEmpty()) interfaceName = "wlan0";

            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address) {
                    IFAddress ifAddress = new IFAddress();
                    ifAddress.name = interfaceName;
                    ifAddress.address = address.getHostAddress();
                    ifAddress.netmask = formatNetmask(linkAddress.getPrefixLength());
                    ifAddress.flags = OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
                    result.add(ifAddress);
                } else if (address instanceof Inet6Address) {
                    IFAddress ifAddress = new IFAddress();
                    ifAddress.name = interfaceName;
                    ifAddress.family = OsConstants.AF_INET6;
                    ifAddress.scopeId = ((Inet6Address) address).getScopeId();
                    ifAddress.address = address.getHostAddress();
                    ifAddress.netmask = formatNetmask(linkAddress.getPrefixLength());
                    ifAddress.flags = OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
                    result.add(ifAddress);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static String formatNetmask(int prefixLength) {
        switch (prefixLength) {
            case 8: return "255.0.0.0";
            case 16: return "255.255.0.0";
            case 24: return "255.255.255.0";
            case 32: return "255.255.255.255";
            case 64: return "ffff:ffff:ffff:ffff::";
            default:
                if (prefixLength > 0 && prefixLength <= 32) {
                    int mask = 0xffffffff << (32 - prefixLength);
                    return ((mask >> 24) & 0xff) + "." +
                           ((mask >> 16) & 0xff) + "." +
                           ((mask >> 8) & 0xff) + "." +
                           (mask & 0xff);
                }
                return "255.255.255.0";
        }
    }

    public boolean isConnected() {
        if (connectivityManager == null) return false;
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
                return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            }
        } catch (Exception ignored) {}
        return false;
    }
}
