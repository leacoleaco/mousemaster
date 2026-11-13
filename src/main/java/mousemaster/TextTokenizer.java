package mousemaster;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts text into the pieces the word picker tags: a web address, an e-mail address, a
 * date or a time, a number, a word in letters, a Chinese word, a punctuation mark.
 * 今天是11月3日，123 is 今天, 是, 11月3日, ，, 123. Whitespace is kept as a piece of its
 * own, so the pieces put back together are the text.
 */
public final class TextTokenizer {

    public enum Kind { URL, EMAIL, TIME, NUMBER, WORD, CHINESE, PUNCTUATION, SPACE }

    /** The piece is the text from {@code start} to {@code end}. */
    public record Token(int start, int end, Kind kind) {
        public String text(String source) {
            return source.substring(start, end);
        }
    }

    private static final Pattern URL = Pattern.compile(
            "(?:(?:https?|ftp)://|www\\.)[^\\s\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF<>\"]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._%+-]*@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern TIME = Pattern.compile(
            // 2024-11-03, 2024/11/3 14:05, 2024.11.03
            "\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)?(?![\\d])" +
            // 14:05, 14:05:30, 2:05 PM
            "|\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?[AaPp][Mm])?(?![\\d:])" +
            // 2024年11月3日, 2024年11月, 2024年
            "|\\d{2,4}年(?:\\d{1,2}月(?:\\d{1,2}[日号])?)?" +
            // 11月3日, 11月
            "|\\d{1,2}月(?:\\d{1,2}[日号])?" +
            // 3日
            "|\\d{1,2}日" +
            // 3点15分, 3时15分20秒, 3点半
            "|\\d{1,2}[点时]\\d{1,2}分?(?:\\d{1,2}秒)?" +
            "|\\d{1,2}点(?:半|钟|整)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*%?");
    private static final Pattern WORD = Pattern.compile(
            "[\\p{L}&&[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]]" +
            "[\\p{L}\\p{M}\\p{Nd}&&[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]]*" +
            "(?:['’_-][\\p{L}\\p{M}\\p{Nd}&&[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]]+)*");
    private static final Pattern[] PATTERNS = {URL, EMAIL, TIME, NUMBER, WORD};
    private static final Kind[] KINDS = {Kind.URL, Kind.EMAIL, Kind.TIME, Kind.NUMBER, Kind.WORD};
    /** A sentence does not end a web address with these, so they are left out of it. */
    private static final String URL_TRAILING = ".,;:!?)]}'";

    private final ChineseWords chineseWords;

    public TextTokenizer(ChineseWords chineseWords) {
        this.chineseWords = chineseWords;
    }

    public List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher[] matchers = new Matcher[PATTERNS.length];
        for (int index = 0; index < PATTERNS.length; index++)
            matchers[index] = PATTERNS[index].matcher(text);
        int length = text.length();
        int at = 0;
        while (at < length) {
            int codePoint = text.codePointAt(at);
            if (isSpace(codePoint)) {
                int end = at;
                while (end < length && isSpace(text.codePointAt(end)))
                    end += Character.charCount(text.codePointAt(end));
                tokens.add(new Token(at, end, Kind.SPACE));
                at = end;
                continue;
            }
            if (isHan(codePoint)) {
                int end = at;
                while (end < length && isHan(text.codePointAt(end)))
                    end += Character.charCount(text.codePointAt(end));
                int[] from = {at};
                chineseWords.cut(text, at, end, wordEnd -> {
                    tokens.add(new Token(from[0], wordEnd, Kind.CHINESE));
                    from[0] = wordEnd;
                });
                at = end;
                continue;
            }
            if (isKana(codePoint)) {
                int end = at;
                while (end < length && isKana(text.codePointAt(end)))
                    end += Character.charCount(text.codePointAt(end));
                tokens.add(new Token(at, end, Kind.CHINESE));
                at = end;
                continue;
            }
            int matched = -1;
            int matchedEnd = at;
            for (int index = 0; index < matchers.length && matched < 0; index++) {
                Matcher matcher = matchers[index];
                matcher.region(at, length);
                if (!matcher.lookingAt())
                    continue;
                int end = matcher.end();
                if (KINDS[index] == Kind.URL)
                    while (end > at + 1 && URL_TRAILING.indexOf(text.charAt(end - 1)) >= 0)
                        end--;
                if (end > at) {
                    matched = index;
                    matchedEnd = end;
                }
            }
            if (matched >= 0) {
                tokens.add(new Token(at, matchedEnd, KINDS[matched]));
                at = matchedEnd;
                continue;
            }
            // A mark repeated, such as …… or --- or !!!, is one piece.
            int end = at + Character.charCount(codePoint);
            while (end < length && text.codePointAt(end) == codePoint)
                end += Character.charCount(codePoint);
            tokens.add(new Token(at, end, Kind.PUNCTUATION));
            at = end;
        }
        return tokens;
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
               Character.isISOControl(codePoint) || codePoint == 0xFEFF ||
               codePoint == 0x200B;
    }

    /** The rare characters outside the basic plane are each left as a piece of their own. */
    static boolean isHan(int codePoint) {
        return codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT &&
               Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN &&
               Character.isLetter(codePoint);
    }

    private static boolean isKana(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return (script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA) && Character.isLetter(codePoint) ||
               codePoint == 0x30FC;
    }
}
