# 2.0.3-beta
- Poprawka po pierwszym buildzie CI: kod E2EE dostosowany do API libsignal 0.63.0 (saveIdentity, markKyberPreKeyUsed, Curve.generateKeyPair, SenderKeyStore). Wykrywanie powtórek last-resort wymaga nowszej wersji libsignal.
- E2EE: libsignal jest stałą zależnością builda (wersja w `gradle.properties`), kod przeniesiony do `src/main` (bez refleksji i bez trybu „bez libsignal"). Poprawki w magazynie kluczy: trwały zapis last-resort i liczników identyfikatorów prekeyów, brak cichego tworzenia nowej tożsamości przy nieczytelnym stanie, czyszczenie danych przy zmianie konta na telefonie, numer bezpieczeństwa i „Zaufaj nowemu kluczowi", uzupełnianie puli kluczy także w tle, usuwanie duplikatów ze skrzynki.
- Gradle: AGP 8.9.1, Gradle 8.13, Kotlin 2.3.0; wrapper generowany przez CI (artefakt do zatwierdzenia).
- CI: APK debug budowany i wysyłany przed testami; jawna instalacja platformy SDK 36.
- Dodano pełny `LICENSE` (AGPL-3.0); dokumenty zależności i stanu bezpieczeństwa zaktualizowane; usunięto stare dokumenty 1.x opisujące Tora.

# Etap 40 (SHYFR 2.0, niewydany)

## 2.0.2-beta
- Tor usunięty w całości (tor-android, jtorctl, TorService/NodeService, onion, SOCKS, stary relay, stary ChatScreen, lokalny sejf 1.x).
- Jedna architektura: HTTPS do backendu SHYFR, adres w gradle.properties (shyfr2ApiBaseUrl). Komunikator V2 jest głównym ekranem.
- E2EE nadal wymaga builda z libsignal (-PlibsignalVersion); bez niego aplikacja odmawia wysyłania.
- Backend: prekeye post-kwantowe (PQXDH: migracja 0003, last-resort, limity jak dla OTK), `/readyz`, narzędzie kopii i odtwarzania z testem pełnego cyklu. 116 testów backendu.
- Wdrożenie: `deploy/` (env dev/staging/prod, Caddy, systemd, timer kopii).
- CI: lint blokuje przy Fatal/Error, `assembleRelease` + `bundleRelease`, weryfikacja artefaktów.
- Dokumenty: BETA_RELEASE_REPORT, BUILD_INSTRUCTIONS, SERVER_DEPLOYMENT, SECURITY_STATUS, KNOWN_ISSUES, BETA_TESTING_GUIDE, GOOGLE_PLAY_CHECKLIST.
- NIE zrobiono: kompilacji Androida, E2EE, ekranów wiadomości 2.0, FCM/WorkManager, wdrożenia.

# Etap 39 (SHYFR 2.0, niewydany)
- Backend: ochrona puli kluczy jednorazowych, logowanie bez blokowania cudzych kont, atomowe usuwanie konta, bezpieczne zaufanie do proxy, wymienny magazyn limitów, zbiorcze ACK. 103 testy backendu.
- Android: nowa warstwa `api/` i ekran serwera testowego 2.0 (kod niezweryfikowany: brak Gradle w środowisku). Brak E2EE.
- CI: pipefail, testy integracyjne przeciw prawdziwemu backendowi, widoczny lint.
- Docs: `docs/2.0/SHYFR_2_CRYPTO_DECISION.md`, `_BACKEND_HARDENING`, `_DELIVERY`, `_ANDROID_CLIENT`, `_STAGE39_STATUS`.

# Zmiany

## 1.1.0-rc27 (etapy 22–37, w toku, nie jest to wydanie)
- Nowy interfejs (neutralna paleta, własne ikony, dolna nawigacja), tożsamości społecznościowe (publiczna, prywatna, anonimowa), obserwowanie na przekaźniku SHYFR, odzyskiwanie konta loginem i hasłem (tylko klucze), publiczny feed Nostr z automatycznym wyborem serwerów, powiadomienia, udostępnianie, zapisane, wyciszanie, blokowanie i zgłaszanie.
- Dostarczanie: osobny stan „na serwerze", opóźnienia ponawiania per kontakt, równoległa wysyłka.
- Kopia zapasowa: obejmuje tabele tożsamości, spójna migawka, oczekujące wysyłki po przywróceniu oznaczane jako niedostarczone.
- Stan osiągalności adresu: jawne stany zamiast fałszywego „osiągalny".
- Załączniki: brak fałszywego sukcesu przy niepełnym zakolejkowaniu, sprzątanie porzuconych odbiorów.
- CI: zapis APK, podsumowanie z commitem i testami, lint.

## 1.0.0
- Weryfikacja kontaktu kodem QR (skanowanie kodu rozmówcy, ostrzeżenie przy niezgodności).
- Wyciszanie rozmów na 1 godzinę, 8 godzin, 7 dni lub bezterminowo.
- Ponawianie i anulowanie niewysłanych wiadomości.
- Kopia zapasowa (.shybk, osobne hasło, szyfrowanie AES-256-GCM fragmentami) i przywracanie na nowym telefonie.
- Blokowanie kontaktów, ostrzeżenie o niezweryfikowanym kontakcie.
- Onboarding przy pierwszym uruchomieniu.
- Centrum prywatności, zarządzanie pamięcią i załącznikami.
- Raport diagnostyczny z maskowaniem adresów i identyfikatorów.
- Atomowy zapis sejfu.
- targetSdk 36, testy jednostkowe, CI (GitHub Actions), polityka prywatności i materiały do Google Play.
- Nowe Ustawienia, panel wiadomości, emoji, status aktywności, motywy, ochrona zrzutów, odpowiedź przeciągnięciem.

## Etap 6 (poprawki)
- Tor i odbiór wiadomości działają po zablokowaniu ekranu (Ustawienia → Bezpieczeństwo), pełna blokada na życzenie.
- Test osiągalności własnego adresu onion, status „Rozgłaszam adres…”, więcej prób dołączania.
- Biometria: monit tylko na pierwszym planie, ciche anulowanie.
- Nowy interfejs: ciepła ciemna paleta, pływająca nawigacja, zakładki-pigułki.
- Nowe logo (znak S): ikona adaptacyjna, warstwa monochromatyczna, warianty jasny/ciemny w katalogu `logo/`.

## Etap 7 (warstwa publiczna, wersja 1)
- `relay/`: przekaźnik publiczny (Python, onion w Termux): społeczności (publiczne/prywatne/ukryte), kanały (dyskusja/ogłoszenia/tablica), wpisy, komentarze, reakcje, reposty, cytaty, przypinanie, edycja, usuwanie, zgłoszenia, role (właściciel/moderator/zablokowany), zaproszenia, wyszukiwanie i hashtagi, paginacja, PoW i limity. 23 testy.
- Aplikacja: zakładki Feed i Społeczności, wpisy anonimowe lub pod pseudonimem per społeczność, nowa tożsamość w społeczności, zakładki lokalne, ustawienie adresu przekaźnika. Klucze Ed25519 wyprowadzane z klucza sejfu osobno dla każdej społeczności (bez zapisu kluczy prywatnych).
- Baza: migracja do wersji 10 (tabele `pub_membership`, `pub_bookmarks`, `pub_reacted`; bez zmian destrukcyjnych).
- Testy jednostkowe zgodności protokołu z przekaźnikiem: `PublicProtoTest`.

## Etap 8 / 1.1.0-rc1 (scalenie)
- Scalono wersję 1.0.0v z wersją z przekaźnikiem: zakładki Feed, Społeczności i Aktywność są teraz prawdziwe (przekaźnik onion), bez danych demo. Lokalna atrapa 1.0.0v (`seedInitialSocialData`, fałszywe liczby, przykładowy adres autora) nie została przeniesiona.
- Prośby o kontakt: domyślnie wyłączone, włączane per społeczność (Nikt / Członkowie / Wszyscy); każdą prośbę akceptuje autor; odpowiedź z zaproszeniem do czatu P2P jest zaszyfrowana kluczem proszącego. Brak automatycznego „zweryfikowano”.
- Aktywność: prośby, odpowiedzi, reakcje, komentarze, reposty, zgłoszenia (dla moderatorów).
- Nawigacja: Feed, Społeczności, Czaty, Aktywność, Profil; Ustawienia z paska górnego.
- Przekaźnik: kontakty, aktywność; 29 testów.
- Testy aplikacji: PublicProtoTest, ContactCryptoTest.

## Etap 9 (sieć niezależna od wspólnego Wi-Fi)
- Obsługa zmiany sieci (Wi-Fi ↔ komórkowa ↔ inne Wi-Fi): `NetMonitor` + `SIGNAL NEWNET`, ponowny test osiągalności adresu onion i natychmiastowa próba wysyłki zaległych wiadomości. Wcześniej Tor odświeżał obwody dopiero po minutach.
- Restart węzła, gdy adres onion przestaje odpowiadać po zmianie sieci (tylko tam, gdzie test własnego adresu wcześniej działał).
- IPv6 włączane w Torze, gdy sieć ma globalny adres IPv6.
- Dziennik dostarczania: przyczyna porażki (typ wyjątku, bez adresów) co 5 min na kontakt, widoczna w raporcie diagnostycznym.
- Ustawienia → Diagnoza sieci: typ sieci, obwody, osiągalność adresu, „Odśwież obwody”, „Sprawdź mój adres”.
- Nowe uprawnienie: ACCESS_NETWORK_STATE (zwykłe, bez pytania użytkownika).

## Etap 10 (skrzynka: dostarczanie niezależne od jednoczesnej obecności i sieci)
- Fallback skrzynki na przekaźniku: gdy połączenie P2P przez Tora zawiedzie, zaszyfrowane ramki idą do skrzynki pary i są pobierane co 60 s (oraz po zmianie sieci). Bez zmiany formatu wiadomości; duplikaty odrzucane po nagłówku.
- Skrzynka jest jednokierunkowa i należy do pary: klucz wyprowadzają obaj rozmówcy (X25519 + HKDF), więc tylko oni mogą pisać i czytać. Limity: 64 kB na ramkę, 500 wiadomości i 8 MB na skrzynkę, 14 dni TTL.
- Przekaźnik: akcje `mbox.put` / `mbox.ack`, zbiorcze `POST /v1/mbox/pull`; 34 testy.
- Diagnoza sieci pokazuje stan skrzynki.
- Ograniczenie: oba telefony muszą mieć ten sam przekaźnik; duże pliki (>64 kB na ramkę) idą tylko P2P.

## Etap 11 (aktywność w tle, wyciszanie, kopia zapasowa)
- Co 10 min węzeł sprawdza nowe prośby o kontakt, odpowiedzi i zdarzenia przy Twoich wpisach; powiadomienie „Nowa aktywność” bez szczegółów na ekranie blokady. Kropka przy ikonie Aktywności.
- Wyciszanie powiadomień per społeczność (w oknie społeczności).
- Kopia zapasowa obejmuje członkostwa, pseudonimy, zakładki i własne prośby w społecznościach. Klucze społeczności nie są w kopii: odtwarzają się z klucza konta, więc kopię przywracaj na to samo konto (ten sam login i hasło).

## Etap 12 (tryb publiczny z profilem, CI przekaźnika)
- Trzy sposoby prezentacji tożsamości per społeczność: anonimowy (domyślny), pseudonim, profil publiczny (pseudonim + opis, wpisy autora). Włączenie wymaga potwierdzenia z opisem skutków; wyłączenie usuwa profil z przekaźnika (pseudonim w opublikowanych wpisach zostaje).
- „Profil autora” z menu wpisu oraz „Podgląd jako obcy” dla własnego profilu. Profil nie daje prawa do kontaktu (prośby nadal domyślnie wyłączone).
- Przekaźnik: akcja `profile.set`, `GET /v1/c/{cid}/profile`, filtr `author`; 36 testów.
- Baza: migracja do wersji 12 (kolumny `bio`, `is_public`).
- CI: osobne zadanie uruchamia testy przekaźnika (Python).

## Etap 13 (wyszukiwanie, reakcje, linki, ustawienia społeczności)
- Feed: zakładka „Szukaj” (tekst i #hashtagi w publicznych społecznościach), więcej reakcji (👍 😂 😮 😢 🔥 obok ❤), „Kopiuj link do wpisu” (`shyfr-p:`), otwieranie linku z „Kod” w Społecznościach.
- Społeczności: opis, kategoria i regulamin edytowane przez administrację (właściciela i moderatorów), regulamin widoczny dla członków.
- Przekaźnik: akcja `community.update`, pole `rules` (z migracją starszych baz); 37 testów.

## Etap 14 (odpowiedzi na komentarze)
- Wątki komentarzy: „Odpowiedz” i „Odpowiedzi (n)” przy każdym komentarzu; odpowiedź na Twój komentarz pojawia się w Aktywności. Przekaźnik ogranicza głębokość wątku do 6 poziomów; 38 testów.

## Etap 15 (ankiety w społecznościach)
- Ankiety we wpisach publicznych: 2–6 opcji, jeden głos na klucz społeczności, zmiana głosu, wyniki z paskami procentowymi. Wynik nie ujawnia, kto jak głosował; przekaźnik zna tylko klucz społeczności. Ankieta w kanale ogłoszeń tylko dla administracji.
- Przekaźnik: akcje `poll.create` (z PoW) i `poll.vote`; 41 testów.

## Etap 16 (ankiety w czatach prywatnych)
- Ankieta w rozmowie 1:1: „Wyślij → Ankieta”, 2–6 opcji; wysyłana jako zwykła wiadomość (szyfrowanie i skrzynka bez zmian), głosy jako osobne zaszyfrowane ramki (KIND_VOTE). Głosują obie osoby, głos można zmienić. Starsze wersje pokażą ankietę jako tekst.
- Baza: tabela `poll_votes` (też w kopii zapasowej). Testy: `PollCodecTest`.
- Uwaga: przypinanie wiadomości w czatach prywatnych już istniało (KIND_PIN).

## Etap 17 (nie przeszkadzać, system projektowy)
- Tryb „nie przeszkadzać” (Ustawienia → Powiadomienia): okno godzin (także przez północ) wyłącza powiadomienia o wiadomościach i aktywności. Test: `DndTest`.
- `docs/DESIGN_SYSTEM.md`: kolory, typografia, odstępy, komponenty, stany i znane braki dostępności.

## Etap 18 (domyślny przekaźnik)
- Feed i społeczności działają bez konfiguracji, jeśli wersja ma wbudowany domyślny przekaźnik (`-PshyfrDefaultRelay`, w CI zmienna `SHYFR_DEFAULT_RELAY`). Użytkownik może wpisać własny, wyłączyć przekaźnik albo wrócić do domyślnego.

## Etap 19 (darmowe hostowanie przekaźnika)
- `relay/setup-termux.sh` (stary telefon) i `relay/setup-linux.sh` (Raspberry Pi/komputer/VPS): instalacja, usługa onion, autostart, codzienna kopia bazy. Przekaźnik używa trybu WAL (odporność na nagłe wyłączenie).

## Etap 20 (audyt, przekaźniki zapasowe, TalkBack)
- `docs/AUDIT_STATUS.md`: stan wszystkich wymagań (✅/🟡/❌) i priorytety P0–P3.
- Skrzynka: do 3 przekaźników (główny + 2 dodatkowe), zapis do wszystkich i odczyt ze wszystkich; Ustawienia → Dodatkowe przekaźniki skrzynki.
- Dostępność: opisy TalkBack dla nawigacji i przycisków paska; większy obszar dotyku menu wpisu.
