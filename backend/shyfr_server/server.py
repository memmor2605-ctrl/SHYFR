"""SHYFR 2.0: serwer referencyjny (konta pseudonimowe, sesje, urządzenia, katalog kluczy, trwała kolejka zaszyfrowanych wiadomości).

Co to jest: wykonywalna specyfikacja i działający fundament modularnego monolitu, uruchamiany i testowany tylko z biblioteką standardową
Pythona + SQLite. Co to NIE jest: gotowy produkcyjnie serwer. Brakuje TLS (kończy go reverse proxy), WebSocket, FCM, mediów, feedu,
społeczności i PostgreSQL (schemat w database/migrations jest pisany pod przeniesienie). Zob. docs/2.0/.

Serwer nigdy nie widzi treści prywatnych wiadomości: `payload` to nieprzezroczysty szyfrogram od klienta.
"""
import base64, contextlib, hashlib, hmac, ipaddress, json, os, re, secrets, sqlite3, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from .ratelimit import MemoryRateLimitStore, SqliteRateLimitStore

USERNAME_RE = re.compile(r"^[a-z0-9_]{3,24}$")
CMID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
B64_RE = re.compile(r"^[A-Za-z0-9+/_-]+={0,2}$")
ID_RE = re.compile(r"^[0-9a-f]{32}$")
MAX_BODY = 200 * 1024
MAX_PAYLOAD_CHARS = 90_000          # ok. 64 kB szyfrogramu po base64; większe pliki to osobna usługa mediów
ACCESS_TTL = 900
REFRESH_TTL = 30 * 86400
STATUS_KEEP = 7 * 86400             # tyle trzymamy sam stan wiadomości (bez treści) po doręczeniu lub wygaśnięciu
MAX_OTK_UPLOAD = 100
MAX_OTK_TOTAL = 200
MAX_KYBER_UPLOAD = 100
MAX_KYBER_TOTAL = 200
MAX_KYBER_KEY_CHARS = 4096          # prekey KEM jest rzędu kilku kB (zob. docs/2.0/SHYFR_2_CRYPTO_DECISION.md)
MAX_ACK_BATCH = 100
LAST_SEEN_STEP = 60
# --- klucze jednorazowe: ochrona przed opróżnianiem puli (zob. docs/2.0/SHYFR_2_BACKEND_HARDENING.md)
OTK_CLAIM_TTL = 86400               # ten sam pytający dostaje przez 24 h ten sam klucz urządzenia (ponowienie nic nie zużywa)
OTK_DEVICE_HOURLY_CAP = 10          # tyle kluczy jednorazowych jednego urządzenia może zostać wydanych WSZYSTKIM pytającym łącznie w ciągu godziny
OTK_REQUESTER_HOURLY_CAP = 60       # tyle NOWYCH kluczy jednorazowych (dowolnych urządzeń) może pobrać jedno konto w ciągu godziny
PUT_KEYS_PER_HOUR = 30
OTK_LOW_WATERMARK = 20              # poniżej tej liczby klient powinien uzupełnić pulę
# --- logowanie: patrz h_login
LOGIN_FAIL_WINDOW = 900
LOGIN_ACCOUNT_FAIL_WINDOW = 3600
DUMMY_SALT = b"\0" * 16
DUMMY_HASH = "0" * 64
SCRYPT = dict(n=2 ** 14, r=8, p=1, maxmem=64 * 1024 * 1024, dklen=32)
PUSH_TOKEN_RE = re.compile(r"^[A-Za-z0-9_:\-.]{20,4096}$")
MIGRATIONS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "database", "migrations")


class ApiError(Exception):
    def __init__(self, status, msg):
        super().__init__(msg); self.status = status; self.msg = msg


class Config:
    def __init__(self, db_path=":memory:", message_ttl=30 * 86400, trusted_proxies=(), proxy_header="X-Forwarded-For", clock=None,
                 register_per_hour=20, login_per_10min=60, send_per_min=120,
                 login_fails_per_pair=5, login_fails_per_account=100, login_fails_per_ip=30,
                 rate_limit_backend="memory", fault=None):
        self.db_path = db_path; self.message_ttl = message_ttl
        self.trusted_proxies = tuple(ipaddress.ip_network(n, strict=False) for n in trusted_proxies)
        self.proxy_header = proxy_header
        self.clock = clock or (lambda: int(time.time()))
        self.register_per_hour = register_per_hour; self.login_per_10min = login_per_10min; self.send_per_min = send_per_min
        self.login_fails_per_pair = login_fails_per_pair; self.login_fails_per_account = login_fails_per_account; self.login_fails_per_ip = login_fails_per_ip
        if rate_limit_backend not in ("memory", "sqlite"): raise ValueError("rate_limit_backend: memory|sqlite")
        self.rate_limit_backend = rate_limit_backend
        self.fault = fault          # tylko testy: wywoływane w punktach awarii (np. delete:after_messages)

    @staticmethod
    def from_env():
        e = os.environ
        if "SHYFR_TRUSTED_PROXY" in e:
            raise SystemExit("SHYFR_TRUSTED_PROXY zostało usunięte (było niebezpieczne: ufało nagłówkowi od każdego). "
                             "Ustaw SHYFR_TRUSTED_PROXIES=<lista CIDR adresów proxy, po przecinku>, np. 127.0.0.1/32,::1/128, i usuń starą zmienną.")
        proxies = [x.strip() for x in e.get("SHYFR_TRUSTED_PROXIES", "").split(",") if x.strip()]
        return Config(db_path=e.get("SHYFR_DB_PATH", "./data/shyfr.db"), message_ttl=int(e.get("SHYFR_MESSAGE_TTL", 30 * 86400)),
                      trusted_proxies=proxies, proxy_header=e.get("SHYFR_PROXY_HEADER", "X-Forwarded-For"),
                      rate_limit_backend=e.get("SHYFR_RATE_LIMIT_BACKEND", "memory"))


