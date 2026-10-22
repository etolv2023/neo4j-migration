package com.etolv.neo4jtool.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A failed job, explained: where it stopped, what happened, the likely cause, what to do, and whether the data was touched.
 * The explanation comes from what the program reported (its message), the stage of the job and the step it was in; a message
 * this table does not know gets a general explanation that still says where it stopped and keeps the original text whole.
 *
 * @param stage     Plan, Dry run, Run or Rollback
 * @param step      the step it was in, as the log names it
 * @param safe      true only when it is certain that nothing of the user's databases was changed
 * @param technical the message exactly as the program gave it, with its causes
 */
public record Failure(String stage, String step, String title, String what, String cause, String todo, String data, boolean safe,
                      String technical) {

    public enum Stage {
        PLAN("Plan"), DRY_RUN("Dry run"), RUN("Run"), ROLLBACK("Rollback");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        /** The stage a run of the log began with, from the line that marks its start. */
        static Stage of(String marker) {
            if (marker == null) {
                return RUN;
            }
            return switch (marker) {
                case "Planning started" -> PLAN;
                case "Dry run started" -> DRY_RUN;
                case "Rollback started" -> ROLLBACK;
                default -> RUN;
            };
        }
    }

    /** What one known message means. {@code {1}} in a text is replaced by the first group of the pattern, and so on. */
    private record Rule(Pattern pattern, String title, String what, String cause, String todo, String data) {

        Rule(String regex, String title, String what, String cause, String todo) {
            this(regex, title, what, cause, todo, null);
        }

        Rule(String regex, String title, String what, String cause, String todo, String data) {
            this(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL), title, what, cause, todo, data);
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("Argument list too long",
                    "The import command was too long for Linux",
                    "neo4j-admin was started with more file names than the operating system allows on one command line.",
                    "The export wrote a very large number of CSV layouts (one for every different set of columns).",
                    "Update the dashboard to the latest version: it passes the files in an arguments file, so there is no limit. "
                            + "If this still happens, send the log; the line \"Export: N node layouts\" says how many there are."),
            new Rule("No space left on device|not enough space|disk full",
                    "The disk is full",
                    "A file could not be written because there is no space left.",
                    "The work folder, the backup folder or the output folder is on a disk that is full. A merge needs room for the "
                            + "CSV files, the new database and a dump: about two to three times the size of the target.",
                    "Free some space, or point Work directory, Backup directory or Output directory in Settings at a bigger disk, "
                            + "and run again. The bar at the top of the page shows the free space of the work folder."),
            new Rule("OutOfMemoryError|Java heap space|GC overhead|unable to create (native )?thread",
                    "A program ran out of memory",
                    "A Java process (the import, the fast reader or the dashboard itself) used all the memory it was given.",
                    "The memory limit is too low for this much data, or other programs on the machine are using the memory.",
                    "In Settings, leave Import memory empty (it is sized from the machine) or give it more; give the fast reader a "
                            + "bigger heap; close what you do not need. The RAM bar at the top shows what is in use."),
            new Rule("exit code 137|\\bKilled\\b|exit code 9\\b",
                    "The operating system stopped the program",
                    "neo4j-admin (or another program the tool started) was killed from outside, with no message of its own.",
                    "Linux kills a process when the machine runs out of memory. Look at RAM and Swap in the bar at the top: "
                            + "if they were nearly full, this is it.",
                    "Lower Import memory in Settings (for example 4g), free memory, or add swap, then run again."),
            new Rule("differ from the ones that were exported|new database has \\d+ (nodes|relationships) (but|of)|"
                    + "has \\d+ of the \\d+ exported|relationships between these nodes",
                    "The new database does not match what was exported, so it was thrown away",
                    "After the import the tool read the new database back and compared it with the exported data. Something was "
                            + "different, so it discarded the result instead of giving you data that may be wrong.",
                    "A value the CSV import cannot carry exactly, or the source being written to while it was exported.",
                    "Nothing was lost: run again after the next update, or send the technical details below (they say which "
                            + "kind of difference it is). If anyone writes to the source database, use a stopped copy or a dump of "
                            + "it as the source."),
            new Rule("changed after the backup",
                    "The target changed after its backup was taken",
                    "The files of the target database were different when the tool was about to swap the new database in, so it did not.",
                    "The server of the target is running, or something else wrote to its data directory during the run.",
                    "Stop the target's server, make sure nothing else uses its files, and run again. The new database of this run "
                            + "was discarded.",
                    "Nothing was swapped. The data directory is as it was."),
            new Rule("(\\d+) selected ids are used by a different node",
                    "Some ids are used by another node on the target",
                    "{1} of the selected ids already belong to a different node on the target, so restoring them would lose one "
                            + "of the two nodes.",
                    "The target got new nodes after the backup that took over ids of nodes that were deleted.",
                    "Open the dry run report: it lists the ids. Exclude them from the selection, or choose another target. "
                            + "Nothing was changed."),
            new Rule("Nothing to restore",
                    "There is nothing to restore",
                    "Either the selection matches no node in the source, or all the selected nodes are already on the target.",
                    "The organisation id, the labels or the ids in the selection do not match anything missing.",
                    "Check the selection (the organisation property and ids, the labels). The dry run says how many are missing."),
            new Rule("gone from the source|source changed while it was being exported",
                    "The source changed while it was being read",
                    "Nodes or properties of the source were changed or deleted during the export.",
                    "The source is a running database that people or programs write to.",
                    "Use a stopped data directory or a dump as the source, so that it cannot change during the run."),
            new Rule("No Neo4j installation|neo4j-admin is missing|Cannot run program|Could not run \\S*(neo4j-admin|java)|error=2,|Permission denied",
                    "neo4j-admin could not be run",
                    "The tool tried to start a Neo4j program and the system refused or did not find it.",
                    "The Neo4j home in Settings is wrong, the program is not installed there, or the user that runs the dashboard may not run it.",
                    "In Settings check Runner and Neo4j home (it must contain bin/neo4j-admin), and the permissions of that folder."),
            new Rule("Cannot connect to the Docker daemon|Docker did not report|docker(\\.sock| daemon)|docker: ",
                    "Docker is not available",
                    "The tool needed Docker to start a temporary Neo4j and could not reach it.",
                    "Docker is not running, or the user that runs the dashboard is not allowed to use it.",
                    "Start Docker and give the dashboard's user access, or switch Runner to LOCAL in Settings."),
            new Rule("did not accept connections within|stopped while starting|did not open in time|Could not start Neo4j",
                    "The temporary Neo4j server did not start",
                    "The tool starts a temporary Neo4j to read or finish a database, and it did not become ready.",
                    "Too little memory, a store written by a different Neo4j version than the one that runs it, or a slow disk.",
                    "Read the lines above the error in the log (Neo4j says why). Use the Docker image or Neo4j home of the same version "
                            + "that wrote the dump, give the temporary server more heap in Settings, or raise the start timeout."),
            new Rule("fast reader",
                    "The fast reader failed",
                    "The faster way of reading a stopped database directly did not work.",
                    "Not enough memory for its heap and page cache, or a store it cannot open.",
                    "Raise the fast reader's heap in Settings, or switch the fast reader off there (slower, but it reads through Bolt)."),
            new Rule("Unable to connect|ServiceUnavailable|Connection refused|authentication failure|unauthorized|Neo\\.ClientError\\.Security",
                    "Neo4j could not be reached or refused the sign-in",
                    "The tool could not connect to a Neo4j server, or its user name and password were not accepted.",
                    "The server is down or at another address, or the saved password is out of date.",
                    "Open Databases and press Check on the database. Fix the address or the password there."),
            new Rule("There is no database '([^']+)'",
                    "That database is not in the files you chose",
                    "The dump or data directory does not contain a database called '{1}'.",
                    "The name of the database under Databases is not the one inside the files.",
                    "Open Databases, edit the entry and give the name of the database that is really inside (usually neo4j)."),
            new Rule("dump .* does not exist|was not written|database load|not a valid dump|Invalid (dump|input)|unsupported (store|format)",
                    "The dump could not be loaded",
                    "neo4j-admin could not read the dump or the store.",
                    "The file is not a dump of this Neo4j version, it is damaged, or it is still being written.",
                    "Check the file under Databases (Check). Use the same Neo4j version that wrote it, or make the dump again."),
            new Rule("already exists",
                    "A file that the run wanted to write already exists",
                    "The tool never overwrites a file, so it stopped before changing anything.",
                    "An earlier run left a file with the same name in the output or backup folder.",
                    "Remove or rename that file (its name is in the technical details), or choose another folder in Settings, and run again."),
            new Rule("was deleted",
                    "A database of this migration was deleted",
                    "The source or the target was removed from Databases after the migration was made.",
                    "Someone deleted it.",
                    "Add it again under Databases and make a new migration."),
            new Rule("Could not decrypt the stored password",
                    "A saved password cannot be read",
                    "The tool could not decrypt the password of a database.",
                    "TOOL_SECRET was changed after the password was saved.",
                    "Put the old TOOL_SECRET back, or edit the database under Databases and type its password again."),
            new Rule("Writing properties afterwards|Could not create an index or constraint",
                    "The last touches on the new database failed",
                    "After the import the tool writes the values the CSV cannot carry and recreates the indexes; one of them was refused.",
                    "A value or an index definition the new database does not accept.",
                    "Send the technical details below and the log: they name the statement or the node."),
            new Rule("Interrupted by a restart",
                    "The dashboard was restarted while the job was running",
                    "Jobs do not survive a restart, so this one was stopped where it was.",
                    "The dashboard was updated, restarted or the server was rebooted during the run.",
                    "Run again. Restart or update the dashboard only when no job is running.")
    );

    /** The message of a throwable and of its causes, so that a rule can see what the deepest one said. */
    public static String describe(Throwable e) {
        List<String> parts = new ArrayList<>();
        Throwable current = e;
        for (int depth = 0; current != null && depth < 5; depth++, current = current.getCause()) {
            String message = current.getMessage();
            String text = message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
            if (depth > 0) {
                text = "Caused by: " + current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
            }
            String line = text;
            if (parts.stream().noneMatch(part -> part.contains(line) || line.contains(part))) {
                parts.add(line);
            }
        }
        return String.join("\n", parts);
    }

    /**
     * @param technical the message and its causes
     * @param stage     the stage the job was in
     * @param step      the step the log last named, or null when the job stopped before the first step (opening the databases)
     */
    public static Failure explain(String technical, Stage stage, String step) {
        String text = technical == null ? "" : technical;
        String shownStep = step == null || step.isBlank() ? "Before the first step (opening the databases)" : step;
        String[] data = dataOf(stage, step);
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            if (m.find()) {
                String note = rule.data() != null ? rule.data() : data[0];
                boolean safe = rule.data() != null ? rule.data().startsWith("Nothing") : "safe".equals(data[1]);
                return new Failure(stage.label(), shownStep, rule.title(), fill(rule.what(), m), fill(rule.cause(), m),
                        fill(rule.todo(), m), note, safe, text);
            }
        }
        return new Failure(stage.label(), shownStep, "The " + stage.label().toLowerCase() + " stopped at \"" + shownStep + "\"",
                "A step failed with an error this tool does not know yet.",
                "The message under Technical details is what the program reported; the last lines of the log usually show the cause.",
                "Press Copy details and Copy log below and send both. Nothing is lost by running the same step again.", data[0],
                "safe".equals(data[1]), text);
    }

    private static String fill(String template, Matcher m) {
        String out = template;
        for (int group = 1; group <= m.groupCount(); group++) {
            out = out.replace("{" + group + "}", m.group(group) == null ? "" : m.group(group));
        }
        return out;
    }

    /**
     * Whether the user's databases can have been changed, from the step the job was in: [text, "safe" or "check"].
     * Only the last step of a run puts anything in a user's place; every other step reads, or writes to the temporary
     * folder that is deleted afterwards. "safe" is said only where the code guarantees it.
     */
    static String[] dataOf(Stage stage, String step) {
        if (stage == Stage.PLAN || stage == Stage.DRY_RUN) {
            return new String[] {"Nothing was changed. This step only reads.", "safe"};
        }
        if (stage == Stage.ROLLBACK) {
            return new String[] {"The rollback may have been partly done. Look at the data directory: the old database is kept "
                    + "next to it as before-<time>, and the one that was swapped in as rolled-back-before-<time>. Do not start "
                    + "the server until you have checked.", "check"};
        }
        String name = step == null ? "" : step;
        if (name.startsWith("Swapping the new database")) {
            return new String[] {"Check before you go on: the swap into the data directory may have started. The old database "
                    + "is kept next to it as before-<time>, and the backup of the target is in the backup folder. Do not start "
                    + "the server until you have checked.", "check"};
        }
        if (name.startsWith("Writing the merged dump") || name.startsWith("Writing the dump")
                || name.startsWith("Moving the data directory")) {
            return new String[] {"The source and the target were not changed. The last step failed, so a partly written file may "
                    + "be left in the output folder: check it and delete it before running again.", "check"};
        }
        return new String[] {"The source and the target were only read; the new database was never put in place and the "
                + "temporary files of this run were removed. A backup of the target taken at the start is kept in the backup "
                + "folder.", "safe"};
    }
}
