# SHYFR 2.0: architektura

Stan: **projekt + fundament serwera**. Aplikacja na Androida nadal jest wersją 1.x (z Torem). Ten dokument odróżnia to, co działa i było uruchomione, od tego, co dopiero zaplanowano.

## Założenia
Centralnie prowadzony serwer (modularny monolit), połączenia HTTPS/TLS, konta pseudonimowe bez e-maila i telefonu, prywatne wiadomości szyfrowane end-to-end (serwer widzi tylko szyfrogram), publiczne treści chronione kontrolą dostępu po stronie serwera (nie szyfrowane).

## Moduły
| Moduł | Stan |
|---|---|
| Uwierzytelnianie i konta | **zaimplementowane i przetestowane** (`backend/shyfr_server/server.py`) |
| Profile | podstawowe (nazwa, opis); awatary: brak |
| Wiadomości i kolejka offline | **zaimplementowane i przetestowane** (stany: accepted, delivered, read, expired; idempotencja; kolejność; wygasanie; blokady) |
| Warstwa E2EE | **tylko serwerowa część**: katalog kluczy (tożsamość, podpisany klucz, klucze jednorazowe). Protokół po stronie klienta: **niewybrany** (patrz SECURITY) |
| Feed, społeczności | **brak** w serwerze 2.0 (w 1.x: Nostr i przekaźnik SHYFR) |
| Media i załączniki | **brak** (limit 64 kB szyfrogramu na wiadomość; pliki wymagają osobnej usługi z magazynem S3) |
| Powiadomienia | **brak** (FCM niezaimplementowane; długie odpytywanie `GET /v1/messages/pending?wait=` działa) |
| Moderacja | blokowanie użytkowników; reszta brak |
| Kopia i odzyskiwanie | brak w 2.0 (w 1.x: koperta klucza głównego) |
| Usuwanie konta | **zaimplementowane i przetestowane** (konto, urządzenia, sesje, klucze, kolejki) |
| Administracja i monitoring | tylko `GET /healthz` |

## Przepływ prywatnej wiadomości
1. Nadawca pobiera pakiety kluczy odbiorcy (`GET /v1/keys/{username}`; klucz jednorazowy jest zużywany).
2. Klient szyfruje wiadomość (protokół do wyboru) i wysyła szyfrogram z kluczem idempotencji (`POST /v1/messages`).
3. Serwer zapisuje szyfrogram w trwałej kolejce urządzenia odbiorcy (stan `accepted`).
4. Odbiorca pobiera (`GET /v1/messages/pending`), odszyfrowuje lokalnie i potwierdza (`POST …/ack`): stan `delivered`, a **szyfrogram jest usuwany z serwera**.
5. Opcjonalnie odbiorca zgłasza odczyt (`…/read`). Niepotwierdzone wiadomości wygasają po `SHYFR_MESSAGE_TTL` (domyślnie 30 dni).
„Dostarczono" znaczy wyłącznie, że urządzenie odbiorcy potwierdziło odbiór. Przyjęcie przez serwer to osobny stan „accepted".

## Czego ten fundament nie rozwiązuje
- **TLS**: serwer mówi zwykłym HTTP i musi stać za reverse proxy (Caddy, nginx). Bez proxy nie wolno go wystawiać do internetu.
- **WebSocket i FCM**: brak. Klient może odpytywać z długim oczekiwaniem, ale na Androidzie bez FCM nie ma niezawodnego budzenia aplikacji w tle (Doze). To osobna praca.
- **Skalowanie**: SQLite, jeden proces, ograniczenia żądań w pamięci. Do produkcji potrzebny PostgreSQL i wspólny magazyn ograniczeń.
- **Stos produkcyjny**: serwer referencyjny jest w Pythonie ze standardowej biblioteki, bo tylko taki dało się tu uruchomić i przetestować. Plan zakłada przeniesienie na wybrany stos (Ktor lub Go) z PostgreSQL; **zestaw testów jest wykonywalną specyfikacją**, którą przeniesiony serwer musi przejść.

## Infrastruktura, którą musi zapewnić właściciel
VPS (np. 1–2 GB RAM), domena z rekordem DNS, certyfikat TLS (Let's Encrypt przez Caddy), konto Firebase (FCM) dla powiadomień, magazyn obiektów zgodny z S3 dla mediów, PostgreSQL (zarządzany lub własny), kopie bazy, monitoring dostępności. To są stałe koszty i obowiązki operatora (patrz `SHYFR_2_SECURITY.md`). Nie zakładam darmowej infrastruktury.
