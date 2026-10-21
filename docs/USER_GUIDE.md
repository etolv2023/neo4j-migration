# User guide

This guide covers the dashboard and the command line of version 0.1 (**read-only**: it plans and dry-runs, it does not yet
write any database). Section 9 lists exactly what is and is not available.

Contents: 1 What the tool does · 2 Install and start · 3 The flow · 4 Giving it a database · 5 Choosing what to move ·
6 Plan, dry run and reading the report · 7 Settings · 8 Logs and users · 9 What is not available yet · 10 Command line ·
11 Troubleshooting

## 1. What the tool does

You deleted data from a Neo4j database and you have a backup (a dump file, or another database that still has the data).
You want the missing nodes back **with their original `id(n)`**, or you want to split some organisations into a database
of their own, again with ids kept.

The tool answers, before anything is written: *which selected ids are missing on the target, which are already used by a
different node, and which relationships would be left pointing nowhere?* That answer is the **dry run**.

Neo4j 5 Community cannot write nodes with chosen ids into a running database. The only id-preserving way is to build a new
database from an export (`neo4j-admin database import full --id-type=actual`). Node ids are kept; **relationship ids are not**.
Your application does not use `id(r)`, so this is fine for you, but it is a fact of the store. It also means a merge rebuilds
the whole target database, so its time grows with the size of the database. The plan shows an estimate.

## 2. Install and start

You need Java 21 and a MySQL 8 database for the dashboard's own data (users, saved databases, plans, reports, logs). It holds
no graph data. To read dumps or stopped data directories you also need **either** Docker **or** a Neo4j installation on the
same machine (Settings, section 7).

    # 1. Build (runs all tests; add -DskipTests to skip them. The integration tests need Docker.)
    mvn clean verify

    # 2. MySQL for the dashboard (skip if you already have one)
    docker compose up -d mysql

    # 3. Start the dashboard
    export TOOL_DB_URL=jdbc:mysql://localhost:3306/neo4j_tool
    export TOOL_DB_USER=neo4j_tool TOOL_DB_PASSWORD=neo4j_tool_password
    export TOOL_SECRET='at least 16 random characters'     # encrypts saved Neo4j passwords; keep it
    export TOOL_ADMIN_USER=admin TOOL_ADMIN_PASSWORD='at least 8 characters'
    java -jar web/target/web-0.1.0-SNAPSHOT.jar

Open <http://localhost:8080>. It listens on `127.0.0.1` only. To reach it from another machine use an SSH tunnel or a
reverse proxy with TLS; the dashboard has no TLS of its own.

- `TOOL_ADMIN_*` creates the first user **only when there are no users yet**. Later you change the password in Settings.
- If you change `TOOL_SECRET`, saved Neo4j passwords become unreadable; enter them again.
- Run the dashboard **on the machine that has the dumps or data directories** (or can mount them). Paths you type are paths on
  that machine.

## 3. The flow

1. **Databases** — tell the tool where the backup and the live database are (section 4). Each is one of three kinds.
2. **New migration** — pick *From* (the backup), *To* (the live database, or nothing to split into a new database) and what to
   move (section 5).
3. **Plan** — counts what the selection contains and lists the steps and warnings.
4. **Dry run** — compares every selected id with the target and classifies the relationships. Result: CLEAR, WARN or BLOCKED.
5. **Run** — *not available in this version* (section 9).

The migration page shows these as a stepper with the log underneath. Nothing you do before Run changes a database.

## 4. Giving it a database

Every database you add is one of three kinds. In a migration, **From** can be any of them; **To** can be any of them as well.

| Kind | You give | What the tool does when it needs to read it |
|---|---|---|
| **Live server** | a Bolt address, for example `bolt://10.0.0.5:7687`, user and password | Connects and reads with read-only sessions. Nothing is copied. |
| **Dump file** | the path of a `.dump` file | Loads a copy into a **temporary Neo4j** in the work directory, reads from it, then deletes the copy. The dump is never changed. |
| **Stopped server's data directory** | the path of the `data` folder (the one that contains `databases/` and `transactions/`) | Takes a dump of it first, loads that dump into a temporary Neo4j, reads, then deletes both. Your data directory is not changed. |

### Making a dump

