package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pengurus Waktu Solat JAKIM Malaysia untuk Glass TV.
 * Mengambil jadual waktu solat bulanan dari API JAKIM (e-Solat / waktusolat.app)
 * dan mengira waktu solat semasa serta seterusnya.
 */
public final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    private static final String API_URL = "https://api.waktusolat.app/v2/solat/";
    private static final String CACHE_FILE = "jakim_prayer_cache.json";

    public static class TodayPrayer {
        public String zone = "KDH01";
        public String hijri = "";
        public String nextName = "";
        public String nextTime = "";
        public String nextCountdown = "";
        public int nextIndex = -1; // 0=Subuh, 1=Syuruk, 2=Zohor, 3=Asar, 4=Maghrib, 5=Isyak

        public String subuh = "";
        public String syuruk = "";
        public String zohor = "";
        public String asar = "";
        public String maghrib = "";
        public String isyak = "";

        public long subuhTs = 0;
        public long syurukTs = 0;
        public long zohorTs = 0;
        public long asarTs = 0;
        public long maghribTs = 0;
        public long isyakTs = 0;
    }

    public interface Listener {
        void onPrayerUpdated(TodayPrayer prayer);
    }

    private final Context context;
    private final String zone;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private TodayPrayer currentData;
    private JSONObject cachedMonthJson;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            recalculateToday();
            // Semak semula setiap 30 saat untuk ketepatan countdown
            mainHandler.postDelayed(this, 30_000);
        }
    };

    public PrayerTimes(Context context, String zone) {
        this.context = context.getApplicationContext();
        this.zone = (zone != null && !zone.isEmpty()) ? zone : "KDH01";
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (currentData != null && listener != null) {
            listener.onPrayerUpdated(currentData);
        }
    }

    public void start() {
        loadFromCache();
        fetchFromNetwork();
        mainHandler.removeCallbacks(tickRunnable);
        mainHandler.post(tickRunnable);
    }

    public void stop() {
        mainHandler.removeCallbacks(tickRunnable);
    }

    private void loadFromCache() {
        try {
            File cache = new File(context.getFilesDir(), CACHE_FILE);
            if (!cache.exists()) return;
            try (FileInputStream in = new FileInputStream(cache)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                cachedMonthJson = new JSONObject(out.toString("UTF-8"));
                recalculateToday();
            }
        } catch (Exception e) {
            Log.w(TAG, "Gagal memuat cache waktu solat: " + e);
        }
    }

    private void fetchFromNetwork() {
        new Thread(() -> {
            try {
                URL url = new URL(API_URL + zone);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "GlassTV/1.0 (Android TV)");

                if (conn.getResponseCode() == 200) {
                    try (InputStream in = conn.getInputStream();
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[4096];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        String body = out.toString("UTF-8");
                        JSONObject json = new JSONObject(body);

                        // Simpan cache
                        File cache = new File(context.getFilesDir(), CACHE_FILE);
                        try (FileOutputStream fos = new FileOutputStream(cache)) {
                            fos.write(body.getBytes("UTF-8"));
                        }

                        mainHandler.post(() -> {
                            cachedMonthJson = json;
                            recalculateToday();
                        });
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Gagal mengambil waktu solat dari internet: " + e);
            }
        }, "PrayerTimesFetch").start();
    }

    public void recalculateToday() {
        if (cachedMonthJson == null) return;
        try {
            JSONArray prayers = cachedMonthJson.optJSONArray("prayers");
            if (prayers == null || prayers.length() == 0) return;

            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
            int currentDay = cal.get(Calendar.DAY_OF_MONTH);
            long nowSec = System.currentTimeMillis() / 1000L;

            JSONObject todayObj = null;
            for (int i = 0; i < prayers.length(); i++) {
                JSONObject p = prayers.getJSONObject(i);
                if (p.optInt("day") == currentDay) {
                    todayObj = p;
                    break;
                }
            }
            if (todayObj == null) todayObj = prayers.getJSONObject(0);

            TodayPrayer data = new TodayPrayer();
            data.zone = zone;
            data.hijri = todayObj.optString("hijri", "");

            data.subuhTs = todayObj.optLong("fajr");
            data.syurukTs = todayObj.optLong("syuruk");
            data.zohorTs = todayObj.optLong("dhuhr");
            data.asarTs = todayObj.optLong("asr");
            data.maghribTs = todayObj.optLong("maghrib");
            data.isyakTs = todayObj.optLong("isha");

            data.subuh = formatTime(data.subuhTs);
            data.syuruk = formatTime(data.syurukTs);
            data.zohor = formatTime(data.zohorTs);
            data.asar = formatTime(data.asarTs);
            data.maghrib = formatTime(data.maghribTs);
            data.isyak = formatTime(data.isyakTs);

            // Tentukan waktu solat seterusnya
            long targetTs;
            if (nowSec < data.subuhTs) {
                data.nextName = "Subuh";
                data.nextTime = data.subuh;
                data.nextIndex = 0;
                targetTs = data.subuhTs;
            } else if (nowSec < data.syurukTs) {
                data.nextName = "Syuruk";
                data.nextTime = data.syuruk;
                data.nextIndex = 1;
                targetTs = data.syurukTs;
            } else if (nowSec < data.zohorTs) {
                data.nextName = "Zohor";
                data.nextTime = data.zohor;
                data.nextIndex = 2;
                targetTs = data.zohorTs;
            } else if (nowSec < data.asarTs) {
                data.nextName = "Asar";
                data.nextTime = data.asar;
                data.nextIndex = 3;
                targetTs = data.asarTs;
            } else if (nowSec < data.maghribTs) {
                data.nextName = "Maghrib";
                data.nextTime = data.maghrib;
                data.nextIndex = 4;
                targetTs = data.maghribTs;
            } else if (nowSec < data.isyakTs) {
                data.nextName = "Isyak";
                data.nextTime = data.isyak;
                data.nextIndex = 5;
                targetTs = data.isyakTs;
            } else {
                data.nextName = "Subuh (Esok)";
                data.nextTime = data.subuh;
                data.nextIndex = 0;
                targetTs = data.subuhTs + 86400L;
            }

            long diffSec = targetTs - nowSec;
            if (diffSec > 0) {
                long h = diffSec / 3600;
                long m = (diffSec % 3600) / 60;
                if (h > 0) {
                    data.nextCountdown = String.format(Locale.ROOT, "%dj %dm lagi", h, m);
                } else {
                    data.nextCountdown = String.format(Locale.ROOT, "%dm lagi", Math.max(1, m));
                }
            } else {
                data.nextCountdown = "Masuk waktu";
            }

            currentData = data;
            if (listener != null) {
                listener.onPrayerUpdated(data);
            }
        } catch (Exception e) {
            Log.w(TAG, "Gagal mengira waktu solat hari ini: " + e);
        }
    }

    private static String formatTime(long unixSec) {
        if (unixSec <= 0) return "--:--";
        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        return sdf.format(new Date(unixSec * 1000L)).toLowerCase(Locale.ENGLISH);
    }
}
