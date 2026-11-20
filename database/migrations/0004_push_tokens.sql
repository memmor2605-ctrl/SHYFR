-- SHYFR 2.0 etap 41: token powiadomień push urządzenia (FCM). Serwer przechowuje wyłącznie token;
-- sygnał budzący nie zawiera treści, nadawcy ani identyfikatora rozmowy.
CREATE TABLE IF NOT EXISTS push_tokens (
    device_id  TEXT    PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    provider   TEXT    NOT NULL,
    token      TEXT    NOT NULL,
    updated_at INTEGER NOT NULL
);
