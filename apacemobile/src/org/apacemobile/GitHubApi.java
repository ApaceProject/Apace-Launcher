package org.apacemobile;

import android.util.JsonReader;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Klient GitHub REST API (releases) — bez bibliotek zewnętrznych, parser JsonReader.
 * Używany do sprawdzania i pobierania aktualizacji Apace (kanał Stable).
 */
public final class GitHubApi {

    public static final String REPO = "KotPasztet/Apace";

    /** Oficjalny build gałęzi dev Apace — rolling prerelease „dev-build”
     *  publikowany przez workflow development.yml (Developer Build) w repo Apace. */
    public static final String DEV_TAG = "dev-build";

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

    /** Release po tagu (także prerelease) — kanał Dev używa rolling release „dev-build”. */
    public static Release releaseByTag(String tag) throws Exception {
        return releaseByApiUrl("https://api.github.com/repos/" + REPO + "/releases/tags/" + tag);
    }

    private static Release releaseByApiUrl(String apiUrl) throws Exception {
        java.net.HttpURLConnection c = Downloader.open(apiUrl);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404 && apiUrl.contains("/tags/")) {
            throw new Exception("No \"dev-build\" release yet — run the Developer Build "
                    + "workflow in the Apace repo (dev branch) first.");
        }
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

    /** Asset linux-arm64 z rolling release „dev-build” (Apace-Dev-linux-arm64.zip). */
    public static Asset pickDevArm64Asset(Release rel) {
        for (Asset a : rel.assets) {
            String n = a.name.toLowerCase();
            if (n.startsWith("apace-dev-linux-arm64") && n.endsWith(".zip")) return a;
        }
        return null;
    }
}
