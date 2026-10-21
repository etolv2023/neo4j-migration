package com.etolv.neo4jtool.reader;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.values.storable.ArrayValue;
import org.neo4j.values.storable.BooleanValue;
import org.neo4j.values.storable.DurationValue;
import org.neo4j.values.storable.FloatingPointValue;
import org.neo4j.values.storable.IntegralValue;
import org.neo4j.values.storable.PointValue;
import org.neo4j.values.storable.TextValue;

/**
 * Turns a property value read by the kernel into the driver's {@link Value}, which is what the rest of the tool (the CSV
 * encoding and the fingerprints) works with. A value read over Bolt and the same value read here come out equal, so the
 * two readers are interchangeable.
 */
final class KernelValues {

    private KernelValues() {
    }

    static Value toDriver(org.neo4j.values.storable.Value value) {
        switch (value.valueRepresentation()) {
            case UTF8_TEXT:
            case UTF16_TEXT:
                return Values.value(((TextValue) value).stringValue());
            case BOOLEAN:
                return Values.value(((BooleanValue) value).booleanValue());
            case INT64:
            case INT32:
            case INT16:
            case INT8:
                return Values.value(((IntegralValue) value).longValue());
            case FLOAT64:
            case FLOAT32:
                return Values.value(((FloatingPointValue) value).doubleValue());
            case DATE:
                return Values.value((LocalDate) value.asObjectCopy());
            case ZONED_TIME:
                return Values.value((OffsetTime) value.asObjectCopy());
            case LOCAL_TIME:
                return Values.value((LocalTime) value.asObjectCopy());
            case LOCAL_DATE_TIME:
                return Values.value((LocalDateTime) value.asObjectCopy());
            case ZONED_DATE_TIME:
                return Values.value((ZonedDateTime) value.asObjectCopy());
            case DURATION: {
                DurationValue d = (DurationValue) value;
                return Values.isoDuration(d.get(ChronoUnit.MONTHS), d.get(ChronoUnit.DAYS), d.get(ChronoUnit.SECONDS),
                        (int) d.get(ChronoUnit.NANOS));
            }
            case GEOMETRY: {
                PointValue p = (PointValue) value;
                double[] c = p.coordinate();
                int srid = p.getCoordinateReferenceSystem().getCode();
                return c.length == 3 ? Values.point(srid, c[0], c[1], c[2]) : Values.point(srid, c[0], c[1]);
            }
            case INT8_ARRAY:
                return Values.value((byte[]) value.asObjectCopy());
            default:
                if (value instanceof ArrayValue array) {
                    Value[] items = new Value[array.intSize()];
                    for (int i = 0; i < items.length; i++) {
                        items[i] = toDriver((org.neo4j.values.storable.Value) array.value(i));
                    }
                    return Values.value(items);
                }
                throw new UnsupportedOperationException("A property value of type " + value.getTypeName() + " cannot be copied");
        }
    }
}
