package mousemaster;

import io.qt.gui.QImage;
import io.qt.gui.QTextDocument;

import java.util.HashMap;
import java.util.Map;

/**
 * Program icons drawn inside the window list. They are kept as images on the text
 * document, because the shipped Qt build has no image-file plugins.
 */
public final class WindowIconImages {

    /** Image names in the menu HTML start with this. The rest is unique per capture. */
    public static final String SOURCE_PREFIX = "window-icon-";

    private static final Map<String, QImage> images = new HashMap<>();

    private WindowIconImages() {
    }

    public static void add(String name, int[] argb, int size) {
        if (name == null || argb == null || size <= 0 || argb.length != size * size)
            return;
        images.computeIfAbsent(name, ignored -> image(argb, size));
    }

    /** Attaches icons named in {@code html} before the document is given that HTML. */
    public static void install(QTextDocument document, String html) {
        if (document == null || html == null || !html.contains(SOURCE_PREFIX))
            return;
        int type = QTextDocument.ResourceType.ImageResource.value();
        int from = 0;
        while ((from = html.indexOf(SOURCE_PREFIX, from)) >= 0) {
            int end = from;
            while (end < html.length()) {
                char c = html.charAt(end);
                if (c == '"' || c == '\'' || c == ' ' || c == '>')
                    break;
                end++;
            }
            String name = html.substring(from, end);
            QImage image = images.get(name);
            if (image != null)
                document.addResource(type, name, image);
            from = Math.max(end, from + SOURCE_PREFIX.length());
        }
    }

    private static QImage image(int[] argb, int size) {
        QImage image = new QImage(size, size, QImage.Format.Format_ARGB32);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                image.setPixel(x, y, argb[y * size + x]);
        return image;
    }

}
