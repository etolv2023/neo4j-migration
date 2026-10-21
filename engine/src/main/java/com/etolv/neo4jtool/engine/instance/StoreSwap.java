package com.etolv.neo4jtool.engine.instance;

/**
 * Shell scripts that put a new database in the place of an old one inside a stopped server's data directory, and put
 * the old one back. They are scripts, not Java file calls, because the files may belong to another user and sit on
 * another file system than the new store: {@code mv} and {@code chown} deal with both, and a container (root) can
 * do it even when the dashboard's user cannot.
 *
 * <p>Both take: target data directory, [new data directory,] database name, name of the kept directory.
 */
final class StoreSwap {

    private StoreSwap() {
    }

    /**
     * The old store moves to {@code <target>/<keep>/databases/<db>} (and the same for transactions), the new one takes
     * its place and gets the owner of the old one. If a step fails the old store is put back.
     */
    static final String SWAP = """
            set -eu
            target=$1; new=$2; db=$3; keep=$4
            [ -d "$target/databases/$db" ] || { echo "databases/$db is not in $target" >&2; exit 2; }
            [ -d "$new/databases/$db" ] || { echo "there is no new database in $new" >&2; exit 2; }
            [ ! -e "$target/$keep" ] || { echo "$target/$keep already exists" >&2; exit 2; }
            owner=$(stat -c '%u:%g' "$target/databases/$db")
            moved=0
            undo() {
              if [ "$moved" = 1 ]; then
                echo "The swap failed; putting the old database back" >&2
                rm -rf "$target/databases/$db" "$target/transactions/$db"
                mv "$target/$keep/databases/$db" "$target/databases/$db"
                if [ -d "$target/$keep/transactions/$db" ]; then mv "$target/$keep/transactions/$db" "$target/transactions/$db"; fi
                rm -rf "$target/$keep"
              fi
            }
            trap undo EXIT
            mkdir -p "$target/$keep/databases" "$target/$keep/transactions"
            moved=1
            mv "$target/databases/$db" "$target/$keep/databases/$db"
            if [ -d "$target/transactions/$db" ]; then mv "$target/transactions/$db" "$target/$keep/transactions/$db"; fi
            mkdir -p "$target/transactions"
            mv "$new/databases/$db" "$target/databases/$db"
            mv "$new/transactions/$db" "$target/transactions/$db"
            moved=0
            chown -R "$owner" "$target/databases/$db" "$target/transactions/$db" 2>/dev/null || true
            now=$(stat -c '%u:%g' "$target/databases/$db")
            if [ "$now" != "$owner" ]; then
              echo "WARNING: the new files belong to $now but the old ones belonged to $owner. Run: chown -R $owner $target/databases/$db $target/transactions/$db"
            fi
            echo "The old database is kept in $target/$keep"
            """;

    /** The reverse: the current store is set aside in {@code <target>/rolled-back-<keep>} and the kept one comes back. */
    static final String RESTORE = """
            set -eu
            target=$1; db=$2; keep=$3
            [ -d "$target/$keep/databases/$db" ] || { echo "$target/$keep/databases/$db is not there" >&2; exit 2; }
            aside="$target/rolled-back-$keep"
            [ ! -e "$aside" ] || { echo "$aside already exists" >&2; exit 2; }
            mkdir -p "$aside/databases" "$aside/transactions"
            mv "$target/databases/$db" "$aside/databases/$db"
            if [ -d "$target/transactions/$db" ]; then mv "$target/transactions/$db" "$aside/transactions/$db"; fi
            mv "$target/$keep/databases/$db" "$target/databases/$db"
            if [ -d "$target/$keep/transactions/$db" ]; then mv "$target/$keep/transactions/$db" "$target/transactions/$db"; fi
            rmdir "$target/$keep/databases" "$target/$keep/transactions" "$target/$keep" 2>/dev/null || true
            echo "The old database is back. The new one was moved to $aside"
            """;
}
