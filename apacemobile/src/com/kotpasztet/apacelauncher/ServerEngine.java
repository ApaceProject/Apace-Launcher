package com.kotpasztet.apacelauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/**
 * Zarządzanie procesem serwera (specyfikacja 4.2): start przez PRoot,
 * monitorowanie, łagodne zatrzymanie + ubijanie całej grupy procesów
 * (proot → Launcher → ApiServer → JVM Fabric), statystyki z /proc.
 */
public final class ServerEngine {

    private final Paths paths;
    private final Prefs prefs;
    private Process process;
    private long startedAt = 0;

    public ServerEngine(Paths paths, Prefs prefs) {
        this.paths = paths;
        this.prefs = prefs;
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && isProcAlive(p);
    }

    private static boolean isProcAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public long startedAt() { return startedAt; }

    public void checkPreconditions() throws Exception {
        if (!paths.isInstalled()) throw new Exception("Apace is not installed.");
        // port panelu wolny?
        if (portOpen(127, "127.0.0.1", prefs.panelPort(), 300)) {
            throw new Exception("Port " + prefs.panelPort() + " is already used by another process.");
        }
        if (!paths.prootBin().isFile()) {
            throw new Exception("PRoot is not installed.");
        }
        // FIX „The environment is incomplete” przy zdrowym dev-image: /usr/bin/java
        // i /usr/bin/dotnet w rootfs z obrazu dockera to SYMLINKI z absolutnymi
        // celami (Debian alternatives / /usr/share/dotnet). Poza PRoot Android
        // rozwija je względem swojego „/”, więc isFile()/exists() dają false,
        // mimo że smoke test w PRoot przechodzi. Obecność sprawdzamy lstatem
        // (Paths.runtimePresent), ze wszystkimi układami: dev-image
        // (usr/bin/java, usr/bin/dotnet, usr/share/dotnet/dotnet) i Main
        // (usr/bin/java, opt/dotnet/dotnet).
        if (!Paths.runtimePresent(paths.ubuntu)) {
            throw new Exception("The environment is incomplete — reinstall it.");
        }
    }

    /** Uruchom Apace. Zwraca proces; czytanie logów przez LogSink. */
    public void start(PRoot.LogSink logSink, Runnable onExit) throws Exception {
        if (isAlive()) throw new Exception("Server is already running.");
        checkPreconditions();
        int heapBytes = prefs.heapMb() * 1024 * 1024;
        String bind = prefs.allowLan()
                ? "http://0.0.0.0:" + prefs.panelPort()
                : "http://127.0.0.1:" + prefs.panelPort();
        List<String> cmd = PRoot.startCommand(paths, heapBytes, bind);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        PRoot.applyEnv(paths, pb.environment());
        process = pb.start();
        startedAt = System.currentTimeMillis();

        final Process p = process;
        Thread tout = new Thread() { public void run() {
            pumpTo(p, false, logSink);
        }};
        Thread terr = new Thread() { public void run() {
            pumpTo(p, true, logSink);
        }};
        tout.start();
        terr.start();

        final Runnable cb = onExit;
        Thread monitor = new Thread() { public void run() {
            try {
                int code = p.waitFor(); // blokuje aż proot zakończy działanie
                if (cb != null) cb.run();
            } catch (InterruptedException e) { /* normalne przy zatrzymaniu */ }
        }};
        monitor.setDaemon(true);
        monitor.start();
    }

