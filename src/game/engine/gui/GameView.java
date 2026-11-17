package game.engine.gui;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.util.Duration;

import game.engine.Role;
import game.engine.cells.*;
import game.engine.cells.Cell;
import game.engine.monsters.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * VIEW — builds every JavaFX node. Zero game logic; zero state ownership.
 *
 * Animation features (M3 bonus):
 *  - Fade-in entry on every scene
 *  - Bouncing player/opponent figures on their current board cell
 *  - Pulsing border on current monster's cell
 *  - Title screen monster type carousel
 *  - Win/Lose screen confetti
 *  - Dice number rolling animation in top bar
 *  - Smooth popup fade-in
 *
 * MVC notes:
 *  - rebuildCurrentScreen Runnable enables theme toggle to refresh scene
 *    while preserving game state.
 *  - Reads sound/theme/multiplayer state from GameModel, never owns it.
 */
public class GameView {

    // ── Stage / lifecycle ─────────────────────────────────────────────────────
    private final Stage stage;
    /** Set by every screen builder so theme toggle can rebuild it. */
    private Runnable rebuildCurrentScreen;

    // ── Start-screen nodes ────────────────────────────────────────────────────
    private Button       scarerBtn, laugherBtn, startInstructionsBtn;
    private ToggleButton singleBtn, multiBtn;

    // ── Game-screen nodes ─────────────────────────────────────────────────────
    private StackPane gameRoot;
    Label  turnLabel, diceLabel, cardLabel;
    Button powerupBtn, rollBtn;
    private GridPane boardGrid;
    private VBox     playerPanel, opponentPanel;
    private TextArea logArea;
    private VBox     legendBox;

    // Player panel labels
    private Label playerNameLbl, playerTypeLbl, playerOrigRoleLbl, playerCurRoleLbl;
    private Label playerEnergyLbl, playerPosLbl, playerShieldLbl, playerFrozenLbl;
    private Label playerConfLbl, playerSpeedLbl;
    private ImageView playerImage;

    // Opponent panel labels
    private Label oppNameLbl, oppTypeLbl, oppOrigRoleLbl, oppCurRoleLbl;
    private Label oppEnergyLbl, oppPosLbl, oppShieldLbl, oppFrozenLbl;
    private Label oppConfLbl, oppSpeedLbl;
    private ImageView oppImage;

    private static final int CELL_SIZE = 78;   // slightly larger board

    private static final String[] DOOR_IMAGE_NAMES = {
        "PurpleDoor","RedDoor","YellowDoor","DarkGreenDoor","MixedDoor",
        "OrangeDoor","PinkDoor","DarkBlueDoor","LightGreenDoor","LightBlueDoor"
    };
    private final Map<Integer, String> doorImageMap = new HashMap<>();
    private final Random rng = new Random();

    /** Persistent image cache — prevents heap exhaustion across refreshes.
     *  Each PNG/JPG is loaded ONCE and reused everywhere. */
    private final Map<String, javafx.scene.image.Image> imageCache = new HashMap<>();
    /** Sentinel value stored when we tried to find an image and failed,
     *  so we don't keep walking the filesystem on every refresh. */
    private static final javafx.scene.image.Image MISSING_IMAGE_SENTINEL =
        new javafx.scene.image.WritableImage(1, 1);

    /** Persistent log buffer — survives scene rebuilds (theme switch). */
    private final StringBuilder logBuffer = new StringBuilder();

    // Theme palette — read from model at every refresh
    private GameModel currentModel;   // last model passed in (for theme access)

    public GameView(Stage stage) {
        this.stage = stage;
    }

    // =========================================================================
    // PALETTE — light vs dark
    // =========================================================================
    private boolean dark() { return currentModel == null || currentModel.isDarkMode(); }

    private String pal(String key) {
        boolean d = dark();
        switch (key) {
            case "bg_main":    return d ? "#0e2233" : "#e3f6fc";
            case "bg_panel":   return d ? "#071e30" : "#b2ebf2";
            case "bg_bar":     return d ? "#081c2e" : "#00838f";
            case "bg_card":    return d ? "#0c2438" : "#e0f7fa";
            case "bg_card2":   return d ? "#082035" : "#b2ebf2";
            case "bg_log":     return d ? "#051520" : "#ffffff";
            case "bg_legend":  return d ? "#04121e" : "#80deea";
            case "text_main":  return d ? "#ffffff" : "#003344";
            case "text_sub":   return d ? "#aaaacc" : "#006677";
            case "text_mute":  return d ? "#888899" : "#005566";
            case "section":    return d ? "#444466" : "#006080";
            case "cell_norm":  return d ? "#ffff99" : "#fff59d";  // friendly yellow
            case "cell_conv":  return d ? "#003d1a" : "#a5d6a7";
            case "cell_dim":   return d ? "#1a1a2e" : "#80deea";
            case "border":     return d ? "#2a2a44" : "#00838f";
            case "accent":     return "#e94560";
            case "gold":       return "#f5a623";
            case "green":      return "#2ecc71";
            case "blue":       return "#3498db";
            case "purple":     return "#9b59b6";
        }
        return "#ffffff";
    }

    // =========================================================================
    // FULLSCREEN-SAFE SCENE SWITCH
    // =========================================================================
    private void safeSetScene(Scene scene) {
        boolean fs = stage.isFullScreen();
        stage.setScene(scene);
        if (fs) stage.setFullScreen(true);
        // Key handlers (F11/W/E) are wired by the Controller via the
        // onSceneRebuilt callback — NOT here, otherwise setOnKeyPressed
        // would overwrite them and they would stop working until a
        // theme rebuild re-ran the controller's wiring.
    }

