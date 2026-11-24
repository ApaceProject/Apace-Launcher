package com.kotpasztet.apacelauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Klient GitHub REST API (releases) i ghcr.io (OCI) — bez bibliotek zewnętrznych.
 * Używany do sprawdzania i pobierania aktualizacji Apace (kanał Main = GitHub
 * Releases, kanał Dev = obraz ghcr.io/kotpasztet/apace:dev).
 *
 * Dev (od 1.1.8): obraz jest czytany w całości — lista WSZYSTKICH warstw z
 * manifestu arm64 klasyfikowana po historii budowania (config.history) na:
 *  - warstwy SYSTEMOWE (przed „COPY /src/build/Release/latest/”) — bazowy
 *    Ubuntu 24.04 + Java 17 + .NET 10 + aapt/zipalign + android-framework +
 *    PowerShell; te trafiają do rootfs telefonu (poprzednio rootfs powstawał
 *    z ubuntu-base + apt i NIGDY nie dostawał aapt2),
 *  - warstwy APLIKACYJNE (od „COPY …build/Release/latest…” — zawartość /app),
 *  - warstwy POMIJANE („apt-get install … docker.io docker-compose-v2”) —
 *    docker na telefonie jest bezużyteczny (brak gniazda dockera), a to ~110 MB
 *    compressed / ~400 MB po rozpakowaniu.
 * Digesty warstw systemowych tworzą „systemKey” — jeśli się nie zmienił,
 * aktualizacja dev NIE przebudowuje rootfs (szybka ścieżka: tylko /app).
 *
 * Zapytania do api.github.com są cacheowane przez ETag/If-None-Match (304 nie
 * zjada limitu 60/h), a przy 403 (limit — częsty na IP operatorów CGNAT)
 * zwracany jest świeży (< 10 min) wynik z cache.
 */
public final class GitHubApi {

    public static final String REPO = "KotPasztet/Apace";

    // ── Kanał Dev: obraz ghcr.io/kotpasztet/apace:dev ──────────────────────
    // Publikowany przez workflow docker-image-dev.yml przy każdym pushu do dev.
    public static final String GHCR_IMAGE = "kotpasztet/apace";
    public static final String GHCR_TAG = "dev";

    public static class Asset {
        public String name;
        public String url;
        public long size;
        public String updated; // updated_at (ISO) — zmienia się przy każdej podmianie assetu
    }

    public static class Release {
        public String tag;
        public String name;
        public List<Asset> assets = new ArrayList<Asset>();
    }

    /** Jedna warstwa OCI z manifestu (digest + rozmiar skompresowany). */
    public static class Layer {
        public final String digest; // "sha256:<64hex>"
        public final long size;     // rozmiar blobu (tar.gz)
        public boolean skip;        // warstwa docker.io — pomijana na telefonie
        public boolean app;         // warstwa „aplikacyjna” (zawartość /app)
        public Layer(String digest, long size) {
            this.digest = digest;
            this.size = size;
        }
    }

    public static class DevBuild {
        public String version;      // commit obrazu (12 hex APACE_COMMIT) albo digest indexu
        public String revision;     // pełny SHA commita z obrazu (APACE_COMMIT), null gdy brak
        public String created;      // timestamp budowy obrazu (ISO 8601), null gdy brak
        public String indexDigest;  // digest indexu manifestów (diagnostyka)
        public String layerDigest;  // digest warstwy „COPY build/Release/latest” (pierwszej app)
        public List<Layer> layers = new ArrayList<Layer>(); // wszystkie warstwy arm64, w kolejności
        public String systemKey;    // łańcuch digestów warstw systemowych (bez pominiętych)
        public String appKey;       // łańcuch digestów warstw aplikacyjnych
        public long systemSize;     // suma rozmiarów warstw systemowych (bez pominiętych)
        public long appSize;        // suma rozmiarów warstw aplikacyjnych
        public int skippedLayers;   // ile warstw pominięto (docker)
    }

