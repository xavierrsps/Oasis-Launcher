package com.oasis.launcher.ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Recolours the native Windows title bar (caption) to the Oasis brown/gold theme via the DWM API.
 *
 * <p>Windows 11 (build 22000+) honours {@code DWMWA_CAPTION_COLOR} / {@code _TEXT_COLOR} /
 * {@code _BORDER_COLOR}; on Windows 10 the immersive-dark-mode attribute still darkens the bar, and
 * anything unsupported just returns an error HRESULT we ignore. Everything is wrapped so a missing
 * native lib or older OS is a silent no-op — the bar simply stays default. JNA is already a launcher
 * dependency (the credential store uses it), so this adds no new packaging surface.
 */
public final class WindowsChrome {

    private static final Logger logger = LogManager.getLogger(WindowsChrome.class);

    private WindowsChrome() {
    }

    private interface Dwmapi extends Library {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class);

        int DwmSetWindowAttribute(HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;

    /** DWM expects COLORREF (0x00BBGGRR); convert from 0xRRGGBB. */
    private static int colorRef(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (b << 16) | (g << 8) | r;
    }

    /**
     * Finds the window by its exact title and paints the caption bar. Safe to call after the stage is
     * shown, from any thread; a no-op on non-Windows or when the window can't be found.
     */
    public static void applyDarkCaption(String windowTitle, int captionRgb, int textRgb, int borderRgb) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            HWND hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
            if (hwnd == null) {
                return;
            }
            Dwmapi dwm = Dwmapi.INSTANCE;
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, new IntByReference(1), 4);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, new IntByReference(colorRef(captionRgb)), 4);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, new IntByReference(colorRef(textRgb)), 4);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, new IntByReference(colorRef(borderRgb)), 4);
        } catch (Throwable t) {
            logger.debug("Could not recolour the title bar (non-fatal): {}", t.toString());
        }
    }
}
