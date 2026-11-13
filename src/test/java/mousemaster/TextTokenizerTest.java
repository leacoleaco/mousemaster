package mousemaster;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text of the word picker is cut into web addresses, e-mail addresses, dates and
 * times, numbers, words in letters, Chinese words and punctuation marks.
 */
class TextTokenizerTest {

    private static final TextTokenizer tokenizer = new TextTokenizer(ChineseWords.shared());

    /** The pieces that get a tag: everything but the whitespace. */
    private static List<String> pieces(String text) {
        List<String> pieces = new ArrayList<>();
        for (TextTokenizer.Token token : tokenizer.tokenize(text))
            if (token.kind() != TextTokenizer.Kind.SPACE)
                pieces.add(token.text(text));
        return pieces;
    }

    private static TextTokenizer.Kind kindOf(String text, String piece) {
        for (TextTokenizer.Token token : tokenizer.tokenize(text))
            if (token.text(text).equals(piece))
                return token.kind();
        throw new AssertionError(piece + " is not a piece of " + pieces(text));
    }

    @Test
    void theDateStaysWholeBetweenChineseWordsAndANumber() {
        assertEquals(List.of("今天", "是", "11月3日", "，", "123"), pieces("今天是11月3日，123"));
        assertEquals(TextTokenizer.Kind.TIME, kindOf("今天是11月3日，123", "11月3日"));
        assertEquals(TextTokenizer.Kind.NUMBER, kindOf("今天是11月3日，123", "123"));
        assertEquals(TextTokenizer.Kind.PUNCTUATION, kindOf("今天是11月3日，123", "，"));
        assertEquals(TextTokenizer.Kind.CHINESE, kindOf("今天是11月3日，123", "今天"));
    }

    @Test
    void englishWordsKeepTheirApostrophesAndUnderscores() {
        assertEquals(List.of("Hello", "world", ",", "it's", "user_name", "and", "e-mail", "!"),
                pieces("Hello world, it's user_name and e-mail!"));
    }

    @Test
    void aNumberKeepsItsDecimalsItsSeparatorsAndItsPercentSign() {
        assertEquals(List.of("3.14", "1,234,567", "50%", "v2"), pieces("3.14 1,234,567 50% v2"));
        assertEquals(List.of("共", "3", "个"), pieces("共3个"));
    }

    @Test
    void anAddressIsOnePiece() {
        String text = "写信到 a.b-c@example.co.uk，或看 https://example.com/a/b?c=1&d=2#e。";
        List<String> pieces = pieces(text);
        assertTrue(pieces.contains("a.b-c@example.co.uk"), pieces.toString());
        assertTrue(pieces.contains("https://example.com/a/b?c=1&d=2#e"), pieces.toString());
        assertEquals("。", pieces.getLast());
        assertEquals(TextTokenizer.Kind.EMAIL, kindOf(text, "a.b-c@example.co.uk"));
        assertEquals(TextTokenizer.Kind.URL, kindOf(text, "https://example.com/a/b?c=1&d=2#e"));
        // The full stop that ends the sentence is not part of the address.
        assertEquals(List.of("See", "www.example.com/x", "."), pieces("See www.example.com/x."));
    }

    @Test
    void datesAndTimesAreOnePiece() {
        assertEquals(List.of("2024-11-03 14:05", "开会"), pieces("2024-11-03 14:05 开会"));
        assertEquals(List.of("14:05:30"), pieces("14:05:30"));
        assertEquals(List.of("2024年11月3日", "，", "3点半"), pieces("2024年11月3日，3点半"));
        assertEquals(List.of("2024/11/3"), pieces("2024/11/3"));
        assertEquals(List.of("9:30 AM"), pieces("9:30 AM"));
    }

    @Test
    void aRepeatedMarkIsOnePieceAndDifferentMarksAreSeparate() {
        assertEquals(List.of("好", "……", "吗", "？", "！"), pieces("好……吗？！"));
        assertEquals(List.of("a", "---", "b"), pieces("a --- b"));
        assertEquals(List.of("(", "x", ")"), pieces("(x)"));
    }

    @Test
    void thePiecesPutBackTogetherAreTheText() {
        String text = "今天是11月3日，123\n  Mail me: x@y.org (http://a.b/c)…… 好的！\t尾";
        StringBuilder joined = new StringBuilder();
        int at = 0;
        for (TextTokenizer.Token token : tokenizer.tokenize(text)) {
            assertEquals(at, token.start());
            assertTrue(token.end() > token.start());
            joined.append(token.text(text));
            at = token.end();
        }
        assertEquals(text, joined.toString());
    }

    @Test
    void chineseIsCutIntoTheMostLikelyWords() throws Exception {
        ChineseWords words = ChineseWords.read(new BufferedReader(new StringReader(
                "今天 1000\n今 50\n天 400\n是 3000\n天是 2\n北京 800\n北 60\n京 40\n")));
        TextTokenizer small = new TextTokenizer(words);
        List<String> pieces = new ArrayList<>();
        String text = "今天是北京龘";
        for (TextTokenizer.Token token : small.tokenize(text))
            pieces.add(token.text(text));
        // A character the list does not know is a word of its own.
        assertEquals(List.of("今天", "是", "北京", "龘"), pieces);
    }

    @Test
    void theBundledListKnowsEverydayWords() {
        assertEquals(List.of("复制", "到", "剪贴板"), pieces("复制到剪贴板"));
        assertEquals(List.of("我们", "明天", "开会"), pieces("我们明天开会"));
    }

    @Test
    void withoutAListEveryCharacterIsAWord() {
        TextTokenizer none = new TextTokenizer(ChineseWords.of(new String[0], new int[0], 0));
        List<String> pieces = new ArrayList<>();
        for (TextTokenizer.Token token : none.tokenize("今天是"))
            pieces.add(token.text("今天是"));
        assertEquals(List.of("今", "天", "是"), pieces);
    }
}
