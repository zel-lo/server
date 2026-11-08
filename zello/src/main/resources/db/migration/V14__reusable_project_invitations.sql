-- Existing links retain their original single-use behavior.
ALTER TABLE project_invitations
    ADD COLUMN reusable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN use_count INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_project_invitations_use_count CHECK (use_count >= 0),
    ADD CONSTRAINT ck_project_invitations_reusable CHECK (
        reusable = FALSE OR (granted_role IN ('MEMBER', 'VIEWER') AND target_account_id IS NULL AND accepted_at IS NULL)
    );

UPDATE project_invitations SET use_count = 1 WHERE accepted_at IS NOT NULL;

CREATE TABLE project_invitation_uses (
    invitation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (invitation_id, user_id),
    CONSTRAINT fk_invitation_uses_invitation FOREIGN KEY (invitation_id) REFERENCES project_invitations(id),
    CONSTRAINT fk_invitation_uses_user FOREIGN KEY (user_id) REFERENCES users(id)
);

INSERT INTO project_invitation_uses (invitation_id, user_id, accepted_at)
SELECT id, accepted_by_user_id, accepted_at FROM project_invitations WHERE accepted_at IS NOT NULL;
