# Wdrożenie serwera SHYFR 2.0

**Nie wdrożono niczego.** Poniżej gotowa konfiguracja; wymaga od właściciela: domeny z DNS na serwer, hostingu (VPS), decyzji o regionie i retencji logów.

## Kroki
1. VPS (Linux), użytkownik `shyfr`, katalogi `/opt/shyfr` (kod), `/var/lib/shyfr` (baza, `backups/`), `/etc/shyfr/server.env` (chmod 600).
2. Skopiuj `deploy/env.production.example` → `/etc/shyfr/server.env`.
3. `deploy/shyfr-server.service` → `/etc/systemd/system/`, `systemctl enable --now shyfr-server`.
4. Caddy: `deploy/Caddyfile.example` (podstaw domenę); TLS automatycznie. Serwer słucha tylko na 127.0.0.1.
5. Kopie: `deploy/shyfr-backup.service` + `.timer`. **Kopiuj je poza serwer, zaszyfrowane, i co jakiś czas testuj odtworzenie** (`python3 -m shyfr_server.backup restore <kopia> <baza>`). Kopia zawiera dane tożsamości (klucze publiczne, kolejki szyfrogramów).
6. Monitoring: sprawdzaj `GET /readyz` (200 gotowy, 503 nie) i `/healthz` (proces żyje); alarmuj na 5xx i na brak miejsca na dysku.
## Środowiska
`env.development.example`, `env.staging.example`, `env.production.example`: osobne bazy i instancje; staging bez danych prawdziwych użytkowników.
## Zasady
Brak sekretów w repo; proxy musi nadpisywać `X-Forwarded-For`; serwer niedostępny bezpośrednio z internetu; logi dostępu proxy zawierają IP (krótka retencja, polityka prywatności musi to opisywać).
## Ograniczenia
Jedna instancja, SQLite. Skalowanie poziome wymaga PostgreSQL i współdzielonego `RateLimitStore`. Brak WebSocket, FCM, magazynu mediów.
