# SHYFR 2.0: decyzja o protokole E2EE (etap 39)

**Status: decyzja NIEZAMKNIĘTA. Wybrany jest kandydat domyślny, ale integracja jest zablokowana przez kwestię licencji, którą może rozstrzygnąć tylko właściciel projektu. E2EE w SHYFR 2.0 NIE jest zaimplementowane.**

Data sprawdzenia źródeł: 17.11.2026. Zasada: żadnej własnej kryptografii. Oznaczenia: **[zweryfikowano]** = przeczytane w repozytorium/dokumentacji w tym etapie; **[do weryfikacji]** = z mojej wiedzy, NIE sprawdzone w tym etapie, trzeba potwierdzić na przypiętej wersji przed pracą.

## 0. Stan wyjściowy
* Android 1.x ma własną implementację Double Ratchet (`crypto/Ratchet.kt`, X25519 + HKDF + AES-GCM). Komentarz w kodzie: "własna implementacja według specyfikacji, niezaudytowana. Przed wydaniem: audyt lub libsignal". **Nie rozbudowujemy jej w 2.0.**
* Repozytorium SHYFR **nie zawiera pliku LICENSE** [zweryfikowano: brak w drzewie projektu]. Bez tej decyzji nie da się ocenić zgodności żadnej biblioteki copyleft.
* Serwer 2.0 traktuje treść jako nieprzezroczysty szyfrogram i ma katalog kluczy w stylu X3DH: klucz tożsamości, podpisany prekey, kluczy jednorazowe, per urządzenie.

## 1. Kandydaci i fakty

### A. libsignal (signalapp/libsignal), Signal Protocol
* Licencja: **AGPL-3.0** [zweryfikowano: README, "Copyright 2020-2026 Signal Messenger, LLC, Licensed under the GNU AGPLv3"].
* Wsparcie spoza Signala: README mówi wprost, że **"Use outside of Signal is unsupported"** oraz że **"All APIs and implementations are subject to change without notice"**, w tym warstwy JNI; zmiany niezgodne wstecz są odzwierciedlane w numerze wersji "na zasadzie best-effort" [zweryfikowano].
* Android: artefakty `org.signal:libsignal-android` + `libsignal-client` (trzeba oba; z `libsignal-client` wykluczyć biblioteki Windows/macOS), natywne biblioteki dla armeabi-v7a, arm64-v8a, x86, x86_64 [zweryfikowano]. Publikowane "for its own use" w repozytorium Maven `https://build-artifacts.signal.org/libraries/maven/`; **starsze wydania były na Maven Central** [zweryfikowano], więc wymagana jest dodatkowa pozycja w `repositories`, a SHYFR ma dziś dodatkowe repozytorium tylko dla `info.guardianproject`.
* Budowanie ze źródeł: Rust (przypięta wersja), JDK **21** dla budowy Androida, Android NDK/SDK, clang/cmake/protoc, Python 3.11+ [zweryfikowano]. Projekt SHYFR używa JDK 17 (CI) i `compileOptions` Java 17; sama aplikacja może zależeć od gotowego AAR, ale własny build biblioteki wymaga JDK 21.
* Utrzymanie: aktywne, ok. 4,5 tys. commitów, ~6,1 tys. gwiazdek, prawa autorskie do 2026 [zweryfikowano]. Wydania wspierają potrzeby Signala, nie zewnętrznych użytkowników.
* Własności protokołu [do weryfikacji na przypiętej wersji]: X3DH/PQXDH z prekeyami post-kwantowymi (Kyber), Double Ratchet (forward secrecy + post-compromise security), sesje per (adres, urządzenie), odzyskiwanie po utracie sesji przez ponowną wymianę kluczy, grupy przez Sender Keys. Wielourządzeniowość jest po stronie aplikacji (fan-out per urządzenie): serwer 2.0 już modeluje urządzenia i ich kolejki. Aplikacja musi sama zaimplementować magazyny (sesje, tożsamości, prekeye, sender keys) i ich trwałość.
* **Konsekwencja dla serwera [do weryfikacji]:** prekeye Kyber są duże (rzędu 1,5 kB); obecny limit 400 znaków base64 na klucz i brak pól na prekey Kyber/ostatniej szansy wymagają migracji schematu i limitów.

### B. OpenMLS (openmls/openmls), MLS (RFC 9420)
* Licencja **MIT** [zweryfikowano]. Rust. Standard IETF (RFC 9420) z grupami jako podstawą, forward secrecy i post-compromise security; każde urządzenie to osobny liść grupy [do weryfikacji szczegółów].
* Android: **nie znalazłem oficjalnych powiązań Kotlin/Android**. Znalazłem tylko wrapper społeczności dla Darta/Fluttera (też MIT; Android SDK 24+, arm64/armv7/x64) [zweryfikowano], który nie pasuje do aplikacji Compose/Kotlin. Wymagałoby to własnych powiązań JNI/UniFFI, czyli utrzymywania kodu klejącego wokół biblioteki kryptograficznej.
* Wymaga zaprojektowania Delivery Service i Authentication Service (obecny serwer ma tylko kolejkę i katalog kluczy) i wyboru zestawu szyfrów.

