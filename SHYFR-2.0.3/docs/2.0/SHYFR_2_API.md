# SHYFR 2.0: API v1

Nagłówek: `Authorization: Bearer <access_token>` (poza rejestracją, logowaniem, odświeżaniem i `/healthz`). Ciało i odpowiedzi: JSON UTF-8. Błędy: `{"error": "opis"}` z kodem HTTP. Nigdy nie zwracamy śladu stosu.

| Metoda i ścieżka | Cel | Uwagi |
|---|---|---|
| `GET /healthz` | stan serwera | |
| `POST /v1/accounts` | rejestracja | `username`, `display_name?`, `auth_secret` (base64, 32–128 B), `device{name, identity_key}`; 201 + tokeny; 409 gdy nazwa zajęta |
| `POST /v1/auth/login` | logowanie | jak wyżej + `device_id` (istniejące) albo `device{…}` (nowe); 401 jednakowo dla złego loginu i sekretu; 429 po blokadzie |
| `POST /v1/auth/refresh` | nowa para tokenów | rotacja; ponowne użycie starego tokenu unieważnia sesję |
| `POST /v1/auth/logout` | koniec sesji | |
| `GET/PATCH /v1/accounts/me` | własny profil | `display_name` ≤40, `bio` ≤300 |
| `GET /v1/accounts/{username}` | profil publiczny | bez identyfikatora konta |
| `DELETE /v1/accounts/me` | usunięcie konta | wymaga `auth_secret`; 403 przy złym |
| `GET /v1/devices`, `DELETE /v1/devices/{id}` | lista i unieważnianie urządzeń | unieważnienie kończy sesje i usuwa kolejkę urządzenia |
| `PUT /v1/keys` | podpisany klucz i do 100 kluczy jednorazowych (łącznie 200) | |
| `GET /v1/keys/{username}` | pakiety kluczy aktywnych urządzeń | klucz jednorazowy jest zużywany; 60/min |
| `PUT/DELETE /v1/blocks/{username}` | blokada | zablokowany nadawca nie jest o tym informowany |
| `POST /v1/messages` | wysłanie szyfrogramu | `recipient_username`, `recipient_device_id`, `client_msg_id` (idempotencja), `payload` (base64 ≤ ~64 kB); 202 nowy, 200 powtórzenie, 409 ten sam klucz z inną treścią |
| `GET /v1/messages/pending?after=&wait=` | kolejka urządzenia | do 100 wiadomości w kolejności + `has_more`; `wait` do 25 s; `after` to tylko kursor stronicowania (po starcie zawsze 0) |
| `POST /v1/messages/ack` `{ids:[..]}` | zbiorcze potwierdzenie (≤100) | wynik per id: ok / expired / not_found; idempotentne; po ACK serwer kasuje szyfrogram |
| `GET /v1/keys/me/count` | stan własnej puli kluczy | `one_time_prekeys`, `low`, `signed_prekey` |
| `POST /v1/messages/{id}/ack` | potwierdzenie odbioru | usuwa szyfrogram z serwera; idempotentne; 410 po wygaśnięciu |
| `POST /v1/messages/{id}/read` | zgłoszenie odczytu | po `ack` |
| `GET /v1/messages/{id}/status` | stan dla nadawcy | `accepted`, `delivered`, `read`, `expired` |

Nie ma jeszcze: WebSocket, mediów, feedu, społeczności, powiadomień FCM, odzyskiwania konta, administracji.


## Zmiany etapu 39
`GET /v1/keys/{user}`: klucz jednorazowy jest rezerwowany na 24 h per pytający, z sufitami (zob. SHYFR_2_BACKEND_HARDENING.md). Logowanie: 429 po 5 błędach z pary (nazwa, adres). Błąd 429 identyczny dla nieistniejących kont.
