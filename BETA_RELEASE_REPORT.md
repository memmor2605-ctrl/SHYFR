> Dokument historyczny (etapy 39-41). Aktualny stan: `SECURITY_STATUS.md`, `KNOWN_ISSUES.md`, `BUILD_INSTRUCTIONS.md`.

# SHYFR 2.0: raport (etap „beta release engineering”)

## Werdykt
**SHYFR 2.0 NIE jest gotowy do zamkniętej bety i nie wolno go przedstawiać jako bezpiecznego komunikatora.** Archiwum nosi nazwę „BETA-RELEASE” zgodnie z zamówieniem, ale to nazwa pliku, nie deklaracja gotowości. Warunki z definicji gotowości, które NIE są spełnione: Android się kompiluje (niezweryfikowane), E2EE (brak), komunikacja między sieciami (nieprzetestowana), PIN/biometria na urządzeniu (nieprzetestowane na 2.0), wdrożony HTTPS (brak), podpisany AAB (brak kluczy).

## Ograniczenia środowiska pracy (fakt)
Brak Gradle, Android SDK, kotlinc, adb i dostępu do sieci z powłoki (sprawdzone ponownie na początku tego etapu). Dlatego **nie zbudowano żadnego APK/AAB, nie skompilowano ani jednej linii Kotlina, nie uruchomiono żadnego testu Androida i nie użyto żadnego telefonu.**

## Co faktycznie zrobiono i zweryfikowano (Python, uruchomione)
| Obszar | Zrobione | Dowód |
|---|---|---|
| Prekeye post-kwantowe (PQXDH) | migracja 0003, `kyber_prekeys`, prekey „ostatniej szansy”, rezerwacje i limity jak dla OTK, `key_id` podpisanego prekeya, limity rozmiarów (do 4096 znaków) | `PqPrekeyTests` (6) |
| Kopia/odtwarzanie | `shyfr_server/backup.py` (kopia „na gorąco”, weryfikacja integralności, odmowa nadpisania, restore atomowy) | `test_backup` (7): CREATE→WRITE→BACKUP→RESTORE→READ, te same dane bit w bit, ta sama tożsamość po logowaniu, kolejka i blokady zachowane, kopia przy współbieżnych zapisach, uszkodzone kopie odrzucane; mutacja (restore gubiący urządzenia) wykryta |
| Gotowość | `/readyz` (migracje + zapisywalność bazy; 503 gdy schemat nieaktualny) | test w `test_backup` |
| Konfiguracja środowisk | `deploy/`: env dev/staging/prod, Caddyfile, unit systemd (utwardzony), timer kopii | pliki; **niewdrożone** |
| CI | lint z błędami Fatal/Error blokuje job; `assembleRelease`+`bundleRelease`; weryfikacja artefaktów; `readyz` przed testami integracyjnymi | składnia YAML sprawdzona; **CI nie było uruchomione** |
| Przegląd kodu (własny, NIE audyt niezależny) | brak sekretów w repo (wzorce), brak SQL z danych użytkownika (jedyna dynamiczna klauzula składa się ze stałych), logi serwera tylko typ błędu 500, TLS weryfikowany przez domyślny `HostnameVerifier`, `allowBackup=false`, `usesCleartextTraffic=false` w wydaniu | grepy; wyniki w SECURITY_STATUS.md |
Z poprzedniego etapu (nadal prawdziwe): utwardzenie serwera (OTK, logowanie, usuwanie konta, proxy, magazyn limitów, ACK).

## Wyniki testów (dokładnie)
* Backend `unittest discover` pod `pipefail`: **Ran 116 tests, OK** (34 `test_server` + 61 `test_hardening` + 14 `test_ratelimit` + 7 `test_backup`), ok. 70 s.
* Relay: **Ran 61 tests, OK**.
* `compileall` backend/relay/tools: OK.
* Testy Kotlina (napisane w etapie 39) i testy integracyjne klienta: **NIE uruchomione.**

## Odpowiedzi
* Android się kompiluje? **Nieznane, nie sprawdzono.**
* Backend działa? **Lokalnie tak (testy, start serwera, `readyz`). Na skonfigurowanym HTTPS: nie sprawdzono (nie wdrożono).**
* Komunikacja między sieciami? **Nie sprawdzono.**
* E2EE kompletne? **Nie. BLOKER.** Nie ma żadnej integracji protokołu.
* Offline? **Serwer: kolejka i ACK przetestowane. Klient 2.0: transport napisany, niepodpięty do UI, nieuruchomiony. Brak WorkManager i FCM.**
* PIN i biometria? **W kodzie 1.x istnieją (`Bio.kt`, `QuickUnlock.kt`); w tym etapie nie testowane i nie połączone z kontem 2.0.**
* Procent gotowości (ocena subiektywna, nie pomiar): **ok. 25–30% drogi do zamkniętej bety 2.0**, głównie backend; brak E2EE, przepływu wiadomości w UI, funkcji społecznościowych na backendzie 2.0, załączników, FCM/WorkManager i weryfikacji na urządzeniach.

## Błędy i blokady
**P0 (blokują betę):** (1) Android niezweryfikowany kompilacją; (2) brak E2EE; (3) brak ekranów wiadomości 2.0 (jest tylko ekran deweloperski `V2Screen`); (4) brak synchronizacji w tle (WorkManager) i FCM; (5) brak wdrożonego serwera HTTPS (wymaga domeny, hostingu i decyzji właściciela); (6) brak pliku LICENSE (właściciel zaakceptował rekomendację AGPL, ale pełnego tekstu licencji nie było lokalnie i nie wolno go odtwarzać z pamięci: skopiuj z gnu.org do `LICENSE`); (7) brak odzyskiwania konta 2.0 i UI usuwania konta (endpoint istnieje).
**P1:** utrata odpowiedzi na odświeżenie tokenu kończy sesję; brak Gradle Wrapper (jar) w repo; brak testów instrumentacyjnych; brak feedu/społeczności/postów/komentarzy/zgłoszeń na backendzie 2.0; brak szyfrowanych załączników i magazynu mediów; polityka prywatności nie opisuje bezpośredniego połączenia (IP widoczny dla serwera); brak regulaminu i akceptacji przed publikacją w 2.0; wersja nadal `1.1.0-rc27`; `minify` wyłączony; zgodność 16 KB page size niesprawdzona; `foregroundServiceType=specialUse` wymaga deklaracji w Play Console.
**P2:** limit logowania globalny podatny na rozproszony DoS (opisane); SQLite jako baza; nazwa użytkownika po usunięciu konta może być zajęta ponownie; brak przypinania certyfikatu.
**P3:** komunikaty i dokumenty częściowo po polsku, częściowo bez tłumaczeń.

## Co blokuje
* **Zamkniętą betę:** P0 (1)–(7).
* **Publiczną premierę:** wszystko powyższe + niezależny audyt kryptografii i bezpieczeństwa, pełne UGC (moderacja, zgłoszenia, blokowanie na backendzie 2.0), wdrożenie produkcyjne z monitoringiem, testy na wielu urządzeniach.

## Lokalizacja artefaktów
ZIP: `/mnt/user-data/outputs/SHYFR-2.0.0-BETA-RELEASE.zip`. **APK: brak (nie zbudowano). AAB: brak (nie zbudowano, brak kluczy).**
