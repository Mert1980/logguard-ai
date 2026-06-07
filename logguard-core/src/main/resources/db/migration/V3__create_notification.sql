CREATE TABLE notification (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- ErrorLog fields
    error_exception_type    VARCHAR(500)             NOT NULL,
    error_message           TEXT,
    error_stack_trace       TEXT,
    error_service_name      VARCHAR(255),
    error_app_name          VARCHAR(255),
    error_team              VARCHAR(255),
    error_environment       VARCHAR(255),
    error_severity          VARCHAR(50),
    error_occurred_at       TIMESTAMP WITH TIME ZONE,

    -- LLMAnalysis fields
    llm_available           BOOLEAN                  NOT NULL,
    llm_summary             TEXT,
    llm_root_cause          TEXT,
    llm_suggested_fix       TEXT,
    llm_unavailability_reason VARCHAR(1000),

    -- GitLab links (JSON array)
    gitlab_links_json       TEXT,

    -- Notification state
    status                  VARCHAR(50)              NOT NULL,
    attempt_count           INT                      NOT NULL DEFAULT 0,
    failure_reason          TEXT,
    persisted_at            TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_status_persisted
    ON notification (status, persisted_at);