### C. Wire core-crypto (wireapp/core-crypto), MLS + Proteus
* Licencja **GPL-3.0** [zweryfikowano: POM i package.json]. Powiązania Kotlin (Android/JVM) w Maven [zweryfikowano], ale widoczne wersje to wstępne/RC (`0.6.0-rc.3…`, npm `1.0.0-rc.60`), czyli **niestabilne API** [zweryfikowano].
* Copyleft tej samej rodziny co A (bez klauzuli sieciowej), więc nie omija problemu licencyjnego, a ma gorszą dojrzałość API.

### D. vodozemac (matrix-org/vodozemac), Olm + Megolm
* Licencja **Apache-2.0** [zweryfikowano]. Rust, brak powiązań Android w samym repozytorium; używana przez klientów Matrix [do weryfikacji dla Kotlin]. Megolm (grupy) z założenia nie daje post-compromise security [do weryfikacji]. Źródło wtórne (audyt pakietu opakowującego, maj 2026) opisuje ujawnione w lutym 2026 problemy kryptograficzne w vodozemac (m.in. niekontrybutywne Diffie-Hellman), naprawione w 0.10.0; **nie sprawdziłem tego w źródle pierwotnym**, więc traktuję jako ostrzeżenie wymagające weryfikacji, a nie jako fakt.

### E. Tink
Biblioteka prymitywów, już w aplikacji. **Nie jest protokołem wiadomości** (brak ratchetu/wymiany kluczy asynchronicznej); budowanie z niej protokołu to właśnie własna kryptografia. Odrzucone jako rozwiązanie E2EE.

### F. Własny Double Ratchet z 1.x
Odrzucony (niezaudytowany; patrz sekcja 0).

## 2. Ocena

| | Licencja | Utrzymanie/stabilność dla obcych | Gotowość na Androida | Grupy | Wielourządzeniowość |
|---|---|---|---|---|---|
| libsignal | AGPL-3.0 (blokada, jeśli SHYFR nie będzie AGPL) | aktywne, ale "unsupported outside Signal", API bez gwarancji | gotowe AAR (osobne repo Maven) | Sender Keys [do weryf.] | fan-out w aplikacji |
| OpenMLS | MIT | aktywne, 0.x | brak oficjalnych wiązań (praca własna) | natywnie | natywnie (liście) |
| core-crypto | GPL-3.0 | RC/pre-1.0 | wiązania Kotlin są | MLS natywnie | tak |
| vodozemac | Apache-2.0 | aktywne, historia błędów do sprawdzenia | brak wiązań w repo | Megolm (słabsze) | per urządzenie |

## 3. Decyzja
1. **Kandydat domyślny: libsignal, pod warunkiem że właściciel projektu przyjmie AGPL-3.0 dla klienta Androida** (i opublikuje źródła zgodnie z licencją, także dla wydań w sklepie). To jedyna opcja z gotowymi artefaktami Androida i protokołem sprawdzonym w produkcyjnym komunikatorze. Ryzyka do zaakceptowania: brak wsparcia poza Signalem, zmiany API bez ostrzeżenia (**przypiąć dokładną wersję, nie używać zakresów**), obowiązek własnych magazynów sesji.
2. **Alternatywa, jeśli właściciel chce licencji liberalnej: OpenMLS (MIT)**, kosztem własnych powiązań Android i zaprojektowania DS/AS.
3. **Nie wybieram** żadnej opcji za właściciela: wybór licencji jest decyzją prawną i biznesową, nie techniczną.

## 4. Blokady (co musi się wydarzyć przed integracją)
1. Decyzja właściciela o licencji SHYFR (dodać plik LICENSE) i zgoda na wybraną ścieżkę.
2. Weryfikacja punktów **[do weryfikacji]** na dokładnie przypiętej wersji (PQXDH/Kyber, Sender Keys, API magazynów).
3. Rozstrzygnięcie w kodzie, czy jedna biblioteka na Androida wystarczy bez JDK 21 po stronie aplikacji (czy AAR nie wymusza nowszego JDK/AGP; **nie sprawdzano, brak Gradle w tym środowisku**).
4. Migracja schematu serwera (prekeye Kyber, ostatniej szansy, limity rozmiarów) i testy.
5. Prawdziwy test dwóch urządzeń.

## 5. Czego etap 39 NIE zrobił
* Nie dodał żadnej zależności kryptograficznej, nie zaszyfrował ani jednej wiadomości. `MessageCipher` w kliencie to interfejs bez implementacji produkcyjnej; interfejs użytkownika nie wysyła wiadomości ścieżką 2.0.
* `identity_key` rejestrowany dziś dla urządzenia to tymczasowy klucz X25519 (`DeviceKey`), nieużywany do szyfrowania; po integracji protokołu urządzenia trzeba zarejestrować od nowa.