On the machine with the Neo4j server, **stop the database** (Community can only dump offline) and run:

    neo4j stop                                        # or systemctl stop neo4j / docker stop <container>
    neo4j-admin database dump neo4j --to-path=/backups
    neo4j start

This writes `/backups/neo4j.dump`. Use that path when you add a *Dump file*. Any file name is accepted.

### "I have a dump, and the live database is running on the server. What happens if I stop it?"

- **Live and running**: add it as *Live server*. You can plan and dry-run against it while it runs; the tool only reads.
- **Live and stopped**: add its `data` directory as *Stopped server's data directory*. The tool takes its own dump of it. The
  server must be fully stopped; if it is still running, the store lock fails and the job tells you so.
- You do not have to stop the live database for the dry run at all. You will have to stop it for the real run (section 9),
  because Neo4j Community can only replace a database that is offline.

### Details that matter

- **Version**: dumps are loaded by the Neo4j version in Settings (a Docker image such as `neo4j:5.26.29-community`, or the
  installation you point to). Use the version that wrote the dump. A dump from an older 5.x loads into a newer 5.x; the other
  direction fails.
- **Dumps from a server that was not shut down cleanly** may need recovery before they load. If loading fails, start that
  server once, stop it cleanly and dump again.
- **Disk**: loading needs room for a full copy of the database in the work directory (Settings).
- **Time**: a dump or stopped directory is loaded again every time a job needs it (the plan and the dry run each load it).
  For a big database that takes minutes. A live server needs no loading.
- **Database name**: Community has one user database, usually `neo4j`. If your data lives under another name, give that name.
- **Passwords** are encrypted in MySQL with AES-256-GCM and are only used to connect.
- **Delete** on the Databases page removes the saved entry only. Dumps and data directories on disk are never touched.

## 5. Choosing what to move

The *New migration* page has these fields. Leave a field empty to not filter on it.

| Field | Meaning |
|---|---|
| Organisation ids | nodes whose organisation property holds one of these values (one company or many) |
| Labels | nodes that have at least one of these labels |
| Node ids | these exact `id(n)` values |
| Id ranges | inclusive ranges written `5000-9000`, one per line or separated by commas |
| Never move these ids | exceptions; they win over everything else |
| Organisation property | the property that holds the company id (default from Settings, `org_id`) |

Rules, so that the result is never a surprise:

- Different fields are combined with **AND**. Values inside one field are combined with **OR**.
- *Node ids* and *Id ranges* count as one field (a node matches if it is in either).
- *Never move these ids* always wins.
- **No filter at all selects every node.** The plan and the report show a warning when that happens.
- Lists accept commas, spaces or new lines.

Example: organisations `12, 45`, label `Booking`, never `77` selects Booking nodes of those two companies, except node 77.

## 6. Plan, dry run and reading the report

**Plan** counts the selected nodes, shows both databases (version, nodes, relationships, indexes, constraints), estimates the
dry-run time, lists the steps of the real run and the warnings (different versions, no filter, same database, dump loading).

**Dry run** compares every selected node of the source with the target:

| Result | Meaning |
|---|---|
| **Missing** | the target has no node with this id. Restoring puts it back with its id. |
| **Same** | the target has a node with this id, with the same labels and organisation. Nothing to do. |
| **Id used by a different node** | the target has a node with this id, but it is another one. Restoring would overwrite it or lose the id. **This blocks.** |

It also shows runs of consecutive missing ids and examples of each case.

Relationships of the nodes that would be restored are classified:

| Kind | Meaning |
|---|---|
| Internal | both ends are in the selection and will be restored together |
| Resolved | the other end is already on the target |
| Conflict | the other end's id is used by a different node on the target |
| Dangling | the other end exists neither in the selection nor on the target. The id-preserving import does not check this and would create an inconsistent store, so it raises a warning. |
| Outside selection | only when there is no target (split): the other end is not selected |

The gate: **CLEAR** (nothing in the way), **WARN** (read the reasons), **BLOCKED** (a clash exists). A clash is not resolved
by the tool; someone has to decide which node keeps the id. In Neo4j the ids of deleted nodes can be handed to nodes created
later, so a clash on real data usually means exactly that: the report shows what the clashing nodes are.

