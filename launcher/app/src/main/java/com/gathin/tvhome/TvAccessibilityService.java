package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * System-wide hardware key interception service for Android TV.
 * Remaps the dedicated purple media/channel button to launch Stremio.
 */
public class TvAccessibilityService extends AccessibilityService {

    private long lastLaunchTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgName = event.getPackageName();
            if (pkgName != null) {
                String pkg = pkgName.toString();
                if ("com.tcl.waterfall.overseas".equals(pkg)
                        || "com.tcl.channelplus".equals(pkg)
                        || "com.tcl.ui_mediaCenter".equals(pkg)
                        || "com.tcl.ttvs".equals(pkg)) {
                    long now = System.currentTimeMillis();
                    if (now - lastLaunchTime > 1500) {
                        lastLaunchTime = now;
                        launchStremio();
                    }
                }
            }
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            android.util.Log.d("TvAccessibility", "Key down: " + code);
            if (isPurpleOrMediaKey(code)) {
                launchStremio();
                return true;
            }
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
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
            case KeyEvent.KEYCODE_MEDIA_TOP_MENU:
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case 170: // KEYCODE_TV
            case 171: // KEYCODE_WINDOW
            case 284:
            case 285:
            case 286:
            case 287:
            case 288:
            case 289:
            case 290:
                return true;
            default:
                return false;
        }
    }

    private void launchStremio() {
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("com.stremio.one");
            if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.stremio.one");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }
}
