ALTER TABLE issues ADD COLUMN board_order BIGINT NOT NULL DEFAULT 0;
ALTER TABLE issues ADD COLUMN planned_week_start DATE NULL;
ALTER TABLE issues ADD COLUMN completed_at DATETIME(6) NULL;
UPDATE issues SET planned_week_start = DATE_SUB(DATE(created_at), INTERVAL WEEKDAY(created_at) DAY);
UPDATE issues SET completed_at = COALESCE(github_updated_at, updated_at) WHERE work_status = 'DONE';
ALTER TABLE issues MODIFY COLUMN planned_week_start DATE NOT NULL;
CREATE INDEX idx_issues_week ON issues (planned_week_start, work_status, completed_at);
