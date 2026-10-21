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
temporary container is left behind; Run (split and merge) is not implemented.
