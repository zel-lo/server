-- zello ERD DDL (MySQL 8.0.16+)
-- Source: docs/erd.md (2026-10-30), 21 tables; backend review revision.
-- Import this file as MySQL SQL in an external ERD tool.
-- dbdiagram.io: Import -> MySQL -> paste this file's full contents or upload it.
-- For direct use in the default DBML editor, use the companion erd.dbml file.
-- Run against an empty schema. No database creation or data deletion is included.
-- All DATETIME(6) values use UTC; configure the application/DB session accordingly.
-- Historical references use RESTRICT; archive/disconnect records instead of cascading deletes.
-- Cross-table rules enforced by the service:
--   * Project owners must be active project members; login accounts must be USER accounts.
--   * Same-project/repository links are enforced by composite foreign keys.
--   * Patch note PRs must be merged (service rule); project scope is enforced by the DB.
--   * Active membership, user status and current GitHub access are checked by the service.
--   * Source timestamp comparisons and optimistic locking are required when applying updates.
--   * Invitation consumption and owner/membership changes must be transactional.

CREATE TABLE github_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    github_id BIGINT NOT NULL,
    login VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NULL,
    avatar_url VARCHAR(2048) NULL,
    account_type VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_github_accounts_github_id UNIQUE (github_id),
    CONSTRAINT ck_github_accounts_type CHECK (account_type IN ('USER', 'BOT', 'ORGANIZATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    github_account_id BIGINT NOT NULL,
    email VARCHAR(320) NULL,
    theme VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    withdrawn_at DATETIME(6) NULL,
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_github_account UNIQUE (github_account_id),
    CONSTRAINT fk_users_github_account FOREIGN KEY (github_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_users_withdrawal CHECK ((status = 'ACTIVE' AND withdrawn_at IS NULL) OR (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    creation_request_key VARCHAR(64) NULL,
    goal TEXT NULL,
    description TEXT NULL,
    timezone VARCHAR(64) NOT NULL,
    sync_interval_minutes INT NOT NULL,
    archived_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_projects_owner FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uk_projects_creation_request UNIQUE (creation_request_key),
    CONSTRAINT ck_projects_period CHECK ((start_date IS NULL AND end_date IS NULL) OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date)),
    CONSTRAINT ck_projects_sync_interval CHECK (sync_interval_minutes > 0),
    CONSTRAINT ck_projects_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE project_members (
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    job_title VARCHAR(100) NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    left_at DATETIME(6) NULL,
    notify_team_updates BOOLEAN NOT NULL,
    notify_deployments BOOLEAN NOT NULL,
    weekly_summary_enabled BOOLEAN NOT NULL,
    PRIMARY KEY (project_id, user_id),
    KEY idx_project_members_user_active (user_id, left_at, project_id),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_project_members_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_project_members_role CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE github_installations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    github_installation_id BIGINT NOT NULL,
    owner_account_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    permissions JSON NOT NULL,
    installed_at DATETIME(6) NOT NULL,
    suspended_at DATETIME(6) NULL,
    removed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_github_installations_github_id UNIQUE (github_installation_id),
    CONSTRAINT fk_github_installations_owner FOREIGN KEY (owner_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_github_installations_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repositories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    installation_id BIGINT NULL,
    authorization_method VARCHAR(20) NOT NULL DEFAULT 'GITHUB_APP',
    github_repo_id BIGINT NOT NULL,
    owner_login VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    html_url VARCHAR(2048) NOT NULL,
    description TEXT NULL,
    primary_language VARCHAR(100) NULL,
    default_branch VARCHAR(255) NOT NULL,
    is_private BOOLEAN NOT NULL,
    connection_status VARCHAR(30) NOT NULL,
    initial_sync_completed_at DATETIME(6) NULL,
    last_synced_at DATETIME(6) NULL,
    last_sync_error TEXT NULL,
    disconnected_at DATETIME(6) NULL,
    github_updated_at DATETIME(6) NULL,
    last_fetched_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_repositories_scope UNIQUE (id, project_id),
    CONSTRAINT uk_repositories_project_github UNIQUE (project_id, github_repo_id),
    CONSTRAINT fk_repositories_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repositories_installation FOREIGN KEY (installation_id)
        REFERENCES github_installations (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_repositories_connection_status CHECK (
        connection_status IN ('PENDING', 'SYNCING', 'CONNECTED', 'PARTIAL_FAILURE', 'AUTH_REQUIRED', 'DISCONNECTED')
    ),
    CONSTRAINT ck_repositories_authorization CHECK (
        (authorization_method = 'GITHUB_APP' AND installation_id IS NOT NULL)
        OR (authorization_method = 'OAUTH' AND installation_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repository_sync_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    retry_of_id BIGINT NULL,
    run_kind VARCHAR(20) NOT NULL,
    sync_scope VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_no INT NOT NULL DEFAULT 1,
    emit_notifications BOOLEAN NOT NULL DEFAULT FALSE,
    source_since_at DATETIME(6) NULL,
    checkpoint JSON NULL,
    items_processed BIGINT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NULL,
    heartbeat_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    next_retry_at DATETIME(6) NULL,
    last_error TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_repository_sync_runs_scope UNIQUE (id, repository_id),
    KEY idx_repository_sync_runs_repository_created (repository_id, created_at, id),
    KEY idx_repository_sync_runs_status_retry (status, next_retry_at, id),
    CONSTRAINT fk_repository_sync_runs_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repository_sync_runs_retry FOREIGN KEY (retry_of_id, repository_id)
        REFERENCES repository_sync_runs (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_repository_sync_runs_kind CHECK (run_kind IN ('INITIAL', 'PERIODIC', 'MANUAL')),
    CONSTRAINT ck_repository_sync_runs_scope CHECK (sync_scope IN ('ALL', 'ISSUES', 'PULL_REQUESTS', 'REVIEWS', 'BUILDS', 'DEPLOYMENTS')),
    CONSTRAINT ck_repository_sync_runs_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_repository_sync_runs_counts CHECK (attempt_no > 0 AND items_processed >= 0),
    CONSTRAINT ck_repository_sync_runs_initial CHECK (run_kind <> 'INITIAL' OR emit_notifications = FALSE),
    CONSTRAINT ck_repository_sync_runs_lifecycle CHECK (
        (status = 'PENDING' AND started_at IS NULL AND finished_at IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND started_at IS NOT NULL AND finished_at IS NOT NULL AND finished_at >= started_at)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE project_invitations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    invited_by_user_id BIGINT NOT NULL,
    target_account_id BIGINT NULL,
    accepted_by_user_id BIGINT NULL,
    token_hash VARCHAR(64) NOT NULL,
    granted_role VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_project_invitations_created (project_id, created_at, id),
    KEY idx_project_invitations_pending (project_id, invited_by_user_id, accepted_at, revoked_at, expires_at),
    CONSTRAINT uk_project_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_project_invitations_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_project_invitations_inviter FOREIGN KEY (invited_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_project_invitations_target FOREIGN KEY (target_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_project_invitations_accepter FOREIGN KEY (accepted_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_project_invitations_role CHECK (granted_role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    CONSTRAINT ck_project_invitations_acceptance CHECK ((accepted_at IS NULL AND accepted_by_user_id IS NULL) OR (accepted_at IS NOT NULL AND accepted_by_user_id IS NOT NULL)),
    CONSTRAINT ck_project_invitations_terminal CHECK (accepted_at IS NULL OR revoked_at IS NULL),
    CONSTRAINT ck_project_invitations_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_project_invitations_accepted_at CHECK (accepted_at IS NULL OR (accepted_at >= created_at AND accepted_at < expires_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE webhook_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    github_delivery_id VARCHAR(64) NOT NULL,
    installation_id BIGINT NULL,
    event_name VARCHAR(100) NOT NULL,
    action VARCHAR(100) NULL,
    payload JSON NOT NULL,
    processing_status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processing_started_at DATETIME(6) NULL,
    lease_expires_at DATETIME(6) NULL,
    next_retry_at DATETIME(6) NULL,
    processed_at DATETIME(6) NULL,
    last_error TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_webhook_deliveries_github_id UNIQUE (github_delivery_id),
    KEY idx_webhook_deliveries_processing (processing_status, next_retry_at, received_at),
    CONSTRAINT fk_webhook_deliveries_installation FOREIGN KEY (installation_id)
        REFERENCES github_installations (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_webhook_deliveries_status CHECK (
        processing_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED')
    ),
    CONSTRAINT ck_webhook_deliveries_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_webhook_deliveries_processed CHECK ((processing_status = 'PROCESSED' AND processed_at IS NOT NULL) OR (processing_status <> 'PROCESSED' AND processed_at IS NULL)),
    CONSTRAINT ck_webhook_deliveries_lease CHECK (processing_status <> 'PROCESSING' OR (processing_started_at IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_expires_at > processing_started_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE issues (
    id BIGINT NOT NULL AUTO_INCREMENT,
    issue_key VARCHAR(32) NOT NULL,
    archived_at DATETIME(6) NULL,
    creation_hash VARCHAR(64) NULL,
    board_order BIGINT NOT NULL DEFAULT 0,
    planned_week_start DATE NOT NULL,
    completed_at DATETIME(6) NULL,
    repository_id BIGINT NOT NULL,
    author_account_id BIGINT NULL,
    assignee_account_id BIGINT NULL,
    created_by_user_id BIGINT NULL,
    github_issue_id BIGINT NULL,
    github_number INT NULL,
    github_state VARCHAR(20) NULL,
    github_url VARCHAR(2048) NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    acceptance_criteria TEXT NULL,
    work_status VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    meeting_date DATE NULL,
    meeting_context TEXT NULL,
    publish_status VARCHAR(20) NOT NULL,
    publish_request_key VARCHAR(64) NULL,
    last_publish_error TEXT NULL,
    github_updated_at DATETIME(6) NULL,
    last_fetched_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_issues_scope UNIQUE (id, repository_id),
    CONSTRAINT uk_issues_key UNIQUE (issue_key),
    CONSTRAINT uk_issues_publish_request UNIQUE (publish_request_key),
    CONSTRAINT uk_issues_repository_github_id UNIQUE (repository_id, github_issue_id),
    CONSTRAINT uk_issues_repository_number UNIQUE (repository_id, github_number),
    KEY idx_issues_week (planned_week_start, work_status, completed_at),
    KEY idx_issues_board (repository_id, archived_at, work_status, board_order, id),
    KEY idx_issues_repository_status_priority (repository_id, work_status, priority),
    KEY idx_issues_assignee_status_repository (assignee_account_id, work_status, repository_id),
    KEY idx_issues_repository_source_meeting (repository_id, source_type, meeting_date),
    CONSTRAINT fk_issues_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_issues_author FOREIGN KEY (author_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_issues_assignee FOREIGN KEY (assignee_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_issues_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_issues_work_status CHECK (work_status IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE')),
    CONSTRAINT ck_issues_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_issues_source_type CHECK (source_type IN ('DAILY', 'WEEKLY', 'REQUEST', 'GITHUB')),
    CONSTRAINT ck_issues_publish_status CHECK (publish_status IN ('LOCAL', 'PENDING', 'PUBLISHED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_issues_meeting_date CHECK (source_type NOT IN ('DAILY', 'WEEKLY') OR meeting_date IS NOT NULL),
    CONSTRAINT ck_issues_external_identity CHECK ((github_issue_id IS NULL AND github_number IS NULL) OR (github_issue_id IS NOT NULL AND github_number IS NOT NULL AND github_issue_id > 0 AND github_number > 0)),
    CONSTRAINT ck_issues_published CHECK (publish_status <> 'PUBLISHED' OR (github_issue_id IS NOT NULL AND github_number IS NOT NULL AND github_state IS NOT NULL)),
    CONSTRAINT ck_issues_github_state CHECK (github_state IS NULL OR github_state IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_issues_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE pull_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    author_account_id BIGINT NULL,
    created_by_user_id BIGINT NULL,
    github_pr_id BIGINT NULL,
    github_number INT NULL,
    github_url VARCHAR(2048) NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NULL,
    team_summary TEXT NULL,
    head_branch VARCHAR(255) NOT NULL,
    base_branch VARCHAR(255) NOT NULL,
    head_sha VARCHAR(64) NULL,
    merge_commit_sha VARCHAR(64) NULL,
    state VARCHAR(20) NOT NULL,
    is_draft BOOLEAN NOT NULL,
    review_decision VARCHAR(30) NOT NULL,
    changed_files INT NULL,
    additions INT NULL,
    deletions INT NULL,
    commit_count INT NULL,
    opened_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    merged_at DATETIME(6) NULL,
    publish_status VARCHAR(20) NOT NULL,
    publish_request_key VARCHAR(64) NULL,
    last_publish_error TEXT NULL,
    github_updated_at DATETIME(6) NULL,
    last_fetched_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_pull_requests_scope UNIQUE (id, repository_id),
    CONSTRAINT uk_pull_requests_publish_request UNIQUE (publish_request_key),
    CONSTRAINT uk_pull_requests_repository_github_id UNIQUE (repository_id, github_pr_id),
    CONSTRAINT uk_pull_requests_repository_number UNIQUE (repository_id, github_number),
    KEY idx_pull_requests_repository_state_updated (repository_id, state, is_draft, updated_at),
    CONSTRAINT fk_pull_requests_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pull_requests_author FOREIGN KEY (author_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pull_requests_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_pull_requests_state CHECK (state IN ('OPEN', 'CLOSED', 'MERGED')),
    CONSTRAINT ck_pull_requests_review_decision CHECK (review_decision IN ('UNKNOWN', 'PENDING', 'APPROVED', 'CHANGES_REQUESTED')),
    CONSTRAINT ck_pull_requests_publish_status CHECK (publish_status IN ('LOCAL', 'PENDING', 'PUBLISHED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_pull_requests_external_identity CHECK ((github_pr_id IS NULL AND github_number IS NULL) OR (github_pr_id IS NOT NULL AND github_number IS NOT NULL AND github_pr_id > 0 AND github_number > 0)),
    CONSTRAINT ck_pull_requests_published CHECK (publish_status <> 'PUBLISHED' OR (github_pr_id IS NOT NULL AND github_number IS NOT NULL)),
    CONSTRAINT ck_pull_requests_merge CHECK ((state = 'MERGED' AND merged_at IS NOT NULL AND is_draft = FALSE AND publish_status = 'PUBLISHED') OR (state <> 'MERGED' AND merged_at IS NULL)),
    CONSTRAINT ck_pull_requests_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE issue_pull_requests (
    repository_id BIGINT NOT NULL,
    issue_id BIGINT NOT NULL,
    pull_request_id BIGINT NOT NULL,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (issue_id, pull_request_id),
    KEY idx_issue_pull_requests_pr_issue (pull_request_id, issue_id),
    CONSTRAINT fk_issue_pull_requests_issue FOREIGN KEY (issue_id, repository_id)
        REFERENCES issues (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_issue_pull_requests_pr FOREIGN KEY (pull_request_id, repository_id)
        REFERENCES pull_requests (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_issue_pull_requests_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE pull_request_reviewers (
    pull_request_id BIGINT NOT NULL,
    reviewer_account_id BIGINT NOT NULL,
    request_status VARCHAR(20) NOT NULL,
    requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (pull_request_id, reviewer_account_id),
    KEY idx_pull_request_reviewers_account_status (reviewer_account_id, request_status, pull_request_id),
    CONSTRAINT fk_pull_request_reviewers_pr FOREIGN KEY (pull_request_id)
        REFERENCES pull_requests (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pull_request_reviewers_account FOREIGN KEY (reviewer_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_pull_request_reviewers_status CHECK (request_status IN ('REQUESTED', 'COMPLETED', 'REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE pull_request_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pull_request_id BIGINT NOT NULL,
    reviewer_account_id BIGINT NULL,
    github_review_id BIGINT NOT NULL,
    state VARCHAR(30) NOT NULL,
    body TEXT NULL,
    commit_sha VARCHAR(64) NULL,
    submitted_at DATETIME(6) NULL,
    last_fetched_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_pull_request_reviews_pr_github UNIQUE (pull_request_id, github_review_id),
    CONSTRAINT fk_pull_request_reviews_pr FOREIGN KEY (pull_request_id)
        REFERENCES pull_requests (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pull_request_reviews_account FOREIGN KEY (reviewer_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE patch_notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    subtitle VARCHAR(255) NULL,
    summary TEXT NULL,
    version_label VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL,
    published_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_patch_notes_scope UNIQUE (id, project_id),
    KEY idx_patch_notes_project_status_published (project_id, status, published_at),
    CONSTRAINT fk_patch_notes_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_patch_notes_author FOREIGN KEY (author_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patch_notes_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_patch_notes_publication CHECK ((status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)),
    CONSTRAINT ck_patch_notes_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE patch_note_items (
    project_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    patch_note_id BIGINT NOT NULL,
    pull_request_id BIGINT NOT NULL,
    category VARCHAR(20) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (patch_note_id, pull_request_id),
    CONSTRAINT fk_patch_note_items_note FOREIGN KEY (patch_note_id, project_id)
        REFERENCES patch_notes (id, project_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_patch_note_items_pr FOREIGN KEY (pull_request_id, repository_id)
        REFERENCES pull_requests (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_patch_note_items_repository_scope FOREIGN KEY (repository_id, project_id)
        REFERENCES repositories (id, project_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patch_note_items_category CHECK (category IN ('FEATURE', 'FIX', 'IMPROVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE build_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    github_run_id BIGINT NOT NULL,
    run_attempt INT NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    branch_name VARCHAR(255) NULL,
    head_sha VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    conclusion VARCHAR(30) NULL,
    html_url VARCHAR(2048) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    github_updated_at DATETIME(6) NULL,
    last_fetched_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_build_runs_scope UNIQUE (id, repository_id),
    CONSTRAINT uk_build_runs_repository_run_attempt UNIQUE (repository_id, github_run_id, run_attempt),
    KEY idx_build_runs_repository_sha_started (repository_id, head_sha, started_at),
    CONSTRAINT fk_build_runs_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_build_runs_attempt CHECK (run_attempt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE deployments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    github_deployment_id BIGINT NOT NULL,
    environment VARCHAR(100) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    environment_url VARCHAR(2048) NULL,
    log_url VARCHAR(2048) NULL,
    deployed_at DATETIME(6) NULL,
    github_status_id BIGINT NULL,
    github_status_at DATETIME(6) NULL,
    last_fetched_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_deployments_scope UNIQUE (id, repository_id),
    CONSTRAINT uk_deployments_repository_github UNIQUE (repository_id, github_deployment_id),
    KEY idx_deployments_repository_environment_date (repository_id, environment, deployed_at),
    CONSTRAINT fk_deployments_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE deployment_pull_requests (
    repository_id BIGINT NOT NULL,
    deployment_id BIGINT NOT NULL,
    pull_request_id BIGINT NOT NULL,
    linked_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (deployment_id, pull_request_id),
    CONSTRAINT fk_deployment_pull_requests_deployment FOREIGN KEY (deployment_id, repository_id)
        REFERENCES deployments (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_deployment_pull_requests_pr FOREIGN KEY (pull_request_id, repository_id)
        REFERENCES pull_requests (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE activities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    repository_id BIGINT NULL,
    actor_account_id BIGINT NULL,
    event_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    occurred_at DATETIME(6) NOT NULL,
    issue_id BIGINT NULL,
    pull_request_id BIGINT NULL,
    build_run_id BIGINT NULL,
    deployment_id BIGINT NULL,
    patch_note_id BIGINT NULL,
    commit_sha VARCHAR(64) NULL,
    branch_name VARCHAR(255) NULL,
    source_url VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_activities_scope UNIQUE (id, project_id),
    CONSTRAINT uk_activities_project_event UNIQUE (project_id, event_key),
    KEY idx_activities_project_occurred (project_id, occurred_at, id),
    KEY idx_activities_repository_occurred (repository_id, occurred_at, id),
    CONSTRAINT fk_activities_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_repository FOREIGN KEY (repository_id, project_id)
        REFERENCES repositories (id, project_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_actor FOREIGN KEY (actor_account_id)
        REFERENCES github_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_issue FOREIGN KEY (issue_id, repository_id)
        REFERENCES issues (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_pr FOREIGN KEY (pull_request_id, repository_id)
        REFERENCES pull_requests (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_build FOREIGN KEY (build_run_id, repository_id)
        REFERENCES build_runs (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_deployment FOREIGN KEY (deployment_id, repository_id)
        REFERENCES deployments (id, repository_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_patch_note FOREIGN KEY (patch_note_id, project_id)
        REFERENCES patch_notes (id, project_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_activities_single_target CHECK (
        (issue_id IS NOT NULL) + (pull_request_id IS NOT NULL)
        + (build_run_id IS NOT NULL) + (deployment_id IS NOT NULL)
        + (patch_note_id IS NOT NULL) <= 1
    ),
    CONSTRAINT ck_activities_repository_target CHECK ((issue_id IS NULL AND pull_request_id IS NULL AND build_run_id IS NULL AND deployment_id IS NULL) OR repository_id IS NOT NULL),
    CONSTRAINT ck_activities_patch_note_scope CHECK (patch_note_id IS NULL OR repository_id IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_notifications_recipient_activity UNIQUE (recipient_user_id, activity_id),
    KEY idx_notifications_recipient_read_created (recipient_user_id, read_at, created_at, id),
    KEY idx_notifications_project_recipient_read (project_id, recipient_user_id, read_at, created_at, id),
    KEY idx_notifications_recipient_created (recipient_user_id, created_at, id),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (project_id, recipient_user_id)
        REFERENCES project_members (project_id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_notifications_activity FOREIGN KEY (activity_id, project_id)
        REFERENCES activities (id, project_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
