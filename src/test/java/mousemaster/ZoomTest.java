package mousemaster;

import mousemaster.platform.MouseController;
import mousemaster.platform.Overlay;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Proxy;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoomTest {

    private static final Rectangle SCREEN = new Rectangle(0, 0, 1920, 1080);

    @Test
    void aCursorZoomKeepsTheCursorPixelUnderTheCursor() {
        Zoom zoom = new Zoom(4, new Point(400, 300), SCREEN, true);
        assertEquals(400, zoom.zoomedX(400), 0);
        assertEquals(300, zoom.zoomedY(300), 0);
        assertEquals(440, zoom.zoomedX(410), 0);
        assertEquals(410, zoom.unzoomedX(440), 0);
    }

    @Test
    void aScreenCenteredZoomDrawsTheCenterAtTheMiddleOfTheScreen() {
        Zoom zoom = new Zoom(4, new Point(400, 300), SCREEN);
        assertEquals(960, zoom.zoomedX(400), 0);
        assertEquals(540, zoom.zoomedY(300), 0);
        assertEquals(400, zoom.unzoomedX(960), 0);
    }

    @Test
    void zoomingAroundTheMouseAnchorsThePictureOnTheCursor() throws Exception {
        ModeMap modeMap = ConfigurationParser.parse(PropertiesReader.readPropertiesFile(
                new BufferedReader(new StringReader("""
                        idle-mode.to.zoom-mode=+x
                        zoom-mode.to.idle-mode=+x
                        zoom-mode.zoom.percent=4
                        zoom-mode.zoom.center=mouse
                        zoom-mode.zoom.transition-enabled=false
                        """))),
                KeyboardLayout.keyboardLayoutByShortName.get("uk-qwerty")).modeMap();
        ScreenManager screenManager = new ScreenManager(
                () -> Set.of(new Screen(SCREEN, 96, 1)));
        Zoom[] shown = new Zoom[1];
        Overlay overlay = (Overlay) Proxy.newProxyInstance(Overlay.class.getClassLoader(),
                new Class<?>[]{Overlay.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setZoom"))
                        shown[0] = (Zoom) args[0];
                    return method.getName().equals("canZoom") ? true : null;
                });
        MouseController mouse = (MouseController) Proxy.newProxyInstance(
                MouseController.class.getClassLoader(), new Class<?>[]{MouseController.class},
                (proxy, method, args) -> null);
        HintManager hintManager = new HintManager(Map.of(), screenManager,
                new MouseManager(screenManager, mouse), overlay, null, null,
                new KeyRedactor(KeyRedaction.NONE), null);
        hintManager.mouseMoved(400, 300);
        ZoomManager zoomManager = new ZoomManager(screenManager, hintManager, overlay);
        hintManager.modeChanged(modeMap.get("zoom-mode"));
        zoomManager.modeChanged(modeMap.get("zoom-mode"));
        assertTrue(shown[0].cursorAnchored());
        assertEquals(4, shown[0].percent());
        assertEquals(400, shown[0].zoomedX(400), 0);
        assertEquals(300, shown[0].zoomedY(300), 0);
        hintManager.modeChanged(modeMap.get("idle-mode"));
        zoomManager.modeChanged(modeMap.get("idle-mode"));
        assertNull(shown[0]);
    }
}
