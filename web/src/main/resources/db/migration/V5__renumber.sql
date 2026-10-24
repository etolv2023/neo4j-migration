-- What a merge does with a selected node whose id the target uses for another node: the options and the properties the
-- person excluded after the dry run (JSON of RenumberOptions). NULL = it stops, as before.
ALTER TABLE migrations ADD COLUMN renumber_json TEXT NULL;
