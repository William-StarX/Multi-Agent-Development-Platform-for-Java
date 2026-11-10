CREATE TABLE jdda.integration_release (
    project_id varchar(128) NOT NULL,
    candidate_sha varchar(40) NOT NULL,
    body jsonb NOT NULL,
    PRIMARY KEY (project_id, candidate_sha),
    FOREIGN KEY (project_id, candidate_sha) REFERENCES jdda.integration_candidate ON DELETE CASCADE
);
