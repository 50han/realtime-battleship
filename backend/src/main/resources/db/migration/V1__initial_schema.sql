-- V1: user accounts, match history, per-shot move log.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    wins          INTEGER     NOT NULL DEFAULT 0,
    losses        INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ
);

-- Usernames are case-insensitively unique without needing the citext extension.
CREATE UNIQUE INDEX users_username_lower_key ON users (lower(username));

CREATE TABLE matches (
    id            UUID        PRIMARY KEY,
    player1_id    BIGINT      NOT NULL REFERENCES users (id),
    player2_id    BIGINT      NOT NULL REFERENCES users (id),
    winner_id     BIGINT      REFERENCES users (id),
    -- IN_PROGRESS | COMPLETED | FORFEITED | ABANDONED
    status        TEXT        NOT NULL,
    total_shots   INTEGER     NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at      TIMESTAMPTZ,
    CONSTRAINT matches_distinct_players CHECK (player1_id <> player2_id)
);

CREATE INDEX matches_player1_started_idx ON matches (player1_id, started_at DESC);
CREATE INDEX matches_player2_started_idx ON matches (player2_id, started_at DESC);

CREATE TABLE match_moves (
    id         BIGSERIAL   PRIMARY KEY,
    match_id   UUID        NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    move_no    INTEGER     NOT NULL,
    shooter_id BIGINT      NOT NULL REFERENCES users (id),
    row_idx    SMALLINT    NOT NULL,
    col_idx    SMALLINT    NOT NULL,
    hit        BOOLEAN     NOT NULL,
    sunk_ship  TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT match_moves_unique_no UNIQUE (match_id, move_no)
);

CREATE INDEX match_moves_match_idx ON match_moves (match_id, move_no);
