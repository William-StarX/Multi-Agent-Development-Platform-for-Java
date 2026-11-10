ALTER TABLE jdda.agent_credential ADD COLUMN execution_profile jsonb;

ALTER TABLE jdda.task_record ADD COLUMN repair_source_attempt_id varchar(128),
    ADD FOREIGN KEY (project_id, repair_source_attempt_id) REFERENCES jdda.attempt(project_id, attempt_id);
