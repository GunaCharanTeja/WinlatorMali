package com.winlator.cmod.win32;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.StreamUtils;
import com.winlator.cmod.core.StringUtils;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Stack;

public class PEParser {
    private static final byte RT_ICON = 3;
    private static final byte RT_VERSION = 16;
    private final File peFile;
    private int resourcesRVA = 0;
    private int resourcesOffset = 0;

    public static class FileVersionInfo {
        public String Comments = "";
        public String CompanyName = "";
        public String FileDescription = "";
        public String FileVersion = "";
        public String InternalName = "";
        public String LegalCopyright = "";
        public String LegalTrademarks = "";
        public String OriginalFilename = "";
        public String PrivateBuild = "";
        public String ProductName = "";
        public String ProductVersion = "";
        public String SpecialBuildprivate = "";
    }

    private interface ImageResourceEntry {
    }

    private static class ImageResourceDirectoryEntry implements ImageResourceEntry {
        private final boolean dataIsDirectory;
        private ImageResourceDirectory directory;
        private final int name;
        private final boolean nameIsString;
        private final int offsetToData;

        private ImageResourceDirectoryEntry(ByteBuffer data) {
            int field1 = data.getInt();
            int field2 = data.getInt();
            this.name = field1 & Integer.MAX_VALUE;
            this.nameIsString = ((field1 >> 31) & 1) != 0;
            this.offsetToData = Integer.MAX_VALUE & field2;
            this.dataIsDirectory = ((field2 >> 31) & 1) != 0;
        }
    }

    private static class ImageResourceDataEntry implements ImageResourceEntry {
        private final int codePage;
        private final int offsetToData;
        private final int reserved;
        private final int size;

        private ImageResourceDataEntry(ByteBuffer data) {
            this.offsetToData = data.getInt();
            this.size = data.getInt();
            this.codePage = data.getInt();
            this.reserved = data.getInt();
        }
    }

    private static class ImageResourceDirectory {
        private final int characteristics;
        private final ArrayList<ImageResourceEntry> entries;
        private final short majorVersion;
        private final short minorVersion;
        private final short numberOfIdEntries;
        private final short numberOfNamedEntries;
        private final int timeDateStamp;

        private ImageResourceDirectory(byte type, ByteBuffer data, int level) {
            this.entries = new ArrayList<>();
            this.characteristics = data.getInt();
            this.timeDateStamp = data.getInt();
            this.majorVersion = data.getShort();
            this.minorVersion = data.getShort();
            short s = data.getShort();
            this.numberOfNamedEntries = s;
            short s2 = data.getShort();
            this.numberOfIdEntries = s2;
            int numberOfEntries = s + s2;
            for (int i = 0; i < numberOfEntries; i++) {
                ImageResourceDirectoryEntry directoryEntry = new ImageResourceDirectoryEntry(data);
                if ((directoryEntry.name == type && directoryEntry.dataIsDirectory) || (level > 0 && directoryEntry.dataIsDirectory)) {
                    int oldPosition = data.position();
                    data.position(directoryEntry.offsetToData);
                    directoryEntry.directory = new ImageResourceDirectory(type, data, level + 1);
                    data.position(oldPosition);
                    this.entries.add(0, directoryEntry);
                } else if (level > 0) {
                    int oldPosition2 = data.position();
                    data.position(directoryEntry.offsetToData);
                    ImageResourceDataEntry dataEntry = new ImageResourceDataEntry(data);
                    data.position(oldPosition2);
                    this.entries.add(0, dataEntry);
                }
            }
        }
    }

    private static class VSFixedFileInfo {
        private final int dwSignature;
        private final int dwStrucVersion;
        private final int dwFileVersionMS;
        private final int dwFileVersionLS;
        private final int dwProductVersionMS;
        private final int dwProductVersionLS;
        private final int dwFileFlagsMask;
        private final int dwFileFlags;
        private final int dwFileOS;
        private final int dwFileType;
        private final int dwFileSubtype;
        private final int dwFileDateMS;
        private final int dwFileDateLS;

