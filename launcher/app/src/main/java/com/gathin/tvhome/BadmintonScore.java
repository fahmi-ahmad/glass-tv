package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mengambil jadual dan live score perlawanan badminton BWF dari suapan FlashScore secara masa-nyata.
 * Hanya memaparkan perlawanan LANGSUNG (LIVE) dan AKAN DATANG (JADUAL).
 * Mengutamakan perlawanan yang melibatkan pemain Malaysia.
 */
public class BadmintonScore {

    private static final String TAG = "BadmintonScore";
    private static final String TODAY_FEED = "https://www.flashscore.com/x/feed/f_21_0_3_en_1";
    private static final String FSIGN = "SW9D1eZo";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final long POLL_INTERVAL_MS = 45_000L; // Semak setiap 45 saat

    public static class Match {
        public final String id;
        public final String tournament;
        public final String category; // "MS", "WS", "MD", "WD", "XD"
        public final String home;
        public final String away;
        public final int status; // 1 = Jadual, 2 = Live
        public final long timestamp;
        public final String timeFormatted;
        public final String homeSets;
        public final String awaySets;
        public final String detailedScore;
        public final boolean isMalaysia;

        public Match(String id, String tournament, String category, String home, String away,
                     int status, long timestamp, String timeFormatted, String homeSets,
                     String awaySets, String detailedScore, boolean isMalaysia) {
            this.id = id;
            this.tournament = tournament;
            this.category = category;
            this.home = home;
            this.away = away;
            this.status = status;
            this.timestamp = timestamp;
            this.timeFormatted = timeFormatted;
            this.homeSets = homeSets;
            this.awaySets = awaySets;
            this.detailedScore = detailedScore;
            this.isMalaysia = isMalaysia;
        }

        public boolean isLive() {
            return status == 2;
        }

        public boolean isScheduled() {
            return status == 1;
        }
    }

