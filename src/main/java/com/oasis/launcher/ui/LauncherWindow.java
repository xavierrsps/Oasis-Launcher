package com.oasis.launcher.ui;

import com.oasis.launcher.DevMode;
import com.oasis.launcher.account.AccountStore;
import com.oasis.launcher.account.CredentialStore;
import com.oasis.launcher.launch.ClientLauncher;
import com.oasis.launcher.model.Account;
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
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
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

    public LauncherWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setTop(buildTop());
        root.setCenter(buildBody());
        root.setBottom(buildBottomBar());

        Scene scene = new Scene(root, 1010, 640);
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

        refreshStatus();
        loadNews();
        startUpdateCheck();
    }

    // ── Top bar: game tabs + account + logo ─────────────────────────────────

    private Region buildTop() {
        HBox games = new HBox(2);
        games.setAlignment(Pos.CENTER_LEFT);
        games.getChildren().addAll(
                gameTab(oasisEmblem(), "Oasis", true, this::selectOasis),
                gameTab(monogramEmblem("RS", "#f0cf67", "#a9781f"), "RuneScape", false, () -> comingSoon("RuneScape 3")),
                gameTab(monogramEmblem("OS", "#9adfa6", "#3f7d4e"), "Old School", false, () -> comingSoon("Old School base")));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label settings = chip("⚙  Settings");
        Label account = chip("Xavier  ▾");

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

    private Region gameTab(Node emblem, String label, boolean active, Runnable onClick) {
        Label text = new Label(label);
        text.setFont(Font.font("System", FontWeight.BOLD, 14));
        text.setStyle("-fx-text-fill: " + (active ? GOLD_HI : DIM) + ";");

        HBox tab = new HBox(9);
        tab.setAlignment(Pos.CENTER_LEFT);
        if (emblem != null) {
            tab.getChildren().add(emblem);
        }
        tab.getChildren().add(text);
        tab.setMinHeight(66);
        tab.setPrefHeight(66);
        tab.setPadding(new Insets(0, 17, 0, 17));
        tab.setStyle(gameTabStyle(active));
        tab.setOnMouseClicked(e -> onClick.run());
        if (!active) {
            if (emblem != null) {
                emblem.setOpacity(0.82);
            }
            tab.setOnMouseEntered(e -> {
                text.setStyle("-fx-text-fill: " + TEXT + ";");
                tab.setStyle(gameTabStyleHover());
                if (emblem != null) {
                    emblem.setOpacity(1.0);
                }
            });
            tab.setOnMouseExited(e -> {
                text.setStyle("-fx-text-fill: " + DIM + ";");
                tab.setStyle(gameTabStyle(false));
                if (emblem != null) {
                    emblem.setOpacity(0.82);
                }
            });
        }
        return tab;
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

    /** The Oasis tab shows the real gold-O icon; falls back to a monogram badge if the image is missing. */
    private Node oasisEmblem() {
        ImageView iv = loadImage("/images/icon.png", 24, 24);
        return iv != null ? iv : monogramEmblem("O", GOLD_HI, EMBER);
    }

    /** A small circular game emblem drawn in code (guaranteed to render, no bundled image needed). */
    private Region monogramEmblem(String letters, String c1, String c2) {
        Label l = new Label(letters);
        l.setAlignment(Pos.CENTER);
        l.setMinSize(24, 24);
        l.setPrefSize(24, 24);
        l.setMaxSize(24, 24);
        l.setFont(Font.font("System", FontWeight.BOLD, letters.length() > 1 ? 9.5 : 12));
        l.setStyle("-fx-text-fill: #1c1206; -fx-background-radius: 12;"
                + " -fx-background-color: radial-gradient(center 34% 28%, radius 72%, " + c1 + ", " + c2 + ");"
                + " -fx-border-color: rgba(0,0,0,0.4); -fx-border-radius: 12;");
        return l;
    }

    private Label chip(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("System", 13));
        l.setStyle("-fx-text-fill: " + DIM + "; -fx-padding: 8 12 8 12; -fx-background-radius: 9; -fx-cursor: hand;");
        return l;
    }

    // ── Body: content (hero + news) + right play panel ──────────────────────

    private Region buildBody() {
        VBox content = new VBox(0);
        content.setPadding(new Insets(18, 30, 24, 22));
        content.getChildren().add(buildHero());
        content.getChildren().add(buildUpdatesHeader());
        newsGrid = new FlowPane(13, 13);
        newsGrid.setPrefWrapLength(660);
        Label loading = new Label("Loading updates…");
        loading.setStyle("-fx-text-fill: " + DIM + ";");
        newsGrid.getChildren().add(loading);
        content.getChildren().add(newsGrid);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        HBox body = new HBox(scroll, buildRightPanel());
        return body;
    }

    private Region buildHero() {
        StackPane hero = new StackPane();
        hero.setMinHeight(198);
        hero.setPrefHeight(198);
        hero.setStyle("-fx-background-color: #17100a;");

        // The logo, blurred + darkened, as an ambient backdrop (Xavier's "logo but blurred").
        ImageView bg = loadImage("/images/background.png", -1, -1);
        if (bg != null) {
            bg.setPreserveRatio(true);
            bg.setFitWidth(940);
            bg.setEffect(new GaussianBlur(28));
            StackPane.setAlignment(bg, Pos.CENTER);
            hero.getChildren().add(bg);
        }

        Region tint = new Region();
        tint.setStyle("-fx-background-color: linear-gradient(to right, rgba(9,6,3,0.95) 0%, rgba(9,6,3,0.64) 46%, rgba(9,6,3,0.24) 100%);");
        hero.getChildren().add(tint);

        Label kick = new Label("NEW BASE · LIVE NOW");
        kick.setFont(Font.font("System", FontWeight.BOLD, 10.5));
        kick.setMaxWidth(Region.USE_PREF_SIZE);
        kick.setStyle("-fx-text-fill: #2a1a06; -fx-background-color: linear-gradient(to bottom, " + GOLD_HI + ", " + GOLD
                + "); -fx-padding: 3 9 3 9; -fx-background-radius: 5;");
        Label title = new Label("Oasis 2.0 — reforged on Old School");
        title.setFont(Font.font("System", FontWeight.BOLD, 27));
        title.setStyle("-fx-text-fill: white;");
        title.setWrapText(true);
        title.setEffect(new DropShadow(8, Color.rgb(0, 0, 0, 0.7)));
        Label sub = new Label("A modern RuneScape (rev 240) foundation with a custom RuneLite client.");
        sub.setFont(Font.font("System", 13));
        sub.setStyle("-fx-text-fill: #e9dcc0;");
        sub.setWrapText(true);
        VBox txt = new VBox(9, kick, title, sub);
        txt.setAlignment(Pos.BOTTOM_LEFT);
        txt.setMaxWidth(470);
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
        section.setFont(Font.font("System", FontWeight.BOLD, 14));
        section.setStyle("-fx-text-fill: " + GOLD + "; -fx-letter-spacing: 3;");
        Label src = new Label("# synced from Discord");
        src.setFont(Font.font("System", 10.5));
        src.setStyle("-fx-text-fill: #9aa4f5; -fx-background-color: rgba(88,101,242,0.14);"
                + " -fx-border-color: rgba(88,101,242,0.4); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 3 8 3 8;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label viewAll = new Label("View all →");
        viewAll.setFont(Font.font("System", FontWeight.BOLD, 12));
        viewAll.setStyle("-fx-text-fill: " + GOLD_DIM + "; -fx-cursor: hand;");
        HBox head = new HBox(11, section, src, spacer, viewAll);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(24, 2, 13, 2));
        return head;
    }

    private Region buildRightPanel() {
        VBox panel = new VBox(13);
        panel.setPrefWidth(312);
        panel.setMinWidth(312);
        panel.setPadding(new Insets(18, 18, 16, 18));
        panel.setStyle("-fx-background-color: linear-gradient(to bottom, #271d10, #19110a);"
                + " -fx-border-color: " + LINE2 + "; -fx-border-width: 0 0 0 1;");

        ImageView baseIcon = loadImage("/images/icon.png", 36, 36);
        Label baseName = new Label("Oasis");
        baseName.setFont(Font.font("System", FontWeight.BOLD, 19));
        baseName.setStyle("-fx-text-fill: " + GOLD_HI + ";");
        Label baseSub = new Label("Old School · rev 240");
        baseSub.setFont(Font.font("System", 11));
        baseSub.setStyle("-fx-text-fill: " + DIM2 + ";");
        VBox baseText = new VBox(2, baseName, baseSub);
        HBox base = new HBox(11);
        base.setAlignment(Pos.CENTER_LEFT);
        if (baseIcon != null) {
            base.getChildren().add(baseIcon);
        }
        base.getChildren().add(baseText);
        base.setPadding(new Insets(0, 0, 13, 0));
        base.setStyle("-fx-border-color: " + LINE + "; -fx-border-width: 0 0 1 0;");

        playButton = new Button("PLAY");
        playButton.setDisable(true);
        playButton.setMaxWidth(Double.MAX_VALUE);
        playButton.setPrefHeight(54);
        playButton.setFont(Font.font("System", FontWeight.BOLD, 21));
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
        serverStatusLabel.setFont(Font.font("System", 12.5));
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

        Button discord = new Button("Log in with Discord");
        discord.setFont(Font.font("System", FontWeight.BOLD, 12.5));
        discord.setMaxWidth(Double.MAX_VALUE);
        discord.setStyle("-fx-background-color: " + DISCORD + "; -fx-text-fill: white; -fx-background-radius: 9;"
                + " -fx-cursor: hand; -fx-padding: 11;");
        discord.setOnAction(e -> onDiscordLogin());

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        panel.getChildren().addAll(base, playButton,
                label("GAME CLIENT"), client,
                label("CHARACTER"), acctRow,
                serverStatusLabel, grow, discord);
        refreshAccountDropdown();
        return panel;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("System", 11));
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
        statusLabel.setFont(Font.font("System", 12));
        statusLabel.setStyle("-fx-text-fill: " + DIM + ";");
        fileLabel = new Label("");
        fileLabel.setFont(Font.font("System", 10));
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
        version.setFont(Font.font("System", 12));
        version.setStyle("-fx-text-fill: " + DIM2 + ";");

        HBox bar = new HBox(12, left, spacer, version);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 18, 0, 18));
        bar.setMinHeight(35);
        bar.setStyle("-fx-background-color: #130d07; -fx-border-color: " + LINE2 + "; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    // ── News (Discord-post cards) ───────────────────────────────────────────

    private void loadNews() {
        background.submit(() -> {
            try {
                NewsFeed feed = fetcher.fetchNews();
                Platform.runLater(() -> renderNews(feed));
            } catch (Exception ex) {
                logger.warn("Could not fetch news: {}", ex.getMessage());
                Platform.runLater(() -> {
                    newsGrid.getChildren().clear();
                    Label err = new Label("Could not load updates.");
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
        card.setPrefWidth(314);
        card.setMinWidth(290);
        card.setStyle(cardStyle(false));

        if (u.image != null && !u.image.isBlank()) {
            ImageView img = new ImageView(new Image(u.image, 322, 104, false, true, true));
            img.setFitWidth(322);
            img.setFitHeight(104);
            StackPane band = new StackPane(img);
            band.setMinHeight(104);
            band.setMaxHeight(104);
            band.setStyle("-fx-border-color: " + LINE + "; -fx-border-width: 0 0 1 0;");
            card.getChildren().add(band);
        }

        Label title = new Label(u.title != null ? u.title : (u.body != null ? firstLine(u.body) : "(update)"));
        title.setFont(Font.font("System", FontWeight.BOLD, 14));
        title.setStyle("-fx-text-fill: " + TEXT + ";");
        title.setWrapText(true);

        VBox body = new VBox(7);
        body.setPadding(new Insets(12, 14, 13, 14));
        body.getChildren().add(title);

        if (u.body != null && !u.body.isBlank()) {
            Label p = new Label(u.body);
            p.setFont(Font.font("System", 12));
            p.setStyle("-fx-text-fill: " + DIM + ";");
            p.setWrapText(true);
            p.setMaxHeight(38);
            body.getChildren().add(p);
        }

        HBox foot = new HBox(8);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.getChildren().add(avatarNode(u.author, u.avatar));
        Label who = new Label(u.author != null && !u.author.isBlank() ? u.author : "Oasis");
        who.setFont(Font.font("System", FontWeight.BOLD, 11.5));
        who.setStyle("-fx-text-fill: " + GOLD_DIM + ";");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label date = new Label(u.date != null ? u.date : "");
        date.setFont(Font.font("System", 11));
        date.setStyle("-fx-text-fill: " + DIM2 + ";");
        foot.getChildren().addAll(who, gap, date);
        body.getChildren().add(foot);

        card.getChildren().add(body);

        // Real hover animation (JavaFX ignores CSS :hover transitions — must be done in code):
        // a smooth lift + a warm gold glow, reversing on exit.
        DropShadow rest = new DropShadow(14, Color.rgb(0, 0, 0, 0.5));
        DropShadow hot = new DropShadow(24, Color.rgb(244, 181, 63, 0.34));
        card.setEffect(rest);
        TranslateTransition lift = new TranslateTransition(Duration.millis(150), card);
        card.setOnMouseEntered(e -> {
            card.setStyle(cardStyle(true));
            card.setEffect(hot);
            lift.stop();
            lift.setToY(-6);
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
        a.setFont(Font.font("System", FontWeight.BOLD, 10));
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

    // ── Game tabs ───────────────────────────────────────────────────────────

    private void selectOasis() {
        // Oasis is the active base; nothing to switch yet.
    }

    private void comingSoon(String what) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Coming soon");
        a.setHeaderText(what + " is coming soon.");
        a.setContentText("Oasis is the live base for now. More game bases will slot in here.");
        a.showAndWait();
    }

    private void onDiscordLogin() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Discord sign-in");
        a.setHeaderText("Discord sign-in is being wired up.");
        a.setContentText("Once the Discord app's Client ID is in, this opens Discord to sign you in.");
        a.showAndWait();
    }

    // ── Server status ───────────────────────────────────────────────────────

    private void refreshStatus() {
        background.submit(() -> {
            try {
                ServerStatus status = fetcher.fetchStatus();
                Platform.runLater(() -> {
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
                    serverStatusLabel.setText("Server status unavailable");
                    serverStatusLabel.setStyle(statusPillStyle(DIM));
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
                playButton.setDisable(false);
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
                    playButton.setDisable(false);
                });
            } catch (Exception ex) {
                logger.warn("Update check failed (tolerated): {}", ex.getMessage());
                Platform.runLater(() -> {
                    statusLabel.setText("Ready");
                    progressBar.setProgress(1.0);
                    playButton.setDisable(false);
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
                    playButton.setDisable(false);
                });
            }
        });
    }

    // ── Account management ──────────────────────────────────────────────────

    private void refreshAccountDropdown() {
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