    public static class DevCommit {
        public String sha;   // pełny SHA HEAD brancha dev
        public String date;  // committer date HEAD (ISO 8601)
    }

    /** URL blobu warstwy OCI (pobranie wymaga świeżego anonimowego tokenu). */
    public static String ghcrBlobUrl(String digest) {
        return "https://ghcr.io/v2/" + GHCR_IMAGE + "/blobs/" + digest;
    }

    // ── api.github.com z cache ETag (304 nie liczy się do limitu 60/h) ─────

    /** url → {etag, body, timestampMs} */
    private static final HashMap<String, String[]> HTTP_CACHE = new HashMap<String, String[]>();
    private static final long STALE_SERVE_MS = 10 * 60 * 1000L;

    private static String fetchCached(String url) throws Exception {
        synchronized (HTTP_CACHE) {
            String[] cached = HTTP_CACHE.get(url);
            java.net.HttpURLConnection c = Downloader.open(url);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (cached != null) c.setRequestProperty("If-None-Match", cached[0]);
            int code;
            try {
                code = c.getResponseCode();
            } catch (Exception e) {
                // błąd sieci — podaj świeży wynik z cache, jeśli jest
                if (cached != null && System.currentTimeMillis() - Long.parseLong(cached[2]) < STALE_SERVE_MS) {
                    return cached[1];
                }
                throw e;
            }
            if (code == 304 && cached != null) {
                return cached[1];
            }
            if (code == 403) {
                // limit zapytań (częsty na IP operatorów — CGNAT) — świeży cache ratuje sytuację
                if (cached != null && System.currentTimeMillis() - Long.parseLong(cached[2]) < STALE_SERVE_MS) {
                    return cached[1];
                }
                throw new Exception("GitHub API rate limit exhausted (403) — try again later.");
            }
            if (code != 200) {
                throw new Exception("GitHub API HTTP " + code);
            }
            String etag = c.getHeaderField("ETag");
            String body = readAll(c.getInputStream());
            if (etag != null && etag.length() > 0) {
                HTTP_CACHE.put(url, new String[]{etag, body,
                        String.valueOf(System.currentTimeMillis())});
            }
            return body;
        }
    }

    // ── Kanał Main: GitHub Releases ────────────────────────────────────────

