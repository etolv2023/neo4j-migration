# User guide

This guide covers the dashboard and the command line of this version. **Split** (copy some organisations, ids or labels into a
new database, written as a dump file) is available and marked *experimental* until you have tried it on a copy of your real
data. **Merge** (put lost nodes back into the data directory of a stopped server, or into a dump) is available too, with a
mandatory backup, a check and a rollback, and is also *experimental*. Section 9 lists exactly what is and is not available.

Contents: 1 What the tool does · 2 Install and start · 3 The flow · 4 Giving it a database · 5 Choosing what to move ·
6 Plan, dry run and reading the report (6a split, 6b merge) · 7 Settings · 8 Logs and users · 9 What is not available yet · 10 Command line ·
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
- **Fast reader.** `mvn clean verify` also builds the reader's jars in `reader/target/lib`. The dashboard finds them when you start
  it from the folder that holds `reader/` (or a folder `reader-lib/`), or you set the directory in Settings (section 7). Without
  them the tool still works, only slower: it reads dumps and stopped data directories through a temporary server.
- Run the dashboard **on the machine that has the dumps or data directories** (or can mount them). Paths you type are paths on
  that machine.

### Install on a server without Docker

`deploy/neo4j-migration.sh` builds, runs and updates the dashboard with nothing but Java 21, Maven, git and MySQL. In
Settings choose **A Neo4j installation on this machine** (the folder that has `bin/neo4j-admin`) so that dumps are loaded
without Docker.

    deploy/neo4j-migration.sh init       # folders under /opt/neo4j-migration, an empty settings file, checks what is missing
    sudoedit /etc/neo4j-migration/env    # MySQL, TOOL_SECRET, first admin (the file is mode 600; the script never prints it)
    deploy/neo4j-migration.sh build      # tests skipped; add --with-tests to run them
    deploy/neo4j-migration.sh start      # then open http://127.0.0.1:8080
    deploy/neo4j-migration.sh status | stop | restart | logs

- **Update:** `deploy/neo4j-migration.sh update` does `git pull`, builds a new release and restarts. If the build fails the
  running dashboard is not touched. A migration that is running when it restarts is stopped and shown as failed.
- **Roll back:** `deploy/neo4j-migration.sh rollback` runs the release before the current one and keeps it until the next
  build. The last 5 releases are kept in `/opt/neo4j-migration/releases/`; each has the dashboard jar and the fast reader's jars
  (`reader-lib`), so nothing depends on the checkout afterwards.
- **Where things are:** log `/opt/neo4j-migration/logs/dashboard.log`, settings `/etc/neo4j-migration/env`. Override with
  `NM_HOME`, `NM_ENV`, `NM_REPO`, `NM_BRANCH`; `JAVA_OPTS` (default `-Xmx2g`) and `SERVER_PORT` go in the settings file.
- **User:** run it as a user that can write to the Neo4j data folders you read in place and to the work and output folders. The
  script does not need root except to create `/etc/neo4j-migration` the first time.
- It starts the dashboard in the background and does not restart it after a reboot; if you want that, run `start` from your
  system's boot mechanism.

### Putting it behind Apache (HTTPS and a first password)

The dashboard listens on 127.0.0.1 only. To reach it from outside, let Apache terminate HTTPS and pass requests on. With
`mod_proxy`, `mod_proxy_http`, `mod_headers` and `mod_ssl` enabled (`a2enmod proxy proxy_http headers ssl`), and a certificate from
`certbot --apache -d dash.example.com`, the HTTPS virtual host holds:

    <VirtualHost *:443>
        ServerName dash.example.com
        # ... the SSLCertificate lines certbot added ...

        ProxyPreserveHost On
        RequestHeader set X-Forwarded-Proto expr=%{REQUEST_SCHEME}
        ProxyPass / http://127.0.0.1:8085/
        ProxyPassReverse / http://127.0.0.1:8085/

        # An extra password before the dashboard's own login page (optional, recommended)
        <Location />
            AuthType Basic
            AuthName "Neo4j Migration"
            AuthUserFile /etc/apache2/.htpasswd-dash
            Require valid-user
            # do not pass the proxy password on: the dashboard has its own login
            RequestHeader unset Authorization
        </Location>
    </VirtualHost>

