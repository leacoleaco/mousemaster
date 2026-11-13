package mousemaster.renderer;

import mousemaster.Hint;
import mousemaster.HintMesh;
import mousemaster.Key;
import mousemaster.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollapsedHintLabelTest {

    private static Hint hint(String label, boolean overlapped) {
        return new Hint(150, 120, -1, -1,
                label.chars().mapToObj(name -> Key.ofName(String.valueOf((char) name))).toList(),
                new Rectangle(100, 100, 100, 40), null, null, overlapped);
    }

    private static HintMesh hintMesh(List<Hint> hints, List<Key> selectedKeySequence) {
        return new HintMesh.HintMeshBuilder()
                .hints(hints)
                .prefixLength(-1)
                .selectedKeySequence(selectedKeySequence)
                .build();
    }

    @Test
    void anOverlappedHintShowsItsFirstKeyUntilAKeyIsSelected() {
        Hint overlapped = hint("sd", true);
        Hint alone = hint("fa", false);
        HintMesh hintMesh = hintMesh(List.of(overlapped, alone), List.of());
        assertTrue(HintMeshRenderer.collapsed(overlapped, hintMesh));
        assertFalse(HintMeshRenderer.collapsed(alone, hintMesh));
        assertFalse(HintMeshRenderer.collapsed(overlapped,
                hintMesh(List.of(overlapped, alone), List.of(Key.ofName("s")))));
    }

    @Test
    void aLabelOfOneKeyIsShownWhole() {
        Hint overlapped = hint("s", true);
        assertFalse(HintMeshRenderer.collapsed(overlapped,
                hintMesh(List.of(overlapped), List.of())));
    }

}