    /** Najnowszy stabilny release (releases/latest pomija prereleases). */
    public static Release latestRelease() throws Exception {
        String body = fetchCached("https://api.github.com/repos/" + REPO + "/releases/latest");
        JSONObject o = new JSONObject(body);
        Release rel = new Release();
        rel.tag = o.optString("tag_name", null);
        if (rel.tag == null || rel.tag.length() == 0) {
            throw new Exception("GitHub API response without tag_name");
        }
        rel.name = o.optString("name", null);
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                Asset as = new Asset();
                as.name = a.optString("name", "");
                as.url = a.optString("browser_download_url", null);
                as.size = a.optLong("size", 0);
                as.updated = a.optString("updated_at", null);
                rel.assets.add(as);
            }
        }
        return rel;
    }

    /**
     * Wybór assetu dla ARM64: najpierw dedykowany Apace-termux-arm64.zip
     * (identyczny bajt w bajt z linux-arm64 — audyt release.yml), potem linux-arm64.
     */
    public static Asset pickArm64Asset(Release rel) {
        Asset termux = null, linux = null;
        for (Asset a : rel.assets) {
            String n = a.name.toLowerCase();
            if (n.startsWith("apace-termux-arm64") && n.endsWith(".zip")) termux = a;
            if (n.startsWith("apace-linux-arm64") && n.endsWith(".zip")) linux = a;
        }
        if (termux != null) return termux;
        if (linux != null) return linux;
        return null;
    }

    // ── Kanał Dev: ghcr.io (OCI) — pełny obraz ─────────────────────────────

    /**
     * Najnowszy build dev: anonimowy token ghcr → index manifestów (multi-arch)
     * → manifest linux/arm64 → config obrazu → klasyfikacja warstw po historii.
     *
     * Warstwa „aplikacyjna” zaczyna się od kroku historii zawierającego
     * „build/Release/latest” (COPY /src/build/Release/latest/ .) — wszystko
     * później dotyczy tylko /app; wszystko wcześniej to system (Ubuntu, Java,
     * .NET, aapt2, android-framework, PowerShell). Warstwa z „docker.io
     * docker-compose-v2” jest pomijana (docker na telefonie jest bezużyteczny).
     */
    public static DevBuild latestDevBuild() throws Exception {
        String tok = ghcrToken();
        java.net.HttpURLConnection c = ghcrOpen(tok,
                "https://ghcr.io/v2/" + GHCR_IMAGE + "/manifests/" + GHCR_TAG,
                "application/vnd.oci.image.index.v1+json,"
                        + "application/vnd.docker.distribution.manifest.list.v2+json");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("GHCR manifest: HTTP " + code);
        String indexDigest = c.getHeaderField("Docker-Content-Digest");
        if (indexDigest == null || indexDigest.length() < 19) {
            throw new Exception("GHCR: missing image index digest");
        }
        JSONObject index = new JSONObject(readAll(c.getInputStream()));
        String armDigest = null;
        JSONArray ms = index.getJSONArray("manifests");
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.getJSONObject(i);
            JSONObject p = m.optJSONObject("platform");
            if (p != null && "linux".equals(p.optString("os"))
                    && "arm64".equals(p.optString("architecture"))) {
                armDigest = m.getString("digest");
                break;
            }
        }
        if (armDigest == null) {
            throw new Exception("ghcr.io/kotpasztet/apace:dev has no linux/arm64 manifest");
        }
        JSONObject man = new JSONObject(ghcrGet(tok,
                "https://ghcr.io/v2/" + GHCR_IMAGE + "/manifests/" + armDigest,
                "application/vnd.oci.image.manifest.v1+json,"
                        + "application/vnd.docker.distribution.manifest.v2+json"));
        String cfgDigest = man.getJSONObject("config").getString("digest");
        JSONObject cfg = new JSONObject(ghcrGet(tok,
                "https://ghcr.io/v2/" + GHCR_IMAGE + "/blobs/" + cfgDigest, null));
        JSONArray hist = cfg.optJSONArray("history");
        JSONArray layers = man.getJSONArray("layers");

        DevBuild d = new DevBuild();
        d.indexDigest = indexDigest;
        for (int i = 0; i < layers.length(); i++) {
            JSONObject l = layers.getJSONObject(i);
            d.layers.add(new Layer(l.getString("digest"), l.optLong("size", 0)));
        }
        // klasyfikacja po historii (kroki non-empty_layer mapują 1:1 na warstwy)
        boolean appSection = false;
        int li = 0;
        StringBuilder sysKey = new StringBuilder();
        StringBuilder appKey = new StringBuilder();
        for (int h = 0; h < (hist != null ? hist.length() : 0) && li < d.layers.size(); h++) {
            JSONObject step = hist.getJSONObject(h);
            if (step.optBoolean("empty_layer", false)) continue;
            Layer layer = d.layers.get(li);
            String createdBy = step.optString("created_by", "");
            if (createdBy.contains("build/Release/latest")) appSection = true;
            if (createdBy.contains("docker.io docker-compose-v2")) {
                // docker CLI + compose do samo-aktualizacji panelu przez gniazdo
                // dockera — na telefonie nie istnieje; pomijamy (~110 MB compressed)
                layer.skip = true;
                d.skippedLayers++;
            } else if (appSection) {
                layer.app = true;
                d.appSize += layer.size;
                if (appKey.length() > 0) appKey.append(',');
                appKey.append(layer.digest);
            } else {
                d.systemSize += layer.size;
                if (sysKey.length() > 0) sysKey.append(',');
                sysKey.append(layer.digest);
            }
            if (appSection && d.layerDigest == null && !layer.skip) {
                d.layerDigest = layer.digest; // „COPY build/Release/latest”
            }
            li++;
        }
        d.systemKey = sysKey.toString();
        d.appKey = appKey.toString();
        if (d.layerDigest == null || d.appKey.length() == 0) {
            throw new Exception("ghcr image: /app layer (build/Release/latest) not found");
        }
        d.revision = extractRevision(cfg);
        d.created = cfg.optString("created", null);
        d.version = d.revision != null && d.revision.length() >= 12
                ? d.revision.substring(0, 12)
                : indexDigest.substring(7, 19);
        return d;
    }

    /**
     * Commit, z którego zbudowano obraz: ENV APACE_COMMIT → label
     * org.opencontainers.image.revision → ARG SOURCE_COMMIT z historii.
     * Puste wartości są pomijane (ENV APACE_COMMIT= przy braku build-arga).
     */
    private static String extractRevision(JSONObject cfg) {
        try {
            JSONArray env = cfg.getJSONObject("config").getJSONArray("Env");
            for (int i = 0; i < env.length(); i++) {
                String e = env.getString(i);
                if (e.startsWith("APACE_COMMIT=")) {
                    String v = e.substring("APACE_COMMIT=".length()).trim();
                    if (v.matches("[0-9a-fA-F]{8,40}")) return v;
                }
            }
        } catch (Exception ignore) {}
        try {
            JSONObject labels = cfg.getJSONObject("config").optJSONObject("Labels");
            if (labels != null) {
                String l = labels.optString("org.opencontainers.image.revision", "");
                if (l.trim().matches("[0-9a-fA-F]{8,40}")) return l.trim();
            }
        } catch (Exception ignore) {}
        try {
            JSONArray hist = cfg.getJSONArray("history");
            for (int i = hist.length() - 1; i >= 0; i--) {
                String by = hist.getJSONObject(i).optString("created_by", "");
                int a = by.indexOf("SOURCE_COMMIT=");
                if (a >= 0) {
                    a += "SOURCE_COMMIT=".length();
                    String sha = by.substring(a, Math.min(by.length(), a + 40));
                    if (sha.matches("[0-9a-f]{8,40}")) return sha;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** HEAD brancha dev w repozytorium Apace — referencja „najnowszego commita”. */
    public static DevCommit latestDevCommit() throws Exception {
        String body = fetchCached("https://api.github.com/repos/" + REPO + "/commits/dev");
        JSONObject o = new JSONObject(body);
        DevCommit out = new DevCommit();
        out.sha = o.optString("sha", null);
        if (out.sha == null || out.sha.length() < 8) {
            throw new Exception("GitHub API: no sha for dev branch HEAD");
        }
        try {
            out.date = o.getJSONObject("commit").getJSONObject("committer").getString("date");
        } catch (Exception ignore) {}
        return out;
    }

    /** Świeży anonimowy token pull ghcr.io (bloby OCI wymagają Bearer). */
    public static String ghcrToken() throws Exception {
        java.net.HttpURLConnection c = Downloader.open(
                "https://ghcr.io/token?scope=repository:" + GHCR_IMAGE + ":pull");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("GHCR token: HTTP " + code);
        return new JSONObject(readAll(c.getInputStream())).getString("token");
    }

    private static java.net.HttpURLConnection ghcrOpen(String token, String url, String accept)
            throws Exception {
        java.net.HttpURLConnection c = Downloader.open(url);
        if (accept != null) c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static String ghcrGet(String token, String url, String accept) throws Exception {
        java.net.HttpURLConnection c = ghcrOpen(token, url, accept);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("GHCR HTTP " + code + " at " + url);
        return readAll(c.getInputStream());
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
