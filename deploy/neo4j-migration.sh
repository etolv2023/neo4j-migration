#!/usr/bin/env bash
# Build, run and update the Neo4j migration dashboard on a server, without Docker.
#
#   neo4j-migration.sh init                  create the folders and the settings file, check what is installed
#   neo4j-migration.sh build [--with-tests]  build a new release from this checkout (does not touch the running one)
#   neo4j-migration.sh start [release]       start the dashboard (default: the newest release, or the one rollback chose)
#   neo4j-migration.sh stop                  stop it
#   neo4j-migration.sh restart [release]     stop, then start
#   neo4j-migration.sh status                running or not, which release, which commit
#   neo4j-migration.sh update                git pull, build, restart; the running one is left alone if the build fails
#   neo4j-migration.sh rollback              go back to the release before the one that runs, and restart
#   neo4j-migration.sh logs                  follow the log
#
# Settings (environment variables, all optional):
#   NM_HOME       install folder                    (default /opt/neo4j-migration)
#   NM_ENV        file with the dashboard's settings (default /etc/neo4j-migration/env)
#   NM_REPO       the checkout to build from         (default: the folder above this script)
#   NM_BRANCH     branch that "update" pulls         (default main)
#   NM_KEEP       releases to keep                   (default 5)
#   NM_STOP_WAIT  seconds to wait for a stop         (default 60)
#   NM_START_WAIT seconds to wait for a start        (default 90)
#   MVN           the Maven command                  (default mvn)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${NM_REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
BASE="${NM_HOME:-/opt/neo4j-migration}"
ENV_FILE="${NM_ENV:-/etc/neo4j-migration/env}"
BRANCH="${NM_BRANCH:-main}"
KEEP="${NM_KEEP:-5}"
STOP_WAIT="${NM_STOP_WAIT:-60}"
START_WAIT="${NM_START_WAIT:-90}"
MVN="${MVN:-mvn}"

RELEASES="$BASE/releases"
CURRENT="$BASE/current"
PINNED="$BASE/pinned"
PID_FILE="$BASE/run/dashboard.pid"
LOG_FILE="$BASE/logs/dashboard.log"

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- helpers

java_major() {
    command -v java >/dev/null 2>&1 || { echo 0; return; }
    # the line with the version is not always the first (JAVA_TOOL_OPTIONS adds one before it)
    java -version 2>&1 | sed -n 's/.* version "\([0-9]*\)[."].*/\1/p' | head -n 1
}

require_java() {
    local major
    major="$(java_major)"
    [ "${major:-0}" -ge 21 ] 2>/dev/null || die "Java 21 or newer is needed (found: ${major:-none})"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "$1 is not installed or not in PATH"
}

make_folders() {
    mkdir -p "$RELEASES" "$BASE/run" "$BASE/logs"
}

load_env() {
    [ -r "$ENV_FILE" ] || die "$ENV_FILE is missing or not readable. Run: $0 init, then fill it in"
    local mode
    mode="$(stat -c %a "$ENV_FILE" 2>/dev/null || echo 600)"
    case "$mode" in
        *00) ;;
        *) say "warning: $ENV_FILE has mode $mode; it holds secrets, use: chmod 600 $ENV_FILE" ;;
    esac
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
}

# Release names are timestamps, so a sort is a time order.
all_releases() {
    [ -d "$RELEASES" ] || return 0
    ls -1 "$RELEASES" | grep -E '^[0-9]{8}-[0-9]{6}$' | sort || true
}

newest_release() { all_releases | tail -n 1; }

current_release() {
    [ -L "$CURRENT" ] || return 0
    basename "$(readlink "$CURRENT")"
}

link_current() {
    local release="$1"
    [ -f "$RELEASES/$release/web.jar" ] || die "release '$release' does not exist in $RELEASES"
    ln -sfn "$RELEASES/$release" "$CURRENT.tmp"
    mv -T "$CURRENT.tmp" "$CURRENT"
}

pid_of_running() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid="$(cat "$PID_FILE")"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
    # the pid may have been reused by something else after a crash or a reboot
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'web.jar' || return 1
    echo "$pid"
}

port() { echo "${SERVER_PORT:-8080}"; }

# ---------------------------------------------------------------- commands

