package mousemaster;

import mousemaster.platform.UiAutomation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;

import static mousemaster.Command.*;

public class CommandRunner {

    private ModeController modeController;
    private final MouseManager mouseManager;
    private final HintManager hintManager;
    private final Platform platform;
    private final CopyTextPlayer copyTextPlayer;
    private MacroPlayer macroPlayer;
    private AnimationPlayer animationPlayer;
    private WindowSwitcher windowSwitcher;
    private AppMenus appMenus;
    private final UiAimPlayer uiAimPlayer;

    public CommandRunner(MouseManager mouseManager, HintManager hintManager,
                         Platform platform) {
        this.mouseManager = mouseManager;
        this.hintManager = hintManager;
        this.platform = platform;
        this.uiAimPlayer = new UiAimPlayer(mouseManager, platform,
                () -> macroPlayer != null && macroPlayer.macroInProgress());
        this.copyTextPlayer = new CopyTextPlayer(new CopyTextPlayer.Output() {
            @Override
            public void releaseLeft() {
                mouseManager.releaseLeft();
            }

            @Override
            public void haltMovement() {
                mouseManager.haltMovement();
            }

            @Override
            public void pressLeft() {
                mouseManager.pressLeft();
            }

            @Override
            public void selectAll() {
                sendChord(Key.ofCharacter("a"));
            }

            @Override
            public Future<Boolean> startCopy(CopyText.Kind kind) {
                UiAutomation.TextExtent extent = switch (kind) {
                    case WORD -> UiAutomation.TextExtent.WORD;
                    case LINE -> UiAutomation.TextExtent.LINE;
                    case ALL -> UiAutomation.TextExtent.ALL;
                };
                return platform.uiAutomation().startCopyText(extent);
            }

            @Override
            public void copy() {
                sendChord(Key.ofCharacter("c"));
                showCopied();
            }

            @Override
            public void showCopied() {
                if (animationPlayer != null)
                    animationPlayer.start("copiedhintanimation");
            }
        });
    }

    public void update(double delta) {
        copyTextPlayer.update(delta);
        uiAimPlayer.update(delta);
    }

    public void setModeController(ModeController modeController) {
        this.modeController = modeController;
    }

    public void setMacroPlayer(MacroPlayer macroPlayer) {
        this.macroPlayer = macroPlayer;
    }

    public void setAnimationPlayer(AnimationPlayer animationPlayer) {
        this.animationPlayer = animationPlayer;
    }

    public void setWindowSwitcher(WindowSwitcher windowSwitcher) {
        this.windowSwitcher = windowSwitcher;
    }

    public void setAppMenus(AppMenus appMenus) {
        this.appMenus = appMenus;
    }

    public boolean runningAtomicCommand() {
        return mouseManager.jumping();
    }

