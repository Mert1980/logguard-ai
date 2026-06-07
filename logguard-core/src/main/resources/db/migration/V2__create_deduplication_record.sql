CREATE TABLE deduplication_record (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exception_type       VARCHAR(500)            NOT NULL,
    throwing_method      VARCHAR(500)            NOT NULL,
    stack_trace_sequence TEXT                    NOT NULL,
    first_seen_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_dedup_exception_method_expires
    ON deduplication_record (exception_type, throwing_method, expires_at);
