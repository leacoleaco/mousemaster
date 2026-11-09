package mousemaster;

public class MouseState {

    private final MouseManager mouseManager;

    public MouseState(MouseManager mouseManager) {
        this.mouseManager = mouseManager;
    }

    public boolean moving() {
        return mouseManager.moving();
    }

    public boolean movingUp() {
        return mouseManager.movingUp();
    }

    public boolean movingDown() {
        return mouseManager.movingDown();
    }

    public boolean movingLeft() {
        return mouseManager.movingLeft();
    }

    public boolean movingRight() {
        return mouseManager.movingRight();
    }

    public boolean leftPressing() {
        return mouseManager.leftPressing();
    }

    public boolean middlePressing() {
        return mouseManager.middlePressing();
    }

    public boolean rightPressing() {
        return mouseManager.rightPressing();
    }

    public boolean wheeling() {
        return mouseManager.wheeling();
    }

    public boolean wheelingUp() {
        return mouseManager.wheelingUp();
    }

    public boolean wheelingDown() {
        return mouseManager.wheelingDown();
    }

    public boolean wheelingLeft() {
        return mouseManager.wheelingLeft();
    }

    public boolean wheelingRight() {
        return mouseManager.wheelingRight();
    }

    public boolean pressing() {
        return leftPressing() || middlePressing() || rightPressing();
    }

    public boolean idling() {
        return !moving() && !wheeling() && !pressing();
    }

}
