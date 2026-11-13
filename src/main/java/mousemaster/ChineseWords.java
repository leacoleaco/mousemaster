package mousemaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/**
 * Cuts a run of Chinese characters into words. The words and how often each is used come
 * from the bundled list (chinese-words.txt, derived from jieba's dictionary). A run is
 * cut the way that makes its words the most likely ones together, so 今天是 is 今天 and 是.
 * A character the list does not know is a word of its own.
 * <p>
 * The list is read the first time a run is cut, which takes a moment: callers cut text
 * away from the thread that reads the keyboard.
 */
public final class ChineseWords {

    private static final Logger logger = LoggerFactory.getLogger(ChineseWords.class);

    public static final String RESOURCE = "chinese-words.txt";
    private static final int MAX_WORD_LENGTH = 8;

    private static ChineseWords shared;

    /** Open addressing. A slot is free while its key is 0. */
    private final long[] keys;
    private final float[] logFrequencies;
    private final int mask;
    private final float logTotal;

    private ChineseWords(long[] keys, float[] logFrequencies, float logTotal) {
        this.keys = keys;
        this.logFrequencies = logFrequencies;
        this.mask = keys.length - 1;
        this.logTotal = logTotal;
    }

    /** The bundled list. Without it every character is its own word. */
    public static synchronized ChineseWords shared() {
        if (shared == null) {
            long before = System.nanoTime();
            try (InputStream stream = MousemasterApplication.resourceStream(RESOURCE);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                shared = read(reader);
                logger.info("Read " + shared.size() + " Chinese words in " +
                            (System.nanoTime() - before) / 1_000_000 + "ms");
            }
            catch (Exception e) {
                logger.warn("Unable to read " + RESOURCE +
                            ", Chinese text is cut one character at a time", e);
                shared = of(new String[0], new int[0], 0);
            }
        }
        return shared;
    }

    /** Lines of {@code word frequency}. */
    static ChineseWords read(BufferedReader reader) throws java.io.IOException {
        String[] words = new String[1 << 14];
        int[] frequencies = new int[1 << 14];
        int count = 0;
        for (String line; (line = reader.readLine()) != null; ) {
            int space = line.indexOf(' ');
            if (space <= 0)
                continue;
            int frequency;
            try {
                frequency = Integer.parseInt(line, space + 1, line.length(), 10);
            }
            catch (NumberFormatException e) {
                continue;
            }
            if (count == words.length) {
                words = java.util.Arrays.copyOf(words, count * 2);
                frequencies = java.util.Arrays.copyOf(frequencies, count * 2);
            }
            words[count] = line.substring(0, space);
            frequencies[count] = Math.max(1, frequency);
            count++;
        }
        return of(words, frequencies, count);
    }

    static ChineseWords of(String[] words, int[] frequencies, int count) {
        int capacity = 16;
        while (capacity < count * 2)
            capacity <<= 1;
        long[] keys = new long[capacity];
        float[] logFrequencies = new float[capacity];
        int mask = capacity - 1;
        double total = 0;
        for (int index = 0; index < count; index++) {
            String word = words[index];
            if (word.isEmpty() || word.length() > MAX_WORD_LENGTH)
                continue;
            total += frequencies[index];
            long key = 0;
            for (int at = 0; at < word.length(); at++)
                key = extend(key, word.charAt(at));
            int slot = (int) (key ^ (key >>> 32)) & mask;
            while (keys[slot] != 0 && keys[slot] != key)
                slot = (slot + 1) & mask;
            keys[slot] = key;
            logFrequencies[slot] = (float) Math.log(frequencies[index]);
        }
        return new ChineseWords(keys, logFrequencies, (float) Math.log(Math.max(1, total)));
    }

    private int size() {
        int size = 0;
        for (long key : keys)
            if (key != 0)
                size++;
        return size;
    }

    /** The key of a word, one character at a time. Never 0, which marks a free slot. */
    private static long extend(long key, char character) {
        long extended = (key ^ character) * 0x100000001B3L + 0x9E3779B97F4A7C15L;
        return extended == 0 ? 1 : extended;
    }

    private float logFrequency(long key) {
        int slot = (int) (key ^ (key >>> 32)) & mask;
        while (keys[slot] != 0) {
            if (keys[slot] == key)
                return logFrequencies[slot];
            slot = (slot + 1) & mask;
        }
        return Float.NaN;
    }

    /**
     * Cuts {@code text} from {@code start} to {@code end} and reports where each word
     * ends, in order. The last one reported is {@code end}.
     */
    public void cut(CharSequence text, int start, int end, IntConsumer wordEnd) {
        int length = end - start;
        if (length <= 0)
            return;
        // best[i] is the score of the best cut of the text from i on.
        float[] best = new float[length + 1];
        int[] wordLength = new int[length];
        for (int from = length - 1; from >= 0; from--) {
            long key = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            int bestLength = 1;
            int longest = Math.min(MAX_WORD_LENGTH, length - from);
            for (int size = 1; size <= longest; size++) {
                key = extend(key, text.charAt(start + from + size - 1));
                float logFrequency = logFrequency(key);
                if (Float.isNaN(logFrequency)) {
                    if (size > 1)
                        continue;
                    // An unknown character is as rare as a word seen once.
                    logFrequency = 0;
                }
                float score = logFrequency - logTotal + best[from + size];
                if (score > bestScore) {
                    bestScore = score;
                    bestLength = size;
                }
            }
            best[from] = bestScore;
            wordLength[from] = bestLength;
        }
        for (int from = 0; from < length; from += wordLength[from])
            wordEnd.accept(start + from + wordLength[from]);
    }
}
