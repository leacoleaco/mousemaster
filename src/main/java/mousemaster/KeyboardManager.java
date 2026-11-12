package mousemaster;

import mousemaster.ComboWatcher.ComboWatcherUpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KeyboardManager {

    private static final Logger logger = LoggerFactory.getLogger(KeyboardManager.class);

    private final ComboWatcher comboWatcher;
    private final KeyRegurgitator keyRegurgitator;
    private final Clock clock;
    private static final Duration maxOsAutoRepeatDelay = Duration.ofSeconds(1);
    private KeyEvent lastOsAutoRepeat;
    private static final Duration lastKeyEventsRetainDuration = Duration.ofSeconds(15);
    private static final int lastKeyEventsMinRetainCount = 128;
    private final List<KeyEventAndEaten> lastKeyEvents = new ArrayList<>();
    /**
     * LinkedHashMap preserves insertion (press) order for correct regurgitation order
     * (e.g. shift before a, not a before shift).
     */
    private final Map<Key, PressKeyEventProcessingSet> currentlyPressedKeys = new LinkedHashMap<>();
    /**
     * All eaten keys in press order.
     * If the combo later fails, these keys are regurgitated in press order.
     * If a combo completes, only eaten keys whose combos are all completed are removed.
     */
    private final Map<Key, Eat> eatenKeys = new LinkedHashMap<>();
    private MacroPlayer macroPlayer;
    /**
     * Set when a key press is unhandled in the current mode. This persists across
     * key releases so that briefly-pressed unhandled keys are not missed by the
     * poll-based check in ModeController.update().
     */
    private boolean unhandledKeyJustPressedInCurrentMode;
    /**
     * Show or hide the mode's unhandled-key note, in the order the keys arrived.
     * True shows it, false hides it.
     */
    private final Deque<UnhandledKeyTip> unhandledKeyTips = new ArrayDeque<>();

    private record UnhandledKeyTip(String animationName, boolean show) {
    }

    private record Eat(boolean released, PressKeyEventProcessingSet processingSet) {
    }

    public KeyboardManager(ComboWatcher comboWatcher, HintManager hintManager,
                           KeyRegurgitator keyRegurgitator, Clock clock) {
        this.comboWatcher = comboWatcher;
        this.keyRegurgitator = keyRegurgitator;
        this.clock = clock;
    }

    public void setMacroPlayer(MacroPlayer macroPlayer) {
        this.macroPlayer = macroPlayer;
    }

    public void update(double delta) {
        if (delta > 10) {
            logger.info("Tick took " + delta + "s, skipping update, clearing currentlyPressedKeys, and breaking combos");
            reset();
        }
        else {
            updateCombos(delta);
            runEntryCombos();
        }
    }

    /** The entry combos of a mode switched to run at once, before the indicator is drawn. */
    private void runEntryCombos() {
        if (comboWatcher.entryCombosPending())
            updateCombos(0);
    }

    private void updateCombos(double delta) {
        releaseKeyTheOsStoppedRepeating();
        ComboWatcherUpdateResult watcherUpdateResult = comboWatcher.update(delta);
        if (!watcherUpdateResult.completedCombos().isEmpty()) {
            markOtherKeysOfTheseCombosAsCompleted(
                    watcherUpdateResult.completedCombos(),
                    watcherUpdateResult.hasComboPreparationBreaker());
            clearFullyCompletedEatenKeys();
        }
        if (!watcherUpdateResult.expiredCombos().isEmpty()) {
            List<Regurgitate> deadComboRegurgitates =
                    handleDeadEatingCombos(watcherUpdateResult.expiredCombos(), null);
            for (Regurgitate regurgitate : deadComboRegurgitates) {
                keyRegurgitator.regurgitate(regurgitate, !regurgitate.alsoRelease());
            }
        }
        if (watcherUpdateResult.preparationIsNotPrefixAnymore()) {
            regurgitatePressedKeys();
        }
        if (watcherUpdateResult.hasComboPreparationBreaker()) {
            regurgitatePressedKeys();
            if (!watcherUpdateResult.comboPreparationAlreadyBroken()) {
                if (watcherUpdateResult.comboPreparationBreakerKey() != null)
                    comboWatcher.reset(watcherUpdateResult.comboPreparationBreakerKey());
                else
                    comboWatcher.breakComboPreparation();
            }
        }
    }

    public boolean pressingKeys() {
        return !currentlyPressedKeys.isEmpty();
    }

    public void reset() {
        reset(true);
    }

    public void reset(boolean regurgitate) {
        if (regurgitate)
            regurgitatePressedKeys();
        currentlyPressedKeys.clear();
        eatenKeys.clear();
        lastOsAutoRepeat = null;
        unhandledKeyJustPressedInCurrentMode = false;
        unhandledKeyTips.clear();
        comboWatcher.reset();
        macroPlayer.reset();
    }

    private void releaseKeyTheOsStoppedRepeating() {
        if (lastOsAutoRepeat == null ||
            !lastOsAutoRepeat.time().plus(maxOsAutoRepeatDelay).isBefore(clock.now()))
            return;
        logger.info("Releasing a key the OS stopped repeating");
        EatAndRegurgitates eatAndRegurgitates = keyEvent(
                new KeyEvent.ReleaseKeyEvent(clock.now(), lastOsAutoRepeat.key()));
        for (Regurgitate regurgitate : eatAndRegurgitates.regurgitates())
            keyRegurgitator.regurgitate(regurgitate, !regurgitate.alsoRelease());
    }

    public void regurgitatePressedKeys() {
        for (Regurgitate regurgitate : buildRegurgitates(null, null, Set.of())) {
            keyRegurgitator.regurgitate(regurgitate, !regurgitate.alsoRelease());
        }
    }

    public record Regurgitate(Key key, boolean alsoRelease) {

    }

    public record EatAndRegurgitates(boolean mustBeEaten, List<Regurgitate> regurgitates) {

    }

    private static final EatAndRegurgitates
            mustNotBeEatenOnly = new EatAndRegurgitates(false, List.of());
    private static final EatAndRegurgitates
            mustBeEatenOnly = new EatAndRegurgitates(true, List.of());
    /**
     * A Ctrl chord that belongs to the app, not to a combo. Handled so it does not
     * count as an unhandled key, and not eaten so the operating system sees it.
     * The release is not fed back into combo matching.
     */
    private static final PressKeyEventProcessingSet ctrlShortcutPassthrough =
            new PressKeyEventProcessingSet(
                    Map.of(PressKeyEventProcessingSet.dummyCombo,
                            PressKeyEventProcessing.comboPreparationBreaker(false)),
                    Map.of());

    public EatAndRegurgitates keyEvent(KeyEvent keyEvent) {
        boolean osAutoRepeat = keyEvent.isPress() &&
                               currentlyPressedKeys.containsKey(keyEvent.key());
        lastOsAutoRepeat = osAutoRepeat ? keyEvent : null;
        EatAndRegurgitates eatAndRegurgitates = singleKeyEvent(keyEvent);
        if (!osAutoRepeat) {
            lastKeyEvents.add(
                    new KeyEventAndEaten(keyEvent, eatAndRegurgitates.mustBeEaten()));
            Instant cutoff = keyEvent.time().minus(lastKeyEventsRetainDuration);
            while (lastKeyEvents.size() > lastKeyEventsMinRetainCount &&
                   lastKeyEvents.getFirst().event().time().isBefore(cutoff))
                lastKeyEvents.removeFirst();
        }
        runEntryCombos();
        return eatAndRegurgitates;
    }

    public record KeyEventAndEaten(KeyEvent event, boolean eaten) {
        @Override
        public String toString() {
            return prefix() + event.key();
        }

        private String prefix() {
            if (event.isRelease())
                return "-";
            return eaten ? "+" : "#";
        }
    }

    public List<KeyEventAndEaten> lastKeyEvents() {
        return lastKeyEvents;
    }

    /**
     * Any key pressed while either Ctrl is already held is a system shortcut
     * (copy, paste, select all, save, and so on) and must not be eaten or run a
     * combo. Ctrl itself still reaches the combo watcher so a double-tap can
     * activate mouse mode.
     */
    private boolean shouldPassCtrlShortcutToOs(Key key) {
        if (key.equals(Key.leftctrl) || key.equals(Key.rightctrl))
            return false;
        return currentlyPressedKeys.containsKey(Key.leftctrl)
               || currentlyPressedKeys.containsKey(Key.rightctrl);
    }

    private EatAndRegurgitates singleKeyEvent(KeyEvent keyEvent) {
        macroPlayer.newKeyEvent();
        Key key = keyEvent.key();
        PressKeyEventProcessingSet processingSet = currentlyPressedKeys.get(key);
        if (keyEvent.isPress() && processingSet == null && shouldPassCtrlShortcutToOs(key)) {
            // Another key cancels an in-progress double-tap, but Ctrl stays held.
            comboWatcher.breakComboPreparation();
            currentlyPressedKeys.put(key, ctrlShortcutPassthrough);
            macroPlayer.clearEarlyRelease(key);
            macroPlayer.clearDeferredRelease(key);
            boolean mustBeEaten = macroPlayer.isKeyPressedByMacro(key);
            if (!mustBeEaten)
                macroPlayer.keyPressedNotEaten(key);
            // Ctrl+key is a system shortcut. Drop the "you cannot type" note.
            queueUnhandledKeyTip(false);
            return eatAndRegurgitates(mustBeEaten, List.of());
        }
        if (keyEvent.isPress()) {
            List<Regurgitate> regurgitates = List.of();
            if (processingSet == null) {
                Mode modeBefore = comboWatcher.getMutatedMode();
                boolean commandKey = modeBefore != null &&
                                     comboWatcher.isCurrentModeCommandKey(key);
                // A held unbound key blocks combos. A key this mode already binds
                // still has to reach them, or exit and hint are swallowed instead.
                if (!pressingUnhandledKey() || commandKey) {
                    PressKeyEventProcessingSet comboWatcherProcessingSet =
                            comboWatcher.keyEvent(keyEvent);
                    Map<Combo, PressKeyEventProcessing> processingByCombo =
                            new HashMap<>(comboWatcherProcessingSet.processingByCombo());
                    processingSet = new PressKeyEventProcessingSet(processingByCombo,
                            new HashMap<>(comboWatcherProcessingSet.matchByCombo()));
                }
                else {
                    processingSet = new PressKeyEventProcessingSet(Map.of(), Map.of());
                }
                processingSet = noteNonFunctionKey(key, processingSet, modeBefore, commandKey);
                if (!processingSet.isPartOfComboSequence()) {
                    regurgitates = buildRegurgitates(null, null, Set.of());
                }
                else if (processingSet.isPartOfComboSequence()) {
                    // Regurgitate keys that are not in any of the combos
                    // associated to the key that triggered the current event.
                    Set<Combo> currentCombos = processingSet.processingByCombo().keySet();
                    regurgitates = buildRegurgitates(null, null, currentCombos);
                }
                currentlyPressedKeys.put(key, processingSet);
                if (pressingUnhandledKeyInCurrentMode())
                    unhandledKeyJustPressedInCurrentMode = true;
                macroPlayer.clearEarlyRelease(key);
                macroPlayer.clearDeferredRelease(key);
                if (processingSet.mustBeEaten() &&
                    !processingSet.isOnlySwallowedNonFunctionKey()) {
                    eatenKeys.put(key, new Eat(false, processingSet));
                }
                if (processingSet.isPartOfCompletedComboSequence()) {
                    markOtherKeysOfTheseCombosAsCompleted(
                            processingSet.partOfCompletedComboSequenceCombosWithMatches(), false);
                    clearFullyCompletedEatenKeys();
                }
                Set<Combo> deadCombos = computeDeadCombos(processingSet);
                List<Regurgitate> deadComboRegurgitates = handleDeadEatingCombos(deadCombos, null);
                if (!deadComboRegurgitates.isEmpty()) {
                    regurgitates = new ArrayList<>(regurgitates);
                    regurgitates.addAll(deadComboRegurgitates);
                }
                if (processingSet.isComboPreparationBreaker()) {
                    comboWatcher.reset(key);
                }
            }
            boolean mustBeEaten = processingSet.mustBeEaten() ||
                                  macroPlayer.isKeyPressedByMacro(key);
            if (!mustBeEaten)
                macroPlayer.keyPressedNotEaten(key);
            return eatAndRegurgitates(mustBeEaten, regurgitates);
        }
        else { // Key release.
            if (processingSet != null) {
                macroPlayer.recordEarlyRelease(key);
                if (processingSet.handled() ||
                    processingSet.isPartOfUnpressedComboPreconditionOnly()) {
                    List<Regurgitate> regurgitates = List.of();
                    // Avoid passing release event to comboWatcher if the key was a combo preparation breaker.
                    // We could add a property for choosing whether we want to ignore
                    // the release of the combo preparation breaker key. But for now,
                    // we always ignore it.
                    if (!processingSet.isComboPreparationBreaker()) {
                        if (processingSet.isPartOfCombo() ||
                            processingSet.isIgnoredByLeadingWait()) {
                            PressKeyEventProcessingSet releaseProcessingSet =
                                    comboWatcher.keyEvent(keyEvent);
                            if (!releaseProcessingSet.handled()) {
                                // No combo recognizes the release: the combo
                                // system lost track of this key, regurgitate.
                                regurgitates = buildRegurgitates(null, key, Set.of());
                            }
                            else {
                                if (releaseProcessingSet.isPartOfCompletedComboSequence()) {
                                    for (Map.Entry<Combo, PressKeyEventProcessing> entry : releaseProcessingSet.processingByCombo()
                                                                                                               .entrySet()) {
                                        // Mark current combo as completed (so it is not regurgitated, e.g. +rightalt -rightalt).
                                        processingSet.processingByCombo()
                                                     .compute(entry.getKey(),
                                                             (combo, existingProcessing) -> {
                                                                 return existingProcessing ==
                                                                        null ?
                                                                         entry.getValue() :
                                                                         PressKeyEventProcessing.partOfComboSequence(
                                                                                 existingProcessing.mustBeEaten(),
                                                                                 entry.getValue()
                                                                                      .isPartOfCompletedComboSequence(),
                                                                                 existingProcessing.isComboPreparationBreaker()
                                                                                 || entry.getValue().isComboPreparationBreaker());
                                                             });
                                    }
                                    markOtherKeysOfTheseCombosAsCompleted(
                                            releaseProcessingSet.partOfCompletedComboSequenceCombosWithMatches(),
                                            false);
                                    clearFullyCompletedEatenKeys();
                                }
                                Set<Combo> deadCombos = computeDeadCombos(releaseProcessingSet);
                                List<Regurgitate> deadComboRegurgitates = handleDeadEatingCombos(deadCombos, key);
                                if (!deadComboRegurgitates.isEmpty())
                                    regurgitates = deadComboRegurgitates;
                            }
                        }
                        if (processingSet.isComboPreparationBreaker()) {
                            comboWatcher.reset(key);
                        }
                    }
                    PressKeyEventProcessingSet pressedProcessingSet = currentlyPressedKeys.remove(key);
//                    logger.info("Removed key " + key, new Exception());
                    boolean macroHoldsKey = macroPlayer.isKeyPressedByMacro(key);
                    boolean mustBeEaten;
                    if (macroHoldsKey && macroPlayer.macroInProgress()) {
                        // Macro is still executing — eat the release and defer it
                        // so the key stays held for subsequent macro parallels.
                        mustBeEaten = pressedProcessingSet.mustBeEaten();
                        if (mustBeEaten)
                            macroPlayer.deferUserRelease(key);
                    }
                    else {
                        mustBeEaten =
                                !macroHoldsKey &&
                                pressedProcessingSet.mustBeEaten();
                    }
                    // Track released eaten keys that are part of a partial combo
                    // for future regurgitation if the combo fails.
                    if (mustBeEaten &&
                        !pressedProcessingSet.isPartOfCompletedComboSequenceAndMustBeEaten() &&
                        !pressedProcessingSet.isOnlySwallowedNonFunctionKey() &&
                        regurgitates.stream().noneMatch(r -> r.key().equals(key))) {
                        eatenKeys.put(key, new Eat(true, pressedProcessingSet));
                    }
                    else {
                        // Even if mustBeEaten is false (e.g. macro holds the key),
                        // mark the existing eat entry as released so that future
                        // regurgitation sends press+release instead of press-only.
                        Eat existingEat = eatenKeys.get(key);
                        if (existingEat != null && !existingEat.released()) {
                            eatenKeys.put(key, new Eat(true, existingEat.processingSet()));
                        }
                    }
                    if (!mustBeEaten)
                        macroPlayer.keyReleasedNotEaten(key);
                    return eatAndRegurgitates(mustBeEaten, regurgitates);
                }
                else {
                    currentlyPressedKeys.remove(key);
//                    logger.info("Removed key " + key, new Exception());
                    macroPlayer.keyReleasedNotEaten(key);
                    return eatAndRegurgitates(false, buildRegurgitates(null, null, Set.of()));
                }
            }
            else {
                macroPlayer.keyReleasedNotEaten(key);
                return eatAndRegurgitates(false, List.of());
            }
        }
    }

    private boolean markOtherKeysOfTheseCombosAsCompleted(List<ComboAndMatch> completedCombos,
                                                          boolean forceIsComboPreparationBreaker) {
        boolean completedCombosHavePressedKeys = false;
        for (ComboAndMatch comboAndMatch : completedCombos) {
            Combo combo = comboAndMatch.combo();
            ComboSequenceMatch match = comboAndMatch.match();
            // Collect all keys that were pressed at any point during the combo
            // (not just keys still pressed at the end), so that keys pressed
            // and released during the combo (e.g. +space +n -space -n) are also
            // marked as completed and not regurgitated.
            Set<Key> pressedKeysInCompletedCombo = new HashSet<>();
            for (ResolvedKeyComboMove move : match.matchedKeyMoves()) {
                if (move.isPress())
                    pressedKeysInCompletedCombo.add(move.key());
            }
            // Absorbed pressed keys (e.g. +d in {+a -a +b -b +{d}}) are also
            // part of the completed combo and should not be regurgitated.
            pressedKeysInCompletedCombo.addAll(match.absorbedPressedKeys());
            completedCombosHavePressedKeys |= !pressedKeysInCompletedCombo.isEmpty();

            for (Key key : pressedKeysInCompletedCombo) {
                PressKeyEventProcessingSet processingSet = currentlyPressedKeys.get(key);
                if (processingSet == null) {
                    Eat eat = eatenKeys.get(key);
                    if (eat != null)
                        processingSet = eat.processingSet();
                }
                if (processingSet == null)
                    continue;
                Map<Combo, PressKeyEventProcessing> processingByCombo =
                        processingSet.processingByCombo();
                PressKeyEventProcessing processing = processingByCombo.get(combo);
                // Can be null with (when going from temp-screen-snap-mode.to.idle-mode,
                // , is pressed and idle-mode's alttab combo is not registered for +rightalt):
                // idle-mode.remapping.alttab=+rightalt-0 +, | _{rightalt} +, -> +leftalt +tab -tab -leftalt
                // temp-screen-snap-mode.remapping.alttab=_{rightalt} +, -> +leftalt +tab -tab -leftalt
                // temp-screen-snap-mode.to.idle-mode=_{rightalt} +,
                // Better solution could be to create the missing processing for that key.
                if (processing != null)
                    processingByCombo.put(combo,
                            PressKeyEventProcessing.partOfComboSequence(
                                    processing.mustBeEaten(), true,
                                    processing.isComboPreparationBreaker() || forceIsComboPreparationBreaker));
            }
        }
        return completedCombosHavePressedKeys;
    }

    /**
     * Builds a regurgitation list in press order from eatenKeys.
     * @param filterKey if non-null, only process this specific key
     * @param releasingKey if non-null, this key is being released (gets release=true)
     * @param retainCombos skip keys associated with these combos (pass empty set for all)
     */
    private List<Regurgitate> buildRegurgitates(Key filterKey, Key releasingKey,
                                                Set<Combo> retainCombos) {
        if (eatenKeys.isEmpty())
            return List.of();
        List<Regurgitate> regurgitates = new ArrayList<>();
        Set<Key> keysToRemove = new HashSet<>();
        for (Map.Entry<Key, Eat> entry : eatenKeys.entrySet()) {
            Key eatenKey = entry.getKey();
            if (filterKey != null && !eatenKey.equals(filterKey))
                continue;
            Eat eat = entry.getValue();
            PressKeyEventProcessingSet processingSet = eat.processingSet();
            boolean alsoRelease;
            if (eat.released()) {
                alsoRelease = true;
                if (!retainCombos.isEmpty() &&
                    processingSet.processingByCombo().entrySet().stream()
                       .anyMatch(e -> retainCombos.contains(e.getKey()) &&
                                      e.getValue().mustBeEaten()))
                    continue;
                keysToRemove.add(eatenKey);
            }
            else {
                if (!retainCombos.isEmpty() &&
                    processingSet.processingByCombo().entrySet().stream()
                       .anyMatch(e -> retainCombos.contains(e.getKey()) &&
                                      e.getValue().mustBeEaten()))
                    continue;
                alsoRelease = releasingKey != null && releasingKey.equals(eatenKey);
            }
            addRegurgitate(processingSet, regurgitates, eatenKey, alsoRelease);
        }
        keysToRemove.forEach(eatenKeys::remove);
        return regurgitates;
    }

    private void addRegurgitate(PressKeyEventProcessingSet processingSet,
                                List<Regurgitate> regurgitates, Key eatenKey,
                                boolean alsoRelease) {
        // One of the combo is mustBeEaten, and there is no mustBeEaten combo that is completed.
        if (processingSet.mustBeEaten() && !processingSet.isPartOfCompletedComboSequenceAndMustBeEaten()) {
            regurgitates.add(new Regurgitate(eatenKey, alsoRelease));
            // Change the key's processing to must not be eaten
            // so that it cannot be regurgitated a second time.
            for (Map.Entry<Combo, PressKeyEventProcessing> entry : Set.copyOf(
                    processingSet.processingByCombo().entrySet())) {
                Combo combo = entry.getKey();
                PressKeyEventProcessing processing = entry.getValue();
                if (processing.isPartOfComboSequence())
                    processingSet.processingByCombo()
                                 .put(combo,
                                         PressKeyEventProcessing.partOfComboSequence(
                                                 false,
                                                 processing.isPartOfCompletedComboSequence(),
                                                 false));
            }
        }
    }

    private static EatAndRegurgitates eatAndRegurgitates(boolean mustBeEaten,
                                                         List<Regurgitate> regurgitates) {
        if (regurgitates.isEmpty())
            return mustBeEaten ? mustBeEatenOnly : mustNotBeEatenOnly;
        return new EatAndRegurgitates(mustBeEaten, regurgitates);
    }

    public boolean pressingUnhandledKey() {
        for (PressKeyEventProcessingSet processingSet : currentlyPressedKeys.values()) {
            if (!processingSet.handled())
                return true;
        }
        return false;
    }

    /**
     * Used for mode-after-unhandled-key-press and active indicator, where we care about
     * the current mode's perspective.
     */
    public boolean pressingUnhandledKeyInCurrentMode() {
        for (Map.Entry<Key, PressKeyEventProcessingSet> entry : currentlyPressedKeys.entrySet()) {
            PressKeyEventProcessingSet processingSet = entry.getValue();
            if (!processingSet.handled())
                return true;
            if (processingSet.isPartOfPressedComboPreconditionOnly() &&
                !comboWatcher.isCurrentModePressedPreconditionKey(entry.getKey()))
                return true;
        }
        return false;
    }

    public boolean pollUnhandledKeyJustPressedInCurrentMode() {
        try {
            return unhandledKeyJustPressedInCurrentMode;
        }
        finally {
            unhandledKeyJustPressedInCurrentMode = false;
        }
    }

    /** Plays the notes queued by key presses, in order, so a command key hides a note
     *  that an earlier non-command key in the same tick had shown. */
    public void drainUnhandledKeyPressTips(AnimationPlayer animationPlayer) {
        while (!unhandledKeyTips.isEmpty()) {
            UnhandledKeyTip tip = unhandledKeyTips.removeFirst();
            if (tip.show())
                animationPlayer.start(tip.animationName());
            else
                animationPlayer.stop(tip.animationName());
        }
    }

    private static boolean isCtrlKey(Key key) {
        return key.equals(Key.leftctrl) || key.equals(Key.rightctrl);
    }

    /**
     * An English letter or symbol that this mode does not bind is eaten, and the mode's
     * note is shown. A bound key — a command, a modifier, or a hint letter — hides that
     * note and is not eaten here, including when its command switches mode during the
     * press. Digits, Enter, arrows, and every other key are left for the focused program.
     * Ctrl itself is never eaten: a chord must still reach the focused program.
     * A letter pressed while Alt or Win is held is a chord too, such as Win+A.
     */
    private PressKeyEventProcessingSet noteNonFunctionKey(Key key,
                                                          PressKeyEventProcessingSet processingSet,
                                                          Mode mode,
                                                          boolean commandKey) {
        if (mode == null)
            return processingSet;
        String animation = mode.unhandledKeyPressAnimation();
        if (isCtrlKey(key) || commandKey || isRealComboKey(processingSet) ||
            pressingAltOrWin()) {
            queueUnhandledKeyTip(animation, false);
            return processingSet;
        }
        if (!mode.eatUnhandledKeyPress() || processingSet.mustBeEaten() ||
            !isEnglishLetterOrSymbol(key))
            return processingSet;
        queueUnhandledKeyTip(animation, true);
        logger.debug("Swallowing non-function key " + key);
        return processingSet.withSwallowedNonFunctionKey();
    }

    private boolean pressingAltOrWin() {
        return currentlyPressedKeys.containsKey(Key.leftalt) ||
               currentlyPressedKeys.containsKey(Key.rightalt) ||
               currentlyPressedKeys.containsKey(Key.leftwin) ||
               currentlyPressedKeys.containsKey(Key.rightwin);
    }

    /** Letters a–z and punctuation. Digits stay available, including a digit that is a command. */
    private static boolean isEnglishLetterOrSymbol(Key key) {
        String character = key.character();
        if (character == null || character.length() != 1)
            return false;
        char c = character.charAt(0);
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')
            return true;
        return !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
    }

    private void queueUnhandledKeyTip(boolean show) {
        Mode mode = comboWatcher.getMutatedMode();
        queueUnhandledKeyTip(mode == null ? null : mode.unhandledKeyPressAnimation(), show);
    }

    private void queueUnhandledKeyTip(String animation, boolean show) {
        if (animation != null)
            unhandledKeyTips.add(new UnhandledKeyTip(animation, show));
    }

    /** True when some combo matched this press as one of its own keys, not merely
     *  as an event a wait ignored. */
    private static boolean isRealComboKey(PressKeyEventProcessingSet processingSet) {
        for (Map.Entry<Combo, PressKeyEventProcessing> entry : processingSet.processingByCombo()
                                                                            .entrySet()) {
            if (!entry.getValue().isPartOfComboSequence())
                continue;
            ComboSequenceMatch match = processingSet.matchByCombo().get(entry.getKey());
            if (match == null || !match.lastEventAbsorbedByWait())
                return true;
        }
        return false;
    }

    /**
     * Returns true if the key is currently pressed by the user and the press was not eaten
     * (i.e. the rest of the OS apps saw the press).
     */
    public boolean isPressedKeyNotEaten(Key key) {
        PressKeyEventProcessingSet processingSet = currentlyPressedKeys.get(key);
        return processingSet != null && !processingSet.mustBeEaten();
    }

    /**
     * Find combos that are eating in some eaten key's processing set
     * but no longer viable in the latest processing set.
     */
    private Set<Combo> computeDeadCombos(PressKeyEventProcessingSet latestProcessingSet) {
        Set<Combo> viableCombos = new HashSet<>();
        for (Map.Entry<Combo, PressKeyEventProcessing> entry :
                latestProcessingSet.processingByCombo().entrySet()) {
            if (entry.getValue().isPartOfComboSequence())
                viableCombos.add(entry.getKey());
        }
        Set<Combo> deadCombos = new HashSet<>();
        for (Eat eat : eatenKeys.values()) {
            for (Map.Entry<Combo, PressKeyEventProcessing> entry :
                    eat.processingSet().processingByCombo().entrySet()) {
                if (entry.getValue().isPartOfComboSequenceMustBeEaten() &&
                    !viableCombos.contains(entry.getKey())) {
                    deadCombos.add(entry.getKey());
                }
            }
        }
        return deadCombos;
    }

    /**
     * For each eaten key, check if any eating combos are in deadCombos.
     * If ALL eating combos are dead, regurgitate the key.
     * If some are dead but others remain, mark dead ones as non-eating.
     */
    private List<Regurgitate> handleDeadEatingCombos(Set<Combo> deadCombos,
                                                      Key releasingKey) {
        if (eatenKeys.isEmpty() || deadCombos.isEmpty()) return List.of();
        List<Regurgitate> regurgitates = new ArrayList<>();
        for (Map.Entry<Key, Eat> entry : eatenKeys.entrySet()) {
            Key eatenKey = entry.getKey();
            Eat eat = entry.getValue();
            PressKeyEventProcessingSet ps = eat.processingSet();
            // Check if any viable (non-dead) eating combo remains.
            boolean hasViableEatingCombo = ps.processingByCombo().entrySet()
                    .stream()
                    .anyMatch(e -> e.getValue().isPartOfComboSequenceMustBeEaten()
                                && !deadCombos.contains(e.getKey()));
            if (!hasViableEatingCombo && ps.mustBeEaten()) {
                // All eating combos are dead. Regurgitate.
                boolean alsoRelease = eat.released() ||
                        (releasingKey != null && releasingKey.equals(eatenKey));
                addRegurgitate(ps, regurgitates, eatenKey, alsoRelease);
            }
            else {
                // Mark dead combos as non-eating for clearFullyCompletedEatenKeys.
                for (Map.Entry<Combo, PressKeyEventProcessing> ce :
                        ps.processingByCombo().entrySet()) {
                    if (ce.getValue().isPartOfComboSequenceMustBeEaten() &&
                        deadCombos.contains(ce.getKey())) {
                        ce.setValue(PressKeyEventProcessing.partOfComboSequence(
                                false,
                                ce.getValue().isPartOfCompletedComboSequence(),
                                ce.getValue().isComboPreparationBreaker()));
                    }
                }
            }
        }
        return regurgitates;
    }

    /**
     * Remove eaten keys only when all their eating combos are completed.
     * Keys with any in-progress eating combo remain for potential regurgitation.
     * Non-eating combos do not prevent removal.
     */
    private void clearFullyCompletedEatenKeys() {
        eatenKeys.entrySet().removeIf(entry ->
                entry.getValue().processingSet().processingByCombo().values().stream()
                     .allMatch(p -> !p.isPartOfComboSequence() ||
                                    p.isPartOfCompletedComboSequence() ||
                                    !p.mustBeEaten()));
    }

}
