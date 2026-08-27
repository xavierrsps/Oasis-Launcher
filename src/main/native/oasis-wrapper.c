/*
 * Kryos Launcher - Native Wrapper
 *
 * Strips poisoned env vars (JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, etc.) before
 * spawning the real jpackage launcher EXE. This prevents crashes on systems
 * that have a Java agent injected via env vars pointing to an incompatible JDK.
 *
 * Compile: gcc -O2 -s -mwindows -o kryos-launcher.exe kryos-wrapper.c -lshlwapi
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlwapi.h>
#include <stdio.h>
#include <string.h>

static const char* POISONED_ENV_VARS[] = {
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JAVA_OPTS",
    "JDK_JAVA_OPTIONS",
    "JAVA_AGENT",
    "JAVAAGENT",
    "JAVA_HOME",
    NULL
};

static const char* REAL_LAUNCHER_NAME = "kryos-java-launcher.exe";

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                   LPSTR lpCmdLine, int nCmdShow) {
    for (int i = 0; POISONED_ENV_VARS[i] != NULL; i++) {
        SetEnvironmentVariableA(POISONED_ENV_VARS[i], NULL);
    }

    char wrapperPath[MAX_PATH];
    if (GetModuleFileNameA(NULL, wrapperPath, MAX_PATH) == 0) {
        MessageBoxA(NULL, "Failed to determine own EXE path.",
                    "Kryos Launcher", MB_OK | MB_ICONERROR);
        return 1;
    }
    PathRemoveFileSpecA(wrapperPath);

    char realLauncherPath[MAX_PATH];
    snprintf(realLauncherPath, MAX_PATH, "%s\\%s", wrapperPath, REAL_LAUNCHER_NAME);

    if (!PathFileExistsA(realLauncherPath)) {
        char msg[MAX_PATH + 128];
        snprintf(msg, sizeof(msg),
                 "Real launcher not found:\n%s\n\n"
                 "Your installation may be corrupted. Try reinstalling.",
                 realLauncherPath);
        MessageBoxA(NULL, msg, "Kryos Launcher", MB_OK | MB_ICONERROR);
        return 1;
    }

    char cmdLine[8192];
    if (lpCmdLine && lpCmdLine[0] != '\0') {
        snprintf(cmdLine, sizeof(cmdLine), "\"%s\" %s", realLauncherPath, lpCmdLine);
    } else {
        snprintf(cmdLine, sizeof(cmdLine), "\"%s\"", realLauncherPath);
    }

    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    BOOL ok = CreateProcessA(
        realLauncherPath,
        cmdLine,
        NULL, NULL, FALSE, 0, NULL,
        wrapperPath,
        &si, &pi
    );

    if (!ok) {
        DWORD err = GetLastError();
        char msg[512];
        snprintf(msg, sizeof(msg),
                 "Failed to start launcher (Windows error %lu).\n\nPath: %s",
                 err, realLauncherPath);
        MessageBoxA(NULL, msg, "Kryos Launcher", MB_OK | MB_ICONERROR);
        return 1;
    }

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return 0;
}