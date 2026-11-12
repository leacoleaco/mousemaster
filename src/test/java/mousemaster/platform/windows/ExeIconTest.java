package mousemaster.platform.windows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExeIconTest {

    @Test
    void aThirtyTwoBitBitmapKeepsItsColor() {
        byte[] dib = new byte[40 + 16];
        put32(dib, 0, 40);
        put32(dib, 4, 2);
        put32(dib, 8, 4);
        put16(dib, 12, 1);
        put16(dib, 14, 32);
        // Bottom row, second pixel: opaque green. Top row, first pixel: opaque red.
        put32(dib, 40 + 4, 0xFF00FF00);
        put32(dib, 40 + 8, 0xFFFF0000);
        int[] pixels = ExeIcon.decode(dib, 2);
        assertNotNull(pixels);
        assertEquals(0xFFFF0000, pixels[0]);
        assertEquals(0xFF00FF00, pixels[3]);
    }

    /** Large icons are stored as PNG, whose numbers are big-endian. */
    @Test
    void aPngIconKeepsItsColor() {
        // Each row: filter type 0, then red, green, blue, alpha for both pixels.
        byte[] rows = {
                0, (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                0, 0, 0, (byte) 255, (byte) 255, 0, 0, 0, 0};
        int[] pixels = ExeIcon.decode(png(2, rows), 2);
        assertNotNull(pixels);
        assertEquals(0xFFFF0000, pixels[0]);
        assertEquals(0xFF00FF00, pixels[1]);
        assertEquals(0xFF0000FF, pixels[2]);
        assertEquals(0, pixels[3] >>> 24);
    }

    /** A clear pixel beside an opaque one halves the alpha and leaves the color alone. */
    @Test
    void shrinkingAveragesWithoutDarkeningEdges() {
        byte[] rows = {
                0, (byte) 255, 0, 0, (byte) 255, 0, 0, 0, 0,
                0, (byte) 255, 0, 0, (byte) 255, 0, 0, 0, 0};
        int[] pixels = ExeIcon.decode(png(2, rows), 1);
        assertNotNull(pixels);
        assertEquals(0x7FFF0000, pixels[0]);
    }

    @Test
    void notepadHasAnIconAtEverySize() {
        Path notepad = Path.of("C:\\Windows\\System32\\notepad.exe");
        assumeTrue(Files.isRegularFile(notepad));
        // 256 is stored as PNG, the smaller ones as bitmaps.
        for (int size : new int[]{32, 48, 256}) {
            int[] pixels = ExeIcon.read(notepad.toString(), size);
            assertNotNull(pixels, "size " + size);
            assertEquals(size * size, pixels.length);
            int visible = 0;
            for (int pixel : pixels)
                if ((pixel >>> 24) != 0)
                    visible++;
            assertTrue(visible > size * size / 8, "size " + size + " visible pixels " + visible);
        }
    }

    private static byte[] png(int size, byte[] rows) {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        putBigEndian32(header, size);
        putBigEndian32(header, size);
        header.writeBytes(new byte[]{8, 6, 0, 0, 0});
        chunk(png, "IHDR", header.toByteArray());
        Deflater deflater = new Deflater();
        deflater.setInput(rows);
        deflater.finish();
        byte[] buffer = new byte[256];
        int length = deflater.deflate(buffer);
        deflater.end();
        byte[] compressed = new byte[length];
        System.arraycopy(buffer, 0, compressed, 0, length);
        chunk(png, "IDAT", compressed);
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream png, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 checksum = new CRC32();
        checksum.update(typeBytes);
        checksum.update(data);
        putBigEndian32(png, data.length);
        png.writeBytes(typeBytes);
        png.writeBytes(data);
        putBigEndian32(png, (int) checksum.getValue());
    }

    private static void putBigEndian32(ByteArrayOutputStream stream, int value) {
        stream.write(value >>> 24);
        stream.write(value >>> 16);
        stream.write(value >>> 8);
        stream.write(value);
    }

    /** Writes a little-endian int. The bitmap stores BGRA, which is this value's byte order. */
    private static void put32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

}
