package com.cordiact.app.privilege;

import android.graphics.Bitmap;
import android.os.Bundle;

/**
 * Runs in the Shizuku/ADB shell process. Only structured operations cross this
 * boundary: no file paths, no display ids and no command strings come from the
 * model — the host builds every command itself.
 */
interface IPrivilegedService {
    /** Returns a Bundle with exitCode/stdout/stderr/timedOut/truncated. */
    Bundle exec(String command, long timeoutMs);

    /** uid of this process: 2000 for shell, 0 for root. */
    int uid();

    /** Creates a public virtual display and returns {displayId, width, height, dpi}. */
    Bundle createDisplay(int width, int height, int dpi);

    boolean destroyDisplay(int displayId);

    /** Latest frame, ARGB bitmap; null when nothing has rendered yet. */
    @nullable Bitmap captureDisplay(int displayId);

    Bundle launchOnDisplay(int displayId, String packageName, @nullable String activity);

    boolean tap(int displayId, int x, int y, long durationMs);

    boolean swipe(int displayId, int x1, int y1, int x2, int y2, long durationMs);

    boolean key(int displayId, int keyCode);

    boolean text(int displayId, String value);

    /** Accessibility tree of the display, or {error} when the platform refuses. */
    Bundle dumpDisplay(int displayId);

    /**
     * Types into the display's editable node through accessibility, so CJK and
     * emoji work. Returns {ok, method, package, observed, error}.
     */
    Bundle typeText(int displayId, String value, boolean submit);

    /** Rendered WebView content of the display, or {error} when it shows none. */
    Bundle dumpWeb(int displayId, int maxChars);

    /** Moves the display's top task back to the default display. */
    boolean handoff(int displayId);
}
