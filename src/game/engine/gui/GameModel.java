package game.engine.gui;

import java.io.IOException;

import game.engine.Constants;
import game.engine.Game;
import game.engine.Role;
import game.engine.cells.Cell;
import game.engine.exceptions.InvalidMoveException;
import game.engine.exceptions.OutOfEnergyException;
import game.engine.monsters.Monster;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

/**
 * MODEL — owns ALL state: game engine, audio, theme, mode.
 *
 * MVC notes:
 *  - No JavaFX scene nodes here (MediaPlayer is a data/resource object, not a UI node).
 *  - Boolean flags (muted, darkMode, multiplayer) are state, not UI logic.
 *
 * Audio files (place in src/ or project root; either .wav or .mp3 works):
 *   gameplay  : game_start.wav  / game_start.mp3
 *   win       : game_win.wav    / game_win.mp3
 *   lose      : game_lose.wav   / game_lose.mp3
 */
public class GameModel {

    // ── Game engine ───────────────────────────────────────────────────────────
    private Game    game;
    private Role    playerRole;
    private boolean multiplayerMode = false;
    private int     lastDiceRoll    = 0;

    // ── Audio (MediaPlayer — supports long files + reliable looping) ─────────
    private MediaPlayer startPlayer, winPlayer, losePlayer;
    /** The player the game WANTS to play (independent of mute state). */
    private MediaPlayer activePlayer;
    private boolean     activeLoops      = false;
    private boolean     muted            = false;
    private double      volume           = 0.65;
    /** Display name of the active track (for the settings panel). */
    private String      activeTrackName  = "—";
    /** Cached library of {displayName → MediaPlayer} for selectable gameplay tracks. */
    private final java.util.LinkedHashMap<String, MediaPlayer> musicLibrary = new java.util.LinkedHashMap<>();

    // ── Theme ─────────────────────────────────────────────────────────────────
    private boolean darkMode = true;

    public GameModel() {
        startPlayer = tryLoadPlayer(new String[]{"game_start.wav","game_start.mp3","start.wav","start.mp3"});
        winPlayer   = tryLoadPlayer(new String[]{"game_win.wav",  "game_win.mp3",  "win.wav",  "win.mp3"});
        losePlayer  = tryLoadPlayer(new String[]{"game_lose.wav", "game_lose.mp3", "lose.wav", "lose.mp3"});

        // Scan src/game/engine/sounds/ for ALL audio files so the user can
        // pick any of them from the Settings dialog.  This is non-destructive:
        // missing folder => library stays empty, defaults still work.
        scanMusicLibrary();
    }

