package com.winlator.cmod.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WineInfo implements Parcelable {
    public static final WineInfo MAIN_WINE_VERSION = new WineInfo("proton","10", "arm64ec");
    private static final Pattern pattern = Pattern.compile("^(wine|proton)\\-([0-9\\.]+)\\-?([0-9\\.]+)?\\-(x86|x86_64|arm64ec)$");
    public final String version;
    public final String type;
    public String subversion;
    public final String path;
    private String arch;

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
    }

    private WineInfo(Parcel in) {
        type = in.readString();
        version = in.readString();
        subversion = in.readString();
        arch = in.readString();
        path = in.readString();
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public boolean isWin64() {
        return arch.equals("x86_64") || arch.equals("arm64ec");
    }

    public boolean isArm64EC() { return arch.equals("arm64ec"); }

    public String identifier() {
        if (type.equals("proton"))
            return "proton-" + fullVersion() + "-"+ arch;
        else
            return "wine-" + fullVersion() + "-" + arch;
    }

    public String fullVersion() {
        return version+(subversion != null ? "-"+subversion : "");
    }

    @NonNull
    @Override
    public String toString() {
        if (type.equals("proton"))
            return "Proton "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
        else
            return "Wine "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(version);
        dest.writeString(subversion);
        dest.writeString(arch);
        dest.writeString(path);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        ImageFs imageFs = ImageFs.find(context);
        Log.d("WineInfo", "Creating WineInfo from identifier: " + identifier);

        if (identifier == null || identifier.isEmpty()) {
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());
        }

        String cleanId = identifier;
        ContentProfile wineProfile = contentsManager != null ? contentsManager.getProfileByEntryName(identifier) : null;
        if (wineProfile != null && wineProfile.verName != null && !wineProfile.verName.isEmpty()) {
            cleanId = wineProfile.verName;
        } else if (cleanId.matches(".*\\-\\d+$")) {
            cleanId = cleanId.replaceAll("\\-\\d+$", "");
        }

        String type = cleanId.contains("proton") ? "proton" : "wine";
        String arch = cleanId.contains("arm64ec") ? "arm64ec" : (cleanId.contains("x86_64") ? "x86_64" : "x86");
        String version = "9.0";
        Matcher m = Pattern.compile("(\\d+(\\.\\d+)*)").matcher(cleanId);
        if (m.find()) {
            version = m.group(1);
        }

        // Resolve absolute path on disk
        String path = "";
        File optPath = new File(imageFs.getRootDir(), "opt/" + cleanId);
        if (optPath.exists()) {
            path = optPath.getAbsolutePath();
        } else if (wineProfile != null) {
            File installDir = ContentsManager.getInstallDir(context, wineProfile);
            if (installDir.exists()) path = installDir.getAbsolutePath();
        }

        if (path.isEmpty()) {
            path = imageFs.getRootDir().getPath() + "/opt/" + cleanId;
        }

        Log.d("WineInfo", "Resolved WineInfo: type=" + type + ", ver=" + version + ", arch=" + arch + ", path=" + path);
        return new WineInfo(type, version, arch, path);
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null ||wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
