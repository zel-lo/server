ALTER TABLE work_changes ADD CONSTRAINT uk_work_changes_scope UNIQUE (id, project_id);

CREATE TABLE patch_note_change_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    project_id BIGINT NOT NULL,
    patch_note_id BIGINT NOT NULL,
    work_change_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    category VARCHAR(20) NOT NULL,
    title_written BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_note_change_link UNIQUE (patch_note_id, work_change_id),
    CONSTRAINT fk_note_change_link_note FOREIGN KEY (patch_note_id, project_id) REFERENCES patch_notes (id, project_id),
    CONSTRAINT fk_note_change_link_source FOREIGN KEY (work_change_id, project_id) REFERENCES work_changes (id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