    public interface Listener {
        void onMatchesUpdated(String tournamentName, List<Match> matches);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile boolean running;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            new Thread(() -> {
                fetchAndNotify();
                if (running) {
                    handler.postDelayed(pollTask, POLL_INTERVAL_MS);
                }
            }, "badminton-poll").start();
        }
    };

    public void start(Listener l) {
        this.listener = l;
        this.running = true;
        handler.post(pollTask);
    }

    public void stop() {
        this.running = false;
        handler.removeCallbacks(pollTask);
    }

    private void fetchAndNotify() {
        try {
            List<Match> matches = fetchFeed(TODAY_FEED);
            String tournamentName = "BWF World Tour";

            if (!matches.isEmpty()) {
                tournamentName = matches.get(0).tournament;
            }

            // Susunan keutamaan:
            // 1. LIVE pemain Malaysia
            // 2. LIVE pemain lain
            // 3. JADUAL pemain Malaysia
            // 4. JADUAL pemain lain (mengikut turutan masa)
            Collections.sort(matches, new Comparator<Match>() {
                @Override
                public int compare(Match a, Match b) {
                    int scoreA = priorityScore(a);
                    int scoreB = priorityScore(b);
                    if (scoreA != scoreB) {
                        return Integer.compare(scoreA, scoreB);
                    }
                    return Long.compare(a.timestamp, b.timestamp);
                }
            });

            final String finalTour = tournamentName;
            final List<Match> finalMatches = matches;

            handler.post(() -> {
                if (!running || listener == null) return;
                listener.onMatchesUpdated(finalTour, finalMatches);
            });

        } catch (Exception e) {
            Log.w(TAG, "fetchAndNotify error: " + e.getMessage());
        }
    }

    private static int priorityScore(Match m) {
        if (m.isLive() && m.isMalaysia) return 0;   // Tertinggi
        if (m.isLive()) return 1;                  // LIVE umum
        if (m.isScheduled() && m.isMalaysia) return 2; // Jadual Malaysia
        return 3;                                  // Jadual umum
    }

    private List<Match> fetchFeed(String feedUrl) {
        List<Match> list = new ArrayList<>();
        try {
            URL url = new URL(feedUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("x-fsign", FSIGN);
            conn.setRequestProperty("Accept", "*/*");

            if (conn.getResponseCode() != 200) {
                return list;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            String raw = sb.toString();
            String[] blocks = raw.split("~");
            String curTour = "BWF World Tour";
            String curCat = "MS";

            SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.US);

            for (String block : blocks) {
                String[] parts = block.split("¬");
                Map<String, String> data = new HashMap<>();
                for (String p : parts) {
                    int div = p.indexOf('÷');
                    if (div > 0) {
                        data.put(p.substring(0, div), p.substring(div + 1));
                    }
                }

                if (data.containsKey("ZA")) {
                    String fullZa = data.get("ZA");
                    if (fullZa != null) {
                        if (fullZa.contains(":")) {
                            String[] s = fullZa.split(":", 2);
                            curCat = cleanCategory(s[0]);
                            curTour = cleanTournament(s[1]);
                        } else {
                            curTour = cleanTournament(fullZa);
                            curCat = "MS";
                        }
                    }
                }

                if (data.containsKey("AA")) {
                    int status = 1;
                    try {
                        String ab = data.get("AB");
                        if (ab != null) status = Integer.parseInt(ab);
                    } catch (Exception ignored) {}

                    // Hanya ambil perlawanan Jadual (1) atau Live (2). Abaikan Tamat (3)
                    if (status != 1 && status != 2) {
                        continue;
                    }

                    String id = data.get("AA");

                    // Paparkan nama setiap pemain walaupun permainan beregu (guna AE / AF)
                    String home = data.get("AE");
                    if (home == null || home.isEmpty()) {
                        home = data.get("CX");
                    }
                    String away = data.get("AF");
                    if (away == null || away.isEmpty()) {
                        away = data.get("CY");
                    }
                    if (home == null || away == null || home.isEmpty()) continue;

                    // Kesan pemain Malaysia
                    String cHome = data.get("FU");
                    String cAway = data.get("FV");
                    boolean isMalaysia = false;
                    if (cHome != null && cHome.toLowerCase(Locale.ROOT).contains("malaysia")) {
                        isMalaysia = true;
                    } else if (cAway != null && cAway.toLowerCase(Locale.ROOT).contains("malaysia")) {
                        isMalaysia = true;
                    }

                    long ts = 0;
                    try {
                        String ad = data.get("AD");
                        if (ad != null) ts = Long.parseLong(ad);
                    } catch (Exception ignored) {}

                    String timeFormatted = "";
                    if (ts > 0) {
                        timeFormatted = timeFmt.format(new Date(ts * 1000L));
                    }

                    String homeSets = data.get("AG");
                    String awaySets = data.get("AH");
                    if (homeSets == null) homeSets = "0";
                    if (awaySets == null) awaySets = "0";

                    // Himpun skor set jika sedang berlangsung
                    StringBuilder scoreSb = new StringBuilder();
                    String ba = data.get("BA"), bb = data.get("BB");
                    String bc = data.get("BC"), bd = data.get("BD");
                    String be = data.get("BE"), bf = data.get("BF");

                    if (ba != null && bb != null && !ba.isEmpty() && !bb.isEmpty()) {
                        scoreSb.append(ba).append("-").append(bb);
                    }
                    if (bc != null && bd != null && !bc.isEmpty() && !bd.isEmpty()) {
                        if (scoreSb.length() > 0) scoreSb.append(", ");
                        scoreSb.append(bc).append("-").append(bd);
                    }
                    if (be != null && bf != null && !be.isEmpty() && !bf.isEmpty()) {
                        if (scoreSb.length() > 0) scoreSb.append(", ");
                        scoreSb.append(be).append("-").append(bf);
                    }

                    String detailed = scoreSb.length() > 0 ? "(" + scoreSb.toString() + ")" : "";

                    list.add(new Match(id, curTour, curCat, home, away, status, ts,
                            timeFormatted, homeSets, awaySets, detailed, isMalaysia));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchFeed error for " + feedUrl + ": " + e.getMessage());
        }
        return list;
    }

    private static String cleanCategory(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        if (u.contains("MIXED DOUBLES")) return "XD";
        if (u.contains("DOUBLES MEN") || u.contains("MEN DOUBLES")) return "MD";
        if (u.contains("DOUBLES WOMEN") || u.contains("WOMEN DOUBLES")) return "WD";
        if (u.contains("MEN")) return "MS";
        if (u.contains("WOMEN")) return "WS";
        return "BWF";
    }

    private static String cleanTournament(String s) {
        String t = s.trim();
        t = t.replace("BWF WORLD TOUR - ", "").trim();
        return t;
    }
}
