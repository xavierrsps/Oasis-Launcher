package com.oasis.launcher.ui;

import javafx.scene.text.Font;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;

/**
 * Bundled brand fonts — Cinzel (display serif) and Sora (UI/body), instanced to static weights and
 * shipped under {@code /fonts}. Loaded once at startup; each weight is registered as its own family so
 * JavaFX picks it by exact name with no faux-bold guessing.
 */
public final class Fonts {

    public static String DISPLAY = "Cinzel";
    public static String BODY = "Sora";
    public static String SEMI = "Sora SemiBold";
    public static String BOLD = "Sora Bold";

    private static final Logger logger = LogManager.getLogger(Fonts.class);
    private static boolean loaded;

    private Fonts() {
    }

    public static void load() {
        if (loaded) {
            return;
        }
        DISPLAY = register("/fonts/cinzel.ttf", DISPLAY);
        BODY = register("/fonts/sora.ttf", BODY);
        SEMI = register("/fonts/sora-semibold.ttf", SEMI);
        BOLD = register("/fonts/sora-bold.ttf", BOLD);
        logger.info("Fonts registered: display='{}' body='{}' semi='{}' bold='{}'", DISPLAY, BODY, SEMI, BOLD);
        loaded = true;
    }

    /** Loads one TTF and returns the family name it actually registered under (or the fallback). */
    private static String register(String resource, String fallback) {
        try (InputStream in = Fonts.class.getResourceAsStream(resource)) {
            if (in == null) {
                return fallback;
            }
            Font f = Font.loadFont(in, 12);
            return f != null ? f.getFamily() : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    public static Font display(double size) {
        return Font.font(DISPLAY, size);
    }

    public static Font body(double size) {
        return Font.font(BODY, size);
    }

    public static Font semi(double size) {
        return Font.font(SEMI, size);
    }

    public static Font bold(double size) {
        return Font.font(BOLD, size);
    }
}
