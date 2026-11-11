package mousemaster;

/**
 * One row of the window list. {@code iconName} is the indicator image name for the
 * program icon, or null when that icon is not available yet.
 */
public record WindowEntry(String title, String processName, String iconName) {

    public WindowEntry {
        title = title == null ? "" : title;
        processName = processName == null ? "" : processName;
    }

}