    /**
     * Build a map of display-name → MediaPlayer for every audio file found in
     * src/game/engine/sounds/ (any .wav or .mp3 file). The default gameplay
     * track is added first so it appears at the top of the picker.
     */
    private void scanMusicLibrary() {
        // Add the default gameplay track first under a friendly name
        if (startPlayer != null) {
            musicLibrary.put("Default Gameplay", startPlayer);
        }
        java.io.File dir = new java.io.File("src/game/engine/sounds/");
        if (!dir.isDirectory()) dir = new java.io.File("game/engine/sounds/");
        if (!dir.isDirectory()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".wav") || n.endsWith(".mp3"))) continue;
            // Skip the win/lose clips — they aren't loopable music
            if (n.contains("win") || n.contains("lose")) continue;
            // Don't double-add the default
            String display = f.getName();
            if (musicLibrary.containsValue(startPlayer) && (n.startsWith("game_start") || n.startsWith("start"))) {
                continue;
            }
            try {
                MediaPlayer mp = new MediaPlayer(new Media(f.toURI().toString()));
                musicLibrary.put(display, mp);
            } catch (Exception ignored) {}
        }
    }

    /** All available gameplay track display names, in the order they were loaded. */
    public java.util.List<String> getMusicLibraryNames() {
        return new java.util.ArrayList<>(musicLibrary.keySet());
    }

    /** Switch the active gameplay track to the one identified by display name. */
    public void selectMusicTrack(String displayName) {
        MediaPlayer mp = musicLibrary.get(displayName);
        if (mp == null) return;
        playPlayer(mp, true);
        activeTrackName = displayName;
    }

    /** Display name of whatever track is active right now (for the UI). */
    public String getActiveTrackName() { return activeTrackName; }

    /**
     * Search for an audio file in (in order):
     *   1. src/game/engine/sounds/<name>
     *   2. src/<name>     (legacy location)
     *   3. <name>         (project root)
     *   4. classpath /game/engine/sounds/<name>
     *   5. classpath /<name>
     */
    private MediaPlayer tryLoadPlayer(String[] names) {
        String[] folders = {"src/game/engine/sounds/", "src/", ""};
        for (String name : names) {
            for (String folder : folders) {
                try {
                    java.io.File f = new java.io.File(folder + name);
                    if (f.exists() && f.isFile()) {
                        return new MediaPlayer(new Media(f.toURI().toString()));
                    }
                } catch (Exception ignored) {}
            }
            for (String pfx : new String[]{"/game/engine/sounds/", "/"}) {
                try {
                    java.net.URL url = getClass().getResource(pfx + name);
                    if (url != null) return new MediaPlayer(new Media(url.toString()));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    // ── Audio actions (called by Controller) ──────────────────────────────────
    public void playStartSound() {
        playPlayer(startPlayer, true);
        if (startPlayer != null && (activeTrackName == null || "—".equals(activeTrackName))) {
            activeTrackName = "Default Gameplay";
        }
    }
    public void playWinSound()   { playPlayer(winPlayer,   false); }
    public void playLoseSound()  { playPlayer(losePlayer,  false); }

    /** Stop the currently playing audio AND forget what was active. */
    public void stopSound() {
        if (activePlayer != null) activePlayer.stop();
        activePlayer = null;
        activeLoops  = false;
    }

    /** Internal: actually play a track if not muted. Remembers it as the active one. */
    private void playPlayer(MediaPlayer player, boolean loop) {
        if (player == null) return;
        // Stop whatever is currently playing
        if (activePlayer != null) activePlayer.stop();
        // Remember this is the active track regardless of mute state
        activePlayer = player;
        activeLoops  = loop;
        // Configure looping — MediaPlayer.INDEFINITE genuinely loops long files
        player.setCycleCount(loop ? MediaPlayer.INDEFINITE : 1);
        player.setVolume(volume);
        // Defensive: if a previous play() exhausted the cycle, rewind first
        player.seek(javafx.util.Duration.ZERO);
        if (!muted) player.play();
    }

    public boolean isMuted() { return muted; }

    /** True if a track is currently active (playing OR paused-by-mute). */
    public boolean isMusicPlaying() {
        return activePlayer != null;
    }

    /**
     * Toggle mute. When unmuting, resume whatever track was active so the
     * gameplay loop keeps going without restarting from scratch.
     */
    public void setMuted(boolean v) {
        if (muted == v) return;
        muted = v;
        if (v) {
            // Mute — pause sound but KEEP activePlayer so we can resume later
            if (activePlayer != null) activePlayer.pause();
        } else {
            // Unmute — resume the active track from where it was
            if (activePlayer != null) {
                activePlayer.setVolume(volume);
                activePlayer.setCycleCount(activeLoops ? MediaPlayer.INDEFINITE : 1);
                activePlayer.play();
            }
        }
    }

    public double  getVolume()         { return volume; }
    public void    setVolume(double v) {
        volume = Math.max(0, Math.min(1, v));
        if (activePlayer != null) activePlayer.setVolume(volume);
    }

    // ── Theme ─────────────────────────────────────────────────────────────────
    public boolean isDarkMode()           { return darkMode; }
    public void    setDarkMode(boolean v)  { darkMode = v; }
    public void    toggleDarkMode()        { darkMode = !darkMode; }

    // ── Multiplayer ───────────────────────────────────────────────────────────
    public boolean isMultiplayerMode()         { return multiplayerMode; }
    public void    setMultiplayerMode(boolean v) { multiplayerMode = v; }

    // ── Dice snapshot ─────────────────────────────────────────────────────────
    public int  getLastDiceRoll()      { return lastDiceRoll; }
    public void setLastDiceRoll(int v) { lastDiceRoll = v; }

    // ── Game engine passthrough ───────────────────────────────────────────────
    public void initGame(Role role) throws IOException {
        this.playerRole = role;
        this.game = new Game(role);
    }

    public Game    getGame()       { return game; }
    public Role    getPlayerRole() { return playerRole; }
    public Monster getPlayer()     { return game.getPlayer(); }
    public Monster getOpponent()   { return game.getOpponent(); }
    public Monster getCurrent()    { return game.getCurrent(); }

    public boolean isPlayerTurn() { return game.getCurrent() == game.getPlayer(); }
    public Monster getWinner()    { return game.getWinner(); }

    public void usePowerup() throws OutOfEnergyException { game.usePowerup(); }
    public void playTurn()   throws InvalidMoveException { game.playTurn();   }

    public Cell[][] getBoardCells() { return game.getBoard().getBoardCells(); }

    public int[] indexToRowCol(int index) {
        int row = index / 10;
        int col = index % 10;
        if (row % 2 == 1) col = 9 - col;
        return new int[]{row, col};
    }

    public String getCellName(int pos) {
        int[] rc = indexToRowCol(pos);
        Cell c = getBoardCells()[rc[0]][rc[1]];
        return (c != null) ? c.getName() : "Unknown";
    }

    // ── Cheats target a specific monster (called by Controller) ──────────────
    public void cheatJumpToEnd(Monster target) {
        target.setPosition(Constants.WINNING_POSITION);
    }

    public void cheatBoostEnergy(Monster target) {
        target.setEnergy(target.getEnergy() + 500);
    }
}
