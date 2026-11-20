> Dokument historyczny (etapy 39-41). Aktualny stan: `SECURITY_STATUS.md`, `KNOWN_ISSUES.md`, `BUILD_INSTRUCTIONS.md`.

# BETA_FOUNDATION_REPORT: SHYFR 2.0.1-BETA-FOUNDATION (etap 41)

**To NIE jest gotowa beta.** Warunki krytyczne (E2EE, kompilacja Androida, test na dwóch urządzeniach) nie są spełnione. Poziom gotowości: **fundament kodu napisany, niezweryfikowany po stronie Androida; backend zweryfikowany testami.**

## Środowisko etapu
Java 21 i Python 3.12 są dostępne. **Brak:** Android SDK, Gradle (w repo jest tylko `gradle-wrapper.properties`, brak `gradlew` i JAR), kotlinc, dostępu do sieci. Skutek: nie pobrano libsignal, Firebase, WorkManagera ani tekstu AGPL; nie uruchomiono żadnego kodu Kotlin.

## IMPLEMENTED (kod napisany)
- Backend: `PUT/DELETE /v1/devices/me/push-token`, migracja `0004_push_tokens.sql`, kasowanie tokenu przy wylogowaniu, odwołaniu urządzenia i usunięciu konta.
- Klient API: `profileOf`, `fetchKeyBundles`, `uploadKeys`, `keyCount`, `registerPushToken`, `deletePushToken`, `deleteAccount`.
- `msg/`: `ConversationStore` (zaszyfrowany plik), `MessageRepository` (Chat UI -> ViewModel -> Repository -> MessageTransport -> MessageCipher -> API), `MessengerViewModel`, `MessengerGraph`. Wykorzystane istniejące `MessageTransport`, `FileOutbox`, `FileInbox`.
- Stany wiadomości: oczekuje / wysyłanie / wysłano (serwer przyjął) / dostarczono (ACK urządzenia odbiorcy) / przeczytano / błąd; ponawianie; fan-out per urządzenie odbiorcy; deduplikacja po id koperty.
- `FailClosedCipher`: bez libsignal aplikacja ODMAWIA wysyłania; jawny tekst nigdy nie opuszcza urządzenia.
- UI (`ui/MessengerUi.kt`, `ui/AccountSafetyUi.kt`): lista rozmów (szukaj, ostatnia wiadomość, czas, nieprzeczytane, status, awatar-inicjał, pseudonim), ekran rozmowy, nowa rozmowa (weryfikacja użytkownika), potwierdzenia odczytu (przełącznik), wylogowanie, usuwanie konta (skutki + hasło + „USUŃ”), ekran odzyskiwania konta, numer bezpieczeństwa. Wejście: Ustawienia (tryb programisty) -> „SHYFR 2.0: wiadomości (beta)”. `V2Screen` zostaje jako ekran konta/logowania.
- `SyncWorker` + `SyncScheduler` (okresowo 15 min i jednorazowo przy sieci, backoff wykładniczy), `MessageNotifier` (powiadomienie bez treści i nadawcy).
- FCM (`msg/Push.kt`): rejestracja/aktualizacja/usuwanie tokenu, usługa ignorująca ładunek. Konfiguracja tylko z `-PfcmAppId -PfcmApiKey -PfcmProjectId [-PfcmSenderId]`.
- libsignal (`app/src/signal/`): `SignalStateStore`, `LibsignalMessageCipher` (PQXDH + Double Ratchet, TOFU), `KeyProvisioner`; włączane `-PlibsignalVersion=...`. **NIESKOMPILOWANE, API NIEZWERYFIKOWANE; funkcja nieukończona.** Braki: `trustChangedIdentity` (TODO), numer bezpieczeństwa per urządzenie, rotacja podpisanego prekeya, rotacja last-resort.
- Gradle: zależności WorkManager/Firebase, opcjonalne libsignal, pola BuildConfig, repozytorium Maven Signala, wpis w manifeście.
- Docs: `SHYFR_2_RECOVERY.md`, `DEPENDENCIES_AND_LICENSES.md`, `LICENSE_TODO.md`, dopisek w `SECURITY_STATUS.md`.
- Istniejące, nieruszane: `deploy/` (Caddyfile, env dev/staging/prod, systemd, backup), migracje, rate limiting, backup/restore, health checks.

