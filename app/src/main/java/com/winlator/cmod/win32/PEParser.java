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
import java.util.Locale;
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
        public VSFixedFileInfo fixedFileInfo;

        public boolean hasVersion() {
            return (FileVersion != null && !FileVersion.trim().isEmpty() && !isZeroVersion(FileVersion))
                || (ProductVersion != null && !ProductVersion.trim().isEmpty() && !isZeroVersion(ProductVersion))
                || (fixedFileInfo != null && fixedFileInfo.hasVersion());
        }

        public String getBestVersion() {
            if (ProductVersion != null && !ProductVersion.trim().isEmpty() && !isZeroVersion(ProductVersion)) {
                return ProductVersion.trim();
            }
            if (FileVersion != null && !FileVersion.trim().isEmpty() && !isZeroVersion(FileVersion)) {
                return FileVersion.trim();
            }
            if (fixedFileInfo != null) {
                String pv = fixedFileInfo.getFormattedProductVersion();
                if (pv != null && !isZeroVersion(pv)) return pv;
                String fv = fixedFileInfo.getFormattedFileVersion();
                if (fv != null && !isZeroVersion(fv)) return fv;
            }
            return (FileVersion != null && !FileVersion.trim().isEmpty()) ? FileVersion.trim() : ProductVersion;
        }

        private static boolean isZeroVersion(String ver) {
            if (ver == null || ver.trim().isEmpty()) return true;
            String clean = ver.replaceAll("[^0-9]", "");
            return clean.isEmpty() || clean.matches("^0+$");
        }
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

    public static class VSFixedFileInfo {
        public final int dwSignature;
        public final int dwStrucVersion;
        public final int dwFileVersionMS;
        public final int dwFileVersionLS;
        public final int dwProductVersionMS;
        public final int dwProductVersionLS;
        public final int dwFileFlagsMask;
        public final int dwFileFlags;
        public final int dwFileOS;
        public final int dwFileType;
        public final int dwFileSubtype;
        public final int dwFileDateMS;
        public final int dwFileDateLS;

        public VSFixedFileInfo(ByteBuffer data) {
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

        public boolean hasVersion() {
            return (dwFileVersionMS | dwFileVersionLS | dwProductVersionMS | dwProductVersionLS) != 0;
        }

        public String getFormattedFileVersion() {
            int v1 = (dwFileVersionMS >> 16) & 0xFFFF;
            int v2 = dwFileVersionMS & 0xFFFF;
            int v3 = (dwFileVersionLS >> 16) & 0xFFFF;
            int v4 = dwFileVersionLS & 0xFFFF;
            if ((v1 | v2 | v3 | v4) == 0) return null;
            if (v4 == 0) {
                if (v3 == 0) return v1 + "." + v2;
                return v1 + "." + v2 + "." + v3;
            }
            return v1 + "." + v2 + "." + v3 + "." + v4;
        }

        public String getFormattedProductVersion() {
            int v1 = (dwProductVersionMS >> 16) & 0xFFFF;
            int v2 = dwProductVersionMS & 0xFFFF;
            int v3 = (dwProductVersionLS >> 16) & 0xFFFF;
            int v4 = dwProductVersionLS & 0xFFFF;
            if ((v1 | v2 | v3 | v4) == 0) return null;
            if (v4 == 0) {
                if (v3 == 0) return v1 + "." + v2;
                return v1 + "." + v2 + "." + v3;
            }
            return v1 + "." + v2 + "." + v3 + "." + v4;
        }
    }

    private static String readUnicodeString(ByteBuffer data) {
        ByteBuffer stringBuf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        short value;
        while (data.hasRemaining() && (value = data.getShort()) != 0) {
            if (stringBuf.remaining() >= 2) stringBuf.putShort(value);
        }
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
            if ((offset & 3) != 0 && data.hasRemaining()) data.getShort();

            if (valueLength > 0 && data.remaining() >= valueLength * 2) {
                byte[] bytes = new byte[valueLength * 2];
                data.get(bytes, 0, bytes.length);
                value = readUnicodeString(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            }
            else value = null;

            int endOffset = data.position() - position;
            if ((endOffset & 3) != 0 && data.hasRemaining()) data.getShort();
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
            if ((offset & 3) != 0 && data.hasRemaining()) data.getShort();
            int remaining = length - offset;

            while (remaining > 6 && data.hasRemaining()) {
                int prePos = data.position();
                StringHdr stringhdr = new StringHdr(data);
                stringHdrs.add(stringhdr);
                int consumed = data.position() - prePos;
                if (consumed <= 0) break;
                remaining -= consumed;
            }
            int endOffset = data.position() - position;
            if ((endOffset & 3) != 0 && data.hasRemaining()) data.getShort();
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
            if ((offset & 3) != 0 && data.hasRemaining()) data.getShort();
            int remaining = length - offset;

            while (remaining > 6 && data.hasRemaining()) {
                int prePos = data.position();
                StringTable stringTable = new StringTable(data);
                stringTables.add(stringTable);
                int consumed = data.position() - prePos;
                if (consumed <= 0) break;
                remaining -= consumed;
            }
            int endOffset = data.position() - position;
            if ((endOffset & 3) != 0 && data.hasRemaining()) data.getShort();
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
            if ((offset & 3) != 0 && data.hasRemaining()) data.getShort();
            value = (valueLength >= 52 && data.remaining() >= 52) ? new VSFixedFileInfo(data) : null;

            if (data.hasRemaining()) {
                int align = data.position() - position;
                if ((align & 3) != 0 && data.hasRemaining()) data.getShort();
                stringFileInfo = new StringFileInfo(data);
            } else {
                stringFileInfo = null;
            }
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
        if (!this.peFile.isFile()) return null;
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(this.peFile), 65536)) {
            ByteBuffer dosHeader = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            int read = inStream.read(dosHeader.array());
            if (read < 64) return null;
            short magicNumber = dosHeader.getShort(0);
            if (magicNumber != 0x5A4D) return null; // 'MZ'

            dosHeader.position(60);
            int peOffset = dosHeader.getInt();
            if (peOffset < 64 || peOffset > 10 * 1024 * 1024) return null;

            int filePos = 64;
            long skipped = StreamUtils.skip(inStream, peOffset - filePos);
            filePos += (int) skipped;

            ByteBuffer peSignature = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            read = inStream.read(peSignature.array());
            if (read < 4 || peSignature.getInt(0) != 0x00004550) return null; // 'PE\0\0'
            filePos += 4;

            ByteBuffer fileHeader = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            read = inStream.read(fileHeader.array());
            if (read < 20) return null;
            filePos += 20;

            short numberOfSections = fileHeader.getShort(2);
            short sizeOfOptionalHeader = fileHeader.getShort(16);

            int resourceRVA = 0;
            int resourceSize = 0;

            if (sizeOfOptionalHeader > 0) {
                ByteBuffer optionalHeader = ByteBuffer.allocate(sizeOfOptionalHeader).order(ByteOrder.LITTLE_ENDIAN);
                read = inStream.read(optionalHeader.array());
                filePos += read;

                if (read >= 2) {
                    short optMagic = optionalHeader.getShort(0);
                    boolean is64Bit = (optMagic == 0x020B);
                    int dataDirOffset = is64Bit ? 112 : 96;
                    int numRvaAndSizesOffset = is64Bit ? 108 : 92;

                    if (sizeOfOptionalHeader >= dataDirOffset + 24) {
                        int numRvaAndSizes = optionalHeader.getInt(numRvaAndSizesOffset);
                        if (numRvaAndSizes > 2) {
                            // Entry 2: IMAGE_DIRECTORY_ENTRY_RESOURCE
                            resourceRVA = optionalHeader.getInt(dataDirOffset + 16);
                            resourceSize = optionalHeader.getInt(dataDirOffset + 20);
                        }
                    }
                }
            }

            int numSections = Math.min(Math.max(0, numberOfSections), 96);
            ByteBuffer sectionHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            byte[] nameBytes = new byte[8];

            int foundResourceOffset = 0;
            int foundResourceRVA = 0;
            int foundResourceSize = 0;

            for (int s = 0; s < numSections; s++) {
                sectionHeader.position(0);
                read = inStream.read(sectionHeader.array());
                if (read < 40) break;
                filePos += 40;

                sectionHeader.get(nameBytes);
                String sectName = StringUtils.fromANSIString(nameBytes).toLowerCase(Locale.US);
                int virtualSize = sectionHeader.getInt(8);
                int virtualAddress = sectionHeader.getInt(12);
                int sizeOfRawData = sectionHeader.getInt(16);
                int pointerToRawData = sectionHeader.getInt(20);

                if (resourceRVA != 0) {
                    int sectSpan = Math.max(virtualSize, sizeOfRawData);
                    if (resourceRVA >= virtualAddress && resourceRVA < virtualAddress + sectSpan) {
                        foundResourceRVA = resourceRVA;
                        foundResourceOffset = pointerToRawData + (resourceRVA - virtualAddress);
                        foundResourceSize = resourceSize > 0 ? resourceSize : sizeOfRawData;
                        break;
                    }
                } else if (sectName.equals(".rsrc") || sectName.equals("rsrc") || sectName.contains("rsrc")) {
                    foundResourceRVA = virtualAddress;
                    foundResourceOffset = pointerToRawData;
                    foundResourceSize = sizeOfRawData;
                    break;
                }
            }

            if (foundResourceOffset > 0 && foundResourceSize > 0 && foundResourceSize <= 32 * 1024 * 1024) {
                this.resourcesRVA = foundResourceRVA;
                this.resourcesOffset = foundResourceOffset;

                if (foundResourceOffset > filePos) {
                    StreamUtils.skip(inStream, foundResourceOffset - filePos);
                }
                ByteBuffer resourcesBuffer = ByteBuffer.allocate(foundResourceSize).order(ByteOrder.LITTLE_ENDIAN);
                inStream.read(resourcesBuffer.array(), 0, resourcesBuffer.limit());
                return new ImageResourceDirectory(type, resourcesBuffer, 0);
            }
        } catch (Exception ignored) {
        }
        return null;
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
        if (peFile == null || !peFile.isFile()) return null;

        try {
            PEParser peParser = new PEParser(peFile);
            ImageResourceDirectory rootDirectory = peParser.readImageResourceDirectory(RT_VERSION);
            if (rootDirectory == null) return scanFileForVersionFallback(peFile);

            ArrayList<ImageResourceDataEntry> dataEntries = peParser.readImageResourceDataEntries(rootDirectory);
            if (dataEntries.isEmpty()) return scanFileForVersionFallback(peFile);

            ImageResourceDataEntry dataEntry = dataEntries.get(0);
            int fileOffset = dataEntry.offsetToData - peParser.resourcesRVA + peParser.resourcesOffset;
            ByteBuffer resourceData = peParser.readResourceData(fileOffset, dataEntry.size);
            if (resourceData == null) return scanFileForVersionFallback(peFile);

            VSVersionInfo versionInfo = new VSVersionInfo(resourceData);
            FileVersionInfo fileVersionInfo = new FileVersionInfo();
            fileVersionInfo.fixedFileInfo = versionInfo.value;

            if (versionInfo.stringFileInfo != null) {
                for (StringTable stringTable : versionInfo.stringFileInfo.stringTables) {
                    for (StringHdr stringHdr : stringTable.stringHdrs) {
                        if (stringHdr.value == null || stringHdr.value.trim().isEmpty()) continue;
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
            }

            // If structured string tables didn't find FileVersion/ProductVersion, scan raw resource buffer
            if (fileVersionInfo.FileVersion.isEmpty() || fileVersionInfo.ProductVersion.isEmpty()) {
                scanRawVersionStrings(resourceData, fileVersionInfo);
            }

            // If still no string version, fall back to FixedFileInfo numeric version
            if (fileVersionInfo.FileVersion.isEmpty() && versionInfo.value != null) {
                String fv = versionInfo.value.getFormattedFileVersion();
                if (fv != null) fileVersionInfo.FileVersion = fv;
            }
            if (fileVersionInfo.ProductVersion.isEmpty() && versionInfo.value != null) {
                String pv = versionInfo.value.getFormattedProductVersion();
                if (pv != null) fileVersionInfo.ProductVersion = pv;
            }

            if (fileVersionInfo.hasVersion()) return fileVersionInfo;
        } catch (Exception ignored) {
        }

        return scanFileForVersionFallback(peFile);
    }

    private static void scanRawVersionStrings(ByteBuffer buf, FileVersionInfo info) {
        try {
            byte[] data = buf.array();
            int limit = buf.limit();
            if (info.FileVersion.isEmpty()) {
                String fv = findUtf16StringValue(data, limit, "FileVersion");
                if (fv != null && !fv.isEmpty()) info.FileVersion = fv;
            }
            if (info.ProductVersion.isEmpty()) {
                String pv = findUtf16StringValue(data, limit, "ProductVersion");
                if (pv != null && !pv.isEmpty()) info.ProductVersion = pv;
            }
            if (info.ProductName.isEmpty()) {
                String pn = findUtf16StringValue(data, limit, "ProductName");
                if (pn != null && !pn.isEmpty()) info.ProductName = pn;
            }
        } catch (Exception ignored) {}
    }

    private static String findUtf16StringValue(byte[] data, int limit, String key) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_16LE);
            int keyLen = keyBytes.length;
            for (int i = 0; i <= limit - keyLen - 4; i += 2) {
                boolean match = true;
                for (int k = 0; k < keyLen; k++) {
                    if (data[i + k] != keyBytes[k]) { match = false; break; }
                }
                if (match) {
                    int pos = i + keyLen;
                    // Skip null terminators or DWORD padding
                    while (pos < limit - 1 && data[pos] == 0 && data[pos + 1] == 0) {
                        pos += 2;
                    }
                    if (pos >= limit - 1) return null;
                    int start = pos;
                    while (pos < limit - 1 && !(data[pos] == 0 && data[pos + 1] == 0)) {
                        pos += 2;
                    }
                    if (pos > start) {
                        String val = new String(data, start, pos - start, StandardCharsets.UTF_16LE).trim();
                        if (val.length() >= 1 && val.length() <= 64 && !val.contains("\ufffd")) {
                            return val;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static FileVersionInfo scanFileForVersionFallback(File peFile) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(peFile), 65536)) {
            // Read up to first 2MB to search for version strings
            int maxScan = (int) Math.min(peFile.length(), 2 * 1024 * 1024);
            byte[] buf = new byte[maxScan];
            int read = in.read(buf);
            if (read > 1024) {
                FileVersionInfo info = new FileVersionInfo();
                info.FileVersion = findUtf16StringValue(buf, read, "FileVersion");
                if (info.FileVersion == null) info.FileVersion = "";
                info.ProductVersion = findUtf16StringValue(buf, read, "ProductVersion");
                if (info.ProductVersion == null) info.ProductVersion = "";
                info.ProductName = findUtf16StringValue(buf, read, "ProductName");
                if (info.ProductName == null) info.ProductName = "";
                if (info.hasVersion()) return info;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static Bitmap extractIcon(File peFile) {
        return extractIcon(peFile, -1);
    }

    public static Bitmap extractIcon(File peFile, int iconIndex) {
        return new PEParser(peFile).extractIcon(iconIndex);
    }
}