def client_ip(peer, header_value, trusted):
    """Adres klienta do ograniczania żądań. Nagłówka proxy słuchamy WYŁĄCZNIE, gdy połączenie TCP przyszło z zaufanego proxy.
    Wtedy idziemy listą od PRAWEJ (dopisuje ją najbliższy proxy, więc prawe wpisy są wiarygodne; lewe może wpisać klient)
    i bierzemy pierwszy adres, który sam nie jest zaufanym proxy. Błędny wpis => ignorujemy nagłówek. IPv6 sprowadzamy do /64."""
    def norm(ip):
        if ip.version == 6:
            if ip.ipv4_mapped: return str(ip.ipv4_mapped)
            return str(ipaddress.ip_network((int(ip) >> 64 << 64, 64)))
        return str(ip)
    try: p = ipaddress.ip_address(peer.split("%")[0])
    except ValueError: return "invalid"
    if not trusted or not header_value or not any(p in n for n in trusted): return norm(p)
    for hop in reversed([h.strip() for h in header_value.split(",")]):
        try: ip = ipaddress.ip_address(hop)
        except ValueError: return norm(p)
        if not any(ip in n for n in trusted): return norm(ip)
    return norm(p)


def sha256(s):
    return hashlib.sha256(s.encode() if isinstance(s, str) else s).hexdigest()


def b64decode(s):
    if not isinstance(s, str) or not s or not B64_RE.match(s): raise ValueError("base64")
    return base64.b64decode(s.replace("-", "+").replace("_", "/") + "=" * (-len(s) % 4), validate=False)


class App:
    def __init__(self, cfg):
        self.cfg = cfg
        self._shared = None
        if cfg.db_path == ":memory:":   # jedno połączenie współdzielone (testy)
            self._shared = sqlite3.connect(":memory:", check_same_thread=False, isolation_level=None)
        elif os.path.dirname(cfg.db_path):
            os.makedirs(os.path.dirname(cfg.db_path), exist_ok=True)
        self.wlock = threading.RLock()
        self.last_sweep = 0
        with self.wlock:
            self._migrate()
        # magazyn ograniczeń: interfejs RateLimitStore (zob. ratelimit.py); dla wielu hostów trzeba podstawić współdzielony
        self.rl = SqliteRateLimitStore(self.conn, self.close, self.wlock) if cfg.rate_limit_backend == "sqlite" else MemoryRateLimitStore()

    # ---- baza
    def conn(self):
        if self._shared is not None:
            c = self._shared
        else:
            c = sqlite3.connect(self.cfg.db_path, isolation_level=None, timeout=10)
            c.execute("PRAGMA journal_mode=WAL")
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA foreign_keys=ON")
        return c

    def close(self, c):
        if self._shared is None: c.close()

    def _migrate(self):
        c = self.conn()
        try:
            c.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)")
            done = {r[0] for r in c.execute("SELECT version FROM schema_migrations")}
            files = sorted(f for f in os.listdir(MIGRATIONS_DIR) if re.match(r"^\d{4}_.*\.sql$", f))
            for f in files:
                v = int(f[:4])
                if v in done: continue
                with open(os.path.join(MIGRATIONS_DIR, f), encoding="utf-8") as fh: sql = fh.read()
                c.executescript("BEGIN;" + sql + "\nCOMMIT;")
                c.execute("INSERT INTO schema_migrations VALUES(?,?)", (v, self.cfg.clock()))
        finally:
            self.close(c)

    def now(self): return self.cfg.clock()

    # ---- transakcje i punkty awarii
    @contextlib.contextmanager
    def tx(self, c):
        """Jawna transakcja zapisu: BEGIN IMMEDIATE, COMMIT przy sukcesie, ROLLBACK przy KAŻDYM wyjątku (też ApiError)."""
        with self.wlock:
            c.execute("BEGIN IMMEDIATE")
            try:
                yield
            except BaseException:
                try: c.execute("ROLLBACK")
                except sqlite3.Error: pass
                raise
            c.execute("COMMIT")

    def fault(self, point):
        if self.cfg.fault: self.cfg.fault(point)

    # ---- ograniczanie liczby żądań (magazyn wymienny, zob. ratelimit.py)
    def limit(self, key, max_n, window):
        if not self.rl.hit(key, self.now(), window, max_n): raise ApiError(429, "Zbyt wiele żądań. Spróbuj później.")

    @staticmethod
    def key(prefix, *parts):
        """Klucz o stałej długości: dane od użytkownika są haszowane, więc nie rozdymają magazynu."""
        return prefix + ":" + sha256("\x00".join(parts))[:24]

    # ---- uwierzytelnianie
    def hash_secret(self, secret, salt):
        return hashlib.scrypt(secret, salt=salt, **SCRYPT).hex()

    def parse_secret(self, v):
        try: s = b64decode(v)
        except Exception: raise ApiError(400, "Zły sekret uwierzytelnienia.")
        if not 32 <= len(s) <= 128: raise ApiError(400, "Zły sekret uwierzytelnienia.")
        return s

    def new_session(self, c, account_id, device_id):
        t = self.now(); access = secrets.token_urlsafe(32); refresh = secrets.token_urlsafe(32); sid = secrets.token_hex(16)
        c.execute("INSERT INTO sessions(id,account_id,device_id,access_hash,access_exp,refresh_hash,refresh_exp,created_at) VALUES(?,?,?,?,?,?,?,?)",
                  (sid, account_id, device_id, sha256(access), t + ACCESS_TTL, sha256(refresh), t + REFRESH_TTL, t))
        return {"access_token": access, "refresh_token": refresh, "expires_in": ACCESS_TTL}

    def authenticate(self, c, token):
        if not token or len(token) > 200: raise ApiError(401, "Wymagane zalogowanie.")
        r = c.execute("""SELECT s.id sid, s.account_id, s.device_id FROM sessions s
                         JOIN devices d ON d.id=s.device_id JOIN accounts a ON a.id=s.account_id
                         WHERE s.access_hash=? AND s.revoked=0 AND s.access_exp>? AND d.revoked=0 AND a.status='active'""", (sha256(token), self.now())).fetchone()
        if not r: raise ApiError(401, "Wymagane zalogowanie.")
        t = self.now()
        if c.execute("SELECT 1 FROM devices WHERE id=? AND last_seen<?", (r["device_id"], t - LAST_SEEN_STEP)).fetchone():
            with self.wlock: c.execute("UPDATE devices SET last_seen=? WHERE id=?", (t, r["device_id"]))
        return r["account_id"], r["device_id"], r["sid"]

    def sweep(self, c):
        t = self.now()
        if t - self.last_sweep < 60: return
        self.last_sweep = t
        with self.wlock:
            c.execute("UPDATE messages SET state='expired', payload=NULL, changed_at=? WHERE payload IS NOT NULL AND expires_at<=?", (t, t))
            c.execute("DELETE FROM messages WHERE payload IS NULL AND changed_at<?", (t - STATUS_KEEP,))
            c.execute("DELETE FROM sessions WHERE refresh_exp<? OR revoked=1 AND created_at<?", (t, t - STATUS_KEEP))
            c.execute("DELETE FROM otk_claims WHERE claimed_at<?", (t - OTK_CLAIM_TTL,))
            c.execute("DELETE FROM kyber_claims WHERE claimed_at<?", (t - OTK_CLAIM_TTL,))
            self.rl.purge(t, 86400)


