# Phase 1 results

All numbers come from throw-away Docker containers in a 2-vCPU / 8 GB sandbox. No real server, database or credential
was touched. Treat the performance numbers as a lower bound: the two Neo4j servers and the tool shared those two CPUs.

## What works

- **Schema view** — labels, relationship types, indexes, constraints, counts, optional per-organisation breakdown.
- **Selection** — organisations, labels, ids, id ranges, exclusions; validated, unknown keys rejected, identifiers escaped.
- **Dry run** — for every selected node of the source: missing / same / clash on the target; runs of consecutive missing
  ids; relationships of the nodes that would be restored classified as internal, resolved, conflict, dangling (or
  outside-selection when there is no target); a CLEAR / WARN / BLOCKED gate with reasons.
- **Read-only by construction** — every Neo4j session is opened in READ mode; a test proves the server rejects a write
  through the tool's session.
- **CLI and dashboard** — dashboard keeps connections (passwords AES-GCM encrypted), saved selections, report history and
  an audit log in MySQL, runs dry runs in the background, listens on localhost with one login from the environment.

## Tests

`mvn clean verify` passes: 63 tests (25 unit, 16 engine integration, 5 CLI, 5 + 12 dashboard). The important ones:

- `DryRunScenarioIT` — a restore story small enough to check by hand (lost ids, a changed label, a changed organisation,
  a relationship to a node that no longer exists).
- `DryRunDifferentialIT` — 8 rounds of random damage to the live database × 6 random selections × with and without a
  target, 96 comparisons of the engine against an independent brute-force oracle that shares no code with it, using
  batch size 100 so every batch boundary is crossed. Deliberately breaking the oracle makes this test fail, so it is not
  vacuous.
- `MainIT` — exit codes 0 / 20 / 1, error output without stack traces.
- `WebIT` — real MySQL (Flyway) and two real Neo4j servers: login, CSRF, encrypted passwords, schema page, selection
  validation, background dry run, stored report, bad input, interrupted runs, audit log.

## Benchmark (dry run)

Data: backup 3,000,000 nodes / 6,000,000 relationships, ids 0..2,999,999. Live copy: 3% of the ids deleted in runs,
0.3% of survivors with another organisation, 300,000 newer nodes. Both servers 1 GB heap + 1 GB page cache, the tool
with `-Xmx1g`, all on the same machine, no index on `org_id`.

| Selection | Selected nodes | Relationships analysed | Engine time | Peak RSS of the tool |
|---|---:|---:|---:|---:|
| 10 organisations | 150,001 | 17,992 | 9.0 s | 181 MB |
| label `Booking` | 375,000 | 44,784 | 7.9 s | 247 MB |
| everything, nodes only | 3,000,000 | – | 30.8 s (about 97,000 nodes/s) | 319 MB |
| everything, with relationships | 3,000,000 | 354,948 | 32.0 s | 306 MB |

Cross-check: 90,014 nodes reported missing is the number the generator deleted, and the 354,948 relationships equal
backup relationships (6,000,000) minus live relationships (5,645,052), which is exactly the set touching a deleted node.

Memory grows by roughly 17 bytes per selected node (sorted id, packed label/organisation index, status) plus the list of
missing ids for the relationship pass. That is a design estimate, not a measurement: 100 million selected nodes would
need about 1.7 GB of heap. Larger data sets have not been run here.

## Spike: `neo4j-admin database import full --id-type=actual`

Neo4j 5.26.29 Community, offline import into a fresh database.

| Question | Observed |
|---|---|
| Are node ids kept? | Yes. Sparse ids 0–4, 10–14, 1000–1004 came back as exactly those `id(n)` values. |
| Does input order matter? | No. Ids 1000, 3, 12, 7 in that order were accepted. |
| Relationship ids? | New. They are numbered from 0 in the order of the file. `id(r)` is **not** preserved. |
| Do the gaps get reused? | Not after the import: new nodes continued at highest id + 1, also after a restart. |
| Relationship to an id that is not in the nodes file? | Imported without any error. The consistency check then reports an inconsistent relationship record. |

The last row is why the dry run treats dangling relationships as a gate, and why a restore must be built from a list
that has been checked, not from whatever the backup contains.

Not investigated: the consistency check also printed about a thousand warnings for the gap-heavy store.

## What this means for phase 2

- Community Edition has no online or incremental ID-preserving write. The ID-preserving path is: export the nodes to keep
  from the live database plus the selected nodes from the backup into CSV with their ids, import into a **new** database
  with `--id-type=actual`, check it, then swap. The database must be offline for the swap.
- A clash cannot be resolved by the tool; someone has to decide which node keeps the id. Phase 1 stops there.
- On Neo4j, ids of deleted nodes can be handed to nodes created later. Any clash the dry run reports on the real data is
  probably this effect, and the sample shows what each clashing node is.
