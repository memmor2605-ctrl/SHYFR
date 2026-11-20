# Instrukcja budowania (GitHub Actions)

**Kod nie był kompilowany w środowisku, w którym powstał. Pierwszy build w CI jest pierwszą weryfikacją kompilacji, w szczególności sygnatur libsignal (zob. „Gdy build się nie powiedzie").**

## 1. Wrzuć projekt na GitHub
Wypchnij całą zawartość archiwum do repozytorium. Workflow `CI` uruchamia się przy `push`, `pull_request` i ręcznie (Actions → CI → Run workflow).

## 2. Adres backendu (jedno miejsce)
Settings → Secrets and variables → Actions → **Variables** → `SHYFR2_API_BASE_URL` = `https://twoja-domena` (HTTPS, bez ścieżki). Bez zmiennej APK zbuduje się, ale aplikacja pokaże „serwer niewdrożony" i z niczym się nie połączy. Wdrożenie serwera: `SERVER_DEPLOYMENT.md`.

## 3. Gradle Wrapper (jednorazowo)
W archiwum brakuje `gradle/wrapper/gradle-wrapper.jar` (binarki nie dało się wygenerować bez Gradle i sieci). Workflow generuje wrapper sam i wystawia go jako artefakt **gradle-wrapper-do-zatwierdzenia**. Pobierz go i zatwierdź (`gradlew`, `gradlew.bat`, `gradle/wrapper/`), a kolejne buildy będą powtarzalne.

## 4. Artefakty
- `shyfr-debug-apk`: instalowalny APK (podpisany kluczem debug).
- `shyfr-release-apk-niepodpisany`: APK release bez podpisu.
- `raporty-testow-i-lint`.
APK debug jest wysyłany jako artefakt PRZED testami, więc błąd testu nie odbiera Ci pliku.

## Podpisywanie wydania
Plik `keystore.properties` (poza repozytorium, jest w `.gitignore`) wg `signingConfigs` w `app/build.gradle.kts`. Utrata klucza = brak możliwości aktualizacji aplikacji.

## Gdy build się nie powiedzie
Pierwszy build CI wykazał niezgodności z libsignal 0.63.0 (poprawione). Kod jest pisany pod API 0.63.0; po podniesieniu wersji sprawdź w `e2ee/LibsignalMessageCipher.kt`: saveIdentity, markKyberPreKeyUsed, generowanie kluczy EC, SenderKeyStore.

## Backend lokalnie (opcjonalnie)
```
cd backend
SHYFR_DB_PATH=./data/shyfr.db python3 -m shyfr_server.server
curl http://127.0.0.1:8080/readyz
```
