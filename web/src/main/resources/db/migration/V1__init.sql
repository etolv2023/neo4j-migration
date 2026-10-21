-- Dashboard metadata only. Node ids from the graph databases are never stored here; reports keep their own JSON.

CREATE TABLE connections (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    uri           VARCHAR(500) NOT NULL,
    username      VARCHAR(200) NULL,
    password_enc  VARCHAR(1000) NULL,
    database_name VARCHAR(100) NOT NULL DEFAULT 'neo4j',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_connections_name UNIQUE (name)
);

CREATE TABLE selection_configs (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    yaml       TEXT         NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_selection_configs_name UNIQUE (name)
);

-- Connection names are copied in so that history stays readable after a connection is deleted.
CREATE TABLE dry_run_reports (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_name    VARCHAR(100) NOT NULL,
    target_name    VARCHAR(100) NULL,
    selection_yaml TEXT         NOT NULL,
    options_json   VARCHAR(500) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    gate           VARCHAR(20)  NULL,
    selected_nodes BIGINT       NULL,
    missing_nodes  BIGINT       NULL,
    same_nodes     BIGINT       NULL,
    conflict_nodes BIGINT       NULL,
    report_json    LONGTEXT     NULL,
    error          TEXT         NULL,
    started_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at    TIMESTAMP(3) NULL,
    INDEX ix_dry_run_reports_started (started_at)
);

CREATE TABLE audit_log (
    id     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    actor  VARCHAR(100)  NOT NULL,
    action VARCHAR(50)   NOT NULL,
    detail VARCHAR(2000) NULL,
    INDEX ix_audit_log_at (at)
);
