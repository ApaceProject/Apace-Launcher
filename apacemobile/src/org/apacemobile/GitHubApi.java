package org.apacemobile;

import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Klient GitHub REST API (releases) — bez bibliotek zewnętrznych, parser JsonReader.
 * Używany do sprawdzania i pobierania aktualizacji Apace (kanał Stable)
 * oraz do rozwiązywania obrazu kanału Dev z ghcr.io (anonimowy pull OCI —
 * dokładnie ten sam mechanizm, którego używa docker-compose.dev.yml).
 */
public final class GitHubApi {

    public static final String REPO = "KotPasztet/Apace";

    // ── Kanał Dev: obraz ghcr.io/kotpasztet/apace:dev ──────────────────────
    // Publikowany przez workflow docker-image-dev.yml przy każdym pushu do dev.
    // Apka ściąga wyłącznie warstwę OCI z zawartością /app (COPY
    // /src/build/Release/latest/) — Java i .NET ma już własne w rootfs.
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

    /** Najnowszy stabilny release (releases/latest pomija prereleases). */
    public static Release latestRelease() throws Exception {
        return releaseByApiUrl("https://api.github.com/repos/" + REPO + "/releases/latest");
    }

    private static Release releaseByApiUrl(String apiUrl) throws Exception {
        java.net.HttpURLConnection c = Downloader.open(apiUrl);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 403) {
            throw new Exception("GitHub API rate limit exhausted (403) — try again later.");
        }
        if (code != 200) {
            throw new Exception("GitHub API HTTP " + code);
        }
        JsonReader r = new JsonReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        Release rel = parseRelease(r);
        r.close();
        if (rel.tag == null) throw new Exception("GitHub API response without tag_name");
        return rel;
    }

    private static Release parseRelease(JsonReader r) throws Exception {
        Release rel = new Release();
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            if ("tag_name".equals(key)) {
                rel.tag = r.nextString();
            } else if ("name".equals(key)) {
                rel.name = r.peek() == android.util.JsonToken.NULL ? null : r.nextString();
            } else if ("assets".equals(key)) {
                r.beginArray();
                while (r.hasNext()) {
                    Asset a = new Asset();
                    r.beginObject();
                    while (r.hasNext()) {
                        String k2 = r.nextName();
                        if ("name".equals(k2)) {
                            a.name = r.nextString();
                        } else if ("browser_download_url".equals(k2)) {
                            a.url = r.nextString();
                        } else if ("size".equals(k2)) {
                            a.size = r.nextLong();
                        } else if ("updated_at".equals(k2)) {
                            a.updated = r.nextString();
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                    rel.assets.add(a);
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
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

    // ── Kanał Dev: ghcr.io (OCI) ───────────────────────────────────────────

    public static class DevBuild {
        public String version;     // krótki digest indexu obrazu (12 hex) — wersja w UI
        public String layerDigest; // digest warstwy OCI z zawartością /app
    }

    /** URL blobu warstwy OCI (pobranie wymaga świeżego anonimowego tokenu). */
    public static String ghcrBlobUrl(String digest) {
        return "https://ghcr.io/v2/" + GHCR_IMAGE + "/blobs/" + digest;
    }

    /**
     * Najnowszy build dev: anonimowy token ghcr → index manifestów (multi-arch) →
     * manifest linux/arm64 → config obrazu → historia warstw → warstwa
     * „COPY /src/build/Release/latest/” (zawartość /app = layout jak w zipie).
     * Wersja = krótki digest indexu — zmienia się przy każdym pushu do dev.
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
        JSONArray hist = cfg.getJSONArray("history");
        JSONArray layers = man.getJSONArray("layers");
        String appLayer = null;
        for (int h = 0, l = 0; h < hist.length() && l < layers.length(); h++) {
            JSONObject step = hist.getJSONObject(h);
            if (step.optBoolean("empty_layer", false)) continue;
            if (step.optString("created_by", "").contains("build/Release/latest")) {
                appLayer = layers.getJSONObject(l).getString("digest");
                break;
            }
            l++;
        }
        if (appLayer == null) {
            throw new Exception("ghcr image: /app layer (build/Release/latest) not found");
        }
        DevBuild d = new DevBuild();
        d.version = indexDigest.substring(7, 19); // po „sha256:” bierzemy 12 hex
        d.layerDigest = appLayer;
        return d;
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
        if (code != 200) throw new Exception("GHCR HTTP " + code + " przy " + url);
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
