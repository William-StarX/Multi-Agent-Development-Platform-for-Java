CREATE INDEX task_claim_queue ON jdda.task_record(project_id, index_id)
    WHERE snapshot->>'state' IN ('READY','WAITING_DEPENDENCIES')
       OR (snapshot->>'state'='BLOCKED' AND snapshot->>'currentAttemptId' IS NULL);

CREATE INDEX active_attempt_host ON jdda.attempt((body->>'hostId'))
    WHERE body->>'state' IN ('ALLOCATED','RUNNING','EXPIRED');

CREATE INDEX active_attempt_worker ON jdda.attempt(project_id,worker_id)
    WHERE body->>'state' IN ('ALLOCATED','RUNNING','EXPIRED');

CREATE INDEX active_attempt_lease ON jdda.attempt(project_id)
    WHERE lease->>'status'='ACTIVE';

CREATE INDEX event_task_cursor ON jdda.event(project_id,task_id,revision,event_id);
