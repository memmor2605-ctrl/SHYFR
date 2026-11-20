-- SHYFR 2.0 etap 40: prekeye post-kwantowe (PQXDH) i identyfikator podpisanego prekeya.
-- Zgodnie ze specyfikacją PQXDH serwer przechowuje podpisany prekey KEM "ostatniej szansy" oraz podpisane jednorazowe prekeye KEM, każdy z identyfikatorem.
ALTER TABLE signed_prekeys ADD COLUMN key_id INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS kyber_prekeys (
    device_id   TEXT    NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_id      INTEGER NOT NULL,
    key         TEXT    NOT NULL,
    signature   TEXT    NOT NULL,
    last_resort INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, key_id)
);

CREATE TABLE IF NOT EXISTS kyber_claims (
    requester  TEXT    NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id  TEXT    NOT NULL REFERENCES devices(id)  ON DELETE CASCADE,
    key_id     INTEGER NOT NULL,
    key        TEXT    NOT NULL,
    signature  TEXT    NOT NULL,
    claimed_at INTEGER NOT NULL,
    PRIMARY KEY (requester, device_id)
);
CREATE INDEX IF NOT EXISTS idx_kyber_claims_device ON kyber_claims(device_id, claimed_at);
