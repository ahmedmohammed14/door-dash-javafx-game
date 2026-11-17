# DooR DasH: Scare vs Laugh Touchdown

DooR DasH is a turn-based JavaFX board game inspired by *Monsters, Inc.* Play as a Scarer or Laugher and race across a 10×10 board to reach Boo’s Door (cell 99) with at least 1,000 energy.

## How to play

1. Choose **Single Player** to play against the computer or **Multiplayer** to take turns with another player on the same computer.
2. Pick a side, then use **Roll Dice** to move. Doors can award or drain energy depending on your role.
3. Use your monster’s power-up and adapt to the board. Conveyor belts move you forward, contaminated socks send you back, and card tiles can grant a shield, steal energy, or change positions.

The game includes an in-game rules guide, a turn log, light and dark themes, and music controls. Press **F11** to toggle fullscreen.

## Run in Eclipse

You’ll need **Eclipse IDE for Java Developers** and a **JDK 8 distribution that includes JavaFX**, such as Liberica JDK 8 Full. [Liberica JDK 8 setup guide](https://docs.bell-sw.com/liberica-jdk/8u492b9/general/install-guide/)

1. Clone the repository or download and extract its ZIP from **Code → Download ZIP**.
2. In the downloaded project folder, open `.project` in a text editor. If it contains a `<linkedResources>` block pointing to OneDrive, remove that whole block and save the file. Eclipse can then use the copies of `GameController.java` and `GameView.java` in `src/game/engine/gui/`.
3. In Eclipse, open **Window → Preferences → Java → Installed JREs** and add your JavaFX-enabled JDK 8 as a **Standard VM**.
4. Choose **File → Import → General → Existing Projects into Workspace**. Select the folder that contains `.project` and import the project.
5. In the project’s **Properties → Java Build Path → Libraries**, check that **JRE System Library** uses the JavaFX-enabled JDK 8. In **Properties → Resource**, set the text file encoding to **UTF-8**.
6. Open `src/game/engine/gui/MainApp.java`, then choose **Run As → Java Application**.

If the game reports that it cannot load its data, open **Run → Run Configurations → Arguments** and set **Working directory** to the folder that contains `cards.csv`, `cells.csv`, and `monsters.csv`.

## Project structure

| Path | Purpose |
| --- | --- |
| `src/game/engine/gui/` | JavaFX entry point and model–view–controller classes |
| `src/game/engine/` | Game rules, turn handling, board cells, cards, and monster abilities |
| `src/game/engine/images/` and `src/game/engine/sounds/` | Artwork and audio used by the interface |
| `cards.csv`, `cells.csv`, and `monsters.csv` | Game data loaded from the project root |

## Credits

Developed by Team 127 for the CSEN 401 Computer Programming Lab at the German University in Cairo.
