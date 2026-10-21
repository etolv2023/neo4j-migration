package com.etolv.neo4jtool.engine.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.Base64;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.IsoDuration;
import org.neo4j.driver.types.Point;

/**
 * A property value as one line of JSON with its type, and back. It carries the values the CSV import cannot (empty
 * lists, date-times with a named time zone): they are written to a file during the export and set afterwards, so the
 * source does not have to be read a second time.
 */
final class ValueJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ValueJson() {
    }

    static String write(long id, String key, Value value) {
        ObjectNode line = MAPPER.createObjectNode();
        line.put("id", id);
        line.put("key", key);
        line.set("v", node(value));
        return line.toString();
    }

    /** The node id, property name and value of a line made by {@link #write}. */
    record Line(long id, String key, Value value) {
    }

    static Line read(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            return new Line(node.get("id").asLong(), node.get("key").asText(), value(node.get("v")));
        } catch (java.io.IOException e) {
            throw new BuildException("A file of properties to write afterwards is damaged: " + e.getMessage(), e);
        }
    }

    private static ObjectNode typed(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", type);
        return node;
    }

    private static JsonNode node(Value value) {
        String kind = value.type().name();
        if (kind.startsWith("LIST")) {
            kind = "LIST";
        }
        ObjectNode out;
        switch (kind) {
            case "STRING" -> {
                out = typed("s");
                out.put("v", value.asString());
            }
            case "INTEGER" -> {
                out = typed("i");
                out.put("v", value.asLong());
            }
            case "FLOAT" -> {
                out = typed("f");
                out.put("v", Long.toHexString(Double.doubleToLongBits(value.asDouble())));
            }
            case "BOOLEAN" -> {
                out = typed("b");
                out.put("v", value.asBoolean());
            }
            case "DATE" -> {
                out = typed("d");
                out.put("v", value.asLocalDate().toString());
            }
            case "TIME" -> {
                out = typed("t");
                out.put("v", value.asOffsetTime().toString());
            }
            case "LOCAL_TIME" -> {
                out = typed("lt");
                out.put("v", value.asLocalTime().toString());
            }
            case "DATE_TIME" -> {
                out = typed("dt");
                out.put("v", value.asZonedDateTime().toString());
            }
            case "LOCAL_DATE_TIME" -> {
                out = typed("ldt");
                out.put("v", value.asLocalDateTime().toString());
            }
            case "DURATION" -> {
                IsoDuration d = value.asIsoDuration();
                out = typed("du");
                ArrayNode parts = out.putArray("v");
                parts.add(d.months()).add(d.days()).add(d.seconds()).add(d.nanoseconds());
            }
            case "POINT" -> {
                Point p = value.asPoint();
                out = typed("p");
                ArrayNode parts = out.putArray("v");
                parts.add(p.srid()).add(Long.toHexString(Double.doubleToLongBits(p.x())))
                        .add(Long.toHexString(Double.doubleToLongBits(p.y())))
                        .add(Long.toHexString(Double.doubleToLongBits(p.z())));
            }
            case "BYTES" -> {
                out = typed("y");
                out.put("v", Base64.getEncoder().encodeToString(value.asByteArray()));
            }
            case "LIST" -> {
                out = typed("l");
                ArrayNode items = out.putArray("v");
                for (Value item : value.values()) {
                    items.add(node(item));
                }
            }
            default -> throw new UnsupportedOperationException("A property value of type " + kind + " cannot be copied");
        }
        return out;
    }

    private static Value value(JsonNode node) {
        JsonNode v = node.get("v");
        switch (node.get("t").asText()) {
            case "s":
                return Values.value(v.asText());
            case "i":
                return Values.value(v.asLong());
            case "f":
                return Values.value(Double.longBitsToDouble(Long.parseUnsignedLong(v.asText(), 16)));
            case "b":
                return Values.value(v.asBoolean());
            case "d":
                return Values.value(LocalDate.parse(v.asText()));
            case "t":
                return Values.value(OffsetTime.parse(v.asText()));
            case "lt":
                return Values.value(LocalTime.parse(v.asText()));
            case "dt":
                return Values.value(ZonedDateTime.parse(v.asText()));
            case "ldt":
                return Values.value(LocalDateTime.parse(v.asText()));
            case "du":
                return Values.isoDuration(v.get(0).asLong(), v.get(1).asLong(), v.get(2).asLong(), v.get(3).asInt());
            case "p": {
                int srid = v.get(0).asInt();
                double x = Double.longBitsToDouble(Long.parseUnsignedLong(v.get(1).asText(), 16));
                double y = Double.longBitsToDouble(Long.parseUnsignedLong(v.get(2).asText(), 16));
                double z = Double.longBitsToDouble(Long.parseUnsignedLong(v.get(3).asText(), 16));
                return Double.isNaN(z) ? Values.point(srid, x, y) : Values.point(srid, x, y, z);
            }
            case "y":
                return Values.value(Base64.getDecoder().decode(v.asText()));
            case "l": {
                Value[] items = new Value[v.size()];
                for (int i = 0; i < items.length; i++) {
                    items[i] = value(v.get(i));
                }
                return Values.value(items);
            }
            default:
                throw new BuildException("A file of properties to write afterwards has a value of an unknown type");
        }
    }
}
