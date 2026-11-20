# Lista Google Play (zamknięta beta)

Stan z kodu: targetSdk 36 ✔ (w pliku Gradle), minSdk 29, versionName `1.1.0-rc27` (do zmiany), `allowBackup=false` ✔, cleartext wyłączony w wydaniu ✔, `foregroundServiceType=specialUse` (wymaga deklaracji), minify wyłączony.
**Niezweryfikowane (wymagają budowy):** 16 KB page size (natywne biblioteki: sprawdź `zipalign -c -P 16` i `llvm-readelf` na .so), AAB, podpis.
**Do przygotowania przez właściciela:** polityka prywatności pod publicznym URL (uwzględnić bezpośrednie połączenie i IP, logi proxy), regulamin + akceptacja przed publikacją UGC, Data Safety, usuwanie konta (UI + strona WWW), zgłaszanie/blokowanie w UGC, deklaracja foreground service, klasyfikacja treści, konto dewelopera, Play App Signing/keystore, grafiki (ikona 512×512, grafika funkcji 1024×500, min. 2 zrzuty ekranu telefonu), krótki opis (≤80 znaków), pełny opis (≤4000), testerzy (lista e-maili lub grupa).
**Nie publikuję niczego samodzielnie.** Opis i krótki opis: `docs/PLAY_STORE.md` (z 1.x; wymaga aktualizacji pod 2.0).
