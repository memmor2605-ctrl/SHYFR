# SHYFR 2.0: utwardzenie serwera (etap 39)

Każda poprawka ma testy regresyjne w `backend/tests/test_hardening.py` i `test_ratelimit.py`. Sprawdziłem też, że testy wykrywają regresję (celowe usunięcie rollbacku, ochron kluczy jednorazowych i sprawdzania zaufanego proxy powodowało czerwone testy).

## 1. Wyczerpywanie kluczy jednorazowych (OTK)
**Luka:** każde `GET /v1/keys/{user}` usuwało po jednym kluczu na urządzenie, więc dowolny zalogowany konto mogło opróżnić pulę ofiary (stary test wprost to zakładał).
**Naprawa:**
* *Rezerwacja:* ten sam pytający dostaje przez 24 h **ten sam** klucz urządzenia (tabela `otk_claims`); powtórne pobranie nic nie zużywa (to też ratuje przypadek utraty odpowiedzi).
* *Sufit na urządzenie:* najwyżej 10 kluczy jednego urządzenia na godzinę dla wszystkich pytających łącznie. Dalej zwracany jest sam podpisany prekey (wymiana kluczy działa, słabsza jest tylko ochrona przed powtórzeniem pierwszej wiadomości).
* *Sufit na pytającego:* najwyżej 60 nowych kluczy (dowolnych urządzeń) na godzinę z jednego konta.
* Zablokowany pytający nie dostaje OTK i nic nie zużywa.
* Uzupełnianie: `GET /v1/keys/me/count` (stan puli, flaga `low` poniżej 20), `PUT /v1/keys` z limitem 30 żądań/h na urządzenie, paczka przekraczająca łączny limit 200 jest wycofywana atomowo razem z podpisanym kluczem.
* Wszystko w jednej transakcji `BEGIN IMMEDIATE`; test współbieżny: 12 wątków, 5 kluczy, 5 różnych wydań, zero duplikatów.
**Ryzyko szczątkowe:** atakujący z wieloma kontami (limit rejestracji 20/h na adres) wyczerpie najwyżej 10 kluczy/h/urządzenie; klient powinien uzupełniać pulę po każdym połączeniu.

## 2. Blokowanie logowania cudzych kont (DoS)
**Luka:** 5 błędnych prób blokowało konto globalnie (także poprawny sekret), więc każdy mógł zablokować każdego.
**Naprawa:** blokada nie jest już stanem konta (`failed_logins/locked_until` nie są używane). Błędy liczone w trzech licznikach magazynu limitów:
* para (nazwa, adres klienta): 5 błędów/15 min. Atakujący blokuje tylko siebie; właściciel z innego adresu loguje się normalnie (test).
* adres klienta: 30 błędów/10 min (zgadywanie wielu nazw z jednego adresu).
* nazwa globalnie: 100 błędów/h, ogranicza zgadywanie rozproszone.
**Brak wyliczania użytkowników:** klucze i odpowiedzi są identyczne dla istniejących i nieistniejących nazw, sprawdzenie blokady nie czyta bazy, scrypt liczony zawsze (test liczy wywołania). Endpoint usuwania konta ma własny limit 5 prób/h (inaczej skradziony token pozwalałby zgadywać sekret).
**Ryzyko szczątkowe (świadomie):** rozproszony atakujący z wieloma adresami może wyczerpać licznik globalny (100 błędów/h) i wstrzymać logowanie ofiary na czas okna. To kompromis wobec zgadywania rozproszonego; docelowo potrzebny dowód pracy/CAPTCHA lub zaufane urządzenia. Sekret uwierzytelnienia jest wyprowadzany Argon2id na urządzeniu, więc zgadywanie online jest kosztowne.

## 3. Usuwanie konta
Cała operacja w jednej transakcji (`App.tx`: BEGIN IMMEDIATE, COMMIT, ROLLBACK przy każdym wyjątku). Jawne kasowanie wszystkich tabel w kolejności (wiadomości obu kierunków, blokady obu kierunków, rezerwacje OTK, klucze, sesje, urządzenia, konto), bez polegania wyłącznie na kaskadach. Test wstrzykuje awarię w 5 punktach i sprawdza, że **żaden wiersz nie zniknął** (migawka wszystkich tabel przed/po) i że konto nadal działa; ponowna próba usuwa wszystko; dane innych użytkowników zostają. `App.tx` zastosowano też w rejestracji, logowaniu, odwołaniu urządzenia, kluczach.
Uwaga: nazwa użytkownika po usunięciu może zostać zajęta przez kogoś innego (podszycie). Nie zmieniano; rozważyć okres karencji.

## 4. Ograniczanie liczby żądań i proxy
* **Usunięto** `SHYFR_TRUSTED_PROXY=true` (ufało nagłówkowi od każdego). Serwer **nie uruchomi się**, jeśli ta zmienna jest ustawiona.
* Nowe: `SHYFR_TRUSTED_PROXIES=<CIDR,CIDR>` (domyślnie puste = nagłówka nie czytamy w ogóle), opcjonalnie `SHYFR_PROXY_HEADER`.
* Nagłówek jest brany pod uwagę tylko, gdy połączenie TCP przyszło z adresu z listy. Lista jest czytana **od prawej**; bierzemy pierwszy adres, który nie jest zaufanym proxy (lewe wpisy może dopisać klient). Błędny wpis lub same zaufane adresy => adres połączenia. IPv6 grupowane po /64, IPv4-mapped rozpakowywane.
* Wymagana konfiguracja proxy (proxy musi **nadpisać**, nie dołączać do nagłówka od klienta):
  * Caddy: `reverse_proxy 127.0.0.1:8080` (domyślnie nadpisuje X-Forwarded-For od niezaufanych klientów).
  * nginx: `proxy_set_header X-Forwarded-For $remote_addr;` (NIE `$proxy_add_x_forwarded_for`, chyba że to kolejny proxy z listy zaufanych).
  * Serwer musi słuchać tylko na adresie dostępnym dla proxy (domyślnie 127.0.0.1) i być niedostępny bezpośrednio z internetu.
* **Wspólny magazyn:** interfejs `RateLimitStore` (`ratelimit.py`) z implementacjami `memory` (domyślna, jedna instancja) i `sqlite` (`SHYFR_RATE_LIMIT_BACKEND=sqlite`, wiele procesów na jednym hoście, test na dwóch serwerach z jedną bazą). Dla wielu hostów trzeba dopisać implementację (Redis/PostgreSQL); te same testy kontraktowe (`test_ratelimit.py`) uruchamia się dla każdej. Magazyn `memory` nie jest współdzielony (test to dokumentuje).

## 5. Dostarczanie wiadomości
Zob. `SHYFR_2_DELIVERY.md`. Zmiany: zbiorcze ACK (`POST /v1/messages/ack`, do 100, wynik per wiadomość), `has_more` w `pending`, atomowe odświeżanie tokenu odświeżania (race między procesami), obsługa wyścigu `client_msg_id` między procesami, `last_seen` urządzeń.

## 6. Znane, niezałatwione
* **Utrata odpowiedzi na odświeżenie tokenu** powoduje, że klient ponawia stary token, a serwer uznaje to za kradzież i kończy sesję. Na słabej sieci mobilnej to realny problem; wymaga okna tolerancji po stronie serwera. Nie zmieniano (zmiana wpływa na model bezpieczeństwa; wymaga osobnej decyzji).
* SQLite i `wlock` w jednym procesie; docelowo PostgreSQL.
* Brak TLS w procesie (kończy go proxy), brak WebSocket/FCM/mediów/odzyskiwania konta.