- Create the password file with `htpasswd -c /etc/apache2/.htpasswd-dash someone` (`-c` only the first time), then `apache2ctl configtest && systemctl reload apache2`.
- Keep the `<Location>` block **inside** the `<VirtualHost *:443>`. Outside it, it applies to every site on the machine.
- Put these in the dashboard's settings file `/etc/neo4j-migration/env` and restart, so that links and the secure cookie are right behind the proxy:
  `SERVER_PORT='8085'` (any free port; Jenkins uses 8080), `SERVER_FORWARD_HEADERS_STRATEGY='framework'`, `SERVER_SERVLET_SESSION_COOKIE_SECURE='true'`.
- Check from outside: `curl -sI https://dash.example.com/login` answers `401` without the proxy password.

## 3. The flow

1. **Databases** — tell the tool where the backup and the live database are (section 4). Each is one of three kinds.
2. **New migration** — pick *From* (the backup), *To* (the live database, or nothing to split into a new database) and what to
   move (section 5).
3. **Plan** — counts what the selection contains and lists the steps and warnings.
4. **Dry run** — compares every selected id with the target and classifies the relationships. Result: CLEAR, WARN or BLOCKED.
5. **Run** — for a split (no *To*): builds the new database and writes it as a dump file (section 6a). For a merge: backs up the
   target, builds the merged database, checks it and swaps it in, with a rollback (section 6b). The target must not be running.

The migration page shows these as a stepper with the log underneath. Plan and dry run change nothing. A run reads the source and
writes only new files in the work, output and backup directories, and (for a merge you chose to swap in) the data directory of the
stopped target; it never writes to the source or to a running server.

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
| Also move nodes without an organisation that are connected to a selected node | see "Nodes that carry no company" below (on by default; a setting) |

Rules, so that the result is never a surprise:

- Different fields are combined with **AND**. Values inside one field are combined with **OR**.
- *Node ids* and *Id ranges* count as one field (a node matches if it is in either).
- *Never move these ids* always wins.
- **No filter at all selects every node.** The plan and the report show a warning when that happens.
- Lists accept commas, spaces or new lines. In the dashboard each value becomes a **tag** inside the box: type it and press
  Enter, a comma or space (a range such as `5000 - 9000` is kept together), paste a whole list at once, click a tag to edit it,
  click its × (or press Backspace in the empty box) to remove it, or *clear all*. A value that cannot work in that field
  (letters in *Node ids*) turns red and the form is not sent until it is fixed. Without JavaScript the box is a plain text field.

Example: organisations `12, 45`, label `Booking`, never `77` selects Booking nodes of those two companies, except node 77.

### Nodes that carry no company

Often only the parent holds the company id: an invoice has `org_id`, its lines do not. A selection by company alone would move
the invoice and leave its lines behind. With the box **Also move nodes that have no organisation but are connected to a selected
node** ticked (default on; change the default in Settings), the tool adds, after the normal selection:

- every node that is **directly connected** (either direction, any relationship type) to a selected node, and
- whose organisation property is **missing or empty**.

Only this first step is followed, and a connected node that carries **another company's value is never added**. A node two steps
away (the product a line points to) is not added either, because nodes like that are often shared by every company; if it is
needed, select it by label or id. The label, id and range fields do not apply to the added nodes, but *Never move these ids*
does. A shared company (countries, cities) is moved together with yours by listing both ids in *Organisation ids*; the
relationships between them are then moved like any others.

The plan says how many nodes were added and under which labels. In the dry run, the section **Relationships that are not moved**
lists every relationship of a moved node that leads to a node that is not moved, grouped by *from label, relationship type, to
label* and the reason: the other node belongs to another organisation (which one), it has no organisation and was not added, it
does not exist on the target, or its id holds a different node on the target. Each group has a count and a few examples.
Look at this list before you run.

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