- A backup gate (refuse to continue without a fresh dump of the live database) needs the database stopped on Community.

## Open questions for phase 2

1. Does the application use `id(r)` for relationships anywhere? If yes, this approach changes those ids and the plan must
   change.
2. Which property values must be compared for "same node"? Phase 1 compares labels and the organisation only.
3. Which indexes and constraints must exist before data is loaded?

## Limits of this delivery

- Validated on synthetic data with 3 million nodes, not on a copy of the real database. Run a dry run on a copy of the
  real data before trusting any number, and look at the clash samples.
- Only Neo4j 5.26.29 Community was tested.
- The dashboard has one user and no TLS; it is meant for localhost behind your own access controls.

## Stage A (dashboard flow, version 0.1)

Added on top of phase 1: login with users in MySQL, databases of three kinds (live server, dump file, stopped server's
data directory), migrations with From / To / selection, plan, dry run, settings, job logs, audit log and sign-in log.
Dumps and stopped data directories are served through a throw-away Neo4j (Docker image or a local installation).

`mvn clean verify` passes: 79 tests (25 engine unit tests and 11 dashboard unit tests; 22 engine, 5 CLI and 16 dashboard
integration tests). New tests cover: a dump loaded and read through Docker and through a local installation, a stopped data
directory dumped without changing a file in it, the whole dashboard flow against real MySQL and Neo4j (login, CSRF, encrypted
passwords, endpoint validation, plan then dry run with the clash found, a failed job returning to its state with the reason,
settings validation, password change, audit log) and the selection form parser.

Observed on the demo data (13,000 nodes, dump of 0.5 MB): loading a dump and starting the temporary server took about 20
seconds, so a plan or dry run on a dump costs that on top of the comparison. Not measured on large dumps.

Known limits: the plan and the dry run each load a dump separately; if the dashboard process is killed during a job the
temporary container is left behind.

## Stage B (split executor)

A split copies the selected nodes and the relationships between them into a new database with node ids kept, and writes it
as a dump file. The source is only read (READ-mode sessions); the only write session talks to the throw-away server that
holds the new database.

How it works: read the selected ids → export typed CSV (one file per label set and per combination of conflicting property
types) → `neo4j-admin database import full --id-type=actual --bad-tolerance=0` → throw-away server to write what the import
cannot carry (empty lists, dates with a named zone) and to create indexes and constraints → read the new database back and
compare ids, counts and a 64-bit fingerprint over every node and relationship → graceful stop → optional
`neo4j-admin database check` → dump → move to the output directory → remove all temporary files and containers.

`mvn clean verify` passes: 91 tests (31 engine and 11 dashboard unit tests; 26 engine, 5 CLI and 18 dashboard integration
tests). New tests:

- `SplitServiceIT`, with the Docker runner and with a local installation: sparse ids (deleted filler), strings with
  newlines, `\r\n`, quotes, commas and non-ASCII text, empty strings, extreme numbers, all temporal types (including a named
  time zone), durations (also negative), 2D, 3D and geographic points, lists of strings, numbers, booleans and dates, an
  empty list, a byte array, the same property name as a number on some nodes and as text on others, parallel relationships,
  a self relationship, relationship properties, relationships leaving and entering the selection, an index and a unique
  constraint. The dump is loaded and **every node (id, labels, properties) and every relationship between the selected nodes
  is compared with the source**, and so are the indexes and constraints; `database check` passes; no container or work file is
  left; an existing output file is refused; an empty selection stops before anything is built.
- `ValueCodecTest` for the CSV encoding rules and the fingerprint.
- Dashboard tests: a run needs a finished dry run, a merge cannot be run, a split runs to a dump file whose path and result
  are shown, a second run is refused while one is working, the log and audit entries exist, a new plan clears the old result.

Bugs found by these tests and fixed: the driver names list types `LIST OF ANY?`, which made every list property fail; a
`datetime` with a named zone and an empty list cannot be imported and are now written afterwards.

Limits: the Run is experimental until it is tried on a copy of the real database; "same node" compares labels and the
organisation property only; relationship ids change; a relationship property that needs a fix-up after the import is refused;
the new store is sized by the highest selected node id; times for large databases were not measured.

## Stage C (merge executor)

A merge puts the selected nodes that are missing on a **stopped** target (its data directory, or a dump) back, keeping every
node the target has. Nothing is written to a running server; Neo4j Community cannot write chosen ids to one.