## TESTED (uruchomione)
- **Backend Python: 124 testy, OK** (`python3 -m unittest discover -s tests -t .`, 74 s), w tym 8 nowych (`test_push_tokens.py`). Zmieniłem 4 oczekiwane wartości wersji schematu 3 -> 4 w `test_backup.py` i `test_server.py` (migracja 0004); żadnego testu nie usunięto ani nie osłabiono.

## NOT TESTED
- `MessageRepositoryTest.kt` (11 testów JVM: kolejka, offline, ACK vs przyjęcie, deduplikacja, fail-closed, wielourządzeniowość, potwierdzenia odczytu) jest napisany, **nie uruchomiony**. Używa atrapy `TestOnlyCipher` wyłącznie dla logiki, bez związku z kryptografią.
- Wszystko w Kotlinie/Androidzie: kompilacja, UI, WorkManager, FCM, libsignal. Sprawdzono tylko zbalansowanie nawiasów.
- Testy z listy zadania, których nie ma lub nie uruchomiono: rejestracja/logowanie/odnawianie sesji po stronie Androida, wymiana kluczy, szyfrowanie/odszyfrowanie, migracje klienta, backup/restore klienta, test dwóch urządzeń, różne sieci.

## BLOCKED
| Prio | Blokada |
|---|---|
| P0 | **E2EE nie istnieje w działającej postaci.** Potrzeba: sieć, przypięta wersja libsignal, kompilacja, poprawka sygnatur, testy |
| P0 | Brak zweryfikowanej kompilacji (`clean/test/lint/assembleDebug/assembleRelease/bundleRelease` NIE uruchomione; powód: brak SDK/Gradle/sieci). **Brak APK/AAB: żadnych artefaktów nie wygenerowano** |
| P0 | Brak testu na dwóch prawdziwych urządzeniach |
| P0 | Brak pliku `LICENSE` (brak sieci; zob. `LICENSE_TODO.md`) |
| P1 | Brak `gradlew` i `gradle-wrapper.jar` (`tools/bootstrap-gradle-wrapper.sh` wymaga sieci) |
| P1 | FCM: brak konfiguracji Firebase od właściciela (appId, apiKey, projectId, senderId); serwer NIE wysyła jeszcze sygnałów push (tylko przechowuje token) |
| P1 | Prawdziwe E2EE decyduje o publikacji: brak odzyskiwania kluczy (świadoma decyzja, opisana) |
| P2 | Wiadomości głosowe i załączniki, grupy, multi-device dla własnych urządzeń nadawcy (sync historii), kopie zapasowe kluczy |
| P2 | Backup/restore i migracje bazy po stronie klienta: bez testów; historia w pliku JSON (docelowo SQLCipher) |
| P3 | Polish UI (animacje, tabletowy układ), audyt dostępności, ikony |
| Wdrożenie | HTTPS/VPS: tylko pliki przykładowe w `deploy/`; nic nie wdrożono, nic nie wypchnięto |

## Jak ruszyć dalej (w tej kolejności)
1. Środowisko z siecią + Android SDK: `tools/bootstrap-gradle-wrapper.sh`, `./gradlew test` bez libsignal (sprawdza, że reszta się kompiluje).
2. `curl` AGPL do `LICENSE`.
3. `-PlibsignalVersion=<wersja>`: kompilacja `src/signal`, poprawka sygnatur, testy sesji (dwa `SignalStateStore` w jednym teście JVM).
4. Dwa telefony: scenariusz offline z `docs/2.0/SHYFR_2_DELIVERY.md`.

## Lokalizacja artefaktów
Tylko ten ZIP (źródła). APK/AAB: brak.