    public void run(Command command, Key eventKey) {
        switch (command) {
            // @formatter:off
            case SwitchMode switchMode -> modeController.switchMode(switchMode.modeName());
            case ShowCursorSpotlight showCursorSpotlight -> platform.showCursorSpotlight();

            case StartMoveUp startMoveUp -> mouseManager.startMoveUp();
            case StartMoveDown startMoveDown -> mouseManager.startMoveDown();
            case StartMoveLeft startMoveLeft -> mouseManager.startMoveLeft();
            case StartMoveRight startMoveRight -> mouseManager.startMoveRight();

            case StopMoveUp stopMoveUp -> mouseManager.stopMoveUp();
            case StopMoveDown stopMoveDown -> mouseManager.stopMoveDown();
            case StopMoveLeft stopMoveLeft -> mouseManager.stopMoveLeft();
            case StopMoveRight stopMoveRight -> mouseManager.stopMoveRight();

            case StartMoveTargetUp startMoveTargetUp -> hintManager.startMoveTargetUp();
            case StartMoveTargetDown startMoveTargetDown -> hintManager.startMoveTargetDown();
            case StartMoveTargetLeft startMoveTargetLeft -> hintManager.startMoveTargetLeft();
            case StartMoveTargetRight startMoveTargetRight -> hintManager.startMoveTargetRight();

            case StopMoveTargetUp stopMoveTargetUp -> hintManager.stopMoveTargetUp();
            case StopMoveTargetDown stopMoveTargetDown -> hintManager.stopMoveTargetDown();
            case StopMoveTargetLeft stopMoveTargetLeft -> hintManager.stopMoveTargetLeft();
            case StopMoveTargetRight stopMoveTargetRight -> hintManager.stopMoveTargetRight();

            case PressLeft pressLeft -> mouseManager.pressLeft();
            case PressMiddle pressMiddle -> mouseManager.pressMiddle();
            case PressRight pressRight -> mouseManager.pressRight();

            case ReleaseLeft releaseLeft -> mouseManager.releaseLeft();
            case ReleaseMiddle releaseMiddle -> mouseManager.releaseMiddle();
            case ReleaseRight releaseRight -> mouseManager.releaseRight();

            case ToggleLeft toggleLeft -> mouseManager.toggleLeft();
            case ToggleMiddle toggleMiddle -> mouseManager.toggleMiddle();
            case ToggleRight toggleRight -> mouseManager.toggleRight();

            case StartWheelUp startWheelUp -> mouseManager.startWheelUp();
            case StartWheelDown startWheelDown -> mouseManager.startWheelDown();
            case StartWheelLeft startWheelLeft -> mouseManager.startWheelLeft();
            case StartWheelRight startWheelRight -> mouseManager.startWheelRight();

            case StopWheelUp stopWheelUp -> mouseManager.stopWheelUp();
            case StopWheelDown stopWheelDown -> mouseManager.stopWheelDown();
            case StopWheelLeft stopWheelLeft -> mouseManager.stopWheelLeft();
            case StopWheelRight stopWheelRight -> mouseManager.stopWheelRight();

            case ShrinkOverlayUp shrinkOverlayUp -> hintManager.shrinkOverlayUp();
            case ShrinkOverlayDown shrinkOverlayDown -> hintManager.shrinkOverlayDown();
            case ShrinkOverlayLeft shrinkOverlayLeft -> hintManager.shrinkOverlayLeft();
            case ShrinkOverlayRight shrinkOverlayRight -> hintManager.shrinkOverlayRight();

            case MoveOverlayToAreaAbove moveOverlayToAreaAbove -> hintManager.moveOverlayToAreaAbove();
            case MoveOverlayToAreaBelow moveOverlayToAreaBelow -> hintManager.moveOverlayToAreaBelow();
            case MoveOverlayToAreaLeft moveOverlayToAreaLeft -> hintManager.moveOverlayToAreaLeft();
            case MoveOverlayToAreaRight moveOverlayToAreaRight -> hintManager.moveOverlayToAreaRight();

            case MoveToTarget moveToTarget -> hintManager.moveToTarget();
            case MoveToLastSelectedHint moveToLastSelectedHint -> hintManager.moveToLastSelectedHint();
            case MoveToOverlayCenter moveToOverlayCenter -> hintManager.moveToOverlayCenter();
            case MoveToCellCenter moveToCellCenter -> hintManager.moveToCellCenter();
            case MoveToCellTop moveToCellTop -> hintManager.moveToCellTop();
            case MoveToCellBottom moveToCellBottom -> hintManager.moveToCellBottom();
            case MoveToCellLeft moveToCellLeft -> hintManager.moveToCellLeft();
            case MoveToCellRight moveToCellRight -> hintManager.moveToCellRight();

            case MoveTargetToMouse moveTargetToMouse -> hintManager.moveTargetToMouse();
            case MoveTargetToLastSelectedHint moveTargetToLastSelectedHint -> hintManager.moveTargetToLastSelectedHint();
            case MoveTargetToOverlayCenter moveTargetToOverlayCenter -> hintManager.moveTargetToOverlayCenter();
            case MoveTargetToCellCenter moveTargetToCellCenter -> hintManager.moveTargetToCellCenter();
            case MoveTargetToCellTop moveTargetToCellTop -> hintManager.moveTargetToCellTop();
            case MoveTargetToCellBottom moveTargetToCellBottom -> hintManager.moveTargetToCellBottom();
            case MoveTargetToCellLeft moveTargetToCellLeft -> hintManager.moveTargetToCellLeft();
            case MoveTargetToCellRight moveTargetToCellRight -> hintManager.moveTargetToCellRight();

            case SavePosition(String positionHistoryName) -> hintManager.saveCurrentPosition(positionHistoryName);
            case UnsavePosition(String positionHistoryName) -> hintManager.unsaveCurrentPosition(positionHistoryName);
            case ToggleSavePosition(String positionHistoryName) -> hintManager.toggleSaveCurrentPosition(positionHistoryName);
            case ClearPositionHistory(String positionHistoryName) -> hintManager.clearPositionHistory(positionHistoryName);
            case CycleNextPosition(String positionHistoryName) -> hintManager.cyclePosition(positionHistoryName, 1);
            case CyclePreviousPosition(String positionHistoryName) -> hintManager.cyclePosition(positionHistoryName, -1);

            case StartAnimation(String animationName) -> animationPlayer.start(animationName);
            case StopAnimation(String animationName) -> animationPlayer.stop(animationName);

            case MacroCommand(Macro macro, AliasResolution aliasResolution) ->
                    macroPlayer.submit(macro.resolve(aliasResolution));

            // Complex command that is manually handled by ComboWatcher and KeyManager.
            case BreakComboPreparation breakComboPreparation -> {}

            // Handled by ComboWatcher directly (it holds the mode state for mutations).
            case MutateMode mutateMode -> {}

            // Handled by ComboWatcher directly (it holds the variable state).
            case SetVariable setVariable -> {}
            case UnsetVariable unsetVariable -> {}
            case ResetVariables resetVariables -> {}

            case BreakMacro breakMacro -> macroPlayer.breakMacro();

            case Exit exit -> platform.requestKillProcess();
            case Restart restart -> platform.requestRestartProcess();

            case Noop noop -> {}

            case SelectHintKey selectHintKey -> hintManager.selectHintKey(eventKey);
            case DeselectHintKey deselectHintKey -> hintManager.deselectHintKey();
            case DeselectAllHintKeys deselectAllHintKeys -> hintManager.deselectAllHintKeys();
            case FilterUiHints filterUiHints -> hintManager.filterUiHints(filterUiHints.role());
            case CopyText copyText -> copyTextPlayer.start(copyText.kind());
            case SelectWindow selectWindow -> {
                if (windowSwitcher != null)
                    windowSwitcher.move(selectWindow.delta());
            }
            case ActivateSelectedWindow activateSelectedWindow -> {
                if (windowSwitcher != null && windowSwitcher.activateSelected())
                    modeController.switchMode(activateSelectedWindow.modeAfter());
            }
            case ActivateWindowByTag activateWindowByTag -> {
                if (windowSwitcher != null && windowSwitcher.selectTag(eventKey) &&
                    windowSwitcher.activateSelected())
                    modeController.switchMode(activateWindowByTag.modeAfter());
            }
            case SelectMenuItem selectMenuItem -> {
                // A key that runs a row does only that. d stays "down" for a menu that
                // gives the key to no row.
                if (appMenus != null && !appMenus.hasTag(eventKey))
                    appMenus.move(selectMenuItem.delta());
            }
            case RunMenuItemByTag runMenuItemByTag -> {
                if (appMenus != null && appMenus.selectTag(eventKey))
                    runSelectedMenuItem(runMenuItemByTag.modeAfter());
            }
            case RunSelectedMenuItem runSelectedMenuItem -> {
                // g confirms, unless the open menu uses g for one of its rows.
                if (appMenus != null && !appMenus.hasTag(eventKey))
                    runSelectedMenuItem(runSelectedMenuItem.modeAfter());
            }
            // @formatter:on
        }
    }

