# Znane problemy
- E2EE (libsignal) jest w kodzie, ale niekompilowane i nieprzetestowane między dwoma urządzeniami; pierwszy build CI może wymagać poprawy sygnatur API (zob. `BUILD_INSTRUCTIONS.md`).
- Backend wymaga wdrożenia pod HTTPS; bez `SHYFR2_API_BASE_URL` aplikacja pokazuje „serwer niewdrożony".
- Brak `gradle-wrapper.jar` w archiwum: generuje go workflow (artefakt do zatwierdzenia).
- Brak rotacji podpisanego prekeya; brak wielourządzeniowej synchronizacji historii (każde urządzenie ma własną).
- Powiadomienia push (FCM) działają tylko po podaniu parametrów Firebase; bez nich synchronizacja co ok. 15 min i przy otwarciu aplikacji (ograniczenia Dozy).
- AGPL §13: po utworzeniu publicznego repozytorium dodaj w aplikacji/serwerze link do źródeł dla użytkowników łączących się z serwerem.
