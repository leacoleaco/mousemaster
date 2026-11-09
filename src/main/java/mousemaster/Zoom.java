package mousemaster;

public record Zoom(double percent, Point center, Rectangle screenRectangle,
                   boolean cursorAnchored) {

    /** The center is drawn at the middle of the screen. */
    public Zoom(double percent, Point center, Rectangle screenRectangle) {
        this(percent, center, screenRectangle, false);
    }

    /**
     * Where {@code center} is drawn. A magnifier around the cursor keeps that pixel under
     * the cursor. Every other zoom puts it in the middle of the screen.
     */
    private Point anchor() {
        return cursorAnchored ? center : screenRectangle.center();
    }

    public double zoomedX(double x) {
        return anchor().x() + (x - center.x()) * percent;
    }

    public double zoomedY(double y) {
        return anchor().y() + (y - center.y()) * percent;
    }

    public double unzoomedX(double zoomedX) {
        return (zoomedX - anchor().x()) / percent + center.x();
    }

    public double unzoomedY(double zoomedY) {
        return (zoomedY - anchor().y()) / percent + center.y();
    }

    /** A desktop region as it appears on screen. */
    public Rectangle zoomedRectangle(Rectangle rectangle) {
        int left = (int) Math.round(zoomedX(rectangle.x()));
        int top = (int) Math.round(zoomedY(rectangle.y()));
        return new Rectangle(left, top,
                (int) Math.round(zoomedX(rectangle.x() + rectangle.width())) - left,
                (int) Math.round(zoomedY(rectangle.y() + rectangle.height())) - top);
    }

    public Rectangle unzoomedRectangle(Rectangle rectangle) {
        int left = (int) Math.round(unzoomedX(rectangle.x()));
        int top = (int) Math.round(unzoomedY(rectangle.y()));
        return new Rectangle(left, top,
                (int) Math.round(unzoomedX(rectangle.x() + rectangle.width())) - left,
                (int) Math.round(unzoomedY(rectangle.y() + rectangle.height())) - top);
    }

}
