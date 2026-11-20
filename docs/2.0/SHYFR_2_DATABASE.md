# SHYFR 2.0: baza danych

Schemat: `database/migrations/0001_init.sql` (SQLite w serwerze referencyjnym, napisany pod przeniesienie na PostgreSQL: `BLOB`→`BYTEA`, `AUTOINCREMENT`→`BIGSERIAL`, indeks częściowy już zgodny). Migracje są numerowane (`NNNN_nazwa.sql`), stosowane w kolejności i zapisywane w `schema_migrations`; test sprawdza, że wielokrotne uruchomienie jest bezpieczne.

| Tabela | Cel | Dane wrażliwe |
|---|---|---|
| `accounts` | konto: stały `id`, publiczna `username`, nazwa, opis | skrót scrypt sekretu, sól; **nie** hasło |
| `devices` | urządzenia i ich publiczny klucz tożsamości | tylko klucz publiczny |
| `sessions` | sesje | skróty tokenów, nie tokeny |
| `signed_prekeys`, `one_time_prekeys` | katalog kluczy publicznych do zakładania sesji E2EE | tylko klucze publiczne |
| `messages` | kolejka i stan wiadomości | `payload` = szyfrogram (NULL po doręczeniu/wygaśnięciu) |
| `blocks` | blokady | relacja kont |

## Retencja
- Szyfrogram: do potwierdzenia odbioru albo wygaśnięcia (domyślnie 30 dni), potem `payload` jest zerowany.
- Sam stan wiadomości (bez treści): 7 dni po doręczeniu lub wygaśnięciu, potem wiersz jest usuwany.
- Sesje: usuwane po wygaśnięciu tokenu odświeżania albo 7 dni po unieważnieniu.
- Konto usunięte: kaskadowo urządzenia, sesje, klucze, blokady; wiadomości z udziałem konta usuwane w tej samej transakcji.

## Do zrobienia przed produkcją
PostgreSQL z rolami o minimalnych uprawnieniach, kopie zaszyfrowane i testowane przywracanie, indeksy pod rzeczywiste zapytania (do zmierzenia), tabele feedu, społeczności, mediów, tokenów FCM i moderacji (brak w 0001).
