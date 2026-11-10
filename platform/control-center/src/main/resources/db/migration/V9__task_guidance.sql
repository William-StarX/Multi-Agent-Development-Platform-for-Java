CREATE TABLE jdda.task_guidance (
    project_id varchar(128) NOT NULL,
    task_id varchar(128) NOT NULL,
    revision bigint NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    instruction varchar(1200) NOT NULL,
    actor varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, task_id, revision, version),
    FOREIGN KEY (project_id, task_id, revision) REFERENCES jdda.task_record ON DELETE CASCADE
);
