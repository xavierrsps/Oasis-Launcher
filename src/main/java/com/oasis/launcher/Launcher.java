package com.oasis.launcher;

import com.oasis.launcher.ui.LauncherWindow;
import com.oasis.launcher.util.Platform;
import javafx.application.Application;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;

/**
 * Oasis Launcher main entry point.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Set up the data directory + logging</li>
 *   <li>Start the JavaFX UI</li>
 *   <li>Hand off control to {@link LauncherWindow}</li>
 * </ul>
 *
 * <p>The actual update + launch logic lives in {@code com.oasis.launcher.update}
 * and {@code com.oasis.launcher.launch} respectively.
 */
public class Launcher extends Application {

    private static final Logger logger = LogManager.getLogger(Launcher.class);

    public static void main(String[] args) {
        // Ensure data dir exists before anything else logs/writes.
        try {
            Files.createDirectories(Platform.dataDir());
            Files.createDirectories(Platform.logsDir());
        } catch (Exception e) {
            System.err.println("Could not create data directory: " + e.getMessage());
        }

        logger.info("Oasis Launcher starting…");
        logger.info("Data directory: {}", Platform.dataDir());
        logger.info("Java: {} ({})", System.getProperty("java.version"),
                System.getProperty("java.vendor"));
        logger.info("OS: {} {}", System.getProperty("os.name"),
                System.getProperty("os.version"));
        DevMode.logState();

        launch(args);
    }

    @Override
    public void start(Stage stage) {
        new LauncherWindow(stage).show();
    }
}
