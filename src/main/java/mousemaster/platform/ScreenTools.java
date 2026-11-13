package mousemaster.platform;

import mousemaster.Rectangle;

/**
 * What the copy tools need from the operating system: the pixels of the screen, the
 * clipboard, and windows to draw the screenshot tools in. A platform without them keeps
 * the defaults, and the screenshot tools then do nothing.
 * <p>
 * The methods that say so block, and are called away from the thread that reads the
 * keyboard. The others are called on that thread and return at once.
 */
public interface ScreenTools {

    ScreenTools NONE = new ScreenTools() {
    };

    /** Whether the screen can be captured and drawn over. */
    default boolean supported() {
        return false;
    }

    /**
     * The pixels of that part of the screen, a row after another, each 0xFFRRGGBB.
     * Null when the screen could not be read. Blocks.
     */
    default int[] capture(Rectangle rectangle) {
        int[] pixels = new int[rectangle.width() * rectangle.height()];
        return capture(rectangle, pixels) ? pixels : null;
    }

    /**
     * Like {@link #capture(Rectangle)}, into an array the caller keeps from one picture
     * to the next. False when the screen could not be read. Blocks.
     */
    default boolean capture(Rectangle rectangle, int[] pixels) {
        return false;
    }

    /** Puts the picture on the clipboard. The pixels are 0xFFRRGGBB. Blocks. */
    default boolean copyImage(int[] pixels, int width, int height) {
        return false;
    }

    /** Puts the text on the clipboard. Blocks. */
    default boolean copyText(String text) {
        return false;
    }

    /** The text on the clipboard, or null when it holds none. Blocks. */
    default String clipboardText() {
        return null;
    }

    /** A number that changes every time the clipboard does. */
    default int clipboardSequence() {
        return 0;
    }

    /**
     * The text drawn in that color on nothing, as premultiplied 0xAARRGGBB pixels.
     * Null when the platform draws no text.
     */
    default Sprite renderText(String text, int pixelSize, int rgb) {
        return null;
    }

    /**
     * A window above the others that the pointer goes through. One that is hidden from
     * captures does not end up in the pictures {@link #capture} returns while it shows.
     * Null when the platform has none.
     */
    default Surface createSurface(boolean hiddenFromCapture) {
        return null;
    }

    /** Premultiplied 0xAARRGGBB pixels, a row after another. */
    record Sprite(int[] pixels, int width, int height) {
    }

    interface Surface {

        /**
         * Shows the pixels at the rectangle, which they fill: premultiplied 0xAARRGGBB,
         * a row after another. Only {@code dirty}, in screen coordinates, is read from
         * them when the surface already shows at that rectangle. Null is all of it.
         */
        void show(Rectangle rectangle, int[] pixels, Rectangle dirty);

        void hide();

        /** Frees the window. The surface is not used afterwards. */
        void close();
    }
}
