ALTER TABLE jdda.project ADD COLUMN dispatch_paused boolean NOT NULL DEFAULT false,
    ADD COLUMN dispatch_version bigint NOT NULL DEFAULT 1 CHECK (dispatch_version > 0);
