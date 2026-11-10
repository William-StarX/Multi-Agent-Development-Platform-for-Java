CREATE TABLE jdda.project (
    project_id varchar(128) PRIMARY KEY,
    topology_version bigint NOT NULL DEFAULT 1 CHECK (topology_version > 0)
);

CREATE TABLE jdda.agent (
    project_id varchar(128) NOT NULL REFERENCES jdda.project,
    agent_id varchar(128) NOT NULL,
    parent_id varchar(128),
    body jsonb NOT NULL,
    PRIMARY KEY (project_id, agent_id),
    FOREIGN KEY (project_id, parent_id) REFERENCES jdda.agent(project_id, agent_id),
    CHECK (parent_id IS DISTINCT FROM agent_id)
);
CREATE UNIQUE INDEX one_root ON jdda.agent(project_id) WHERE body->'roles' ? 'ROOT';
CREATE UNIQUE INDEX one_library ON jdda.agent(project_id) WHERE body->'roles' ? 'LIBRARY';
CREATE UNIQUE INDEX one_bugfix ON jdda.agent(project_id) WHERE body->'roles' ? 'BUGFIX';

CREATE TABLE jdda.task_record (
    index_id bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    project_id varchar(128) NOT NULL REFERENCES jdda.project,
    task_id varchar(128) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    package jsonb NOT NULL,
    snapshot jsonb NOT NULL,
    PRIMARY KEY (project_id, task_id, revision)
);
CREATE INDEX task_library_page ON jdda.task_record(project_id, index_id);

CREATE TABLE jdda.request_receipt (
    project_id varchar(128) NOT NULL REFERENCES jdda.project,
    operation text NOT NULL,
    request_key varchar(128) NOT NULL,
    request_body jsonb NOT NULL,
    response jsonb NOT NULL,
    PRIMARY KEY (project_id, operation, request_key)
);