        private VSFixedFileInfo(ByteBuffer data) {
            dwSignature = data.getInt();
            dwStrucVersion = data.getInt();
            dwFileVersionMS = data.getInt();
            dwFileVersionLS = data.getInt();
            dwProductVersionMS = data.getInt();
            dwProductVersionLS = data.getInt();
            dwFileFlagsMask = data.getInt();
            dwFileFlags = data.getInt();
            dwFileOS = data.getInt();
            dwFileType = data.getInt();
            dwFileSubtype = data.getInt();
            dwFileDateMS = data.getInt();
            dwFileDateLS = data.getInt();
        }
    }

    private static String readUnicodeString(ByteBuffer data) {
        ByteBuffer stringBuf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        short value;
        while ((value = data.getShort()) != 0) stringBuf.putShort(value);
        return new String(Arrays.copyOf(stringBuf.array(), stringBuf.position()), StandardCharsets.UTF_16LE);
    }

    private static class StringHdr {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final String value;

        private StringHdr(ByteBuffer data) {
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();

            key = readUnicodeString(data);
            int offset = data.position() - position;
            if ((offset & 3) != 0) data.getShort();

            if (valueLength > 0) {
                byte[] bytes = new byte[valueLength * 2];
                data.get(bytes, 0, bytes.length);
                value = readUnicodeString(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            }
            else value = null;
            if ((length & 3) != 0) data.getShort();
        }
    }

    private static class StringTable {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final ArrayList<StringHdr> stringHdrs = new ArrayList<>();

        private StringTable(ByteBuffer data) {
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();
            key = readUnicodeString(data);
            int offset = data.position() - position;
            if ((offset & 3) != 0) data.getShort();
            int remaining = length - offset;

            while (remaining > 0) {
                StringHdr stringhdr = new StringHdr(data);
                stringHdrs.add(stringhdr);
                remaining -= stringhdr.length;
            }
            if ((length & 3) != 0) data.getShort();
        }
    }

    private static class StringFileInfo {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final ArrayList<StringTable> stringTables = new ArrayList<>();

        private StringFileInfo(ByteBuffer data) {
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();
            key = readUnicodeString(data);
            if (!key.equals("StringFileInfo")) return;
            int offset = data.position() - position;
            if ((offset & 3) != 0) data.getShort();
            int remaining = length - offset;

            while (remaining > 0) {
                StringTable stringTable = new StringTable(data);
                stringTables.add(stringTable);
                remaining -= stringTable.length;
            }
            if ((length & 3) != 0) data.getShort();
        }
    }

    private static class VSVersionInfo {
        private final short length;
        private final short valueLength;
        private final short type;
        private final String key;
        private final VSFixedFileInfo value;
        private final StringFileInfo stringFileInfo;

        private VSVersionInfo(ByteBuffer data) {
            int position = data.position();
            length = data.getShort();
            valueLength = data.getShort();
            type = data.getShort();
            key = readUnicodeString(data);
            int offset = data.position() - position;
            if ((offset & 3) != 0) data.getShort();
            value = valueLength > 0 ? new VSFixedFileInfo(data) : null;

            if (value == null || value.dwStrucVersion != 0x10000) {
                stringFileInfo = null;
                return;
            }

            stringFileInfo = new StringFileInfo(data);
        }
    }

    private PEParser(File peFile) {
        this.peFile = peFile;
    }

