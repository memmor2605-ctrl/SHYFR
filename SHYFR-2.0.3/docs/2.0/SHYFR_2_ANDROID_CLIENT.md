> Dokument historyczny (etapy 39-41). Aktualny stan: `SECURITY_STATUS.md`, `KNOWN_ISSUES.md`, `BUILD_INSTRUCTIONS.md`.

# SHYFR 2.0: klient API na Androidzie (architektura, konfiguracja, budowanie, plan testów)

**Status: kod napisany, NIEskompilowany i nieuruchomiony w tym środowisku (brak Gradle, Android SDK i sieci). Weryfikacja Kotlina odbywa się dopiero w CI lub lokalnie komendami poniżej.**

## Architektura (`app/src/main/java/com/shyfr/app/api/`)
| Plik | Rola |
|---|---|
| `ApiConfig` | adres serwera; wymaga HTTPS; HTTP tylko do loopback/emulatora i tylko gdy wywołujący pozwoli (aplikacja pozwala wyłącznie w debug) |
| `HttpTransport` | interfejs + `UrlConnectionTransport` (HttpURLConnection, zaufanie systemowe, bez przekierowań, limit odpowiedzi 1 MB) |
| `ShyfrApiClient` | rejestracja, logowanie, profil, urządzenia, wylogowanie, odświeżanie tokenu (jeden zamek, odświeżanie z wyprzedzeniem 30 s, na 401 odśwież raz i ponów) |
| `ApiErrors` | typowane błędy z komunikatem dla użytkownika (Offline, Network, Timeout, InvalidCredentials, SessionRevoked, RateLimited, Http, Server, Malformed) |
| `SessionStore` | tokeny i zapamiętane urządzenia w pliku zaszyfrowanym kluczem Android Keystore (`noBackupFilesDir`), zapis atomowy |
| `NetworkState` | `NetworkStatus`; brak sieci = natychmiastowy `Offline` |
| `V2Credentials` | sekret uwierzytelnienia = Argon2id(login, hasło) + HKDF liczone na urządzeniu; hasło nie wychodzi |
| `V2Backend` | jedyne miejsce składające klienta z konfiguracji |
| `MessageTransport` + `MessageModels` | kolejka wychodząca (idempotentna, uporządkowana), skrzynka odbiorcza (zapis przed ACK), stany, `MessageCipher` (interfejs, brak implementacji) |
| `ui/V2Screen` | ekran: rejestracja, logowanie, profil, urządzenia, usuwanie urządzenia, wylogowanie, błędy |
Wejście w UI: Ustawienia → Zaawansowane → "SHYFR 2.0: serwer testowy (programista)" (widoczne w debug lub gdy włączony tryb programisty). SHYFR 1.x i Tor nie zostały ruszone (zmieniono tylko: enum ekranu, jeden wiersz w ustawieniach, `BuildConfig`).

**Prywatność:** warstwa 2.0 łączy się bezpośrednio (bez Tora); serwer widzi adres IP. To decyzja produktowa do potwierdzenia przed wydaniem. Brak przypinania certyfikatu.

## Konfiguracja (bez adresów produkcyjnych w kodzie)
* Budowanie: `-Pshyfr2ApiBaseUrl=https://twoj-test.example` (pusty = funkcja wyłączona).
* W apce (debug/tryb programisty): pole "Adres serwera" na ekranie 2.0.
* Emulator → lokalny backend: `-Pshyfr2ApiBaseUrl=http://10.0.2.2:8080` (działa tylko w debug dzięki `app/src/debug/.../network_security_config.xml`; wydanie jest HTTPS-only).
* Prawdziwe urządzenie → test na HTTPS: postaw backend za reverse proxy z certyfikatem (`backend/README.md`) i podaj adres https.

## Backend lokalnie
```
cd backend
SHYFR_DB_PATH=./data/shyfr.db SHYFR_HOST=127.0.0.1 SHYFR_PORT=8080 python3 -m shyfr_server.server
```
## Komendy budowania i testów
```
(cd backend && python3 -m unittest discover -s tests -t . -v)       # backend
cd relay && python3 -m unittest test_relay test_social test_account
./tools/bootstrap-gradle-wrapper.sh                       # jednorazowo, potem zatwierdź gradlew i gradle/wrapper/*.jar
./gradlew assembleDebug -Pshyfr2ApiBaseUrl=http://10.0.2.2:8080
./gradlew testDebugUnitTest                                        # testy jednostkowe (integracyjne pominięte bez zmiennej poniżej)
SHYFR2_TEST_URL=http://127.0.0.1:8080 ./gradlew testDebugUnitTest  # + testy integracyjne przeciw PRAWDZIWEMU backendowi
```

## Testy klienta
* `ApiConfigTest`, `V2CredentialsTest`: czyste testy jednostkowe.
* `ShyfrApiClientUnitTest`, `MessageTransportUnitTest`: logika klienta na zaskryptowanym transporcie (to NIE są testy integracyjne): odświeżanie z wyprzedzeniem, 401→refresh→retry, odwołana sesja, błąd sieci nie kasuje tokenów, **jedno odświeżenie przy 6 równoległych żądaniach**, mapowanie błędów, kolejność wysyłki, **zapis przed ACK**, deduplikacja po utracie ACK, błąd odszyfrowania nie traci wiadomości, trwałość plików.
* `BackendIntegrationTest`: prawdziwy klient + prawdziwy backend, bez atrap: rejestracja, profil, urządzenia, wylogowanie, ponowne logowanie na to samo urządzenie, 409/401, rotacja tokenu i unieważnienie po ponownym użyciu starego, odwołanie sesji z innego urządzenia, transport kolejki (wysłanie, duplikat żądania, odbiór, ACK, dostarczono, przeczytano), odbiorca offline → po powrocie dostaje 3 wiadomości po kolei. Bez `SHYFR2_TEST_URL` testy są POMINIĘTE, a CI wymusza, że nie wszystkie zostały pominięte.
Scenariusze serwerowe tych testów odtworzyłem skryptem Pythona na prawdziwym serwerze (rejestracja, wylogowanie, ponowne logowanie na to samo urządzenie, odwołanie, duplikat wysyłki, ACK, stany), wszystkie zgodne z założeniami testów Kotlina. To nie zastępuje uruchomienia Kotlina.

## Plan testu fizycznego na dwóch telefonach (NIEWYKONANY)
Wymagane: backend za HTTPS, dwa telefony z buildem debug, konta `a` i `b`.
1. Telefon A na Wi-Fi, telefon B na danych komórkowych (różne sieci): rejestracja/logowanie obu, profil, lista urządzeń.
2. Wylogowanie i ponowne logowanie (to samo urządzenie, brak nowego wpisu na liście).
3. Tryb samolotowy: operacje dają "Brak połączenia", sesja zostaje; po powrocie działa.
4. Odwołanie telefonu B z A: B przy następnej operacji dostaje "Sesja wygasła…" i wraca do logowania.
5. Odczekaj >15 min: kolejne żądanie odświeża token bez wylogowania.
6. Transport wiadomości (po dodaniu tymczasowego narzędzia debug do wysyłki surowych bajtów): odbiorca offline, wysyłka, zabicie aplikacji nadawcy w trakcie wysyłki, powrót odbiorcy, ACK, brak duplikatów, zmiana Wi-Fi↔komórkowa w trakcie.
Wyniki wpisz do `SHYFR_2_STAGE39_STATUS.md`.
