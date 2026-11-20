# SHYFR 2.0

Komunikator Android z kontami na zarządzanym backendzie. Jedna architektura: HTTPS do serwera SHYFR, bez Tora, bez ręcznej konfiguracji serwerów i bez własnego serwera po stronie użytkownika.

## Jak to działa
- Aplikacja łączy się z backendem pod jednym adresem ustawianym przy budowaniu (`shyfr2ApiBaseUrl` w `gradle.properties`, w CI: zmienna repozytorium `SHYFR2_API_BASE_URL`). Użytkownik niczego nie wpisuje.
- Rejestracja i logowanie, sesje, urządzenia (`api/`), wiadomości z kolejką wysyłania i odbioru, statusami i synchronizacją w tle (`msg/`).
- E2EE: libsignal (PQXDH + Double Ratchet), kod w `app/src/main/java/com/shyfr/app/e2ee/`. Treść jest szyfrowana na urządzeniu nadawcy i odszyfrowywana na urządzeniu odbiorcy; backend widzi tylko nieprzezroczysty szyfrogram. Stan: `SECURITY_STATUS.md`.
- Backend: `backend/` (Python), wdrożenie: `SERVER_DEPLOYMENT.md`, `deploy/`.

## Budowanie
Wyłącznie przez GitHub Actions (`.github/workflows/android.yml`). Zob. `BUILD_INSTRUCTIONS.md`.

## Licencja
AGPL-3.0, pełny tekst w `LICENSE`. Zależności: `docs/2.0/DEPENDENCIES_AND_LICENSES.md`.