How it works: dump the stopped target into the backup directory (mandatory, never deleted) → serve that backup on a throw-away
server and read the target from it → classify every selected id (same node on the target / free / used by a different node,
which stops the run) → export the whole target and the nodes to restore as CSV → the same build as a split (import with ids
kept, write back what the import cannot carry, indexes and constraints of the target, read back and compare ids, counts and
fingerprints, `neo4j-admin database check`) → swap into the data directory (the old store is kept as `before-<time>/`, the swap
undoes itself on failure, and it is refused if the target's files changed since the backup) or write a dump → rollback
available.

`mvn clean verify` passes: 98 tests (31 engine and 11 dashboard unit tests; 30 engine, 5 CLI and 21 dashboard integration tests). New tests:

- `MergeServiceIT` (4 tests): a damaged copy of a database (a company deleted, one node holding another company's value, a
  node that exists only on the target) merged with its backup, **with Docker and with a local installation**. Every node (id,
  labels, properties) and every relationship of the result is compared with what the merge must give, worked out in the test from
  both databases; the schema is compared; the backup equals the target as it was; after the rollback the store is
  byte-for-byte the old one (name, size and time of every file) and a second rollback is refused. A dump target gives a new dump
  and the target dump is unchanged. A clash and "nothing to restore" stop before anything is built and leave no file behind.
- `WebIT` (21 tests): a merge into a stopped data directory through the dashboard — dry run, run, result card, backup file,
  kept directory, a second run refused until it is rolled back, rollback, audit entries — and a merge into a live server refused.

Bug found by these tests: a node created on the target after a deletion takes over the freed id, so it shows up as a clash. The
tool reports it and stops, which is the intended behaviour; the test data was changed to create it before the deletion.

Run through the dashboard on synthetic data (Docker runner, 2 vCPU, Neo4j heap 1 GB): source 325,000 nodes / 225,000
relationships, a stopped target with company 1 deleted (216,667 nodes / 108,334 relationships), restoring company 1:

| Step | Seconds |
|---|---:|
| Backing up the target (dump) | 25.3 |
| Comparing the selection with the target (loads the backup) | 11.2 |
| Exporting the target to CSV | 10.6 |
| Exporting the nodes to restore to CSV | 6.5 |
| Import with ids kept | 14.7 |
| Finishing the new database on a temporary server | 17.2 |
| Read-back comparison | 7.0 |
| Stopping the temporary server | 11.1 |
| `database check` | 11.9 |
| Swap | 0.9 |
| **Whole run** | **116** |

Result: 108,333 nodes restored, 116,666 relationships restored, none left out; the rollback took under a second. The plan took
36 s and the dry run 47 s. The target is exported through Bolt, so a merge costs time in proportion to the whole target; larger
databases were not measured.

Limits: the Run is experimental until it is tried on a copy of the real database; "same node" compares labels and the
organisation property only; relationship ids change for the whole database; a relationship of a restored node is restored only
when its other end is on the target as the same node (or restored too); the store size depends on the highest node id; the
target's server must stay stopped; the local runner's swap needs `sh`, `mv`, `chown` and `stat -c`.

## Connected nodes without an organisation

Only the parent often carries the company (an invoice has `org_id`, its lines do not). The selection can now also take the nodes
that are directly connected to a selected node and have a missing or empty organisation property (`include_neighbours: true`;
on by default in the dashboard as a setting, off in the engine and the CLI). One step only; a node that carries another
company's value is never added; the label and id criteria do not apply to the added nodes, an excluded id stays excluded. The
expansion is a Cypher `UNION` in `SelectionQueryBuilder`, so the plan, the dry run, a split and a merge all use the same
selection, and the work happens in the database, not in the tool.

The dry run lists the relationships of moved nodes that are not moved, grouped by from-label, type, to-label and reason (another
organisation, no organisation and not added, not on the target, a different node on the target), with counts, the other
nodes' organisation values and examples. The plan counts the added nodes by label.

Tests: `NeighbourSelectionIT` (9 tests, real Neo4j): an invoice with lines that have no organisation, an empty one, another
company's value, a line shared by two companies' invoices, a product two steps away, and a shared company; checks the selected
ids for each case (with and without the option, with a label filter, with an exclusion), the counts by label, the report
groups with their reasons, that a shared company chosen together makes its relationship internal, that a null-organisation node
is "the same node" on a target when its labels match, and a split into a dump that holds exactly the expected nodes and
relationships. `SelectionQueryBuilderTest` covers the generated Cypher; `WebIT` the form, the setting, the plan and the report
page.

Limits: the lines are found only through a direct relationship from a node that was selected by the other criteria; a chain
(invoice, line, line detail) needs the detail selected by label or id. A node with no organisation that is shared by several
companies' invoices comes with each of them (a split copies it; a merge compares it by labels and organisation only).

`mvn clean verify` passes with these: 110 tests (33 engine and 11 dashboard unit tests; 39 engine, 5 CLI and 22 dashboard integration tests).
