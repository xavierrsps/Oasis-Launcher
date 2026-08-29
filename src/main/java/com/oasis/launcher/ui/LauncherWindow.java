package com.oasis.launcher.ui;

import com.oasis.launcher.DevMode;
import com.oasis.launcher.account.AccountStore;
import com.oasis.launcher.account.CredentialStore;
import com.oasis.launcher.account.DiscordLinkStore;
import com.oasis.launcher.account.DiscordVerifier;
import com.oasis.launcher.launch.ClientLauncher;
import com.oasis.launcher.model.Account;
import com.oasis.launcher.model.DiscordConfig;
import com.oasis.launcher.model.DiscordLink;
import com.oasis.launcher.model.NewsFeed;
import com.oasis.launcher.model.ServerStatus;
import com.oasis.launcher.model.VersionInfo;
import com.oasis.launcher.update.LauncherSelfUpdater;
import com.oasis.launcher.update.ManifestFetcher;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main launcher window — Jagex-launcher-style layout in an OSRS brown/gold theme: top game-base tabs,
 * a hero banner, a Discord-fed "Recent Updates" grid, and a right-hand Play panel (client, character,
 * server status). Resizable. The launcher self-updates from {@code version.json} on startup.
 */
public class LauncherWindow {

    private static final Logger logger = LogManager.getLogger(LauncherWindow.class);

    // OSRS brown/gold palette
    private static final String BG = "#181109";
    private static final String PANEL = "#2b2113";
    private static final String PANEL2 = "#352815";
    private static final String GOLD = "#f4b53f";
    private static final String GOLD_HI = "#ffd884";
    private static final String GOLD_DIM = "#bd8b2c";
    private static final String EMBER = "#c9701f";
    private static final String TEAL = "#5bcf92";
    private static final String DISCORD = "#5865f2";
    private static final String TEXT = "#f1e5cb";
    private static final String DIM = "#bcaa86";
    private static final String DIM2 = "#8f7f60";
    private static final String LINE = "rgba(244,181,63,0.18)";
    private static final String LINE2 = "rgba(244,181,63,0.34)";

