package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Screensaver: Home's own backgrounds, full screen, cross-fading at Home's wallpaper interval, with
 * the time and the place's name in a corner. The corner swaps sides with each picture so nothing
 * burns in. Uses the same pictures Home would, downloads nothing, and decodes off the main thread.
 */
public class BackgroundDream extends DreamService {

    private static final long FADE_MS = 1500L;
    private static final long DEFAULT_INTERVAL_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageView under, over;
    private View caption;
    private TextView place, where;
    private List<File> pictures = new ArrayList<>();
    private long intervalMs = DEFAULT_INTERVAL_MS;
    private int index;
    private boolean onLeft = true;
    private volatile boolean dreaming;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            showNext();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setContentView(R.layout.dream_backgrounds);
        under = findViewById(R.id.dreamUnder);
        over = findViewById(R.id.dreamOver);
        caption = findViewById(R.id.dreamCaption);
        place = findViewById(R.id.dreamPlace);
        where = findViewById(R.id.dreamWhere);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        new Thread(() -> {
            Config cfg = Config.load(this);
            // The same choice Home makes: places when they are on, else the theme's own pictures.
            List<File> found = new ArrayList<>(cfg.places ? PlaceWallpapers.list(this) : cfg.wallpapers);
            if (found.isEmpty() && cfg.wallpaper != null && !cfg.places) found.add(cfg.wallpaper);
            int start = getSharedPreferences("wallpaper", MODE_PRIVATE).getInt("index", -1) + 1;
            handler.post(() -> {
                if (!dreaming) return;
                pictures = found;
                index = start;
                if (cfg.wallpaperIntervalMs > 0) intervalMs = cfg.wallpaperIntervalMs;
                showNext();
            });
        }, "dream-start").start();
    }

    private void showNext() {
        if (!dreaming || pictures.isEmpty()) return;
        final File file = pictures.get(index % pictures.size());
        index = (index + 1) % pictures.size();
        new Thread(() -> {
            final Bitmap bitmap = MainActivity.decodeWallpaper(this, file);
            final String[] about = PlaceWallpapers.describe(file);
            handler.post(() -> {
                if (!dreaming) return;
                if (bitmap != null) crossFadeTo(bitmap, about);
                if (pictures.size() > 1) handler.postDelayed(next, intervalMs);
            });
        }, "dream-picture").start();
    }

    private void crossFadeTo(Bitmap bitmap, String[] about) {
        under.setImageDrawable(over.getDrawable());
        over.setAlpha(0f);
        over.setImageBitmap(bitmap);
        // Only the picture on screen stays in memory once the fade is over.
        over.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> under.setImageDrawable(null)).start();
        caption.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            setCaption(about);
            caption.animate().alpha(1f).setDuration(FADE_MS / 2).start();
        }).start();
    }

    private void setCaption(String[] about) {
        place.setText(about != null ? about[0] : "");
        place.setVisibility(about != null ? View.VISIBLE : View.GONE);
        where.setText(about != null ? about[2] : "");
        where.setVisibility(about != null && !about[2].isEmpty() ? View.VISIBLE : View.GONE);
        int side = onLeft ? Gravity.START : Gravity.END;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) caption.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | side;
        caption.setLayoutParams(lp);
        ((android.widget.LinearLayout) caption).setGravity(side);
        onLeft = !onLeft;
    }

    private void stop() {
        dreaming = false;
        handler.removeCallbacks(next);
    }

    @Override
    public void onDreamingStopped() {
        stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
