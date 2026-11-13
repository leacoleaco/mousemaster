package mousemaster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The word picker: a tag marks its word, another tag marks up to that word, backspace
 * takes the last one back, and what is marked is copied as it was written.
 */
class TextPickerTest {

    private static final TextTokenizer tokenizer = new TextTokenizer(ChineseWords.shared());

    private static List<Key> keys(String letters) {
        List<Key> keys = new ArrayList<>();
        for (String letter : letters.split(" "))
            keys.add(Key.ofCharacter(letter));
        return keys;
    }

    private static final List<Key> LETTERS =
            keys("a s d f g h j k l q w e r t y u i o p z x c v b n m");

    private static Key key(String letter) {
        return Key.ofCharacter(letter);
    }

    private static TextPicker picker(String text) {
        return TextPicker.of(text, tokenizer, LETTERS);
    }

    @Test
    void everyWordGetsATagAndWhitespaceGetsNone() {
        TextPicker picker = picker("今天是11月3日，123");
        assertEquals(5, picker.pieceCount());
        assertEquals(List.of("今天", "是", "11月3日", "，", "123"),
                List.of(picker.pieceText(0), picker.pieceText(1), picker.pieceText(2),
                        picker.pieceText(3), picker.pieceText(4)));
        assertEquals(List.of("A", "S", "D", "F", "G"),
                List.of(picker.tag(0), picker.tag(1), picker.tag(2), picker.tag(3),
                        picker.tag(4)));
        assertEquals(3, picker("hello  big\tworld").pieceCount());
    }

    @Test
    void oneTagMarksItsWordAndASecondMarksUpToThatWord() {
        TextPicker picker = picker("今天是11月3日，123");
        assertFalse(picker.hasSelection());
        // Nothing marked: the whole text is what is copied.
        assertEquals("今天是11月3日，123", picker.selectedText());

        assertTrue(picker.type(key("d")));
        assertTrue(picker.hasSelection());
        assertEquals(2, picker.selectionStart());
        assertEquals(2, picker.selectionEnd());
        assertEquals("11月3日", picker.selectedText());

        assertTrue(picker.type(key("g")));
        assertEquals(2, picker.selectionStart());
        assertEquals(4, picker.selectionEnd());
        assertEquals("11月3日，123", picker.selectedText());
    }

    @Test
    void furtherTagsMoveTheFarEndAndTheFirstWordStays() {
        TextPicker picker = picker("今天是11月3日，123");
        picker.type(key("d"));
        picker.type(key("g"));
        // A tag before the first word: the selection now runs back to it.
        assertTrue(picker.type(key("a")));
        assertEquals(0, picker.selectionStart());
        assertEquals(2, picker.selectionEnd());
        assertEquals("今天是11月3日", picker.selectedText());
        assertTrue(picker.type(key("f")));
        assertEquals("11月3日，", picker.selectedText());
        // The tag of the first word again: only that word.
        assertTrue(picker.type(key("d")));
        assertEquals("11月3日", picker.selectedText());
    }

    @Test
    void backspaceTakesBackTheLastTagOneAtATime() {
        TextPicker picker = picker("今天是11月3日，123");
        picker.type(key("d"));
        picker.type(key("g"));
        picker.type(key("a"));
        assertTrue(picker.undo());
        assertEquals("11月3日，123", picker.selectedText());
        assertTrue(picker.undo());
        assertEquals("11月3日", picker.selectedText());
        assertTrue(picker.undo());
        assertFalse(picker.hasSelection());
        assertEquals(-1, picker.selectionStart());
        assertFalse(picker.undo());
    }

    @Test
    void whatIsCopiedKeepsTheSpacesAndMarksBetweenTheWords() {
        TextPicker picker = picker("Say hello,  big world!");
        // Say hello , big world !
        assertEquals(6, picker.pieceCount());
        picker.type(key("s"));
        picker.type(key("g"));
        assertEquals("hello,  big world", picker.selectedText());
    }

    @Test
    void manyWordsGetTagsOfTwoLettersTypedOneLetterAtATime() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 30; index++)
            text.append("w").append(index).append(' ');
        TextPicker picker = picker(text.toString());
        assertEquals(30, picker.pieceCount());
        assertEquals("AA", picker.tag(0));
        assertEquals("AS", picker.tag(1));
        assertEquals("SA", picker.tag(26));
        assertEquals("SF", picker.tag(29));

        // The first letter marks nothing yet.
        assertTrue(picker.type(key("s")));
        assertFalse(picker.hasSelection());
        // No tag is s then g: there are only 30 words. The letter is dropped.
        assertFalse(picker.type(key("g")));
        // Backspace takes back the letter, not a tag.
        assertTrue(picker.undo());
        assertFalse(picker.undo());

        picker.type(key("a"));
        assertTrue(picker.type(key("d")));
        assertEquals("w2", picker.selectedText());
        picker.type(key("s"));
        assertTrue(picker.undo());
        assertEquals("w2", picker.selectedText());
        picker.type(key("s"));
        picker.type(key("f"));
        assertEquals(2, picker.selectionStart());
        assertEquals(29, picker.selectionEnd());
        assertTrue(picker.selectedText().startsWith("w2 w3 "));
        assertTrue(picker.selectedText().endsWith(" w29"));
    }

    @Test
    void aKeyThatIsNoLetterOfATagChangesNothing() {
        TextPicker picker = TextPicker.of("one two", tokenizer, keys("a s"));
        assertFalse(picker.type(key("d")));
        assertFalse(picker.hasSelection());
    }

    @Test
    void theWordsAreLaidOutAsAParagraphAndMarkedWordsAreHighlighted() {
        TextPicker picker = picker("今天是11月3日，123\n第二行");
        String html = picker.html();
        // One table per line: the line break of the text starts a new one.
        assertEquals(2, count(html, "<table cellspacing=3"));
        assertEquals(0, count(html, "class=pieceon"));
        assertTrue(html.contains("今天"));
        assertTrue(html.contains("还没有选词"));
        picker.type(key("s"));
        picker.type(key("f"));
        html = picker.html();
        assertEquals(3, count(html, "class=pieceon"));
        assertTrue(html.contains("已选 3 个词"));
        assertTrue(html.contains("是11月3日，"));
    }

    @Test
    void aLongTextWrapsAndWhatDoesNotFitIsLeftOut() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 2000; index++)
            text.append("word").append(index).append(' ');
        TextPicker picker = picker(text.toString());
        assertTrue(picker.pieceCount() < 2000);
        assertTrue(picker.pieceCount() > 20);
        assertEquals(TextPicker.MAX_LINES, count(picker.html(), "<table cellspacing=3"));
        assertTrue(picker.html().contains("文字太长"));
        // Nothing marked still copies all of it.
        assertEquals(text.toString().strip(), picker.selectedText());
    }

    @Test
    void textIsEscaped() {
        TextPicker picker = picker("a<b>&c");
        String html = picker.html();
        assertTrue(html.contains("&lt;"));
        assertTrue(html.contains("&amp;"));
        assertFalse(html.contains("<b>"));
    }

    @Test
    void onlyWhitespaceIsEmpty() {
        assertTrue(picker(" \n\t ").isEmpty());
        assertTrue(picker("").isEmpty());
        assertTrue(picker("").html().contains("没有读到文字"));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
            count++;
        return count;
    }
}