    private final Stage stage;
    private final ManifestFetcher fetcher = new ManifestFetcher();
    private final LauncherSelfUpdater selfUpdater = new LauncherSelfUpdater();
    private final ClientLauncher gameLauncher = new ClientLauncher();
    private final AccountStore accountStore = new AccountStore();
    private final CredentialStore credentialStore = new CredentialStore();
    private final DiscordLinkStore discordLinkStore = new DiscordLinkStore();
    private final DiscordVerifier discordVerifier = new DiscordVerifier();
    private final ScheduledExecutorService background = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "launcher-bg");
        t.setDaemon(true);
        return t;
    });

    private Label statusLabel;
    private Label fileLabel;
    private ProgressBar progressBar;
    private Button playButton;
    private Label serverStatusLabel;
    private FlowPane newsGrid;
    private ComboBox<Account> accountDropdown;

    private BorderPane root;
    private GameBase selectedBase;
    private Label baseStatusLabel;
    private final List<TabHandle> tabs = new ArrayList<>();

    private DiscordLink discordLink;
    private DiscordConfig discordConfig;
    private VBox discordArea;

    /** The game bases shown as tabs. Both "coming soon" for now; flip {@code live} when one launches. */
    private final List<GameBase> bases = List.of(
            new GameBase("oasis3", "Oasis3", "RuneScape · rev 949",
                    "In production, working on rev949.",
                    "Oasis3 — RuneScape, reforged",
                    "A modern RuneScape (rev 949) base — currently in production.",
                    "3", "#f0cf67", "#a9781f", false),
            new GameBase("oasisos", "OasisOS", "Old School · rev 240",
                    "Migrating content, releasing soon.",
                    "OasisOS — Old School, reforged",
                    "An Old School base — migrating the Oasis content across now.",
                    "OS", "#9adfa6", "#3f7d4e", false));

    /** A selectable game base: its identity, status line, hero copy, and emblem colours. */
    private static final class GameBase {
        final String id, name, sub, status, heroTitle, heroSub, emblemText, emblemC1, emblemC2;
        final boolean live;
        GameBase(String id, String name, String sub, String status, String heroTitle, String heroSub,
                 String emblemText, String emblemC1, String emblemC2, boolean live) {
            this.id = id; this.name = name; this.sub = sub; this.status = status;
            this.heroTitle = heroTitle; this.heroSub = heroSub;
            this.emblemText = emblemText; this.emblemC1 = emblemC1; this.emblemC2 = emblemC2;
            this.live = live;
        }
    }

    /** A top game tab bound to a base, so it can be re-styled when the selection changes. */
    private static final class TabHandle {
        final GameBase base; final HBox node; final Label text; final Node emblem;
        TabHandle(GameBase base, HBox node, Label text, Node emblem) {
            this.base = base; this.node = node; this.text = text; this.emblem = emblem;
        }
    }

    public LauncherWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        Fonts.load();
        discordLink = discordLinkStore.get().orElse(null);
        selectedBase = bases.get(0);
        root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setTop(buildTop());
        root.setCenter(buildBody());
        root.setBottom(buildBottomBar());

        Scene scene = new Scene(root, 1024, 660);
        try {
            scene.getStylesheets().add(getClass().getResource("/launcher.css").toExternalForm());
        } catch (Exception ex) {
            logger.warn("Could not load launcher.css: {}", ex.getMessage());
        }
        stage.setScene(scene);
        stage.setTitle("Oasis Launcher");
        stage.setResizable(true);
        stage.setMinWidth(880);
        stage.setMinHeight(560);

        try {
            InputStream iconStream = getClass().getResourceAsStream("/images/icon.png");
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception ex) {
            logger.warn("Could not set window icon: {}", ex.getMessage());
        }

        stage.setOnCloseRequest(e -> background.shutdownNow());
        stage.show();

        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), root);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        // Paint the native Windows title bar in the theme (Win11; silent no-op elsewhere). Re-apply once
        // shortly after in case the first call lands before the native window has fully settled.
        WindowsChrome.applyDarkCaption("Oasis Launcher", 0x1C1409, 0xF4B53F, 0x3A2C17);
        background.schedule(
                () -> WindowsChrome.applyDarkCaption("Oasis Launcher", 0x1C1409, 0xF4B53F, 0x3A2C17),
                400, TimeUnit.MILLISECONDS);

        refreshStatus();
        startUpdateCheck();
        loadDiscordConfig();
    }

    private void loadDiscordConfig() {
        background.submit(() -> {
            try {
                DiscordConfig cfg = fetcher.fetchDiscordConfig();
                Platform.runLater(() -> {
                    discordConfig = cfg;
                    renderDiscordArea();   // invite button becomes live once we know the URL
                });
            } catch (Exception ex) {
                logger.debug("No discord.json yet ({}) — Discord button stays available.", ex.getMessage());
            }
        });
    }

    // ── Top bar: game tabs + account + logo ─────────────────────────────────

    private Region buildTop() {
        HBox games = new HBox(2);
        games.setAlignment(Pos.CENTER_LEFT);
        tabs.clear();
        for (GameBase base : bases) {
            TabHandle handle = buildTab(base);
            tabs.add(handle);
            games.getChildren().add(handle.node);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label settings = chip("⚙  Settings");
        Region account = userChip("Xavier");

        ImageView logo = loadImage("/images/logo.png", -1, 40);
        HBox top = new HBox(6);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getChildren().addAll(games, spacer, settings, account);
        if (logo != null) {
            HBox.setMargin(logo, new Insets(0, 4, 0, 12));
            top.getChildren().add(logo);
        }
        top.setPadding(new Insets(0, 20, 0, 18));
        top.setMinHeight(68);
        top.setPrefHeight(68);
        top.setStyle("-fx-background-color: linear-gradient(to bottom, #2d2213, #1c1409);"
                + " -fx-border-color: " + LINE2 + "; -fx-border-width: 0 0 1 0;");

        // thin gold divider strip below the bar
        Region divider = new Region();
        divider.setMinHeight(2);
        divider.setStyle("-fx-background-color: linear-gradient(to right, transparent, " + GOLD_DIM + ", transparent);");

        VBox box = new VBox(top, divider);
        return box;
    }

    private TabHandle buildTab(GameBase base) {
        Node emblem = monogramEmblem(base.emblemText, base.emblemC1, base.emblemC2);
        Label text = new Label(base.name);
        text.setFont(Fonts.semi(14));
        HBox tab = new HBox(9, emblem, text);
        tab.setAlignment(Pos.CENTER_LEFT);
        tab.setMinHeight(66);
        tab.setPrefHeight(66);
        tab.setPadding(new Insets(0, 18, 0, 18));
        tab.setOnMouseClicked(e -> selectBase(base));
        TabHandle handle = new TabHandle(base, tab, text, emblem);
        styleTab(handle, base == selectedBase);
        tab.setOnMouseEntered(e -> { if (base != selectedBase) styleTabHover(handle); });
        tab.setOnMouseExited(e -> { if (base != selectedBase) styleTab(handle, false); });
        return handle;
    }

    private void styleTab(TabHandle h, boolean active) {
        h.text.setStyle("-fx-text-fill: " + (active ? GOLD_HI : DIM) + ";");
        h.emblem.setOpacity(active ? 1.0 : 0.8);
        h.node.setStyle(gameTabStyle(active));
    }

    private void styleTabHover(TabHandle h) {
        h.text.setStyle("-fx-text-fill: " + TEXT + ";");
        h.emblem.setOpacity(1.0);
        h.node.setStyle(gameTabStyleHover());
    }

    private void selectBase(GameBase base) {
        if (base == selectedBase) {
            return;
        }
        selectedBase = base;
        for (TabHandle h : tabs) {
            styleTab(h, h.base == selectedBase);
        }
        root.setCenter(buildBody());
        refreshStatus();
    }

    private String gameTabStyle(boolean active) {
        if (active) {
            return "-fx-background-color: linear-gradient(to bottom, rgba(244,181,63,0.04), rgba(244,181,63,0.12));"
                    + " -fx-border-color: transparent transparent " + GOLD + " transparent;"
                    + " -fx-border-width: 0 0 3 0; -fx-cursor: hand;";
        }
        return "-fx-background-color: transparent; -fx-border-color: transparent; -fx-cursor: hand;";
    }

    private String gameTabStyleHover() {
        return "-fx-background-color: linear-gradient(to bottom, transparent, rgba(244,181,63,0.08));"
                + " -fx-border-color: transparent transparent " + GOLD_DIM + " transparent;"
                + " -fx-border-width: 0 0 3 0; -fx-cursor: hand;";
    }

    /** A small circular game emblem drawn in code (guaranteed to render, no bundled image needed). */
    private Region monogramEmblem(String letters, String c1, String c2) {
        return monogramEmblem(letters, c1, c2, 24);
    }

    private Region monogramEmblem(String letters, String c1, String c2, double size) {
        Label l = new Label(letters);
        l.setAlignment(Pos.CENTER);
        l.setMinSize(size, size);
        l.setPrefSize(size, size);
        l.setMaxSize(size, size);
        l.setFont(Fonts.bold(size * (letters.length() > 1 ? 0.4 : 0.5)));
        double r = size / 2;
        l.setStyle("-fx-text-fill: #1c1206; -fx-background-radius: " + r + ";"
                + " -fx-background-color: radial-gradient(center 34% 28%, radius 72%, " + c1 + ", " + c2 + ");"
                + " -fx-border-color: rgba(0,0,0,0.4); -fx-border-radius: " + r + ";");
        return l;
    }

    private Label chip(String text) {
        String base = "-fx-text-fill: " + DIM + "; -fx-padding: 8 12 8 12; -fx-background-radius: 9; -fx-cursor: hand;";
        String hov = "-fx-text-fill: " + TEXT + "; -fx-padding: 8 12 8 12; -fx-background-radius: 9; -fx-cursor: hand;"
                + " -fx-background-color: rgba(244,181,63,0.07);";
        Label l = new Label(text);
        l.setFont(Fonts.body(13));
        l.setStyle(base);
        l.setOnMouseEntered(e -> l.setStyle(hov));
        l.setOnMouseExited(e -> l.setStyle(base));
        return l;
    }

    /** The top-right account chip: a gold monogram avatar + name + caret (the "user logo"). */
    private Region userChip(String name) {
        String initial = name != null && !name.isBlank() ? name.substring(0, 1).toUpperCase() : "?";
        Label av = new Label(initial);
        av.setAlignment(Pos.CENTER);
        av.setMinSize(28, 28);
        av.setPrefSize(28, 28);
        av.setMaxSize(28, 28);
        av.setFont(Fonts.bold(12));
        av.setStyle("-fx-text-fill: #2a1a06; -fx-background-radius: 14;"
                + " -fx-background-color: radial-gradient(center 34% 28%, radius 66%, " + GOLD_HI + ", " + EMBER + ");");
        Label nm = new Label(name);
        nm.setFont(Fonts.semi(13));
        nm.setStyle("-fx-text-fill: " + TEXT + ";");
        Label caret = new Label("▾");
        caret.setFont(Fonts.body(11));
        caret.setStyle("-fx-text-fill: " + GOLD + ";");
        HBox chip = new HBox(7, av, nm, caret);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(5, 11, 5, 6));
        String base = "-fx-background-radius: 20; -fx-cursor: hand; -fx-border-color: " + LINE + "; -fx-border-radius: 20;";
        String hov = "-fx-background-radius: 20; -fx-cursor: hand; -fx-border-color: " + LINE2 + "; -fx-border-radius: 20;"
                + " -fx-background-color: rgba(244,181,63,0.07);";
        chip.setStyle(base);
        chip.setOnMouseEntered(e -> chip.setStyle(hov));
        chip.setOnMouseExited(e -> chip.setStyle(base));
        return chip;
    }

    // ── Body: content (hero + news) + right play panel ──────────────────────

    private Region buildBody() {
        VBox content = new VBox(0);
        content.setPadding(new Insets(18, 30, 24, 22));
        content.getChildren().add(buildHero(selectedBase));
        content.getChildren().add(buildUpdatesHeader());
        newsGrid = new FlowPane(13, 13);
        newsGrid.setPrefWrapLength(660);
        Label loading = new Label("Loading updates…");
        loading.setFont(Fonts.body(12));
        loading.setStyle("-fx-text-fill: " + DIM + ";");
        newsGrid.getChildren().add(loading);
        content.getChildren().add(newsGrid);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        HBox.setHgrow(scroll, Priority.ALWAYS);
        // Hard-cap the content to the viewport width so the news grid always wraps instead of
        // overflowing under the right panel.
        scroll.viewportBoundsProperty().addListener((o, a, b) -> content.setMaxWidth(b.getWidth()));

        HBox body = new HBox(scroll, buildRightPanel(selectedBase));
        loadNews(selectedBase);
        return body;
    }

    private Region buildHero(GameBase base) {
        StackPane hero = new StackPane();
        hero.setMinHeight(190);
        hero.setPrefHeight(190);
        hero.setMaxHeight(190);
        hero.setStyle("-fx-background-color: #17100a;");

        // Pre-composed wide banner (logo on the right, faded into dark on the left). SHARP, not blurred.
        // The image is UNMANAGED so it never inflates the scroll's content width (that was hiding the cards
        // under the right panel); we size + right-anchor it ourselves as a "cover" fit that keeps the logo.
        ImageView bg = loadImage("/images/hero.png", -1, -1);
        if (bg == null) {
            bg = loadImage("/images/background.png", -1, -1);
        }
        if (bg != null) {
            final ImageView heroImg = bg;
            final Image im = heroImg.getImage();
            heroImg.setManaged(false);
            heroImg.setPreserveRatio(true);
            heroImg.setSmooth(true);
            Runnable cover = () -> {
                double hw = hero.getWidth(), hh = hero.getHeight();
                double iw = im.getWidth(), ih = im.getHeight();
                if (hw <= 0 || hh <= 0 || iw <= 0 || ih <= 0) {
                    return;
                }
                double scale = Math.max(hw / iw, hh / ih);
                double w = iw * scale, h = ih * scale;
                heroImg.setFitWidth(w);
                heroImg.setLayoutX(hw - w);          // right-anchor so the logo is always in view
                heroImg.setLayoutY((hh - h) / 2);
            };
            hero.widthProperty().addListener((o, a, b) -> cover.run());
            hero.heightProperty().addListener((o, a, b) -> cover.run());
            hero.getChildren().add(heroImg);
            Platform.runLater(cover);
        }

        // Left-to-right darkening so the text stays readable over the art.
        Region tint = new Region();
        tint.setStyle("-fx-background-color: linear-gradient(to right, rgba(10,7,4,0.9) 0%, rgba(10,7,4,0.32) 52%, transparent);");
        hero.getChildren().add(tint);

        Label kick = new Label(base.live ? "LIVE NOW" : "COMING SOON");
        kick.setFont(Fonts.bold(10.5));
        kick.setMaxWidth(Region.USE_PREF_SIZE);
        kick.setStyle("-fx-text-fill: #2a1a06; -fx-letter-spacing: 2;"
                + " -fx-background-color: linear-gradient(to bottom, " + GOLD_HI + ", " + GOLD
                + "); -fx-padding: 3 9 3 9; -fx-background-radius: 5;");
        Label title = new Label(base.heroTitle);
        title.setFont(Fonts.display(28));
        title.setStyle("-fx-text-fill: white;");
        title.setWrapText(true);
        title.setEffect(new DropShadow(14, Color.rgb(0, 0, 0, 0.75)));
        Label sub = new Label(base.heroSub);
        sub.setFont(Fonts.body(13));
        sub.setStyle("-fx-text-fill: #e9dcc0;");
        sub.setWrapText(true);
        sub.setEffect(new DropShadow(8, Color.rgb(0, 0, 0, 0.7)));
        VBox txt = new VBox(7, kick, title, sub);
        txt.setAlignment(Pos.BOTTOM_LEFT);
        txt.setMaxWidth(480);
        txt.setPadding(new Insets(22, 24, 22, 26));
        StackPane.setAlignment(txt, Pos.BOTTOM_LEFT);
        hero.getChildren().add(txt);

        // Round the whole banner and give it a soft drop shadow.
        Rectangle clip = new Rectangle();
        clip.setArcWidth(26);
        clip.setArcHeight(26);
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        hero.setClip(clip);

        StackPane wrap = new StackPane(hero);
        wrap.setEffect(new DropShadow(16, Color.rgb(0, 0, 0, 0.5)));
        return wrap;
    }

    private Region buildUpdatesHeader() {
        Label section = new Label("RECENT UPDATES");
        section.setFont(Fonts.display(15));
        section.setStyle("-fx-text-fill: " + GOLD + "; -fx-letter-spacing: 2;");
        Label src = new Label("# synced from Discord");
        src.setFont(Fonts.body(10.5));
        src.setStyle("-fx-text-fill: #9aa4f5; -fx-background-color: rgba(88,101,242,0.14);"
                + " -fx-border-color: rgba(88,101,242,0.4); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 3 8 3 8;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label viewAll = new Label("View all →");
        viewAll.setFont(Fonts.semi(12));
        viewAll.setStyle("-fx-text-fill: " + GOLD_DIM + "; -fx-cursor: hand;");
        HBox head = new HBox(11, section, src, spacer, viewAll);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(24, 2, 13, 2));
        return head;
    }

    private Region buildRightPanel(GameBase base) {
        VBox panel = new VBox(13);
        panel.setPrefWidth(312);
        panel.setMinWidth(312);
        panel.setPadding(new Insets(18, 18, 16, 18));
        panel.setStyle("-fx-background-color: linear-gradient(to bottom, #271d10, #19110a);"
                + " -fx-border-color: " + LINE2 + "; -fx-border-width: 0 0 0 1;");

        // Base header: emblem + name + sub.
        Label baseName = new Label(base.name);
        baseName.setFont(Fonts.display(19));
        baseName.setStyle("-fx-text-fill: " + GOLD_HI + ";");
        Label baseSub = new Label(base.sub);
        baseSub.setFont(Fonts.body(11));
        baseSub.setStyle("-fx-text-fill: " + DIM2 + ";");
        VBox baseText = new VBox(2, baseName, baseSub);
        HBox header = new HBox(11, monogramEmblem(base.emblemText, base.emblemC1, base.emblemC2, 36), baseText);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 13, 0));
        header.setStyle("-fx-border-color: " + LINE + "; -fx-border-width: 0 0 1 0;");

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        Region discord = buildDiscordArea();

        if (base.live) {
            playButton = new Button("PLAY");
            playButton.setDisable(true);
            playButton.setMaxWidth(Double.MAX_VALUE);
            playButton.setPrefHeight(54);
            playButton.setFont(Fonts.display(21));
            String playBase = "-fx-background-color: linear-gradient(to bottom, " + GOLD_HI + ", " + GOLD + " 52%, " + EMBER
                    + "); -fx-text-fill: #2a1a06; -fx-background-radius: 11; -fx-cursor: hand;";
            String playHover = "-fx-background-color: linear-gradient(to bottom, #ffe7b0, " + GOLD_HI + " 52%, " + GOLD
                    + "); -fx-text-fill: #2a1a06; -fx-background-radius: 11; -fx-cursor: hand;";
            playButton.setStyle(playBase);
            playButton.setOnAction(e -> onPlay());
            ScaleTransition playScale = new ScaleTransition(Duration.millis(120), playButton);
            playButton.setOnMouseEntered(e -> {
                if (playButton.isDisabled()) {
                    return;
                }
                playButton.setStyle(playHover);
                playButton.setEffect(new DropShadow(20, Color.rgb(244, 181, 63, 0.55)));
                playScale.stop();
                playScale.setToX(1.03);
                playScale.setToY(1.03);
                playScale.play();
            });
            playButton.setOnMouseExited(e -> {
                playButton.setStyle(playBase);
                playButton.setEffect(null);
                playScale.stop();
                playScale.setToX(1.0);
                playScale.setToY(1.0);
                playScale.play();
            });

            ComboBox<String> client = new ComboBox<>(FXCollections.observableArrayList("RuneLite"));
            client.getSelectionModel().selectFirst();
            client.setMaxWidth(Double.MAX_VALUE);
            client.setStyle(dropdownStyle());

            accountDropdown = new ComboBox<>();
            accountDropdown.setMaxWidth(Double.MAX_VALUE);
            accountDropdown.setPromptText("Select account");
            accountDropdown.setStyle(dropdownStyle());
            accountDropdown.setButtonCell(new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(Account item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? "Select account" : item.label());
                    setStyle("-fx-text-fill: " + TEXT + ";");
                }
            });

            serverStatusLabel = new Label("Checking server…");
            serverStatusLabel.setFont(Fonts.semi(12.5));
            serverStatusLabel.setMaxWidth(Double.MAX_VALUE);
            serverStatusLabel.setStyle(statusPillStyle(DIM));

            Button addBtn = new Button("+");
            addBtn.setStyle(smallBtnStyle(GOLD));
            addBtn.setOnAction(e -> onAddAccount());
            Button removeBtn = new Button("−");
            removeBtn.setStyle(smallBtnStyle("#6b5a3e"));
            removeBtn.setOnAction(e -> onRemoveAccount());
            HBox acctRow = new HBox(6, accountDropdown, addBtn, removeBtn);
            HBox.setHgrow(accountDropdown, Priority.ALWAYS);

            baseStatusLabel = null;
            panel.getChildren().addAll(header, playButton,
                    label("GAME CLIENT"), client,
                    label("CHARACTER"), acctRow,
                    serverStatusLabel, grow, discord);
            refreshAccountDropdown();
        } else {
            // Coming-soon base: a "COMING SOON" plate + the current-status callout, no Play/account.
            playButton = null;
            serverStatusLabel = null;
            accountDropdown = null;

            Label soon = new Label("COMING SOON");
            soon.setAlignment(Pos.CENTER);
            soon.setMaxWidth(Double.MAX_VALUE);
            soon.setPrefHeight(54);
            soon.setFont(Fonts.display(18));
            soon.setStyle("-fx-text-fill: " + GOLD + "; -fx-letter-spacing: 3;"
                    + " -fx-background-color: linear-gradient(to bottom, rgba(244,181,63,0.10), rgba(244,181,63,0.03));"
                    + " -fx-background-radius: 11; -fx-border-color: " + LINE2 + "; -fx-border-radius: 11;");

            baseStatusLabel = new Label(base.status);
            baseStatusLabel.setFont(Fonts.body(13));
            baseStatusLabel.setWrapText(true);
            baseStatusLabel.setMaxWidth(Double.MAX_VALUE);
            baseStatusLabel.setStyle("-fx-text-fill: " + TEXT + "; -fx-background-color: rgba(0,0,0,0.28);"
                    + " -fx-background-radius: 10; -fx-border-color: " + LINE + "; -fx-border-radius: 10; -fx-padding: 12 13 12 13;");
            VBox statusBox = new VBox(7, label("CURRENT STATUS"), baseStatusLabel);

            panel.getChildren().addAll(header, soon, statusBox, grow, discord);
        }
        return panel;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.setFont(Fonts.semi(11));
        l.setStyle("-fx-text-fill: " + DIM2 + "; -fx-letter-spacing: 2;");
        return l;
    }

    private String dropdownStyle() {
        return "-fx-background-color: " + PANEL + "; -fx-border-color: " + LINE2 + ";"
                + " -fx-border-radius: 9; -fx-background-radius: 9;";
    }

    private String statusPillStyle(String colour) {
        return "-fx-text-fill: " + colour + "; -fx-background-color: rgba(0,0,0,0.3); -fx-background-radius: 9;"
                + " -fx-border-color: " + LINE + "; -fx-border-radius: 9; -fx-padding: 9 12 9 12;";
    }

    private String smallBtnStyle(String colour) {
        return "-fx-background-color: " + colour + "; -fx-text-fill: white; -fx-background-radius: 8;"
                + " -fx-min-width: 30; -fx-min-height: 30; -fx-font-weight: bold; -fx-cursor: hand;";
    }

    // ── Bottom bar: status + version ────────────────────────────────────────

    private Region buildBottomBar() {
        statusLabel = new Label("Starting…");
        statusLabel.setFont(Fonts.body(12));
        statusLabel.setStyle("-fx-text-fill: " + DIM + ";");
        fileLabel = new Label("");
        fileLabel.setFont(Fonts.body(10));
        fileLabel.setStyle("-fx-text-fill: " + DIM2 + ";");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(160);
        progressBar.setPrefHeight(5);
        progressBar.setStyle("-fx-accent: " + GOLD + ";");
        HBox left = new HBox(12, statusLabel, fileLabel, progressBar);
        left.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label version = new Label("Oasis Launcher v" + LauncherSelfUpdater.CURRENT_VERSION);
        version.setFont(Fonts.body(12));
        version.setStyle("-fx-text-fill: " + DIM2 + ";");

        HBox bar = new HBox(12, left, spacer, version);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 18, 0, 18));
        bar.setMinHeight(35);
        bar.setStyle("-fx-background-color: #130d07; -fx-border-color: " + LINE2 + "; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    // ── News (Discord-post cards) ───────────────────────────────────────────

    private void loadNews(GameBase base) {
        final String baseId = base.id;
        background.submit(() -> {
            try {
                NewsFeed feed = fetcher.fetchNews(baseId);
                Platform.runLater(() -> {
                    if (base != selectedBase) {
                        return; // user switched tabs while this was loading
                    }
                    renderNews(feed);
                    if (feed != null && feed.status != null && !feed.status.isBlank() && baseStatusLabel != null) {
                        baseStatusLabel.setText(feed.status);
                    }
                });
            } catch (Exception ex) {
                logger.warn("Could not fetch news for {}: {}", baseId, ex.getMessage());
                Platform.runLater(() -> {
                    if (base != selectedBase) {
                        return;
                    }
                    newsGrid.getChildren().clear();
                    Label err = new Label("No updates yet for " + base.name + ".");
                    err.setStyle("-fx-text-fill: " + DIM + ";");
                    newsGrid.getChildren().add(err);
                });
            }
        });
    }

    private void renderNews(NewsFeed feed) {
        newsGrid.getChildren().clear();
        if (feed == null || feed.updates == null || feed.updates.isEmpty()) {
            Label empty = new Label("No updates yet.");
            empty.setStyle("-fx-text-fill: " + DIM + ";");
            newsGrid.getChildren().add(empty);
            return;
        }
        for (NewsFeed.Update u : feed.updates) {
            newsGrid.getChildren().add(buildCard(u));
        }
    }

    private Region buildCard(NewsFeed.Update u) {
        VBox card = new VBox();
        card.setMinWidth(300);
        card.setPrefWidth(300);
        card.setMaxWidth(300);
        card.setStyle(cardStyle(false));

        boolean hasImg = u.image != null && !u.image.isBlank();
        if (hasImg) {
            ImageView img = new ImageView(new Image(u.image, 300, 104, false, true, true));
            img.setFitWidth(300);
            img.setFitHeight(104);
            StackPane band = new StackPane(img);
            band.setMinHeight(104);
            band.setMaxHeight(104);
            band.setStyle("-fx-border-color: " + LINE + "; -fx-border-width: 0 0 1 0;");
            if (u.badge != null && !u.badge.isBlank()) {
                Label tag = tagPill(u.badge);
                StackPane.setAlignment(tag, Pos.TOP_LEFT);
                StackPane.setMargin(tag, new Insets(9, 0, 0, 9));
                band.getChildren().add(tag);
            }
            card.getChildren().add(band);
        }

        VBox body = new VBox(7);
        body.setPadding(new Insets(12, 14, 13, 14));

        if (!hasImg && u.badge != null && !u.badge.isBlank()) {
            HBox tagRow = new HBox(tagPill(u.badge));
            body.getChildren().add(tagRow);
        }

        Label title = new Label(u.title != null ? u.title : (u.body != null ? firstLine(u.body) : "(update)"));
        title.setFont(Fonts.semi(14));
        title.setStyle("-fx-text-fill: " + TEXT + ";");
        title.setWrapText(true);
        body.getChildren().add(title);

        if (u.body != null && !u.body.isBlank()) {
            Label p = new Label(u.body);
            p.setFont(Fonts.body(12));
            p.setStyle("-fx-text-fill: " + DIM + "; -fx-line-spacing: 1.5;");
            p.setWrapText(true);
            p.setMaxHeight(40);
            body.getChildren().add(p);
        }

        HBox foot = new HBox(8);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.getChildren().add(avatarNode(u.author, u.avatar));
        Label who = new Label(u.author != null && !u.author.isBlank() ? u.author : "Oasis");
        who.setFont(Fonts.semi(11.5));
        who.setStyle("-fx-text-fill: " + GOLD_DIM + ";");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label date = new Label(u.date != null ? u.date : "");
        date.setFont(Fonts.body(11));
        date.setStyle("-fx-text-fill: " + DIM2 + ";");
        foot.getChildren().addAll(who, gap, date);
        body.getChildren().add(foot);

        card.getChildren().add(body);

        // Real hover animation (JavaFX ignores CSS :hover transitions — must be done in code):
        // a smooth lift + a warm gold glow, reversing on exit.
        DropShadow rest = new DropShadow(14, Color.rgb(0, 0, 0, 0.5));
        DropShadow hot = new DropShadow(22, Color.rgb(244, 181, 63, 0.32));
        card.setEffect(rest);
        TranslateTransition lift = new TranslateTransition(Duration.millis(150), card);
        card.setOnMouseEntered(e -> {
            card.setStyle(cardStyle(true));
            card.setEffect(hot);
            lift.stop();
            lift.setToY(-4);
            lift.play();
        });
        card.setOnMouseExited(e -> {
            card.setStyle(cardStyle(false));
            card.setEffect(rest);
            lift.stop();
            lift.setToY(0);
            lift.play();
        });
        if (u.link != null && !u.link.isBlank()) {
            card.setOnMouseClicked(e -> openLink(u.link));
        }
        return card;
    }

    /** A small uppercase corner tag pill (e.g. "ANNOUNCEMENT"). */
    private Label tagPill(String text) {
        Label t = new Label(text.toUpperCase());
        t.setFont(Fonts.bold(9.5));
        t.setMaxWidth(Region.USE_PREF_SIZE);
        t.setStyle("-fx-text-fill: #2a1a06; -fx-letter-spacing: 1;"
                + " -fx-background-color: linear-gradient(to bottom, " + GOLD_HI + ", " + GOLD_DIM + ");"
                + " -fx-background-radius: 5; -fx-padding: 3 8 3 8;");
        return t;
    }

    private String cardStyle(boolean hover) {
        String top = hover ? "#33260f" : PANEL;
        String border = hover ? LINE2 : LINE;
        return "-fx-background-color: linear-gradient(to bottom, " + top + ", #241a0e);"
                + " -fx-background-radius: 12; -fx-border-color: " + border + "; -fx-border-radius: 12; -fx-cursor: hand;";
    }

    /** Open a URL in the user's default browser (Windows shell, no java.desktop dependency). */
    private void openLink(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        background.submit(() -> {
            try {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            } catch (Exception ex) {
                logger.warn("Could not open link {}: {}", url, ex.getMessage());
            }
        });
    }

    private Region avatarNode(String author, String avatarUrl) {
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            ImageView iv = new ImageView(new Image(avatarUrl, 22, 22, false, true, true));
            iv.setFitWidth(22);
            iv.setFitHeight(22);
            iv.setClip(new Circle(11, 11, 11));
            return new StackPane(iv);
        }
        String initial = author != null && !author.isBlank() ? author.substring(0, 1).toUpperCase() : "O";
        Label a = new Label(initial);
        a.setAlignment(Pos.CENTER);
        a.setMinSize(22, 22);
        a.setPrefSize(22, 22);
        a.setFont(Fonts.bold(10));
        a.setStyle("-fx-text-fill: #2a1a06; -fx-background-radius: 11;"
                + " -fx-background-color: radial-gradient(center 34% 28%, radius 66%, " + GOLD_HI + ", " + EMBER + ");");
        return a;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private ImageView loadImage(String resource, double fitW, double fitH) {
        try {
            InputStream in = getClass().getResourceAsStream(resource);
            if (in == null) {
                return null;
            }
            Image img = new Image(in);
            if (img.isError()) {
                return null;
            }
            ImageView v = new ImageView(img);
            v.setPreserveRatio(true);
            v.setSmooth(true);
            if (fitW > 0) {
                v.setFitWidth(fitW);
            }
            if (fitH > 0) {
                v.setFitHeight(fitH);
            }
            return v;
        } catch (Exception ex) {
            return null;
        }
    }

    // ── Discord link (OTP verify) ───────────────────────────────────────────

    /** Discord "Clyde" logo path (simple-icons), drawn as an SVGPath so no image asset is needed. */
    private static final String DISCORD_PATH = "M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0"
            + " 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868"
            + " 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0"
            + " 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824"
            + " 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0"
            + " 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0"
            + " 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0"
            + " 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933"
            + " 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0"
            + " 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0"
            + " 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067"
            + " 3.9495-1.5219 6.0023-3.0294a.077.077 0"
            + " 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0"
            + " 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189"
            + " 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569"
            + " 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189"
            + " 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189z";

    private Region buildDiscordArea() {
        discordArea = new VBox(8);
        discordArea.setMaxWidth(Double.MAX_VALUE);
        renderDiscordArea();
        return discordArea;
    }

    private Node discordGlyph(double size, String fillHex) {
        SVGPath p = new SVGPath();
        p.setContent(DISCORD_PATH);
        p.setFill(Color.web(fillHex));
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        StackPane wrap = new StackPane(p);
        wrap.setMinSize(size, size);
        wrap.setPrefSize(size, size);
        wrap.setMaxSize(size, size);
        return wrap;
    }

    private void renderDiscordArea() {
        if (discordArea == null) {
            return;
        }
        discordArea.getChildren().setAll(discordLink != null ? buildVerifiedRow() : buildLoginButton());
    }

    private Region buildLoginButton() {
        Label text = new Label("Log in with Discord");
        text.setFont(Fonts.semi(12.5));
        text.setStyle("-fx-text-fill: white;");
        HBox btn = new HBox(9, discordGlyph(18, "white"), text);
        btn.setAlignment(Pos.CENTER);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setPrefHeight(40);
        String base = "-fx-background-color: " + DISCORD + "; -fx-background-radius: 9; -fx-cursor: hand;";
        String hov = "-fx-background-color: #6b76f5; -fx-background-radius: 9; -fx-cursor: hand;";
        btn.setStyle(base);
        btn.setOnMouseEntered(e -> btn.setStyle(hov));
        btn.setOnMouseExited(e -> btn.setStyle(base));
        btn.setOnMouseClicked(e -> showOtpEntry());
        return btn;
    }

    private void showOtpEntry() {
        Label hint = new Label("In Discord, run /link (or DM the Oasis bot the word “link”) to get a "
                + "6-digit code, then paste it here — it verifies on its own.");
        hint.setFont(Fonts.body(11));
        hint.setStyle("-fx-text-fill: " + DIM + ";");
        hint.setWrapText(true);

        TextField code = new TextField();
        code.setPromptText("6-digit code");
        code.setFont(Fonts.body(14));
        code.setStyle(dropdownStyle() + " -fx-text-fill: " + TEXT + "; -fx-highlight-fill: " + GOLD_DIM
                + "; -fx-padding: 8 10 8 10;");
        // Keep the field to digits (max 6), but sanitise rather than reject so a pasted "  123456\n" still lands.
        code.setTextFormatter(new TextFormatter<>(change -> {
            if (!change.isContentChange()) {
                return change;
            }
            String next = change.getControlNewText();
            if (next.matches("\\d{0,6}")) {
                return change;
            }
            String digits = next.replaceAll("\\D", "");
            if (digits.length() > 6) {
                digits = digits.substring(0, 6);
            }
            change.setRange(0, change.getControlText().length());
            change.setText(digits);
            change.setCaretPosition(digits.length());
            change.setAnchor(digits.length());
            return change;
        }));

        Label err = new Label();
        err.setFont(Fonts.body(11));
        err.setStyle("-fx-text-fill: #e08a6a;");
        err.setWrapText(true);
        err.setManaged(false);
        err.setVisible(false);

        Button verify = new Button("Verify");
        verify.setFont(Fonts.semi(12.5));
        verify.setMaxWidth(Double.MAX_VALUE);
        verify.setPrefHeight(36);
        verify.setStyle("-fx-background-color: " + DISCORD + "; -fx-text-fill: white; -fx-background-radius: 9;"
                + " -fx-cursor: hand;");
        Runnable submit = () -> doVerify(code.getText(), verify, err);
        verify.setOnAction(e -> submit.run());
        code.setOnAction(e -> {
            if (!verify.isDisabled()) {
                submit.run();
            }
        });
        // Auto-verify the moment a full 6-digit code is present (typed or pasted); clear any stale error as they edit.
        code.textProperty().addListener((obs, old, val) -> {
            if (err.isVisible()) {
                err.setVisible(false);
                err.setManaged(false);
            }
            if (val != null && val.length() == 6 && !verify.isDisabled()) {
                submit.run();
            }
        });

        Label getCode = new Label("Get a code in Discord →");
        getCode.setFont(Fonts.body(11));
        getCode.setStyle("-fx-text-fill: #9aa4f5; -fx-cursor: hand;");
        getCode.setOnMouseClicked(e -> openCodeSource());
        Label cancel = new Label("Cancel");
        cancel.setFont(Fonts.body(11));
        cancel.setStyle("-fx-text-fill: " + DIM2 + "; -fx-cursor: hand;");
        cancel.setOnMouseClicked(e -> renderDiscordArea());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox links = new HBox(getCode, gap, cancel);
        links.setAlignment(Pos.CENTER_LEFT);

        discordArea.getChildren().setAll(hint, code, err, verify, links);
        code.requestFocus();
    }

    private void doVerify(String codeText, Button verify, Label err) {
        String code = codeText == null ? "" : codeText.trim();
        if (code.isEmpty()) {
            return;
        }
        err.setVisible(false);
        err.setManaged(false);
        verify.setDisable(true);
        verify.setText("Checking…");
        final String verifyUrl = discordConfig != null ? discordConfig.verifyUrl : null;
        background.submit(() -> {
            DiscordVerifier.VerifyOutcome outcome = discordVerifier.verify(verifyUrl, code);
            if (outcome.status != DiscordVerifier.Status.LINKED) {
                // Verifier already logged the underlying cause; this line ties it to the user-facing result.
                logger.warn("Discord verify unsuccessful ({}): {}", outcome.status, outcome.userMessage);
            }
            Platform.runLater(() -> {
                if (outcome.ok()) {
                    discordLink = outcome.link;
                    discordLinkStore.save(outcome.link);
                    renderDiscordArea();
                } else {
                    verify.setDisable(false);
                    verify.setText("Verify");
                    err.setText(outcome.userMessage);
                    err.setManaged(true);
                    err.setVisible(true);
                }
            });
        });
    }

    private Region buildVerifiedRow() {
        Label tick = new Label("✓");
        tick.setFont(Fonts.bold(13));
        tick.setStyle("-fx-text-fill: " + TEAL + ";");
        Label who = new Label("Verified" + (discordLink.username != null ? " · " + discordLink.username : ""));
        who.setFont(Fonts.semi(12));
        who.setStyle("-fx-text-fill: " + TEXT + ";");
        HBox line = new HBox(6, tick, who);
        line.setAlignment(Pos.CENTER_LEFT);

        Label unlink = new Label("Unlink");
        unlink.setFont(Fonts.body(10.5));
        unlink.setStyle("-fx-text-fill: " + DIM2 + "; -fx-cursor: hand;");
        unlink.setOnMouseClicked(e -> {
            discordLink = null;
            discordLinkStore.clear();
            renderDiscordArea();
        });
        VBox txt = new VBox(2, line, unlink);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        StackPane square = new StackPane(discordGlyph(20, "white"));
        square.setMinSize(38, 38);
        square.setPrefSize(38, 38);
        square.setMaxSize(38, 38);
        String sBase = "-fx-background-color: " + DISCORD + "; -fx-background-radius: 9; -fx-cursor: hand;";
        String sHov = "-fx-background-color: #6b76f5; -fx-background-radius: 9; -fx-cursor: hand;";
        square.setStyle(sBase);
        square.setOnMouseEntered(e -> square.setStyle(sHov));
        square.setOnMouseExited(e -> square.setStyle(sBase));
        square.setOnMouseClicked(e -> openDiscord());

        HBox row = new HBox(10, txt, gap, square);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color: rgba(88,101,242,0.10); -fx-background-radius: 10;"
                + " -fx-border-color: rgba(88,101,242,0.40); -fx-border-radius: 10; -fx-padding: 8 10 8 10;");
        return row;
    }

    private void openDiscord() {
        String url = (discordConfig != null && discordConfig.inviteUrl != null && !discordConfig.inviteUrl.isBlank())
                ? discordConfig.inviteUrl
                : "https://discord.com/app";
        openLink(url);
    }

    /** Where "Get a code in Discord →" points: the bot DM ({@code codeUrl}) if set, else the invite. */
    private void openCodeSource() {
        String codeUrl = discordConfig != null ? discordConfig.codeUrl : null;
        if (codeUrl != null && !codeUrl.isBlank()) {
            openLink(codeUrl);
        } else {
            openDiscord();
        }
    }

    // ── Server status ───────────────────────────────────────────────────────

    private void refreshStatus() {
        // Only live bases show a server-status pill; coming-soon bases have no such label.
        if (serverStatusLabel == null) {
            return;
        }
        background.submit(() -> {
            try {
                ServerStatus status = fetcher.fetchStatus();
                Platform.runLater(() -> {
                    if (serverStatusLabel == null) {
                        return;
                    }
                    if (status == null) {
                        serverStatusLabel.setText("Server status unavailable");
                        serverStatusLabel.setStyle(statusPillStyle(DIM));
                    } else if (status.online) {
                        serverStatusLabel.setText("●  Online · " + status.playerCount + " players");
                        serverStatusLabel.setStyle(statusPillStyle(TEAL));
                    } else {
                        serverStatusLabel.setText("●  Offline");
                        serverStatusLabel.setStyle(statusPillStyle("#d68a6a"));
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (serverStatusLabel != null) {
                        serverStatusLabel.setText("Server status unavailable");
                        serverStatusLabel.setStyle(statusPillStyle(DIM));
                    }
                });
            }
        });
        background.schedule(this::refreshStatus, 60, TimeUnit.SECONDS);
    }

    // ── Self-update flow ────────────────────────────────────────────────────

    private void startUpdateCheck() {
        if (DevMode.isEnabled()) {
            Platform.runLater(() -> {
                statusLabel.setText("DEV MODE — updates skipped");
                statusLabel.setStyle("-fx-text-fill: " + GOLD_HI + ";");
                progressBar.setProgress(1.0);
                if (playButton != null) playButton.setDisable(false);
            });
            return;
        }
        background.submit(() -> {
            try {
                Platform.runLater(() -> statusLabel.setText("Checking for updates…"));
                VersionInfo info = fetcher.fetchVersionInfo();
                if (selfUpdater.hasUpdate(info)) {
                    applyLauncherUpdate(info);   // silent + auto-relaunch; does not return
                    return;
                }
                Platform.runLater(() -> {
                    statusLabel.setText("Ready");
                    fileLabel.setText("");
                    progressBar.setProgress(1.0);
                    if (playButton != null) playButton.setDisable(false);
                });
            } catch (Exception ex) {
                logger.warn("Update check failed (tolerated): {}", ex.getMessage());
                Platform.runLater(() -> {
                    statusLabel.setText("Ready");
                    progressBar.setProgress(1.0);
                    if (playButton != null) playButton.setDisable(false);
                });
            }
        });
    }

    private void applyLauncherUpdate(VersionInfo info) throws Exception {
        Platform.runLater(() -> statusLabel.setText("Updating… the launcher will reopen"));
        selfUpdater.applyUpdate(info, (downloaded, total) -> Platform.runLater(() -> {
            fileLabel.setText(formatProgress(downloaded, total));
            if (total > 0) {
                progressBar.setProgress((double) downloaded / total);
            }
        }));
    }

    // ── Play ────────────────────────────────────────────────────────────────

    private void onPlay() {
        playButton.setDisable(true);
        statusLabel.setText("Starting client…");
        Account selected = accountDropdown != null ? accountDropdown.getValue() : null;
        final String username = selected != null ? selected.username : null;
        final String password = selected != null && selected.rememberPassword
                ? credentialStore.load(selected.username).orElse(null) : null;
        if (selected != null) {
            accountStore.markUsed(selected.username);
        }
        background.submit(() -> {
            try {
                gameLauncher.launch(ClientLauncher.DEFAULT_HEAP_MB, username, password);
                Platform.runLater(() -> {
                    statusLabel.setText("Client launched — see you in Oasis!");
                    background.schedule(() -> Platform.runLater(stage::close), 2, TimeUnit.SECONDS);
                });
            } catch (Exception ex) {
                logger.error("Failed to launch client", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Client not available yet — coming with the next update.");
                    if (playButton != null) playButton.setDisable(false);
                });
            }
        });
    }

    // ── Account management ──────────────────────────────────────────────────

    private void refreshAccountDropdown() {
        if (accountDropdown == null) {
            return;
        }
        List<Account> accounts = accountStore.list();
        accountDropdown.setItems(FXCollections.observableArrayList(accounts));
        if (!accountDropdown.getItems().isEmpty()) {
            accountDropdown.getSelectionModel().selectFirst();
        }
    }

    private void onAddAccount() {
        Optional<NewAccountInput> result = showAccountDialog();
        if (result.isEmpty()) {
            return;
        }
        NewAccountInput input = result.get();
        Account account = new Account(input.username, input.rememberPassword);
        account.displayName = input.displayName.isBlank() ? input.username : input.displayName;
        accountStore.save(account);
        if (input.rememberPassword && input.password != null && !input.password.isEmpty()) {
            credentialStore.save(input.username, input.password);
        }
        refreshAccountDropdown();
    }

    private void onRemoveAccount() {
        Account selected = accountDropdown.getValue();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove account");
        confirm.setHeaderText("Remove " + selected.label() + "?");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        credentialStore.delete(selected.username);
        accountStore.remove(selected.username);
        refreshAccountDropdown();
    }

    private Optional<NewAccountInput> showAccountDialog() {
        Dialog<NewAccountInput> dialog = new Dialog<>();
        dialog.setTitle("Add account");
        dialog.setHeaderText("Enter the username and (optionally) password for this account.");
        ButtonType save = new ButtonType("Save", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField userField = new TextField();
        userField.setPromptText("Username");
        TextField displayField = new TextField();
        displayField.setPromptText("Display label (optional)");
        PasswordField pwdField = new PasswordField();
        pwdField.setPromptText("Password");
        CheckBox remember = new CheckBox("Remember password securely");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(14, 12, 6, 12));
        grid.add(new Label("Username:"), 0, 0);
        grid.add(userField, 1, 0);
        grid.add(new Label("Display:"), 0, 1);
        grid.add(displayField, 1, 1);
        grid.add(new Label("Password:"), 0, 2);
        grid.add(pwdField, 1, 2);
        grid.add(remember, 1, 3);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != save) {
                return null;
            }
            NewAccountInput in = new NewAccountInput();
            in.username = userField.getText() == null ? "" : userField.getText().trim();
            in.displayName = displayField.getText() == null ? "" : displayField.getText().trim();
            in.password = pwdField.getText();
            in.rememberPassword = remember.isSelected();
            return in.username.isEmpty() ? null : in;
        });
        return dialog.showAndWait();
    }

    private static class NewAccountInput {
        String username;
        String displayName = "";
        String password = "";
        boolean rememberPassword;
    }

    private static String formatProgress(long downloaded, long total) {
        return total <= 0 ? formatBytes(downloaded) : formatBytes(downloaded) + " / " + formatBytes(total);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
