package mousemaster;

import io.qt.gui.QImage;
import io.qt.gui.QPainter;
import io.qt.gui.QTextDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The menu's icon travels inside the HTML. Nothing is attached to the document. */
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
    void qtPaintsTheIconFromTheHtmlAlone() {
        assumeTrue(qtAvailable);
        int size = 16;
        int[] red = new int[size * size];
        for (int i = 0; i < red.length; i++)
            red[i] = 0xFFFF0000;
        String source = WindowIconImages.source(red, size);
        assertNotNull(source);
        QTextDocument document = new QTextDocument();
        document.setHtml("<img src=\"" + source + "\" width=\"16\" height=\"16\">");
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
        // The whole 16 by 16 icon, not a placeholder that happens to have some red in it.
        assertTrue(redPixels >= 200, "red pixels " + redPixels);
    }

    @Test
    void aHalfClearPixelStaysHalfClear() {
        assumeTrue(qtAvailable);
        int[] pixel = {0x80FF0000};
        String source = WindowIconImages.source(pixel, 1);
        QTextDocument document = new QTextDocument();
        document.setDocumentMargin(0);
        document.setHtml("<img src=\"" + source + "\" width=\"8\" height=\"8\">");
        document.setTextWidth(16);
        QImage canvas = new QImage(16, 16, QImage.Format.Format_ARGB32);
        canvas.fill(0xFF000000);
        QPainter painter = new QPainter(canvas);
        document.drawContents(painter);
        painter.end();
        painter.dispose();
        int red = (canvas.pixel(3, 3) >> 16) & 0xFF;
        canvas.dispose();
        document.dispose();
        assertTrue(red > 100 && red < 160, "red over black " + red);
    }

    @Test
    void windowsOfOneProgramShareAnAddress() {
        int[] pixels = {0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC};
        assertSame(WindowIconImages.source(pixels, 2), WindowIconImages.source(pixels, 2));
    }

    @Test
    void pixelsOfTheWrongSizeHaveNoAddress() {
        assertNull(WindowIconImages.source(null, 2));
        assertNull(WindowIconImages.source(new int[3], 2));
    }

}