    /**
     * The item's keys are queued first. Leaving the menu mode then takes the menu off
     * the screen. A program with no menu only leaves.
     */
    private void runSelectedMenuItem(String modeAfter) {
        ResolvedMacro macro = appMenus.selectedMacro();
        if (macro != null && macroPlayer != null)
            macroPlayer.submit(macro);
        UiAim aim = appMenus.selectedAim();
        if (aim != null)
            uiAimPlayer.start(aim, macro != null);
        modeController.switchMode(modeAfter);
    }

    /** Ctrl+key on Windows, Command+key on macOS. Injected, so the hook does not eat it. */
    private void sendChord(Key key) {
        if (macroPlayer == null)
            return;
        Key modifier = Os.macos ? Key.leftwin : Key.leftctrl;
        macroPlayer.submit(new ResolvedMacro("copy-text",
                new ResolvedMacroSequence(List.of(new ResolvedMacroParallel(List.of(
                        new ResolvedKeyMacroMove(modifier, true, MacroMoveDestination.OS),
                        new ResolvedKeyMacroMove(key, true, MacroMoveDestination.OS),
                        new ResolvedKeyMacroMove(key, false, MacroMoveDestination.OS),
                        new ResolvedKeyMacroMove(modifier, false, MacroMoveDestination.OS)
                ), Duration.ZERO)))));
    }
}
