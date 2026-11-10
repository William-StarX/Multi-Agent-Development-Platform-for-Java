CREATE TABLE jdda.agent_credential (
    token_hash varchar(64) PRIMARY KEY,
    project_id varchar(128) NOT NULL,
    agent_id varchar(128) NOT NULL,
    FOREIGN KEY (project_id, agent_id) REFERENCES jdda.agent(project_id, agent_id) ON DELETE CASCADE
);
CREATE TABLE jdda.attempt (
    project_id varchar(128) NOT NULL,
    attempt_id varchar(128) NOT NULL,
    task_id varchar(128) NOT NULL,
    revision bigint NOT NULL,
    worker_id varchar(128) NOT NULL,
    body jsonb NOT NULL,
    lease jsonb NOT NULL,
    result jsonb,
    PRIMARY KEY (project_id, attempt_id),
    FOREIGN KEY (project_id, task_id, revision) REFERENCES jdda.task_record ON DELETE CASCADE
);
CREATE TABLE jdda.event (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id varchar(128) NOT NULL REFERENCES jdda.project ON DELETE CASCADE,
    task_id varchar(128) NOT NULL,
    revision bigint NOT NULL,
    body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX event_project_cursor ON jdda.event(project_id, event_id);
