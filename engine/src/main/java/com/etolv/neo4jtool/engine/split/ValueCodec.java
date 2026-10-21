package com.etolv.neo4jtool.engine.split;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.IsoDuration;
import org.neo4j.driver.types.Point;

/**
 * Turns a property value into what the CSV import of neo4j-admin can carry, and into a canonical text used to
 * fingerprint it. Everything that the import cannot carry exactly is reported as {@code null} from
 * {@link #encode}: the split then writes it in a second pass, straight from the source value.
 *
 * <p>What the import cannot carry (found by trying it against Neo4j 5.26): empty lists (an empty cell means "no
 * property"), date-times with a named time zone (only offsets are parsed), and property names that contain ':' or
 * parentheses (they are part of the header syntax).
 */
final class ValueCodec {

    /** Separates the elements of a list inside one CSV cell. A control character, so it cannot clash with text. */
    static final char ARRAY_DELIMITER = '\u001f';

    /** A value as one CSV cell: {@code type} is the header type (for example "long" or "string[]"). */
    record Encoded(String type, String cell) {
    }

    private ValueCodec() {
    }

    /** @return the cell, or null when the CSV import cannot carry this value exactly */
    static Encoded encode(Value value) {
        switch (kind(value)) {
            case "STRING":
                return new Encoded("string", value.asString());
            case "INTEGER":
                return new Encoded("long", Long.toString(value.asLong()));
            case "FLOAT":
                return new Encoded("double", Double.toString(value.asDouble()));
            case "BOOLEAN":
                return new Encoded("boolean", Boolean.toString(value.asBoolean()));
            case "DATE":
                return new Encoded("date", value.asLocalDate().toString());
            case "TIME":
                return new Encoded("time", DateTimeFormatter.ISO_OFFSET_TIME.format(value.asOffsetTime()));
            case "LOCAL_TIME":
                return new Encoded("localtime", DateTimeFormatter.ISO_LOCAL_TIME.format(value.asLocalTime()));
            case "DATE_TIME": {
                ZonedDateTime zoned = value.asZonedDateTime();
                if (!(zoned.getZone() instanceof ZoneOffset)) {
                    return null;
                }
                return new Encoded("datetime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned));
            }
            case "LOCAL_DATE_TIME":
                return new Encoded("localdatetime", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value.asLocalDateTime()));
            case "DURATION":
                return new Encoded("duration", duration(value.asIsoDuration()));
            case "POINT":
                return new Encoded("point", point(value.asPoint()));
            case "BYTES": {
                byte[] bytes = value.asByteArray();
                if (bytes.length == 0) {
                    return null;
                }
                StringBuilder cell = new StringBuilder();
                for (int i = 0; i < bytes.length; i++) {
                    if (i > 0) {
                        cell.append(ARRAY_DELIMITER);
                    }
                    cell.append(bytes[i]);
                }
                return new Encoded("byte[]", cell.toString());
            }
            case "LIST":
                return list(value);
            default:
                throw new UnsupportedOperationException("A property value of type " + kind(value)
                        + " cannot be copied");
        }
    }

    /** The driver names a list type "LIST OF ANY?", so every list is reported as plain LIST. */
    private static String kind(Value value) {
        String name = value.type().name();
        return name.startsWith("LIST") ? "LIST" : name;
    }

    private static Encoded list(Value value) {
        List<Value> items = new ArrayList<>();
        value.values().forEach(items::add);
        if (items.isEmpty()) {
            return null;
        }
        String elementType = null;
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Encoded item = encode(items.get(i));
            if (item == null || item.type().endsWith("[]")) {
                return null;
            }
            if (item.cell().indexOf(ARRAY_DELIMITER) >= 0) {
                return null;
            }
            if (elementType == null) {
                elementType = item.type();
            } else if (!elementType.equals(item.type())) {
                throw new UnsupportedOperationException("A list with elements of different types cannot be copied");
            }
            if (i > 0) {
                cell.append(ARRAY_DELIMITER);
            }
            cell.append(item.cell());
        }
        return new Encoded(elementType + "[]", cell.toString());
    }

    private static String duration(IsoDuration d) {
        BigDecimal seconds = BigDecimal.valueOf(d.seconds()).add(BigDecimal.valueOf(d.nanoseconds(), 9));
        return "P" + d.months() + "M" + d.days() + "DT" + seconds.toPlainString() + "S";
    }

    private static String point(Point p) {
        StringBuilder text = new StringBuilder("{x:").append(Double.toString(p.x())).append(", y:").append(Double.toString(p.y()));
        if (!Double.isNaN(p.z())) {
            text.append(", z:").append(Double.toString(p.z()));
        }
        return text.append(", srid:").append(p.srid()).append('}').toString();
    }

    /** True when the property name can be a CSV column name as it is. */
    static boolean isSafeKey(String key) {
        if (key.isEmpty() || key.charAt(0) == ' ' || key.charAt(key.length() - 1) == ' ') {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ':' || c == '(' || c == ')' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /** A text that is equal for equal values and different for values that differ in value or in type. */
    static void canonical(Value value, StringBuilder out) {
        switch (kind(value)) {
            case "STRING" -> out.append('s').append(value.asString().length()).append(':').append(value.asString());
            case "INTEGER" -> out.append('i').append(value.asLong());
            case "FLOAT" -> out.append('f').append(Long.toHexString(Double.doubleToLongBits(value.asDouble())));
            case "BOOLEAN" -> out.append(value.asBoolean() ? "b1" : "b0");
            case "DATE" -> out.append('d').append(value.asLocalDate());
            case "TIME" -> out.append('t').append(value.asOffsetTime());
            case "LOCAL_TIME" -> out.append("lt").append(value.asLocalTime());
            case "DATE_TIME" -> out.append("dt").append(value.asZonedDateTime());
            case "LOCAL_DATE_TIME" -> out.append("ldt").append(value.asLocalDateTime());
            case "DURATION" -> {
                IsoDuration d = value.asIsoDuration();
                out.append("du").append(d.months()).append(',').append(d.days()).append(',').append(d.seconds()).append(',')
                        .append(d.nanoseconds());
            }
            case "POINT" -> {
                Point p = value.asPoint();
                out.append('p').append(p.srid()).append(',').append(Long.toHexString(Double.doubleToLongBits(p.x()))).append(',')
                        .append(Long.toHexString(Double.doubleToLongBits(p.y()))).append(',')
                        .append(Long.toHexString(Double.doubleToLongBits(p.z())));
            }
            case "BYTES" -> {
                out.append('y');
                for (byte b : value.asByteArray()) {
                    out.append(Integer.toHexString(b & 0xFF)).append('.');
                }
            }
            case "LIST" -> {
                out.append('[');
                for (Value item : value.values()) {
                    canonical(item, out);
                    out.append(',');
                }
                out.append(']');
            }
            default -> throw new UnsupportedOperationException("A property value of type " + kind(value)
                    + " cannot be copied");
        }
    }
}
