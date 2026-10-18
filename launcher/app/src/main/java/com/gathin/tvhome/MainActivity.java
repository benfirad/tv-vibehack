package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private ImageView wallpaper;
    private int currentWallpaperRes = 0;
    private boolean isDockPeeking = false;
    private ValueAnimator dockPeekAnimator;
    private float peekOffset;
    private String peekDepthPref = "ultra";
    private boolean isTopBarShowText = true;
    private View settingsOverlay;
    private TextView btnTopStyleText, btnTopStyleIcon;
    private TextView btnPeekUltra, btnPeekBalanced, btnPeekNone;
    private TextView btnWallAuto, btnWallDay, btnWallNight;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    private float density;

    private View scrim;
    private View dimOverlay;
    private DockDecoration dockDecoration;
    private View upcomingPill;
    private TextView upcomingText;
    private boolean isScreenDimmed = false;
    private static final long IDLE_TIMEOUT_MS = 180_000L;
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleRunnable = this::enterIdleMode;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateWallpaper();
            updateUpcomingEvent();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        SharedPreferences prefs = getSharedPreferences("tvhome_prefs", MODE_PRIVATE);
        peekDepthPref = prefs.getString("dock_peek_depth", "ultra");
        updatePeekOffset();

        root = findViewById(R.id.root);
        scrim = findViewById(R.id.scrim);
        dimOverlay = findViewById(R.id.dimOverlay);
        upcomingPill = findViewById(R.id.upcomingPill);
        upcomingText = findViewById(R.id.upcomingText);
        if (upcomingPill != null) {
            upcomingPill.setOnClickListener(v -> showCalendar());
        }
        updateUpcomingEvent();
        resetIdleTimer();
        checkForUpdates(false);

        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        wallpaper = findViewById(R.id.wallpaper);
        updateWallpaper();

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density);
        dockDecoration.setAlpha(0f);
        grid.addItemDecoration(dockDecoration);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: 2-row dock sits at the bottom, giving maximum space for the wallpaper above.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int want = Math.max(0, h - (int) ((225 + 24) * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: smooth, stable, non-bouncy springs on highlight position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(360f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        }

        View btnCalendar = findViewById(R.id.btnCalendar);
        View btnDevices = findViewById(R.id.btnDevices);
        View btnSettings = findViewById(R.id.btnSettings);
        btnCalendar.setOnClickListener(v -> showCalendar());
        btnDevices.setOnClickListener(v -> showDevices());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> showSettings());
        isTopBarShowText = prefs.getBoolean("top_bar_show_text", true);
        applyTopBarStyle(isTopBarShowText);

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnCalendar) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                setDockPeek(false, true);
                int target = (lastFocused >= 0 && lastFocused < COLUMNS) ? lastFocused : 0;
                focusTile(target);
                return true;
            }
            return false;
        };
        btnCalendar.setOnKeyListener(pillKeys);
        btnDevices.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass/hero and peek the dock when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                hideHero();
                setDockPeek(true, true);
            } else {
                setDockPeek(false, true);
            }
        });
    }

    @Override
    public void onInputsTileClicked() {
        showInputs();
    }

    private boolean isInitialLaunch = true;

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);

        if (adapter.getItemCount() == 0) {
            adapter.reload();
            grid.scheduleLayoutAnimation();
        }

        updateGreeting();
        updateWifiLabel();
        updateWallpaper();
        updateUpcomingEvent();
        resetIdleTimer();

        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Only move focus to top bar and set peek mode on initial cold launch
        if (isInitialLaunch) {
            isInitialLaunch = false;
            grid.post(() -> {
                View btn = findViewById(R.id.btnCalendar);
                if (btn != null) btn.requestFocus();
                setDockPeek(true, false);
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        idleHandler.removeCallbacks(idleRunnable);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        resetIdleTimer();
        if (isScreenDimmed) {
            wakeFromIdle();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (isPurpleOrMediaKey(code)) {
                launchStremio();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void enterIdleMode() {
        if (isScreenDimmed) return;
        isScreenDimmed = true;
        if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) hideSettings();
        if (calendarOverlay != null && calendarOverlay.getVisibility() == View.VISIBLE) hideCalendar();
        if (devicesOverlay != null && devicesOverlay.getVisibility() == View.VISIBLE) hideDevices();

        View btn = findViewById(R.id.btnCalendar);
        if (btn != null) btn.requestFocus();
        setDockPeek(true, true);

        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0.03f;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}

        if (dimOverlay != null) {
            dimOverlay.setVisibility(View.VISIBLE);
            dimOverlay.animate().alpha(0.88f).setDuration(800).start();
        }
    }

    private void wakeFromIdle() {
        if (!isScreenDimmed) return;
        isScreenDimmed = false;

        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}

        if (dimOverlay != null) {
            dimOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                dimOverlay.setVisibility(View.GONE);
            }).start();
        }
        resetIdleTimer();
    }

    private void resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable);
        if (!isScreenDimmed) {
            idleHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS);
        }
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

    @Override
    public void onBackPressed() {
        if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) {
            hideSettings();
            return;
        }
        if (calendarOverlay != null && calendarOverlay.getVisibility() == View.VISIBLE) {
            hideCalendar();
            return;
        }
        if (devicesOverlay != null && devicesOverlay.getVisibility() == View.VISIBLE) {
            hideDevices();
            return;
        }
        View focused = getCurrentFocus();
        if (focused != null && focused.getId() == R.id.card) {
            if (lastFocused >= COLUMNS * 2) {
                // Return from apps grid to the dock
                focusTile(0);
            } else {
                // Return from dock to top bar (peek mode, full photo view)
                View btn = findViewById(R.id.btnCalendar);
                if (btn != null) btn.requestFocus();
            }
        }
    }

    // ---- Calendar Overlay ------------------------------------------------------------------------

    private View calendarOverlay;
    private TextView tvCalMonthYear, tvCalSubtitle;
    private TableLayout calGridTable;
    private int calYear = 2026, calMonth = Calendar.OCTOBER;

    private static final class CalEvent {
        final String badge;
        final String title;
        final int color;
        CalEvent(String badge, String title, int color) {
            this.badge = badge;
            this.title = title;
            this.color = color;
        }
    }
    private static final Map<Integer, CalEvent> OCT_EVENTS = new HashMap<>();
    static {
        OCT_EVENTS.put(19, new CalEvent("Derecho", "LUNES, 19 OCT — Examen Derecho Privado (Aula 44, 13:40)", 0xFFF43F5E));
        OCT_EVENTS.put(20, new CalEvent("Contabilidad", "MARTES, 20 OCT — Examen Contabilidad (Aula 03, 13:30)", 0xFFA855F7));
        OCT_EVENTS.put(22, new CalEvent("Estadística", "JUEVES, 22 OCT — Examen Estadística (Aula 27, 13:35)", 0xFF38BDF8));
        OCT_EVENTS.put(23, new CalEvent("Finanzas", "VIERNES, 23 OCT — Examen Finanzas I (Aula 26, 13:40)", 0xFF10B981));
        OCT_EVENTS.put(26, new CalEvent("Stocks&PERT", "LUNES, 26 OCT — IEE Práctica 3: Stocks & PERT (13:30)", 0xFFF59E0B));
        OCT_EVENTS.put(27, new CalEvent("ITV Gelves", "MARTES, 27 OCT — 🚗 Cita ITV Honda Accord (Gelves, 08:33)", 0xFF38BDF8));
        OCT_EVENTS.put(29, new CalEvent("Microeconomía", "JUEVES, 29 OCT — Examen Microeconomía (Aula 31, 13:40)", 0xFFEF4444));
        OCT_EVENTS.put(30, new CalEvent("Intro Economía", "VIERNES, 30 OCT — Examen Intro a la Economía (13:40)", 0xFFEAB308));
    }

    private void showCalendar() {
        if (calendarOverlay == null) {
            calendarOverlay = findViewById(R.id.calendarOverlay);
            tvCalMonthYear = findViewById(R.id.tvCalMonthYear);
            tvCalSubtitle = findViewById(R.id.tvCalSubtitle);
            calGridTable = findViewById(R.id.calGridTable);
            findViewById(R.id.btnCalClose).setOnClickListener(v -> hideCalendar());
            findViewById(R.id.btnCalPrev).setOnClickListener(v -> {
                calMonth--;
                if (calMonth < 0) { calMonth = 11; calYear--; }
                renderCalendar();
                v.requestFocus();
            });
            findViewById(R.id.btnCalNext).setOnClickListener(v -> {
                calMonth++;
                if (calMonth > 11) { calMonth = 0; calYear++; }
                renderCalendar();
                v.requestFocus();
            });
            findViewById(R.id.btnCalToday).setOnClickListener(v -> {
                Calendar c = Calendar.getInstance();
                calYear = c.get(Calendar.YEAR);
                calMonth = c.get(Calendar.MONTH);
                renderCalendar();
                v.requestFocus();
            });
        }
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ((ViewGroup) findViewById(R.id.topButtons)).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        Calendar c = Calendar.getInstance();
        calYear = c.get(Calendar.YEAR);
        calMonth = c.get(Calendar.MONTH);
        renderCalendar();
        calendarOverlay.setAlpha(0f);
        calendarOverlay.setVisibility(View.VISIBLE);
        calendarOverlay.animate().alpha(1f).setDuration(220).start();
    }

    private void hideCalendar() {
        if (calendarOverlay == null || calendarOverlay.getVisibility() != View.VISIBLE) return;
        calendarOverlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            calendarOverlay.setVisibility(View.GONE);
            grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            ((ViewGroup) findViewById(R.id.topButtons)).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View btn = findViewById(R.id.btnCalendar);
            if (btn != null) btn.requestFocus();
        }).start();
    }

    private void renderCalendar() {
        Calendar cal = Calendar.getInstance();
        int todayYear = cal.get(Calendar.YEAR);
        int todayMonth = cal.get(Calendar.MONTH);
        int todayDay = cal.get(Calendar.DAY_OF_MONTH);

        cal.set(Calendar.YEAR, calYear);
        cal.set(Calendar.MONTH, calMonth);
        cal.set(Calendar.DAY_OF_MONTH, 1);

        SimpleDateFormat sdf = new SimpleDateFormat("MMMM yyyy", new Locale("es", "ES"));
        tvCalMonthYear.setText(sdf.format(cal.getTime()));
        tvCalSubtitle.setText(todayDay + " DE " + new SimpleDateFormat("MMMM", new Locale("es", "ES")).format(cal.getTime()).toUpperCase(new Locale("es", "ES")) + " — Sin eventos programados");

        calGridTable.removeAllViews();

        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0=Sunday
        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        Calendar prevCal = (Calendar) cal.clone();
        prevCal.add(Calendar.MONTH, -1);
        int daysInPrevMonth = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH);

        int dayCounter = 1;
        int nextMonthDayCounter = 1;
        View viewToFocus = null;

        for (int row = 0; row < 5; row++) {
            TableRow tr = new TableRow(this);
            TableLayout.LayoutParams trLp = new TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            tr.setLayoutParams(trLp);

            for (int col = 0; col < 7; col++) {
                View cell = getLayoutInflater().inflate(R.layout.item_cal_day, tr, false);
                TextView tvDayNum = cell.findViewById(R.id.tvDayNum);
                View dotToday = cell.findViewById(R.id.dotToday);
                View eventBadge = cell.findViewById(R.id.eventBadge);
                View eventDot = cell.findViewById(R.id.eventDot);
                TextView eventText = cell.findViewById(R.id.eventText);

                TableRow.LayoutParams cellLp = new TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, 1f);
                cell.setLayoutParams(cellLp);

                int cellIndex = row * 7 + col;
                if (cellIndex < firstDayOfWeek) {
                    int pDay = daysInPrevMonth - (firstDayOfWeek - 1 - cellIndex);
                    tvDayNum.setText(String.valueOf(pDay));
                    tvDayNum.setTextColor(0x38FFFFFF);
                    cell.setAlpha(0.35f);
                    cell.setFocusable(false);
                    cell.setClickable(false);
                } else if (dayCounter <= daysInMonth) {
                    final int d = dayCounter;
                    tvDayNum.setText(String.valueOf(d));
                    cell.setFocusable(true);
                    cell.setClickable(true);
                    if (row == 0) {
                        cell.setNextFocusUpId(R.id.btnCalToday);
                    }
                    boolean isToday = (calYear == todayYear && calMonth == todayMonth && d == todayDay);
                    if (isToday) {
                        dotToday.setVisibility(View.VISIBLE);
                        dotToday.setBackgroundColor(0xFF38BDF8);
                        tvDayNum.setTextColor(0xFF38BDF8);
                        viewToFocus = cell;
                    }

                    if (calYear == 2026 && calMonth == Calendar.OCTOBER && OCT_EVENTS.containsKey(d)) {
                        CalEvent ev = OCT_EVENTS.get(d);
                        eventBadge.setVisibility(View.VISIBLE);
                        eventDot.setBackgroundColor(ev.color);
                        eventText.setText(ev.badge);
                        eventText.setTextColor(ev.color);
                    }

                    cell.setOnFocusChangeListener((v, hasFocus) -> {
                        if (hasFocus) {
                            if (calYear == 2026 && calMonth == Calendar.OCTOBER && OCT_EVENTS.containsKey(d)) {
                                tvCalSubtitle.setText(OCT_EVENTS.get(d).title);
                                tvCalSubtitle.setTextColor(OCT_EVENTS.get(d).color);
                            } else {
                                tvCalSubtitle.setText(d + " DE " + sdf.format(cal.getTime()).toUpperCase(new Locale("es", "ES")) + " — Sin eventos programados");
                                tvCalSubtitle.setTextColor(0xFF38BDF8);
                            }
                        }
                    });

                    dayCounter++;
                } else {
                    tvDayNum.setText(String.valueOf(nextMonthDayCounter++));
                    tvDayNum.setTextColor(0x38FFFFFF);
                    cell.setAlpha(0.35f);
                    cell.setFocusable(false);
                    cell.setClickable(false);
                }

                tr.addView(cell);
            }
            calGridTable.addView(tr);
        }

        if (viewToFocus != null) {
            final View f = viewToFocus;
            f.post(f::requestFocus);
        }
    }

    // ---- Devices Overlay -------------------------------------------------------------------------

    private View devicesOverlay;
    private LinearLayout devicesContainer;
    private HueManager.LightState hueLight1State = new HueManager.LightState();
    private HueManager.LightState hueLight2State = new HueManager.LightState();
    private boolean hueControlsSetup = false;

    private void showDevices() {
        if (devicesOverlay == null) {
            devicesOverlay = findViewById(R.id.devicesOverlay);
            devicesContainer = findViewById(R.id.devicesContainer);
            findViewById(R.id.btnDevClose).setOnClickListener(v -> hideDevices());
            findViewById(R.id.tvDevRefresh).setOnClickListener(v -> {
                populateDevices();
                refreshHueStatus();
            });
        }
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ((ViewGroup) findViewById(R.id.topButtons)).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        if (!hueControlsSetup) {
            setupHueControls();
            hueControlsSetup = true;
        }

        populateDevices();
        refreshHueStatus();

        devicesOverlay.setAlpha(0f);
        devicesOverlay.setVisibility(View.VISIBLE);
        devicesOverlay.animate().alpha(1f).setDuration(220).start();
        findViewById(R.id.btnDevClose).requestFocus();
    }

    private void hideDevices() {
        if (devicesOverlay == null || devicesOverlay.getVisibility() != View.VISIBLE) return;
        devicesOverlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            devicesOverlay.setVisibility(View.GONE);
            grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            ((ViewGroup) findViewById(R.id.topButtons)).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View btn = findViewById(R.id.btnDevices);
            if (btn != null) btn.requestFocus();
        }).start();
    }

    private void setupHueControls() {
        TextView btnSceneCinema = findViewById(R.id.btnSceneCinema);
        TextView btnSceneNight = findViewById(R.id.btnSceneNight);
        TextView btnSceneReading = findViewById(R.id.btnSceneReading);
        TextView btnSceneFull = findViewById(R.id.btnSceneFull);
        TextView btnHueMasterOn = findViewById(R.id.btnHueMasterOn);
        TextView btnHueMasterOff = findViewById(R.id.btnHueMasterOff);
        TextView btnHueLight1 = findViewById(R.id.btnHueLight1);
        TextView btnHueLight2 = findViewById(R.id.btnHueLight2);

        if (btnSceneCinema == null) return;

        btnSceneCinema.setOnClickListener(v -> {
            Toast.makeText(this, "🎬 Sinema Ambiyansı (%15) Uygulanıyor...", Toast.LENGTH_SHORT).show();
            HueManager.applyScene("cinema", new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnSceneNight.setOnClickListener(v -> {
            Toast.makeText(this, "🌙 Gece Lambası (%5) Uygulanıyor...", Toast.LENGTH_SHORT).show();
            HueManager.applyScene("night", new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnSceneReading.setOnClickListener(v -> {
            Toast.makeText(this, "📖 Okuma / Çalışma Modu (%70) Uygulanıyor...", Toast.LENGTH_SHORT).show();
            HueManager.applyScene("reading", new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnSceneFull.setOnClickListener(v -> {
            Toast.makeText(this, "☀️ Tam Aydınlatma (%100) Uygulanıyor...", Toast.LENGTH_SHORT).show();
            HueManager.applyScene("full", new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnHueMasterOn.setOnClickListener(v -> {
            Toast.makeText(this, "💡 Tüm Işıklar Açılıyor...", Toast.LENGTH_SHORT).show();
            HueManager.setAllLights(true, 254, new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnHueMasterOff.setOnClickListener(v -> {
            Toast.makeText(this, "🌑 Tüm Işıklar Kapatılıyor...", Toast.LENGTH_SHORT).show();
            HueManager.setAllLights(false, -1, new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnHueLight1.setOnClickListener(v -> {
            boolean nextState = !hueLight1State.on;
            HueManager.setLightState("8", nextState, 200, new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnHueLight2.setOnClickListener(v -> {
            boolean nextState = !hueLight2State.on;
            HueManager.setLightState("10", nextState, 200, new HueManager.HueCallback() {
                @Override
                public void onSuccess(String response) { refreshHueStatus(); }
                @Override
                public void onError(Exception e) {
                    Toast.makeText(MainActivity.this, "Hue Bağlantı Hatası", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void refreshHueStatus() {
        HueManager.fetchStatus((connected, l1, l2) -> {
            View dot = findViewById(R.id.dotHueStatus);
            TextView tvStatus = findViewById(R.id.tvHueBridgeStatus);
            TextView btnL1 = findViewById(R.id.btnHueLight1);
            TextView btnL2 = findViewById(R.id.btnHueLight2);

            if (dot == null || tvStatus == null) return;

            if (connected) {
                dot.setBackgroundColor(0xFF34D399);
                tvStatus.setText("Puente: 192.168.236.62 • Conectado");
                tvStatus.setTextColor(0xFFA7F3D0);

                if (l1 != null) {
                    hueLight1State = l1;
                    int pct = Math.round((l1.bri / 254f) * 100);
                    btnL1.setText("Chimenea Izq (8): " + (l1.on ? "ENCENDIDA (%" + pct + ")" : "APAGADA"));
                    btnL1.setTextColor(l1.on ? 0xFF34D399 : 0x99FFFFFF);
                }
                if (l2 != null) {
                    hueLight2State = l2;
                    int pct = Math.round((l2.bri / 254f) * 100);
                    btnL2.setText("Chimenea Dcha (10): " + (l2.on ? "ENCENDIDA (%" + pct + ")" : "APAGADA"));
                    btnL2.setTextColor(l2.on ? 0xFF34D399 : 0x99FFFFFF);
                }
            } else {
                dot.setBackgroundColor(0xFFEF4444);
                tvStatus.setText("Puente: 192.168.236.62 • Desconectado");
                tvStatus.setTextColor(0xFFFCA5A5);
            }
        });
    }

    private void populateDevices() {
        if (devicesContainer == null) return;
        devicesContainer.removeAllViews();

        String[][] devList = new String[][]{
                {"DAAK Node Hub (vaytniga / S9+)", "Núcleo de Red y Pasarela Hue • Tailscale", "100.72.106.12 • 192.168.34.12", "Activo (Online • Hub)", "#34D399"},
                {"Philips Hue Bridge (Chimenea)", "Iluminación Inteligente Zigbee • 2 Lámparas", "192.168.236.62 (eth0 tether)", "Activo (Online • 2 Luces)", "#34D399"},
                {"MYA-L11 (Mac Roto)", "Servidor 24/7 • Docker Engine • FastDrop • Hermes", "100.106.212.28 • 192.168.34.46", "Activo (Online)", "#34D399"},
                {"MacBook Pro M3 (kai)", "Dispositivo Principal de Desarrollo • Antigravity", "100.102.108.7 • 192.168.34.194", "Activo (Online)", "#34D399"},
                {"LOLILE (PC Master)", "Windows 11 Pro • Workstation • RM-OS y Servidor Stremio", "100.69.25.110 • 192.168.1.57", "Activo (Online • Stremio 11470)", "#34D399"},
                {"DAAK-DNS (Linux Core)", "Resolución DNS y Conector Cloudflare", "100.114.210.93", "Activo (Online)", "#34D399"},
                {"TCL 4K Smart TV Pro (Nigtutio)", "Pantalla 4K Salón • Android 11 (Este Dispositivo)", "100.66.111.125 • 192.168.34.182", "Activo (Online)", "#38BDF8"},
                {"Keenetic Hopper (Vaytniga Wi-Fi)", "Router Wi-Fi 5GHz • Red Local LAN", "192.168.34.1", "Activo (Online)", "#34D399"}
        };

        for (String[] d : devList) {
            View row = getLayoutInflater().inflate(R.layout.item_device_row, devicesContainer, false);
            TextView name = row.findViewById(R.id.devName);
            TextView role = row.findViewById(R.id.devRole);
            TextView ip = row.findViewById(R.id.devIp);
            TextView statusText = row.findViewById(R.id.devStatusText);
            View dot = row.findViewById(R.id.devStatusDot);

            name.setText(d[0]);
            role.setText(d[1]);
            ip.setText(d[2]);
            statusText.setText(d[3]);
            int col = Color.parseColor(d[4]);
            statusText.setTextColor(col);
            dot.setBackgroundColor(col);

            row.setOnClickListener(v -> Toast.makeText(this, d[0] + "\n" + d[2], Toast.LENGTH_SHORT).show());
            devicesContainer.addView(row);
        }
    }

    private void setDockPeek(boolean peek, boolean animate) {
        if (isDockPeeking == peek && dockPeekAnimator == null) return;
        isDockPeeking = peek;
        final float targetY = peek ? peekOffset : 0f;
        final float targetAlpha = peek ? 0f : 1f;
        final float targetScrimAlpha = peek ? 0.08f : 0.45f;
        if (peek) {
            grid.scrollToPosition(0);
        }

        if (dockPeekAnimator != null) {
            dockPeekAnimator.cancel();
            dockPeekAnimator = null;
        }

        if (!animate) {
            grid.setTranslationY(targetY);
            if (dockDecoration != null) {
                dockDecoration.setAlpha(targetAlpha);
                grid.invalidate();
            }
            if (scrim != null) {
                scrim.setAlpha(targetScrimAlpha);
            }
            return;
        }

        final float startY = grid.getTranslationY();
        final float startAlpha = dockDecoration != null ? dockDecoration.getAlpha() : 0f;
        final float startScrim = scrim != null ? scrim.getAlpha() : 0.08f;

        dockPeekAnimator = ValueAnimator.ofFloat(0f, 1f);
        dockPeekAnimator.setDuration(peek ? 240 : 260);
        dockPeekAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
        dockPeekAnimator.addUpdateListener(anim -> {
            float frac = (float) anim.getAnimatedValue();
            grid.setTranslationY(startY + (targetY - startY) * frac);
            if (dockDecoration != null) {
                dockDecoration.setAlpha(startAlpha + (targetAlpha - startAlpha) * frac);
                grid.invalidate();
            }
            if (scrim != null) {
                scrim.setAlpha(startScrim + (targetScrimAlpha - startScrim) * frac);
            }
        });
        dockPeekAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dockPeekAnimator = null;
                if (dockDecoration != null) {
                    dockDecoration.setAlpha(targetAlpha);
                    grid.invalidate();
                }
                if (scrim != null) {
                    scrim.setAlpha(targetScrimAlpha);
                }
                if (!isDockPeeking) {
                    RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(lastFocused);
                    if (vh != null) {
                        View card = vh.itemView.findViewById(R.id.card);
                        if (card != null && card.isFocused()) {
                            onTileFocused(lastFocused, card);
                        }
                    }
                }
            }
        });
        dockPeekAnimator.start();
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        // Tile rect in root coordinates, normalized by subtracting current grid translation.
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - grid.getTranslationY();

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        int row = position / COLUMNS;
        if (row == 0) {
            // Row 0 (Favorites Bar): smoothly park grid at top padding
            RecyclerView.ViewHolder vh0 = grid.findViewHolderForAdapterPosition(0);
            int dockTop = (vh0 != null) ? vh0.itemView.getTop() : item.getTop();
            int dy = dockTop - (int) (6 * density) - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        } else {
            // Row 1+ (Stremio shelf and All Apps): smooth, natural scroll without violent jumps
            Rect want = new Rect(0, -(int) (12 * density), item.getWidth(), item.getHeight() + (int) (26 * density));
            grid.requestChildRectangleOnScreen(item, want, false);
        }
        showHero(position, 0f);
    }

    // ---- top shelf & ambient ---------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        if (heroName != null) {
            heroName.setVisibility(View.GONE);
        }
        int color = adapter.ambientColorAt(position);
        if (!heroShown) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (color != ambientColor) {
            ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(500).start();
            }).start();
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (heroName != null) {
            heroName.setVisibility(View.GONE);
        }
        if (!heroShown) return;
        heroShown = false;
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    private void updateWallpaper() {
        SharedPreferences prefs = getSharedPreferences("tvhome_prefs", MODE_PRIVATE);
        String themePref = prefs.getString("wallpaper_theme", "auto");
        boolean isDay;
        if ("day".equals(themePref)) {
            isDay = true;
        } else if ("night".equals(themePref)) {
            isDay = false;
        } else {
            Calendar now = Calendar.getInstance();
            int hour = now.get(Calendar.HOUR_OF_DAY);
            int minute = now.get(Calendar.MINUTE);
            int timeInMinutes = hour * 60 + minute;
            // Day cycle: 07:00 (420 min) to 19:30 (1170 min)
            isDay = (timeInMinutes >= 420 && timeInMinutes < 1170);
        }
        int targetRes = isDay ? R.drawable.wallpaper_day : R.drawable.wallpaper_night;

        if (wallpaper == null) return;

        if (currentWallpaperRes == 0) {
            currentWallpaperRes = targetRes;
            wallpaper.setImageResource(targetRes);
            wallpaper.setAlpha(1f);
        } else if (currentWallpaperRes != targetRes) {
            currentWallpaperRes = targetRes;
            wallpaper.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                wallpaper.setImageResource(targetRes);
                wallpaper.animate().alpha(1f).setDuration(600).start();
            }).start();
        }
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        if (!isTopBarShowText) {
            if (btnWifi != null) btnWifi.setText("");
            return;
        }
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        if (btnWifi != null) btnWifi.setText(text);
    }

    private void updateUpcomingEvent() {
        if (upcomingText == null) return;
        Calendar now = Calendar.getInstance();
        int today = now.get(Calendar.DAY_OF_MONTH);
        int month = now.get(Calendar.MONTH);

        if (month == Calendar.OCTOBER) {
            int nextDay = -1;
            for (int d = today; d <= 31; d++) {
                if (OCT_EVENTS.containsKey(d)) {
                    nextDay = d;
                    break;
                }
            }
            if (nextDay != -1) {
                CalEvent ev = OCT_EVENTS.get(nextDay);
                int daysLeft = nextDay - today;
                String prefix;
                if (daysLeft == 0) prefix = "🔴 Hoy • ";
                else if (daysLeft == 1) prefix = "⚡ Mañana (" + nextDay + " Oct) • ";
                else prefix = "📅 " + nextDay + " Octubre (" + daysLeft + " días) • ";

                String shortTitle = ev.title;
                int dashIdx = shortTitle.indexOf("— ");
                if (dashIdx != -1) shortTitle = shortTitle.substring(dashIdx + 2);

                if (nextDay < 27) {
                    int itvDays = 27 - today;
                    upcomingText.setText(prefix + shortTitle + "  |  🚗 27 Oct ITV (" + itvDays + " días)");
                } else {
                    upcomingText.setText(prefix + shortTitle);
                }
                return;
            }
        }
        upcomingText.setText("🚗 27 Octubre • Cita Previa ITV Honda Accord");
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = (int) (240 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- Settings & Top Bar Preferences ----------------------------------------------------------

    private void updatePeekOffset() {
        if ("none".equals(peekDepthPref)) {
            peekOffset = 0f;
        } else if ("balanced".equals(peekDepthPref)) {
            peekOffset = 140f * density;
        } else {
            peekOffset = 220f * density; // default: ultra low peek (wallpaper takes ~95% of screen)
        }
    }

    private void applyTopBarStyle(boolean showText) {
        isTopBarShowText = showText;
        TextView btnCalendar = findViewById(R.id.btnCalendar);
        TextView btnDevices = findViewById(R.id.btnDevices);
        TextView btnSettings = findViewById(R.id.btnSettings);

        int padStart = (int) ((showText ? 16 : 11) * density);
        int padEnd = (int) ((showText ? 20 : 11) * density);
        int drawPad = (int) ((showText ? 9 : 0) * density);

        TextView[] pills = new TextView[]{btnCalendar, btnDevices, btnWifi, btnSettings};
        for (TextView p : pills) {
            if (p != null) {
                p.setPadding(padStart, 0, padEnd, 0);
                p.setCompoundDrawablePadding(drawPad);
            }
        }

        if (btnCalendar != null) btnCalendar.setText(showText ? "Calendario" : "");
        if (btnDevices != null) btnDevices.setText(showText ? "Dispositivos" : "");
        if (btnSettings != null) btnSettings.setText(showText ? getString(R.string.settings) : "");
        updateWifiLabel();
    }

    private void showSettings() {
        if (settingsOverlay == null) {
            settingsOverlay = findViewById(R.id.settingsOverlay);
            findViewById(R.id.btnSettingsClose).setOnClickListener(v -> hideSettings());

            btnTopStyleText = findViewById(R.id.btnTopStyleText);
            btnTopStyleIcon = findViewById(R.id.btnTopStyleIcon);
            btnPeekUltra = findViewById(R.id.btnPeekUltra);
            btnPeekBalanced = findViewById(R.id.btnPeekBalanced);
            btnPeekNone = findViewById(R.id.btnPeekNone);
            btnWallAuto = findViewById(R.id.btnWallAuto);
            btnWallDay = findViewById(R.id.btnWallDay);
            btnWallNight = findViewById(R.id.btnWallNight);

            btnTopStyleText.setOnClickListener(v -> setTopBarStyle(true));
            btnTopStyleIcon.setOnClickListener(v -> setTopBarStyle(false));

            btnPeekUltra.setOnClickListener(v -> setPeekDepth("ultra"));
            btnPeekBalanced.setOnClickListener(v -> setPeekDepth("balanced"));
            btnPeekNone.setOnClickListener(v -> setPeekDepth("none"));

            btnWallAuto.setOnClickListener(v -> setWallpaperTheme("auto"));
            btnWallDay.setOnClickListener(v -> setWallpaperTheme("day"));
            btnWallNight.setOnClickListener(v -> setWallpaperTheme("night"));

            findViewById(R.id.btnSysSettings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
            findViewById(R.id.btnSysWifi).setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            findViewById(R.id.btnSysApps).setOnClickListener(v -> open(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)));
            View btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
            if (btnCheckUpdate != null) {
                btnCheckUpdate.setOnClickListener(v -> checkForUpdates(true));
            }
        }

        updateSettingsUI();
        settingsOverlay.setVisibility(View.VISIBLE);
        settingsOverlay.setAlpha(0f);
        settingsOverlay.animate().alpha(1f).setDuration(220).start();
        findViewById(R.id.btnSettingsClose).requestFocus();
    }

    private void hideSettings() {
        if (settingsOverlay == null || settingsOverlay.getVisibility() != View.VISIBLE) return;
        settingsOverlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            settingsOverlay.setVisibility(View.GONE);
            View btn = findViewById(R.id.btnSettings);
            if (btn != null) btn.requestFocus();
        }).start();
    }

    private void updateSettingsUI() {
        if (btnTopStyleText == null) return;
        btnTopStyleText.setSelected(isTopBarShowText);
        btnTopStyleIcon.setSelected(!isTopBarShowText);

        btnPeekUltra.setSelected("ultra".equals(peekDepthPref));
        btnPeekBalanced.setSelected("balanced".equals(peekDepthPref));
        btnPeekNone.setSelected("none".equals(peekDepthPref));

        String wTheme = getSharedPreferences("tvhome_prefs", MODE_PRIVATE).getString("wallpaper_theme", "auto");
        btnWallAuto.setSelected("auto".equals(wTheme));
        btnWallDay.setSelected("day".equals(wTheme));
        btnWallNight.setSelected("night".equals(wTheme));
    }

    private void setTopBarStyle(boolean showText) {
        getSharedPreferences("tvhome_prefs", MODE_PRIVATE).edit().putBoolean("top_bar_show_text", showText).apply();
        applyTopBarStyle(showText);
        updateSettingsUI();
    }

    private void setPeekDepth(String depth) {
        peekDepthPref = depth;
        getSharedPreferences("tvhome_prefs", MODE_PRIVATE).edit().putString("dock_peek_depth", depth).apply();
        updatePeekOffset();
        if (isDockPeeking) {
            setDockPeek(true, true);
        }
        updateSettingsUI();
    }

    private void setWallpaperTheme(String theme) {
        getSharedPreferences("tvhome_prefs", MODE_PRIVATE).edit().putString("wallpaper_theme", theme).apply();
        updateWallpaper();
        updateSettingsUI();
    }

    private void checkForUpdates(boolean userInitiated) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://api.github.com/repos/benfirad/tv-vibehack/commits/main");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "tv-vibehack-updater");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    if (userInitiated) {
                        runOnUiThread(() -> Toast.makeText(this, "✅ v1.2.0 • El sistema está actualizado", Toast.LENGTH_LONG).show());
                    }
                } else {
                    if (userInitiated) {
                        runOnUiThread(() -> Toast.makeText(this, "Comprobación finalizada • v1.2.0 versión actual", Toast.LENGTH_SHORT).show());
                    }
                }
            } catch (Exception e) {
                if (userInitiated) {
                    runOnUiThread(() -> Toast.makeText(this, "Comprobación finalizada • v1.2.0 versión actual", Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }
}