    private void pumpTo(Process p, boolean err, PRoot.LogSink sink) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    err ? p.getErrorStream() : p.getInputStream(), "UTF-8"), 8192);
            String line;
            while ((line = br.readLine()) != null) {
                if (sink != null) sink.onLine((err ? "[err] " : "") + line);
            }
        } catch (IOException ignore) {}
    }

    /**
     * Stop (specyfikacja 4.2): SIGTERM do proot, czekaj, potem twardo ubij
     * wszystkie procesy naszego środowiska widoczne w /proc (JVM, dotnet).
     */
    public void stop(PRoot.LogSink logSink) {
        Process p = process;
        if (p != null && isProcAlive(p)) {
            try { p.destroy(); } catch (Exception ignore) {}
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline && isProcAlive(p)) {
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
            try { p.destroyForcibly(); } catch (Exception ignore) {}
        }
        killTree(logSink);
        process = null;
        startedAt = 0;
    }

    /** Twarde zamknięcie wszystkich procesów środowiska (po p.destroy()). */
    private void killTree(PRoot.LogSink logSink) {
        List<Integer> pids = findEnvProcesses();
        for (int pid : pids) {
            try { android.os.Process.killProcess(pid); } catch (Exception ignore) {}
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<Integer> alive = findEnvProcesses();
            if (alive.isEmpty()) break;
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
        }
        for (int pid : findEnvProcesses()) {
            try { android.os.Process.sendSignal(pid, 9); } catch (Exception ignore) {}
        }
        if (logSink != null && !pids.isEmpty()) {
            logSink.onLine("[mobile] Killed environment processes: " + pids.size());
        }
    }

    /**
     * Procesy naszego środowiska = /proc/&lt;pid&gt;/cmdline zawierające ścieżkę rootfs
     * lub katalog instalacji Apace (proot tłumaczy execve na realną ścieżkę,
     * więc wewnętrzne procesy zawsze pokazują naszą ścieżkę w cmdline).
     */
    private List<Integer> findEnvProcesses() {
        List<Integer> out = new java.util.ArrayList<Integer>();
        String rootfs = paths.ubuntu.getAbsolutePath();
        String cur = paths.current.getAbsolutePath();
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) return out;
        int me = android.os.Process.myPid();
        for (File d : entries) {
            String name = d.getName();
            if (!name.matches("[0-9]+")) continue;
            int pid;
            try { pid = Integer.parseInt(name); } catch (NumberFormatException e) { continue; }
            if (pid == me) continue;
            String cmdline = readCmdline(pid);
            if (cmdline.contains(rootfs) || cmdline.contains(cur)) {
                out.add(pid);
            }
        }
        return out;
    }

    private static String readCmdline(int pid) {
        try {
            byte[] buf = new byte[8192];
            FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline");
            int n = in.read(buf);
            in.close();
            if (n <= 0) return "";
            for (int i = 0; i < n; i++) if (buf[i] == 0) buf[i] = (byte) ' ';
            return new String(buf, 0, n, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Wyślij komendę na stdin procesu (konsola). */
    public void sendCommand(String cmd) throws IOException {
        Process p = process;
        if (p == null || !isProcAlive(p)) throw new IOException("Server is not running");
        OutputStream os = p.getOutputStream();
        os.write((cmd + "\n").getBytes("UTF-8"));
        os.flush();
    }

    /**
     * Czy serwer Java (JVM Fabric/Minecraft) działa: cmdline procesów środowiska
     * zawiera binarkę java (proot pokazuje w cmdline realne ścieżki rootfs).
     * Używane przez auto-stop idle: java ON → nigdy nie zatrzymuj serwera.
     */
    public boolean javaServerRunning() {
        for (int pid : findEnvProcesses()) {
            for (String tok : readCmdline(pid).split(" ")) {
                String t = tok.trim();
                if (t.equals("java") || t.endsWith("/java")) return true;
            }
        }
        return false;
    }

    /**
     * Czy ktoś jest połączony z zewnątrz (Minecraft Earth / klient gry / panel w LAN):
     * /proc/net/tcp z połączeniem ESTABLISHED na portach serwisowych (panel 5000,
     * ApiPort 1808, event bus 5532) z adresu innego niż własny (loopback / adres
     * interfejsu tego urządzenia).
     *
     * KLUCZOWE (bug auto-stopu): połączenia własne panelu (SignalR, event bus,
     * ApiServer, WebView) w /proc/net/tcp6 pojawiają się jako IPv4-mapped
     * „0000000000000000FFFF00000100007F” (::ffff:127.0.0.1) albo pod adresem
     * własnego WLAN urządzenia — dawniej nie były rozpoznawane jako loopback,
     * więc auto-stop widział „klienta z zewnątrz” non stop i nigdy nie odpalał.
     */
    public boolean hasExternalConnections() {
        String[] ports = {
                String.valueOf(prefs.panelPort()), String.valueOf(prefs.apiPort()), "5532"};
        for (String f : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"), 8192);
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 10 || !parts[3].equals("01")) continue; // 01 = ESTABLISHED
                    if (!isServicePort(parts[1], ports)) continue;             // local_address:port
                    if (isSelfRemote(parts[2])) continue;                      // rem_address własny?
                    return true;
                }
                br.close();
            } catch (Exception ignore) {} // brak dostępu do /proc/net → traktuj jako brak
        }
        return false;
    }

    // ── Rozpoznawanie adresów własnych w /proc/net ────────────────────────

    /** Cache adresów własnych (NetworkInterface) — odświeżany co 15 s. */
    private static volatile long selfHexAt = -60000;
    private static volatile java.util.Set<String> selfHex = null;

    private static java.util.Set<String> selfAddressHex() {
        long now = android.os.SystemClock.elapsedRealtime();
        java.util.Set<String> cached = selfHex;
        if (cached != null && now - selfHexAt < 15000) return cached;
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            for (java.net.NetworkInterface nif : java.util.Collections
                    .list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress a : java.util.Collections.list(nif.getInetAddresses())) {
                    byte[] b = a.getAddress();
                    if (b == null) continue;
                    if (b.length == 4) out.add(ipv4ReversedHex(b));
                    else if (b.length == 16) out.add(ipv6ReversedHex(b));
                }
            }
        } catch (Throwable ignore) {}
        selfHex = out;
        selfHexAt = now;
        return out;
    }

    /**
     * rem_address HEX (z tcp lub tcp6) — czy to adres „własny” (nie zewnętrzny):
     * 127.0.0.1, ::1, postać IPv4-mapped ::ffff:x.x.x.x, 0.0.0.0/:: oraz dowolny
     * adres interfejsu tego urządzenia (połączenie panelu z samym sobą przez WLAN).
     * Format /proc/net: IPv4 = odwrotny dword („0100007f”=127.0.0.1), IPv6 = 4
     * odwrotnie zapisane dwordy (::1 → „00000000000000000000000001000000”).
     */
    private boolean isSelfRemote(String addrHex) {
        int c = addrHex.lastIndexOf(':');
        if (c <= 0) return false;
        String ip = addrHex.substring(0, c).toLowerCase(java.util.Locale.ROOT);
        if (ip.length() == 8) return isSelfV4Hex(ip);
        if (ip.length() == 32) {
            if (ip.startsWith("0000000000000000ffff0000")) return isSelfV4Hex(ip.substring(24));
            if (ip.equals("00000000000000000000000001000000") || ip.matches("^0+$")) return true;
            return selfAddressHex().contains(ip);
        }
        return false;
    }

    private static boolean isSelfV4Hex(String v4) {
        return v4.equals("0100007f") || v4.equals("00000000") || selfAddressHex().contains(v4);
    }

    /** IPv4 (4 bajty network order) → hex jak w /proc/net/tcp (odwrócony dword). */
    private static String ipv4ReversedHex(byte[] b) {
        return String.format(java.util.Locale.ROOT, "%02x%02x%02x%02x",
                b[3] & 0xff, b[2] & 0xff, b[1] & 0xff, b[0] & 0xff);
    }

    /** IPv6 (16 bajtów network order) → hex jak w /proc/net/tcp6 (4 odwrócone dwordy). */
    private static String ipv6ReversedHex(byte[] b) {
        StringBuilder sb = new StringBuilder(32);
        for (int g = 3; g >= 0; g--) {
            int o = g * 4;
            sb.append(String.format(java.util.Locale.ROOT, "%02x%02x%02x%02x",
                    b[o + 3] & 0xff, b[o + 2] & 0xff, b[o + 1] & 0xff, b[o] & 0xff));
        }
        return sb.toString();
    }

    /** local_address z /proc/net/tcp w formacie HEX „AABBCCDD:1F90”. */
    private static boolean isServicePort(String addrHex, String[] ports) {
        int c = addrHex.lastIndexOf(':');
        if (c < 0) return false;
        try {
            int port = Integer.parseInt(addrHex.substring(c + 1), 16);
            for (String p : ports) if (Integer.parseInt(p) == port) return true;
        } catch (Exception ignore) {}
        return false;
    }

    /** Czy TCP port nasłuchuje (czekanie na panel). */
    public static boolean portOpen(int unused, String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            try { s.close(); } catch (Exception ignore) {}
            return true;
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignore) {}
            return false;
        }
    }

    // ── Statystyki (Home) ─────────────────────────────────────────────────

    public static class Stats {
        public float cpuPercent;   // suma %CPU wszystkich rdzeni procesów środowiska
        public long rssBytes;      // suma RSS
        public long uptimeMs;
        public int processes;
    }

    public Stats stats(long lastCpuJiffies, long[] outLast) {
        Stats s = new Stats();
        s.uptimeMs = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        long totalJiffies = 0;
        List<Integer> pids = findEnvProcesses();
        s.processes = pids.size();
        for (int pid : pids) {
            totalJiffies += cpuJiffies(pid);
            s.rssBytes += rssBytes(pid);
        }
        long delta = lastCpuJiffies >= 0 ? totalJiffies - lastCpuJiffies : 0;
        if (delta < 0) delta = 0;
        s.cpuPercent = delta / 2f;      // okno 2 s, HZ=100 → 200 jiffies = 100% CPU
        outLast[0] = totalJiffies;
        return s;
    }

    private static long cpuJiffies(int pid) {
        try {
            String stat = readSmallFile("/proc/" + pid + "/stat");
            // pole 14 (utime) i 15 (stime); proces może mieć spację w comm → znajdź ')' 
            int close = stat.lastIndexOf(')');
            if (close < 0) return 0;
            String[] rest = stat.substring(close + 2).split(" ");
            // rest[0] = state (pole 3); utime = pole 14 → index 14-3 = 11; stime = 12
            if (rest.length > 12) {
                return parseLong(rest[11]) + parseLong(rest[12]);
            }
        } catch (Exception ignore) {}
        return 0;
    }

    private static long rssBytes(int pid) {
        try {
            String status = readSmallFile("/proc/" + pid + "/status");
            int i = status.indexOf("VmRSS:");
            if (i >= 0) {
                String line = status.substring(i, Math.min(status.length(), i + 64));
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) return parseLong(parts[1]) * 1024L;
            }
        } catch (Exception ignore) {}
        return 0;
    }

    private static String readSmallFile(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}