    private ByteBuffer readResourceData(int dataOffset, int dataSize) {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(this.peFile), 65536)) {
            byte[] bytes = new byte[dataSize];
            StreamUtils.skip(inStream, dataOffset);
            int bytesRead = inStream.read(bytes);
            return bytesRead != -1 ? ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private ImageResourceDirectory readImageResourceDirectory(byte type) {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(this.peFile), 65536)) {
            ByteBuffer allocate = ByteBuffer.allocate(64);
            ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
            ByteBuffer dosHeader = allocate.order(byteOrder);
            int filePosition = 0 + inStream.read(dosHeader.array());
            short magicNumber = dosHeader.getShort();
            if (magicNumber == 23117) {
                dosHeader.position(60);
                int fileHeaderOffset = dosHeader.getInt() + 4;
                int filePosition2 = filePosition + StreamUtils.skip(inStream, fileHeaderOffset - filePosition);
                ByteBuffer fileHeader = ByteBuffer.allocate(20).order(byteOrder);
                int filePosition3 = filePosition2 + inStream.read(fileHeader.array());
                Short.toUnsignedInt(fileHeader.getShort());
                short numberOfSections = fileHeader.getShort();
                fileHeader.position(fileHeader.position() + 12);
                short sizeofOptionalHeader = fileHeader.getShort();
                int filePosition4 = filePosition3 + StreamUtils.skip(inStream, sizeofOptionalHeader);
                int i = 0;
                this.resourcesRVA = 0;
                this.resourcesOffset = 0;
                int resourcesSize = 0;
                ByteBuffer sectionHeader = ByteBuffer.allocate(40).order(byteOrder);
                byte[] nameBytes = new byte[8];
                byte i2 = 0;
                while (true) {
                    if (i2 >= numberOfSections) {
                        break;
                    }
                    sectionHeader.position(i);
                    filePosition4 += inStream.read(sectionHeader.array());
                    sectionHeader.get(nameBytes);
                    String name = StringUtils.fromANSIString(nameBytes);
                    if (!name.equals(".rsrc")) {
                        i2 = (byte) (i2 + 1);
                        i = 0;
                    } else {
                        sectionHeader.getInt();
                        this.resourcesRVA = sectionHeader.getInt();
                        resourcesSize = sectionHeader.getInt();
                        this.resourcesOffset = sectionHeader.getInt();
                        break;
                    }
                }
                int i3 = this.resourcesOffset;
                if (i3 > 0) {
                    int skip = filePosition4 + StreamUtils.skip(inStream, i3 - filePosition4);
                    ByteBuffer resourcesBuffer = ByteBuffer.allocate(resourcesSize).order(ByteOrder.LITTLE_ENDIAN);
                    inStream.read(resourcesBuffer.array(), 0, resourcesBuffer.limit());
                    return new ImageResourceDirectory(type, resourcesBuffer, 0);
                }
                return null;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private Bitmap decodeIcon(int iconIndex, boolean largeIcon, ArrayList<ImageResourceDataEntry> dataEntries) {
        int i = 0;
        while (i < dataEntries.size()) {
            ImageResourceDataEntry dataEntry = dataEntries.get(i);
            int fileOffset = (dataEntry.offsetToData - this.resourcesRVA) + this.resourcesOffset;
            ByteBuffer iconData = readResourceData(fileOffset, dataEntry.size);
            if (iconData != null) {
                boolean z = true;
                if (ImageUtils.isPNGData(iconData)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(iconData.array(), 0, iconData.limit(), options);
                    if (iconIndex >= 0) {
                        if (iconIndex == i) z = false;
                    } else if (largeIcon == (options.outWidth >= 32)) z = false;
                    boolean z2 = z;
                    if (!z2) {
                        return BitmapFactory.decodeByteArray(iconData.array(), 0, iconData.limit());
                    }
                } else {
                    int bitmapOffset = iconData.getInt();
                    int bmpWidth = iconData.getInt();
                    iconData.getInt();
                    iconData.getShort();
                    short bitCount = iconData.getShort();
                    int compression = iconData.getInt();
                    iconData.getInt();
                    iconData.getInt();
                    iconData.getInt();
                    int clrUsed = iconData.getInt();
                    if (bitCount != 8 || (compression == 0 && clrUsed == 0)) {
                        if (iconIndex >= 0) {
                            if (iconIndex == i) z = false;
                        } else if (bitCount >= 8 && largeIcon == (bmpWidth >= 32)) z = false;
                        boolean z3 = z;
                        if (!z3) {
                            iconData.position(bitmapOffset);
                            Bitmap bitmap = MSBitmap.decodeBuffer(bmpWidth, bmpWidth, bitCount, iconData);
                            if (bitmap != null) {
                                return bitmap;
                            }
                        }
                    }
                }
            }
            i++;
        }
        return null;
    }

    private ArrayList<ImageResourceDataEntry> readImageResourceDataEntries(ImageResourceDirectory rootDirectory) {
        ArrayList<ImageResourceDataEntry> dataEntries = new ArrayList<>();
        Stack<ImageResourceDirectory> stack = new Stack<>();
        stack.push(rootDirectory);
        while (!stack.isEmpty()) {
            ImageResourceDirectory directory = stack.pop();
            Iterator it = directory.entries.iterator();
            while (it.hasNext()) {
                ImageResourceEntry entry = (ImageResourceEntry) it.next();
                if (entry instanceof ImageResourceDirectoryEntry) {
                    stack.push(((ImageResourceDirectoryEntry) entry).directory);
                } else if (entry instanceof ImageResourceDataEntry) {
                    dataEntries.add((ImageResourceDataEntry) entry);
                }
            }
        }
        return dataEntries;
    }

    private Bitmap extractIcon(int iconIndex) {
        ImageResourceDirectory rootDirectory;
        if (!this.peFile.isFile() || (rootDirectory = readImageResourceDirectory(RT_ICON)) == null) {
            return null;
        }
        ArrayList<ImageResourceDataEntry> dataEntries = readImageResourceDataEntries(rootDirectory);
        if (iconIndex < 0) {
            Bitmap bitmap = decodeIcon(-1, true, dataEntries);
            if (bitmap != null) {
                return bitmap;
            }
            Bitmap bitmap2 = decodeIcon(-1, false, dataEntries);
            if (bitmap2 != null) {
                return bitmap2;
            }
            return null;
        }
        return decodeIcon(iconIndex, true, dataEntries);
    }

    public static FileVersionInfo getFileVersionInfo(File peFile) {
        if (!peFile.isFile()) return null;

        PEParser peParser = new PEParser(peFile);
        ImageResourceDirectory rootDirectory = peParser.readImageResourceDirectory(RT_VERSION);
        if (rootDirectory == null) return null;
        ArrayList<ImageResourceDataEntry> dataEntries = peParser.readImageResourceDataEntries(rootDirectory);
        if (dataEntries.isEmpty()) return null;

        ImageResourceDataEntry dataEntry = dataEntries.get(0);
        int fileOffset = dataEntry.offsetToData - peParser.resourcesRVA + peParser.resourcesOffset;
        ByteBuffer resourceData = peParser.readResourceData(fileOffset, dataEntry.size);
        if (resourceData == null) return null;

        VSVersionInfo versionInfo = new VSVersionInfo(resourceData);

        if (versionInfo.stringFileInfo != null) {
            FileVersionInfo fileVersionInfo = new FileVersionInfo();
            for (StringTable stringTable : versionInfo.stringFileInfo.stringTables) {
                for (StringHdr stringHdr : stringTable.stringHdrs) {
                    switch (stringHdr.key) {
                        case "Comments": if (fileVersionInfo.Comments.isEmpty()) fileVersionInfo.Comments = stringHdr.value; break;
                        case "CompanyName": if (fileVersionInfo.CompanyName.isEmpty()) fileVersionInfo.CompanyName = stringHdr.value; break;
                        case "FileDescription": if (fileVersionInfo.FileDescription.isEmpty()) fileVersionInfo.FileDescription = stringHdr.value; break;
                        case "FileVersion": if (fileVersionInfo.FileVersion.isEmpty()) fileVersionInfo.FileVersion = stringHdr.value; break;
                        case "InternalName": if (fileVersionInfo.InternalName.isEmpty()) fileVersionInfo.InternalName = stringHdr.value; break;
                        case "LegalCopyright": if (fileVersionInfo.LegalCopyright.isEmpty()) fileVersionInfo.LegalCopyright = stringHdr.value; break;
                        case "LegalTrademarks": if (fileVersionInfo.LegalTrademarks.isEmpty()) fileVersionInfo.LegalTrademarks = stringHdr.value; break;
                        case "OriginalFilename": if (fileVersionInfo.OriginalFilename.isEmpty()) fileVersionInfo.OriginalFilename = stringHdr.value; break;
                        case "PrivateBuild": if (fileVersionInfo.PrivateBuild.isEmpty()) fileVersionInfo.PrivateBuild = stringHdr.value; break;
                        case "ProductName": if (fileVersionInfo.ProductName.isEmpty()) fileVersionInfo.ProductName = stringHdr.value; break;
                        case "ProductVersion": if (fileVersionInfo.ProductVersion.isEmpty()) fileVersionInfo.ProductVersion = stringHdr.value; break;
                        case "SpecialBuildprivate": if (fileVersionInfo.SpecialBuildprivate.isEmpty()) fileVersionInfo.SpecialBuildprivate = stringHdr.value; break;
                    }
                }
            }
            return fileVersionInfo;
        }
        return null;
    }

    public static Bitmap extractIcon(File peFile) {
        return extractIcon(peFile, -1);
    }

    public static Bitmap extractIcon(File peFile, int iconIndex) {
        return new PEParser(peFile).extractIcon(iconIndex);
    }
}
