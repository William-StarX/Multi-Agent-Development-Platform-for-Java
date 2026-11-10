CREATE TABLE jdda.repository_binding (
    project_id varchar(128) NOT NULL REFERENCES jdda.project ON DELETE CASCADE,
    repo_id varchar(128) NOT NULL,
    body jsonb NOT NULL,
    PRIMARY KEY (project_id,repo_id)
);
CREATE TABLE jdda.ci_evidence (
    project_id varchar(128) NOT NULL,
    repo_id varchar(128) NOT NULL,
    candidate_sha varchar(64) NOT NULL,
    body jsonb NOT NULL,
    PRIMARY KEY (project_id,repo_id,candidate_sha),
    FOREIGN KEY (project_id,repo_id) REFERENCES jdda.repository_binding ON DELETE CASCADE
);
