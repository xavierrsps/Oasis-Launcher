package com.oasis.launcher.ui;

import com.oasis.launcher.DevMode;
import com.oasis.launcher.account.AccountStore;
import com.oasis.launcher.account.CredentialStore;
import com.oasis.launcher.launch.ClientLauncher;
import com.oasis.launcher.model.Account;
import com.oasis.launcher.model.Manifest;
import com.oasis.launcher.model.NewsFeed;
import com.oasis.launcher.model.ServerStatus;
import com.oasis.launcher.update.Downloader;
import com.oasis.launcher.update.LauncherSelfUpdater;
import com.oasis.launcher.update.ManifestFetcher;
import com.oasis.launcher.update.UpdateManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.CheckBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main launcher window.
 *
 * <p>Layout (top to bottom):
 * <ul>
 *   <li>Header: logo placeholder + server status</li>
 *   <li>News feed: scrollable list of articles</li>
 *   <li>Footer: progress bar + status text + Play button</li>
 * </ul>
 */
public class LauncherWindow {

    private static final Logger logger = LogManager.getLogger(LauncherWindow.class);

    /** Accent color used throughout the launcher (royal blue, matches in-game admin broadcasts). */
    public static final String ACCENT = "#4169e1";
    public static final String BG_DARK = "#1a1a1a";
    public static final String BG_PANEL = "#252525";
    public static final String TEXT_LIGHT = "#e0e0e0";
    public static final String TEXT_DIM = "#888888";

    private final Stage stage;
    private final ManifestFetcher fetcher = new ManifestFetcher();
    private final UpdateManager updater = new UpdateManager();
    private final LauncherSelfUpdater selfUpdater = new LauncherSelfUpdater();
    private final ClientLauncher gameLauncher = new ClientLauncher();
    private final AccountStore accountStore = new AccountStore();
    private final CredentialStore credentialStore = new CredentialStore();
    private final ScheduledExecutorService background = Executors.newScheduledThreadPool(2,
            r -> {
                Thread t = new Thread(r, "launcher-bg");
                t.setDaemon(true);
                return t;
            });

    // UI components (kept as fields so background tasks can update them)
    private Label statusLabel;
    private Label fileLabel;
    private ProgressBar progressBar;
    private Button playButton;
    private Label serverStatusLabel;
    private VBox newsBox;
    private ComboBox<Account> accountDropdown;

    public LauncherWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        BorderPane content = new BorderPane();
        // Transparent so the background image shows through.
        content.setStyle("-fx-background-color: transparent;");
        content.setTop(buildHeader());
        content.setCenter(buildNewsArea());
        content.setBottom(buildFooter());

        // Root is a StackPane: background image on the bottom layer,
        // content (with semi-transparent panels) on top.
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: " + BG_DARK + ";");

        ImageView bgImage = tryLoadBackground();
        if (bgImage != null) {
            root.getChildren().add(bgImage);
        }
        root.getChildren().add(content);

        Scene scene = new Scene(root, 720, 520);
        stage.setScene(scene);
        stage.setTitle("Oasis Launcher");
        stage.setResizable(false);

        // Try to set the window/taskbar icon from the same logo.
        try {
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception ex) {
            logger.warn("Could not set window icon: {}", ex.getMessage());
        }

        stage.setOnCloseRequest(e -> background.shutdownNow());
        stage.show();

