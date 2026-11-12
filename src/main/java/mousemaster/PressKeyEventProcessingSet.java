package mousemaster;

import mousemaster.ComboPrecondition.PressedKeyPrecondition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PressKeyEventProcessingSet(
        Map<Combo, PressKeyEventProcessing> processingByCombo,
        Map<Combo, ComboSequenceMatch> matchByCombo) {

    /**
     * Marks a key that was not a command, so the press and its release are eaten
     * without joining the eaten-key list that regurgitation replays into the OS.
     */
    public static final Combo swallowedNonFunctionKeyCombo = new Combo(
            "swallowed-non-function-key", new ComboPrecondition(
            new ComboPrecondition.ComboKeyPrecondition(Set.of(),
                    new PressedKeyPrecondition(List.of())),
            new ComboPrecondition.ComboAppPrecondition(Set.of(), Set.of()),
            new ComboPrecondition.ComboVariablePrecondition(List.of())),
            new ComboSequence(List.of(), Set.of()));

    /**
     * Used for hints and unknown combo (combo of another mode, for which the key is a precondition key).
     */
    public static final Combo dummyCombo = new Combo("dummy", new ComboPrecondition(
            new ComboPrecondition.ComboKeyPrecondition(Set.of(),
                    new PressedKeyPrecondition(List.of())),
            new ComboPrecondition.ComboAppPrecondition(Set.of(), Set.of()),
            new ComboPrecondition.ComboVariablePrecondition(List.of())),
            new ComboSequence(List.of(), Set.of()));

    public boolean mustBeEaten() {
        return processingByCombo.values().stream().anyMatch(
                PressKeyEventProcessing::mustBeEaten);
    }

    public boolean handled() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::handled);
    }

    public boolean isPartOfComboSequence() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isPartOfComboSequence);
    }

    public boolean isPartOfCompletedComboSequenceAndMustBeEaten() {
        return processingByCombo.values().stream()
                                .anyMatch(
                                        PressKeyEventProcessing::isPartOfCompletedComboSequenceAndMustBeEaten);
    }

    public boolean hasInProgressMustBeEatenCombo() {
        return processingByCombo.values().stream()
                                .anyMatch(p -> p.isPartOfComboSequence() && p.mustBeEaten()
                                            && !p.isPartOfCompletedComboSequence());
    }

    public boolean isPartOfCompletedComboSequence() {
        return processingByCombo.values().stream()
                                .anyMatch(
                                        PressKeyEventProcessing::isPartOfCompletedComboSequence);
    }

    public List<ComboAndMatch> partOfCompletedComboSequenceCombosWithMatches() {
        List<ComboAndMatch> result = new java.util.ArrayList<>();
        for (Map.Entry<Combo, PressKeyEventProcessing> entry : processingByCombo.entrySet()) {
            if (entry.getValue().isPartOfCompletedComboSequence()) {
                Combo combo = entry.getKey();
                ComboSequenceMatch match = matchByCombo.getOrDefault(combo, ComboSequenceMatch.noMatch());
                result.add(new ComboAndMatch(combo, match));
            }
        }
        return result;
    }

    public boolean isPartOfPressedComboPreconditionOnly() {
        return processingByCombo.values().stream()
                                .anyMatch(
                                        PressKeyEventProcessing::isPartOfPressedComboPreconditionOnly);
    }

    public boolean isPartOfUnpressedComboPreconditionOnly() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(
                                        PressKeyEventProcessing::isPartOfUnpressedComboPreconditionOnly);
    }

    public boolean isPartOfCombo() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isPartOfCombo);
    }

    public boolean isIgnoredByLeadingWait() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isIgnoredByLeadingWait);
    }

    public boolean isComboPreparationBreaker() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isComboPreparationBreaker);
    }

    public boolean isPartOfHintPrefix() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isPartOfHintPrefix);
    }

    public boolean isHintEnd() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isHintEnd);
    }

    public boolean isUnswallowedHintEnd() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isUnswallowedHintEnd);
    }

    public boolean isHint() {
        return processingByCombo.values()
                                .stream()
                                .anyMatch(PressKeyEventProcessing::isHint);
    }

    /**
     * True when the key is eaten only because it was not a command. An eating combo
     * that also matches must stay on the regurgitation list.
     */
    public boolean isOnlySwallowedNonFunctionKey() {
        boolean swallowed = false;
        for (PressKeyEventProcessing processing : processingByCombo.values()) {
            if (processing.isSwallowedNonFunctionKey())
                swallowed = true;
            else if (processing.mustBeEaten())
                return false;
        }
        return swallowed;
    }

    public PressKeyEventProcessingSet withSwallowedNonFunctionKey() {
        Map<Combo, PressKeyEventProcessing> processing =
                new HashMap<>(processingByCombo);
        processing.put(swallowedNonFunctionKeyCombo,
                PressKeyEventProcessing.swallowedNonFunctionKey());
        return new PressKeyEventProcessingSet(processing, new HashMap<>(matchByCombo));
    }

}