## 6a. Running a split

A split needs a finished dry run that is not BLOCKED. Press **Run the split** on the migration page. The tool then:

1. reads the ids of the selected nodes from the source (read only);
2. exports the selected nodes and the relationships *between* them as CSV files, with exact value types (numbers, text,
   dates, durations, points, lists, bytes);
3. builds a new database with `neo4j-admin database import full --id-type=actual`, so **node ids are the same**;
4. starts a throw-away server on it to write what the import cannot carry (empty lists, lists with an empty text in them, and dates with a named time zone),
   and to create the same indexes and constraints as the source;
5. reads the new database back and compares it with the export: the set of ids, the counts, and a hash over every node
   (id, labels, properties with their types) and every relationship (ends, type, properties). Any difference fails the run;
6. optionally runs `neo4j-admin database check` on the new store;
7. writes the database as a dump file in the **output directory** (settings). An existing file is never overwritten; the name is
   `<migration name>-<id>-<date-time>.dump`. The temporary files and servers are removed whether it succeeds or fails.

Load the dump into a Neo4j of the same version with `neo4j-admin database load`. The result card shows the file, its size,
how many nodes and relationships were copied and how many relationships were left out because they lead to a node that is not
selected.

Things to know:

- **Relationship ids change** (node ids do not). Relationships between selected nodes are copied; relationships to nodes outside
  the selection are not, and the dry run warns about them.
- The size of the new store depends on the **highest node id** selected, not on how many nodes there are: the node store has a
  slot for every id up to the highest one. Splitting a few nodes whose ids are near the top of a huge database makes a large
  store full of unused slots. This is how Neo4j stores ids; it is not a bug of the tool.
- The comparison covers what the export carries. A relationship property that the CSV import cannot carry (an empty list or a
  named-zone date on a relationship) stops the run with a clear message instead of copying it wrongly.
- Property names with `:` or parentheses, and list values that contain the character U+001F, are not supported and stop the run.
- Memory: the ids of the selection are held in memory (8 bytes each).
- The run is **experimental**. It has been tested against Neo4j 5.26.29 on synthetic data with awkward values (see
  `docs/RESULTS.md`); try it on a copy of your real database first and compare the result yourself.

## 6b. Running a merge (restoring into an existing database)

A merge puts the selected nodes that are **missing** on the target back, with their ids, and keeps everything that is on the
target. It needs a finished dry run that is not BLOCKED, and a target that is **not running**: a *stopped server's data
directory* (the result is swapped in) or a *dump* (the result is a new dump). A running server cannot be merged into, because
Neo4j Community cannot write nodes with chosen ids to a running database. Stop the server, add its data directory as a database
and make the migration to that.

What the Run does:

1. **Backup (mandatory).** A stopped data directory is dumped into the **backup directory** (settings) as
   `<database>-before-merge-<date-time>.dump`. A dump target is used as it is. The backup is never deleted by the tool.
2. Reads the target from the backup (the files of the stopped server are not touched).
3. **Sorts the selected ids.** An id on the target holding the same node (same labels, same organisation) is left alone. An id
   that is free is restored. An id used by a *different* node is a **clash**: unless you switched on *give a node whose id is
   taken a new id* (next section), the run stops before building anything and tells you the first id. The tool never decides
   which node wins; with the option on, neither node is lost: the target keeps its node and the restored one gets a new id.
4. Exports the whole target and the restored nodes as CSV, builds a **new** database from both with
   `neo4j-admin database import full --id-type=actual`, writes back what the import cannot carry, creates the target's indexes
   and constraints, and compares the new database with the exports (ids, counts and a hash over every node and relationship).
