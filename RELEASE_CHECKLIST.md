# Lista przed publikacją SHYFR 2.0
- [ ] Backend wdrożony pod HTTPS (`SERVER_DEPLOYMENT.md`, `deploy/`), zmienna repozytorium `SHYFR2_API_BASE_URL` ustawiona.
- [ ] Wrapper z artefaktu `gradle-wrapper-do-zatwierdzenia` zatwierdzony w repozytorium.
- [ ] CI zielone (APK debug i release, testy, lint).
- [ ] Test sesji E2EE między dwoma urządzeniami (pierwsza wiadomość, odpowiedź, restart aplikacji, zmiana klucza i „Zaufaj nowemu kluczowi").
- [ ] Test dwóch telefonów: rejestracja, rozmowa, status dostarczenia, użytkownik offline, restart aplikacji, Wi-Fi i internet mobilny.
- [ ] Polityka prywatności i formularze Google Play opisujące połączenie HTTPS z backendem i opcjonalny FCM.
- [ ] Keystore wydania zapisany w bezpiecznym miejscu; R8/proguard dopiero po testach.
- [ ] Audyt kryptografii i backendu przed szerokim wydaniem.
