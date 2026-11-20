"""Kontrakt RateLimitStore: te same testy dla KAŻDEJ implementacji (dodając nową, dopisz ją do IMPLS)."""
import os, shutil, tempfile, threading, unittest

from shyfr_server.ratelimit import MemoryRateLimitStore
from shyfr_server.server import Config, App
from tests.test_server import Env, b64


def memory(tmp): return MemoryRateLimitStore(), (lambda: None)
def sqlite(tmp):
    app = App(Config(db_path=os.path.join(tmp, "rl.db"), rate_limit_backend="sqlite")); return app.rl, (lambda: None)

IMPLS = {"memory": memory, "sqlite": sqlite}


class Contract:
    impl = None
    def setUp(self):
        self.tmp = tempfile.mkdtemp(); self.s, _ = IMPLS[self.impl](self.tmp)
    def tearDown(self): shutil.rmtree(self.tmp, ignore_errors=True)

    def test_hit_allows_up_to_limit_then_refuses_without_recording(self):
        self.assertEqual([self.s.hit("k", 1000, 60, 3) for _ in range(5)], [True, True, True, False, False])
        self.assertEqual(self.s.count("k", 1000, 60), 3)

    def test_window_slides(self):
        for t in (1000, 1010, 1020): self.assertTrue(self.s.hit("k", t, 60, 3))
        self.assertFalse(self.s.hit("k", 1030, 60, 3))
        self.assertTrue(self.s.hit("k", 1061, 60, 3))        # najstarsze zdarzenie (1000) wypadło z okna
        self.assertEqual(self.s.count("k", 1061, 60), 3)

    def test_keys_are_independent_and_reset_works(self):
        self.s.hit("a", 1, 60, 1); self.assertFalse(self.s.hit("a", 1, 60, 1)); self.assertTrue(self.s.hit("b", 1, 60, 1))
        self.s.reset("a"); self.assertEqual(self.s.count("a", 1, 60), 0); self.assertTrue(self.s.hit("a", 1, 60, 1))

    def test_record_is_unconditional_and_counted(self):
        for _ in range(10): self.s.record("k", 5)
        self.assertEqual(self.s.count("k", 5, 60), 10)

    def test_purge_removes_old_events_only(self):
        self.s.record("old", 100); self.s.record("new", 10_000)
        self.s.purge(10_000, 3600)
        self.assertEqual((self.s.count("old", 10_000, 10**6), self.s.count("new", 10_000, 10**6)), (0, 1))

    def test_hit_is_atomic_under_concurrency(self):
        res = []
        def go(): res.append(self.s.hit("race", 500, 60, 10))
        ts = [threading.Thread(target=go) for _ in range(40)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sum(res), 10)       # dokładnie limit, nigdy więcej


class MemoryContract(Contract, unittest.TestCase): impl = "memory"
class SqliteContract(Contract, unittest.TestCase): impl = "sqlite"


class SharedStoreAcrossInstances(unittest.TestCase):
    """Dwie instancje serwera na tej samej bazie: z backendem sqlite dzielą limity, z memory nie (i to jest udokumentowane)."""
    def _two(self, backend):
        e1 = Env(register_per_hour=3, rate_limit_backend=backend)
        from shyfr_server.server import make_server
        srv2, app2 = make_server(Config(db_path=e1.db, clock=e1.clock, register_per_hour=3, login_per_10min=1000, send_per_min=1000, rate_limit_backend=backend))
        threading.Thread(target=srv2.serve_forever, daemon=True).start()
        e2 = Env.__new__(Env); e2.base = "http://127.0.0.1:%d" % srv2.server_address[1]
        return e1, e2, srv2

    def _reg(self, e, i):
        return e.call("POST", "/v1/accounts", {"username": "user_%s" % i, "auth_secret": b64(), "device": {"name": "x", "identity_key": b64()}})[0]

    def test_sqlite_backend_shares_limits(self):
        e1, e2, srv2 = self._two("sqlite")
        try:
            codes = [self._reg(e1, 1), self._reg(e2, 2), self._reg(e1, 3), self._reg(e2, 4), self._reg(e1, 5)]
            self.assertEqual(codes, [201, 201, 201, 429, 429])
        finally: srv2.shutdown(); srv2.server_close(); e1.close()

    def test_memory_backend_does_not_share_limits_documented_limitation(self):
        e1, e2, srv2 = self._two("memory")
        try:
            codes = [self._reg(e1, i) for i in range(4)] + [self._reg(e2, i + 10) for i in range(3)]
            self.assertEqual(codes, [201, 201, 201, 429, 201, 201, 201])   # każda instancja liczy osobno: dlatego przy wielu instancjach potrzebny wspólny magazyn
        finally: srv2.shutdown(); srv2.server_close(); e1.close()


if __name__ == "__main__":
    unittest.main()