"Same node" compares labels and the organisation property only. Other properties are not compared in this version.

Download the full report as JSON from its page.

## 7. Settings

| Setting | What it does |
|---|---|
| Run it with | **Docker** (an image) or **a Neo4j installation on this machine** — how dumps are loaded and served |
| Docker image | e.g. `neo4j:5.26.29-community`. Needs the `docker` command and permission to use it. |
| Neo4j installation directory | for the installation option, e.g. `/opt/neo4j`. Its own config and logs are not used or changed. |
| Work directory | where temporary databases are made; deleted when a job ends |
| Heap / page cache | memory of the temporary server (default 1g each); raise for big dumps |
| Wait up to (minutes) | how long a temporary server may take to start |
| Organisation property | default for new migrations |
| Ids per query | batch size of the dry run (min 100; default 10,000) |
| Examples per section | how many example rows the report keeps |
| Check relationships | switch off to make the dry run faster |

Settings are stored in MySQL and apply to the next job. You can also change your password on this page.

## 8. Logs and users

- **Logs → Jobs**: everything a plan or dry run printed, including the commands used to load dumps.
- **Logs → What people did**: who created, planned, dry-ran, changed settings, and so on.
- **Logs → Sign-ins**: every successful and failed login with the address it came from.

A job that fails goes back to its previous state with the reason shown on the migration page and in the log. If the dashboard
restarts while a job is running, that job is marked as interrupted.

## 9. What is not available yet

Be clear about this before relying on the tool:

- **Run is not available.** The button is disabled. Splitting (export, build a new database with ids kept, consistency check)
  and merging (mandatory backup, build, check, swap, rollback) are the next stages and will be delivered with their own tests.
  Until then nothing in this tool writes to a database.
- Validated on synthetic data (3 million nodes in the benchmark), not yet on a copy of your real database. Run a plan and a
  dry run on a copy and read the samples before trusting a number.
- Only Neo4j 5.26 Community was tested.
- One kind of user (admin); no roles.

## 10. Command line

The same dry run without the dashboard (live servers only):

    java -jar cli/target/neo4j-migration-cli.jar schema --uri bolt://host:7687 --user neo4j
    java -jar cli/target/neo4j-migration-cli.jar dry-run \
        --source-uri bolt://backup:7687 --target-uri bolt://live:7687 --selection selection.yaml --out report.json

Passwords come from environment variables (`NEO4J_PASSWORD`, `NEO4J_SOURCE_PASSWORD`, `NEO4J_TARGET_PASSWORD`), never from the
command line. Leave out `--target-*` for a split. Exit codes: `0` clear, `10` warnings, `20` blocked, `1` error.

Selection file:

    org_property: org_id
    org_ids: [12, 45]
    labels: [Booking]
    ids: [1001]
    id_ranges: [[5000, 9000]]
    exclude_ids: [77]

Unknown keys are rejected so that a typo cannot silently select everything.

## 11. Troubleshooting

| You see | Cause and fix |
|---|---|
| The dashboard refuses to start: "Set TOOL_SECRET…" | Set `TOOL_SECRET` (16+ characters) and `TOOL_ADMIN_PASSWORD` (8+). |
| "There is no file … on the machine the tool runs on" | The path is on another machine. Copy the dump there or run the dashboard where it is. |
| "has no databases/neo4j" | Give the `data` folder, not the folder above or below it; or give the right database name. |
| Planning fails with `docker: command not found` / permission denied | Install Docker for the user who runs the dashboard, or choose the installation runner in Settings. |
| Loading a dump fails with a version or format message | Set the Docker image / installation to the version that wrote the dump. |
| Stopped directory fails with a lock error | The server is still running. Stop it completely. |
| A `neo4j-migration-…` container is still running after the dashboard was killed | Jobs clean up after themselves when they end or fail. If the dashboard process was killed during a job, the temporary container stays: remove it with `docker rm -f <name>` and delete its folder under the work directory. |
| A temporary server does not start in time | Raise "Wait up to" and the memory in Settings; check free disk in the work directory. |
| Everything is selected | You left every filter empty. The warning says so. |
| Dry run is BLOCKED | Some selected ids hold different nodes on the target; open the report and look at the examples. |
