# Neo4j Migration Tool

Restore or split data between Neo4j 5.x Community databases **keeping `id(n)`**. This version plans, dry-runs and can
**split** the selected data into a new database written as a dump file, and **merge** missing nodes back into a stopped
server's data directory or a dump, with a mandatory backup, a check, a swap and a rollback (both experimental until tried on a
copy of your data). Nothing is ever written to a running server. A dry run tells you which selected ids are missing
on the target, which are already used by a *different* node, and which relationships would be left pointing nowhere.

**Start here: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)** — install, the flow, how to give it a dump, a live server or a
stopped server's data directory, how to choose a company, ids or labels, and how to read the report.

Flow in the dashboard: add databases → new migration (From, To, what to move) → Plan → Dry run → Run (a split writes a dump file; a merge backs up the stopped target, builds and checks a new database and swaps it in, with a rollback).
From and To are each a **dump file**, a **live running server** or the **data directory of a stopped server**.

## Modules

| Module | What it is |
|---|---|
| `engine` | Schema reader, selection parser and Cypher builder, dry run, the split and merge (CSV export, id-preserving import, comparison), and the runners that serve a dump or a stopped data directory through a throw-away Neo4j (Docker or a local installation). Reads over Bolt in READ-mode sessions only. |
| `cli` | `neo4j-migration-cli.jar` — `schema` and `dry-run` commands for live servers. |
| `web` | Spring Boot dashboard: login, databases, migrations (plan, dry run, run a split or a merge, roll back), reports, settings, logs. MySQL + Flyway. |

Requires Java 21, Maven 3.9+, and Docker for the integration tests (Testcontainers).

## Build and test

    mvn clean verify          # unit tests, then integration tests against real Neo4j 5.26.29 and MySQL 8.4 containers

Integration tests pull `neo4j:5.26.29-community` and `mysql:8.4`. Use `-Dneo4j.image=...` to test another server version.

## Selection file

    org_property: org_id        # property that identifies the organisation (default org_id)
    org_ids: [12, 45]           # nodes of these organisations
    labels: [Booking, Invoice]  # nodes that have at least one of these labels
    ids: [1001, 1002]           # explicit node ids
    id_ranges: [[5000, 9000]]   # inclusive id ranges
    exclude_ids: [77]           # never selected

Different criteria are combined with AND, values inside one criterion with OR, `ids` and `id_ranges` form one
criterion, and `exclude_ids` always wins. Unknown keys are rejected so a typo cannot silently select everything.
An empty selection selects every node and the report says so.

## CLI

    java -jar cli/target/neo4j-migration-cli.jar schema --uri bolt://host:7687 --user neo4j
    java -jar cli/target/neo4j-migration-cli.jar dry-run \
        --source-uri bolt://backup-host:7687 --target-uri bolt://live-host:7687 \
        --selection selection.yaml --out report.json

Passwords are read from environment variables (`NEO4J_PASSWORD`, `NEO4J_SOURCE_PASSWORD`, `NEO4J_TARGET_PASSWORD`,
or the name given with `--password-env`), never from the command line. Leave out `--target-*` to analyse a split into
a new database. Exit codes: `0` clear, `10` warnings, `20` blocked, `1` error.

## Dashboard

    docker compose up -d mysql
    export TOOL_DB_URL=jdbc:mysql://localhost:3306/neo4j_tool TOOL_DB_USER=neo4j_tool TOOL_DB_PASSWORD=neo4j_tool_password
    export TOOL_SECRET=<16+ characters>        # encrypts stored Neo4j passwords (AES-256-GCM)
    export TOOL_ADMIN_USER=admin TOOL_ADMIN_PASSWORD=<8+ characters>   # creates the first user
    java -jar web/target/web-0.1.0-SNAPSHOT.jar

Open http://localhost:8080 (it listens on `127.0.0.1` only). Details, settings and troubleshooting are in the
[user guide](docs/USER_GUIDE.md). MySQL holds metadata, plans, reports and logs; no graph data is copied into it.

## Reading the result

- **missing** — selected in the source, no node with that id on the target. These can be restored with their id.
- **same** — the target already holds the same node (same labels and organisation).
- **clash** — the id is used by a different node on the target. The dry run treats this as BLOCKED: restoring would
  either overwrite live data or give the restored node another id.
- Relationships are classified for the nodes that would be restored: *internal*, *resolved*, *conflict*, *dangling*
  (the other end does not exist on the target) and, without a target, *outside selection*. Dangling relationships are
  a warning because the id-preserving import does not check them and would create an inconsistent store.

## Comparison rule

Two nodes are "the same" when their label sets and the organisation property are equal. Other properties are not
compared in this version.
