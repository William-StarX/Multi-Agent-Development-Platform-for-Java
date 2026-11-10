CREATE TABLE jdda.integration_candidate (
    project_id varchar(128) NOT NULL REFERENCES jdda.project ON DELETE CASCADE,
    candidate_sha varchar(40) NOT NULL,
    body jsonb NOT NULL,
    PRIMARY KEY (project_id, candidate_sha)
);
