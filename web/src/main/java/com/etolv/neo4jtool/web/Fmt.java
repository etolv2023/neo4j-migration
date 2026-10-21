package com.etolv.neo4jtool.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Formatting helpers for the templates, used as {@code @fmt.time(x)}. */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String time(Instant instant) {
        return instant == null ? "" : TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    public String number(Number value) {
        return value == null ? "" : String.format("%,d", value.longValue());
    }

    public String duration(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
