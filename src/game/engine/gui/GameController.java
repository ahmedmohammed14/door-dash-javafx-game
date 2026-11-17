package game.engine.gui;

import javafx.stage.Stage;

import game.engine.Board;
import game.engine.Role;
import game.engine.cells.CardCell;
import game.engine.cells.Cell;
import game.engine.cells.ConveyorBelt;
import game.engine.cells.ContaminationSock;
import game.engine.cells.DoorCell;
import game.engine.cells.MonsterCell;
import game.engine.cells.TransportCell;
import game.engine.exceptions.InvalidMoveException;
import game.engine.exceptions.OutOfEnergyException;
import game.engine.monsters.Monster;

/**
 * CONTROLLER — wires Model ↔ View.
 *
 * MVC notes:
 *  - Model owns all state (game engine + audio + theme + mode).
 *  - View builds JavaFX nodes; never touches game logic.
 *  - Controller responds to user events, calls Model, then asks View to refresh.
 *  - Sound/theme triggers happen here (response to events), but the data lives in Model.
 */
public class GameController {

    private final GameModel model;
    private final GameView  view;
    private final Stage     stage;
    /** Last scene we attached the cheat-key filter to (so we never stack filters). */
    private javafx.scene.Scene cheatFilterScene;

    public GameController(GameModel model, GameView view, Stage stage) {
        this.model = model;
        this.view  = view;
        this.stage = stage;
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    public void showStartScreen() {
        // Resume gameplay music if it was stopped (e.g., after win/lose screen)
        model.stopSound();
        model.playStartSound();
        view.showStartScreen(
            (isMulti) -> startGame(Role.SCARER,  isMulti),
            (isMulti) -> startGame(Role.LAUGHER, isMulti),
            this::showInstructionsFromStart,
            model
        );
        // Attach the F11 key filter to the start screen too — onSceneRebuilt
        // is safe to call before a game exists (cheats W/E are guarded).
        onSceneRebuilt();
    }

    private void showInstructionsFromStart() {
        view.showInstructionsOverlay(view.getCurrentRoot());
    }

    private void showInstructionsFromGame() {
        view.showInstructionsOverlay(view.getCurrentRoot());
    }

    // ── Game start ────────────────────────────────────────────────────────────

    private void startGame(Role role, boolean multiplayer) {
        try {
            model.initGame(role);
            model.setMultiplayerMode(multiplayer);
        } catch (Exception ex) {
            view.showError("Could not load game data:\n" + ex.getMessage());
            return;
        }

        // Gameplay music already started in MainApp.start() so the user
        // hears the soundtrack on the start screen too. Re-call it here only
        // if music was stopped (e.g., after a win/lose screen returned to start).
        if (!model.isMusicPlaying()) model.playStartSound();

        Monster player   = model.getPlayer();
        Monster opponent = model.getOpponent();

        view.showGameScreen(
            player, opponent,
            this::onPowerup,
            this::onRoll,
            this::showInstructionsFromGame,
            this::onSceneRebuilt,    // called by View after every scene rebuild
            model
        );

        onSceneRebuilt();   // attach keys + refresh labels for the very first scene
        view.log("▶ Game started! You are " + player.getName()
            + " (" + player.getOriginalRole() + " / " + monType(player) + ")");
        view.log("▶ Opponent: " + opponent.getName()
            + " (" + opponent.getOriginalRole() + " / " + monType(opponent) + ")");
        view.log("▶ Mode: " + (multiplayer ? "Multiplayer 👥 — both roll manually" : "Single Player 👤"));
        view.log("▶ Goal: reach cell 99 with at least 1000 energy!");
        view.log("─────────────────────────────────────────");
    }

    // ── Player actions ────────────────────────────────────────────────────────

    private void onPowerup() {
        Monster current = model.getCurrent();
        // Determine who is using the powerup, in plain language that
        // works in both single-player and multiplayer modes.
        boolean isPlayer = model.isPlayerTurn();
        String whoLabel;
        if (model.isMultiplayerMode()) {
            whoLabel = isPlayer ? "You" : "Opponent";
        } else {
            whoLabel = isPlayer ? "You" : "The Opponent";
        }

        // Snapshot opponent state so we can detect freeze (Dynamo) effect
        boolean opponentWasFrozen = model.getOpponent().isFrozen();
        boolean playerWasFrozen   = model.getPlayer().isFrozen();
        int     opponentEnergyBefore = model.getOpponent().getEnergy();
        int     playerEnergyBefore   = model.getPlayer().getEnergy();

        try {
            model.usePowerup();
            view.log("⚡ " + current.getName() + " used their powerup! (-500 energy)");

            // Build a friendly description of WHAT the powerup actually did,
            // based on the monster type and observed state changes.
            String effectDesc = describePowerupEffect(current,
                playerEnergyBefore, opponentEnergyBefore,
                playerWasFrozen, opponentWasFrozen, isPlayer);

            view.log("   → " + effectDesc);
            view.showAlert(whoLabel + " Used Powerup!",
                current.getName() + " (" + monType(current) + ") activated their powerup.\n\n"
                + effectDesc);

            refreshAll();
        } catch (OutOfEnergyException ex) {
            view.showError(whoLabel + " can't use powerup!\n"
                + current.getName() + " has " + current.getEnergy()
                + " energy but needs 500.");
        }
    }

    /**
     * Produce a human-readable description of the powerup effect that just
     * happened, based on the monster type and observed state changes.
     */
    private String describePowerupEffect(Monster m,
            int playerEnergyBefore, int opponentEnergyBefore,
            boolean playerWasFrozen, boolean opponentWasFrozen,
            boolean isPlayer) {

        if (m instanceof game.engine.monsters.Dasher) {
            return "💨 Momentum Rush!  3× speed for the next 3 turns.";
        }
        if (m instanceof game.engine.monsters.Dynamo) {
            // Dynamo freezes the opponent — see which side got frozen
            Monster victim = isPlayer ? model.getOpponent() : model.getPlayer();
            boolean victimWasFrozenBefore = isPlayer ? opponentWasFrozen : playerWasFrozen;
            if (victim.isFrozen() && !victimWasFrozenBefore) {
                return "❄ Freeze!  " + victim.getName() + " is frozen and will miss their next turn.";
            }
            return "❄ Freeze!  Attempted to freeze the opponent.";
        }
        if (m instanceof game.engine.monsters.MultiTasker) {
            return "🎯 Focus Mode!  Normal speed (no ½ slowdown) for the next 2 turns.";
        }
        if (m instanceof game.engine.monsters.Schemer) {
            // Schemer steals energy — compute how much
            int diff;
            if (isPlayer) {
                diff = opponentEnergyBefore - model.getOpponent().getEnergy();
            } else {
                diff = playerEnergyBefore - model.getPlayer().getEnergy();
            }
            if (diff > 0) {
                return "🎭 Chain Attack!  Stole " + diff + " energy from the opponent.";
            }
            return "🎭 Chain Attack!  Tried to steal energy from the opponent.";
        }
        return "Powerup activated.";
    }

    private void onRoll() {
        // Single-player: only the human rolls; if somehow the button was
        // clickable during the AI's turn (race condition), kick the AI.
        if (!model.isMultiplayerMode() && !model.isPlayerTurn()) {
            autoPlayOpponent();
            return;
        }
        boolean isOpponent = !model.isPlayerTurn();
        performTurn(isOpponent);
    }

    // ── Turn logic ────────────────────────────────────────────────────────────

    private void performTurn(boolean isOpponent) {
        Monster current      = model.getCurrent();
        int     posBefore    = current.getPosition();
        int     enBefore     = current.getEnergy();
        boolean shieldBefore = current.isShielded();
        boolean wasFrozen    = current.isFrozen();

        int     cardsBefore  = Board.getCards().size();
        Monster other        = isOpponent ? model.getPlayer() : model.getOpponent();
        int     otherEnBefore= other.getEnergy();

        try {
            model.playTurn();

            // FREEZE
            if (wasFrozen) {
                String who = isOpponent
                    ? current.getName() + " (opponent)"
                    : "YOU (" + current.getName() + ")";
                view.log("❄️  " + who + " — FROZEN, turn skipped!");
                view.showFreezeNotice(current.getName());
                refreshAll();
                if (checkWinner()) return;
                // After a frozen human turn, the AI gets to play (single-player only)
                if (!model.isMultiplayerMode() && !isOpponent && !model.isPlayerTurn()) {
                    autoPlayOpponent();
                }
                return;
            }

            final int posAfter = current.getPosition();
            final int enAfter  = current.getEnergy();
            int moved    = posAfter - posBefore;
            if (moved < 0) moved += 100;

            final int diceRaw = computeRaw(current, moved);
            model.setLastDiceRoll(diceRaw);

            String prefix = isOpponent ? "🤖 OPPONENT" : "🎲 YOU";
            view.setDiceLabel("🎲 Dice: " + diceRaw);
            view.log(prefix + " (" + current.getName() + ") rolled " + diceRaw
                + " → cell " + posAfter + "  [" + model.getCellName(posAfter) + "]");

            // ── Dice roll animation — show a tumbling dice for ~1s ──────────
            // We disable buttons during the animation so the player can't roll
            // again while it's playing.
            view.setButtonsEnabled(false);
            // Capture variables the closure needs (effectively final already)
            final boolean isOpponentF = isOpponent;
            final int cardsAfter = Board.getCards().size();
            view.animateDiceRoll(diceRaw, () -> {
                // After the dice settles, do the rest of the turn rendering
                continueTurnAfterDice(current, other, posBefore, posAfter,
                    enBefore, enAfter, shieldBefore, otherEnBefore,
                    cardsBefore, cardsAfter, isOpponentF);
            });
            return;   // rest of turn flow continues asynchronously
        }
        catch (InvalidMoveException ex) {
            view.log("⚠️ Move blocked: opponent on destination cell. Roll again.");
            if (!model.isMultiplayerMode() && isOpponent) {
                view.log("   🤖 Opponent re-rolling…");
                autoPlayOpponent();
            } else {
                view.showError("Can't move there — cell occupied by opponent!\nRoll again.");
            }
            return;
        }
    }

    /**
     * Stage 2 of a turn — runs AFTER the dice roll animation completes.
     * Performs all the post-move updates that used to be inline in performTurn:
     *  - card detection, transport animation, monster-cell toast,
     *  - energy logs, shield detection, refresh + win check + AI auto-play.
     */
    private void continueTurnAfterDice(Monster current, Monster other,
            int posBefore, int posAfter, int enBefore, int enAfter,
            boolean shieldBefore, int otherEnBefore,
            int cardsBefore, int cardsAfter, boolean isOpponent) {

            // Card detection — cardsAfter is now a parameter
            int[]   rc         = model.indexToRowCol(posAfter);
            Cell    landed     = model.getBoardCells()[rc[0]][rc[1]];
            boolean cardDrawn  = cardsBefore != cardsAfter || landed instanceof CardCell;

            if (cardDrawn) {
                int    enDiff  = enAfter - enBefore;
                int    posDiff = posAfter - posBefore;
                String cn      = inferCardName(enDiff, posDiff, posAfter, current, other);
                String ce      = inferCardEffect(enDiff, posAfter, current, other, otherEnBefore, isOpponent);
                view.log("🃏 Card: " + cn + " — " + ce);
                view.showCardDrawn(cn, ce, enDiff >= 0 && posDiff >= 0);
            }

            // ── Deck reshuffle detection ────────────────────────────────────
            // Board.drawCard() automatically reloads + shuffles when the deck
            // empties. We detect that by the deck size GROWING (e.g. from 0
            // up to 24 after the draw). Show a "Cards being shuffled" popup.
            if (cardsAfter > cardsBefore) {
                view.log("🃏 Deck reshuffled — 25 fresh cards!");
                view.showShuffleNotice();
            }

            // ── Stationed-monster encounter toast ───────────────────────────
            // If the monster landed on a MonsterCell, the engine has already
            // executed the powerup/swap. Show a short-lived popup explaining
            // what happened, with energy delta and ally/enemy status.
            if (landed instanceof MonsterCell) {
                MonsterCell mc = (MonsterCell) landed;
                Monster station = mc.getCellMonster();
                String stationName = station != null ? station.getName() : "the monster";
                boolean sameRole = station != null && station.getRole() == current.getRole();
                int enDiff = enAfter - enBefore;
                String who = isOpponent ? "Opponent" : "You";
                String pronoun = isOpponent ? "the opponent" : "you";

                String title; String message; String color;
                if (sameRole) {
                    title = "🤝  Ally Encounter!";
                    message = who + " landed on " + stationName + " (same team!).\n"
                        + "Got a free powerup activation!";
                    color = "#27ae60";   // green for allies
                } else if (enDiff > 0) {
                    title = "💱  Energy Swap!";
                    message = who + " landed on " + stationName + " (rival team).\n"
                        + pronoun + " gained " + enDiff + " energy by swapping.";
                    color = "#f5a623";   // gold for gain
                } else if (enDiff < 0) {
                    title = "💱  Energy Swap!";
                    message = who + " landed on " + stationName + " (rival team).\n"
                        + pronoun + " lost " + (-enDiff) + " energy to the swap.";
                    color = "#e94560";   // red for loss
                } else {
                    title = "👾  Monster Encounter";
                    message = who + " met " + stationName + " (rival team).\n"
                        + "No energy change.";
                    color = "#9b59b6";   // purple for neutral
                }
                view.showToast(title, message, color, 3500);
            }

            // ── Door-cell encounter toast ──────────────────────────────────
            // The engine has already applied the door's energy effect. We
            // detect role match and energy delta to compose a friendly popup.
            // Activated doors do nothing on re-land (engine guards that case).
            if (landed instanceof DoorCell) {
                DoorCell door = (DoorCell) landed;
                boolean sameRole = door.getRole() == current.getRole();
                int     enDiff   = enAfter - enBefore;
                String  who      = isOpponent ? "Opponent" : "You";
                String  pronoun  = isOpponent ? "The opponent" : "You";
                String  roleStr  = door.getRole().toString();   // SCARER or LAUGHER

                String title; String message; String color;
                if (enDiff == 0) {
                    // Door was already activated (no effect) OR shield absorbed the loss
                    if (shieldBefore && !current.isShielded() && !sameRole) {
                        title = "🛡  Shielded Door!";
                        message = who + " landed on a " + roleStr + " door (rival team)."

                            + "Shield absorbed the energy loss!";
                        color = "#3498db";
                    } else {
                        title = "🚪  Already-Used Door";
                        message = who + " landed on an already-activated "
                            + roleStr + " door.No effect this time.";
                        color = "#888899";
                    }
                } else if (sameRole) {
                    title = "🚪  Friendly Door!";
                    message = who + " landed on a " + roleStr + " door (same team!)."
                        + pronoun + " gained " + enDiff + " energy.";
                    color = "#27ae60";   // green
                } else {
                    title = "🚪  Rival Door!";
                    message = who + " landed on a " + roleStr + " door (rival team)."
                        + pronoun + " lost " + (-enDiff) + " energy.";
                    color = "#e94560";   // red
                }
                view.showToast(title, message, color, 3500);
            }

            // ── Transport cells (Conveyor / Sock) detection + animation ─────
            // The engine has already teleported the monster by the time we
            // get here, so posAfter is the FINAL cell. We figure out which
            // transport cell triggered the jump by scanning the board for a
            // TransportCell whose (index + effect) equals posAfter and whose
            // index is in the dice-rollable range from posBefore.
            // Skip if a card was drawn (those move the monster too and we
            // don't want a double popup).
            // ── Movement animation: hop first, then transport if any ───────
            // We always want the player to SEE the dice-roll hop (from
            // posBefore to landingCell), and only AFTER that hop completes
            // do we kick off the conveyor/sock animation (if any).
            //
            // Flow:
            //   1. Detect transport trigger (if any) — its landingCell may
            //      be != posAfter (engine already teleported the monster).
            //   2. Hop animation: posBefore → landingCell.
            //   3. Transport animation (if triggered): landingCell → posAfter.
            //   4. finishTurnAfterTransport() — energy logs, refresh, AI turn.
            // If no transport: hop is from posBefore → posAfter directly.

            TransportCell trigger = (!cardDrawn && posBefore != posAfter)
                ? findTransportTrigger(posBefore, posAfter) : null;

            final int landingCell = (trigger != null)
                ? posAfter - trigger.getEffect()
                : posAfter;
            final int finalPos = posAfter;
            final boolean hasTransport = (trigger != null);
            final boolean isConveyor   = trigger instanceof ConveyorBelt;
            final boolean isPlayerSide = !isOpponent;
            final boolean finalIsOpponent = isOpponent;

            // Skip animations for wild card-induced jumps (cards may teleport
            // the monster very far, animating a 30-cell hop would look weird)
            boolean cardMoved = (cardsBefore != cardsAfter)
                && Math.abs(posAfter - posBefore) > 6 + 12;

            if (cardMoved || posBefore == posAfter) {
                // No movement to animate
                finishTurnAfterTransport(current, other,
                    enBefore, enAfter, shieldBefore,
                    otherEnBefore, isOpponent);
            } else {
                // Build the chained animation: hop → (optional transport)
                Runnable afterTransport = () ->
                    finishTurnAfterTransport(current, other,
                        enBefore, enAfter, shieldBefore,
                        otherEnBefore, isOpponent);

                Runnable afterHop = () -> {
                    if (hasTransport) {
                        // Log + run transport animation
                        if (isConveyor) {
                            view.log("⏩ Conveyor at cell " + landingCell + " +"
                                + (finalPos - landingCell) + " → " + finalPos);
                        } else {
                            view.log("🧦 Sock at cell " + landingCell + " "
                                + (finalPos - landingCell) + " → " + finalPos);
                        }
                        view.animateTransport(landingCell, finalPos, isConveyor,
                            isPlayerSide, () -> {
                                String who  = labelFor(finalIsOpponent);
                                String whom = finalIsOpponent ? "the opponent" : "you";
                                if (isConveyor) {
                                    view.log("   ⏩ " + who + " rode the conveyor to cell " + finalPos);
                                } else {
                                    view.log("   🧦 The sock pulled " + whom + " back to cell " + finalPos);
                                }
                                afterTransport.run();
                            });
                    } else {
                        afterTransport.run();
                    }
                };

                // Hop from posBefore → landingCell (where the dice put us).
                // If posBefore == landingCell (rare edge case), skip hop.
                if (posBefore != landingCell) {
                    view.animateHop(posBefore, landingCell, isPlayerSide, afterHop);
                } else {
                    afterHop.run();
                }
            }
    }

    /**
     * Run the post-turn updates that come AFTER a possible transport animation:
     *  - current monster's energy change log
     *  - shield-blocked detection
     *  - opponent energy change log
     *  - refresh UI
     *  - win check + AI auto-play
     *
     * Extracted into its own method so it can be called either inline (no
     * animation) or as the animation's onDone callback.
     */
    private void finishTurnAfterTransport(Monster current, Monster other,
            int enBefore, int enAfter, boolean shieldBefore,
            int otherEnBefore, boolean isOpponent) {

        if (enAfter != enBefore) {
            int d = enAfter - enBefore;
            view.log("   ⚡ " + current.getName() + ": " + enBefore
                + " → " + enAfter + " (" + (d > 0 ? "+" : "") + d + ")");
        }

        if (shieldBefore && !current.isShielded()) {
            view.log("   🛡 " + current.getName() + "'s shield blocked an energy loss!");
            view.showShieldBlocked(current.getName());
        }

        int otherEnAfter = other.getEnergy();
        if (otherEnAfter != otherEnBefore) {
            int d = otherEnAfter - otherEnBefore;
            view.log("   ⚡ " + other.getName() + ": " + otherEnBefore
                + " → " + otherEnAfter + " (" + (d > 0 ? "+" : "") + d + ")");
        }

        refreshAll();
        if (checkWinner()) return;

        // Single-player AI: opponent auto-plays after a short delay
        if (!model.isMultiplayerMode() && !isOpponent && !model.isPlayerTurn()) {
            autoPlayOpponent();
        }
    }

    /**
     * Find which transport cell teleported the monster from posBefore to posAfter.
     * Walks all board cells looking for a TransportCell at index `landing` where
     * `landing + effect == posAfter` and `landing` is a plausible dice-roll
     * destination from posBefore.  Returns null if no match (means a card or
     * normal move produced the position change).
     */
    private TransportCell findTransportTrigger(int posBefore, int posAfter) {
        Cell[][] cells = model.getBoardCells();
        // Maximum reachable distance per turn = 6 (raw) × 3 (Dasher momentum) = 18
        int maxReach = 18;
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 10; col++) {
                Cell cell = cells[row][col];
                if (!(cell instanceof TransportCell)) continue;
                int landing = linearIndex(row, col);
                // Landing must be ahead of posBefore (dice always moves forward)
                // and within reachable range.
                if (landing <= posBefore || landing - posBefore > maxReach) continue;
                TransportCell tc = (TransportCell) cell;
                if (landing + tc.getEffect() == posAfter) {
                    return tc;
                }
            }
        }
        return null;
    }

    /** Reverse-engineer the linear board index for a row/col pair. */
    private int linearIndex(int row, int col) {
        return row * 10 + (row % 2 == 0 ? col : 9 - col);
    }

    /** Friendly "You" / "Opponent" label that respects single/multiplayer mode. */
    private String labelFor(boolean isOpponent) {
        if (model.isMultiplayerMode()) {
            return isOpponent ? "Opponent" : "You";
        }
        return isOpponent ? "The Opponent" : "You";
    }

    private int computeRaw(Monster current, int moved) {
        if (current instanceof game.engine.monsters.Dasher) {
            int divisor = ((game.engine.monsters.Dasher) current).getMomentumTurns() > 0 ? 3 : 2;
            return Math.max(1, Math.min(6, moved / divisor));
        }
        if (current instanceof game.engine.monsters.MultiTasker) {
            game.engine.monsters.MultiTasker mt = (game.engine.monsters.MultiTasker) current;
            return Math.max(1, Math.min(6, mt.getNormalSpeedTurns() > 0 ? moved : moved * 2));
        }
        return Math.max(1, Math.min(6, moved));
    }

    private String inferCardName(int enDiff, int posDiff, int posAfter,
                                  Monster current, Monster other) {
        if (posAfter == 0 || posDiff < -10) return "Start Over";
        if (enDiff > 0 && Math.abs(enDiff) <= 150) return "Energy Steal";
        if (current.isShielded()) return "Super Shield";
        if (current.isConfused() || other.isConfused()) return "Confusion";
        return "Mystery Card";
    }

    private String inferCardEffect(int enDiff, int posAfter,
                                    Monster current, Monster other, int otherEnBefore,
                                    boolean isOpponent) {
        if (posAfter == 0) return (isOpponent ? "Opponent" : "You") + " sent back to cell 0!";
        if (enDiff > 0)    return "Stole " + enDiff + " energy from opponent!";
        if (current.isShielded()) return "Shield activated — next energy loss blocked!";
        if (current.isConfused() || other.isConfused())
            return "Roles swapped for "
                + Math.max(current.getConfusionTurns(), other.getConfusionTurns()) + " turns!";
        return "Card effect applied.";
    }

    // ── Win check ─────────────────────────────────────────────────────────────

    private boolean checkWinner() {
        Monster winner = model.getWinner();
        if (winner == null) return false;
        view.disableActions();
        view.log("─────────────────────────────────────────");
        view.log("🏆 " + winner.getName() + " wins the game!");
        model.stopSound();
        if (winner == model.getPlayer()) model.playWinSound();
        else                              model.playLoseSound();
        view.showWinScreen(winner, model.getPlayer(), model.getOpponent(),
            this::showStartScreen);
        return true;
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private void refreshAll() {
        view.refreshBoard(model.getBoardCells(), model.getPlayer(), model.getOpponent());
        view.updatePlayerPanel(model.getPlayer());
        view.updateOpponentPanel(model.getOpponent());
        view.setCardCount(Board.getCards().size());

        boolean myTurn = model.isPlayerTurn();
        view.setTurnState(myTurn, model.getCurrent().isFrozen(), model.isMultiplayerMode());

        // Decide button state — ONE clear branch, no conflicting calls
        boolean enableButtons;
        if (model.isMultiplayerMode()) {
            // Both players use the same physical buttons; always enabled
            view.setRollButtonText(myTurn ? "🎲 Roll (You)" : "🎲 Roll (Opponent)");
            view.setPowerupButtonText(myTurn
                ? "⚡ Your Powerup  (500)"
                : "⚡ Opponent Powerup  (500)");
            enableButtons = true;
        } else {
            // Single-player: human rolls on their turn, AI rolls on opponent's turn
            view.setRollButtonText(myTurn ? "🎲 Roll Dice" : "🤖 Opponent thinking…");
            view.setPowerupButtonText(myTurn
                ? "⚡ Your Powerup  (500)"
                : "⚡ Opponent Powerup  (500)");
            enableButtons = myTurn;   // disabled only when it's AI's turn
        }
        view.setButtonsEnabled(enableButtons);
    }

    /**
     * Auto-play the opponent's turn after a short delay so the player can see
     * the board update first. Only used in single-player mode.
     */
    private void autoPlayOpponent() {
        new Thread(() -> {
            try { Thread.sleep(900); } catch (InterruptedException ignored) {}
            javafx.application.Platform.runLater(() -> {
                // Double-check turn ownership before acting (state may have
                // changed if the player hit a powerup-induced freeze etc.)
                if (!model.isPlayerTurn() && model.getWinner() == null) {
                    performTurn(true);
                }
            });
        }).start();
    }

    private String monType(Monster m) {
        if (m instanceof game.engine.monsters.Dasher)      return "Dasher";
        if (m instanceof game.engine.monsters.Dynamo)      return "Dynamo";
        if (m instanceof game.engine.monsters.MultiTasker) return "MultiTasker";
        if (m instanceof game.engine.monsters.Schemer)     return "Schemer";
        return "Unknown";
    }

    // ── Cheats ────────────────────────────────────────────────────────────────

    /**
     * One-time-per-scene setup: attach the W / E / F11 key handler AND
     * refresh button labels.  Called every time the View rebuilds (e.g.,
     * theme toggle) so the keys keep working and the button labels stay
     * correct for whose turn it is.
     *
     * Uses addEventFilter at the SCENE level so keys fire regardless of
     * which child control currently has focus (setOnKeyPressed would be
     * blocked by a focused button).
     */
    private void onSceneRebuilt() {
        if (stage.getScene() == null) return;
        // addEventFilter fires BEFORE child controls — works even when a
        // button has focus. We track which Scene already has the filter
        // attached so we don't stack filters on every theme rebuild.
        javafx.scene.Scene sc = stage.getScene();
        if (sc != cheatFilterScene) {
            sc.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
                switch (ev.getCode()) {
                    case W:   cheatJumpToEnd();   ev.consume(); break;
                    case E:   cheatBoostEnergy(); ev.consume(); break;
                    case F11: stage.setFullScreen(!stage.isFullScreen()); ev.consume(); break;
                    default:  break;
                }
            });
            cheatFilterScene = sc;
        }
        // Bring the scene's root into focus so it actually receives key events
        if (sc.getRoot() != null) sc.getRoot().requestFocus();

        // Refresh button labels & turn state — they were just rebuilt with
        // default text so without this call multiplayer/single-player labels
        // would stay generic until the next turn.
        if (model != null && model.getGame() != null) refreshAll();
    }

    private void cheatJumpToEnd() {
        // Safety guard — only works once a game is running
        if (model.getGame() == null) return;
        Monster cur = model.getCurrent();
        model.cheatJumpToEnd(cur);
        view.log("🔧 [CHEAT-W] " + cur.getName() + " jumped to cell 99!");
        refreshAll();
        checkWinner();
    }

    private void cheatBoostEnergy() {
        if (model.getGame() == null) return;
        Monster cur = model.getCurrent();
        model.cheatBoostEnergy(cur);
        view.log("🔧 [CHEAT-E] " + cur.getName() + " energy +500!");
        refreshAll();
    }
}
