CREATE TABLE catalog_version (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    digest CHAR(64) NOT NULL UNIQUE,
    questions_tsv TEXT NOT NULL,
    followups_tsv TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE catalog_current (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    version_id BIGINT NOT NULL REFERENCES catalog_version(id)
);
CREATE TABLE catalog_question (
    version_id BIGINT NOT NULL REFERENCES catalog_version(id),
    question_id TEXT NOT NULL,
    position INTEGER NOT NULL CHECK (position > 0),
    level TEXT NOT NULL, topic TEXT NOT NULL, question TEXT NOT NULL,
    reference TEXT NOT NULL, notes TEXT NOT NULL,
    PRIMARY KEY (version_id, question_id), UNIQUE (version_id, position)
);
CREATE TABLE catalog_followup (
    version_id BIGINT NOT NULL,
    followup_id TEXT NOT NULL, question_id TEXT NOT NULL,
    position INTEGER NOT NULL CHECK (position > 0),
    question TEXT NOT NULL, reference TEXT NOT NULL, notes TEXT NOT NULL,
    PRIMARY KEY (version_id, followup_id),
    UNIQUE (version_id, question_id, position),
    FOREIGN KEY (version_id, question_id) REFERENCES catalog_question(version_id, question_id)
);
CREATE TABLE archive_interview (
    id UUID PRIMARY KEY,
    catalog_version_id BIGINT NOT NULL REFERENCES catalog_version(id),
    call_status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE archive_artifact (
    interview_id UUID NOT NULL REFERENCES archive_interview(id),
    kind TEXT NOT NULL, object_key TEXT NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (interview_id, kind)
);