5. Optionally runs `neo4j-admin database check`.
6. **Swap** (setting "Swap the result into the data directory", on by default). The old store is moved to
   `<data directory>/before-<date-time>/` and the new one takes its place, with the same owner. If the swap fails midway it puts
   the old store back. If the swap setting is off, or the target is a dump, the result is written as a dump in the output
   directory and nothing existing is changed.

After a swap the migration page shows the backup file, the kept folder and a **Roll back** button. Rolling back sets the new
store aside in `rolled-back-before-<date-time>/` and moves the old one back. Start the server only after you have looked at the
result; stop it before rolling back. A merge that was swapped in cannot be run again until it is rolled back.

### When an id is taken: restore under a new id and follow it

Neo4j hands the ids of deleted nodes to new nodes, so some ids of the company's lost nodes may now belong to other nodes on
the target. With *When an id is taken on the target → give a node whose id is taken a new id* switched on (per migration, with
the default in Settings):

- **Dry run.** The report gets a **New ids** section: the table *old id → new id* (with what the source and the target hold at
  the old id; **CSV and JSON downloads with every row**, while the page keeps the first 20,000 and shows the first 200), and the
  properties that hold an old id, found in
  1. the nodes that would be restored (including the renumbered ones),
  2. the company's nodes already on the target that the source also had,
  3. the company's nodes that are on the target but **not** in the source (made after the backup). They are listed apart and
     skipped, because their number most likely means the node that has that id *now*. Tick *also change company nodes made
     after the backup* to change them too.

  Each result is grouped by labels · property · kind of value (whole number, text, list), with the count and a few examples
  (`node: before → after`). Every property name has a **Leave alone** box: tick a property (a quantity, a phone number) that
  only looks like an id and save; the merge will not touch it in any node. The gate is WARN, not BLOCKED, while the option is on.
