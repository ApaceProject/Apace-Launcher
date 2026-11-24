package com.kotpasztet.apacelauncher;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Pierścieniowy bufor logów konsoli (widok Konsola). */
public final class LogRing {
    private static final ArrayDeque<String> RING = new ArrayDeque<String>(4000);
    private static final int MAX = 4000;

    public static void add(String line) {
        synchronized (RING) {
            if (RING.size() >= MAX) RING.poll();
            RING.add(line);
        }
    }

    public static String dump() {
        synchronized (RING) {
            StringBuilder sb = new StringBuilder();
            Iterator<String> it = RING.iterator();
            while (it.hasNext()) {
                sb.append(it.next()).append('\n');
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (RING) { RING.clear(); }
    }
}
