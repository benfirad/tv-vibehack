package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        void onInputsTileClicked();
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        final boolean isStremio;
        final String stremioType;
        final String stremioId;
        android.graphics.Bitmap posterBitmap;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(label, component, banner, icon, false, null, null, 0);
        }

        Entry(String label, ComponentName component, Drawable banner, Drawable icon,
              boolean isStremio, String stremioType, String stremioId, int ambient) {
            this.label = label;
            this.component = component;
            this.pkg = component != null ? component.getPackageName() : "com.stremio.one";
            this.banner = banner;
            this.icon = icon;
            this.isStremio = isStremio;
            this.stremioType = stremioType;
            this.stremioId = stremioId;
            this.ambient = ambient;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private static final java.util.Map<String, Bitmap> POSTER_CACHE = new java.util.HashMap<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        fresh.add(new Entry("Inputs", new ComponentName("com.gathin.tvhome.inputs", "InputsActivity"),
                ctx.getDrawable(R.drawable.banner_inputs), ctx.getDrawable(R.drawable.ic_input)));

        // Row 0: Dock Core Apps
        List<Entry> row0 = new ArrayList<>();
        List<Entry> otherApps = new ArrayList<>();
        for (String fav : favourites) {
            for (Entry e : fresh) {
                if (fav.equals(e.pkg) && !row0.contains(e)) {
                    row0.add(e);
                    break;
                }
            }
        }
        for (Entry e : fresh) {
            if (!row0.contains(e)) otherApps.add(e);
        }
        Collections.sort(otherApps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        // Guarantee row0 has exactly COLUMNS items so rows stay aligned and Stremio shelf sits in row 1
        while (row0.size() < MainActivity.COLUMNS && !otherApps.isEmpty()) {
            row0.add(otherApps.remove(0));
        }
        while (row0.size() > MainActivity.COLUMNS) {
            otherApps.add(0, row0.remove(row0.size() - 1));
        }

        // Row 1: Stremio Quick Stream Shelf
        List<Entry> stremioRow = new ArrayList<>();
        stremioRow.add(new Entry("Deadpool & Wolverine", null,
                ctx.getDrawable(R.drawable.banner_stremio_deadpool), null,
                true, "movie", "tt6263850", 0xFFDC2626));
        stremioRow.add(new Entry("Spider-Man: Across Spider-Verse", null,
                ctx.getDrawable(R.drawable.banner_stremio_spiderman), null,
                true, "movie", "tt9362722", 0xFF9333EA));
        stremioRow.add(new Entry("Dune: Part Two", null,
                ctx.getDrawable(R.drawable.banner_stremio_dune), null,
                true, "movie", "tt15239678", 0xFFD97706));
        stremioRow.add(new Entry("The Penguin", null,
                ctx.getDrawable(R.drawable.banner_stremio_penguin), null,
                true, "series", "tt15435876", 0xFF0284C7));
        stremioRow.add(new Entry("Breaking Bad", null,
                ctx.getDrawable(R.drawable.banner_stremio_breakingbad), null,
                true, "series", "tt0903747", 0xFF059669));

        for (Entry e : stremioRow) {
            if (e.stremioId != null && POSTER_CACHE.containsKey(e.stremioId)) {
                e.posterBitmap = POSTER_CACHE.get(e.stremioId);
            }
        }

        items.clear();
        items.addAll(row0);
        items.addAll(stremioRow);
        items.addAll(otherApps);
        notifyDataSetChanged();

        fetchStremioPostersAsync(stremioRow);
    }

    private void fetchStremioPostersAsync(List<Entry> entries) {
        new Thread(() -> {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (!e.isStremio || e.stremioId == null) continue;
                if (POSTER_CACHE.containsKey(e.stremioId)) continue;
                try {
                    java.net.URL url = new java.net.URL("https://images.metahub.space/poster/medium/" + e.stremioId + "/img.jpg");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(4000);
                    conn.connect();
                    if (conn.getResponseCode() == 200) {
                        Bitmap bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
                        if (bmp != null) {
                            POSTER_CACHE.put(e.stremioId, bmp);
                            e.posterBitmap = bmp;
                            final int idx = items.indexOf(e);
                            if (idx >= 0 && ctx instanceof MainActivity) {
                                ((MainActivity) ctx).runOnUiThread(() -> notifyItemChanged(idx));
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        boolean focused = h.card.isFocused();
        float density = h.itemView.getResources().getDisplayMetrics().density;
        h.name.setVisibility(View.VISIBLE);
        h.name.setAlpha(focused ? 1.0f : 0.0f);
        h.name.setTranslationY(focused ? 0f : -density * 4f);
        h.name.setScaleX(focused ? 1.04f : 1.0f);
        h.name.setScaleY(focused ? 1.04f : 1.0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.posterBitmap != null) {
            h.banner.setImageBitmap(e.posterBitmap);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> {
            if ("com.gathin.tvhome.inputs".equals(e.pkg)) {
                if (listener != null) listener.onInputsTileClicked();
            } else if (e.isStremio) {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("stremio:///detail/" + e.stremioType + "/" + e.stremioId));
                i.setPackage("com.stremio.one");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                } catch (Exception ex) {
                    Intent fallback = ctx.getPackageManager().getLeanbackLaunchIntentForPackage("com.stremio.one");
                    if (fallback != null) {
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(fallback);
                    } else {
                        Toast.makeText(ctx, ctx.getString(R.string.stremio_not_installed), Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                launch(e);
            }
        });
        h.card.setOnLongClickListener(v -> {
            if (e.isStremio || "com.gathin.tvhome.inputs".equals(e.pkg)) return true;
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if ("com.gathin.tvhome.inputs".equals(e.pkg)) return 0xFF2563EB;
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate()
                        .alpha(focused ? 1.0f : 0.0f)
                        .translationY(focused ? 0f : -density * 4f)
                        .scaleX(focused ? 1.04f : 1.0f)
                        .scaleY(focused ? 1.04f : 1.0f)
                        .setDuration(focused ? 200 : 150).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
