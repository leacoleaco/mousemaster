package mousemaster;

/**
 * One row of the window list. {@code iconSource} is what the row's image loads, as
 * {@link WindowIconImages#source} writes it, or null when the program icon has not
 * been read or there is none.
 */
public record WindowEntry(String title, String processName, String iconSource) {

    public WindowEntry {
        title = title == null ? "" : title;
        processName = processName == null ? "" : processName;
    }

}
