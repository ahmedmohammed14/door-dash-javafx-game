package game.engine.gui;

import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("DooR DasH: Scare vs Laugh Touchdown");
        primaryStage.setResizable(true);

        // F11 is the ONLY way out of fullscreen
        primaryStage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        primaryStage.setFullScreenExitHint("");

        // Try loading the game icon from src/ or project root, never crash
        try {
            // Try new images package location first, then legacy locations
            String[] candidates = {
                "src/game/engine/images/GameIcon.png",
                "src/GameIcon.png",
                "GameIcon.png"
            };
            java.io.File found = null;
            for (String path : candidates) {
                java.io.File f = new java.io.File(path);
                if (f.exists()) { found = f; break; }
            }
            if (found != null) {
                primaryStage.getIcons().add(new Image(found.toURI().toString()));
            } else {
                // Classpath fallback — new package then root
                java.io.InputStream is = getClass().getResourceAsStream("/game/engine/images/GameIcon.png");
                if (is == null) is = getClass().getResourceAsStream("/GameIcon.png");
                if (is != null) primaryStage.getIcons().add(new Image(is));
            }
        } catch (Exception ignored) {}

        GameModel      model      = new GameModel();
        GameView       view       = new GameView(primaryStage);
        GameController controller = new GameController(model, view, primaryStage);

        // Start gameplay music immediately on launch so the user hears
        // the soundtrack on the start screen too — not only after pressing Start.
        model.playStartSound();

        controller.showStartScreen();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
