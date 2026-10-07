CREATE SCHEMA IF NOT EXISTS agent_runtime;

CREATE TABLE IF NOT EXISTS agent_runtime.conversations (
    id varchar(128) PRIMARY KEY,
    user_id bigint NOT NULL,
    title varchar(300),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_runtime.runs (
    request_id varchar(128) PRIMARY KEY,
    conversation_id varchar(128) NOT NULL REFERENCES agent_runtime.conversations(id),
    user_id bigint NOT NULL,
    status varchar(32) NOT NULL,
    request_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    route jsonb,
    evidence jsonb NOT NULL DEFAULT '[]'::jsonb,
    errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE IF NOT EXISTS agent_runtime.handoffs (
    id bigserial PRIMARY KEY,
    request_id varchar(128) NOT NULL REFERENCES agent_runtime.runs(request_id),
    conversation_id varchar(128) NOT NULL REFERENCES agent_runtime.conversations(id),
    user_id bigint NOT NULL,
    reason varchar(1000) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'pending',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_runtime.handoff_audit (
    id bigserial PRIMARY KEY,
    handoff_id bigint NOT NULL REFERENCES agent_runtime.handoffs(id),
    actor_user_id bigint NOT NULL,
    from_status varchar(32) NOT NULL,
    to_status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_runs_conversation
    ON agent_runtime.runs(conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_handoffs_user
    ON agent_runtime.handoffs(user_id, created_at DESC);
