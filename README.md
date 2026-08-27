# Oasis Launcher

The Oasis game launcher — a native, auto-updating Windows launcher (bundled Java runtime, MSI installer) for the Oasis server. This repo is the launcher's **source** *and* its **public distribution host**: the owner-controlled trust root that decides which client the launcher downloads and where it points.

## Distribution files (read live by the installed launcher)

- `version.json` — launcher self-update info
- `updates.json` — the in-launcher "Recent Updates" feed (edit to post news)
- `client-manifest.json` — which client the launcher downloads + its SHA-256

The installer (`OasisLauncherSetup.exe`) and the client are attached to this repo's **Releases**.

## Source

JavaFX launcher, package `com.oasis.launcher`. Build with `./gradlew fatJar` (runnable jar) or `./gradlew jpackage` (Windows MSI). Requires JDK 21.

> Currently being rewired for the new **OSRS RuneLite (rev-240)** base — the jump up from the old OasisPS base. See Recent Updates.
