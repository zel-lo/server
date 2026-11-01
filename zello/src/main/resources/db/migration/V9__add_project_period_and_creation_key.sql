ALTER TABLE projects ADD COLUMN start_date DATE NULL;
ALTER TABLE projects ADD COLUMN end_date DATE NULL;
ALTER TABLE projects ADD COLUMN creation_request_key VARCHAR(64) NULL;
ALTER TABLE projects ADD CONSTRAINT uk_projects_creation_request UNIQUE (creation_request_key);
ALTER TABLE projects ADD CONSTRAINT ck_projects_period CHECK ((start_date IS NULL AND end_date IS NULL) OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date));
