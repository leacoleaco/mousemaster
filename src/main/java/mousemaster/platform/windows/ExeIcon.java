package mousemaster.platform.windows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads a program icon out of an executable with ordinary file IO, so it can run on
 * a background thread. It reads the first icon group, which is the one Explorer shows.
 */
final class ExeIcon {

    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;
    private static final int MAX_RESOURCE_BYTES = 32 * 1024 * 1024;

    private ExeIcon() {
    }

    /** Null when {@code path} has no icon this reader understands. */
    static int[] read(String path, int size) {
        if (path == null || path.isEmpty() || size <= 0)
            return null;
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            IconBlob icon = iconBlob(file, size);
            return icon == null ? null : decode(icon.bytes, size, icon.height);
        }
        catch (IOException failure) {
            return null;
        }
    }

    /** Decodes one RT_ICON blob, PNG or device-independent bitmap, into {@code size} square pixels. */
    static int[] decode(byte[] icon, int size) {
        return decode(icon, size, 0);
    }

    private static int[] decode(byte[] icon, int size, int height) {
        if (icon == null || icon.length < 16 || size <= 0)
            return null;
        try {
            Raster raster = png(icon) ? decodePng(icon) : decodeDib(icon, height);
            if (raster == null || !visible(raster.pixels))
                return null;
            return scale(raster, size);
        }
        catch (RuntimeException | DataFormatException failure) {
            return null;
        }
    }

    private static IconBlob iconBlob(RandomAccessFile file, int size) throws IOException {
        if (file.length() < 64)
            return null;
        byte[] dos = new byte[64];
        file.readFully(dos);
        if (u16(dos, 0) != 0x5A4D)
            return null;
        int peOffset = u32(dos, 0x3C);
        if (peOffset <= 0 || peOffset > file.length() - 24)
            return null;
        file.seek(peOffset);
        byte[] signature = new byte[4];
        file.readFully(signature);
        if (u32(signature, 0) != 0x00004550)
            return null;
        byte[] coff = new byte[20];
        file.readFully(coff);
        int sectionCount = u16(coff, 2);
        int optionalSize = u16(coff, 16);
        if (sectionCount <= 0 || sectionCount > 96 || optionalSize < 96 || optionalSize > 1024)
            return null;
        byte[] optional = new byte[optionalSize];
        file.readFully(optional);
        int magic = u16(optional, 0);
        int dataDirectory = magic == 0x20B ? 112 : magic == 0x10B ? 96 : -1;
        if (dataDirectory < 0 || optionalSize < dataDirectory + 8 * 3)
            return null;
        int resourceRva = u32(optional, dataDirectory + 16);
        int resourceSize = u32(optional, dataDirectory + 20);
        if (resourceRva == 0 || resourceSize <= 0 || resourceSize > MAX_RESOURCE_BYTES)
            return null;
        Section[] sections = new Section[sectionCount];
        for (int index = 0; index < sectionCount; index++) {
            byte[] header = new byte[40];
            file.readFully(header);
            sections[index] = new Section(u32(header, 12), u32(header, 8),
                    u32(header, 16), u32(header, 20));
        }
        long offset = fileOffset(sections, resourceRva);
        if (offset < 0 || offset >= file.length())
            return null;
        int available = (int) Math.min(resourceSize, file.length() - offset);
        if (available < 16)
            return null;
        byte[] resources = new byte[available];
        file.seek(offset);
        file.readFully(resources);
        return groupIcon(resources, resourceRva, size);
    }

    /**
     * The image from the executable's icon group that suits {@code size}, with its real
     * pixel height: the smallest one at least that wide, so it is never stretched, or
     * the widest one when all are smaller. Between equal widths, the deeper color wins.
     */
    private static IconBlob groupIcon(byte[] resources, int resourceRva, int size) {
        byte[] group = resource(resources, resourceRva, RT_GROUP_ICON, -1);
        if (group == null || group.length < 6)
            return null;
        int count = u16(group, 4);
        if (count <= 0 || 6 + count * 14 > group.length)
            return null;
        int bestWidth = 0;
        int bestBits = -1;
        int bestId = -1;
        int bestHeight = 0;
        for (int index = 0; index < count; index++) {
            int entry = 6 + index * 14;
            int width = group[entry] & 0xFF;
            int height = group[entry + 1] & 0xFF;
            if (width == 0)
                width = 256;
            if (height == 0)
                height = 256;
            int bits = u16(group, entry + 6);
            boolean better;
            if (bestId < 0)
                better = true;
            else if (width == bestWidth)
                better = bits > bestBits;
            else if (width >= size)
                better = bestWidth < size || width < bestWidth;
            else
                better = bestWidth < size && width > bestWidth;
            if (better) {
                bestWidth = width;
                bestBits = bits;
                bestId = u16(group, entry + 12);
                bestHeight = height;
            }
        }
        if (bestId < 0)
            return null;
        byte[] icon = resource(resources, resourceRva, RT_ICON, bestId);
        return icon == null ? null : new IconBlob(icon, bestHeight);
    }

    private static byte[] resource(byte[] resources, int resourceRva, int typeId, int nameId) {
        int typeDirectory = subdirectory(resources, 0, typeId);
        if (typeDirectory < 0)
            return null;
        int nameDirectory = nameId < 0
                ? firstSubdirectory(resources, typeDirectory)
                : subdirectory(resources, typeDirectory, nameId);
        if (nameDirectory < 0)
            return null;
        int data = dataEntry(resources, nameDirectory);
        if (data < 0 || data + 8 > resources.length)
            return null;
        int dataRva = u32(resources, data);
        int size = u32(resources, data + 4);
        int offset = dataRva - resourceRva;
        if (size <= 0 || offset < 0 || offset > resources.length - size)
            return null;
        return Arrays.copyOfRange(resources, offset, offset + size);
    }

    private static int subdirectory(byte[] resources, int directory, int id) {
        int count = entryCount(resources, directory);
        if (count < 0)
            return -1;
        int entry = directory + 16;
        for (int index = 0; index < count; index++) {
            if (entry + 8 > resources.length)
                return -1;
            int name = u32(resources, entry);
            int offset = u32(resources, entry + 4);
            entry += 8;
            if ((name & 0x80000000) != 0 || (name & 0x7FFFFFFF) != id)
                continue;
            if ((offset & 0x80000000) == 0)
                return -1;
            return offset & 0x7FFFFFFF;
        }
        return -1;
    }

    /** The first entry, named or numbered: named icon groups such as MAINICON come first. */
    private static int firstSubdirectory(byte[] resources, int directory) {
        int count = entryCount(resources, directory);
        if (count <= 0)
            return -1;
        int entry = directory + 16;
        if (entry + 8 > resources.length)
            return -1;
        int offset = u32(resources, entry + 4);
        if ((offset & 0x80000000) == 0)
            return -1;
        return offset & 0x7FFFFFFF;
    }

    private static int dataEntry(byte[] resources, int directory) {
        int count = entryCount(resources, directory);
        if (count <= 0 || directory + 16 + 8 > resources.length)
            return -1;
        int offset = u32(resources, directory + 20);
        if ((offset & 0x80000000) != 0)
            return -1;
        return offset & 0x7FFFFFFF;
    }

    private static int entryCount(byte[] resources, int directory) {
        if (directory < 0 || directory + 16 > resources.length)
            return -1;
        int count = u16(resources, directory + 12) + u16(resources, directory + 14);
        if (count < 0 || count > 4096 || directory + 16L + count * 8L > resources.length)
            return -1;
        return count;
    }

    private static boolean png(byte[] icon) {
        return icon.length > 8 &&
               (icon[0] & 0xFF) == 0x89 && icon[1] == 0x50 && icon[2] == 0x4E && icon[3] == 0x47;
    }

    private static Raster decodePng(byte[] png) throws DataFormatException {
        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = 0;
        byte[] palette = null;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int position = 8;
        while (position + 8 <= png.length) {
            int length = bigEndian32(png, position);
            if (length < 0 || position + 12L + length > png.length)
                return null;
            String type = new String(png, position + 4, 4, StandardCharsets.ISO_8859_1);
            int data = position + 8;
            if ("IHDR".equals(type) && length >= 13) {
                width = bigEndian32(png, data);
                height = bigEndian32(png, data + 4);
                bitDepth = png[data + 8] & 0xFF;
                colorType = png[data + 9] & 0xFF;
                if ((png[data + 12] & 0xFF) != 0)
                    return null;
            }
            else if ("PLTE".equals(type))
                palette = Arrays.copyOfRange(png, data, data + length);
            else if ("IDAT".equals(type))
                compressed.write(png, data, length);
            else if ("IEND".equals(type))
                break;
            position = data + length + 4;
        }
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024 || bitDepth != 8)
            return null;
        int channels = switch (colorType) {
            case 0 -> 1;
            case 2 -> 3;
            case 3 -> 1;
            case 4 -> 2;
            case 6 -> 4;
            default -> 0;
        };
        if (channels == 0 || (colorType == 3 && palette == null))
            return null;
        int rowBytes = width * channels;
        byte[] raw = inflate(compressed.toByteArray(), (rowBytes + 1) * height);
        if (raw == null)
            return null;
        byte[] rows = unfilter(raw, width, height, channels);
        if (rows == null)
            return null;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sample = (y * width + x) * channels;
                pixels[y * width + x] = switch (colorType) {
                    case 6 -> argb(rows[sample], rows[sample + 1], rows[sample + 2], rows[sample + 3]);
                    case 2 -> argb(rows[sample], rows[sample + 1], rows[sample + 2], (byte) 255);
                    case 0 -> gray(rows[sample] & 0xFF, 255);
                    case 4 -> gray(rows[sample] & 0xFF, rows[sample + 1] & 0xFF);
                    default -> palette(palette, rows[sample] & 0xFF);
                };
            }
        }
        return new Raster(width, height, pixels);
    }

    private static Raster decodeDib(byte[] dib, int claimedHeight) {
        int header = u32(dib, 0);
        if (header < 40 || header > dib.length - 16)
            return null;
        int width = u32(dib, 4);
        int heightField = u32(dib, 8);
        int bitCount = u16(dib, 14);
        int compression = u32(dib, 16);
        if (width <= 0 || width > 1024 || (compression != 0 && compression != 3))
            return null;
        if (bitCount != 1 && bitCount != 4 && bitCount != 8 && bitCount != 24 && bitCount != 32)
            return null;
        boolean topDown = heightField < 0;
        int storedHeight = Math.abs(heightField);
        int xorHeight = claimedHeight > 0 && storedHeight == claimedHeight * 2
                ? claimedHeight
                : claimedHeight > 0 && storedHeight == claimedHeight
                ? claimedHeight
                : storedHeight / 2;
        if (xorHeight <= 0 || xorHeight > 1024)
            return null;
        int colors = bitCount <= 8 ? u32(dib, 32) : 0;
        if (colors == 0 && bitCount <= 8)
            colors = 1 << bitCount;
        int paletteBytes = colors * 4;
        int xorStride = ((width * bitCount + 31) / 32) * 4;
        int andStride = ((width + 31) / 32) * 4;
        int xorOffset = header + paletteBytes;
        int andOffset = xorOffset + xorStride * xorHeight;
        if (xorOffset < 0 || andOffset > dib.length)
            return null;
        int[] pixels = new int[width * xorHeight];
        boolean anyAlpha = false;
        for (int y = 0; y < xorHeight; y++) {
            int sourceRow = topDown ? y : xorHeight - 1 - y;
            int row = xorOffset + sourceRow * xorStride;
            for (int x = 0; x < width; x++) {
                int pixel = switch (bitCount) {
                    case 32 -> argb(dib[row + x * 4 + 2], dib[row + x * 4 + 1],
                            dib[row + x * 4], dib[row + x * 4 + 3]);
                    case 24 -> argb(dib[row + x * 3 + 2], dib[row + x * 3 + 1],
                            dib[row + x * 3], (byte) 255);
                    case 8 -> palette(dib, header, dib[row + x] & 0xFF);
                    case 4 -> palette(dib, header, (dib[row + x / 2] >> ((1 - (x & 1)) * 4)) & 0x0F);
                    case 1 -> palette(dib, header, (dib[row + x / 8] >> (7 - (x & 7))) & 0x01);
                    default -> 0;
                };
                if (bitCount == 32 && (pixel >>> 24) != 0)
                    anyAlpha = true;
                pixels[y * width + x] = pixel;
            }
        }
        if (!anyAlpha && andOffset + andStride * xorHeight <= dib.length)
            applyMask(pixels, dib, andOffset, andStride, width, xorHeight, topDown);
        return new Raster(width, xorHeight, pixels);
    }

    /** An unset alpha channel means the AND mask decides which pixels are clear. */
    private static void applyMask(int[] pixels, byte[] dib, int offset, int stride,
                                  int width, int height, boolean topDown) {
        for (int y = 0; y < height; y++) {
            int sourceRow = topDown ? y : height - 1 - y;
            int row = offset + sourceRow * stride;
            for (int x = 0; x < width; x++) {
                int masked = (dib[row + x / 8] >> (7 - (x & 7))) & 0x01;
                if (masked != 0)
                    pixels[y * width + x] = 0;
                else if ((pixels[y * width + x] >>> 24) == 0)
                    pixels[y * width + x] |= 0xFF000000;
            }
        }
    }

    private static byte[] inflate(byte[] compressed, int size) throws DataFormatException {
        if (size <= 0 || size > MAX_RESOURCE_BYTES)
            return null;
        byte[] raw = new byte[size];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int filled = 0;
            while (filled < size && !inflater.finished()) {
                int count = inflater.inflate(raw, filled, size - filled);
                if (count == 0)
                    break;
                filled += count;
            }
            return filled == size ? raw : null;
        }
        finally {
            inflater.end();
        }
    }

    private static byte[] unfilter(byte[] raw, int width, int height, int channels) {
        int rowBytes = width * channels;
        if (raw.length < (rowBytes + 1) * height)
            return null;
        byte[] rows = new byte[rowBytes * height];
        int source = 0;
        for (int y = 0; y < height; y++) {
            int filter = raw[source++] & 0xFF;
            int destination = y * rowBytes;
            for (int x = 0; x < rowBytes; x++) {
                int value = raw[source++] & 0xFF;
                int left = x >= channels ? rows[destination + x - channels] & 0xFF : 0;
                int up = y > 0 ? rows[destination - rowBytes + x] & 0xFF : 0;
                int upLeft = y > 0 && x >= channels ? rows[destination - rowBytes + x - channels] & 0xFF : 0;
                int decoded = switch (filter) {
                    case 0 -> value;
                    case 1 -> value + left;
                    case 2 -> value + up;
                    case 3 -> value + (left + up) / 2;
                    case 4 -> value + paeth(left, up, upLeft);
                    default -> -1;
                };
                if (decoded < 0)
                    return null;
                rows[destination + x] = (byte) decoded;
            }
        }
        return rows;
    }

    private static int paeth(int left, int up, int upLeft) {
        int estimate = left + up - upLeft;
        int leftDistance = Math.abs(estimate - left);
        int upDistance = Math.abs(estimate - up);
        int diagonal = Math.abs(estimate - upLeft);
        if (leftDistance <= upDistance && leftDistance <= diagonal)
            return left;
        return upDistance <= diagonal ? up : upLeft;
    }

    /**
     * Each output pixel is the average of the source pixels it covers. Colors are
     * weighted by alpha, so a clear pixel's color does not darken an edge.
     */
    private static int[] scale(Raster raster, int size) {
        if (raster.width == size && raster.height == size)
            return raster.pixels;
        int[] scaled = new int[size * size];
        for (int y = 0; y < size; y++) {
            int top = Math.min(raster.height - 1, y * raster.height / size);
            int bottom = Math.min(raster.height, Math.max(top + 1, (y + 1) * raster.height / size));
            for (int x = 0; x < size; x++) {
                int left = Math.min(raster.width - 1, x * raster.width / size);
                int right = Math.min(raster.width, Math.max(left + 1, (x + 1) * raster.width / size));
                long alpha = 0, red = 0, green = 0, blue = 0;
                for (int sourceY = top; sourceY < bottom; sourceY++) {
                    for (int sourceX = left; sourceX < right; sourceX++) {
                        int pixel = raster.pixels[sourceY * raster.width + sourceX];
                        int a = pixel >>> 24;
                        alpha += a;
                        red += (long) ((pixel >> 16) & 0xFF) * a;
                        green += (long) ((pixel >> 8) & 0xFF) * a;
                        blue += (long) (pixel & 0xFF) * a;
                    }
                }
                if (alpha == 0)
                    continue;
                int covered = (bottom - top) * (right - left);
                scaled[y * size + x] = (int) (alpha / covered) << 24 |
                                       (int) (red / alpha) << 16 |
                                       (int) (green / alpha) << 8 |
                                       (int) (blue / alpha);
            }
        }
        return scaled;
    }

    private static int palette(byte[] dib, int header, int index) {
        int offset = header + index * 4;
        if (offset + 2 >= dib.length)
            return 0;
        return argb(dib[offset + 2], dib[offset + 1], dib[offset], (byte) 255);
    }

    private static int palette(byte[] palette, int index) {
        int offset = index * 3;
        if (palette == null || offset + 2 >= palette.length)
            return 0;
        return argb(palette[offset], palette[offset + 1], palette[offset + 2], (byte) 255);
    }

    private static int gray(int value, int alpha) {
        return (alpha << 24) | (value << 16) | (value << 8) | value;
    }

    private static boolean visible(int[] pixels) {
        for (int pixel : pixels)
            if ((pixel >>> 24) != 0)
                return true;
        return false;
    }

    private static int argb(byte red, byte green, byte blue, byte alpha) {
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    private static long fileOffset(Section[] sections, int rva) {
        for (Section section : sections) {
            int span = Math.max(section.virtualSize, section.rawSize);
            if (rva >= section.virtualAddress && rva < section.virtualAddress + span)
                return (long) section.rawOffset + (rva - section.virtualAddress);
        }
        return -1;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) |
               ((bytes[offset + 1] & 0xFF) << 8) |
               ((bytes[offset + 2] & 0xFF) << 16) |
               ((bytes[offset + 3] & 0xFF) << 24);
    }

    /** PNG stores its numbers the other way round from the executable around it. */
    private static int bigEndian32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) |
               ((bytes[offset + 1] & 0xFF) << 16) |
               ((bytes[offset + 2] & 0xFF) << 8) |
               (bytes[offset + 3] & 0xFF);
    }

    private record Section(int virtualAddress, int virtualSize, int rawSize, int rawOffset) {
    }

    private record IconBlob(byte[] bytes, int height) {
    }

    private record Raster(int width, int height, int[] pixels) {
    }

}
