"""Magazyn ograniczeń liczby żądań (sliding window).

`RateLimitStore` to interfejs, który serwer zna. Dziś są dwie implementacje:
  * MemoryRateLimitStore : stan w pamięci jednego procesu (domyślna; wystarcza dla JEDNEJ instancji),
  * SqliteRateLimitStore : stan w tej samej bazie SQLite (wspólny dla wielu PROCESÓW na jednym hoście).
Dla wielu HOSTÓW trzeba napisać trzecią implementację (np. Redis/PostgreSQL) spełniającą ten sam kontrakt;
kontrakt sprawdza `tests/test_ratelimit.py` (te same testy uruchamiane dla każdej implementacji).

Wszystkie metody dostają `now` z zewnątrz (zegar serwera), więc są deterministyczne w testach.
Klucze są dowolnymi krótkimi napisami; NIE wkładamy do nich surowych danych użytkownika o nieograniczonej długości (zob. `App.key`).
"""
import abc, threading


class RateLimitStore(abc.ABC):
    @abc.abstractmethod
    def hit(self, key, now, window, limit):
        """Atomowo: jeśli w oknie [now-window, now] jest < limit zdarzeń, zapisz zdarzenie i zwróć True; inaczej nic nie zapisuj i zwróć False."""

    @abc.abstractmethod
    def record(self, key, now):
        """Bezwarunkowo zapisz zdarzenie."""

    @abc.abstractmethod
    def count(self, key, now, window):
        """Liczba zdarzeń w oknie (bez zapisu)."""

    @abc.abstractmethod
    def reset(self, key):
        """Usuń wszystkie zdarzenia klucza."""

    @abc.abstractmethod
    def purge(self, now, max_window):
        """Usuń zdarzenia starsze niż max_window (higiena pamięci)."""


class MemoryRateLimitStore(RateLimitStore):
    MAX_KEYS = 100_000

    def __init__(self):
        self._d = {}; self._lock = threading.Lock()

    def _live(self, key, now, window):
        q = [x for x in self._d.get(key, ()) if x > now - window]
        return q

    def hit(self, key, now, window, limit):
        with self._lock:
            q = self._live(key, now, window)
            if len(q) >= limit:
                self._d[key] = q; return False
            q.append(now); self._d[key] = q
            if len(self._d) > self.MAX_KEYS: self._evict(now, window)
            return True

    def record(self, key, now):
        with self._lock:
            self._d.setdefault(key, []).append(now)
            if len(self._d) > self.MAX_KEYS: self._evict(now, 86400)

    def count(self, key, now, window):
        with self._lock: return len(self._live(key, now, window))

    def reset(self, key):
        with self._lock: self._d.pop(key, None)

    def purge(self, now, max_window):
        with self._lock: self._evict(now, max_window)

    def _evict(self, now, window):
        self._d = {k: v for k, v in self._d.items() if v and v[-1] > now - window}


class SqliteRateLimitStore(RateLimitStore):
    """`connect()` zwraca połączenie SQLite (isolation_level=None); `lock` serializuje zapisy w procesie.
    Między procesami atomowość daje BEGIN IMMEDIATE."""

    def __init__(self, connect, close, lock):
        self._connect = connect; self._close = close; self._lock = lock

    def _run(self, fn):
        with self._lock:
            c = self._connect()
            try:
                c.execute("BEGIN IMMEDIATE")
                try:
                    out = fn(c); c.execute("COMMIT"); return out
                except BaseException:
                    c.execute("ROLLBACK"); raise
            finally:
                self._close(c)

    def hit(self, key, now, window, limit):
        def f(c):
            n = c.execute("SELECT COUNT(*) FROM rate_events WHERE key=? AND ts>?", (key, now - window)).fetchone()[0]
            if n >= limit: return False
            c.execute("INSERT INTO rate_events(key,ts) VALUES(?,?)", (key, now)); return True
        return self._run(f)

    def record(self, key, now):
        self._run(lambda c: c.execute("INSERT INTO rate_events(key,ts) VALUES(?,?)", (key, now)))

    def count(self, key, now, window):
        return self._run(lambda c: c.execute("SELECT COUNT(*) FROM rate_events WHERE key=? AND ts>?", (key, now - window)).fetchone()[0])

    def reset(self, key):
        self._run(lambda c: c.execute("DELETE FROM rate_events WHERE key=?", (key,)))

    def purge(self, now, max_window):
        self._run(lambda c: c.execute("DELETE FROM rate_events WHERE ts<=?", (now - max_window,)))
