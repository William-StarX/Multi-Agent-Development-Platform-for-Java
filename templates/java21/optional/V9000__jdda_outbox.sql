CREATE TABLE jdda_outbox (
    event_id uuid PRIMARY KEY,
    event_type varchar(128) NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX jdda_outbox_pending ON jdda_outbox(created_at,event_id) WHERE published_at IS NULL;

CREATE TABLE jdda_delivered_event (
    event_id uuid PRIMARY KEY,
    event_type varchar(128) NOT NULL,
    delivered_at timestamptz NOT NULL DEFAULT now()
);