    /** Fade-in animation applied to every new scene root. */
    private void animateSceneIn(javafx.scene.Node root) {
        root.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(400), root);
        ft.setFromValue(0); ft.setToValue(1);
        ft.play();
    }

    // =========================================================================
    // START SCREEN
    // =========================================================================
    void showStartScreen(Consumer<Boolean> onScarer, Consumer<Boolean> onLaugher,
                         Runnable onInstructions, GameModel model) {
        this.currentModel = model;

        StackPane root = new StackPane();
        String grad = dark()
            ? "linear-gradient(to bottom, #071a2a, #0e2d42, #071a2a)"
            : "linear-gradient(to bottom, #e0f7fa, #80deea, #b2ebf2)";
        root.setStyle("-fx-background-color: " + grad + ";");

        VBox center = new VBox(20);
        center.setAlignment(Pos.CENTER);
        center.setMaxWidth(720);
        center.setPadding(new Insets(30, 40, 30, 40));

        // ── Animated title ─────────────────────────────────────────────────
        Label title = new Label("DooR DasH");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 68));
        title.setTextFill(Color.web(pal("accent")));
        addPulse(title);

        Label subtitle = new Label("Scare vs Laugh Touchdown");
        subtitle.setFont(Font.font("Arial", FontWeight.SEMI_BOLD, 22));
        subtitle.setTextFill(Color.web(pal("gold")));

        Label tagline = new Label("\"We Scare, Because we care.\"   OR   \"We Laugh, That's our path.\"");
        tagline.setFont(Font.font("Arial", 13));
        tagline.setTextFill(Color.web(pal("text_sub")));

        // ── Mode toggle ─────────────────────────────────────────────────────
        Label modeLabel = new Label("Game Mode");
        modeLabel.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        modeLabel.setTextFill(Color.web(pal("text_main")));

        ToggleGroup mg = new ToggleGroup();
        singleBtn = modeBtn("👤  Single Player", mg);
        multiBtn  = modeBtn("👥  Multiplayer",   mg);
        if (model.isMultiplayerMode()) multiBtn.setSelected(true); else singleBtn.setSelected(true);
        HBox modeRow = new HBox(0, singleBtn, multiBtn);
        modeRow.setAlignment(Pos.CENTER);

        // ── Role buttons ────────────────────────────────────────────────────
        Label chooseLabel = new Label("Choose Your Side");
        chooseLabel.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        chooseLabel.setTextFill(Color.web(pal("text_sub")));

        scarerBtn  = buildRoleBtn("⚡   SCARER",  "#7b0000","#c0392b","#e74c3c");
        laugherBtn = buildRoleBtn("😂   LAUGHER", "#004d00","#1e8449","#27ae60");
        HBox roleRow = new HBox(28, scarerBtn, laugherBtn);
        roleRow.setAlignment(Pos.CENTER);

        // ── Top-bar controls (sound + theme) ────────────────────────────────
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER);
        controls.getChildren().addAll(
            soundBtn(model),
            settingsBtn(model),
            themeBtn(model),
            startInstructionsBtnBuilder(onInstructions)
        );

        Label hotkeys = new Label("F11 = Fullscreen   |   W = Win Cheat   |   E = Energy Cheat");
        hotkeys.setFont(Font.font("Arial", 11));
        hotkeys.setTextFill(Color.web(pal("text_mute")));

        center.getChildren().addAll(title, subtitle, tagline,
            new Region(),
            modeLabel, modeRow,
            new Region(),
            chooseLabel, roleRow,
            new Region(),
            controls, hotkeys);

        root.getChildren().add(center);

        // Wire actions
        scarerBtn .setOnAction(e -> onScarer .accept(multiBtn.isSelected()));
        laugherBtn.setOnAction(e -> onLaugher.accept(multiBtn.isSelected()));

        rebuildCurrentScreen = () -> showStartScreen(onScarer, onLaugher, onInstructions, model);

        Scene scene = new Scene(root, 980, 640);
        safeSetScene(scene);
        animateSceneIn(root);
        stage.show();
    }

    private Button startInstructionsBtnBuilder(Runnable onInstructions) {
        startInstructionsBtn = new Button("📖  Instructions");
        startInstructionsBtn.setFont(Font.font("Arial", 13));
        styleBtn(startInstructionsBtn, "#103050", "#1a4a7a");
        startInstructionsBtn.setOnAction(e -> onInstructions.run());
        return startInstructionsBtn;
    }

    private ToggleButton modeBtn(String text, ToggleGroup g) {
        ToggleButton tb = new ToggleButton(text);
        tb.setToggleGroup(g);
        tb.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        tb.setPrefSize(180, 38);
        String on  = "-fx-background-color:#1a5276;-fx-text-fill:white;-fx-cursor:hand;";
        String off = "-fx-background-color:#0d2233;-fx-text-fill:#88aabb;-fx-cursor:hand;";
        tb.setStyle(off);
        tb.selectedProperty().addListener((o,ov,nv) -> tb.setStyle(nv ? on : off));
        return tb;
    }

    // =========================================================================
    // INSTRUCTIONS OVERLAY
    // =========================================================================
    void showInstructionsOverlay(StackPane sceneRoot) {
        Rectangle backdrop = new Rectangle();
        backdrop.setFill(Color.rgb(0,0,0,0.80));
        backdrop.widthProperty().bind(sceneRoot.widthProperty());
        backdrop.heightProperty().bind(sceneRoot.heightProperty());

        VBox content = new VBox(12);
        content.setPadding(new Insets(24, 30, 24, 30));
        content.setStyle("-fx-background-color:" + pal("bg_panel") + ";");

        Button closeBtn = new Button("✕  Close");
        closeBtn.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        styleBtn(closeBtn, pal("accent"), "#c0392b");

        HBox closeRow = new HBox(closeBtn);
        closeRow.setAlignment(Pos.CENTER_RIGHT);

        Label mainTitle = new Label("🚪  DOOR DASH – RULES");
        mainTitle.setFont(Font.font("Arial", FontWeight.BOLD, 22));
        mainTitle.setTextFill(Color.web(pal("gold")));

        content.getChildren().addAll(closeRow, mainTitle);
        content.getChildren().addAll(buildInstructionNodes());

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background:" + pal("bg_panel") + ";-fx-background-color:" + pal("bg_panel") + ";");
        scroll.setMaxWidth(820); scroll.setMaxHeight(560); scroll.setPrefHeight(560);

        VBox card = new VBox(scroll);
        card.setStyle("-fx-background-color:" + pal("bg_panel")
            + ";-fx-border-color:" + pal("accent") + ";-fx-border-width:2;"
            + "-fx-border-radius:10;-fx-background-radius:10;");
        card.setMaxWidth(830); card.setMaxHeight(580);

        StackPane overlay = new StackPane(backdrop, card);
        overlay.setPickOnBounds(true);
        sceneRoot.getChildren().add(overlay);

        // fade-in
        overlay.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(250), overlay);
        ft.setFromValue(0); ft.setToValue(1); ft.play();

        Runnable close = () -> sceneRoot.getChildren().remove(overlay);
        closeBtn.setOnAction(e -> close.run());

        stage.getScene().addEventHandler(
            javafx.scene.input.KeyEvent.KEY_PRESSED,
            ev -> {
                if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                    close.run();
                    ev.consume();
                }
            }
        );
    }

    StackPane getCurrentRoot() {
        if (stage.getScene() != null && stage.getScene().getRoot() instanceof StackPane)
            return (StackPane) stage.getScene().getRoot();
        StackPane sp = new StackPane(stage.getScene().getRoot());
        stage.getScene().setRoot(sp);
        return sp;
    }

    // =========================================================================
    // GAME SCREEN
    // =========================================================================
    /**
     * Build the game scene.
     * @param onSceneRebuilt called every time the scene is rebuilt (theme
     *                       toggle) so the Controller can re-attach key
     *                       handlers (W/E cheats etc) to the new scene.
     */
    void showGameScreen(Monster playerMon, Monster opponentMon,
                        Runnable onPowerup, Runnable onRoll,
                        Runnable onInfo, Runnable onSceneRebuilt,
                        GameModel model) {
        this.currentModel = model;
        // Only randomise door image positions when starting a NEW game,
        // never on theme rebuilds (otherwise doors visually "shuffle" mid-game).
        // We detect a new game by the player being at cell 0.
        if (playerMon.getPosition() == 0 && opponentMon.getPosition() == 0)
            doorImageMap.clear();

        String bg = pal("bg_main");

        BorderPane inner = new BorderPane();
        inner.setStyle("-fx-background-color:" + bg + ";");

        inner.setTop(buildTopBar(model));

        // ── Board (no scrollpane — fixed-size grid, always fits) ───────────
        boardGrid = new GridPane();
        boardGrid.setHgap(2); boardGrid.setVgap(2);
        boardGrid.setPadding(new Insets(6));
        boardGrid.setStyle("-fx-background-color:" + bg + ";");
        boardGrid.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        boardGrid.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        // Info button — circular badge
        Button infoBtn = buildInfoButton();
        infoBtn.setOnAction(e -> onInfo.run());

        StackPane boardStack = new StackPane(boardGrid, infoBtn);
        boardStack.setAlignment(Pos.CENTER);
        boardStack.setStyle("-fx-background-color:" + bg + ";");
        StackPane.setAlignment(infoBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(infoBtn, new Insets(6,6,0,0));

        // Side panels
        playerPanel   = buildMonsterPanel(playerMon,   "YOU",      pal("gold"),    true);
        opponentPanel = buildMonsterPanel(opponentMon, "OPPONENT", pal("accent"),  false);

        // Legend stacked below player panel
        legendBox = buildLegendBox();
        VBox leftCol = new VBox(0, playerPanel, legendBox);
        leftCol.setPrefWidth(200);
        leftCol.setMaxWidth(200);
        leftCol.setMinWidth(200);
        leftCol.setStyle("-fx-background-color:" + pal("bg_panel") + ";");

        HBox.setHgrow(boardStack, Priority.ALWAYS);
        HBox.setHgrow(leftCol,       Priority.NEVER);
        HBox.setHgrow(opponentPanel, Priority.NEVER);

        HBox centerRow = new HBox(0, leftCol, boardStack, opponentPanel);
        centerRow.setAlignment(Pos.CENTER);
        centerRow.setFillHeight(true);
        centerRow.setStyle("-fx-background-color:" + bg + ";");

        inner.setCenter(centerRow);
        inner.setBottom(buildBottomSection());

        powerupBtn.setOnAction(e -> onPowerup.run());
        rollBtn   .setOnAction(e -> onRoll.run());

        gameRoot = new StackPane(inner);
        gameRoot.setStyle("-fx-background-color:" + bg + ";");

        // Rebuild callback for theme toggle — keep all state intact
        rebuildCurrentScreen = () -> {
            showGameScreen(playerMon, opponentMon, onPowerup, onRoll, onInfo,
                onSceneRebuilt, model);
            refreshBoard(model.getBoardCells(), model.getPlayer(), model.getOpponent());
            updatePlayerPanel(model.getPlayer());
            updateOpponentPanel(model.getOpponent());
            // Re-attach key handlers (W/E cheats) to the freshly-built scene
            if (onSceneRebuilt != null) onSceneRebuilt.run();
        };

        Scene scene = new Scene(gameRoot, 1300, 820);
        safeSetScene(scene);
        animateSceneIn(gameRoot);
        stage.show();
    }

    // =========================================================================
    // WIN SCREEN with confetti animation
    // =========================================================================
    void showWinScreen(Monster winner, Monster player, Monster opponent, Runnable onReturn) {
        boolean playerWon = winner == player;

        StackPane root = new StackPane();
        root.setStyle("-fx-background-color:" + pal("bg_main") + ";");

        VBox center = new VBox(18);
        center.setAlignment(Pos.CENTER);
        center.setMaxWidth(720);
        center.setPadding(new Insets(40));

        Label resultLbl = new Label(playerWon ? "🏆  YOU WIN!" : "💀  GAME OVER");
        resultLbl.setFont(Font.font("Arial", FontWeight.BOLD, 64));
        resultLbl.setTextFill(Color.web(playerWon ? pal("gold") : pal("accent")));
        addBounce(resultLbl);

        Label winnerLbl = new Label(winner.getName() + " wins as " + winner.getOriginalRole() + "!");
        winnerLbl.setFont(Font.font("Arial", FontWeight.SEMI_BOLD, 24));
        winnerLbl.setTextFill(Color.web(pal("text_main")));

        HBox statsBox = new HBox(50);
        statsBox.setAlignment(Pos.CENTER);
        statsBox.getChildren().addAll(
            buildFinalStats(player,   "YOU",      player == winner),
            buildFinalStats(opponent, "OPPONENT", opponent == winner)
        );

        Button returnBtn = new Button("↩  Return to Start");
        returnBtn.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        returnBtn.setPrefSize(230, 50);
        styleBtn(returnBtn, "#1a5276", "#2980b9");
        returnBtn.setOnAction(e -> onReturn.run());

        center.getChildren().addAll(resultLbl, winnerLbl, statsBox, returnBtn);
        root.getChildren().add(center);
        StackPane.setAlignment(center, Pos.CENTER);

        if (playerWon) launchConfetti(root);

        Scene scene = new Scene(root, 800, 540);
        safeSetScene(scene);
        animateSceneIn(root);
        stage.show();
    }

    /** Spawn small falling colored squares — works without external assets. */
    private void launchConfetti(StackPane root) {
        String[] colors = {"#f5a623","#e94560","#27ae60","#3498db","#9b59b6","#ffffff"};
        for (int i = 0; i < 40; i++) {
            Rectangle p = new Rectangle(8, 8, Color.web(colors[i % colors.length]));
            p.setRotate(rng.nextInt(360));
            double startX = rng.nextInt(800) - 400;
            p.setTranslateX(startX);
            p.setTranslateY(-300);

            TranslateTransition tt = new TranslateTransition(
                Duration.seconds(2 + rng.nextDouble() * 2), p);
            tt.setByY(800);
            tt.setByX((rng.nextDouble() - 0.5) * 200);
            tt.setDelay(Duration.millis(rng.nextInt(1500)));
            tt.setCycleCount(Timeline.INDEFINITE);

            RotateTransition rt = new RotateTransition(Duration.seconds(2), p);
            rt.setByAngle(360);
            rt.setCycleCount(Timeline.INDEFINITE);

            root.getChildren().add(p);
            tt.play();
            rt.play();
        }
    }

    // =========================================================================
    // LIVE REFRESH
    // =========================================================================
    void refreshBoard(Cell[][] cells, Monster player, Monster opponent) {
        boardGrid.getChildren().clear();
        boardGrid.getColumnConstraints().clear();
        boardGrid.getRowConstraints().clear();
        for (int br = 0; br < 10; br++) {
            for (int c = 0; c < 10; c++) {
                int idx = linearIndex(br, c);
                int dispR = 9 - br;
                boardGrid.add(buildCellPane(cells[br][c], idx, player, opponent), c, dispR);
            }
        }
        for (int i = 0; i < 10; i++) {
            boardGrid.getColumnConstraints().add(new ColumnConstraints(CELL_SIZE));
            boardGrid.getRowConstraints().add(new RowConstraints(CELL_SIZE));
        }
    }

    void updatePlayerPanel(Monster m) {
        updatePanel(m, playerNameLbl, playerTypeLbl, playerOrigRoleLbl, playerCurRoleLbl,
            playerEnergyLbl, playerPosLbl, playerShieldLbl, playerFrozenLbl,
            playerConfLbl, playerSpeedLbl, playerImage);
    }
    void updateOpponentPanel(Monster m) {
        updatePanel(m, oppNameLbl, oppTypeLbl, oppOrigRoleLbl, oppCurRoleLbl,
            oppEnergyLbl, oppPosLbl, oppShieldLbl, oppFrozenLbl,
            oppConfLbl, oppSpeedLbl, oppImage);
    }

    void log(String msg) {
        logBuffer.append(msg).append("\n");
        if (logArea != null) logArea.appendText(msg + "\n");
    }

    void setTurnState(boolean myTurn, boolean frozen, boolean multiplayer) {
        String who;
        if (multiplayer) {
            who = myTurn ? "P1 TURN ▶" : "P2 TURN ▶";
        } else {
            who = myTurn ? "YOUR TURN ▶" : "OPPONENT'S TURN ▶";
        }
        if (frozen) who += "  ❄ FROZEN";
        turnLabel.setText(who);
        turnLabel.setTextFill(frozen ? Color.web(pal("blue")) :
            myTurn ? Color.web(pal("green")) : Color.web(pal("accent")));
    }

    void setRollButtonText(String text)    { rollBtn.setText(text); }
    void setPowerupEnabled(boolean enabled){ powerupBtn.setDisable(!enabled); }
    void setPowerupButtonText(String text) { powerupBtn.setText(text); }
    void setDiceLabel(String text)         { diceLabel.setText(text); animateDice(); }

    /** Update the card-count label in the top bar. */
    void setCardCount(int remaining) {
        if (cardLabel != null) cardLabel.setText("🃏 Cards: " + remaining);
    }

    /** Show a "Cards being shuffled" popup. Used when the deck refills. */
    void showShuffleNotice() {
        showPopup("🃏  Shuffling Deck!",
            "The card deck has been reshuffled with 25 fresh cards.\nGood luck on your next draw!",
            pal("gold"));
    }
    void disableActions()                  { powerupBtn.setDisable(true); rollBtn.setDisable(true); }

    /** Enable or disable BOTH action buttons together (clean state mgmt). */
    void setButtonsEnabled(boolean enabled) {
        powerupBtn.setDisable(!enabled);
        rollBtn.setDisable(!enabled);
    }

    /**
     * Animate a 3D-looking dice rolling over the board for ~1 second
     * before settling on the final number. The dice is drawn as a Group
     * of shapes (square + dots) so no image asset is needed.
     *
     * @param finalNumber the dice result that will be shown when it stops
     * @param onDone      callback invoked when the dice fades out
     */
    void animateDiceRoll(int finalNumber, Runnable onDone) {
        if (boardGrid == null) { if (onDone != null) onDone.run(); return; }

        javafx.geometry.Bounds gb = boardGrid.getBoundsInLocal();
        double boardW = gb.getWidth();
        double boardH = gb.getHeight();
        double cx = boardW / 2;
        double cy = boardH / 2;

        // ── Realistic dice toss timeline ────────────────────────────────────
        // 1. Dice "tossed" from a corner of the board
        // 2. Travels along a curved path toward the center
        // 3. Rotates fast at first, slowing down (deceleration)
        // 4. Bounces twice on landing
        // 5. Settles on the final face

        // Pick a random toss-origin corner (top-left or top-right) for variety
        boolean fromLeft = Math.random() < 0.5;
        double startX = fromLeft ? 40        : boardW - 110;
        double startY = 20;
        double endX   = cx - 35;
        double endY   = cy - 35;

        // Total roll duration ~1.8 seconds — long enough to feel like a real toss
        final double TOTAL_MS = 1800;
        // Deceleration profile: rotations per second drops from 8 → 0
        final int frames = 30;
        final double frameMs = TOTAL_MS / frames;

        // Create dice with a random initial face
        javafx.scene.layout.StackPane dice = buildDiceFace(1 + (int)(Math.random() * 6));
        dice.setManaged(false);
        dice.setLayoutX(startX);
        dice.setLayoutY(startY);
        dice.setOpacity(0);
        boardGrid.getChildren().add(dice);

        // Drop a soft shadow underneath that grows as the dice "approaches" the surface
        javafx.scene.shape.Ellipse shadow = new javafx.scene.shape.Ellipse(
            startX + 35, startY + 70, 25, 6);
        shadow.setFill(javafx.scene.paint.Color.rgb(0, 0, 0, 0.30));
        shadow.setManaged(false);
        boardGrid.getChildren().add(shadow);

        // Fade in
        FadeTransition fi = new FadeTransition(Duration.millis(120), dice);
        fi.setFromValue(0); fi.setToValue(1);

        // ── Build the rolling timeline frame-by-frame ───────────────────────
        // Each keyframe interpolates position (eased), rotation (slowing),
        // and swaps the visible face.
        javafx.animation.Timeline roll = new javafx.animation.Timeline();
        double totalRotation = 0;
        for (int i = 1; i <= frames; i++) {
            final int frameIndex = i;
            double t = i / (double) frames;          // 0..1 progress
            // Ease-out: starts fast, slows down (1 - (1-t)^3)
            double easeT = 1.0 - Math.pow(1.0 - t, 3);

            // Position interpolated along an arc (parabolic vertical drop)
            double posX = startX + (endX - startX) * easeT;
            double posYbase = startY + (endY - startY) * easeT;
            // Add a small upward arc that decays — simulates the dice bouncing
            double bounceOffset = Math.sin(t * Math.PI * 3) * (1 - t) * 35;
            double posY = posYbase - bounceOffset;

            // Rotation — fast at start, slowing. Total ≈ 720° (2 full rotations)
            double rotSpeed = 24 * (1 - t);   // degrees per frame, decreasing
            totalRotation += rotSpeed;

            // Random face (cycling)
            int face = 1 + (int)(Math.random() * 6);
            // In the last 3 frames, settle on the real number
            if (i >= frames - 2) face = finalNumber;

            final double fx = posX;
            final double fy = posY;
            final double rot = totalRotation;
            final int faceFinal = face;
            // Shadow grows + darkens as dice nears the surface
            final double shadowScale = 0.6 + 0.4 * easeT;
            final double shadowOp    = 0.20 + 0.30 * easeT;
            final double shadowX     = posX + 35;
            final double shadowY     = endY + 70;   // shadow stays at the "ground"

            javafx.animation.KeyFrame kf = new javafx.animation.KeyFrame(
                Duration.millis(frameMs * i),
                ev -> {
                    dice.setLayoutX(fx);
                    dice.setLayoutY(fy);
                    dice.setRotate(rot);
                    javafx.scene.layout.StackPane newFace = buildDiceFace(faceFinal);
                    dice.getChildren().setAll(newFace.getChildren());
                    shadow.setCenterX(shadowX);
                    shadow.setCenterY(shadowY);
                    shadow.setScaleX(shadowScale);
                    shadow.setFill(javafx.scene.paint.Color.rgb(0, 0, 0, shadowOp));
                }
            );
            roll.getKeyFrames().add(kf);
        }

        // ── Final landing: lock face, snap rotation, scale pop ──────────────
        javafx.animation.Timeline land = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(Duration.millis(220),
                ev -> {
                    javafx.scene.layout.StackPane finalFace = buildDiceFace(finalNumber);
                    dice.getChildren().setAll(finalFace.getChildren());
                },
                new javafx.animation.KeyValue(dice.scaleXProperty(), 1.25,
                    javafx.animation.Interpolator.EASE_OUT),
                new javafx.animation.KeyValue(dice.scaleYProperty(), 1.25,
                    javafx.animation.Interpolator.EASE_OUT)
            ),
            new javafx.animation.KeyFrame(Duration.millis(380),
                new javafx.animation.KeyValue(dice.scaleXProperty(), 1.0,
                    javafx.animation.Interpolator.EASE_BOTH),
                new javafx.animation.KeyValue(dice.scaleYProperty(), 1.0,
                    javafx.animation.Interpolator.EASE_BOTH)
            )
        );

        // Hold visible, then fade out
        FadeTransition fo = new FadeTransition(Duration.millis(450), dice);
        fo.setFromValue(1); fo.setToValue(0);
        fo.setDelay(Duration.millis(400));
        FadeTransition foShadow = new FadeTransition(Duration.millis(450), shadow);
        foShadow.setFromValue(0.5); foShadow.setToValue(0);
        foShadow.setDelay(Duration.millis(400));
        javafx.animation.ParallelTransition fadeBoth =
            new javafx.animation.ParallelTransition(fo, foShadow);

        javafx.animation.SequentialTransition full = new javafx.animation.SequentialTransition(
            fi, roll, land, fadeBoth);

        full.setOnFinished(ev -> {
            boardGrid.getChildren().removeAll(dice, shadow);
            if (onDone != null) onDone.run();
        });
        full.play();
    }

    /** Build a 70x70 dice face with the given number (1-6) of dots. */
    private javafx.scene.layout.StackPane buildDiceFace(int n) {
        javafx.scene.shape.Rectangle face = new javafx.scene.shape.Rectangle(70, 70);
        face.setArcWidth(14);
        face.setArcHeight(14);
        face.setFill(Color.web("#fafafa"));
        face.setStroke(Color.web("#222244"));
        face.setStrokeWidth(2);
        face.setEffect(new javafx.scene.effect.DropShadow(
            10, Color.web("#000000", 0.7)));

        javafx.scene.layout.StackPane pane = new javafx.scene.layout.StackPane(face);
        pane.setPrefSize(70, 70);

        // Dot positions per face number
        boolean[][] dots = new boolean[3][3];
        switch (n) {
            case 1: dots[1][1] = true; break;
            case 2: dots[0][0] = true; dots[2][2] = true; break;
            case 3: dots[0][0] = true; dots[1][1] = true; dots[2][2] = true; break;
            case 4: dots[0][0] = true; dots[0][2] = true; dots[2][0] = true; dots[2][2] = true; break;
            case 5: dots[0][0] = true; dots[0][2] = true; dots[1][1] = true; dots[2][0] = true; dots[2][2] = true; break;
            case 6: dots[0][0] = true; dots[0][2] = true; dots[1][0] = true; dots[1][2] = true; dots[2][0] = true; dots[2][2] = true; break;
        }
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8); grid.setVgap(8);
        grid.setAlignment(Pos.CENTER);
        grid.setPadding(new Insets(10));
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (dots[r][c]) {
                    javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(6, Color.web("#1a1a1a"));
                    grid.add(dot, c, r);
                } else {
                    javafx.scene.shape.Circle blank = new javafx.scene.shape.Circle(6, Color.TRANSPARENT);
                    grid.add(blank, c, r);
                }
            }
        }
        pane.getChildren().add(grid);
        return pane;
    }

    /**
     * Animate a player or opponent marker hopping from one cell to another
     * for normal dice moves (NOT transport jumps — those use animateTransport).
     * The marker hops one cell at a time so the path is visible.
     *
     * @param fromIdx   starting cell
     * @param toIdx     ending cell
     * @param isPlayer  true if it's the human player
     * @param onDone    callback when the hop finishes
     */
    void animateHop(int fromIdx, int toIdx, boolean isPlayer, Runnable onDone) {
        if (boardGrid == null || fromIdx == toIdx) {
            if (onDone != null) onDone.run();
            return;
        }
        javafx.scene.Node fromNode = findCellNode(fromIdx);
        javafx.scene.Node toNode   = findCellNode(toIdx);
        if (fromNode == null || toNode == null) {
            if (onDone != null) onDone.run();
            return;
        }

        javafx.geometry.Bounds fb = fromNode.getBoundsInParent();
        javafx.geometry.Bounds tb = toNode.getBoundsInParent();
        double startX = fb.getMinX() + fb.getWidth()  / 2;
        double startY = fb.getMinY() + fb.getHeight() / 2;
        double endX   = tb.getMinX() + tb.getWidth()  / 2;
        double endY   = tb.getMinY() + tb.getHeight() / 2;

        // Build a marker that will hop along
        String letter = isPlayer ? "P" : "O";
        String fill   = isPlayer ? pal("gold")   : pal("accent");
        String dark   = isPlayer ? "#7a4a00"     : "#700a1a";
        javafx.scene.layout.StackPane marker = monsterFigure(letter, fill, dark);
        marker.setManaged(false);
        marker.setLayoutX(startX - 12);
        marker.setLayoutY(startY - 12);
        boardGrid.getChildren().add(marker);

        // Hop step by step: build a sequence of small arcs cell-by-cell
        int steps = Math.abs(toIdx - fromIdx);
        if (steps > 12) steps = 12;   // cap for very long moves (cards etc.)
        int direction = (toIdx > fromIdx) ? 1 : -1;
        double stepDurMs = Math.max(80, 350.0 / steps);

        javafx.animation.SequentialTransition seq = new javafx.animation.SequentialTransition();
        for (int i = 1; i <= steps; i++) {
            int targetIdx = fromIdx + i * direction;
            javafx.scene.Node hopNode = findCellNode(targetIdx);
            if (hopNode == null) continue;
            javafx.geometry.Bounds hb = hopNode.getBoundsInParent();
            double tx = hb.getMinX() + hb.getWidth()  / 2;
            double ty = hb.getMinY() + hb.getHeight() / 2;

            // Each hop: translate + arc up (translateY -8) at the midpoint
            javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(
                Duration.millis(stepDurMs), marker);
            slide.setToX(tx - startX);
            slide.setToY(ty - startY);
            // Arc the marker upward then back down using interpolated motion
            slide.setInterpolator(javafx.animation.Interpolator.EASE_BOTH);

            // Add a quick scale "bump" on landing
            javafx.animation.ScaleTransition bump = new javafx.animation.ScaleTransition(
                Duration.millis(stepDurMs / 2), marker);
            bump.setFromX(1.0); bump.setToX(1.15);
            bump.setFromY(1.0); bump.setToY(1.15);
            bump.setAutoReverse(true);
            bump.setCycleCount(2);

            javafx.animation.ParallelTransition step = new javafx.animation.ParallelTransition(slide, bump);
            seq.getChildren().add(step);
        }

        // Fade out at the end
        FadeTransition fo = new FadeTransition(Duration.millis(150), marker);
        fo.setFromValue(1); fo.setToValue(0);
        seq.getChildren().add(fo);

        seq.setOnFinished(ev -> {
            boardGrid.getChildren().remove(marker);
            if (onDone != null) onDone.run();
        });
        seq.play();
    }

    /**
     * Show a short-lived toast popup that auto-dismisses after a few seconds.
     * Used for stationed-monster encounter messages.
     */
    void showToast(String title, String message, String accentHex, int millis) {
        StackPane root = getCurrentRoot();
        if (root == null) return;

        Label titleLbl = new Label(title);
        titleLbl.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        titleLbl.setTextFill(Color.web(accentHex));
        Label msgLbl = new Label(message);
        msgLbl.setFont(Font.font("Arial", 12));
        msgLbl.setTextFill(Color.web("#e8e8f0"));
        msgLbl.setWrapText(true);
        msgLbl.setMaxWidth(320);
        msgLbl.setTextAlignment(TextAlignment.CENTER);

        VBox card = new VBox(6, titleLbl, msgLbl);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(12, 20, 12, 20));
        card.setMaxWidth(360);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setStyle(
            "-fx-background-color:#0c1e30;" +
            "-fx-border-color:" + accentHex + ";-fx-border-width:2;" +
            "-fx-border-radius:8;-fx-background-radius:8;" +
            "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.7),12,0,0,2);"
        );

        StackPane wrap = new StackPane(card);
        wrap.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        wrap.setMouseTransparent(true);   // toast doesn't block clicks
        StackPane.setAlignment(wrap, Pos.TOP_CENTER);
        StackPane.setMargin(wrap, new Insets(70, 0, 0, 0));

        root.getChildren().add(wrap);
        wrap.setOpacity(0);

        FadeTransition fi = new FadeTransition(Duration.millis(220), wrap);
        fi.setFromValue(0); fi.setToValue(1);
        FadeTransition fo = new FadeTransition(Duration.millis(380), wrap);
        fo.setFromValue(1); fo.setToValue(0);
        fo.setDelay(Duration.millis(millis));

        javafx.animation.SequentialTransition seq = new javafx.animation.SequentialTransition(fi, fo);
        seq.setOnFinished(ev -> root.getChildren().remove(wrap));
        seq.play();
    }

    /**
     * Animate a player or opponent marker traveling between two board cells
     * to visualize a Conveyor (a ladder appears, monster climbs it) or
     * a Sock (a snake appears, monster slides down it).
     *
     * The ladder/snake shape is drawn dynamically as JavaFX nodes (no image
     * assets needed) so it scales with the cell size and theme.
     *
     * Timeline:
     *  1. Ladder/Snake fades in over the path             (250 ms)
     *  2. Monster marker travels along the path            (~900 ms)
     *  3. Ladder/Snake fades out                           (350 ms)
     *  4. onDone callback fires
     *
     * @param landingIdx  cell index where the monster first landed
     * @param destIdx     cell index where the monster ends up after transport
     * @param isConveyor  true for forward conveyor (ladder), false for sock (snake)
     * @param isPlayer    true if it's the human player, false for opponent
     * @param onDone      callback invoked when the animation finishes
     */
    void animateTransport(int landingIdx, int destIdx, boolean isConveyor,
                          boolean isPlayer, Runnable onDone) {
        if (boardGrid == null) { if (onDone != null) onDone.run(); return; }

        // Find the cell panes for landing and destination
        javafx.scene.Node landingNode = findCellNode(landingIdx);
        javafx.scene.Node destNode    = findCellNode(destIdx);
        if (landingNode == null || destNode == null) {
            if (onDone != null) onDone.run();
            return;
        }

        javafx.geometry.Bounds lb = landingNode.getBoundsInParent();
        javafx.geometry.Bounds db = destNode   .getBoundsInParent();
        double startX = lb.getMinX() + lb.getWidth()  / 2;
        double startY = lb.getMinY() + lb.getHeight() / 2;
        double endX   = db.getMinX() + db.getWidth()  / 2;
        double endY   = db.getMinY() + db.getHeight() / 2;

        // Build the ladder OR snake shape spanning between the two points
        javafx.scene.Group structure = isConveyor
            ? buildLadder(startX, startY, endX, endY)
            : buildSnake (startX, startY, endX, endY);
        structure.setManaged(false);
        structure.setOpacity(0);

        // Build the traveling marker — same look as the bouncing P/O badge
        String letter = isPlayer ? "P" : "O";
        String fill   = isPlayer ? pal("gold")   : pal("accent");
        String dark   = isPlayer ? "#7a4a00"     : "#700a1a";
        javafx.scene.layout.StackPane traveler = monsterFigure(letter, fill, dark);
        traveler.setManaged(false);
        traveler.setLayoutX(startX - 12);
        traveler.setLayoutY(startY - 12);

        // Add structure underneath the traveler so the marker rides on top
        boardGrid.getChildren().addAll(structure, traveler);

        // ── Phase 1: fade IN the ladder/snake ─────────────────────────────
        javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
            javafx.util.Duration.millis(250), structure);
        fadeIn.setFromValue(0); fadeIn.setToValue(1);

        // ── Phase 2: animate the marker along the path ────────────────────
        javafx.animation.TranslateTransition move = new javafx.animation.TranslateTransition(
            javafx.util.Duration.millis(isConveyor ? 950 : 850), traveler);
        move.setByX(endX - startX);
        move.setByY(endY - startY);

        // Subtle scale pulse for emphasis
        javafx.animation.ScaleTransition pulse = new javafx.animation.ScaleTransition(
            javafx.util.Duration.millis(isConveyor ? 475 : 425), traveler);
        pulse.setFromX(1.0); pulse.setToX(isConveyor ? 1.35 : 1.30);
        pulse.setFromY(1.0); pulse.setToY(isConveyor ? 1.35 : 1.30);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(2);

        // For sock: spin the marker as if being yanked
        javafx.animation.RotateTransition spin = null;
        if (!isConveyor) {
            spin = new javafx.animation.RotateTransition(
                javafx.util.Duration.millis(850), traveler);
            spin.setByAngle(-360);
        }

        javafx.animation.ParallelTransition travelGroup = new javafx.animation.ParallelTransition();
        travelGroup.getChildren().addAll(move, pulse);
        if (spin != null) travelGroup.getChildren().add(spin);

        // ── Phase 3: fade OUT the ladder/snake ────────────────────────────
        javafx.animation.FadeTransition fadeOut = new javafx.animation.FadeTransition(
            javafx.util.Duration.millis(350), structure);
        fadeOut.setFromValue(1); fadeOut.setToValue(0);
        javafx.animation.FadeTransition travelerFade = new javafx.animation.FadeTransition(
            javafx.util.Duration.millis(350), traveler);
        travelerFade.setFromValue(1); travelerFade.setToValue(0);
        javafx.animation.ParallelTransition fadeAll = new javafx.animation.ParallelTransition(
            fadeOut, travelerFade);

        javafx.animation.SequentialTransition full = new javafx.animation.SequentialTransition(
            fadeIn, travelGroup, fadeAll);

        full.setOnFinished(ev -> {
            boardGrid.getChildren().removeAll(structure, traveler);
            if (onDone != null) onDone.run();
        });
        full.play();
    }

    /**
     * Draw a ladder between two points: two parallel rails with horizontal rungs.
     * Looks like a real ladder regardless of orientation (board may zigzag).
     */
    private javafx.scene.Group buildLadder(double x1, double y1, double x2, double y2) {
        javafx.scene.Group g = new javafx.scene.Group();

        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (len < 1) return g;
        // Perpendicular unit vector (for rail spacing)
        double px = -dy / len, py = dx / len;
        double railSpacing = 12;   // distance between the two rails

        // Two rails
        double[] r1x1 = {x1 + px * railSpacing, y1 + py * railSpacing};
        double[] r1x2 = {x2 + px * railSpacing, y2 + py * railSpacing};
        double[] r2x1 = {x1 - px * railSpacing, y1 - py * railSpacing};
        double[] r2x2 = {x2 - px * railSpacing, y2 - py * railSpacing};

        javafx.scene.shape.Line rail1 = new javafx.scene.shape.Line(r1x1[0], r1x1[1], r1x2[0], r1x2[1]);
        javafx.scene.shape.Line rail2 = new javafx.scene.shape.Line(r2x1[0], r2x1[1], r2x2[0], r2x2[1]);
        for (javafx.scene.shape.Line r : new javafx.scene.shape.Line[]{rail1, rail2}) {
            r.setStroke(javafx.scene.paint.Color.web("#c8a05a"));
            r.setStrokeWidth(5);
            r.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            r.setEffect(new javafx.scene.effect.DropShadow(
                6, javafx.scene.paint.Color.web("#000000", 0.55)));
        }

        // Rungs spaced along the rails
        int rungs = Math.max(3, (int) (len / 30));
        for (int i = 1; i < rungs; i++) {
            double t = i / (double) rungs;
            double cx = x1 + dx * t;
            double cy = y1 + dy * t;
            double rx1 = cx + px * railSpacing;
            double ry1 = cy + py * railSpacing;
            double rx2 = cx - px * railSpacing;
            double ry2 = cy - py * railSpacing;
            javafx.scene.shape.Line rung = new javafx.scene.shape.Line(rx1, ry1, rx2, ry2);
            rung.setStroke(javafx.scene.paint.Color.web("#8b5a2b"));
            rung.setStrokeWidth(4);
            rung.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            g.getChildren().add(rung);
        }
        g.getChildren().addAll(rail1, rail2);

        // A small green glow at both ends to make the ladder feel "active"
        javafx.scene.shape.Circle startGlow = new javafx.scene.shape.Circle(x1, y1, 14);
        startGlow.setFill(javafx.scene.paint.Color.web("#7CFC00", 0.35));
        startGlow.setEffect(new javafx.scene.effect.Glow(0.8));
        javafx.scene.shape.Circle endGlow = new javafx.scene.shape.Circle(x2, y2, 14);
        endGlow.setFill(javafx.scene.paint.Color.web("#7CFC00", 0.35));
        endGlow.setEffect(new javafx.scene.effect.Glow(0.8));
        g.getChildren().addAll(startGlow, endGlow);

        return g;
    }

    /**
     * Draw a snake from (x1, y1) to (x2, y2): a wavy thick body with a head
     * at the start and a tapered tail at the end.  Used for sock cells —
     * the monster "slides down" the snake.
     */
    private javafx.scene.Group buildSnake(double x1, double y1, double x2, double y2) {
        javafx.scene.Group g = new javafx.scene.Group();

        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (len < 1) return g;
        double px = -dy / len, py = dx / len;
        double waveAmplitude = 22;   // how wavy the snake is

        // Body — a path with cubic curves wobbling left/right along the route
        javafx.scene.shape.Path body = new javafx.scene.shape.Path();
        body.getElements().add(new javafx.scene.shape.MoveTo(x1, y1));

        int segments = 4;
        for (int i = 1; i <= segments; i++) {
            double t1 = (i - 1) / (double) segments;
            double t2 =  i      / (double) segments;
            double midT = (t1 + t2) / 2;

            double midX = x1 + dx * midT;
            double midY = y1 + dy * midT;
            // Alternating side wave
            double sign = (i % 2 == 0) ? 1 : -1;
            double cX1 = midX + px * waveAmplitude * sign;
            double cY1 = midY + py * waveAmplitude * sign;

            double endXseg = x1 + dx * t2;
            double endYseg = y1 + dy * t2;

            javafx.scene.shape.QuadCurveTo qct = new javafx.scene.shape.QuadCurveTo(
                cX1, cY1, endXseg, endYseg);
            body.getElements().add(qct);
        }
        body.setStroke(javafx.scene.paint.Color.web("#5dbb63"));
        body.setStrokeWidth(14);
        body.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        body.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        body.setFill(null);
        body.setEffect(new javafx.scene.effect.DropShadow(
            8, javafx.scene.paint.Color.web("#000000", 0.7)));

        // Lighter belly stripe on top
        javafx.scene.shape.Path belly = new javafx.scene.shape.Path();
        belly.getElements().addAll(body.getElements());
        belly.setStroke(javafx.scene.paint.Color.web("#a0e0a5"));
        belly.setStrokeWidth(5);
        belly.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        belly.setFill(null);

        g.getChildren().addAll(body, belly);

        // Snake head — slightly larger circle at start with eyes + forked tongue
        javafx.scene.shape.Circle head = new javafx.scene.shape.Circle(x1, y1, 11);
        head.setFill(javafx.scene.paint.Color.web("#4ba456"));
        head.setStroke(javafx.scene.paint.Color.web("#2d5e34"));
        head.setStrokeWidth(2);
        // Eyes
        javafx.scene.shape.Circle eye1 = new javafx.scene.shape.Circle(x1 + px * 4 - dx / len * 2, y1 + py * 4 - dy / len * 2, 2);
        javafx.scene.shape.Circle eye2 = new javafx.scene.shape.Circle(x1 - px * 4 - dx / len * 2, y1 - py * 4 - dy / len * 2, 2);
        eye1.setFill(javafx.scene.paint.Color.BLACK);
        eye2.setFill(javafx.scene.paint.Color.BLACK);
        // Tongue (small red line in front of the head)
        double tongueBaseX = x1 + dx / len * 10;
        double tongueBaseY = y1 + dy / len * 10;
        double tongueTipX  = x1 + dx / len * 18;
        double tongueTipY  = y1 + dy / len * 18;
        javafx.scene.shape.Line tongue = new javafx.scene.shape.Line(
            tongueBaseX, tongueBaseY, tongueTipX, tongueTipY);
        tongue.setStroke(javafx.scene.paint.Color.web("#e94560"));
        tongue.setStrokeWidth(2);
        g.getChildren().addAll(head, eye1, eye2, tongue);

        // Tail — a small triangle at the destination
        double tailBaseX = x2 - dx / len * 8;
        double tailBaseY = y2 - dy / len * 8;
        javafx.scene.shape.Circle tail = new javafx.scene.shape.Circle(tailBaseX, tailBaseY, 5);
        tail.setFill(javafx.scene.paint.Color.web("#5dbb63"));
        g.getChildren().add(tail);

        return g;
    }

    /** Locate the StackPane in boardGrid that represents the given cell index. */
    private javafx.scene.Node findCellNode(int cellIdx) {
        int row = cellIdx / 10;
        int col = cellIdx % 10;
        if (row % 2 == 1) col = 9 - col;
        int displayRow = 9 - row;
        for (javafx.scene.Node n : boardGrid.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            Integer c = GridPane.getColumnIndex(n);
            if (r == null) r = 0;
            if (c == null) c = 0;
            if (r == displayRow && c == col) return n;
        }
        return null;
    }

    private void animateDice() {
        if (diceLabel == null) return;
        ScaleTransition st = new ScaleTransition(Duration.millis(180), diceLabel);
        st.setFromX(1.0); st.setToX(1.4);
        st.setFromY(1.0); st.setToY(1.4);
        st.setAutoReverse(true); st.setCycleCount(2);
        st.play();
    }

    // =========================================================================
    // UNIVERSITY-STYLE POPUPS (in-scene overlays for fullscreen safety)
    // =========================================================================
    void showError(String msg)                              { showPopup("⚠  Invalid Action", msg, pal("accent")); }
    void showAlert(String title, String msg)                { showPopup(title, msg, pal("gold")); }
    void showCardDrawn(String name, String effect, boolean lucky) {
        showPopup(lucky ? "🃏  Lucky Card!" : "🃏  Card Drawn",
            name + "\n" + effect, lucky ? pal("green") : pal("accent"));
    }
    void showFreezeNotice(String monName) { showPopup("❄️  Turn Skipped!",
        monName + " is FROZEN and cannot move this turn.", pal("blue")); }
    void showShieldBlocked(String monName) { showPopup("🛡  Shield Activated!",
        monName + "'s shield blocked an energy loss!", pal("blue")); }

    private void showPopup(String title, String message, String accent) {
        log("⚠ " + title + ": " + message.replace("\n", " "));
        StackPane root = getCurrentRoot();
        if (root == null) return;

        Label titleLbl = new Label(title);
        titleLbl.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        titleLbl.setTextFill(Color.web(accent));

        Label msgLbl = new Label(message);
        msgLbl.setFont(Font.font("Arial", 12));
        msgLbl.setTextFill(Color.web("#e8e8f0"));
        msgLbl.setWrapText(true);
        msgLbl.setPrefWidth(280);
        msgLbl.setTextAlignment(TextAlignment.CENTER);

        Button okBtn = new Button("Continue");
        okBtn.setPrefSize(110, 32);
        okBtn.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        styleBtn(okBtn, accent, "#c0392b");

        VBox card = new VBox(10, titleLbl, msgLbl, okBtn);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(18, 24, 18, 24));
        card.setMaxWidth(330);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setStyle(
            "-fx-background-color:#0c1e30;" +
            "-fx-border-color:" + accent + ";-fx-border-width:2;" +
            "-fx-border-radius:10;-fx-background-radius:10;" +
            "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.7),18,0,0,3);"
        );

        StackPane popup = new StackPane(card);
        popup.setPickOnBounds(false);
        popup.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane overlay = new StackPane(popup);
        overlay.setStyle("-fx-background-color:rgba(0,0,0,0.50);");
        overlay.setPickOnBounds(true);
        root.getChildren().add(overlay);

        // Animate popup-in
        card.setScaleX(0.5); card.setScaleY(0.5);
        card.setOpacity(0);
        ParallelTransition pt = new ParallelTransition();
        ScaleTransition s = new ScaleTransition(Duration.millis(220), card);
        s.setToX(1.0); s.setToY(1.0);
        FadeTransition f = new FadeTransition(Duration.millis(220), card);
        f.setFromValue(0); f.setToValue(1);
        pt.getChildren().addAll(s, f);
        pt.play();

        Runnable close = () -> root.getChildren().remove(overlay);
        okBtn.setOnAction(e -> close.run());
        overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) close.run(); });
    }

    // =========================================================================
    // TOP BAR  (title + turn + dice + sound + theme buttons)
    // =========================================================================
    private HBox buildTopBar(GameModel model) {
        HBox bar = new HBox(14);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 18, 8, 18));
        bar.setStyle("-fx-background-color:" + pal("bg_bar")
            + ";-fx-border-color:" + pal("accent") + ";-fx-border-width:0 0 2 0;");

        Label title = new Label("DooR DasH");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 19));
        title.setTextFill(Color.web(pal("accent")));

        turnLabel = new Label("YOUR TURN ▶");
        turnLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        turnLabel.setTextFill(Color.web(pal("green")));

        diceLabel = new Label("🎲 Dice: —");
        diceLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        diceLabel.setTextFill(Color.web(pal("text_main")));

        cardLabel = new Label("🃏 Cards: 25");
        cardLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        cardLabel.setTextFill(Color.web(pal("text_main")));

        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);

        Label keys = new Label("W=Win  E=Energy  F11=Fullscreen");
        keys.setFont(Font.font("Arial", 10));
        keys.setTextFill(Color.web(pal("text_mute")));

        bar.getChildren().addAll(title, turnLabel, diceLabel, cardLabel, spacer,
            soundBtn(model), settingsBtn(model), themeBtn(model), keys);
        return bar;
    }

    /** Round mute button — reads current state from model. */
    private Button soundBtn(GameModel model) {
        Button b = new Button(model.isMuted() ? "🔇" : "🔊");
        b.setFont(Font.font(15));
        b.setPrefSize(36, 36);
        b.setStyle("-fx-background-color:#1a4a6a;-fx-text-fill:white;"
            + "-fx-background-radius:18;-fx-cursor:hand;");
        b.setOnAction(e -> {
            model.setMuted(!model.isMuted());
            b.setText(model.isMuted() ? "🔇" : "🔊");
        });
        return b;
    }

    /** Round theme button — rebuilds scene to apply theme. */
    private Button themeBtn(GameModel model) {
        Button b = new Button(model.isDarkMode() ? "☀" : "🌙");
        b.setFont(Font.font(15));
        b.setPrefSize(36, 36);
        b.setStyle("-fx-background-color:#4a3a00;-fx-text-fill:white;"
            + "-fx-background-radius:18;-fx-cursor:hand;");
        b.setOnAction(e -> {
            model.toggleDarkMode();
            if (rebuildCurrentScreen != null) rebuildCurrentScreen.run();
        });
        return b;
    }

    /**
     * Settings button — opens a panel with:
     *   - a list of available music tracks (scanned from src/game/engine/sounds/)
     *   - currently playing track name
     *   - volume slider
     */
    private Button settingsBtn(GameModel model) {
        Button b = new Button("⚙");
        b.setFont(Font.font(15));
        b.setPrefSize(36, 36);
        b.setStyle("-fx-background-color:#3a5a3a;-fx-text-fill:white;"
            + "-fx-background-radius:18;-fx-cursor:hand;");
        b.setOnAction(e -> showSettingsOverlay(model));
        return b;
    }

    /**
     * Overlay panel with music picker + volume slider + current track display.
     * Sits on top of the current scene root; closes when "Close" is clicked.
     */
    private void showSettingsOverlay(GameModel model) {
        StackPane sceneRoot = getCurrentRoot();
        if (sceneRoot == null) return;

        // Dim backdrop
        Rectangle backdrop = new Rectangle();
        backdrop.setFill(Color.rgb(0, 0, 0, 0.65));
        backdrop.widthProperty().bind(sceneRoot.widthProperty());
        backdrop.heightProperty().bind(sceneRoot.heightProperty());

        // Title
        Label title = new Label("⚙  Settings");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        title.setTextFill(Color.web(pal("gold")));

        // Now-playing section
        Label nowLabel = new Label("Now playing:");
        nowLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        nowLabel.setTextFill(Color.web(pal("text_sub")));
        Label nowName = new Label(model.getActiveTrackName());
        nowName.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        nowName.setTextFill(Color.web(pal("text_main")));
        nowName.setWrapText(true);
        nowName.setMaxWidth(330);

        // Volume slider
        Label volLabel = new Label("Volume");
        volLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        volLabel.setTextFill(Color.web(pal("text_sub")));
        Slider volSlider = new Slider(0, 1, model.getVolume());
        volSlider.setShowTickLabels(true);
        volSlider.setShowTickMarks(true);
        volSlider.setMajorTickUnit(0.25);
        volSlider.setBlockIncrement(0.05);
        volSlider.setPrefWidth(330);
        volSlider.valueProperty().addListener((o, ov, nv) ->
            model.setVolume(nv.doubleValue()));

        // Music library list
        Label libLabel = new Label("Available Music");
        libLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        libLabel.setTextFill(Color.web(pal("text_sub")));
        ListView<String> trackList = new ListView<>();
        java.util.List<String> names = model.getMusicLibraryNames();
        if (names.isEmpty()) {
            trackList.getItems().add("(no audio files found in game/engine/sounds)");
            trackList.setDisable(true);
        } else {
            trackList.getItems().addAll(names);
            // Select current track if it matches one
            String active = model.getActiveTrackName();
            if (active != null && names.contains(active)) {
                trackList.getSelectionModel().select(active);
            }
        }
        trackList.setPrefHeight(160);
        trackList.setMaxHeight(160);   // lock height so the list scrolls when overflowing
        trackList.setPrefWidth(330);
        trackList.setStyle("-fx-control-inner-background:" + pal("bg_log") + ";"
            + "-fx-background-color:" + pal("bg_log") + ";");

        Button playSelectedBtn = new Button("▶  Play Selected");
        playSelectedBtn.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        styleBtn(playSelectedBtn, "#1a5276", "#2980b9");
        playSelectedBtn.setOnAction(ev -> {
            String selected = trackList.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.startsWith("(")) {
                model.selectMusicTrack(selected);
                nowName.setText(model.getActiveTrackName());
            }
        });

        // Mute toggle button (also available in top bar but convenient here)
        Button muteBtn = new Button(model.isMuted() ? "🔇  Unmute" : "🔊  Mute");
        muteBtn.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        styleBtn(muteBtn, "#3a3a55", "#555577");
        muteBtn.setOnAction(ev -> {
            model.setMuted(!model.isMuted());
            muteBtn.setText(model.isMuted() ? "🔇  Unmute" : "🔊  Mute");
        });

        HBox btnRow = new HBox(10, playSelectedBtn, muteBtn);
        btnRow.setAlignment(Pos.CENTER);

        // Close button
        Button closeBtn = new Button("✕  Close");
        closeBtn.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        styleBtn(closeBtn, pal("accent"), "#c0392b");
        HBox closeRow = new HBox(closeBtn);
        closeRow.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8);
        content.setPadding(new Insets(20, 24, 20, 24));
        content.setMaxWidth(380);
        content.setMaxHeight(380);
        content.setStyle("-fx-background-color:" + pal("bg_panel") + ";"
            + "-fx-border-color:" + pal("gold") + ";-fx-border-width:2;"
            + "-fx-border-radius:10;-fx-background-radius:10;");
        content.getChildren().addAll(
            closeRow, title,
            new Region(), nowLabel, nowName,
            new Region(), volLabel, volSlider,
            new Region(), libLabel, trackList,
            new Region(), btnRow);

        StackPane overlay = new StackPane(backdrop, content);
        overlay.setPickOnBounds(true);
        sceneRoot.getChildren().add(overlay);

        Runnable close = () -> sceneRoot.getChildren().remove(overlay);
        closeBtn.setOnAction(ev -> close.run());
        backdrop.setOnMouseClicked(ev -> close.run());

        // Fade in
        overlay.setOpacity(0);
        FadeTransition fi = new FadeTransition(Duration.millis(220), overlay);
        fi.setFromValue(0); fi.setToValue(1);
        fi.play();
    }

    /** Cleaner ⓘ button — circular badge with shadow. */
    private Button buildInfoButton() {
        Button btn = new Button("?");
        btn.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        btn.setPrefSize(38, 38);
        btn.setStyle(
            "-fx-background-color:#e94560;-fx-text-fill:white;"
            + "-fx-background-radius:19;-fx-border-color:rgba(255,255,255,0.4);"
            + "-fx-border-width:2;-fx-border-radius:19;-fx-cursor:hand;"
            + "-fx-effect:dropshadow(gaussian,rgba(233,69,96,0.7),10,0,0,0);"
        );
        return btn;
    }

    // =========================================================================
    // BOTTOM ACTION BAR
    // =========================================================================
    private VBox buildBottomSection() {
        VBox section = new VBox(8);
        section.setPadding(new Insets(8, 14, 12, 14));
        section.setStyle("-fx-background-color:" + pal("bg_bar")
            + ";-fx-border-color:" + pal("accent") + ";-fx-border-width:2 0 0 0;");

        powerupBtn = new Button("⚡  Use Powerup  (500)");
        rollBtn    = new Button("🎲  Roll Dice");
        powerupBtn.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        rollBtn   .setFont(Font.font("Arial", FontWeight.BOLD, 13));
        powerupBtn.setPrefSize(240, 38);
        rollBtn   .setPrefSize(180, 38);
        styleBtn(powerupBtn, "#5b2382", "#8e44ad");
        styleBtn(rollBtn,    "#154360", "#2980b9");

        HBox btnRow = new HBox(14, powerupBtn, rollBtn);
        btnRow.setAlignment(Pos.CENTER);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefHeight(105);
        logArea.setStyle("-fx-control-inner-background:" + pal("bg_log")
            + ";-fx-text-fill:" + pal("text_main") + ";-fx-font-size:12px;"
            + "-fx-border-color:#222244;-fx-border-width:1;");
        // Preserve log history across scene rebuilds (e.g., theme switch)
        if (logBuffer.length() > 0) logArea.setText(logBuffer.toString());
        // Auto-scroll to bottom
        logArea.textProperty().addListener((o,ov,nv) -> {
            javafx.application.Platform.runLater(() ->
                logArea.setScrollTop(Double.MAX_VALUE));
        });

        section.getChildren().addAll(btnRow, logArea);
        return section;
    }

    // =========================================================================
    // MONSTER SIDE PANEL  (compact, sectioned, animated)
    // =========================================================================
    private VBox buildMonsterPanel(Monster m, String label, String accent, boolean isPlayer) {
        VBox box = new VBox(0);
        box.setPrefWidth(200);
        box.setMaxWidth(200);
        box.setMinWidth(200);
        box.setAlignment(Pos.TOP_CENTER);
        box.setStyle("-fx-background-color:" + pal("bg_panel") + ";");

        // Header bar
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(9, 8, 9, 8));
        header.setStyle("-fx-background-color:" + accent + ";");
        Label headerLbl = new Label(label);
        headerLbl.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        headerLbl.setTextFill(Color.WHITE);
        Label typeBadge = new Label(monType(m));
        typeBadge.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        typeBadge.setTextFill(Color.WHITE);
        typeBadge.setStyle("-fx-background-color:rgba(0,0,0,0.30);-fx-padding:2 6 2 6;-fx-background-radius:4;");
        Region hs = new Region(); HBox.setHgrow(hs, Priority.ALWAYS);
        header.getChildren().addAll(headerLbl, hs, typeBadge);

        // Image area
        StackPane imgPane = new StackPane();
        imgPane.setPrefHeight(140);
        imgPane.setMinHeight(140);
        imgPane.setStyle("-fx-background-color:" + pal("bg_card") + ";");

        ImageView iv = new ImageView();
        iv.setFitWidth(130); iv.setFitHeight(130);
        iv.setPreserveRatio(true);
        loadImage(iv, m.getName());

        Rectangle ring = new Rectangle(138, 138);
        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(Color.web(accent));
        ring.setStrokeWidth(2);
        ring.setArcWidth(8); ring.setArcHeight(8);

        imgPane.getChildren().addAll(iv, ring);
        if (isPlayer) playerImage = iv; else oppImage = iv;

        // Info rows
        Label nameLbl     = pLbl("👤  " + m.getName(),            pal("text_main"), 12, true);
        Label typeLbl     = pLbl(monType(m),                      accent,           11, true);
        Label origRoleLbl = pLbl("Original: " + m.getOriginalRole(), pal("text_mute"), 10, false);
        Label curRoleLbl  = pLbl("Current:  " + m.getRole(),        pal("text_main"), 11, true);
        Label energyLbl   = pLbl("⚡  " + m.getEnergy(),           pal("gold"),       16, true);
        Label posLbl      = pLbl("📍  Cell " + m.getPosition(),    pal("text_mute"), 11, false);
        Label shieldLbl   = statusBadge("🛡","SHIELD","None","#3a3a55",pal("text_main"));
        Label frozenLbl   = statusBadge("❄","FROZEN","No",  "#3a3a55",pal("text_main"));
        Label confLbl     = statusBadge("😵","CONFUSION", "None","#3a3a55",pal("text_main"));
        Label speedLbl    = statusBadge("🏃","SPEED", "Normal","#3a3a55",pal("text_main"));

        VBox nameBlock = new VBox(2, nameLbl, typeLbl);
        nameBlock.setPadding(new Insets(7, 9, 5, 9));
        nameBlock.setStyle("-fx-background-color:" + pal("bg_card") + ";");

        VBox roleBlock = new VBox(3, pSecLbl("ROLE"), origRoleLbl, curRoleLbl);
        roleBlock.setPadding(new Insets(5, 9, 5, 9));
        roleBlock.setStyle("-fx-background-color:" + pal("bg_card2") + ";");

        VBox energyBlock = new VBox(3, pSecLbl("ENERGY / POSITION"), energyLbl, posLbl);
        energyBlock.setPadding(new Insets(5, 9, 5, 9));
        energyBlock.setStyle("-fx-background-color:" + pal("bg_card") + ";");

        VBox statusBlock = new VBox(4, pSecLbl("STATUS"),
                                    shieldLbl, frozenLbl, confLbl, speedLbl);
        statusBlock.setPadding(new Insets(5, 9, 8, 9));
        statusBlock.setStyle("-fx-background-color:" + pal("bg_card2") + ";");

        VBox info = new VBox(0,
            hDiv(accent), nameBlock,
            hDiv(pal("border")), roleBlock,
            hDiv(pal("border")), energyBlock,
            hDiv(pal("border")), statusBlock);

        if (isPlayer) {
            playerNameLbl=nameLbl; playerTypeLbl=typeLbl;
            playerOrigRoleLbl=origRoleLbl; playerCurRoleLbl=curRoleLbl;
            playerEnergyLbl=energyLbl; playerPosLbl=posLbl;
            playerShieldLbl=shieldLbl; playerFrozenLbl=frozenLbl;
            playerConfLbl=confLbl; playerSpeedLbl=speedLbl;
        } else {
            oppNameLbl=nameLbl; oppTypeLbl=typeLbl;
            oppOrigRoleLbl=origRoleLbl; oppCurRoleLbl=curRoleLbl;
            oppEnergyLbl=energyLbl; oppPosLbl=posLbl;
            oppShieldLbl=shieldLbl; oppFrozenLbl=frozenLbl;
            oppConfLbl=confLbl; oppSpeedLbl=speedLbl;
        }

        box.getChildren().addAll(header, imgPane, info);
        return box;
    }

    private void updatePanel(Monster m, Label nameLbl, Label typeLbl,
                              Label origRoleLbl, Label curRoleLbl,
                              Label energyLbl, Label posLbl,
                              Label shieldLbl, Label frozenLbl, Label confLbl,
                              Label speedLbl, ImageView iv) {
        nameLbl.setText("👤  " + m.getName());
        typeLbl.setText(monType(m));
        origRoleLbl.setText("Original: " + m.getOriginalRole());

        boolean confused = m.isConfused();
        curRoleLbl.setText("Current:  " + m.getRole() + (confused ? " ⚠" : ""));
        curRoleLbl.setTextFill(confused ? Color.web(pal("purple")) :
            (m.getRole() == Role.SCARER ? Color.web("#e74c3c") : Color.web("#2ecc71")));

        energyLbl.setText("⚡  " + m.getEnergy());
        posLbl   .setText("📍  Cell " + m.getPosition());

        updBadge(shieldLbl,
            m.isShielded() ? "ACTIVE" : "None",
            m.isShielded() ? "#1a4a7a" : "#3a3a55",
            m.isShielded() ? "#7ec8f0" : pal("text_main"));
        updBadge(frozenLbl,
            m.isFrozen() ? "YES!" : "No",
            m.isFrozen() ? "#1a3a5a" : "#3a3a55",
            m.isFrozen() ? "#aaddff" : pal("text_main"));
        
        if (confused){
        	int n = m.getConfusionTurns();
        	updBadge(confLbl, n + (n == 1 ? " Turn" : " Turns"), "#3a1a5a", "#cc88ff");
        }
        else          updBadge(confLbl, "None","#3a3a55",pal("text_main"));
        String spd = speedStatus(m);
        if (!spd.isEmpty()) updBadge(speedLbl, spd.replaceAll("^\\S+\\s*",""),"#1a3a1a","#88ffaa");
        else                updBadge(speedLbl, "Normal","#3a3a55",pal("text_main"));

        if (iv != null && iv.getImage() == null) loadImage(iv, m.getName());
    }

    // =========================================================================
    // BOARD CELL  (with animated monster figures, redesigned doors)
    // =========================================================================
    private StackPane buildCellPane(Cell cell, int index, Monster player, Monster opponent) {
        StackPane pane = new StackPane();
        pane.setPrefSize(CELL_SIZE, CELL_SIZE);
        pane.setMinSize(CELL_SIZE, CELL_SIZE);
        pane.setMaxSize(CELL_SIZE, CELL_SIZE);
        pane.setClip(new Rectangle(CELL_SIZE, CELL_SIZE));

        String border = pal("border");
        double bw = 1;
        if      (player.getPosition()   == index) { border = pal("gold");   bw = 3; }
        else if (opponent.getPosition() == index) { border = pal("accent"); bw = 3; }

        pane.setStyle("-fx-border-color:" + border + ";-fx-border-width:" + bw
            + ";-fx-border-radius:3;-fx-background-radius:3;");

        if (index == 0)  { addFullImage(pane,"Start","#1a2a1a");  addIndexLabel(pane,index); addMonsterMarkers(pane,index,player,opponent); return pane; }
        // Cell 99 is a DoorCell in the engine — let it fall through to the
        // DoorCell block below so it shows the S/L pill and Energy badge.
        // We just force its image to BooDoor.png by pre-seeding doorImageMap.
        if (index == 99 && cell instanceof DoorCell) {
            doorImageMap.put(99, "BooDoor");
            // fall through (no return) so DoorCell handler renders it
        }

        if (cell instanceof CardCell) {
            addFullImage(pane, "Card", "#3d1a00");
            addIndexLabel(pane, index);
            addMonsterMarkers(pane, index, player, opponent);
            return pane;
        }

        // ── Contamination Sock ─────────────────────────────────────────────
        // Tries to load Sock.png. If found, shows the image with the jump
        // value as a small badge on top. If not, falls back to the original
        // coloured display + 🧦 icon + jump value badge.
        if (cell instanceof ContaminationSock) {
            ContaminationSock sock = (ContaminationSock) cell;
            int jump = sock.getEffect();  // negative (jump backward)

            ImageView iv = new ImageView();
            iv.setFitWidth(CELL_SIZE); iv.setFitHeight(CELL_SIZE); iv.setPreserveRatio(false);
            try { loadImage(iv, "Sock"); } catch (Exception ignored) {}

            if (iv.getImage() != null) {
                // Image found — show it
                pane.getChildren().add(iv);
            } else {
                // No image — fallback colour + emoji
                pane.setStyle(pane.getStyle() + "-fx-background-color:#3d2800;");
                Label icon = new Label("🧦");
                icon.setFont(Font.font(20));
                pane.getChildren().add(icon);
            }

            addIndexLabel(pane, index);

            // Jump value badge (bottom-center) — always shown
            Label jumpLbl = new Label(String.valueOf(jump));  // already negative, e.g. "-3"
            jumpLbl.setFont(Font.font("Arial", FontWeight.BOLD, 10));
            jumpLbl.setTextFill(Color.web("#ff8a65"));
            jumpLbl.setStyle("-fx-background-color:rgba(0,0,0,0.78);"
                + "-fx-padding:1 5 1 5;-fx-background-radius:3;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),3,0,0,0);");
            StackPane.setAlignment(jumpLbl, Pos.BOTTOM_CENTER);
            StackPane.setMargin(jumpLbl, new Insets(0, 0, 2, 0));
            pane.getChildren().add(jumpLbl);

            addMonsterMarkers(pane, index, player, opponent);
            return pane;
        }

        // ── Conveyor Belt ──────────────────────────────────────────────────
        // Tries to load ConveyorBelt.png. If found, shows the image with the
        // jump value + ⏩ arrow on top. If not, falls back to the original
        // green background + ⏩ icon + jump value badge.
        if (cell instanceof ConveyorBelt) {
            ConveyorBelt belt = (ConveyorBelt) cell;
            int jump = belt.getEffect();  // positive (jump forward)

            ImageView iv = new ImageView();
            iv.setFitWidth(CELL_SIZE); iv.setFitHeight(CELL_SIZE); iv.setPreserveRatio(false);
            try { loadImage(iv, "ConveyorBelt"); } catch (Exception ignored) {}

            if (iv.getImage() != null) {
                pane.getChildren().add(iv);
            } else {
                pane.setStyle(pane.getStyle() + "-fx-background-color:" + pal("cell_conv") + ";");
            }

            addIndexLabel(pane, index);

            // ⏩ arrows symbol (top-center) — always visible
            Label arrows = new Label("⏩");
            arrows.setFont(Font.font(18));
            arrows.setStyle("-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),3,0,0,0);");
            StackPane.setAlignment(arrows, Pos.TOP_CENTER);
            StackPane.setMargin(arrows, new Insets(2, 0, 0, 0));
            pane.getChildren().add(arrows);

            // Jump value badge (bottom-center) — always shown
            Label jumpLbl = new Label("+" + jump);
            jumpLbl.setFont(Font.font("Arial", FontWeight.BOLD, 10));
            jumpLbl.setTextFill(Color.web("#7CFC00"));
            jumpLbl.setStyle("-fx-background-color:rgba(0,0,0,0.78);"
                + "-fx-padding:1 5 1 5;-fx-background-radius:3;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),3,0,0,0);");
            StackPane.setAlignment(jumpLbl, Pos.BOTTOM_CENTER);
            StackPane.setMargin(jumpLbl, new Insets(0, 0, 2, 0));
            pane.getChildren().add(jumpLbl);

            addMonsterMarkers(pane, index, player, opponent);
            return pane;
        }

        if (cell instanceof MonsterCell) {
            MonsterCell mc = (MonsterCell) cell;
            String monName = mc.getCellMonster().getName();
            ImageView iv = new ImageView();
            iv.setFitWidth(CELL_SIZE); iv.setFitHeight(CELL_SIZE); iv.setPreserveRatio(false);
            loadImage(iv, monName);
            if (iv.getImage() != null) {
                pane.getChildren().add(iv);
                pane.getChildren().add(new Rectangle(CELL_SIZE, CELL_SIZE, Color.rgb(0, 0, 30, 0.40)));
            } else {
                pane.setStyle(pane.getStyle() + "-fx-background-color:#16213e;");
            }
            addIndexLabel(pane, index);
            // Role badge (top-right) — same style as door cells so you can tell
            // a stationed monster's team at a glance
            boolean mcScarer = mc.getCellMonster().getRole() == Role.SCARER;
            Label roleBadge = new Label(mcScarer ? "S" : "L");
            roleBadge.setFont(Font.font("Arial", FontWeight.BOLD, 10));
            roleBadge.setTextFill(Color.WHITE);
            roleBadge.setStyle(
                "-fx-background-color:" + (mcScarer ? "rgba(220,30,30,0.95)" : "rgba(30,180,60,0.95)") + ";" +
                "-fx-padding:0 4 0 4;-fx-background-radius:3;" +
                "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),3,0,0,0);"
            );
            StackPane.setAlignment(roleBadge, Pos.TOP_RIGHT);
            StackPane.setMargin(roleBadge, new Insets(2, 2, 0, 0));
            pane.getChildren().add(roleBadge);

            Label nl = new Label(shortName(monName));
            nl.setFont(Font.font("Arial", FontWeight.BOLD, 8));
            nl.setTextFill(Color.web("#dda0dd"));
            nl.setStyle("-fx-background-color:rgba(0,0,0,0.65);-fx-padding:1 3 1 3;");
            StackPane.setAlignment(nl, Pos.BOTTOM_CENTER);
            pane.getChildren().add(nl);
            addMonsterMarkers(pane, index, player, opponent);
            return pane;
        }

        if (cell instanceof DoorCell) {
            DoorCell door  = (DoorCell) cell;
            boolean  scarer = door.getRole() == Role.SCARER;

            if (!doorImageMap.containsKey(index))
                doorImageMap.put(index, DOOR_IMAGE_NAMES[rng.nextInt(DOOR_IMAGE_NAMES.length)]);

            ImageView iv = new ImageView();
            iv.setFitWidth(CELL_SIZE); iv.setFitHeight(CELL_SIZE); iv.setPreserveRatio(false);
            String doorImg = doorImageMap.get(index);
            try { loadImage(iv, doorImg); } catch (Exception ignored) {}

            if (iv.getImage() != null) {
                // Image found — show it WITHOUT any colour overlay
                pane.getChildren().add(iv);
            } else {
                // Image missing — fallback to a coloured background + name label
                String fb = scarer ? "#5a0000" : "#004d1a";
                pane.setStyle(pane.getStyle() + "-fx-background-color:" + fb + ";");
                Label nameLbl = new Label(doorImg);
                nameLbl.setFont(Font.font("Arial", FontWeight.BOLD, 7));
                nameLbl.setTextFill(Color.web("#cccccc"));
                nameLbl.setStyle("-fx-background-color:rgba(0,0,0,0.55);-fx-padding:1 3 1 3;");
                StackPane.setAlignment(nameLbl, Pos.CENTER);
                pane.getChildren().add(nameLbl);
            }

            addIndexLabel(pane, index);

            // Role badge — tiny single-letter chip at TOP-RIGHT (out of door-image way)
            Label roleBadge = new Label(scarer ? "S" : "L");
            roleBadge.setFont(Font.font("Arial", FontWeight.BOLD, 10));
            roleBadge.setTextFill(Color.WHITE);
            roleBadge.setStyle(
                "-fx-background-color:" + (scarer ? "rgba(220,30,30,0.95)" : "rgba(30,180,60,0.95)") + ";" +
                "-fx-padding:0 4 0 4;-fx-background-radius:3;" +
                "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),3,0,0,0);"
            );
            StackPane.setAlignment(roleBadge, Pos.TOP_RIGHT);
            StackPane.setMargin(roleBadge, new Insets(2, 2, 0, 0));
            pane.getChildren().add(roleBadge);

            // Energy badge — small pill at BOTTOM-CENTER (does not cover door art)
            Label eVal = new Label("⚡ " + door.getEnergy());
            eVal.setFont(Font.font("Arial", FontWeight.BOLD, 9));
            eVal.setTextFill(door.isActivated() ? Color.web("#bbbbbb") : Color.web("#ffd96b"));
            eVal.setStyle("-fx-background-color:rgba(0,0,0,0.78);"
                + "-fx-padding:1 5 1 5;-fx-background-radius:3;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),3,0,0,0);");
            StackPane.setAlignment(eVal, Pos.BOTTOM_CENTER);
            StackPane.setMargin(eVal, new Insets(0, 0, 2, 0));
            pane.getChildren().add(eVal);

            addMonsterMarkers(pane, index, player, opponent);

            // Activated overlay LAST — full cell dim + ✕ mark
            if (door.isActivated()) {
                pane.getChildren().add(new Rectangle(CELL_SIZE, CELL_SIZE, Color.rgb(0,0,0,0.55)));
                Label used = new Label("✕");
                used.setFont(Font.font("Arial", FontWeight.BOLD, 20));
                used.setTextFill(Color.web("#bbbbbb"));
                used.setStyle("-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.9),3,0,0,0);");
                pane.getChildren().add(used);
            }

            return pane;
        }

        // Normal cell — try loading NormalCell.png if present, else friendly yellow
        ImageView normalIv = new ImageView();
        normalIv.setFitWidth(CELL_SIZE); normalIv.setFitHeight(CELL_SIZE); normalIv.setPreserveRatio(false);
        try { loadImage(normalIv, "NormalCell"); } catch (Exception ignored) {}
        if (normalIv.getImage() != null) {
            pane.getChildren().add(normalIv);
        } else {
            pane.setStyle(pane.getStyle() + "-fx-background-color:" + pal("cell_norm") + ";");
        }
        addIndexLabel(pane, index);
        addMonsterMarkers(pane, index, player, opponent);
        return pane;
    }

    private void addFullImage(StackPane pane, String name, String fallback) {
        ImageView iv = new ImageView();
        iv.setFitWidth(CELL_SIZE); iv.setFitHeight(CELL_SIZE); iv.setPreserveRatio(false);
        loadImage(iv, name);
        if (iv.getImage() != null) pane.getChildren().add(iv);
        else pane.setStyle(pane.getStyle() + "-fx-background-color:" + fallback + ";");
    }

    private void addIndexLabel(StackPane pane, int index) {
        Label lbl = new Label(String.valueOf(index));
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        lbl.setTextFill(Color.WHITE);
        lbl.setStyle("-fx-background-color:rgba(0,0,0,0.60);-fx-padding:0 3 0 3;-fx-background-radius:3;");
        StackPane.setAlignment(lbl, Pos.TOP_LEFT);
        StackPane.setMargin(lbl, new Insets(2, 0, 0, 2));
        pane.getChildren().add(lbl);
    }

    /**
     * Animated bouncing figures instead of plain P/O letters.
     * Player = gold circle with P, Opponent = red circle with O.
     * Both have shadow + a continuous gentle bounce animation.
     */
    private void addMonsterMarkers(StackPane pane, int index, Monster player, Monster opponent) {
        if (player.getPosition() == index) {
            StackPane fig = monsterFigure("P", pal("gold"), "#7a4a00");
            StackPane.setAlignment(fig, Pos.BOTTOM_LEFT);
            StackPane.setMargin(fig, new Insets(0, 0, 3, 3));
            pane.getChildren().add(fig);
            addBounce(fig);
        }
        if (opponent.getPosition() == index) {
            StackPane fig = monsterFigure("O", pal("accent"), "#700a1a");
            StackPane.setAlignment(fig, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(fig, new Insets(0, 3, 3, 0));
            pane.getChildren().add(fig);
            addBounce(fig);
        }
    }

    private StackPane monsterFigure(String letter, String fillHex, String darkHex) {
        Circle bg = new Circle(12);
        bg.setFill(Color.web(fillHex));
        bg.setStroke(Color.WHITE);
        bg.setStrokeWidth(2);
        Label lbl = new Label(letter);
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        lbl.setTextFill(Color.web(darkHex));
        StackPane fig = new StackPane(bg, lbl);
        fig.setStyle("-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),5,0,0,1);");
        return fig;
    }

    private void addBounce(javafx.scene.Node node) {
        TranslateTransition t = new TranslateTransition(Duration.millis(600), node);
        t.setByY(-3);
        t.setAutoReverse(true);
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
    }

    private void addPulse(javafx.scene.Node node) {
        ScaleTransition s = new ScaleTransition(Duration.millis(1500), node);
        s.setFromX(1.0); s.setFromY(1.0);
        s.setToX(1.04);  s.setToY(1.04);
        s.setAutoReverse(true);
        s.setCycleCount(Timeline.INDEFINITE);
        s.play();
    }

    // =========================================================================
    // WIN STATS BOX
    // =========================================================================
    private VBox buildFinalStats(Monster m, String label, boolean isWinner) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(18));
        box.setStyle(
            "-fx-background-color:" + (isWinner ? "#0d2a0d" : "#2a0d0d") + ";" +
            "-fx-border-color:" + (isWinner ? "#2ecc71" : "#e74c3c") + ";" +
            "-fx-border-width:2;-fx-border-radius:10;-fx-background-radius:10;"
        );
        Label hdr = new Label(label + (isWinner ? " 🏆" : ""));
        hdr.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        hdr.setTextFill(Color.web(isWinner ? "#2ecc71" : "#e74c3c"));
        Label nm = new Label(m.getName()); nm.setFont(Font.font("Arial",FontWeight.BOLD,14)); nm.setTextFill(Color.WHITE);
        Label rl = new Label("Role: " + m.getOriginalRole()); rl.setFont(Font.font("Arial",12)); rl.setTextFill(Color.web("#aaaaaa"));
        Label en = new Label("⚡ Final Energy: " + m.getEnergy()); en.setFont(Font.font("Arial",FontWeight.BOLD,15)); en.setTextFill(Color.web("#f5a623"));
        box.getChildren().addAll(hdr, nm, rl, en);
        return box;
    }

    // =========================================================================
    // LEGEND  (left column, below player panel)
    // =========================================================================
    private VBox buildLegendBox() {
        VBox box = new VBox(0);
        box.setStyle("-fx-background-color:" + pal("bg_legend") + ";");
        box.setPrefWidth(200);

        Label header = new Label("🗺  BOARD LEGEND");
        header.setFont(Font.font("Arial", FontWeight.BOLD, 11));
        header.setTextFill(Color.web(pal("gold")));
        header.setPadding(new Insets(6, 8, 4, 8));
        header.setStyle("-fx-background-color:" + pal("bg_bar")
            + ";-fx-border-color:" + pal("gold") + ";-fx-border-width:0 0 1 0;");
        header.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().add(header);

        Object[][] entries = {
            {null, "⬛ Start (Cell 0)",          "#2ecc71"},
            {null, "👻 Boo's Door (Cell 99)",    "#cc88ff"},
            {null, "🃏 Card – Draw a card",      "#f5a623"},
            {null, "🧦 Sock – Jump Back",        "#e67e22"},
            {null, "⏩ Conveyor – Jump Forward", "#2ecc71"},
            {null, "🚪 S Door – Scarer team",    "#e74c3c"},
            {null, "🚪 L Door – Laugher team",   "#27ae60"},
            {null, "👾 Monster – PowerUP/Swap",  "#cc88ff"},
            {null, "⬜ Normal – Nothing",        "#aaaaaa"},
        };

        for (Object[] e : entries) {
            String labelTxt = (String) e[1];
            String accent   = (String) e[2];
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(3, 6, 3, 6));
            row.setStyle("-fx-border-color:" + pal("border") + ";-fx-border-width:0 0 1 0;");
            String firstWord = labelTxt.substring(0, labelTxt.indexOf(' '));
            Label ph = new Label(firstWord);
            ph.setFont(Font.font("Arial", 13));
            ph.setTextFill(Color.web(accent));
            ph.setMinWidth(22);
            Label lbl = new Label(labelTxt.replaceAll("^\\S+\\s", "").trim());
            lbl.setFont(Font.font("Arial", 13));
            lbl.setTextFill(Color.web(accent));
            lbl.setWrapText(true);
            lbl.setMaxWidth(155);
            row.getChildren().addAll(ph, lbl);
            box.getChildren().add(row);
        }
        return box;
    }

    // =========================================================================
    // INSTRUCTIONS CONTENT
    // =========================================================================
    private javafx.scene.Node[] buildInstructionNodes() {
        java.util.List<javafx.scene.Node> n = new java.util.ArrayList<>();
        n.add(iSec("🏆  TO WIN"));
        n.add(iBody("Land on cell 99 with 1000+ energy."));
        n.add(iSec("🔁  ONE TURN"));
        n.add(iBody("1. Roll die → move that many cells. Opponent there? Stay + roll again."));
        n.add(iBody("2. Trigger cell effect. 3. Other player goes."));
        n.add(iSec("📍  CELLS"));
        for (String[] r : new String[][]{
            {"🚪","Door","Same role → team gains energy. Opposite → all lose. Shield blocks. One use only."},
            {"👾","Monster","Same role → free powerup. Opposite + more energy → swap."},
            {"⏩","Conveyor","Jump forward."},
            {"🧦","Sock","Jump back −100 energy."},
            {"🃏","Card","Draw and apply."},
            {"⬜","Normal","Nothing."}})
            n.add(iRow(r[0], r[1], r[2]));
        n.add(iSec("🃏  CARDS"));
        for (String s : new String[]{
            "Swapper – swap if behind.",
            "Start Over – back to cell 0.",
            "Steal – take 50/100/150 energy.",
            "Shield – block 1 loss.",
            "Confusion – swap roles 2–3 turns."})
            n.add(iBody("• " + s));
        n.add(iSec("⚡  MONSTERS"));
        for (String[] r : new String[][]{
            {"Dasher",     "2× speed",          "3× for 3 turns"},
            {"Dynamo",     "±×2",               "Skip opponent"},
            {"Multitasker","½ speed ±200",      "Normal 2 turns"},
            {"Schemer",    "±10",                "Steal from all"}})
            n.add(iAbil(r[0], r[1], r[2]));
        Label f = new Label("Roll the die and scare (or laugh) your way to victory! 👹😆");
        f.setFont(Font.font("Arial",FontWeight.BOLD,12));
        f.setTextFill(Color.web(pal("gold")));
        f.setPadding(new Insets(12, 0, 0, 0));
        f.setWrapText(true);
        n.add(f);
        return n.toArray(new javafx.scene.Node[0]);
    }
    private Label iSec(String t) { Label l=new Label(t); l.setFont(Font.font("Arial",FontWeight.BOLD,15)); l.setTextFill(Color.web(pal("gold"))); l.setPadding(new Insets(12,0,3,0)); l.setWrapText(true); return l; }
    private Label iBody(String t){ Label l=new Label(t); l.setFont(Font.font("Arial",12)); l.setTextFill(Color.web(pal("text_main"))); l.setWrapText(true); l.setMaxWidth(740); return l; }
    private HBox  iRow(String ic,String nm,String ef){ Label a=new Label(ic+"  "+nm); a.setFont(Font.font("Arial",FontWeight.BOLD,12)); a.setTextFill(Color.web("#aaddff")); a.setMinWidth(100); Label b=new Label(ef); b.setFont(Font.font("Arial",11)); b.setTextFill(Color.web(pal("text_main"))); b.setWrapText(true); HBox h=new HBox(8,a,b); h.setPadding(new Insets(1,0,1,12)); return h; }
    private HBox  iAbil(String t,String p,String pu){ Label a=new Label(t); a.setFont(Font.font("Arial",FontWeight.BOLD,12)); a.setTextFill(Color.web("#aaddff")); a.setMinWidth(90); Label b=new Label("Always: "+p); b.setFont(Font.font("Arial",11)); b.setTextFill(Color.web(pal("text_main"))); b.setMinWidth(170); Label c=new Label("Powerup: "+pu); c.setFont(Font.font("Arial",11)); c.setTextFill(Color.web(pal("gold"))); HBox h=new HBox(8,a,b,c); h.setPadding(new Insets(1,0,1,12)); return h; }

    // =========================================================================
    // IMAGE LOADING — case-insensitive directory walk, finds fungus.png etc.
    // =========================================================================
    private boolean loadImageDebugDone = false;

    /**
     * Load an image into the ImageView using a persistent cache.
     * The image file is read from disk only ONCE per filename; subsequent
     * calls reuse the same Image object. This eliminates the heap exhaustion
     * that caused refreshBoard to OutOfMemoryError after a few turns.
     */
    private void loadImage(ImageView iv, String name) {
        if (name == null || name.isEmpty()) return;
        name = name.trim();

        // Check cache first — avoid reloading the same file from disk
        javafx.scene.image.Image cached = imageCache.get(name.toLowerCase());
        if (cached != null) {
            if (cached != MISSING_IMAGE_SENTINEL) iv.setImage(cached);
            return;
        }

        // Build candidate filenames
        java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>();
        cands.add(name + ".png");
        cands.add(name.toLowerCase() + ".png");
        String snake = name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        cands.add(snake + ".png");
        String safe = name.toLowerCase()
            .replaceAll("[^a-z0-9]","_").replaceAll("_+","_").replaceAll("^_|_$","") + ".png";
        cands.add(safe);
        if (name.length() > 0) {
            cands.add(Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase() + ".png");
        }

        // Build directory list — walk up from class location
        // Includes the new package location: src/game/engine/images/
        java.util.LinkedHashSet<java.io.File> dirs = new java.util.LinkedHashSet<>();
        try {
            java.io.File loc = new java.io.File(
                getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            java.io.File cur = loc;
            for (int i = 0; i < 6; i++) {
                if (cur == null) break;
                dirs.add(cur);
                dirs.add(new java.io.File(cur, "src"));
                dirs.add(new java.io.File(cur, "images"));
                dirs.add(new java.io.File(cur, "src/images"));
                dirs.add(new java.io.File(cur, "game/engine/images"));
                dirs.add(new java.io.File(cur, "src/game/engine/images"));
                cur = cur.getParentFile();
            }
        } catch (Exception ignored) {}
        java.io.File wd = new java.io.File(".");
        for (int i = 0; i < 4; i++) {
            if (wd == null) break;
            dirs.add(wd);
            dirs.add(new java.io.File(wd, "src"));
            dirs.add(new java.io.File(wd, "images"));
            dirs.add(new java.io.File(wd, "game/engine/images"));
            dirs.add(new java.io.File(wd, "src/game/engine/images"));
            wd = wd.getParentFile();
        }

        if (!loadImageDebugDone) {
            loadImageDebugDone = true;
            System.out.println("[IMAGE] Working dir: "
                + new java.io.File(".").getAbsolutePath());
            for (java.io.File d : dirs) {
                if (d == null || !d.isDirectory()) continue;
                java.io.File[] fs = d.listFiles();
                if (fs == null) continue;
                for (java.io.File f : fs) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".png"))
                        System.out.println("  [PNG] " + f.getAbsolutePath());
                }
            }
        }

        // Case-insensitive scan
        for (java.io.File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            java.io.File[] files = dir.listFiles();
            if (files == null) continue;
            for (java.io.File f : files) {
                if (!f.isFile()) continue;
                String fname = f.getName();
                if (!fname.toLowerCase().endsWith(".png")) continue;
                for (String cand : cands) {
                    if (fname.equalsIgnoreCase(cand)) {
                        try {
                            javafx.scene.image.Image img = new Image(f.toURI().toString());
                            imageCache.put(name.toLowerCase(), img);
                            iv.setImage(img);
                            return;
                        } catch (Exception ex) {
                            System.out.println("[IMAGE] decode failed " + f + ": " + ex.getMessage());
                        }
                    }
                }
            }
        }

        // Classpath fallback — includes new package location
        for (String cand : cands) {
            for (String pfx : new String[]{"/game/engine/images/", "/", "/images/"}) {
                try {
                    java.io.InputStream is = getClass().getResourceAsStream(pfx + cand);
                    if (is != null) {
                        javafx.scene.image.Image img = new Image(is);
                        imageCache.put(name.toLowerCase(), img);
                        iv.setImage(img);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }
        // Mark this name as missing in the cache so we don't search again
        imageCache.put(name.toLowerCase(), MISSING_IMAGE_SENTINEL);
        System.out.println("[IMAGE] NOT FOUND: '" + name + "'");
    }

    // =========================================================================
    // SMALL HELPERS
    // =========================================================================
    private String monType(Monster m) {
        if (m instanceof Dasher)      return "Dasher";
        if (m instanceof Dynamo)      return "Dynamo";
        if (m instanceof MultiTasker) return "MultiTasker";
        if (m instanceof Schemer)     return "Schemer";
        return "Unknown";
    }

    private String speedStatus(Monster m) {
        if (m instanceof Dasher) {
            int t = ((Dasher) m).getMomentumTurns();
            return t > 0 ? "💨 Momentum " + t + (t == 1 ? " Turn" : " Turns") : "";
        }
        if (m instanceof MultiTasker) {
            int t = ((MultiTasker) m).getNormalSpeedTurns();
            return t > 0 ? "🎯 Focus "    + t + (t == 1 ? " Turn" : " Turns") : "";
        }
        return "";
    }

    private void styleBtn(Button btn, String base, String hover) {
        String bs = "-fx-background-color:" + base  + ";-fx-text-fill:white;-fx-background-radius:6;-fx-cursor:hand;-fx-padding:6 14 6 14;";
        String hs = "-fx-background-color:" + hover + ";-fx-text-fill:white;-fx-background-radius:6;-fx-cursor:hand;-fx-padding:6 14 6 14;";
        btn.setStyle(bs);
        btn.setOnMouseEntered(e -> btn.setStyle(hs));
        btn.setOnMouseExited (e -> btn.setStyle(bs));
    }

    private Button buildRoleBtn(String text, String dark, String base, String hover) {
        Button btn = new Button(text);
        btn.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        btn.setPrefSize(210, 58);
        String n = "-fx-background-color:linear-gradient(to bottom," + base + "," + dark + ");"
            + "-fx-text-fill:white;-fx-background-radius:8;"
            + "-fx-border-color:" + hover + ";-fx-border-width:1;-fx-border-radius:8;-fx-cursor:hand;";
        String hv= "-fx-background-color:linear-gradient(to bottom," + hover + "," + base + ");"
            + "-fx-text-fill:white;-fx-background-radius:8;"
            + "-fx-border-color:white;-fx-border-width:1;-fx-border-radius:8;-fx-cursor:hand;";
        btn.setStyle(n);
        btn.setOnMouseEntered(e -> btn.setStyle(hv));
        btn.setOnMouseExited (e -> btn.setStyle(n));
        return btn;
    }

    private Label pLbl(String t, String hex, int sz, boolean bold) {
        Label l = new Label(t);
        l.setFont(Font.font("Arial", bold ? FontWeight.BOLD : FontWeight.NORMAL, sz));
        l.setTextFill(Color.web(hex));
        l.setWrapText(true);
        l.setMaxWidth(185);
        return l;
    }

    private Label pSecLbl(String t) {
        Label l = new Label(t);
        l.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        l.setTextFill(Color.web(pal("section")));
        l.setPadding(new Insets(0, 0, 2, 0));
        return l;
    }

    private Region hDiv(String c) {
        Region r = new Region();
        r.setPrefHeight(1); r.setMinHeight(1); r.setMaxHeight(1);
        r.setStyle("-fx-background-color:" + c + ";");
        return r;
    }

    private String shortName(String name) {
        if (name == null) return "?";
        String[] p = name.split(" ");
        return p[0].length() > 8 ? p[0].substring(0, 8) : p[0];
    }

    private int linearIndex(int row, int col) {
        return row * 10 + (row % 2 == 0 ? col : 9 - col);
    }

    // ── Status badge helpers ──────────────────────────────────────────────────
    private Label statusBadge(String icon, String key, String value, String bg, String tc) {
        Label l = new Label();
        l.setUserData(icon + " " + key);
        l.setMaxWidth(190);
        l.setWrapText(false);
        applyBadge(l, icon, key, value, bg, tc);
        return l;
    }
    private void updBadge(Label l, String value, String bg, String tc) {
        if (l == null) return;
        Object ud = l.getUserData();
        String ik = (ud != null) ? ud.toString() : "";
        String[] p = ik.split(" ", 2);
        applyBadge(l, p.length > 0 ? p[0] : "", p.length > 1 ? p[1] : "", value, bg, tc);
    }
    private void applyBadge(Label l, String icon, String key, String value, String bg, String tc) {
        Label kl = new Label(icon + " " + key);
        kl.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        kl.setTextFill(Color.web("#aaaacc"));
        kl.setStyle("-fx-background-color:#1e1e38;-fx-padding:2 4 2 4;-fx-background-radius:3;");
        kl.setMinWidth(58);
        Label vl = new Label(value);
        vl.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        vl.setTextFill(Color.web(tc));
        vl.setStyle("-fx-background-color:" + bg + ";-fx-padding:2 4 2 4;-fx-background-radius:3;");
        HBox row = new HBox(3, kl, vl);
        row.setAlignment(Pos.CENTER_LEFT);
        l.setGraphic(row);
        l.setText("");
    }
}
