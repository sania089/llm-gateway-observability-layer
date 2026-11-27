-- Only the SHA-256 hash of a key is stored; the raw key is shown once at creation.
-- Limit columns are nullable (null = no per-key limit) and are enforced from Day 7.
CREATE TABLE api_keys (
    id                    uuid         PRIMARY KEY,
    key_hash              varchar(64)  NOT NULL UNIQUE,
    key_prefix            varchar(12)  NOT NULL,
    owner                 varchar(100) NOT NULL,
    status                varchar(10)  NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    requests_per_minute   integer,
    monthly_budget_micros bigint,
    created_at            timestamptz  NOT NULL
);

-- One row per gateway request. Prompts and completions are never stored here.
CREATE TABLE request_logs (
    id                bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at        timestamptz  NOT NULL,
    api_key_id        uuid         NOT NULL REFERENCES api_keys (id),
    model             varchar(100) NOT NULL,
    provider          varchar(50),
    latency_ms        integer      NOT NULL,
    status            integer      NOT NULL,
    prompt_tokens     integer,
    completion_tokens integer,
    total_tokens      integer,
    cache_hit         boolean      NOT NULL DEFAULT false,
    fallback_used     boolean      NOT NULL DEFAULT false,
    error_code        varchar(60)
);

CREATE INDEX idx_request_logs_created_at ON request_logs (created_at);
CREATE INDEX idx_request_logs_api_key_id ON request_logs (api_key_id);
CREATE INDEX idx_request_logs_model ON request_logs (model);