- **Outside the company (warning only).** With *In the dry run, also look for the old ids outside the company* (on by default,
  also a setting) the dry run reads every other node of the target once more and lists, grouped the same way, the properties
  there whose value is an old id. They are **never changed** (those nodes are not the company's), but a number there may mean
  a node that now has a new id, so the gate says so and you can check before running. It costs one more pass over the target,
  a few seconds for a million nodes. The property that holds the organisation is not looked at.
- **What counts as holding an old id.** The value *is* the id: a whole number equal to it, or a text of exactly its digits
  (no sign, spaces, leading zero or decimals). `122`, `"012"`, `12.0` and `"12 "` are not `12`. A whole number stays a whole
  number and a text stays a text; in a list (if lists are on) each matching element is replaced. Every value is looked up once,
  so `10→12` and `12→14` never chain. The property that holds the organisation is never changed. Only nodes of the selected
  organisation(s) are changed; other companies' nodes are never touched, even if a property in them holds the same number.
- **New ids.** *Above the highest id* (default): from the highest id of the source and the target plus one, so an id that ever
  belonged to a node is never reused. *Fill the free ids*: the smallest ids the new database does not use (smaller numbers, but
  an id of a deleted node is used again and a later restore of that node would clash).
- **Run.** The replacement is done while the CSV is written, not by a later update, so it costs almost nothing extra; the log
  says *Rewrote N references in M nodes* and the stepper shows the step. The fingerprints that check the new database are
  computed on the new ids and the new values, so the check proves the replaced data is what is in the database. An
  `<output>.id-mapping.csv` (or `<database>-id-mapping-<time>.csv` in the backup directory for a swap) lists old id, new id,
  labels and organisation of every renumbered node.

What this does **not** do: it does not look inside relationship properties; it does not change nodes of other companies or
nodes without the organisation property (it only lists them, above), and it cannot know whether a number in a property really was an id (that is why the
dry run lists them and lets you leave properties alone); a company node made after the backup is judged only by its id being
absent from the source.

Things to know:

- **Relationship ids change for the whole database**, because the target is rebuilt. Node ids do not change. If anything uses
  `id(r)`, do not merge.
- A relationship of a restored node is restored only when its other end is on the target as the *same* node (or is restored
  too). Relationships to nodes that are not on the target, or are a different node there, are left out and counted.
- The target is read through Bolt, so a merge into a big database takes time in proportion to the **whole target**, not only
  the restored nodes. Times for large databases have not been measured beyond the benchmark in `docs/RESULTS.md`.
- The work directory needs room for the backup copy, the CSV files, the new database and its dump.
- Swapping with a local Neo4j installation runs `sh`, `mv`, `chown` and `stat -c` on this machine (Linux). If the dashboard is
  not run as root it may not be able to give the files the old owner; the log then prints the `chown` command to run.
- The run is **experimental**: try it on a copy of your real data directory first.

## 7. Settings

| Setting | What it does |
|---|---|
| Run it with | **Docker** (an image) or **a Neo4j installation on this machine** — how dumps are loaded and served |
| Docker image | e.g. `neo4j:5.26.29-community`. Needs the `docker` command and permission to use it. |
| Neo4j installation directory | for the installation option, e.g. `/opt/neo4j`. Its own config and logs are not used or changed. |
| Work directory | where temporary databases are made; deleted when a job ends |
| Output directory | where the dump files of a split (or of a merge that is not swapped in) are written |
| Backup directory | where the mandatory backup of a merge target is kept; the tool never deletes it |
| Swap a merge into the data directory | on by default; off writes the merged database as a dump instead of changing the data directory |
| Create the same indexes and constraints | on by default; switch off to build the new database without them |
| A split leaves a data directory | off by default; on leaves `databases/` and `transactions/` instead of a dump, which saves writing the whole store and loading it again |
| Check the new database | on by default; runs `neo4j-admin database check` before dumping. It reads every record: **hours on billions of nodes**. Switch it off for a big database; the comparison with the source (counts, ids, fingerprints) always runs. |
| Heap / page cache | memory of the temporary server (default 1g each); raise for big dumps |
| Wait up to (minutes) | how long a temporary server may take to start |
| Fast reader | on by default; reads a dump (after loading it into a folder) or a stopped data directory with the Neo4j kernel on all threads, about 5 to 10 times faster than through a temporary server. Falls back by itself, and says why in the job log. A running server is always read over Bolt. |
| Fast reader jars / heap / page cache | where `reader/target/lib` is (empty = next to the application); memory of the reader process. The more of the store the page cache holds, the faster the scan. |
| Reading threads | threads that read the source and write CSV at once (default: the number of cores) |
| Import threads / memory | `neo4j-admin import` settings. Threads: 0 = one per core. Memory: empty = sized from this server: a heap of an eighth of the machine (1 to 8 GB), a quarter of the machine (at least 3 GB) kept for the system and the dashboard, and the rest as off-heap memory, never more than the system reports as available; the job log shows the numbers it used. Type a value such as `8g` to set the off-heap memory yourself. |
| Shared layout width | A node export writes one CSV layout (a header file and its parts) per distinct set of properties. `neo4j-admin import` opens each layout on its own and takes about a third of a second for every one before it reads a row: a database with ten thousand layouts waits more than half an hour. With this setting, label sets whose properties do not contradict each other (no property has two types) share a layout whose header holds all their properties, up to this many columns; a row leaves the cells of the properties it does not have empty, which is no property at all. A label set in which one property has several types is written to one layout per combination of types; the export reads the nodes of such label sets once more first (`Export, pass 1b`) to learn the combinations, and each combination then shares a layout like any other label set. A label set with more than 20,000 combinations keeps a layout per combination. The log says how many layouts there are (`Export: sharing node layouts ...`). 0 = one layout per set of properties, as before. Default 100. |
| Memory for the selected ids | past this many MB the ids go to a file on disk (default 512) |
| Migrations at the same time | default 2; each one needs its own disk space and memory |
| Organisation property | default for new migrations |
| Also move connected nodes without an organisation | default for the box of the same name on new migrations; each migration can change it |
| Ids per query | batch size of the dry run (min 100; default 10,000) |
| Examples per section | how many example rows the report keeps |
| Check relationships | switch off to make the dry run faster |
| Live refresh (seconds) | how often a running job and the resource bar update themselves (default 2, 1 to 60) |
| Stay signed in for (hours) | how long you can leave the dashboard alone before signing in again (default 12, 1 to 720); counts from your last click and applies to the next sign-in |
| Require two-factor sign-in for every user | off by default; when on, a user who has not set it up is sent to do so right after the password. You can only turn it on once your own account has two-factor. |

The speed settings change how fast the work goes, never what comes out: the tests compare the files, counts and fingerprints for
different numbers of threads. Reading a stopped data directory in place does not change it (the reader copies the few files Neo4j
rewrites on opening, and refuses a store that was not shut down cleanly); the user of the dashboard needs write permission on the
record files because Neo4j opens them for writing. Details and measurements: `docs/RESULTS.md`, Stage D.

Settings are stored in MySQL and apply to the next job. You can also change your password and turn two-factor sign-in on or off for your own account on this page (see section 8).

### While a job runs

Every button that starts something shows a spinner and cannot be pressed twice. Links and forms that take long (Schema, Check, Reload) put a "please wait" screen over the page.
A migration that is working shows a progress card at the top: the step it is in (`Step 6 of 11`), a bar with a percentage when the step has a count, a moving bar when it has none, plus elapsed time, speed and the time left. While `neo4j-admin import` runs, the bar follows its own parts (`part 1 of 4: Node import 50%`).

Under Plan, Dry run and Run the steps are written out: a green square is done (with how long it took), a blue one is running, an empty one is still to come, a red one is where a job stopped. Run shows the steps of a merge or a split in the order they happen, and the extra detail of the running step (for example the pass of an export) under its name.

The page updates itself without reloading: the server pushes news every few seconds (Settings → Live refresh), and a connection that cannot carry that (some proxies hold it back) is asked instead. The bar at the top of every page shows the RAM in use, swap, the free space of the disk of the work folder, the processor, and what the processes started by the dashboard hold; it turns amber and then red when the memory or the disk is nearly used up. The Log shows the latest run only (Logs → Jobs has all of them): long lists such as the thousands of CSV files are one line, the import's progress is a line per ten percent, errors are red, warnings amber and the start of each step blue; **Important only** hides the rest.
The log under it updates by itself every two seconds; when the job ends the page reloads to show the result.
**Copy log** (on a migration, on Logs → Jobs and on Logs → What people did) puts the text on your clipboard, one line per row.

## 8. Logs and users

- **Logs → Jobs**: everything a plan or dry run printed, including the commands used to load dumps.
- **Logs → What people did**: who created, planned, dry-ran, changed settings, and so on.
- **Logs → Sign-ins**: every successful and failed login with the address it came from, and the steps of two-factor sign-in
  (`password_ok`, `two_factor_failed`, `two_factor_locked`, `recovery_code_used`).

### Users

**Users** in the menu lists everyone who can sign in. Every user can do everything; there are no other roles.

- **Add a user:** a name (3 to 100 letters, digits, `.`, `-`, `_`, `@`) and a password of at least 8 characters. They can change it in Settings.
- **Switch off / on:** a switched-off user cannot sign in, and a session they have open ends on its next click. Nothing of theirs is deleted.
- **Set password:** you give another user a new password (you change your own in Settings).
- **Delete:** removes the user and their recovery codes. Their entries in the logs stay.
- You cannot switch off or delete the account you are using, so there is always someone who can sign in.

Users and their password hashes live in the MySQL table `users`. Passwords are never stored in a readable form.

### Two-factor sign-in (authenticator app)

The second step is a 6-digit code that an authenticator app computes (Google Authenticator, Authy, Microsoft Authenticator, 1Password
and others). It follows the standard (RFC 6238); nothing is sent to any outside service.

- **Turn it on:** Settings → *Two-factor sign-in* → *Set up*. Scan the QR code (or type the key into the app), then enter the code the app
  shows. The tool then shows **8 recovery codes, once**. Save them away from the phone.
- **Signing in:** user and password first, then the code. A code works once; wait for the next one if you typed it twice.
- **Lost phone:** type a recovery code instead of the 6-digit code. Each recovery code works one time.
- **Lost phone and recovery codes:** another user opens *Users* and presses *Turn off 2FA* next to your name; you sign in with your
  password and set it up again.
- **Turn it off for yourself:** Settings, with your password (not possible while two-factor is required for everyone).
- **Wrong codes:** 5 wrong codes within 5 minutes lock the second step of that user for the rest of the 5 minutes, even for a right
  code. Every wrong code, lock and recovery code used is in *Logs → Sign-ins*.
- **The clock:** the code depends on the time. The server must keep its clock right (`timedatectl` should say "System clock synchronized: yes"); the
  tool accepts the code before and after the current one to forgive a few seconds of difference on the phone.
- The key is stored in MySQL encrypted with `TOOL_SECRET`; recovery codes are stored as hashes. If you change `TOOL_SECRET`, two-factor
  keys become unreadable: turn two-factor off for those users (below) and set it up again.

**If nobody can sign in** (the only user lost the phone and the recovery codes), turn two-factor off directly in MySQL, then sign in with the password and set it up again:

    mysql neo4j_tool -e "UPDATE users SET totp_enabled = FALSE, totp_secret_enc = NULL, totp_last_step = 0 WHERE username = 'admin'; DELETE FROM recovery_codes WHERE user_id = (SELECT id FROM users WHERE username = 'admin');"

If you also forgot the password, make a new hash with `htpasswd -bnBC 10 "" 'the new password' | tr -d ':\n'` (it starts with `$2y$`, which the tool accepts) and put it in `users.password_hash`.

A job that fails goes back to its previous state with the reason shown on the migration page and in the log. If the dashboard
restarts while a job is running, that job is marked as interrupted.

## 9. What is not available yet

Be clear about this before relying on the tool:

- **Merging into a running server.** Not possible with Neo4j Community and ids kept; stop the server and merge into its data
  directory or a dump.
- **Resolving a clash.** An id used by a different node stops the run unless renumbering is on (then the restored node gets a
  new id; see section 6b). It never overwrites a node on the target. Relationship properties are not rewritten.
- **Split and merge are experimental.** They are covered by integration tests that compare every node and relationship, but
  they have not been tried on a copy of your real database.
- Validated on synthetic data (3 million nodes in the benchmark for the dry run), not yet on a copy of your real database.
  Run a plan and a dry run on a copy and read the samples before trusting a number.
- **Very large sources.** Keeping the ids means the new store has a slot for every id up to the highest selected one (about 16
  bytes each): with ids near 100 billion that is about 1.6 TB even for 1 billion nodes, and the import time grows with it. A
  merge rebuilds the whole target. The speed numbers in `docs/RESULTS.md` are from a 2-CPU test machine; the figures for your
  size are extrapolations until they are measured on your hardware.
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

A run is not limited by how many different shapes of data it has. Rows are grouped into CSV layouts by their columns (the labels travel in a column of every row), so thousands of label sets that carry the same properties share one layout; the same property stored as a number on some nodes and as text on others adds a layout.
The writer keeps at most 128 files open per worker, writes one header file per layout and one headerless part file per worker, and the import is given one line per layout in an arguments file (`import.args`, next to the CSV files), so the command stays short however many layouts there are.
The job log says how many layouts were written ("Export: N node layouts and M relationship layouts")."

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
| "That code is wrong or already used" although the code looks right | The clocks differ (check `timedatectl` on the server and the phone's automatic time), or that code was already used: wait for the next one. |
| Everything is selected | You left every filter empty. The warning says so. |
| Dry run is BLOCKED | Some selected ids hold different nodes on the target; open the report and look at the examples. |