def need(body, key, kind=str, maxlen=None):
    v = body.get(key)
    if not isinstance(v, kind) or (kind is str and (not v.strip() or (maxlen and len(v) > maxlen))):
        raise ApiError(400, "Nieprawidłowe pole: " + key)
    return v


class Handler(BaseHTTPRequestHandler):
    server_version = "SHYFR"; protocol_version = "HTTP/1.1"
    app = None

    def log_message(self, *a): pass     # brak logów dostępowych: nie zapisujemy adresów ani ścieżek

    def ip(self):
        cfg = self.app.cfg
        hv = ",".join(self.headers.get_all(cfg.proxy_header, [])) if cfg.trusted_proxies else ""
        return client_ip(self.client_address[0], hv, cfg.trusted_proxies)

    def send_json(self, status, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        for k, v in (("Content-Type", "application/json; charset=utf-8"), ("Content-Length", str(len(data))), ("Cache-Control", "no-store"),
                     ("X-Content-Type-Options", "nosniff")): self.send_header(k, v)
        self.end_headers(); self.wfile.write(data)

    def handle_any(self, method):
        try:
            u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
            body = {}
            try: n = int(self.headers.get("Content-Length") or 0)
            except ValueError: raise ApiError(400, "Zły nagłówek Content-Length.")
            if n < 0: raise ApiError(400, "Zły nagłówek Content-Length.")
            if n > MAX_BODY: raise ApiError(413, "Za duże żądanie.")
            if n:
                try: body = json.loads(self.rfile.read(n).decode("utf-8"))
                except Exception: raise ApiError(400, "Zły JSON.")
                if not isinstance(body, dict): raise ApiError(400, "Zły JSON.")
            auth = self.headers.get("Authorization", "")
            token = auth[7:].strip() if auth.startswith("Bearer ") else None
            for m, rx, fn in ROUTES:
                if m == method and (mm := rx.match(u.path)):
                    c = self.app.conn()
                    try:
                        self.app.sweep(c)
                        status, out = fn(self.app, c, self, token, body, q, *mm.groups())
                    finally:
                        self.app.close(c)
                    return self.send_json(status, out)
            raise ApiError(404, "Nie znaleziono.")
        except ApiError as e:
            self.send_json(e.status, {"error": e.msg})
        except Exception as e:      # klientowi nie ujawniamy szczegółów; operatorowi na stderr tylko typ błędu (bez danych)
            print("błąd 500:", type(e).__name__, file=sys.stderr)
            self.send_json(500, {"error": "Błąd serwera."})

    do_GET = lambda s: s.handle_any("GET")
    do_POST = lambda s: s.handle_any("POST")
    do_PUT = lambda s: s.handle_any("PUT")
    do_PATCH = lambda s: s.handle_any("PATCH")
    do_DELETE = lambda s: s.handle_any("DELETE")


# ---------------------------------------------------------------- konta i sesje
def device_from(body, app, c, account_id):
    d = body.get("device")
    if not isinstance(d, dict): raise ApiError(400, "Brak danych urządzenia.")
    name = need(d, "name", str, 60); ik = need(d, "identity_key", str, 200)
    try: b64decode(ik)
    except Exception: raise ApiError(400, "Zły klucz tożsamości.")
    did = secrets.token_hex(16); t = app.now()
    c.execute("INSERT INTO devices(id,account_id,name,identity_key,created_at,last_seen) VALUES(?,?,?,?,?,?)", (did, account_id, name.strip(), ik, t, t))
    return did


def h_register(app, c, req, token, body, q):
    app.limit("reg:" + req.ip(), app.cfg.register_per_hour, 3600)
    username = need(body, "username", str, 40).strip().lower()
    if not USERNAME_RE.match(username): raise ApiError(400, "Nazwa użytkownika: 3-24 znaki a-z, 0-9, _.")
    display = (body.get("display_name") or username)
    if not isinstance(display, str) or not display.strip() or len(display) > 40: raise ApiError(400, "Nieprawidłowa nazwa wyświetlana.")
    secret = app.parse_secret(body.get("auth_secret"))
    salt = secrets.token_bytes(16); aid = secrets.token_hex(16); t = app.now()
    pw_hash = app.hash_secret(secret, salt)            # scrypt poza blokadą zapisu: wolne hashowanie nie blokuje innych zapisów
    with app.tx(c):
        try:
            c.execute("INSERT INTO accounts(id,username,display_name,created_at,auth_salt,auth_hash) VALUES(?,?,?,?,?,?)",
                      (aid, username, display.strip(), t, salt.hex(), pw_hash))
        except sqlite3.IntegrityError:
            raise ApiError(409, "Nazwa użytkownika jest zajęta.")
        did = device_from(body, app, c, aid)
        tokens = app.new_session(c, aid, did)
    return 201, {"account_id": aid, "device_id": did, **tokens}


def h_login(app, c, req, token, body, q):
    """Logowanie bez blokowania cudzych kont.

    Błędne próby liczymy w TRZECH niezależnych licznikach (magazyn RateLimitStore), wszystkie klucze działają tak samo
    dla istniejących i nieistniejących nazw (brak wyliczania użytkowników):
      * (nazwa, adres klienta): `login_fails_per_pair` w 15 min. Atakujący blokuje tylko SAMEGO SIEBIE dla tej nazwy;
        prawowity właściciel logujący się z innego adresu nie jest dotknięty.
      * adres klienta: `login_fails_per_ip` błędów w 10 min (zgadywanie wielu nazw z jednego adresu).
      * nazwa (globalnie): `login_fails_per_account` w godzinę. Ogranicza rozproszone zgadywanie; to jedyny licznik, którym
        rozproszony atakujący (wiele adresów) może wstrzymać logowanie ofiary, więc próg jest wysoki. Zob. docs, ryzyko szczątkowe.
    Sprawdzenie blokad nie czyta bazy, a hash scrypt liczymy zawsze (prawdziwy lub fikcyjny), więc odpowiedź i czas nie zdradzają, czy konto istnieje.
    """
    ip = req.ip(); app.limit("login:" + ip, app.cfg.login_per_10min, 600)
    username = need(body, "username", str, 40).strip().lower()
    secret = app.parse_secret(body.get("auth_secret"))
    t = app.now(); cfg = app.cfg
    k_pair = app.key("lf", username, ip); k_acct = app.key("lfa", username); k_ip = app.key("lfi", ip)
    if (app.rl.count(k_pair, t, LOGIN_FAIL_WINDOW) >= cfg.login_fails_per_pair
            or app.rl.count(k_ip, t, 600) >= cfg.login_fails_per_ip
            or app.rl.count(k_acct, t, LOGIN_ACCOUNT_FAIL_WINDOW) >= cfg.login_fails_per_account):
        raise ApiError(429, "Zbyt wiele prób. Spróbuj później.")
    a = c.execute("SELECT * FROM accounts WHERE username=? AND status='active'", (username,)).fetchone()
    computed = app.hash_secret(secret, bytes.fromhex(a["auth_salt"]) if a else DUMMY_SALT)
    ok = hmac.compare_digest(computed, a["auth_hash"] if a else DUMMY_HASH) and a is not None
    if not ok:
        for k in (k_pair, k_acct, k_ip): app.rl.record(k, t)
        raise ApiError(401, "Nieprawidłowy login lub sekret.")
    app.rl.reset(k_pair)
    with app.tx(c):
        if body.get("device_id") is not None:
            did = body["device_id"]
            if not isinstance(did, str) or not ID_RE.match(did) or not c.execute("SELECT 1 FROM devices WHERE id=? AND account_id=? AND revoked=0", (did, a["id"])).fetchone():
                raise ApiError(400, "Nieznane urządzenie.")
        else:
            did = device_from(body, app, c, a["id"])
        tokens = app.new_session(c, a["id"], did)
    return 200, {"account_id": a["id"], "device_id": did, **tokens}


def h_refresh(app, c, req, token, body, q):
    rt = need(body, "refresh_token", str, 200); h = sha256(rt); t = app.now()
    with app.wlock:
        s = c.execute("SELECT * FROM sessions WHERE refresh_hash=? OR prev_refresh_hash=?", (h, h)).fetchone()
        if not s: raise ApiError(401, "Sesja wygasła.")
        if s["prev_refresh_hash"] == h and s["refresh_hash"] != h:   # ponowne użycie starego tokenu: ktoś go przechwycił, unieważniamy całą sesję
            c.execute("UPDATE sessions SET revoked=1 WHERE id=?", (s["id"],)); raise ApiError(401, "Sesja wygasła.")
        if s["revoked"] or s["refresh_exp"] <= t: raise ApiError(401, "Sesja wygasła.")
        dev = c.execute("SELECT revoked FROM devices WHERE id=?", (s["device_id"],)).fetchone()
        if not dev or dev["revoked"]: raise ApiError(401, "Sesja wygasła.")
        access = secrets.token_urlsafe(32); refresh = secrets.token_urlsafe(32)
        cur = c.execute("UPDATE sessions SET access_hash=?, access_exp=?, refresh_hash=?, refresh_exp=?, prev_refresh_hash=? WHERE id=? AND refresh_hash=? AND revoked=0",
                        (sha256(access), t + ACCESS_TTL, sha256(refresh), t + REFRESH_TTL, h, s["id"], h))
        if cur.rowcount != 1: raise ApiError(401, "Sesja wygasła.")   # wyścig z innym procesem: przegrywa drugi
    return 200, {"access_token": access, "refresh_token": refresh, "expires_in": ACCESS_TTL}


def h_logout(app, c, req, token, body, q):
    _, did, sid = app.authenticate(c, token)
    with app.wlock:
        c.execute("UPDATE sessions SET revoked=1 WHERE id=?", (sid,))
        c.execute("DELETE FROM push_tokens WHERE device_id=?", (did,))   # wylogowane urządzenie nie dostaje sygnałów
    return 200, {"ok": True}


def profile_of(a): return {"username": a["username"], "display_name": a["display_name"], "bio": a["bio"], "created_at": a["created_at"]}


def h_me(app, c, req, token, body, q):
    aid, did, _ = app.authenticate(c, token)
    a = c.execute("SELECT * FROM accounts WHERE id=?", (aid,)).fetchone()
    return 200, {**profile_of(a), "account_id": aid, "device_id": did}


def h_me_update(app, c, req, token, body, q):
    aid, _, _ = app.authenticate(c, token)
    sets, vals = [], []
    if "display_name" in body:
        v = body["display_name"]
        if not isinstance(v, str) or not v.strip() or len(v) > 40: raise ApiError(400, "Nieprawidłowa nazwa wyświetlana.")
        sets.append("display_name=?"); vals.append(v.strip())
    if "bio" in body:
        v = body["bio"]
        if not isinstance(v, str) or len(v) > 300: raise ApiError(400, "Zbyt długi opis.")
        sets.append("bio=?"); vals.append(v)
    if not sets: raise ApiError(400, "Brak zmian.")
    with app.wlock: c.execute("UPDATE accounts SET " + ",".join(sets) + " WHERE id=?", (*vals, aid))
    return 200, {"ok": True}


def h_profile(app, c, req, token, body, q, username):
    app.authenticate(c, token)
    a = c.execute("SELECT * FROM accounts WHERE username=? AND status='active'", (username.lower(),)).fetchone()
    if not a: raise ApiError(404, "Nie ma takiego użytkownika.")
    return 200, {"username": a["username"], "display_name": a["display_name"], "bio": a["bio"]}


def h_delete_account(app, c, req, token, body, q):
    """Usuwanie konta w JEDNEJ transakcji: albo znika wszystko, albo nic (zob. testy z wstrzykiwaniem awarii)."""
    aid, _, _ = app.authenticate(c, token)
    app.limit("del:" + aid, 5, 3600)       # inaczej skradziony token dostępu pozwalałby zgadywać sekret przez ten endpoint
    secret = app.parse_secret(body.get("auth_secret"))
    a = c.execute("SELECT * FROM accounts WHERE id=?", (aid,)).fetchone()
    if not hmac.compare_digest(app.hash_secret(secret, bytes.fromhex(a["auth_salt"])), a["auth_hash"]): raise ApiError(403, "Błędny sekret.")
    with app.tx(c):
        app.fault("delete:begin")
        c.execute("DELETE FROM messages WHERE sender_account=? OR recipient_account=?", (aid, aid))
        app.fault("delete:after_messages")
        c.execute("DELETE FROM blocks WHERE blocker=? OR blocked=?", (aid, aid))
        c.execute("DELETE FROM otk_claims WHERE requester=? OR device_id IN (SELECT id FROM devices WHERE account_id=?)", (aid, aid))
        app.fault("delete:after_claims")
        c.execute("DELETE FROM kyber_claims WHERE requester=? OR device_id IN (SELECT id FROM devices WHERE account_id=?)", (aid, aid))
        c.execute("DELETE FROM kyber_prekeys WHERE device_id IN (SELECT id FROM devices WHERE account_id=?)", (aid,))
        c.execute("DELETE FROM push_tokens WHERE device_id IN (SELECT id FROM devices WHERE account_id=?)", (aid,))
        c.execute("DELETE FROM one_time_prekeys WHERE device_id IN (SELECT id FROM devices WHERE account_id=?)", (aid,))
        c.execute("DELETE FROM signed_prekeys WHERE device_id IN (SELECT id FROM devices WHERE account_id=?)", (aid,))
        c.execute("DELETE FROM sessions WHERE account_id=?", (aid,))
        c.execute("DELETE FROM devices WHERE account_id=?", (aid,))
        app.fault("delete:before_account_row")
        c.execute("DELETE FROM accounts WHERE id=?", (aid,))
        app.fault("delete:before_commit")
    return 200, {"ok": True}


# ---------------------------------------------------------------- urządzenia i klucze
def h_devices(app, c, req, token, body, q):
    aid, did, _ = app.authenticate(c, token)
    rows = c.execute("SELECT id,name,created_at,last_seen FROM devices WHERE account_id=? AND revoked=0 ORDER BY created_at", (aid,)).fetchall()
    return 200, {"devices": [{"id": r["id"], "name": r["name"], "created_at": r["created_at"], "last_seen": r["last_seen"], "current": r["id"] == did} for r in rows]}


def h_put_push_token(app, c, req, token, body, q):
    """Rejestruje token push bieżącego urządzenia. Serwer przechowuje WYŁĄCZNIE token; sygnał budzący nie niesie treści ani nadawcy."""
    _, did, _ = app.authenticate(c, token)
    app.limit("pushtoken:" + did, 30, 3600)
    prov = need(body, "provider", str, 16)
    if prov != "fcm": raise ApiError(400, "Nieobsługiwany dostawca powiadomień.")
    tok = need(body, "token", str, 4096).strip()
    if not PUSH_TOKEN_RE.match(tok): raise ApiError(400, "Zły token.")
    with app.tx(c):
        c.execute("INSERT OR REPLACE INTO push_tokens(device_id,provider,token,updated_at) VALUES(?,?,?,?)", (did, prov, tok, app.now()))
    return 200, {"ok": True}


def h_delete_push_token(app, c, req, token, body, q):
    _, did, _ = app.authenticate(c, token)
    with app.tx(c):
        c.execute("DELETE FROM push_tokens WHERE device_id=?", (did,))
    return 200, {"ok": True}


def h_revoke_device(app, c, req, token, body, q, device_id):
    aid, _, _ = app.authenticate(c, token)
    with app.tx(c):
        if not c.execute("SELECT 1 FROM devices WHERE id=? AND account_id=? AND revoked=0", (device_id, aid)).fetchone(): raise ApiError(404, "Nie ma takiego urządzenia.")
        t = app.now()
        c.execute("UPDATE devices SET revoked=1 WHERE id=?", (device_id,))
        c.execute("UPDATE sessions SET revoked=1 WHERE device_id=?", (device_id,))
        c.execute("DELETE FROM one_time_prekeys WHERE device_id=?", (device_id,))
        c.execute("DELETE FROM signed_prekeys WHERE device_id=?", (device_id,))
        c.execute("DELETE FROM otk_claims WHERE device_id=?", (device_id,))
        c.execute("DELETE FROM kyber_claims WHERE device_id=?", (device_id,))
        c.execute("DELETE FROM kyber_prekeys WHERE device_id=?", (device_id,))
        c.execute("DELETE FROM push_tokens WHERE device_id=?", (device_id,))
        c.execute("UPDATE messages SET state='expired', payload=NULL, changed_at=? WHERE recipient_device=? AND payload IS NOT NULL", (t, device_id))
    return 200, {"ok": True}


def h_put_keys(app, c, req, token, body, q):
    """Uzupełnianie puli kluczy własnego urządzenia (z limitem). Klucz o istniejącym key_id jest nadpisywany."""
    aid, did, _ = app.authenticate(c, token)
    app.limit("putkeys:" + did, PUT_KEYS_PER_HOUR, 3600)
    sp = body.get("signed_prekey"); otks = body.get("one_time_prekeys", []); kys = body.get("kyber_prekeys", [])
    sp_id = 0
    if sp is not None:
        if not isinstance(sp, dict): raise ApiError(400, "Zły podpisany klucz.")
        sp_id = sp.get("key_id", 0)
        if not isinstance(sp_id, int) or isinstance(sp_id, bool) or not 0 <= sp_id < 2 ** 31: raise ApiError(400, "Zły identyfikator klucza.")
        for k in ("key", "signature"):
            try: b64decode(need(sp, k, str, 400))
            except ApiError: raise
            except Exception: raise ApiError(400, "Zły klucz.")
    if not isinstance(otks, list) or len(otks) > MAX_OTK_UPLOAD: raise ApiError(400, "Zbyt wiele kluczy jednorazowych.")
    clean = []
    for o in otks:
        if not isinstance(o, dict) or not isinstance(o.get("key_id"), int) or isinstance(o.get("key_id"), bool) or not 0 <= o["key_id"] < 2 ** 31: raise ApiError(400, "Zły identyfikator klucza.")
        try: b64decode(need(o, "key", str, 400))
        except ApiError: raise
        except Exception: raise ApiError(400, "Zły klucz.")
        clean.append((did, o["key_id"], o["key"]))
    if not isinstance(kys, list) or len(kys) > MAX_KYBER_UPLOAD: raise ApiError(400, "Zbyt wiele prekeyów post-kwantowych.")
    kclean = []; last_resorts = 0
    for k in kys:
        if not isinstance(k, dict) or not isinstance(k.get("key_id"), int) or isinstance(k.get("key_id"), bool) or not 0 <= k["key_id"] < 2 ** 31: raise ApiError(400, "Zły identyfikator klucza.")
        lr = k.get("last_resort", False)
        if not isinstance(lr, bool): raise ApiError(400, "Zły znacznik last_resort.")
        for f, lim in (("key", MAX_KYBER_KEY_CHARS), ("signature", 400)):
            try: b64decode(need(k, f, str, lim))
            except ApiError: raise
            except Exception: raise ApiError(400, "Zły klucz.")
        last_resorts += lr
        kclean.append((did, k["key_id"], k["key"], k["signature"], int(lr)))
    if last_resorts > 1: raise ApiError(400, "Dozwolony jeden klucz last_resort na paczkę.")
    with app.tx(c):
        if sp is not None:
            c.execute("INSERT OR REPLACE INTO signed_prekeys(device_id,key,signature,created_at,key_id) VALUES(?,?,?,?,?)", (did, sp["key"], sp["signature"], app.now(), sp_id))
        c.executemany("INSERT OR REPLACE INTO one_time_prekeys VALUES(?,?,?)", clean)
        if last_resorts: c.execute("DELETE FROM kyber_prekeys WHERE device_id=? AND last_resort=1", (did,))   # nowy last_resort zastępuje stary
        c.executemany("INSERT OR REPLACE INTO kyber_prekeys(device_id,key_id,key,signature,last_resort) VALUES(?,?,?,?,?)", kclean)
        if c.execute("SELECT COUNT(*) FROM kyber_prekeys WHERE device_id=? AND last_resort=0", (did,)).fetchone()[0] > MAX_KYBER_TOTAL: raise ApiError(400, "Przekroczono limit prekeyów post-kwantowych.")
        have = c.execute("SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=?", (did,)).fetchone()[0]
        if have > MAX_OTK_TOTAL: raise ApiError(400, "Przekroczono limit kluczy jednorazowych.")   # wyjątek => rollback całej paczki
    return 200, {"ok": True, "one_time_prekeys": have, "low": have < OTK_LOW_WATERMARK}


def h_key_count(app, c, req, token, body, q):
    """Stan puli kluczy WŁASNEGO urządzenia: klient sprawdza to po zalogowaniu i po każdym połączeniu, i uzupełnia poniżej progu."""
    _, did, _ = app.authenticate(c, token)
    have = c.execute("SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=?", (did,)).fetchone()[0]
    return 200, {"one_time_prekeys": have, "low": have < OTK_LOW_WATERMARK, "low_watermark": OTK_LOW_WATERMARK, "max_total": MAX_OTK_TOTAL,
                 "signed_prekey": c.execute("SELECT 1 FROM signed_prekeys WHERE device_id=?", (did,)).fetchone() is not None,
                 "kyber_one_time": c.execute("SELECT COUNT(*) FROM kyber_prekeys WHERE device_id=? AND last_resort=0", (did,)).fetchone()[0],
                 "kyber_last_resort": c.execute("SELECT 1 FROM kyber_prekeys WHERE device_id=? AND last_resort=1", (did,)).fetchone() is not None}


def claim_otk(app, c, requester, device_id, t):
    """Wydaje klucz jednorazowy pytającemu, w ramach już otwartej transakcji. Zwraca None (=> sam podpisany klucz), gdy:
    brak kluczy, limit urządzenia lub limit pytającego. Brak klucza jednorazowego nie psuje wymiany kluczy (zostaje podpisany klucz)."""
    cl = c.execute("SELECT key_id,key,claimed_at FROM otk_claims WHERE requester=? AND device_id=?", (requester, device_id)).fetchone()
    if cl and cl["claimed_at"] > t - OTK_CLAIM_TTL: return cl         # ponowne pobranie: ten sam klucz, nic nie zużywamy
    if c.execute("SELECT COUNT(*) FROM otk_claims WHERE device_id=? AND claimed_at>?", (device_id, t - 3600)).fetchone()[0] >= OTK_DEVICE_HOURLY_CAP: return None
    if c.execute("SELECT COUNT(*) FROM otk_claims WHERE requester=? AND claimed_at>?", (requester, t - 3600)).fetchone()[0] >= OTK_REQUESTER_HOURLY_CAP: return None
    ot = c.execute("SELECT key_id,key FROM one_time_prekeys WHERE device_id=? ORDER BY key_id LIMIT 1", (device_id,)).fetchone()
    if not ot: return None
    c.execute("DELETE FROM one_time_prekeys WHERE device_id=? AND key_id=?", (device_id, ot["key_id"]))   # zużycie
    c.execute("INSERT OR REPLACE INTO otk_claims VALUES(?,?,?,?,?)", (requester, device_id, ot["key_id"], ot["key"], t))
    return ot


def claim_kyber(app, c, requester, device_id, t):
    """Jak claim_otk, dla prekeyów KEM. Gdy jednorazowego nie wolno/nie ma, zwraca klucz "ostatniej szansy" (nie jest zużywany). None tylko gdy urządzenie nie ma żadnego."""
    cl = c.execute("SELECT key_id,key,signature,claimed_at FROM kyber_claims WHERE requester=? AND device_id=?", (requester, device_id)).fetchone()
    if cl and cl["claimed_at"] > t - OTK_CLAIM_TTL: return {"key_id": cl["key_id"], "key": cl["key"], "signature": cl["signature"], "last_resort": False}
    if (c.execute("SELECT COUNT(*) FROM kyber_claims WHERE device_id=? AND claimed_at>?", (device_id, t - 3600)).fetchone()[0] < OTK_DEVICE_HOURLY_CAP
            and c.execute("SELECT COUNT(*) FROM kyber_claims WHERE requester=? AND claimed_at>?", (requester, t - 3600)).fetchone()[0] < OTK_REQUESTER_HOURLY_CAP):
        ot = c.execute("SELECT key_id,key,signature FROM kyber_prekeys WHERE device_id=? AND last_resort=0 ORDER BY key_id LIMIT 1", (device_id,)).fetchone()
        if ot:
            c.execute("DELETE FROM kyber_prekeys WHERE device_id=? AND key_id=?", (device_id, ot["key_id"]))
            c.execute("INSERT OR REPLACE INTO kyber_claims VALUES(?,?,?,?,?,?)", (requester, device_id, ot["key_id"], ot["key"], ot["signature"], t))
            return {"key_id": ot["key_id"], "key": ot["key"], "signature": ot["signature"], "last_resort": False}
    lr = c.execute("SELECT key_id,key,signature FROM kyber_prekeys WHERE device_id=? AND last_resort=1", (device_id,)).fetchone()
    return {"key_id": lr["key_id"], "key": lr["key"], "signature": lr["signature"], "last_resort": True} if lr else None


def h_get_keys(app, c, req, token, body, q, username):
    aid, _, _ = app.authenticate(c, token)
    app.limit("keys:" + aid, 120, 60)
    t = app.now()
    with app.tx(c):
        a = c.execute("SELECT id FROM accounts WHERE username=? AND status='active'", (username.lower(),)).fetchone()
        if not a: raise ApiError(404, "Nie ma takiego użytkownika.")
        blocked = c.execute("SELECT 1 FROM blocks WHERE blocker=? AND blocked=?", (a["id"], aid)).fetchone() is not None   # zablokowany nie zużywa kluczy
        out = []
        for d in c.execute("SELECT id,identity_key FROM devices WHERE account_id=? AND revoked=0 ORDER BY created_at", (a["id"],)).fetchall():
            sp = c.execute("SELECT key,signature,key_id FROM signed_prekeys WHERE device_id=?", (d["id"],)).fetchone()
            ot = None if blocked else claim_otk(app, c, aid, d["id"], t)
            ky = None if blocked else claim_kyber(app, c, aid, d["id"], t)
            out.append({"device_id": d["id"], "identity_key": d["identity_key"],
                        "signed_prekey": {"key_id": sp["key_id"], "key": sp["key"], "signature": sp["signature"]} if sp else None,
                        "kyber_prekey": ky,
                        "one_time_prekey": {"key_id": ot["key_id"], "key": ot["key"]} if ot else None})
    return 200, {"username": username.lower(), "devices": out}


# ---------------------------------------------------------------- blokowanie
def h_block(app, c, req, token, body, q, username):
    aid, _, _ = app.authenticate(c, token)
    t = c.execute("SELECT id FROM accounts WHERE username=?", (username.lower(),)).fetchone()
    if not t or t["id"] == aid: raise ApiError(404, "Nie ma takiego użytkownika.")
    with app.wlock: c.execute("INSERT OR IGNORE INTO blocks VALUES(?,?)", (aid, t["id"]))
    return 200, {"ok": True}


def h_unblock(app, c, req, token, body, q, username):
    aid, _, _ = app.authenticate(c, token)
    t = c.execute("SELECT id FROM accounts WHERE username=?", (username.lower(),)).fetchone()
    if t:
        with app.wlock: c.execute("DELETE FROM blocks WHERE blocker=? AND blocked=?", (aid, t["id"]))
    return 200, {"ok": True}


# ---------------------------------------------------------------- wiadomości
def h_send(app, c, req, token, body, q):
    aid, did, _ = app.authenticate(c, token)
    app.limit("send:" + aid, app.cfg.send_per_min, 60)
    ru = need(body, "recipient_username", str, 40).strip().lower(); rd = need(body, "recipient_device_id", str, 40)
    cmid = need(body, "client_msg_id", str, 80); payload = need(body, "payload", str)
    if not CMID_RE.match(cmid) or not ID_RE.match(rd): raise ApiError(400, "Nieprawidłowe pole.")
    if len(payload) > MAX_PAYLOAD_CHARS: raise ApiError(413, "Wiadomość za duża.")
    try: b64decode(payload)
    except Exception: raise ApiError(400, "Zły szyfrogram.")
    t = app.now()
    with app.wlock:
        old = c.execute("SELECT * FROM messages WHERE sender_account=? AND client_msg_id=?", (aid, cmid)).fetchone()
        if old:   # idempotencja: ponowienie przerwanego żądania nie tworzy drugiej wiadomości
            if old["recipient_device"] != rd or (old["payload"] is not None and old["payload"] != payload): raise ApiError(409, "Ten identyfikator był już użyty z inną treścią.")
            return 200, {"id": old["id"], "state": "accepted" if old["dropped"] else old["state"]}
        r = c.execute("SELECT id FROM accounts WHERE username=? AND status='active'", (ru,)).fetchone()
        if not r or not c.execute("SELECT 1 FROM devices WHERE id=? AND account_id=? AND revoked=0", (rd, r["id"])).fetchone(): raise ApiError(404, "Nie ma takiego odbiorcy.")
        dropped = 1 if c.execute("SELECT 1 FROM blocks WHERE blocker=? AND blocked=?", (r["id"], aid)).fetchone() else 0
        mid = secrets.token_hex(16)
        try:
            c.execute("INSERT INTO messages(id,sender_account,sender_device,recipient_account,recipient_device,client_msg_id,payload,created_at,expires_at,state,changed_at,dropped) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                      (mid, aid, did, r["id"], rd, cmid, payload, t, t + app.cfg.message_ttl, "accepted", t, dropped))
        except sqlite3.IntegrityError:    # drugi proces zdążył z tym samym client_msg_id: to ponowienie, nie błąd
            old = c.execute("SELECT * FROM messages WHERE sender_account=? AND client_msg_id=?", (aid, cmid)).fetchone()
            if not old: raise
            return 200, {"id": old["id"], "state": "accepted" if old["dropped"] else old["state"]}
    return 202, {"id": mid, "state": "accepted"}


def h_pending(app, c, req, token, body, q):
    """Zwraca NIEPOTWIERDZONE wiadomości urządzenia w kolejności seq. `after` to wyłącznie kursor stronicowania w obrębie jednego przebiegu pobierania:
    po starcie aplikacji i po każdym ponownym połączeniu klient MUSI zacząć od after=0 (inaczej pominie wiadomości sprzed kursora, których nie potwierdził)."""
    aid, did, _ = app.authenticate(c, token)
    try: after = int(q.get("after", 0)); wait = min(25.0, float(q.get("wait", 0)))
    except ValueError: raise ApiError(400, "Zły parametr.")
    deadline = time.time() + max(0.0, wait)
    while True:
        rows = c.execute("""SELECT m.id,m.seq,m.payload,m.created_at,a.username su,m.sender_device FROM messages m JOIN accounts a ON a.id=m.sender_account
                            WHERE m.recipient_device=? AND m.payload IS NOT NULL AND m.dropped=0 AND m.expires_at>? AND m.seq>? ORDER BY m.seq LIMIT 101""",
                         (did, app.now(), after)).fetchall()
        if rows or time.time() >= deadline: break
        time.sleep(0.2)
    more = len(rows) > 100; rows = rows[:100]
    return 200, {"has_more": more, "messages": [{"id": r["id"], "seq": r["seq"], "payload": r["payload"], "created_at": r["created_at"],
                               "sender": {"username": r["su"], "device_id": r["sender_device"]}} for r in rows]}


def _own_inbox_msg(app, c, did, mid):
    if not ID_RE.match(mid): raise ApiError(404, "Nie ma takiej wiadomości.")
    m = c.execute("SELECT * FROM messages WHERE id=? AND recipient_device=? AND dropped=0", (mid, did)).fetchone()   # autoryzacja na poziomie obiektu
    if not m: raise ApiError(404, "Nie ma takiej wiadomości.")
    return m


def h_ack(app, c, req, token, body, q, mid):
    """ACK = "mam tę wiadomość TRWALE zapisaną i serwer może skasować szyfrogram". Nieodwracalne: po ACK serwer nie ma już treści.
    Klient wysyła ACK dopiero po zatwierdzeniu szyfrogramu w lokalnej bazie (zob. docs/2.0/SHYFR_2_DELIVERY.md)."""
    _, did, _ = app.authenticate(c, token)
    with app.wlock:
        m = _own_inbox_msg(app, c, did, mid)
        if m["state"] == "expired": raise ApiError(410, "Wiadomość wygasła.")
        if m["state"] == "accepted":
            c.execute("UPDATE messages SET state='delivered', payload=NULL, changed_at=? WHERE id=?", (app.now(), mid))
    return 200, {"ok": True}


def h_ack_batch(app, c, req, token, body, q):
    """Potwierdzenie do 100 wiadomości naraz, jedna transakcja. Wynik dla każdego id: ok | expired | not_found. Idempotentne."""
    _, did, _ = app.authenticate(c, token)
    ids = body.get("ids")
    if not isinstance(ids, list) or not 1 <= len(ids) <= MAX_ACK_BATCH or not all(isinstance(i, str) for i in ids): raise ApiError(400, "Nieprawidłowe pole: ids")
    res = {}
    with app.tx(c):
        for mid in dict.fromkeys(ids):
            m = c.execute("SELECT * FROM messages WHERE id=? AND recipient_device=? AND dropped=0", (mid, did)).fetchone() if ID_RE.match(mid) else None
            if not m: res[mid] = "not_found"
            elif m["state"] == "expired": res[mid] = "expired"
            else:
                if m["state"] == "accepted": c.execute("UPDATE messages SET state='delivered', payload=NULL, changed_at=? WHERE id=?", (app.now(), mid))
                res[mid] = "ok"
    return 200, {"results": res}


def h_read(app, c, req, token, body, q, mid):
    _, did, _ = app.authenticate(c, token)
    with app.wlock:
        m = _own_inbox_msg(app, c, did, mid)
        if m["state"] == "accepted": raise ApiError(409, "Najpierw potwierdź odbiór.")
        if m["state"] == "delivered":
            c.execute("UPDATE messages SET state='read', changed_at=? WHERE id=?", (app.now(), mid))
    return 200, {"ok": True}


def h_status(app, c, req, token, body, q, mid):
    aid, _, _ = app.authenticate(c, token)
    if not ID_RE.match(mid): raise ApiError(404, "Nie ma takiej wiadomości.")
    m = c.execute("SELECT state,dropped FROM messages WHERE id=? AND sender_account=?", (mid, aid)).fetchone()
    if not m: raise ApiError(404, "Nie ma takiej wiadomości.")
    return 200, {"id": mid, "state": "accepted" if m["dropped"] else m["state"]}   # nadawca nie dowiaduje się o blokadzie


def h_health(app, c, req, token, body, q):
    c.execute("SELECT 1"); return 200, {"ok": True}


def h_ready(app, c, req, token, body, q):
    """Gotowość do ruchu: baza zapisywalna i wszystkie migracje zastosowane (dla load balancera/monitoringu; /healthz tylko mówi, że proces żyje)."""
    expected = sorted(int(f.split("_")[0]) for f in os.listdir(MIGRATIONS_DIR) if f.endswith(".sql"))
    have = [r[0] for r in c.execute("SELECT version FROM schema_migrations ORDER BY version")]
    if have != expected: raise ApiError(503, "Schemat bazy nieaktualny.")
    with app.tx(c): pass            # sprawdza, czy baza przyjmuje zapis (np. dysk nie jest pełny/tylko do odczytu)
    return 200, {"ready": True, "schema_version": have[-1]}


ROUTES = [(m, re.compile("^" + p + "$"), f) for m, p, f in [
    ("GET", r"/healthz", h_health), ("GET", r"/readyz", h_ready),
    ("POST", r"/v1/accounts", h_register),
    ("GET", r"/v1/accounts/me", h_me), ("PATCH", r"/v1/accounts/me", h_me_update), ("DELETE", r"/v1/accounts/me", h_delete_account),
    ("GET", r"/v1/accounts/([A-Za-z0-9_]{1,40})", h_profile),
    ("POST", r"/v1/auth/login", h_login), ("POST", r"/v1/auth/refresh", h_refresh), ("POST", r"/v1/auth/logout", h_logout),
    ("GET", r"/v1/devices", h_devices), ("PUT", r"/v1/devices/me/push-token", h_put_push_token), ("DELETE", r"/v1/devices/me/push-token", h_delete_push_token), ("DELETE", r"/v1/devices/([0-9a-f]{32})", h_revoke_device),
    ("PUT", r"/v1/keys", h_put_keys), ("GET", r"/v1/keys/me/count", h_key_count), ("GET", r"/v1/keys/([A-Za-z0-9_]{1,40})", h_get_keys),
    ("PUT", r"/v1/blocks/([A-Za-z0-9_]{1,40})", h_block), ("DELETE", r"/v1/blocks/([A-Za-z0-9_]{1,40})", h_unblock),
    ("POST", r"/v1/messages", h_send), ("GET", r"/v1/messages/pending", h_pending), ("POST", r"/v1/messages/ack", h_ack_batch),
    ("POST", r"/v1/messages/([0-9a-f]{32})/ack", h_ack), ("POST", r"/v1/messages/([0-9a-f]{32})/read", h_read),
    ("GET", r"/v1/messages/([0-9a-f]{32})/status", h_status),
]]


def make_server(cfg, host="127.0.0.1", port=0):
    app = App(cfg)
    H = type("H", (Handler,), {"app": app})
    srv = ThreadingHTTPServer((host, port), H); srv.daemon_threads = True
    return srv, app


if __name__ == "__main__":
    cfg = Config.from_env()
    srv, _ = make_server(cfg, os.environ.get("SHYFR_HOST", "127.0.0.1"), int(os.environ.get("SHYFR_PORT", "8080")))
    print("SHYFR server nasłuchuje na %s:%d (TLS kończy reverse proxy)" % srv.server_address); srv.serve_forever()
