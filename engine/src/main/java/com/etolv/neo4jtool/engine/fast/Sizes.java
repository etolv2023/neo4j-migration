package com.etolv.neo4jtool.engine.fast;

import java.util.Locale;

/** Reads sizes such as 512m or 2g. */
public final class Sizes {

    private Sizes() {
    }

    public static long bytes(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (t.endsWith("b")) {
            t = t.substring(0, t.length() - 1);
        }
        long unit = 1;
        if (!t.isEmpty()) {
            switch (t.charAt(t.length() - 1)) {
                case 'k' -> unit = 1L << 10;
                case 'm' -> unit = 1L << 20;
                case 'g' -> unit = 1L << 30;
                case 't' -> unit = 1L << 40;
                default -> {
                }
            }
            if (unit != 1) {
                t = t.substring(0, t.length() - 1);
            }
        }
        try {
            return Math.multiplyExact(Long.parseLong(t.strip()), unit);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("'" + text + "' is not a size; write for example 512m or 2g");
        }
    }
}
