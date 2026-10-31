ALTER TABLE project_members ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_project_invitations_created ON project_invitations (project_id, created_at, id);
CREATE INDEX idx_project_invitations_pending ON project_invitations (project_id, invited_by_user_id, accepted_at, revoked_at, expires_at);
