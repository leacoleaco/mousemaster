package mousemaster;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Turns a program icon's pixels into what the window list's HTML loads: a PNG written
 * into the image's address itself, which Qt decodes on its own.
 * The icon is not attached with QTextDocument.addResource. That call builds an
 * io.qt.core.QUrl, a class the native image has no metadata for, and QtJambi
 * deadlocks while resolving it: the exe froze as soon as the first icon was shown.
 */
public final class WindowIconImages {

    private static final String PREFIX = "data:image/png;base64,";
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** The same pixel array is handed back for every window of a program. */
    private static final Map<int[], String> sourceByPixels = new IdentityHashMap<>();

    private WindowIconImages() {
    }

    /**
     * The image address for {@code size} by {@code size} ARGB pixels.
     * Null when the array does not hold that many.
     */
    public static String source(int[] argb, int size) {
        if (argb == null || size <= 0 || argb.length != size * size)
            return null;
        return sourceByPixels.computeIfAbsent(argb,
                pixels -> PREFIX + Base64.getEncoder().encodeToString(png(pixels, size)));
    }

    static byte[] png(int[] argb, int size) {
        byte[] rows = new byte[size * (1 + size * 4)];
        int offset = 0;
        for (int y = 0; y < size; y++) {
            // Filter type 0: the row is stored as it is.
            rows[offset++] = 0;
            for (int x = 0; x < size; x++) {
                int pixel = argb[y * size + x];
                rows[offset++] = (byte) (pixel >>> 16);
                rows[offset++] = (byte) (pixel >>> 8);
                rows[offset++] = (byte) pixel;
                rows[offset++] = (byte) (pixel >>> 24);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(PNG_SIGNATURE);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeInt(header, size);
        writeInt(header, size);
        // 8 bits a channel, color type 6 (RGB with alpha), then the only compression,
        // filter and interlace methods there are.
        header.writeBytes(new byte[]{8, 6, 0, 0, 0});
        writeChunk(png, "IHDR", header.toByteArray());
        writeChunk(png, "IDAT", deflate(rows));
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!deflater.finished())
                compressed.write(buffer, 0, deflater.deflate(buffer));
            return compressed.toByteArray();
        }
        finally {
            deflater.end();
        }
    }

    private static void writeChunk(ByteArrayOutputStream png, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 checksum = new CRC32();
        checksum.update(typeBytes);
        checksum.update(data);
        writeInt(png, data.length);
        png.writeBytes(typeBytes);
        png.writeBytes(data);
        writeInt(png, (int) checksum.getValue());
    }

    private static void writeInt(ByteArrayOutputStream stream, int value) {
        stream.write(value >>> 24);
        stream.write(value >>> 16);
        stream.write(value >>> 8);
        stream.write(value);
    }

}
