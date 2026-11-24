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

    public static class Asset {
        public String name;
        public String url;
        public long size;
    }

    public static class Release {
        public String tag;
        public String name;
        public List<Asset> assets = new ArrayList<Asset>();
    }

    public static Release latestRelease() throws Exception {
        java.net.HttpURLConnection c = Downloader.open(
                "https://api.github.com/repos/" + REPO + "/releases/latest");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 403) {
            throw new Exception("GitHub API rate limit exhausted (403) — try again later.");
        }
        if (code != 200) {
            throw new Exception("GitHub API HTTP " + code);
        }
        JsonReader r = new JsonReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
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
        r.close();
        if (rel.tag == null) throw new Exception("GitHub API response without tag_name");
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

    /**
     * Kanał "Main": manifest JSON {version, url, sha256} wystawiany przez własny
     * workflow CI (dokumentacja: docs/main-channel.yml). Bez URL-a kanał jest
     * niedostępny — aplikacja informuje użytkownika.
     */
    public static class Manifest {
        public String version;
        public String url;
        public String sha256;
    }

    public static Manifest fetchManifest(String manifestUrl) throws Exception {
        java.net.HttpURLConnection c = Downloader.open(manifestUrl);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("Dev channel manifest: HTTP " + code);
        JsonReader r = new JsonReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        Manifest m = new Manifest();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("version".equals(k)) m.version = r.nextString();
            else if ("url".equals(k)) m.url = r.nextString();
            else if ("sha256".equals(k)) m.sha256 = r.nextString();
            else r.skipValue();
        }
        r.endObject();
        r.close();
        if (m.url == null) throw new Exception("Dev channel manifest without a url field");
        return m;
    }
}
