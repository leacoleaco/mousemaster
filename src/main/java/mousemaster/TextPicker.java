package mousemaster;

import java.util.ArrayList;
import java.util.List;

/**
 * The word picker: a piece of text cut into words, laid out as a paragraph, each word
 * under a tag of letters. Typing a tag marks its word. Typing another marks everything
 * from the first word to that one, and a third moves the far end again. Backspace takes
 * back the letter just typed, or else the last tag. What is marked is copied as it was
 * written, with the spaces and marks between the words.
 */
public final class TextPicker {

    /** Left in the indicator text and replaced with the picker when it is drawn. */
    public static final String PLACEHOLDER = "[[text-picker]]";

    /** How wide a line may get, in widths of a Chinese character. */
    static final double LINE_WIDTH = 40;
    static final int MAX_LINES = 14;
    private static final int MAX_PIECE_CHARACTERS = 36;
    private static final int STATUS_CHARACTERS = 44;

    /** A word that is shown, and the line it is on. */
    record Piece(int start, int end, int line) {}

    private final String text;
    private final List<Piece> pieces = new ArrayList<>();
    private final List<Key> alphabet;
    private final int tagLength;
    /** The text did not fit: the words after the last line have no tag. */
    private final boolean truncated;
    private final List<Key> typed = new ArrayList<>();
    /** The words whose tags were typed, in that order. */
    private final List<Integer> anchors = new ArrayList<>();

    public TextPicker(String text, List<TextTokenizer.Token> tokens, List<Key> alphabet) {
        this.text = text;
        this.alphabet = List.copyOf(alphabet);
        int line = 0;
        double width = 0;
        boolean cut = false;
        boolean breakBefore = false;
        for (TextTokenizer.Token token : tokens) {
            if (token.kind() == TextTokenizer.Kind.SPACE) {
                if (text.indexOf('\n', token.start()) >= 0 &&
                    text.indexOf('\n', token.start()) < token.end())
                    breakBefore = true;
                continue;
            }
            double pieceWidth = pieceWidth(display(token.text(text)));
            if (width > 0 && (breakBefore || width + pieceWidth > LINE_WIDTH)) {
                line++;
                width = 0;
            }
            breakBefore = false;
            if (line >= MAX_LINES) {
                cut = true;
                break;
            }
            pieces.add(new Piece(token.start(), token.end(), line));
            width += pieceWidth;
        }
        truncated = cut;
        int length = 1;
        if (!this.alphabet.isEmpty()) {
            long reach = this.alphabet.size();
            while (reach < pieces.size() && this.alphabet.size() > 1) {
                reach *= this.alphabet.size();
                length++;
            }
        }
        tagLength = length;
    }

    public static TextPicker of(String text, TextTokenizer tokenizer, List<Key> alphabet) {
        return new TextPicker(text, tokenizer.tokenize(text), alphabet);
    }

    public boolean isEmpty() {
        return pieces.isEmpty();
    }

    public int pieceCount() {
        return pieces.size();
    }

    public String pieceText(int index) {
        Piece piece = pieces.get(index);
        return text.substring(piece.start(), piece.end());
    }

    /** The letters of the word at {@code index}. */
    public String tag(int index) {
        StringBuilder tag = new StringBuilder();
        for (Key key : tagKeys(index))
            tag.append(key.hintLabel());
        return tag.toString();
    }

    List<Key> tagKeys(int index) {
        Key[] keys = new Key[tagLength];
        int rest = index;
        for (int at = tagLength - 1; at >= 0; at--) {
            keys[at] = alphabet.get(rest % alphabet.size());
            rest /= alphabet.size();
        }
        return List.of(keys);
    }

    /**
     * One more letter of a tag. The letter that completes a tag marks its word. A letter
     * that no tag continues with is dropped. False when nothing changed.
     */
    public boolean type(Key key) {
        int letter = alphabet.indexOf(key);
        if (letter < 0 || pieces.isEmpty())
            return false;
        long prefix = letter;
        for (int at = typed.size() - 1, scale = alphabet.size(); at >= 0; at--) {
            prefix += (long) alphabet.indexOf(typed.get(at)) * scale;
            scale *= alphabet.size();
        }
        long first = prefix;
        for (int rest = tagLength - typed.size() - 1; rest > 0; rest--)
            first *= alphabet.size();
        if (first >= pieces.size())
            return false;
        typed.add(key);
        if (typed.size() == tagLength) {
            anchors.add((int) prefix);
            typed.clear();
        }
        return true;
    }

    /** Takes back the letter just typed, or else the last tag. False when there is neither. */
    public boolean undo() {
        if (!typed.isEmpty()) {
            typed.removeLast();
            return true;
        }
        if (!anchors.isEmpty()) {
            anchors.removeLast();
            return true;
        }
        return false;
    }

    public boolean hasSelection() {
        return !anchors.isEmpty();
    }

    /** The first marked word, or -1. */
    public int selectionStart() {
        return anchors.isEmpty() ? -1 : Math.min(anchors.getFirst(), anchors.getLast());
    }

    /** The last marked word, or -1. */
    public int selectionEnd() {
        return anchors.isEmpty() ? -1 : Math.max(anchors.getFirst(), anchors.getLast());
    }

