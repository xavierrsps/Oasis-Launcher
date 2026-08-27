module com.oasis.launcher {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires com.google.gson;
    requires java.net.http;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;
    requires com.sun.jna;
    requires com.sun.jna.platform;

    // Required for TLS/HTTPS on GitHub (uses elliptic curve crypto).
    // Without these, jlink strips them out of the bundled runtime and
    // network connections fail with "handshake_failure" errors.
    requires jdk.crypto.ec;

    exports com.oasis.launcher;
    exports com.oasis.launcher.ui;
    exports com.oasis.launcher.update;
    exports com.oasis.launcher.launch;
    exports com.oasis.launcher.model;
    exports com.oasis.launcher.util;
    exports com.oasis.launcher.account;

    opens com.oasis.launcher.model to com.google.gson;
}