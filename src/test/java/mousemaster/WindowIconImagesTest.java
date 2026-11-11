package mousemaster;

import io.qt.gui.QImage;
import io.qt.gui.QPainter;
import io.qt.gui.QTextDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The menu's icon is drawn from memory. The shipped Qt build cannot load an image file. */
class WindowIconImagesTest {

    private static boolean qtAvailable;

    @BeforeAll
    static void initializeQt() {
        MousemasterApplication.tempDirectory =
                System.getProperty("java.io.tmpdir") + "/mousemaster-window-icon-test";
        try {
            QtManager.initialize();
        }
        catch (Throwable alreadyInitializedByAnotherTest) {
        }
        try {
            new QImage(1, 1, QImage.Format.Format_ARGB32).dispose();
            qtAvailable = true;
        }
        catch (Throwable e) {
            qtAvailable = false;
        }
    }

    @Test
    void anInstalledIconIsPaintedIntoTheDocument() {
        assumeTrue(qtAvailable);
        int size = 16;
        int[] red = new int[size * size];
        for (int i = 0; i < red.length; i++)
            red[i] = 0xFFFF0000;
        String name = WindowIconImages.SOURCE_PREFIX + "test";
        WindowIconImages.add(name, red, size);
        QTextDocument document = new QTextDocument();
        String html = "<img src=\"" + name + "\" width=\"16\" height=\"16\">";
        WindowIconImages.install(document, html);
        document.setHtml(html);
        document.setTextWidth(64);
        QImage canvas = new QImage(64, 64, QImage.Format.Format_ARGB32);
        canvas.fill(0xFF0000FF);
        QPainter painter = new QPainter(canvas);
        document.drawContents(painter);
        painter.end();
        painter.dispose();
        int redPixels = 0;
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) {
                int pixel = canvas.pixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                if (r > 200 && g < 80 && b < 80)
                    redPixels++;
            }
        }
        canvas.dispose();
        document.dispose();
        assertTrue(redPixels > 8, "red pixels " + redPixels);
    }

}
