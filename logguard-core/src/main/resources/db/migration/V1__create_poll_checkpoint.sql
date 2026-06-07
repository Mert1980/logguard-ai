CREATE TABLE poll_checkpoint (
    id                        BIGINT          NOT NULL PRIMARY KEY,
    last_successful_poll_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    consecutive_poll_failures INT             NOT NULL DEFAULT 0
);
