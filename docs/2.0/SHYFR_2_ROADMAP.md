# SHYFR 2.0: plan

Stan na etap 38. „Zrobione" oznacza uruchomione i przetestowane tutaj (tylko serwer). Wszystko po stronie Androida jest nietestowane na urządzeniach.

## Zrobione
Serwer referencyjny: konta pseudonimowe, sesje z rotacją, urządzenia, katalog kluczy, trwała kolejka z idempotencją, wygasaniem, blokadami i usuwaniem konta; 35 testów (w tym autoryzacja obiektów, wstrzyknięcia w polach, ponowne użycie tokenów, restart serwera). Schemat z migracjami. Dokumentacja.

## Następne kroki (kolejność)
1. **Decyzja o protokole E2EE** (libsignal lub inny sprawdzony): licencja, utrzymanie, Android. Bez tego nie ma sensu pisać klienta wiadomości.
2. **Wybór i przeniesienie serwera na docelowy stos** (Ktor lub Go + PostgreSQL), z zachowaniem zestawu testów jako specyfikacji; dodać TLS za proxy, WebSocket i FCM.
3. **Klient Androida: warstwa sieci** (HTTPS, sesje, odświeżanie, kolejka wysyłki z idempotencją) obok istniejącej, bez usuwania Tora.
4. **Odzyskiwanie konta w 2.0** (kod odzyskiwania + zaszyfrowana koperta nieodczytywalna dla serwera).
5. **Media** (szyfrowanie po stronie klienta, magazyn S3, adresy z ograniczonym dostępem).
6. **Feed i społeczności po stronie serwera** z autoryzacją dla każdego poziomu widoczności i rolami.
7. **Usunięcie Tora** dopiero po punkcie 3 i okresie równoległym.
8. Testy na dwóch telefonach, audyt kryptografii, Google Play (regulamin, polityka, usuwanie konta, adres żądania usunięcia).

## Ryzyka
Zmiana protokołu zrywa historię rozmów (patrz MIGRATION). Centralny serwer to stałe koszty, odpowiedzialność prawna za treści użytkowników i pojedynczy punkt awarii. Brak anonimowości sieciowej po usunięciu Tora. Zakres prompu jest rzędu pracy zespołu przez miesiące; ten etap to pierwszy fundament.
