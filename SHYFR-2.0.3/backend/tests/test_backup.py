"""CREATE -> WRITE -> BACKUP -> RESTORE -> READ: odtworzenie zachowuje konta, urządzenia, klucze, kontakty (blokady) i kolejki, a tożsamości się nie zmieniają."""
import os, shutil, sqlite3, tempfile, threading, unittest

from shyfr_server import backup as B
from tests.test_server import Env, b64
from tests.test_hardening import KEYS, KY, fetch, otk_count


class BackupRestoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(); self.e = Env()
    def tearDown(self):
        self.e.close(); shutil.rmtree(self.tmp, ignore_errors=True)

    def populate(self):
        e = self.e
        self.a = e.register("alice"); self.b = e.register("bob")
        st, self.a2 = e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": self.a["secret"], "device": {"name": "tablet", "identity_key": b64()}})
        e.call("PUT", "/v1/keys", {**KEYS(range(5)), **KY(range(3), lr=99)}, self.a["access_token"])
        e.call("PUT", "/v1/blocks/bob", token=self.a["access_token"])
        self.c = e.register("carol"); self.pl = b64(40); e.send(self.c, self.a, self.pl)     # od niezablokowanej Carol: czeka w kolejce
        e.send(self.b, self.a, b64(41))                                                       # od zablokowanego Boba: odrzucona po cichu
        fetch(e, self.b, "alice")

    def snapshot(self, path):
        c = sqlite3.connect(path)
        try:
            return {t: sorted(map(repr, c.execute("SELECT * FROM %s" % t).fetchall())) for t in
                    ("accounts", "devices", "signed_prekeys", "one_time_prekeys", "kyber_prekeys", "otk_claims", "messages", "blocks", "schema_migrations")}
        finally: c.close()

    def test_full_cycle_preserves_identity_keys_devices_contacts_and_queued_messages(self):
        self.populate()
        before = self.snapshot(self.e.db)
        bk = os.path.join(self.tmp, "kopia", "b.db"); restored = os.path.join(self.tmp, "nowa", "shyfr.db")
        versions = B.backup(self.e.db, bk); self.assertEqual(versions, [1, 2, 3, 4])
        B.restore(bk, restored)
        self.assertEqual(self.snapshot(restored), before)                     # bit w bit te same dane (poza sesjami i limitami)
        # READ przez prawdziwy serwer uruchomiony na odtworzonej bazie
        from shyfr_server.server import Config, make_server
        srv, app = make_server(Config(db_path=restored, clock=self.e.clock, register_per_hour=1000, login_per_10min=1000, send_per_min=1000))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        e2 = Env.__new__(Env); e2.base = "http://127.0.0.1:%d" % srv.server_address[1]
        try:
            st, l = e2.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": self.a["secret"], "device_id": self.a["device_id"]})
            self.assertEqual((st, l["account_id"], l["device_id"]), (200, self.a["account_id"], self.a["device_id"]))   # ta sama tożsamość
            self.assertEqual([m["payload"] for m in e2.call("GET", "/v1/messages/pending", token=l["access_token"])[1]["messages"]], [self.pl])
            self.assertEqual(len(e2.call("GET", "/v1/devices", token=l["access_token"])[1]["devices"]), 2)
            # blokada (kontakt) przetrwała: Bob po odtworzeniu nadal nie dosyła wiadomości do Alice
            st, lb = e2.call("POST", "/v1/auth/login", {"username": "bob", "auth_secret": self.b["secret"], "device_id": self.b["device_id"]}); self.assertEqual(st, 200)
            e2.send({"access_token": lb["access_token"]}, self.a, b64(33))
            self.assertEqual(len(e2.call("GET", "/v1/messages/pending", token=l["access_token"])[1]["messages"]), 1)
            self.assertEqual(e2.call("GET", "/readyz")[1]["schema_version"], 4)
        finally:
            srv.shutdown(); srv.server_close()

    def test_backup_under_concurrent_writes_is_consistent(self):
        self.populate(); stop = threading.Event()
        def writer():
            i = 0
            while not stop.is_set():
                self.e.call("PUT", "/v1/keys", {"one_time_prekeys": [{"key_id": 1000 + (i % 50), "key": b64()}]}, self.a["access_token"]); i += 1
        t = threading.Thread(target=writer); t.start()
        try:
            for n in range(3): B.backup(self.e.db, os.path.join(self.tmp, "c%d.db" % n))
        finally: stop.set(); t.join()
        for n in range(3): self.assertTrue(B.verify(os.path.join(self.tmp, "c%d.db" % n)))

    def test_corrupt_or_foreign_backups_are_refused_and_nothing_is_overwritten(self):
        self.populate(); good = os.path.join(self.tmp, "g.db"); B.backup(self.e.db, good)
        bad = os.path.join(self.tmp, "bad.db"); shutil.copy(good, bad)
        with open(bad, "r+b") as f: f.seek(4096); f.write(b"\xff" * 4096)
        junk = os.path.join(self.tmp, "junk.db"); open(junk, "wb").write(b"to nie jest sqlite" * 100)
        empty = os.path.join(self.tmp, "empty.db"); sqlite3.connect(empty).execute("CREATE TABLE x(a)").connection.commit()
        target = os.path.join(self.tmp, "t.db"); shutil.copy(good, target); before = open(target, "rb").read()
        for p in (bad, junk, empty, os.path.join(self.tmp, "nie_ma.db")):
            with self.assertRaises(B.BackupError, msg=p): B.restore(p, target, force=True)
        self.assertEqual(open(target, "rb").read(), before)

    def test_restore_refuses_to_overwrite_without_force_and_keeps_the_previous_db_with_force(self):
        self.populate(); bk = os.path.join(self.tmp, "b.db"); B.backup(self.e.db, bk)
        target = os.path.join(self.tmp, "t.db"); B.restore(bk, target)
        with self.assertRaises(B.BackupError): B.restore(bk, target)
        B.restore(bk, target, force=True); self.assertTrue(os.path.exists(target + ".before-restore"))

    def test_backup_refuses_to_overwrite_an_existing_file(self):
        self.populate(); bk = os.path.join(self.tmp, "b.db"); B.backup(self.e.db, bk)
        with self.assertRaises(B.BackupError): B.backup(self.e.db, bk)

    def test_cli_exit_codes(self):
        self.populate(); bk = os.path.join(self.tmp, "b.db")
        self.assertEqual(B.main(["backup", self.e.db, bk]), 0); self.assertEqual(B.main(["verify", bk]), 0)
        self.assertEqual(B.main(["verify", os.path.join(self.tmp, "none")]), 1); self.assertEqual(B.main([]), 2)


class ReadinessTests(unittest.TestCase):
    def test_readyz_ok_and_503_when_schema_is_stale(self):
        e = Env()
        try:
            self.assertEqual(e.call("GET", "/healthz")[0], 200)
            st, r = e.call("GET", "/readyz"); self.assertEqual((st, r["ready"], r["schema_version"]), (200, True, 4))
            c = sqlite3.connect(e.db); c.execute("DELETE FROM schema_migrations WHERE version=3"); c.commit(); c.close()
            self.assertEqual(e.call("GET", "/readyz")[0], 503)
            self.assertEqual(e.call("GET", "/healthz")[0], 200)          # proces żyje, ale nie jest gotowy
        finally: e.close()


if __name__ == "__main__":
    unittest.main()
