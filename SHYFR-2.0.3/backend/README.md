# SHYFR 2.0: serwer referencyjny

Wykonywalna specyfikacja i fundament (konta, sesje, urządzenia, klucze, kolejka wiadomości). **Nie jest gotowy produkcyjnie** (patrz `docs/2.0/`).

## Uruchomienie i testy
```
cd backend
python3 -m unittest discover -s tests -t . -v      # 103 testy, ok. 1 min
SHYFR_DB_PATH=./data/shyfr.db python3 -m shyfr_server.server   # nasłuchuje na 127.0.0.1:8080
```
Konfiguracja: zmienne środowiskowe, wzór w `../.env.example` (same przykłady, bez sekretów).

## Wdrożenie za reverse proxy (TLS kończy proxy)
Serwer **nie obsługuje TLS** i nie wolno go wystawiać bezpośrednio. Przykład Caddy (certyfikat Let's Encrypt automatycznie):
```
api.twoja-domena.example {
    reverse_proxy 127.0.0.1:8080
    log { output discard }        # bez logów dostępu: nie zapisujemy adresów IP
}
```
**Zaufanie do proxy:** domyślnie nagłówek `X-Forwarded-For` jest IGNOROWANY. Ustaw `SHYFR_TRUSTED_PROXIES=127.0.0.1/32,::1/128` (adresy TCP proxy), a proxy musi nadpisywać ten nagłówek (Caddy domyślnie; nginx: `proxy_set_header X-Forwarded-For $remote_addr;`). Stara zmienna `SHYFR_TRUSTED_PROXY` została usunięta i serwer nie wystartuje, gdy jest ustawiona. Wiele procesów na jednym hoście: `SHYFR_RATE_LIMIT_BACKEND=sqlite`. Szczegóły: `docs/2.0/SHYFR_2_BACKEND_HARDENING.md`.
Przykład jednostki systemd:
```
[Service]
User=shyfr
WorkingDirectory=/opt/shyfr/backend
EnvironmentFile=/etc/shyfr/server.env
ExecStart=/usr/bin/python3 -m shyfr_server.server
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/shyfr
```
Kopie bazy: `sqlite3 /var/lib/shyfr/shyfr.db ".backup /var/backups/shyfr-$(date +%F).db"` z crona; przywracanie sprawdzaj regularnie.
