package org.apacemobile;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Budowanie i uruchamianie komend PRoot.
 *
 * Środowisko wewnętrzne: Ubuntu ARM64 (glibc) montowany przez PRoot, z bindami
 * /dev, /proc, /sys oraz katalogiem instalacji Apace pod /apace.
 * Env serwera = dokładnie te zmienne, które ustawia run-inside.sh z
 * install-termux.sh / Dockerfile Apace (audyt Etap 0).
 */
public final class PRoot {

    /** Zbuduj pełną komendę startową: proot ... -- env -i ... /bin/bash /apace/run-inside.sh */
    public static List<String> startCommand(Paths paths, int gcHardLimitBytes, String panelBind) {
        // binding /apace wymaga istniejącego katalogu — inaczej proot warning
        if (!paths.current.isDirectory()) Paths.mkdirs(paths.current);
        List<String> cmd = new ArrayList<String>();
        cmd.add(paths.prootBin().getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("-r"); cmd.add(paths.ubuntu.getAbsolutePath());
        cmd.add("-0");
        cmd.add("-w"); cmd.add("/root");
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add(paths.current.getAbsolutePath() + ":/apace");
        // UWAGA: PRoot NIE wspiera separatora „--" („unknown option '--'") —
        // parsowanie opcji kończy pierwszy argument bez „-", tu: /usr/bin/env.
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("TERM=xterm-256color");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PATH=/opt/dotnet:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("DOTNET_ROOT=/opt/dotnet");
        cmd.add("APACE_PANEL_BIND=" + panelBind);
        cmd.add("APACE_GC_HARD_LIMIT=" + gcHardLimitBytes);
        cmd.add("/bin/bash");
        cmd.add("/apace/run-inside.sh");
        return cmd;
    }

    /** Środowisko procesu PRoot (bionic linker + loader + biblioteki z assets). */
    public static void applyEnv(Paths paths, Map<String, String> env) {
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("PROOT_LOADER", paths.prootLoader().getAbsolutePath());
        env.put("PROOT_LOADER32", paths.prootLoader32().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", paths.prootDir.getAbsolutePath());
        env.put("PROOT_TMP_DIR", paths.cache.getAbsolutePath());
        env.put("TMPDIR", paths.cache.getAbsolutePath());
    }

    /** Komenda jednorazowa wewnątrz rootfs (instalacja pakietów, smoke test). */
    public static List<String> shellCommand(Paths paths, String bashScript) {
        if (!paths.current.isDirectory()) Paths.mkdirs(paths.current);
        List<String> cmd = new ArrayList<String>();
        cmd.add(paths.prootBin().getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("-r"); cmd.add(paths.ubuntu.getAbsolutePath());
        cmd.add("-0");
        cmd.add("-w"); cmd.add("/root");
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add(paths.current.getAbsolutePath() + ":/apace");
        // UWAGA: PRoot NIE wspiera separatora „--" („unknown option '--'") —
        // parsowanie opcji kończy pierwszy argument bez „-", tu: /usr/bin/env.
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("TERM=xterm");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PATH=/opt/dotnet:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("DEBIAN_FRONTEND=noninteractive");
        cmd.add("DOTNET_ROOT=/opt/dotnet");
        cmd.add("/bin/bash");
        cmd.add("-e");
        cmd.add("-c");
        cmd.add(bashScript);
        return cmd;
    }

    public static void applyEnvShell(Paths paths, Map<String, String> env) {
        applyEnv(paths, env);
    }

    public static class Result {
        public int exitCode;
        public StringBuilder out = new StringBuilder();
        public boolean ok() { return exitCode == 0; }
        public String tail(int lines) {
            String[] arr = out.toString().split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, arr.length - lines);
            for (int i = from; i < arr.length; i++) sb.append(arr[i]).append('\n');
            return sb.toString();
        }
    }

    /** Uruchom komendę synchronicznie; loguj na żywo przez LogSink. */
    public static Result run(List<String> cmd, Paths paths, final LogSink sink) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        applyEnvShell(paths, pb.environment());
        final Process p = pb.start();
        final Result res = new Result();
        Thread t1 = pump(p.getInputStream(), sink, res.out);
        Thread t2 = pump(p.getErrorStream(), sink, res.out);
        t1.start();
        t2.start();
        res.exitCode = p.waitFor();
        t1.join(5000);
        t2.join(5000);
        return res;
    }

    public interface LogSink {
        void onLine(String line);
    }

    private static Thread pump(final InputStream in, final LogSink sink, final StringBuilder acc) {
        return new Thread() {
            @Override public void run() {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, "UTF-8"), 8192);
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (acc.length() < 400000) acc.append(line).append('\n');
                        if (sink != null) sink.onLine(line);
                    }
                } catch (Exception ignore) {}
            }
        };
    }
}
