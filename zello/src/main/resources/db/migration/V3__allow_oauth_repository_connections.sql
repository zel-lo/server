-- Preserve existing GitHub App links while allowing real OAuth links without a fabricated installation.
ALTER TABLE repositories
    MODIFY installation_id BIGINT NULL,
    ADD COLUMN authorization_method VARCHAR(20) NOT NULL DEFAULT 'GITHUB_APP' AFTER installation_id,
    ADD CONSTRAINT ck_repositories_authorization CHECK (
        (authorization_method = 'GITHUB_APP' AND installation_id IS NOT NULL)
        OR (authorization_method = 'OAUTH' AND installation_id IS NULL)
    );
