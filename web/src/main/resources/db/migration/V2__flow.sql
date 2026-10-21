-- Endpoints: a database the tool reads from or compares with. It is a running server (LIVE),
-- a dump file (DUMP) or the data directory of a stopped server (STOPPED).
RENAME TABLE connections TO endpoints;
ALTER TABLE endpoints
    ADD COLUMN kind VARCHAR(20)   NOT NULL DEFAULT 'LIVE' AFTER name,
    ADD COLUMN path VARCHAR(1000) NULL AFTER uri,
    MODIFY uri VARCHAR(500) NULL;

-- Selections now live inside a migration.
DROP TABLE selection_configs;

CREATE TABLE settings (
    name       VARCHAR(100)  NOT NULL PRIMARY KEY,
    value      VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- One migration is one pass through the flow: from, to, selection, plan, dry run (and later the run).
CREATE TABLE migrations (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(150) NOT NULL,
    source_name    VARCHAR(100) NOT NULL,
    target_name    VARCHAR(100) NULL,
    source_id      BIGINT       NOT NULL,
    target_id      BIGINT       NULL,
    selection_yaml TEXT         NOT NULL,
    state          VARCHAR(20)  NOT NULL,
    plan_json      LONGTEXT     NULL,
    dry_run_id     BIGINT       NULL,
    last_error     TEXT         NULL,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX ix_migrations_created (created_at)
);

ALTER TABLE dry_run_reports ADD COLUMN migration_id BIGINT NULL;

-- Everything a job prints while it works. scope is "migration:<id>".
CREATE TABLE job_logs (
    id      BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope   VARCHAR(60)   NOT NULL,
    at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    level   VARCHAR(10)   NOT NULL,
    message VARCHAR(2000) NOT NULL,
    INDEX ix_job_logs_scope (scope, id)
);
