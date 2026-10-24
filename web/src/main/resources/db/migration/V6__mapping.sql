-- The whole table of new ids of a dry run (gzip of one JSON object per line). The report keeps only the first rows of it;
-- this is what the CSV and JSON downloads give.
CREATE TABLE dry_run_mappings (
    report_id BIGINT   NOT NULL PRIMARY KEY,
    entries   BIGINT   NOT NULL,
    data      LONGBLOB NOT NULL,
    CONSTRAINT fk_dry_run_mappings_report FOREIGN KEY (report_id) REFERENCES dry_run_reports (id) ON DELETE CASCADE
);
