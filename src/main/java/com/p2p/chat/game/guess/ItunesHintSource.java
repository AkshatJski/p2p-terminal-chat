package com.p2p.chat.game.guess;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2p.chat.config.Config;
import com.p2p.chat.game.Clue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keyless, live hint source for "Name That". Picks a hidden song or movie from
 * Apple's marketing RSS chart feeds and enriches it with the iTunes Search
 * {@code /lookup} endpoint so the hint ladder is built from real metadata
 * (genre, release year, album, runtime, plot).
 *
 * <p>Never throws at the caller: any network/parse failure returns
 * {@link Optional#empty()} so the game can fall back to its built-in packs.
 * Results are cached per category (bounded) so repeat rounds are instant.
 */
public final class ItunesHintSource implements HintSource {
    private static final String DEFAULT_CHART_BASE = "https://rss.marketingtools.apple.com";
    private static final String DEFAULT_API_BASE = "https://itunes.apple.com";
    private static final int SONG_CHART_DEPTH = 20;
    private static final int MOVIE_CHART_DEPTH = 25;
    private static final int CACHE_CAP = 50;

    private final HttpClient client;
    private final String chartBase;
    private final String apiBase;
    private final Duration requestTimeout;
    private final Map<String, List<Clue>> cache = new ConcurrentHashMap<>();

    /** Production source; endpoints and timeouts come from {@link Config}. */
    public ItunesHintSource() {
        this(resolve(true), resolve(false), Duration.ofMillis(Config.get().getGuessDynamicTimeoutMs()));
    }

    /**
     * Source pinned to explicit endpoints and timeout. Used by the harness to
     * point requests at a local {@code com.sun.net.httpserver.HttpServer}.
     */
    public ItunesHintSource(String chartBase, String apiBase, Duration requestTimeout) {
        this.chartBase = strip(chartBase);
        this.apiBase = strip(apiBase);
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    private static String resolve(boolean chart) {
        String cfg = Config.get().getGuessDynamicBaseUrl();
        if (cfg.isBlank()) {
            return chart ? DEFAULT_CHART_BASE : DEFAULT_API_BASE;
        }
        return cfg; // one custom host mirrors both endpoint families (local testing)
    }

    @Override
    public Optional<Clue> fetch(String category) {
        try {
            return switch (category) {
                case "song" -> fetchSong();
                case "movie" -> fetchMovie();
                default -> Optional.empty();
            };
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private Optional<Clue> fetchSong() throws IOException, InterruptedException {
        Optional<Clue> cached = pickCached("song");
        if (cached.isPresent()) {
            return cached;
        }
        JsonObject chart = getJson(chartBase + "/api/v2/us/music/most-played/" + SONG_CHART_DEPTH + "/songs.json");
        JsonObject entry = randomEntry(feedResults(chart));
        if (entry == null) {
            return Optional.empty();
        }
        String id = str(entry, "id");
        JsonObject info = id.isEmpty() ? new JsonObject() : firstResult(lookup(id));
        String title = first(str(entry, "name"), str(info, "trackName"));
        String artist = str(info, "artistName");
        int year = yearOf(str(info, "releaseDate"));
        String album = str(info, "collectionName");
        String genre = first(str(info, "primaryGenreName"), firstGenre(entry));
        long millis = lng(info, "trackTimeMillis");

        List<String> hints = new ArrayList<>(4);
        add(hints, "Genre: " + genre);
        add(hints, year > 0 ? "Released in " + year : albumIfAny(album));
        add(hints, "By " + artist);
        add(hints, albumOrDuration(album, millis));
        return finish("song", title, hints);
    }

    private Optional<Clue> fetchMovie() throws IOException, InterruptedException {
        Optional<Clue> cached = pickCached("movie");
        if (cached.isPresent()) {
            return cached;
        }
        JsonObject chart = getJson(chartBase + "/api/v2/us/movies/top-movies/" + MOVIE_CHART_DEPTH + "/movies.json");
        JsonObject entry = randomEntry(feedResults(chart));
        if (entry == null) {
            return Optional.empty();
        }
        String id = str(entry, "id");
        JsonObject info = id.isEmpty() ? new JsonObject() : firstResult(lookup(id));
        String title = first(str(entry, "name"), str(info, "trackName"));
        String genre = str(info, "primaryGenreName");
        int year = yearOf(str(info, "releaseDate"));
        long millis = lng(info, "trackTimeMillis");
        String plot = str(info, "longDescription");

        List<String> hints = new ArrayList<>(4);
        add(hints, "Genre: " + genre);
        add(hints, year > 0 ? "Released in " + year : "");
        add(hints, millis > 0 ? "Runtime: " + minutes(millis) + " min" : "");
        if (!plot.isEmpty()) {
            String shortPlot = clip(plot, 160);
            if (!spoils(title, shortPlot)) {
                add(hints, "Plot: " + shortPlot);
            }
        }
        return finish("movie", title, hints);
    }

    // ------------------------------------------------------------------
    // Hints + cache
    // ------------------------------------------------------------------

    private Optional<Clue> finish(String cat, String title, List<String> hints) {
        if (title.isEmpty()) {
            return Optional.empty();
        }
        List<String> clean = new ArrayList<>(hints.size());
        for (String h : hints) {
            String s = h == null ? "" : h.trim();
            if (!s.isEmpty() && !spoils(title, s)) {
                clean.add(s);
            }
        }
        if (clean.isEmpty()) {
            return Optional.empty();
        }
        Clue clue = new Clue(title, clean.toArray(new String[0]));
        cache.computeIfAbsent(cat, k -> new CopyOnWriteArrayList<>()).add(clue);
        trimCache(cat);
        return Optional.of(clue);
    }

    private void trimCache(String cat) {
        List<Clue> l = cache.get(cat);
        while (l != null && l.size() > CACHE_CAP) {
            l.remove(0);
        }
    }

    private Optional<Clue> pickCached(String cat) {
        List<Clue> l = cache.get(cat);
        if (l == null || l.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(l.get(ThreadLocalRandom.current().nextInt(l.size())));
    }

    // ------------------------------------------------------------------
    // HTTP + JSON plumbing
    // ------------------------------------------------------------------

    private JsonObject lookup(String id) throws IOException, InterruptedException {
        return getJson(apiBase + "/lookup?id=" + id);
    }

    /** The first item of an iTunes Search /lookup response's {@code results} array. */
    private static JsonObject firstResult(JsonObject root) {
        JsonElement results = root.get("results");
        if (!(results instanceof JsonArray a) || a.isEmpty()) {
            return new JsonObject();
        }
        JsonElement first = a.get(0);
        return first instanceof JsonObject o ? o : new JsonObject();
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            return new JsonObject();
        }
        JsonElement root = parse(res.body());
        return root instanceof JsonObject o ? o : new JsonObject();
    }

    private static JsonElement parse(String body) {
        try {
            return JsonParser.parseString(body);
        } catch (Exception e) {
            return JsonNull.INSTANCE;
        }
    }

    private static JsonArray feedResults(JsonObject root) {
        JsonElement feed = root.get("feed");
        if (!(feed instanceof JsonObject fo)) {
            return null;
        }
        JsonElement results = fo.get("results");
        return results instanceof JsonArray a ? a : null;
    }

    private static JsonObject randomEntry(JsonArray results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        JsonElement pick = results.get(ThreadLocalRandom.current().nextInt(results.size()));
        return pick instanceof JsonObject o ? o : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return "";
        }
        String s = e.getAsString();
        return s == null ? "" : s.trim();
    }

    private static long lng(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return 0;
        }
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String firstGenre(JsonObject o) {
        JsonElement arr = o.get("genreNames");
        if (arr instanceof JsonArray a && !a.isEmpty() && a.get(0).isJsonPrimitive()) {
            return a.get(0).getAsString().trim();
        }
        JsonElement g = o.get("genres");
        if (g instanceof JsonArray ga && !ga.isEmpty()) {
            JsonElement first = ga.get(0);
            if (first instanceof JsonObject fo) {
                return str(fo, "name");
            }
            if (first.isJsonPrimitive()) {
                return first.getAsString().trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private static void add(List<String> hints, String hint) {
        if (hint != null && !hint.isBlank()) {
            hints.add(hint.trim());
        }
    }

    private static String albumIfAny(String album) {
        return album == null || album.isEmpty() ? "" : "From the album '" + album + "'";
    }

    private static String albumOrDuration(String album, long millis) {
        boolean hasAlbum = album != null && !album.isEmpty();
        boolean hasDur = millis > 0;
        if (!hasAlbum && !hasDur) {
            return "";
        }
        String d = duration(millis);
        if (hasAlbum && hasDur) {
            return "From the album '" + album + "' \u00b7 " + d;
        }
        return hasAlbum ? "From the album '" + album + "'" : d;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static boolean spoils(String title, String hint) {
        String t = normalize(title);
        return !t.isEmpty() && !normalize(hint).isEmpty() && normalize(hint).contains(t);
    }

    private static int yearOf(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0;
        }
        int i = 0;
        while (i < iso.length() && Character.isDigit(iso.charAt(i))) {
            i++;
        }
        if (i != 4) {
            return 0;
        }
        try {
            return Integer.parseInt(iso.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String minutes(long millis) {
        if (millis <= 0) {
            return "?";
        }
        return Long.toString(millis / 1000 / 60);
    }

    private static String duration(long millis) {
        if (millis <= 0) {
            return "";
        }
        long total = millis / 1000;
        if (total < 60) {
            return "About " + total + " seconds";
        }
        long m = total / 60;
        long s = total % 60;
        if (s == 0) {
            return "About " + m + " minutes";
        }
        return "About " + m + " min " + s + " sec";
    }

    private static String clip(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut <= max / 2) {
            cut = max;
        }
        return s.substring(0, cut).trim() + "\u2026";
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static String strip(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}