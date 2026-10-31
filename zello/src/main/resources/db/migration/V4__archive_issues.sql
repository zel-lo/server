ALTER TABLE issues ADD COLUMN archived_at DATETIME(6) NULL;
ALTER TABLE issues ADD COLUMN creation_hash VARCHAR(64) NULL;
CREATE INDEX idx_issues_repository_archived ON issues (repository_id, archived_at);
