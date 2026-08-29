package com.oasis.launcher.ui;

import com.oasis.launcher.util.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The Oasis legal gate as an <em>in-app overlay</em> — a centered, scrollable card floating over a
 * dimmed scrim (the launcher behind is blurred by the caller). Not a separate OS window.
 *
 * <p>The bundled branded HTML is extracted to real files and loaded via {@code file://} URLs (dodges
 * JPMS resource-encapsulation and lets the pages' relative logo/cross-links resolve). In
 * {@code requireAgreement} mode it is a mandatory gate: scroll the Rules to the bottom and click
 * <em>Agree &amp; Continue</em>; declining exits. Otherwise it opens read-only (click outside to close).
 */
public final class LegalOverlay {

    private static final Logger logger = LogManager.getLogger(LegalOverlay.class);

    private LegalOverlay() {}

    private static final String BG = "#15100a";
    private static final String PANEL = "#1f1a12";
    private static final String LINE = "rgba(244,181,63,0.30)";
    private static final String GOLD = "#f4b53f";
    private static final String INK = "#f1e5cb";
    private static final String DIM = "#b6a684";

    private static Path assetDir;

    /**
     * @param requireAgreement true for the mandatory gate; false for read-only review
     * @param onAgreed         run if the user agrees (mandatory mode)
     * @param onDecline        run if the user declines (mandatory mode)
     * @param onClose          run if the user dismisses in read-only mode
     * @return the full-size overlay node to add on top of the launcher
     */
    public static Region build(boolean requireAgreement, Runnable onAgreed, Runnable onDecline, Runnable onClose) {
        Path dir = ensureAssets();

        // ── Dim scrim (blocks clicks to the blurred launcher behind) ──
        StackPane scrim = new StackPane();
        scrim.setStyle("-fx-background-color: rgba(8,5,2,0.42);");
        scrim.setPadding(new Insets(36));

        // ── Floating card ──
        // Shadow + rounded translucent background live on a SEPARATE layer behind the content, so the
        // effect never rasterises the content above it — that offscreen rasterisation was softening
        // (blurring) the doc text. The content card itself carries NO effect, so text stays crisp.
        Region cardBg = new Region();
        cardBg.setMaxSize(760, 640);
        cardBg.setStyle("-fx-background-color: rgba(22,16,10,0.62); -fx-background-radius: 14;"
                + " -fx-border-color: " + LINE + "; -fx-border-radius: 14;");
        cardBg.setEffect(new DropShadow(42, Color.rgb(0, 0, 0, 0.62)));

        BorderPane card = new BorderPane();
        card.getStyleClass().add("legal-card");
        card.setMaxSize(760, 640);
        card.setStyle("-fx-background-color: transparent;");

        // Header
        HBox header = new HBox(13);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(15, 20, 15, 20));
        header.setStyle("-fx-background-color: transparent;"
                + " -fx-border-color: " + LINE + "; -fx-border-width: 0 0 1 0;");
        ImageView logo = loadLogo(dir);
        if (logo != null) {
            header.getChildren().add(logo);
        }
        Label title = new Label("Terms, Rules & Privacy");
        title.setStyle("-fx-text-fill: " + GOLD + "; -fx-font-size: 16px; -fx-font-weight: bold;");
        Label sub = new Label(requireAgreement
                ? "Please review and agree to continue"
                : "Our terms — available any time");
        sub.setStyle("-fx-text-fill: " + DIM + "; -fx-font-size: 11.5px;");
        header.getChildren().add(new VBox(1, title, sub));

        // Tabs
        Button agree = new Button("Agree & Continue");
        agree.setStyle(btnStyle(true));
        agree.setDisable(true);

        List<Timeline> timers = new ArrayList<>();
        WebView vTerms = docView(dir, "terms.html", null, timers);
        WebView vRules = docView(dir, "rules.html", requireAgreement ? agree : null, timers);
        WebView vPrivacy = docView(dir, "privacy.html", null, timers);
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Terms of Service", vTerms),
                new Tab("Rules", vRules),
                new Tab("Privacy", vPrivacy));
        tabs.getSelectionModel().select(1);
        // In-doc cross-links (e.g. Rules → Terms) switch the tab instead of navigating the WebView.
        wireTabLinks(vTerms, tabs);
        wireTabLinks(vRules, tabs);
        wireTabLinks(vPrivacy, tabs);

        // Footer
        Label status = new Label(requireAgreement ? "Scroll to the bottom of the Rules, then agree" : "");
        status.setStyle("-fx-text-fill: " + DIM + "; -fx-font-size: 12px;");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox footer = new HBox(11, status, grow);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 16, 12, 16));
        footer.setStyle("-fx-background-color: transparent;"
                + " -fx-border-color: " + LINE + "; -fx-border-width: 1 0 0 0;");

        if (requireAgreement) {
            Button decline = new Button("Decline & Exit");
            decline.setStyle(btnStyle(false));
            decline.setOnAction(e -> {
                if (onDecline != null) {
                    onDecline.run();
                }
            });
            agree.setOnAction(e -> {
                if (onAgreed != null) {
                    onAgreed.run();
                }
            });
            footer.getChildren().addAll(decline, agree);
        } else {
            Button close = new Button("Close");
            close.setStyle(btnStyle(false));
            close.setOnAction(e -> {
                if (onClose != null) {
                    onClose.run();
                }
            });
            footer.getChildren().add(close);
        }

        if (requireAgreement) {
            Label notice = new Label("⚠  You must read and scroll to the bottom of the Rules "
                    + "before “Agree & Continue” unlocks.");
            notice.setWrapText(true);
            notice.setStyle("-fx-text-fill: #ffdf94; -fx-font-size: 12.5px; -fx-font-weight: bold;");
            HBox noticeBar = new HBox(notice);
            noticeBar.setPadding(new Insets(9, 18, 9, 18));
            noticeBar.setStyle("-fx-background-color: rgba(244,181,63,0.14);"
                    + " -fx-border-color: " + LINE + "; -fx-border-width: 0 0 1 0;");
            card.setTop(new VBox(header, noticeBar));
        } else {
            card.setTop(header);
        }
        card.setCenter(tabs);
        card.setBottom(footer);

        StackPane cardHolder = new StackPane(cardBg, card);
        cardHolder.setMaxSize(760, 640);
        scrim.getChildren().add(cardHolder);
        StackPane.setAlignment(cardHolder, Pos.CENTER);

        // Click outside the card (read-only mode) dismisses; in mandatory mode it's swallowed.
        scrim.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getTarget() == scrim) {
                if (!requireAgreement && onClose != null) {
                    onClose.run();
                }
                e.consume();
            }
        });

        // Scoped stylesheet (dark/gold tabs), attached to the scene once the overlay is added.
        card.sceneProperty().addListener((o, oldSc, sc) -> {
            if (sc != null && dir != null) {
                String css = dir.resolve("legal-dialog.css").toUri().toString();
                if (!sc.getStylesheets().contains(css)) {
                    sc.getStylesheets().add(css);
                }
            }
        });

        // Fade in.
        scrim.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(170), scrim);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();

        return scrim;
    }

    /** Copies the bundled docs (and dialog CSS) to {@code <dataDir>/legal/} once per run; returns that dir. */
    private static synchronized Path ensureAssets() {
        if (assetDir != null) {
            return assetDir;
        }
        try {
            Path dir = Platform.dataDir().resolve("legal");
            Files.createDirectories(dir);
            copyResource("/legal/terms.html", dir.resolve("terms.html"));
            copyResource("/legal/rules.html", dir.resolve("rules.html"));
            copyResource("/legal/privacy.html", dir.resolve("privacy.html"));
            copyResource("/legal/logo.png", dir.resolve("logo.png"));
            copyResource("/legal/legal-dialog.css", dir.resolve("legal-dialog.css"));
            assetDir = dir;
            return dir;
        } catch (Exception e) {
            logger.error("Could not extract legal assets", e);
            return null;
        }
    }

    private static void copyResource(String resource, Path target) {
        try (InputStream in = LegalOverlay.class.getResourceAsStream(resource)) {
            if (in == null) {
                logger.warn("Legal asset missing on classpath: {}", resource);
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("Could not extract {}: {}", resource, e.getMessage());
        }
    }

    private static ImageView loadLogo(Path dir) {
        try {
            Path logo = dir != null ? dir.resolve("logo.png") : null;
            if (logo == null || !Files.exists(logo)) {
                return null;
            }
            ImageView iv = new ImageView(new Image(logo.toUri().toString(), 0, 32, true, true));
            iv.setFitHeight(32);
            iv.setPreserveRatio(true);
            return iv;
        } catch (Exception e) {
            return null;
        }
    }

    private static WebView docView(Path dir, String file, Button agreeToEnable, List<Timeline> timers) {
        WebView web = new WebView();
        WebEngine engine = web.getEngine();
        try {
            // Render at the display's scale so text is crisp on HiDPI (also enlarges it a touch).
            web.setZoom(javafx.stage.Screen.getPrimary().getOutputScaleX());
        } catch (Exception ignore) {
            // default zoom is fine
        }
        Path doc = dir != null ? dir.resolve(file) : null;
        if (doc != null && Files.exists(doc)) {
            engine.load(doc.toUri().toString());
        } else {
            logger.warn("Legal doc not available: {}", file);
            engine.loadContent("<body style='background:" + BG + ";color:" + INK
                    + ";font-family:sans-serif;padding:24px'>Document unavailable.</body>");
        }
        engine.getLoadWorker().stateProperty().addListener((obs, old, st) -> {
            if (st == Worker.State.SUCCEEDED) {
                try {
                    engine.executeScript(
                            "document.documentElement.setAttribute('data-theme','dark');"
                            + "var s=document.createElement('style');"
                            + "s.textContent='header.bar{display:none!important}footer{display:none!important}"
                            + "html{scroll-padding-top:0}main{padding-top:1.25rem}"
                            + "body{font-family:\"Segoe UI\",system-ui,sans-serif!important;font-size:16px!important;line-height:1.7!important;color:#f3ecda!important}"
                            + "h1,h2,h3{font-family:\"Segoe UI\",system-ui,sans-serif!important;font-weight:700!important;color:#f7eedb!important;letter-spacing:0!important}"
                            + ".rid{font-family:\"Segoe UI\",system-ui,sans-serif!important;font-weight:700!important;font-size:.8em!important}"
                            + "strong,b{font-weight:700!important}"
                            + "p,li,td,th{letter-spacing:.1px}';"
                            + "document.head.appendChild(s);");
                } catch (Exception ignore) {
                    // page still renders without the tweak
                }
                try {
                    // In-doc links to a sibling doc switch the launcher's tab instead of navigating.
                    engine.executeScript(
                            "document.addEventListener('click',function(e){"
                            + "var a=e.target&&e.target.closest?e.target.closest('a'):null;if(!a)return;"
                            + "var h=(a.getAttribute('href')||'').toLowerCase();var t=null;"
                            + "if(h.indexOf('terms.html')>=0)t='terms';"
                            + "else if(h.indexOf('privacy.html')>=0)t='privacy';"
                            + "else if(h.indexOf('rules.html')>=0)t='rules';"
                            + "if(t){e.preventDefault();location.hash='oasistab='+t+'-'+Date.now();}"
                            + "},true);");
                } catch (Exception ignore) {
                    // fall back to normal link behaviour
                }
                if (agreeToEnable != null) {
                    timers.add(scrollGate(engine, agreeToEnable));
                }
            }
        });
        return web;
    }

    /** Cross-doc links inside a WebView switch the launcher's tab (via a hash the engine reports)
     *  instead of navigating the page — so e.g. the Rules → Terms link jumps to the Terms tab. */
    private static void wireTabLinks(WebView web, TabPane tabs) {
        web.getEngine().locationProperty().addListener((o, oldLoc, loc) -> {
            if (loc == null) {
                return;
            }
            int i = loc.indexOf("#oasistab=");
            if (i < 0) {
                return;
            }
            String t = loc.substring(i + 10);
            int idx = t.startsWith("terms") ? 0 : t.startsWith("privacy") ? 2 : 1;
            tabs.getSelectionModel().select(idx);
        });
    }

    private static Timeline scrollGate(WebEngine engine, Button agree) {
        Timeline[] ref = new Timeline[1];
        ref[0] = new Timeline(new KeyFrame(Duration.millis(250), ev -> {
            try {
                Object atBottom = engine.executeScript(
                        "(function(){var d=document.documentElement,b=document.body;"
                        + "var st=(window.pageYOffset||d.scrollTop||b.scrollTop||0);"
                        + "var sh=Math.max(b.scrollHeight||0,d.scrollHeight||0);"
                        + "var ih=window.innerHeight||d.clientHeight||0;"
                        + "return (sh-ih)<=8 || (st+ih)>=(sh-8);})();");
                if (Boolean.TRUE.equals(atBottom)) {
                    agree.setDisable(false);
                    ref[0].stop();
                }
            } catch (Exception ex) {
                ref[0].stop();
            }
        }));
        ref[0].setCycleCount(Timeline.INDEFINITE);
        ref[0].play();
        return ref[0];
    }

    private static String btnStyle(boolean primary) {
        if (primary) {
            return "-fx-background-color: linear-gradient(to bottom, #ffdf94, " + GOLD + " 55%, #b5551b);"
                    + " -fx-text-fill: #2a1a06; -fx-font-weight: bold; -fx-background-radius: 9;"
                    + " -fx-cursor: hand; -fx-padding: 9 20 9 20;";
        }
        return "-fx-background-color: transparent; -fx-text-fill: " + INK + "; -fx-border-color: " + LINE + ";"
                + " -fx-border-radius: 9; -fx-background-radius: 9; -fx-cursor: hand; -fx-padding: 9 16 9 16;";
    }
}
