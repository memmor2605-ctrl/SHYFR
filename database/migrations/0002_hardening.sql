-- SHYFR 2.0 etap 39: utwardzanie. (1) rezerwacje kluczy jednorazowych, (2) wspólny magazyn ograniczeń żądań.

-- Rezerwacja: ten sam pytający dostaje przez OTK_CLAIM_TTL ten sam klucz jednorazowy danego urządzenia,
-- więc ponowne pobranie (np. po utracie odpowiedzi) nie zużywa kolejnego klucza.
CREATE TABLE IF NOT EXISTS otk_claims (
    requester  TEXT    NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id  TEXT    NOT NULL REFERENCES devices(id)  ON DELETE CASCADE,
    key_id     INTEGER NOT NULL,
    key        TEXT    NOT NULL,
    claimed_at INTEGER NOT NULL,
    PRIMARY KEY (requester, device_id)
);
CREATE INDEX IF NOT EXISTS idx_otk_claims_device ON otk_claims(device_id, claimed_at);

CREATE TABLE IF NOT EXISTS rate_events (
    key TEXT    NOT NULL,
    ts  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rate_events ON rate_events(key, ts);
