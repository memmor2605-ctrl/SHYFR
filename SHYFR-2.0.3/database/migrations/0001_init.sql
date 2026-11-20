-- SHYFR 2.0: schemat początkowy. Napisany tak, by dał się przenieść na PostgreSQL (BYTEA zamiast BLOB, BIGSERIAL zamiast AUTOINCREMENT).
CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL);

CREATE TABLE IF NOT EXISTS accounts (
    id            TEXT PRIMARY KEY,                 -- stały, wewnętrzny identyfikator (nie zmienia się przy zmianie nazwy)
    username      TEXT NOT NULL UNIQUE,             -- publiczna nazwa, małe litery
    display_name  TEXT NOT NULL,
    bio           TEXT NOT NULL DEFAULT '',
    status        TEXT NOT NULL DEFAULT 'active',
    created_at    INTEGER NOT NULL,
    auth_salt     TEXT NOT NULL,
    auth_hash     TEXT NOT NULL,                    -- scrypt(sekret uwierzytelnienia wyprowadzony na urządzeniu); hasło nigdy nie trafia na serwer
    failed_logins INTEGER NOT NULL DEFAULT 0,
    locked_until  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS devices (
    id           TEXT PRIMARY KEY,
    account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    identity_key TEXT NOT NULL,                     -- klucz publiczny tożsamości urządzenia (base64)
    created_at   INTEGER NOT NULL,
    last_seen    INTEGER NOT NULL,
    revoked      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_id);

CREATE TABLE IF NOT EXISTS sessions (
    id                 TEXT PRIMARY KEY,
    account_id         TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id          TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    access_hash        TEXT NOT NULL,               -- SHA-256 tokenu; sam token nie jest przechowywany
    access_exp         INTEGER NOT NULL,
    refresh_hash       TEXT NOT NULL,
    refresh_exp        INTEGER NOT NULL,
    prev_refresh_hash  TEXT NOT NULL DEFAULT '',    -- do wykrywania ponownego użycia starego tokenu odświeżania
    revoked            INTEGER NOT NULL DEFAULT 0,
    created_at         INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_access ON sessions(access_hash);
CREATE INDEX IF NOT EXISTS idx_sessions_refresh ON sessions(refresh_hash);

CREATE TABLE IF NOT EXISTS signed_prekeys (
    device_id  TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    key        TEXT NOT NULL,
    signature  TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS one_time_prekeys (
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_id    INTEGER NOT NULL,
    key       TEXT NOT NULL,
    PRIMARY KEY (device_id, key_id)
);

CREATE TABLE IF NOT EXISTS messages (
    seq               INTEGER PRIMARY KEY AUTOINCREMENT,   -- PostgreSQL: BIGSERIAL; kolejność doręczania dla odbiorcy
    id                TEXT NOT NULL UNIQUE,
    sender_account    TEXT NOT NULL,
    sender_device     TEXT NOT NULL,
    recipient_account TEXT NOT NULL,
    recipient_device  TEXT NOT NULL,
    client_msg_id     TEXT NOT NULL,                       -- klucz idempotencji ustawiany przez nadawcę
    payload           TEXT,                                -- zaszyfrowana treść (base64); NULL po doręczeniu lub wygaśnięciu
    created_at        INTEGER NOT NULL,
    expires_at        INTEGER NOT NULL,
    state             TEXT NOT NULL,                       -- accepted | delivered | read | expired
    changed_at        INTEGER NOT NULL,
    dropped           INTEGER NOT NULL DEFAULT 0,          -- 1: odbiorca zablokował nadawcę (nadawca nie jest o tym informowany)
    UNIQUE (sender_account, client_msg_id)
);
CREATE INDEX IF NOT EXISTS idx_messages_inbox ON messages(recipient_device, seq) WHERE payload IS NOT NULL AND dropped = 0;

CREATE TABLE IF NOT EXISTS blocks (
    blocker TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    blocked TEXT NOT NULL,
    PRIMARY KEY (blocker, blocked)
);
