package mousemaster;

import mousemaster.platform.DesktopCapture;
import mousemaster.platform.UiAutomation.UiElement;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class VisionTest {

    private static final int density = Vision.densities;
    private static final int defaultDensity = 6;

    private static final int width = 400, height = 300;

    private static BufferedImage screen(Color background) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = pen(image);
        pen.setColor(background);
        pen.fillRect(0, 0, width, height);
        pen.dispose();
        return image;
    }

    private static Graphics2D pen(BufferedImage image) {
        Graphics2D pen = image.createGraphics();
        pen.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        pen.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        pen.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        return pen;
    }

    private static BufferedImage captured(BufferedImage drawn) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.getGraphics().drawImage(
                drawn.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0,
                null);
        return image;
    }

    private static List<UiElement> elements(BufferedImage image, int density) {
        return elements(image, 1, density);
    }

    private static List<UiElement> elements(BufferedImage image, int scale, int density) {
        return elements(image, scale, density, 0);
    }

    private static List<UiElement> elements(BufferedImage image, int scale, int density,
                                            double minSize) {
        return elements(image, scale, density, minSize, 0, List.of());
    }

    private static List<UiElement> elements(BufferedImage image, int scale, int density,
                                            double minSize, int refine,
                                            List<Rectangle> occupied) {
        byte[] rgb = new byte[width * height * 3];
        for (int y = 0, i = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                rgb[i++] = (byte) (pixel >> 16);
                rgb[i++] = (byte) (pixel >> 8);
                rgb[i++] = (byte) pixel;
            }
        return new Vision(null).findElements(
                new DesktopCapture(new Rectangle(0, 0, width * scale, height * scale), rgb,
                        width, height), scale, density, minSize, refine, occupied);
    }

    private static long within(List<UiElement> elements, int x, int y, int right,
                               int bottom) {
        return elements.stream()
                       .filter(element -> element.centerX() >= x
                                          && element.centerX() <= right
                                          && element.centerY() >= y
                                          && element.centerY() <= bottom)
                       .count();
    }

    @Test
    public void flatIconIsHintedOnceAtItsCentre() {
        BufferedImage image = screen(new Color(0x2E3035));
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x5865F2));
        pen.fillRoundRect(150, 100, 100, 100, 30, 30);
        pen.dispose();
        List<UiElement> elements = elements(image, density);
        assertEquals(1, elements.size(), "a flat icon is one thing to click");
        assertEquals(1, within(elements, 185, 135, 215, 165),
                "the hint belongs at the centre, not on the corners of the outline");
    }

    @Test
    public void elementIsItsInkWithoutTheEdgeAroundIt() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x3C3C3C));
        pen.fillRect(100, 100, 40, 40);
        pen.dispose();
        for (int density : new int[] {defaultDensity, VisionTest.density})
            assertEquals(List.of(new Rectangle(100, 100, 40, 40)),
                    elements(image, density).stream().map(UiElement::rectangle).toList());
    }

    @Test
    public void colourThatKeepsTheBrightnessIsContent() {
        BufferedImage image = screen(new Color(0x767676));
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x0078D4));
        pen.fillRect(160, 130, 80, 40);
        pen.dispose();
        assertEquals(1, within(elements(image, density), 160, 130, 240, 170));
    }

    @Test
    public void borderedPanelSplitsIntoItsControls() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x3A76D8));
        pen.setStroke(new BasicStroke(1));
        pen.drawRoundRect(40, 100, 320, 100, 8, 8);
        pen.setColor(new Color(0x333333));
        pen.drawString("Save", 80, 158);
        pen.drawString("Cancel", 180, 158);
        pen.drawString("Help", 290, 158);
        pen.dispose();
        List<UiElement> elements = elements(image, density);
        assertTrue(within(elements, 70, 140, 130, 170) >= 1, "Save");
        assertTrue(within(elements, 170, 140, 240, 170) >= 1, "Cancel");
        assertTrue(within(elements, 280, 140, 340, 170) >= 1, "Help");
    }

    @Test
    public void smallFilledButtonBesideTextIsHinted() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0xFF6766));
        pen.fillRoundRect(44, 100, 62, 15, 7, 7);
        pen.setColor(Color.WHITE);
        pen.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        pen.drawString("LEAVE", 54, 111);
        pen.setColor(new Color(0x333333));
        pen.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        pen.drawString("Show my flair on this subreddit", 44, 130);
        pen.dispose();
        assertTrue(within(elements(image, density), 44, 96, 106, 120) >= 1);
    }

    @Test
    public void textIsNotOneHintPerGlyph() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x222222));
        pen.drawString("The quick brown fox", 40, 80);
        pen.drawString("jumps over the lazy dog", 40, 140);
        pen.drawString("Pack my box with jugs", 40, 200);
        pen.dispose();
        List<UiElement> elements = elements(image, density);
        assertTrue(within(elements, 40, 60, 360, 85) >= 1, "first line");
        assertTrue(within(elements, 40, 120, 360, 145) >= 1, "second line");
        assertTrue(within(elements, 40, 180, 360, 205) >= 1, "third line");
        assertTrue(elements.size() <= 30,
                "three lines of text, not sixty glyphs: " + elements.size());
    }

    @Test
    public void frameOnePixelWideIsScannedAtEveryDensity() {
        for (int density = 1; density <= Vision.densities; density++) {
            int scanned = density;
            assertDoesNotThrow(() -> new Vision(null).findElements(
                    new DesktopCapture(new Rectangle(0, 0, 1, 5), new byte[15], 1, 5), 1,
                    scanned, 0), "density " + scanned);
        }
    }

    @Test
    public void linkInASentenceIsHintedApart() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        FontMetrics metrics = pen.getFontMetrics();
        int linkX = 40 + metrics.stringWidth("Before you start, read the ");
        int linkRight = linkX + metrics.stringWidth("guide");
        pen.setColor(new Color(0x202122));
        pen.drawString("Before you start, read the ", 40, 150);
        pen.setColor(new Color(0x3366CC));
        pen.drawString("guide", linkX, 150);
        pen.dispose();
        assertEquals(1,
                within(elements(image, defaultDensity), linkX, 130, linkRight, 160));
    }

    @Test
    public void lineOnTheEdgeOfTheScreenIsScannedAtEveryDensity() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x333333));
        pen.drawLine(0, 100, 0, 200);
        pen.drawLine(width - 1, 100, width - 1, 200);
        pen.dispose();
        for (int density = 1; density <= Vision.densities; density++) {
            int scanned = density;
            assertDoesNotThrow(() -> elements(image, scanned), "density " + scanned);
        }
    }

    @Test
    public void firstDensityHintsAFaintButtonButNotTheTextBesideIt() {
        BufferedImage image = screen(Color.BLACK);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x0E100E));
        pen.drawRoundRect(40, 100, 90, 32, 12, 12);
        pen.setColor(new Color(0xF0F6FC));
        pen.drawString("Sign in", 60, 122);
        pen.drawString("Assigned to me", 180, 122);
        pen.dispose();
        List<UiElement> elements = elements(image, 1);
        assertEquals(1, within(elements, 40, 100, 130, 132), "the button");
        assertEquals(0, within(elements, 180, 100, 360, 132), "the text");
    }

    @Test
    public void capturedWordAloneIsLeftOutAtTheFirstDensity() {
        BufferedImage drawn = new BufferedImage(3 * width, 3 * height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = pen(drawn);
        pen.setColor(new Color(0xF6F6EF));
        pen.fillRect(0, 0, 3 * width, 3 * height);
        pen.setColor(new Color(0x828282));
        pen.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 39));
        pen.drawString("past", 300, 450);
        pen.dispose();
        assertEquals(0, elements(captured(drawn), 3, 1).size());
    }

    @Test
    public void faintCardOutlineIsNotHintedAtAnyDensity() {
        BufferedImage drawn = new BufferedImage(3 * width, 3 * height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = pen(drawn);
        pen.setColor(new Color(0x0D1117));
        pen.fillRect(0, 0, 3 * width, 3 * height);
        pen.setColor(new Color(0x3D444D));
        pen.fillRoundRect(120, 300, 960, 186, 36, 36);
        pen.setColor(new Color(0x0D1117));
        pen.fillRoundRect(123, 303, 954, 180, 30, 30);
        pen.setColor(new Color(0xF0F6FC));
        pen.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        pen.drawString("Native dialogs on Windows", 180, 384);
        pen.dispose();
        BufferedImage image = captured(drawn);
        for (int density = 1; density <= Vision.densities; density++) {
            List<UiElement> elements = elements(image, 3, density);
            assertEquals(0, within(elements, 114, 294, 129, 492)
                            + within(elements, 1071, 294, 1086, 492)
                            + within(elements, 114, 294, 1086, 309)
                            + within(elements, 114, 477, 1086, 492),
                    "density " + density);
        }
    }

    @Test
    public void darkThemeOutlineIsInk() {
        BufferedImage image = screen(new Color(0x17191A));
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x3E3E3F));
        pen.setStroke(new BasicStroke(2));
        pen.drawRoundRect(193, 143, 14, 14, 4, 4);
        pen.dispose();
        assertEquals(1, within(elements(image, density), 190, 140, 210, 160));
    }

    @Test
    public void rowMenuOfThreeDotsIsHinted() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x333333));
        for (int dot = 0; dot < 3; dot++)
            pen.fillOval(190 + dot * 4, 150, 2, 2);
        pen.dispose();
        assertEquals(1, within(elements(image, defaultDensity), 185, 140, 210, 160));
    }

    @Test
    public void menuOfThreeLinesIsHinted() {
        BufferedImage image = screen(new Color(0x181818));
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0xCCCCCC));
        for (int line = 0; line < 3; line++)
            pen.fillRect(190, 150 + line * 4, 16, 1);
        pen.dispose();
        assertEquals(1, within(elements(image, density), 185, 145, 210, 165));
    }

    @Test
    public void greyTextBesideBlackIsHintedApart() {
        BufferedImage image = screen(new Color(0xF6F6EF));
        Graphics2D pen = pen(image);
        FontMetrics metrics = pen.getFontMetrics();
        int domainX = 40 + metrics.stringWidth("Black hole singularity ");
        int domainRight = domainX + metrics.stringWidth("(arxiv.org)");
        pen.setColor(Color.BLACK);
        pen.drawString("Black hole singularity ", 40, 150);
        pen.setColor(new Color(0x828282));
        pen.drawString("(arxiv.org)", domainX, 150);
        pen.dispose();
        assertEquals(1,
                within(elements(image, defaultDensity), domainX, 130, domainRight, 160));
    }

    @Test
    public void barThinnerThanTheMinSizeIsNotHinted() {
        BufferedImage drawn = new BufferedImage(3 * width, 3 * height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = pen(drawn);
        pen.setColor(new Color(0x202020));
        pen.fillRect(0, 0, 3 * width, 3 * height);
        pen.setColor(new Color(0xCCCCCC));
        pen.fillRect(12, 300, 6, 75);
        pen.setColor(new Color(0x0078D4));
        pen.fillRoundRect(33, 300, 72, 72, 12, 12);
        pen.dispose();
        BufferedImage image = captured(drawn);
        assertEquals(1, within(elements(image, 3, defaultDensity), 0, 290, 30, 385),
                "the bar without a min size");
        List<UiElement> elements = elements(image, 3, defaultDensity, 6);
        assertEquals(0, within(elements, 0, 290, 30, 385), "the bar");
        assertEquals(1, within(elements, 33, 300, 105, 372), "the icon");
    }

    @Test
    public void iconsBesideAWindowBorderAreHintedApart() {
        BufferedImage image = screen(new Color(0x242225));
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x1A1A1A));
        pen.fillRect(0, 0, 48, height);
        pen.setColor(new Color(0x757575));
        pen.fillRect(48, 0, 1, height);
        int[] colors = {0xDC3C32, 0x2F7BDB, 0x3BB35C, 0xF0B32F, 0x9550C8};
        for (int icon = 0; icon < colors.length; icon++) {
            pen.setColor(new Color(colors[icon]));
            pen.fillRect(8, 20 + 50 * icon, 32, 32);
        }
        pen.dispose();
        for (int density = 1; density <= Vision.densities; density++) {
            List<UiElement> elements = elements(image, density);
            for (int icon = 0; icon < colors.length; icon++)
                assertEquals(1, within(elements, 8, 20 + 50 * icon, 40, 52 + 50 * icon),
                        "density " + density + ", icon " + icon);
            assertEquals(0, within(elements, 44, 0, 52, height),
                    "density " + density + ", the border");
        }
    }

    @Test
    public void aControlInsideAShownRegionIsNotTheSameTag() {
        Rectangle panel = new Rectangle(0, 0, 200, 80);
        Rectangle button = new Rectangle(150, 20, 30, 30);
        assertFalse(Vision.coversShownTag(button, List.of(panel), 1),
                "a button away from the panel's tag is a place the first look missed");
        Rectangle onTheTag = new Rectangle(85, 25, 30, 30);
        assertTrue(Vision.coversShownTag(onTheTag, List.of(panel), 1));
        Rectangle shifted = new Rectangle(99, 80, 40, 40);
        assertTrue(Vision.coversShownTag(shifted, List.of(new Rectangle(80, 80, 40, 40)), 1),
                "the same control found again, a little to the side");
    }

    @Test
    public void straightLinesMarkBothDirectionsAndAShownOneDropsOut() {
        BufferedImage image = screen(Color.WHITE);
        int ink = 0x222222;
        for (int x = 20; x < 140; x++)
            image.setRGB(x, 80, ink);
        for (int y = 30; y < 160; y++)
            image.setRGB(300, y, ink);
        for (int x = 20; x < 90; x++)
            image.setRGB(x, 40, ink);
        List<UiElement> lines = elements(image, 1, defaultDensity, 0, Vision.straightLines,
                List.of());
        assertTrue(within(lines, 60, 70, 100, 95) >= 1, "the middle of the horizontal rule");
        assertTrue(within(lines, 290, 70, 310, 120) >= 1, "the middle of the vertical rule");
        assertEquals(0, within(lines, 10, 30, 100, 50), "70 pixels is not a line");
        // The length is in screen pixels: twice as many when the screen is at 200%.
        List<UiElement> zoomed = elements(image, 2, defaultDensity, 0, Vision.straightLines,
                List.of());
        assertTrue(within(zoomed, 20, 60, 200, 100) >= 1, "140 screen pixels is a line");
        Rectangle shown = lines.stream()
                .filter(element -> element.centerY() < 100 && element.centerX() < 200)
                .findFirst()
                .orElseThrow()
                .rectangle();
        List<UiElement> again = elements(image, 1, defaultDensity, 0, Vision.straightLines,
                List.of(shown));
        assertEquals(0, within(again, 60, 70, 100, 95), "the line already shown is gone");
        assertTrue(within(again, 290, 70, 310, 120) >= 1, "the other line stays");
    }

    @Test
    public void aRuleThatOtherRulesCrossIsStillOneLine() {
        BufferedImage image = screen(Color.WHITE);
        int ink = 0x222222;
        for (int x = 20; x < 260; x++)
            image.setRGB(x, 80, ink);
        // Each piece between two crossings is under 80 pixels, and so is each crossing rule.
        for (int x : new int[]{70, 130, 190})
            for (int y = 50; y < 110; y++)
                image.setRGB(x, y, ink);
        List<UiElement> lines = elements(image, 1, defaultDensity, 0, Vision.straightLines,
                List.of());
        String found = lines.stream().map(UiElement::rectangle).toList().toString();
        assertEquals(1, lines.size(), found);
        assertEquals(1, within(lines, 130, 75, 150, 85), "the middle of the whole rule: " + found);
    }

    @Test
    public void aSoftColourBoundaryIsAStraightLine() {
        BufferedImage image = screen(Color.WHITE);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int level = x < 160 ? 0xff : x > 165 ? 0x87 : 0xff - (x - 159) * 20;
                image.setRGB(x, y, level << 16 | level << 8 | level);
            }
        List<UiElement> lines = elements(image, 1, defaultDensity, 0, Vision.straightLines,
                List.of());
        assertTrue(within(lines, 145, 120, 185, 180) >= 1,
                "the ramp between two colours: " +
                lines.stream().map(UiElement::rectangle).toList());
    }

    @Test
    public void twoPanelsOfADarkThemeMeetInAStraightLine() {
        BufferedImage image = screen(new Color(0x181818));
        for (int y = 0; y < height; y++)
            for (int x = 200; x < width; x++)
                image.setRGB(x, y, 0x1f1f1f);
        List<UiElement> lines = elements(image, 1, defaultDensity, 0, Vision.straightLines,
                List.of());
        assertEquals(1, lines.size(),
                "one boundary: " + lines.stream().map(UiElement::rectangle).toList());
        assertEquals(1, within(lines, 190, 130, 210, 170), "the middle of where they meet");
    }

    @Test
    public void textIsNotAStraightLine() {
        BufferedImage image = screen(Color.WHITE);
        Graphics2D pen = pen(image);
        pen.setColor(new Color(0x222222));
        pen.drawString("The quick brown fox jumps over the lazy dog", 30, 100);
        pen.drawString("EXPLORER   OUTLINE   TIMELINE   ___", 30, 180);
        pen.dispose();
        List<UiElement> lines = elements(image, 1, defaultDensity, 0, Vision.straightLines,
                List.of());
        assertEquals(0, within(lines, 0, 80, width, 110),
                "letters break the run: " + lines.stream().map(UiElement::rectangle).toList());
    }

    @Test
    public void aBlankIsTheLargestRectangleClearOfWhatIsDrawn() {
        BufferedImage image = screen(Color.WHITE);
        // Stripes, as text is: nothing in the block is one colour.
        for (int y = 0; y < 100; y++)
            if (y / 3 % 2 == 0)
                for (int x = 0; x < 100; x++)
                    image.setRGB(x, y, 0x222222);
        List<UiElement> blanks = elements(image, 1, defaultDensity, 0, Vision.blankAreas,
                List.of());
        String found = blanks.stream().map(UiElement::rectangle).toList().toString();
        assertEquals(2, blanks.size(), found);
        assertEquals(1, within(blanks, 232, 128, 272, 168), "beside the block: " + found);
        assertEquals(1, within(blanks, 32, 176, 72, 216), "what is left below it: " + found);
        assertEquals(0, within(blanks, 0, 0, 104, 104), "nothing on the block: " + found);
    }

    @Test
    public void aBlankHasAShortSideOf40PixelsAndALongSide() {
        BufferedImage image = screen(Color.WHITE);
        // Stripes everywhere, as text is, so only what is painted flat below is blank.
        for (int y = 0; y < height; y++)
            if (y / 3 % 2 == 0)
                for (int x = 0; x < width; x++)
                    image.setRGB(x, y, 0x222222);
        int flat = 0xE8E8E8;
        Graphics2D pen = image.createGraphics();
        pen.setColor(new Color(flat));
        pen.fillRect(30, 22, 200, 46);
        pen.fillRect(30, 102, 200, 28);
        pen.fillRect(290, 30, 46, 200);
        pen.fillRect(30, 182, 60, 60);
        pen.dispose();
        List<UiElement> blanks = elements(image, 1, defaultDensity, 0, Vision.blankAreas,
                List.of());
        String found = blanks.stream().map(UiElement::rectangle).toList().toString();
        assertEquals(2, blanks.size(), found);
        assertEquals(1, within(blanks, 120, 40, 140, 50), "a strip 46 pixels tall: " + found);
        assertEquals(1, within(blanks, 308, 120, 318, 140), "a strip 46 pixels wide: " + found);
        assertEquals(0, within(blanks, 30, 102, 230, 130), "28 pixels is too thin: " + found);
        assertEquals(0, within(blanks, 30, 182, 90, 242), "a square has no long side: " + found);
        // The sides are in screen pixels: at 200% the thin strip is 56 of them.
        List<UiElement> zoomed = elements(image, 2, defaultDensity, 0, Vision.blankAreas,
                List.of());
        assertEquals(1, within(zoomed, 60, 204, 460, 260), "56 screen pixels tall: " +
                zoomed.stream().map(UiElement::rectangle).toList());
    }

    @Test
    public void blankAreaMarksALargeRegularBlockAndSkipsAStrip() {
        BufferedImage image = screen(Color.WHITE);
        int panel = 0x3A5F8A;
        for (int y = 20; y < 140; y++)
            for (int x = 30; x < 230; x++)
                image.setRGB(x, y, panel);
        for (int y = 200; y < 216; y++)
            for (int x = 20; x < 240; x++)
                image.setRGB(x, y, 0x222222);
        for (int y = 240; y < 270; y++)
            for (int x = 340; x < 370; x++)
                image.setRGB(x, y, 0x222222);
        List<UiElement> blanks = elements(image, 1, defaultDensity, 0, Vision.blankAreas,
                List.of());
        assertTrue(within(blanks, 100, 50, 160, 110) >= 1, "the centre of the panel");
        assertEquals(0, within(blanks, 20, 200, 240, 216), "a thin strip is not a blank");
        assertEquals(0, within(blanks, 340, 240, 370, 270), "a small block is not a blank");
        Rectangle shown = blanks.stream()
                .filter(element -> element.centerX() < 200 && element.centerY() < 150)
                .findFirst()
                .orElseThrow()
                .rectangle();
        List<UiElement> again = elements(image, 1, defaultDensity, 0, Vision.blankAreas,
                List.of(shown));
        assertEquals(0, within(again, 100, 50, 160, 110), "the blank already shown is gone");
    }
}
