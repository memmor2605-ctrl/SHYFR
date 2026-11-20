# SHYFR 2.0: semantyka dostarczania wiadomości

## Stany i kiedy serwer może skasować szyfrogram
`accepted` (serwer ma szyfrogram w kolejce odbiorcy) → `delivered` (odbiorca zrobił ACK; **szyfrogram usunięty z serwera, nieodwracalnie**) → `read` (potwierdzenie przeczytania, opcjonalne). Poza tym `expired` (TTL, domyślnie 30 dni, szyfrogram usuwany) i odrzucenie przez blokadę (nadawca widzi `accepted`).

**Zasada: serwer kasuje szyfrogram wyłącznie po ACK od urządzenia-adresata albo po wygaśnięciu TTL/odwołaniu urządzenia.** Samo pobranie (`pending`) nigdy nie kasuje; wiadomość czeka, aż klient potwierdzi.

## Obowiązek klienta: zapis przed ACK
ACK znaczy "mam szyfrogram **trwale zapisany**". Klient (`MessageTransport.syncInbox`):
1. pobiera `GET /v1/messages/pending?after=0` (po starcie i po każdym połączeniu zawsze od 0; `after` to tylko kursor stronicowania, **nie** potwierdzenie),
2. zapisuje WSZYSTKIE szyfrogramy z strony do lokalnej skrzynki (atomowy zapis pliku),
3. dopiero wtedy wysyła zbiorcze ACK,
4. odszyfrowuje osobno, po ACK. Błąd odszyfrowania nie traci wiadomości (szyfrogram jest lokalnie), co jest istotne, bo ratchet zmienia stan przy odszyfrowaniu.

Scenariusze awarii:
| Awaria | Skutek |
|---|---|
| klient padł po pobraniu, przed zapisem | wiadomość nadal na serwerze, przyjdzie ponownie |
| padł po zapisie, przed ACK | przyjdzie ponownie, klient odrzuca duplikat po `id` i potwierdza |
| ACK zgubiony w sieci | j.w.; ACK jest idempotentny |
| ACK po wygaśnięciu | `410` / wynik `expired`: wiadomość utracona po TTL (nieuniknione) |
| klient potwierdził późniejszą, nie pierwszą | pierwsza nie ginie przy odpytaniu od `after=0` (test) |
| sesja wygasła | wiadomości zostają w kolejce; po odświeżeniu tokenu/zalogowaniu wracają (test) |
| urządzenie odwołane | jego kolejka jest kasowana (`expired`); inne urządzenia konta zachowują swoje |

## Wysyłanie
Klient nadaje `client_msg_id` przy kolejkowaniu i **zawsze** wysyła z tym samym; zerwanie połączenia i ponowienie nie tworzy duplikatu (`200` ze starym `id`; także po ACK odbiorcy; także współbieżnie, test: 8 równoległych = 1 wiadomość). Ten sam id z inną treścią = `409`. Kolejność nadawcy zachowana (pierwszy błąd przejściowy zatrzymuje wysyłkę; ważne dla ratchetu). Błędy `400/404/409/413` = wiadomość `FAILED` bez ponawiania.

## Do zrobienia
Długie odpytywanie jest w obrębie jednego wątku HTTP na klienta; docelowo WebSocket + FCM jako sygnał "sprawdź kolejkę". Test na prawdziwych urządzeniach: zob. `SHYFR_2_ANDROID_CLIENT.md`.
