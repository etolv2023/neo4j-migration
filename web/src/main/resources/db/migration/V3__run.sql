-- What a finished run produced (JSON); null until a run has finished.
ALTER TABLE migrations ADD COLUMN run_json LONGTEXT NULL AFTER dry_run_id;
