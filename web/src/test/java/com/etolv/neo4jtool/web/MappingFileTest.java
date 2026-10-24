package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.build.RenumberPlanner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The stored table of new ids and its two downloads: every row comes back, however many there are. */
class MappingFileTest {

    private static RenumberPlanner.Entry entry(long i) {
        return new RenumberPlanner.Entry(i, 1_000_000 + i, List.of("Customer", "Vip"), i % 2 == 0 ? (Object) 26124496L : "text, with \"quotes\"",
                List.of("Supplier"), null);
    }

    @Test
    void moreRowsThanTheReportKeepsAreAllInBothDownloads() throws Exception {
        MappingFile.Collector collector = new MappingFile.Collector();
        int total = RenumberPlanner.MAPPING_LIMIT + 5_000;
        for (int from = 0; from < total; from += 7_000) {
            List<RenumberPlanner.Entry> batch = new ArrayList<>();
            for (long i = from; i < Math.min(total, from + 7_000); i++) {
                batch.add(entry(i));
            }
            collector.accept(batch);
        }
        assertThat(collector.entries()).isEqualTo(total);
        byte[] stored = collector.finish();

        ByteArrayOutputStream csv = new ByteArrayOutputStream();
        MappingFile.writeCsv(stored, csv);
        List<String> lines = csv.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(total + 1);
        assertThat(lines.get(0)).isEqualTo("old_id,new_id,labels,org,target_labels,target_org");
        assertThat(lines.get(1)).isEqualTo("0,1000000,Customer;Vip,26124496,Supplier,");
        assertThat(lines.get(2)).isEqualTo("1,1000001,Customer;Vip,\"text, with \"\"quotes\"\"\",Supplier,");
        assertThat(lines.get(total)).startsWith((total - 1) + "," + (1_000_000 + total - 1) + ",");

        ByteArrayOutputStream json = new ByteArrayOutputStream();
        MappingFile.writeJson(stored, json);
        var rows = Json.read(json.toString(StandardCharsets.UTF_8), RenumberPlanner.Entry[].class);
        assertThat(rows).hasSize(total);
        assertThat(rows[1].org()).isEqualTo("text, with \"quotes\"");
        assertThat(rows[total - 1].newId()).isEqualTo(1_000_000L + total - 1);
    }

    @Test
    void noRowsGiveAnEmptyArrayAndOnlyTheHeader() throws Exception {
        MappingFile.Collector collector = new MappingFile.Collector();
        byte[] stored = collector.finish();
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        MappingFile.writeJson(stored, json);
        assertThat(json.toString(StandardCharsets.UTF_8).strip()).isEqualTo("[]");
        ByteArrayOutputStream csv = new ByteArrayOutputStream();
        MappingFile.writeCsv(stored, csv);
        assertThat(csv.toString(StandardCharsets.UTF_8)).isEqualTo(MappingFile.CSV_HEADER);
    }
}