cmd_init() {
    local problems=0
    make_folders
    say "folders: $BASE/{releases,run,logs}"

    if [ ! -e "$ENV_FILE" ]; then
        mkdir -p "$(dirname "$ENV_FILE")" || die "cannot create $(dirname "$ENV_FILE") (run init with sudo, or set NM_ENV)"
        (
            umask 077
            cat > "$ENV_FILE" <<'EOF'
# Settings of the Neo4j migration dashboard. This is shell syntax: quote values that have spaces or special characters.
# Keep this file private (mode 600). Nothing here is printed by the script.

# MySQL for the dashboard's own data (users, saved databases, plans, reports, logs). No graph data is stored there.
#TOOL_DB_URL='jdbc:mysql://localhost:3306/neo4j_tool'
#TOOL_DB_USER='neo4j_tool'
#TOOL_DB_PASSWORD=''

# Encrypts the saved Neo4j passwords, at least 16 characters. If you change it, saved passwords become unreadable.
#TOOL_SECRET=''

# Creates the first user, only when there are no users yet. The password needs at least 8 characters.
#TOOL_ADMIN_USER='admin'
#TOOL_ADMIN_PASSWORD=''

# Optional
#SERVER_PORT=8080
#JAVA_OPTS='-Xmx2g'
EOF
        )
        say "settings file: $ENV_FILE (new, mode 600; uncomment and fill in the values)"
    else
        say "settings file: $ENV_FILE (already there, not changed)"
    fi

    local major
    major="$(java_major)"
    if [ "${major:-0}" -ge 21 ] 2>/dev/null; then say "java: $major"; else say "MISSING: Java 21 or newer (found: ${major:-none})"; problems=1; fi
    for tool in git "$MVN" curl; do
        if command -v "$tool" >/dev/null 2>&1; then say "$tool: found"; else
            if [ "$tool" = curl ]; then say "note: curl is not installed; start cannot check the web page, it only checks the process"
            else say "MISSING: $tool"; problems=1; fi
        fi
    done
    if command -v neo4j-admin >/dev/null 2>&1 || [ -x "${NEO4J_HOME:-/nonexistent}/bin/neo4j-admin" ]; then
        say "neo4j-admin: found"
    else
        say "note: neo4j-admin is not in PATH. Give the Neo4j installation folder in the dashboard (Settings, \"A Neo4j installation on this machine\")"
    fi
    [ -d "$REPO/.git" ] || { say "MISSING: $REPO is not a git checkout (set NM_REPO)"; problems=1; }
    say "note: run the dashboard as a user that can write to the Neo4j data folders it reads in place, and to the work/output folders"
    [ "$problems" -eq 0 ] || exit 1
}

cmd_build() {
    local tests=skip
    case "${1:-}" in
        --with-tests) tests=run ;;
        "") ;;
        *) die "unknown option '$1' (only --with-tests)" ;;
    esac
    require_java
    require_command git
    require_command "$MVN"
    make_folders

    local commit stamp tmp jar lib
    commit="$(git -C "$REPO" rev-parse --short HEAD)"
    git -C "$REPO" diff --quiet HEAD -- 2>/dev/null || say "note: $REPO has uncommitted changes; they are part of this build"
    stamp="$(date +%Y%m%d-%H%M%S)"
    tmp="$RELEASES/.building-$stamp"
    trap 'rm -rf "$tmp"' EXIT

    say "building $commit ($([ "$tests" = skip ] && echo 'tests skipped' || echo 'with tests'))"
    if [ "$tests" = skip ]; then
        (cd "$REPO" && "$MVN" -B clean verify -DskipTests)
    else
        (cd "$REPO" && "$MVN" -B clean verify)
    fi

    jar=""
    for f in "$REPO"/web/target/web-*.jar; do
        case "$f" in *.original) ;; *) jar="$f" ;; esac
    done
    [ -f "$jar" ] || die "the build did not produce web/target/web-*.jar"
    lib="$REPO/reader/target/lib"
    ls "$lib"/reader-*.jar >/dev/null 2>&1 || die "the build did not produce $lib (the fast reader's jars)"

    mkdir -p "$tmp"
    cp "$jar" "$tmp/web.jar"
    cp -r "$lib" "$tmp/reader-lib"
    echo "$commit" > "$tmp/COMMIT"
    mv "$tmp" "$RELEASES/$stamp"
    trap - EXIT
    rm -f "$PINNED"          # a new build ends a rollback
    prune
    say "built release $stamp (commit $commit) in $RELEASES/$stamp"
    say "run it with: $0 restart (or start, if it is not running)"
}

prune() {
    local keep_current keep_pinned release
    keep_current="$(current_release)"
    keep_pinned="$( [ -f "$PINNED" ] && cat "$PINNED" || true )"
    local count=0
    for release in $(all_releases | sort -r); do
        count=$((count + 1))
        [ "$count" -le "$KEEP" ] && continue
        [ "$release" = "$keep_current" ] && continue
        [ "$release" = "$keep_pinned" ] && continue
        rm -rf "${RELEASES:?}/$release"
    done
}

