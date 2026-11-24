-- Снимок сохраняется до подтверждения завершения живого этапа.
CREATE TABLE finalization_snapshot (
    interview_id UUID PRIMARY KEY REFERENCES archive_interview(id),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Задание относится к одной закреплённой версии интервью и переживает перезапуск обработчика.
CREATE TABLE finalization_job (
    interview_id UUID PRIMARY KEY REFERENCES archive_interview(id),
    state TEXT NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'running', 'retry_wait', 'ready', 'failed')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    error_code TEXT,
    snapshot JSONB NOT NULL,
    input_manifest JSONB NOT NULL,
    result_manifest JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((state = 'running' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'running' AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK (state <> 'retry_wait' OR next_attempt_at IS NOT NULL),
    CHECK (state <> 'ready' OR result_manifest IS NOT NULL)
);

CREATE INDEX finalization_job_pending_idx ON finalization_job (next_attempt_at, created_at)
    WHERE state IN ('pending', 'retry_wait');

CREATE INDEX finalization_job_lease_idx ON finalization_job (lease_until)
    WHERE state = 'running';
