# Zależności i licencje

Licencja projektu: **AGPL-3.0** (`LICENSE`). Lista wynika z `app/build.gradle.kts`; licencje wpisano z wiedzy autora, nie z automatycznego skanu (to nie jest porada prawna).

| Zależność | Rola | Licencja | Zgodność z AGPL-3.0 |
|---|---|---|---|
| `org.signal:libsignal-android` / `libsignal-client` (wersja w `gradle.properties`) | E2EE | AGPL-3.0 | tak (ta sama licencja) |
| AndroidX (Compose, Material3, Activity, Core, Fragment, Lifecycle, WorkManager) | UI, praca w tle | Apache-2.0 | tak (jednokierunkowo: Apache-2.0 → GPLv3/AGPLv3) |
| kotlinx-coroutines | współbieżność | Apache-2.0 | tak |
| Tink (`tink-android`) | krypto pomocnicze | Apache-2.0 | tak |
| Argon2kt | Argon2id | MIT | tak |
| Firebase Messaging (`firebase-messaging`) | opcjonalny push | warunki Google (Android SDK / Play Services, własnościowe) | **do decyzji właściciela**: biblioteka jest linkowana do APK nawet gdy FCM jest wyłączony. Jeśli zgodność z AGPL ma być bez zastrzeżeń (np. F-Droid), usuń tę zależność i `msg/Push.kt`; aplikacja działa wtedy na samym WorkManagerze |
| junit, org.json (tylko testy) | testy | EPL-1.0 / licencja JSON | nie trafiają do APK |

Backend (`backend/`) używa wyłącznie biblioteki standardowej Pythona.
Uwaga AGPL §13: operator serwera z zmodyfikowanym kodem musi zapewnić użytkownikom dostęp do źródeł.