cmd_start() {
    local pid
    require_java
    load_env
    make_folders
    if pid="$(pid_of_running)"; then
        say "already running (pid $pid, release $(current_release))"
        return 0
    fi

    local release="${1:-}"
    if [ -z "$release" ] && [ -f "$PINNED" ]; then release="$(cat "$PINNED")"; fi
    if [ -z "$release" ]; then release="$(newest_release)"; fi
    [ -n "$release" ] || die "there is no release yet. Run: $0 build"
    link_current "$release"

    say "starting release $release (commit $(cat "$CURRENT/COMMIT" 2>/dev/null || echo unknown))"
    cd "$CURRENT"
    # JAVA_OPTS is meant to be split into words
    # shellcheck disable=SC2086
    nohup java ${JAVA_OPTS--Xmx2g} -jar web.jar >> "$LOG_FILE" 2>&1 &
    pid=$!
    echo "$pid" > "$PID_FILE"

    local waited=0
    while [ "$waited" -lt "$START_WAIT" ]; do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$PID_FILE"
            say "---- last lines of $LOG_FILE ----"
            tail -n 30 "$LOG_FILE" || true
            die "the dashboard stopped right after starting (see above)"
        fi
        if command -v curl >/dev/null 2>&1 \
            && curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$(port)/login"; then
            say "running: pid $pid, http://127.0.0.1:$(port)"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    if command -v curl >/dev/null 2>&1; then
        say "warning: pid $pid is alive but the page did not answer in ${START_WAIT}s; see: $0 logs"
    else
        say "running: pid $pid (curl is not installed, so the page was not checked)"
    fi
}

cmd_stop() {
    local pid
    if ! pid="$(pid_of_running)"; then
        rm -f "$PID_FILE"
        say "not running"
        return 0
    fi
    say "stopping pid $pid (a migration that is running is stopped and shown as failed)"
    kill -TERM "$pid"
    local waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$STOP_WAIT" ]; then
            say "still running after ${STOP_WAIT}s, killing it"
            kill -KILL "$pid" 2>/dev/null || true
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
    rm -f "$PID_FILE"
    say "stopped"
}

cmd_restart() {
    cmd_stop
    cmd_start "$@"
}

cmd_status() {
    local pid newest
    if pid="$(pid_of_running)"; then
        say "running: pid $pid, port $(port)"
    else
        say "not running"
    fi
    say "release: $(current_release || true) (commit $(cat "$CURRENT/COMMIT" 2>/dev/null || echo unknown))"
    [ -f "$PINNED" ] && say "pinned by rollback: $(cat "$PINNED") (the next build clears it)"
    newest="$(newest_release)"
    [ -n "$newest" ] && [ "$newest" != "$(current_release)" ] && say "newest built release: $newest (not the one linked as current)"
    say "releases: $(all_releases | tr '\n' ' ')"
    say "log: $LOG_FILE"
}

cmd_update() {
    require_command git
    local on
    on="$(git -C "$REPO" rev-parse --abbrev-ref HEAD)"
    [ "$on" = "$BRANCH" ] || die "$REPO is on '$on', not '$BRANCH' (set NM_BRANCH, or check out $BRANCH)"
    say "pulling $BRANCH"
    git -C "$REPO" pull --ff-only origin "$BRANCH"
    cmd_build "$@"          # if this fails, set -e stops here and the running dashboard is not touched
    cmd_restart
}

cmd_rollback() {
    local now previous release
    now="$(current_release)"
    [ -n "$now" ] || die "nothing is running from a release yet"
    previous=""
    for release in $(all_releases); do
        [ "$release" = "$now" ] && break
        previous="$release"
    done
    [ -n "$previous" ] || die "there is no release before $now"
    echo "$previous" > "$PINNED"
    say "rolling back from $now to $previous"
    cmd_restart "$previous"
}

cmd_logs() {
    [ -f "$LOG_FILE" ] || die "no log yet: $LOG_FILE"
    exec tail -n 100 -f "$LOG_FILE"
}

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; }

command="${1:-help}"
shift || true
case "$command" in
    init) cmd_init "$@" ;;
    build) cmd_build "$@" ;;
    start) cmd_start "$@" ;;
    stop) cmd_stop "$@" ;;
    restart) cmd_restart "$@" ;;
    status) cmd_status "$@" ;;
    update) cmd_update "$@" ;;
    rollback) cmd_rollback "$@" ;;
    logs) cmd_logs "$@" ;;
    help|-h|--help) usage ;;
    *) usage >&2; exit 1 ;;
esac