    /** What is copied: the marked words as they were written, or all the text when none is. */
    public String selectedText() {
        if (anchors.isEmpty())
            return text.strip();
        return text.substring(pieces.get(selectionStart()).start(),
                pieces.get(selectionEnd()).end());
    }

    public static String loadingHtml() {
        return message("正在读取文字…", "Esc 取消");
    }

    public static String emptyHtml() {
        return message("这里没有读到文字", "Esc 或 空格 返回");
    }

    private static String message(String message, String hint) {
        return "<table cellspacing=0 cellpadding=0>" +
               "<tr><td class=title>拆词选字</td></tr>" +
               "<tr><td class=hint>" + hint + "</td></tr>" +
               "<tr><td class=empty>" + message + "</td></tr></table>";
    }

    public String html() {
        if (pieces.isEmpty())
            return emptyHtml();
        StringBuilder html = new StringBuilder();
        html.append("<table cellspacing=0 cellpadding=0>");
        html.append("<tr><td class=title>拆词选字</td></tr>");
        html.append("<tr><td class=hint>字母 选词&nbsp;&nbsp;再输入字母 选到那个词&nbsp;&nbsp;")
            .append("空格 / 回车 复制&nbsp;&nbsp;退格 撤销&nbsp;&nbsp;Esc 取消</td></tr>");
        html.append("<tr><td class=lines>");
        int from = selectionStart();
        int to = selectionEnd();
        int line = -1;
        for (int index = 0; index < pieces.size(); index++) {
            Piece piece = pieces.get(index);
            if (piece.line() != line) {
                if (line >= 0)
                    html.append("</tr></table>");
                html.append("<table cellspacing=3 cellpadding=0><tr>");
                line = piece.line();
            }
            appendPiece(html, index, index >= from && index <= to);
        }
        html.append("</tr></table>");
        html.append("</td></tr>");
        html.append("<tr><td class=status>").append(status()).append("</td></tr>");
        html.append("</table>");
        return html.toString();
    }

    private void appendPiece(StringBuilder html, int index, boolean marked) {
        List<Key> keys = tagKeys(index);
        boolean matches = keys.subList(0, typed.size()).equals(typed);
        html.append("<td class=").append(marked ? "pieceon" : "piece")
            .append(" align=center bgcolor=\"").append(marked ? "#7AA2F7" : "#24283B")
            .append("\">");
        if (marked)
            html.append("<span class=ptagon>").append(WindowMenu.escape(tag(index)))
                .append("</span>");
        else if (!matches)
            html.append("<span class=ptagoff>").append(WindowMenu.escape(tag(index)))
                .append("</span>");
        else {
            // The letters already typed are dimmed, so the ones left to type stand out.
            if (!typed.isEmpty()) {
                html.append("<span class=ptagoff>");
                for (int at = 0; at < typed.size(); at++)
                    html.append(WindowMenu.escape(keys.get(at).hintLabel()));
                html.append("</span>");
            }
            html.append("<span class=ptag>");
            for (int at = typed.size(); at < keys.size(); at++)
                html.append(WindowMenu.escape(keys.get(at).hintLabel()));
            html.append("</span>");
        }
        html.append("<br>").append(WindowMenu.escape(display(pieceText(index))))
            .append("</td>");
    }

    private String status() {
        StringBuilder status = new StringBuilder();
        if (anchors.isEmpty())
            status.append("还没有选词，空格 / 回车 复制整段");
        else {
            int count = selectionEnd() - selectionStart() + 1;
            status.append("已选 ").append(count).append(" 个词：")
                  .append(WindowMenu.escape(shortened(selectedText(), STATUS_CHARACTERS)));
        }
        if (truncated)
            status.append("&nbsp;&nbsp;（文字太长，后面的没有显示）");
        return status.toString();
    }

    /** A long web address is shown by its two ends. All of it is still copied. */
    private static String display(String piece) {
        return shortened(piece, MAX_PIECE_CHARACTERS);
    }

    private static String shortened(String value, int limit) {
        String single = value.replaceAll("\\s+", " ");
        if (single.codePointCount(0, single.length()) <= limit)
            return single;
        int head = single.offsetByCodePoints(0, limit * 2 / 3);
        int tail = single.offsetByCodePoints(single.length(), -(limit / 3 - 1));
        return single.substring(0, head) + "…" + single.substring(tail);
    }

    /** The width of a word's cell: the word, or its tag when that is wider, plus the gap. */
    private static double pieceWidth(String display) {
        double width = 0;
        for (int at = 0; at < display.length(); ) {
            int codePoint = display.codePointAt(at);
            width += characterWidth(codePoint);
            at += Character.charCount(codePoint);
        }
        return Math.max(width, 1.2) + 1.1;
    }

    private static double characterWidth(int codePoint) {
        if (codePoint >= 0x2E80 && codePoint <= 0x9FFF || codePoint >= 0xF900 && codePoint <= 0xFAFF ||
            codePoint >= 0xFE30 && codePoint <= 0xFE4F || codePoint >= 0xFF00 && codePoint <= 0xFF60 ||
            codePoint >= 0xFFE0 && codePoint <= 0xFFE6 || codePoint >= 0x1F000)
            return 1;
        if (Character.isUpperCase(codePoint) || Character.isDigit(codePoint))
            return 0.68;
        return 0.58;
    }
}
