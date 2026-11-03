package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote helpers that need system-wide access. Both are switchable in res/values/config.xml.
 *
 * 1. Media-key shortcut (remap_media_keys_to_stremio): the purple/colour/media buttons open
 *    Stremio. A key is only consumed when Stremio is installed and actually opened, so play/pause
 *    keeps working in other apps when it isn't.
 *
 * 2. Fire TV home override (firetv_home_override): Fire OS won't let another app become the
 *    default home or disable Amazon's launcher, and it handles the Home key before accessibility
 *    services see it. So whenever Amazon's home screen comes to the front, we bring this launcher
 *    back. Only active when Amazon's launcher is installed; other TVs are unaffected.
 */
public class TvAccessibilityService extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String STREMIO = "com.stremio.one";

    // Fire OS opens its Settings by briefly showing Amazon's HomeActivity (220-300 ms), then
    // MainSettingsActivity on top. When this launcher opens a settings screen it calls
    // letAmazonHomeThrough(), so that one pass isn't redirected. Every other appearance of Amazon's
    // home is redirected after a short delay that only lets the window settle.
    private static final long REDIRECT_DELAY_MS = 150;
    private static final long LET_THROUGH_MAX_MS = 3000;
    private static volatile long letThroughUntil = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean homeOverride;
    private boolean remapMediaKeys;
    private String frontWindow = "";

    private final Runnable redirectIfStillAmazonHome = () -> {
        if (isAmazonHome(frontWindow) && !lettingThrough()) goHome();
    };

    /** Called by the launcher right before it opens a system settings screen. */
    static void letAmazonHomeThrough() {
        letThroughUntil = SystemClock.uptimeMillis() + LET_THROUGH_MAX_MS;
    }

    private static boolean lettingThrough() {
        return SystemClock.uptimeMillis() < letThroughUntil;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        remapMediaKeys = getResources().getBoolean(R.bool.remap_media_keys_to_stremio);
        homeOverride = getResources().getBoolean(R.bool.firetv_home_override) && isInstalled(AMAZON_LAUNCHER);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!homeOverride || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        // Ignore non-activity windows (toasts, dialogs): they keep the previous front window.
        if (!cls.toString().contains(".")) return;
        frontWindow = pkg + "/" + cls;
        // An Amazon settings screen has arrived: the let-through pass is used up. (Fire OS first
        // shows an invisible helper from com.amazon.tv.settings.v2, which must not count.)
        if (frontWindow.startsWith(AMAZON_LAUNCHER + "/") && frontWindow.contains("Settings")) letThroughUntil = 0;
        handler.removeCallbacks(redirectIfStillAmazonHome);
        if (isAmazonHome(frontWindow) && !lettingThrough()) {
            handler.postDelayed(redirectIfStillAmazonHome, REDIRECT_DELAY_MS);
        }
    }

    private static boolean isAmazonHome(String window) {
        return window.startsWith(AMAZON_LAUNCHER + "/") && window.contains("HomeActivity");
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (remapMediaKeys && isPurpleOrMediaKey(event.getKeyCode())) {
            // Consume both down and up, but only if Stremio is there to open.
            Intent stremio = stremioIntent();
            if (stremio == null) return super.onKeyEvent(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                try { startActivity(stremio); } catch (Exception ignored) {}
            }
            return true;
        }
        return super.onKeyEvent(event);
    }

    private boolean isPurpleOrMediaKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_DVR:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_BUTTON_1:
            case KeyEvent.KEYCODE_BUTTON_2:
            case KeyEvent.KEYCODE_BUTTON_3:
            case KeyEvent.KEYCODE_BUTTON_4:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
            case 284:
            case 285:
            case 286:
            case 287:
                return true;
            default:
                return false;
        }
    }

    private Intent stremioIntent() {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(STREMIO);
        if (i == null) i = pm.getLaunchIntentForPackage(STREMIO);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception ignored) {}
    }
}
