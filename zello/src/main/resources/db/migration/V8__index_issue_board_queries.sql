CREATE INDEX idx_issues_board ON issues (repository_id, archived_at, work_status, board_order, id);
