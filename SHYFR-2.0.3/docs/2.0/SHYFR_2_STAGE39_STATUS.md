# Etap 39: faktyczny status (uruchomione vs napisane)

Środowisko pracy: Python 3.12, JDK, **brak Gradle, brak Android SDK, brak kotlinc, brak dostępu do sieci z powłoki**. Skutek: Kotlin nie był kompilowany ani uruchamiany.

## Testy faktycznie uruchomione
| Zestaw | Wynik |
|---|---|
| Backend `tests.test_server` (35 → 34: usunięto test blokady konta, zastąpiono test OTK) | OK |
| Backend `tests.test_hardening` | 55 testów, OK |
| Backend `tests.test_ratelimit` (kontrakt memory + sqlite, 2 instancje) | 14 testów, OK |
| **Backend razem** (`unittest discover`, pod `pipefail`, ok. 62 s) | **Ran 103 tests, OK** |
| Relay (stare testy) | Ran 61 tests, OK |
| Kontrola testów (mutacje): bez rollbacku / bez ochron OTK / zaufanie nagłówkowi od każdego | testy zawodzą, zgodnie z oczekiwaniem |
| Składnia YAML workflow, `compileall`, skrypty `junit_summary.py`/`lint_summary.py` na danych próbnych | OK |
| Start prawdziwego serwera jak w CI + skrypt odtwarzający scenariusze testów integracyjnych | OK |

## Napisane, ale NIEURUCHOMIONE
Kod Kotlin klienta (`api/*`, `ui/V2Screen.kt`, zmiany w `ShyfrApp.kt`/`SettingsScreen.kt`, `build.gradle.kts`), testy Kotlina (`ApiConfigTest`, `V2CredentialsTest`, `ShyfrApiClientUnitTest`, `MessageTransportUnitTest`, `BackendIntegrationTest`), CI Androida. Mogą zawierać błędy kompilacji. Stare testy Kotlina 1.x nie były uruchamiane w tym etapie.

## Odpowiedzi na pytania z zadania
1. **Czy Android komunikuje się z backendem?** Kod, który to robi, jest napisany i podpięty do UI, ale **nie zweryfikowano tego**: nie zbudowano APK ani nie uruchomiono aplikacji. Nie twierdzę, że działa.
2. **Czy E2EE jest zaimplementowane?** **Nie.** Brak jakiejkolwiek integracji protokołu. Decyzja niezamknięta (blokada licencji), zob. `SHYFR_2_CRYPTO_DECISION.md`.
3. **Czy testowano dwa urządzenia fizycznie?** **Nie.** Jest plan (`SHYFR_2_ANDROID_CLIENT.md`).
4. **HTTPS w teście?** Klient wymusza HTTPS w wydaniu; test wdrożenia z HTTPS nie został wykonany (brak wdrożenia).

## Zrealizowane
* P1: wszystkie 5 punktów + testy regresyjne (`SHYFR_2_BACKEND_HARDENING.md`).
* P2: dokument decyzji z weryfikacją źródeł; decyzja warunkowa, jawna blokada.
* P3: modularna warstwa sieciowa (konfigurowalny adres, rejestracja, logowanie, tokeny, rotacja, wylogowanie, profil, urządzenia, bezpieczny zapis, błędy, stan sieci); kod napisany, niezweryfikowany.
* P4: ekran integracji (rejestracja, logowanie, profil, urządzenia, wylogowanie, odświeżanie, odwołana sesja, komunikaty); niezweryfikowany; testy integracyjne bez atrap napisane, niewykonane.
* P5: transport i stany wiadomości po stronie klienta + testy; bez UI wysyłki (celowo: brak szyfrowania).
* P6: CI poprawione (pipefail, test failures fail the job, integracja z prawdziwym backendem, widoczność lintu, kontrola pustych testów); Gradle Wrapper przygotowany częściowo.

## Niezrealizowane / otwarte
* Kompilacja i uruchomienie Kotlina, APK, testy Kotlina (CI zrobi to po wypchnięciu).
* **Gradle Wrapper:** dodano tylko `gradle-wrapper.properties` i `tools/bootstrap-gradle-wrapper.sh`; brak `gradlew` i `gradle-wrapper.jar` (nie do wygenerowania offline). CI generuje go na bieżąco z ostrzeżeniem. Do zrobienia: uruchomić skrypt i zatwierdzić wynik.
* E2EE, test dwóch telefonów, wdrożenie HTTPS, przypinanie certyfikatu.
* Skrzynka/kolejka klienta w pliku zaszyfrowanym, nie w bazie sejfu SQLCipher; brak usuwania danych v2 przy czyszczeniu sejfu. Klucz Keystore jest współdzielony z sejfem (`shyfr_vault_key`).
* Stara ścieżka wiadomości 1.x nie została ruszona ani usunięta (zgodnie z wymaganiem).
* Utrata odpowiedzi na odświeżenie tokenu kończy sesję (zob. HARDENING §6).
* Brak pliku LICENSE w projekcie.
* Feed, Społeczności, FCM, WebSocket, media, odzyskiwanie konta: poza zakresem.