        // Kick off initial checks.
        refreshStatus();
        loadNews();
        startUpdateCheck();
    }

    /**
     * Loads /images/logo.png as a full-window background image, scaled to
     * fit the window. Returns null if no logo file is present.
     */
    private ImageView tryLoadBackground() {
        InputStream stream = getClass().getResourceAsStream("/images/logo.png");
        if (stream == null) {
            return null;
        }
        Image img = new Image(stream);
        if (img.isError()) {
            return null;
        }
        ImageView view = new ImageView(img);
        view.setPreserveRatio(true);
        view.setFitWidth(720);
        view.setFitHeight(520);
        view.setSmooth(true);
        // Dim it a bit so the foreground text/buttons stay readable.
        view.setOpacity(0.35);
        return view;
    }

    // ── Header ────────────────────────────────────────────────────────────

    private Region buildHeader() {
        // No logo here anymore — it lives in the background.
        Label tagline = new Label("Oasis ~ #1 Upcoming Semi-Custom RSPS");
        tagline.setFont(Font.font("System", FontWeight.BOLD, 14));
        tagline.setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");

        VBox titleBox = new VBox(tagline);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        serverStatusLabel = new Label("Checking server…");
        serverStatusLabel.setFont(Font.font("System", 12));
        serverStatusLabel.setStyle("-fx-text-fill: " + TEXT_DIM + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(titleBox, spacer, serverStatusLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 22, 14, 22));
        // Semi-transparent panel so the background logo shows through faintly.
        header.setStyle("-fx-background-color: rgba(26, 26, 26, 0.85);"
                + " -fx-border-color: " + ACCENT + ";"
                + " -fx-border-width: 0 0 2 0;");
        return header;
    }

    // ── News feed ─────────────────────────────────────────────────────────

    private Region buildNewsArea() {
        newsBox = new VBox(10);
        newsBox.setPadding(new Insets(16, 22, 16, 22));

        Label header = new Label("Latest News");
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");
        newsBox.getChildren().add(header);

        Label loading = new Label("Loading news…");
        loading.setStyle("-fx-text-fill: " + TEXT_DIM + ";");
        newsBox.getChildren().add(loading);

        ScrollPane scroll = new ScrollPane(newsBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent;"
                + " -fx-background-color: transparent;");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    // ── Footer with progress bar and Play button ─────────────────────────

    private Region buildFooter() {
        statusLabel = new Label("Idle");
        statusLabel.setFont(Font.font("System", 12));
        statusLabel.setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");

        fileLabel = new Label("");
        fileLabel.setFont(Font.font("System", 10));
        fileLabel.setStyle("-fx-text-fill: " + TEXT_DIM + ";");

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(8);
        progressBar.setStyle("-fx-accent: " + ACCENT + ";");

        VBox progressBox = new VBox(4, statusLabel, fileLabel, progressBar);
        progressBox.setMaxWidth(Double.MAX_VALUE);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBox, Priority.ALWAYS);

        playButton = new Button("PLAY");
        playButton.setDisable(true);
        playButton.setMinSize(140, 56);
        playButton.setPrefSize(140, 56);
        playButton.setMaxSize(140, 56);
        playButton.setFont(Font.font("System", FontWeight.BOLD, 18));
        playButton.setStyle("-fx-background-color: " + ACCENT + ";"
                + " -fx-text-fill: white;"
                + " -fx-background-radius: 4;"
                + " -fx-cursor: hand;");
        playButton.setOnAction(e -> onPlay());

        // ── Account selection bar (above the progress section) ──
        Label accountLabel = new Label("Account:");
        accountLabel.setFont(Font.font("System", 11));
        accountLabel.setStyle("-fx-text-fill: " + TEXT_DIM + ";");

        accountDropdown = new ComboBox<>();
        accountDropdown.setPrefWidth(220);
        accountDropdown.setPromptText("No accounts saved");
        accountDropdown.setStyle("-fx-background-color: rgba(26, 26, 26, 0.9);"
                + " -fx-text-fill: " + TEXT_LIGHT + ";"
                + " -fx-border-color: rgba(65, 105, 225, 0.5);"
                + " -fx-border-radius: 4;");
        accountDropdown.setCellFactory(lv -> new javafx.scene.control.ListCell<Account>() {
            @Override protected void updateItem(Account item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
                setStyle("-fx-text-fill: " + TEXT_LIGHT + "; -fx-background-color: " + BG_PANEL + ";");
            }
        });
        accountDropdown.setButtonCell(new javafx.scene.control.ListCell<Account>() {
            @Override protected void updateItem(Account item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "No account selected" : item.label());
                setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");
            }
        });

        Button addBtn = new Button("+");
        addBtn.setStyle("-fx-background-color: rgba(65, 105, 225, 0.8);"
                + " -fx-text-fill: white;"
                + " -fx-background-radius: 4;"
                + " -fx-cursor: hand;"
                + " -fx-font-weight: bold;");
        addBtn.setPrefSize(28, 28);
        addBtn.setOnAction(e -> onAddAccount());

        Button removeBtn = new Button("−");
        removeBtn.setStyle("-fx-background-color: rgba(80, 80, 80, 0.8);"
                + " -fx-text-fill: white;"
                + " -fx-background-radius: 4;"
                + " -fx-cursor: hand;"
                + " -fx-font-weight: bold;");
        removeBtn.setPrefSize(28, 28);
        removeBtn.setOnAction(e -> onRemoveAccount());

        HBox accountBar = new HBox(8, accountLabel, accountDropdown, addBtn, removeBtn);
        accountBar.setAlignment(Pos.CENTER_LEFT);

        VBox bottomLeft = new VBox(8, accountBar, progressBox);
        bottomLeft.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bottomLeft, Priority.ALWAYS);

        HBox footer = new HBox(20, bottomLeft, playButton);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(16, 22, 18, 22));
        footer.setStyle("-fx-background-color: rgba(26, 26, 26, 0.85);"
                + " -fx-border-color: " + ACCENT + ";"
                + " -fx-border-width: 2 0 0 0;");

        // Populate dropdown with saved accounts.
        refreshAccountDropdown();
        return footer;
    }

    // ── Background tasks ──────────────────────────────────────────────────

    private void refreshStatus() {
        background.submit(() -> {
            try {
                ServerStatus status = fetcher.fetchStatus();
                Platform.runLater(() -> {
                    if (status == null) {
                        serverStatusLabel.setText("● Status unavailable");
                        serverStatusLabel.setStyle("-fx-text-fill: " + TEXT_DIM + ";");
                    } else if (status.online) {
                        serverStatusLabel.setText("● Online — " + status.playerCount + " players");
                        serverStatusLabel.setStyle("-fx-text-fill: #6bd16b;");
                    } else {
                        serverStatusLabel.setText("● Offline");
                        serverStatusLabel.setStyle("-fx-text-fill: #d16b6b;");
                    }
                });
            } catch (Exception ex) {
                logger.warn("Could not fetch server status: {}", ex.getMessage());
                Platform.runLater(() -> {
                    serverStatusLabel.setText("● Status unavailable");
                    serverStatusLabel.setStyle("-fx-text-fill: " + TEXT_DIM + ";");
                });
            }
        });
        // Re-check every 60s.
        background.schedule(this::refreshStatus, 60, TimeUnit.SECONDS);
    }

    private void loadNews() {
        background.submit(() -> {
            try {
                NewsFeed feed = fetcher.fetchNews();
                Platform.runLater(() -> renderNews(feed));
            } catch (Exception ex) {
                logger.warn("Could not fetch news: {}", ex.getMessage());
                Platform.runLater(() -> {
                    newsBox.getChildren().clear();
                    Label header = new Label("Latest News");
                    header.setFont(Font.font("System", FontWeight.BOLD, 14));
                    header.setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");
                    Label err = new Label("Could not load news.");
                    err.setStyle("-fx-text-fill: " + TEXT_DIM + ";");
                    newsBox.getChildren().addAll(header, err);
                });
            }
        });
    }

    private void renderNews(NewsFeed feed) {
        newsBox.getChildren().clear();
        Label header = new Label("Latest News");
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");
        newsBox.getChildren().add(header);

        if (feed == null || feed.articles == null || feed.articles.isEmpty()) {
            Label empty = new Label("No news yet.");
            empty.setStyle("-fx-text-fill: " + TEXT_DIM + ";");
            newsBox.getChildren().add(empty);
            return;
        }
        for (NewsFeed.Article article : feed.articles) {
            newsBox.getChildren().add(buildArticleCard(article));
        }
    }

    private Region buildArticleCard(NewsFeed.Article article) {
        Label title = new Label(article.title != null ? article.title : "(untitled)");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        title.setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");

        Label date = new Label(article.date != null ? article.date : "");
        date.setFont(Font.font("System", 10));
        date.setStyle("-fx-text-fill: " + ACCENT + ";");

        Label summary = new Label(article.summary != null ? article.summary : "");
        summary.setFont(Font.font("System", 12));
        summary.setStyle("-fx-text-fill: " + TEXT_LIGHT + ";");
        summary.setWrapText(true);

        VBox card = new VBox(3, title, date, summary);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setStyle("-fx-background-color: rgba(37, 37, 37, 0.9);"
                + " -fx-background-radius: 4;");
        return card;
    }

    private void startUpdateCheck() {
        // Dev escape hatch: skip the entire update flow. Used when iterating on the
        // client locally — without this every ./gradlew run on the launcher overwrites
        // your freshly built client jar with whatever's published to GitHub.
        if (DevMode.isEnabled()) {
            Platform.runLater(() -> {
                statusLabel.setText("DEV MODE — updates skipped");
                statusLabel.setStyle("-fx-text-fill: #ffcc66;");
                java.nio.file.Path jarPath = DevMode.clientJarOverride() != null
                        ? DevMode.clientJarOverride()
                        : com.oasis.launcher.util.Platform.clientJar();
                fileLabel.setText("Launching: " + jarPath);
                progressBar.setProgress(1.0);
                playButton.setDisable(false);
            });
            return;
        }
        background.submit(() -> {
            try {
                // ── Step 1: Check if THE LAUNCHER ITSELF needs an update ──
                //
                // We fetch the manifest first, look for a LAUNCHER entry,
                // and if its version is newer than ours, prompt the user
                // before downloading + installing.
                Platform.runLater(() -> statusLabel.setText("Checking for launcher update…"));
                Manifest manifest = fetcher.fetchManifest();
                Optional<Manifest.ManifestFile> launcherUpdate =
                        selfUpdater.checkForUpdate(manifest);

                if (launcherUpdate.isPresent()) {
                    boolean accepted = promptLauncherUpdate(manifest.launcherVersion);
                    if (accepted) {
                        applyLauncherUpdate(launcherUpdate.get());
                        return;  // applyLauncherUpdate calls System.exit
                    }
                    logger.info("User declined launcher update; continuing with client update.");
                }

                // ── Step 2: Normal client/cache/resource update ──
                Manifest finalManifest = updater.update(new UpdateManager.ProgressListener() {
                    @Override public void status(String message) {
                        Platform.runLater(() -> statusLabel.setText(message));
                    }
                    @Override public void fileProgress(long downloaded, long total) {
                        Platform.runLater(() -> fileLabel.setText(formatProgress(downloaded, total)));
                    }
                    @Override public void overallProgress(double fraction) {
                        Platform.runLater(() -> progressBar.setProgress(fraction));
                    }
                });
                Platform.runLater(() -> {
                    statusLabel.setText("Ready to play — v" + finalManifest.clientVersion);
                    fileLabel.setText("");
                    progressBar.setProgress(1.0);
                    playButton.setDisable(false);
                });
            } catch (Exception ex) {
                logger.error("Update failed", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Update failed: " + ex.getMessage());
                    statusLabel.setStyle("-fx-text-fill: #d16b6b;");
                    fileLabel.setText("Check your internet connection or try again later.");
                });
            }
        });
    }

    /**
     * Shows a confirmation dialog asking the user whether they want to
     * install a new launcher version. Blocks the background thread until
     * the user picks; the actual dialog runs on the JavaFX thread.
     *
     * @return true if the user clicked "Update now"
     */
    private boolean promptLauncherUpdate(String newVersion) {
        final boolean[] result = {false};
        final Object lock = new Object();
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Launcher update available");
            alert.setHeaderText("A new version of the Oasis Launcher is available.");
            alert.setContentText("Current version: " + LauncherSelfUpdater.CURRENT_VERSION + "\n"
                    + "New version: " + newVersion + "\n\n"
                    + "Click 'Update now' to download and install. The launcher "
                    + "will close while the installer runs (~30 seconds).");
            ButtonType updateNow = new ButtonType("Update now");
            ButtonType later = new ButtonType("Later", ButtonType.CANCEL.getButtonData());
            alert.getButtonTypes().setAll(updateNow, later);
            Optional<ButtonType> choice = alert.showAndWait();
            synchronized (lock) {
                result[0] = choice.isPresent() && choice.get() == updateNow;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return result[0];
    }

    /**
     * Downloads the launcher MSI and triggers msiexec. Does not return.
     */
    private void applyLauncherUpdate(Manifest.ManifestFile file) throws Exception {
        Platform.runLater(() -> statusLabel.setText("Downloading launcher update…"));
        selfUpdater.applyUpdate(file, (downloaded, total) -> {
            Platform.runLater(() -> {
                fileLabel.setText(formatProgress(downloaded, total));
                if (total > 0) {
                    progressBar.setProgress((double) downloaded / total);
                }
            });
        });
    }

    private void onPlay() {
        playButton.setDisable(true);
        statusLabel.setText("Starting client…");

        // Resolve which account (if any) the user picked from the dropdown.
        Account selected = accountDropdown != null ? accountDropdown.getValue() : null;
        final String username;
        final String password;
        if (selected != null) {
            username = selected.username;
            password = selected.rememberPassword
                    ? credentialStore.load(selected.username).orElse(null)
                    : null;
            accountStore.markUsed(selected.username);
        } else {
            username = null;
            password = null;
        }

        background.submit(() -> {
            try {
                gameLauncher.launch(ClientLauncher.DEFAULT_HEAP_MB, username, password);
                Platform.runLater(() -> {
                    statusLabel.setText("Client launched — happy gaming!");
                    background.schedule(
                            () -> Platform.runLater(() -> stage.close()),
                            2, TimeUnit.SECONDS);
                });
            } catch (Exception ex) {
                logger.error("Failed to launch client", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to launch: " + ex.getMessage());
                    playButton.setDisable(false);
                });
            }
        });
    }

    // ── Account management ────────────────────────────────────────────────

    /** Reloads the dropdown contents from the AccountStore. */
    private void refreshAccountDropdown() {
        List<Account> accounts = accountStore.list();
        ObservableList<Account> items = FXCollections.observableArrayList(accounts);
        accountDropdown.setItems(items);
        if (!items.isEmpty()) {
            accountDropdown.getSelectionModel().selectFirst();
        }
    }

    /** Opens the "Add account" dialog. */
    private void onAddAccount() {
        Optional<NewAccountInput> result = showAccountDialog(null);
        if (result.isEmpty()) return;
        NewAccountInput input = result.get();

        Account account = new Account(input.username, input.rememberPassword);
        account.displayName = input.displayName.isBlank() ? input.username : input.displayName;
        accountStore.save(account);

        if (input.rememberPassword && input.password != null && !input.password.isEmpty()) {
            boolean ok = credentialStore.save(input.username, input.password);
            if (!ok) {
                logger.warn("Saving password to credential store failed for {}", input.username);
            }
        }

        refreshAccountDropdown();
        // Select the new account.
        for (Account a : accountDropdown.getItems()) {
            if (a.username.equalsIgnoreCase(input.username)) {
                accountDropdown.getSelectionModel().select(a);
                break;
            }
        }
    }

    /** Confirms then removes the currently selected account. */
    private void onRemoveAccount() {
        Account selected = accountDropdown.getValue();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove account");
        confirm.setHeaderText("Remove " + selected.label() + "?");
        confirm.setContentText("The username and any saved password will be removed from this launcher.");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) return;

        credentialStore.delete(selected.username);
        accountStore.remove(selected.username);
        refreshAccountDropdown();
    }

    /**
     * Shows the add/edit-account dialog. Pass null to add a new one.
     * Returns the entered values, or empty if the user cancelled.
     */
    private Optional<NewAccountInput> showAccountDialog(Account existing) {
        Dialog<NewAccountInput> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "Add account" : "Edit account");
        dialog.setHeaderText(existing == null
                ? "Enter the username and (optionally) password for this account."
                : "Update this account's saved details.");

        ButtonType save = new ButtonType("Save", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField userField = new TextField();
        userField.setPromptText("Username");
        if (existing != null) userField.setText(existing.username);

        TextField displayField = new TextField();
        displayField.setPromptText("Display label (optional, e.g. 'My Iron')");
        if (existing != null) displayField.setText(existing.displayName);

        PasswordField pwdField = new PasswordField();
        pwdField.setPromptText("Password (only saved if 'Remember password' is checked)");

        CheckBox remember = new CheckBox("Remember password securely");
        remember.setSelected(existing != null && existing.rememberPassword);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.setPadding(new Insets(14, 12, 6, 12));
        grid.add(new Label("Username:"), 0, 0); grid.add(userField, 1, 0);
        grid.add(new Label("Display:"),  0, 1); grid.add(displayField, 1, 1);
        grid.add(new Label("Password:"), 0, 2); grid.add(pwdField, 1, 2);
        grid.add(remember, 1, 3);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != save) return null;
            NewAccountInput in = new NewAccountInput();
            in.username = userField.getText() == null ? "" : userField.getText().trim();
            in.displayName = displayField.getText() == null ? "" : displayField.getText().trim();
            in.password = pwdField.getText();
            in.rememberPassword = remember.isSelected();
            return in.username.isEmpty() ? null : in;
        });

        return dialog.showAndWait();
    }

    /** Simple holder for new-account-dialog values. */
    private static class NewAccountInput {
        String username;
        String displayName = "";
        String password = "";
        boolean rememberPassword;
    }

    private static String formatProgress(long downloaded, long total) {
        if (total <= 0) {
            return formatBytes(downloaded);
        }
        return formatBytes(downloaded) + " / " + formatBytes(total);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}