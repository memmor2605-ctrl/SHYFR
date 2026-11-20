"""Testy regresyjne etapu 39: klucze jednorazowe, logowanie, usuwanie konta, zaufanie do proxy, doręczanie."""
import json, os, sqlite3, threading, unittest
from unittest import mock

import shyfr_server.server as S
from shyfr_server.server import Config, client_ip
from tests.test_server import Env, Base, b64

KEYS = lambda ids: {"signed_prekey": {"key": b64(), "signature": b64(64)}, "one_time_prekeys": [{"key_id": i, "key": b64()} for i in ids]}
DEV = {"name": "x", "identity_key": b64()}
XFF = lambda ip: {"X-Forwarded-For": ip}
LOCAL = ["127.0.0.0/8"]


def otk_count(e, who): return e.call("GET", "/v1/keys/me/count", token=who["access_token"])[1]["one_time_prekeys"]
def fetch(e, who, name): return e.call("GET", "/v1/keys/" + name, token=who["access_token"])


# ============================================================ 1. klucze jednorazowe
class PrekeyAbuseTests(Base):
    def victim(self, n=100):
        v = self.e.register("victim")
        self.assertEqual(self.e.call("PUT", "/v1/keys", KEYS(range(n)), v["access_token"])[0], 200); return v

    def test_one_attacker_repeating_requests_consumes_one_key_only(self):
        v = self.victim(); atk = self.e.register("attacker")
        ids = {fetch(self.e, atk, "victim")[1]["devices"][0]["one_time_prekey"]["key_id"] for _ in range(50)}
        self.assertEqual(len(ids), 1); self.assertEqual(otk_count(self.e, v), 99)

    def test_many_sybil_accounts_cannot_drain_the_pool_beyond_the_hourly_device_cap(self):
        v = self.victim(); got = []
        for i in range(S.OTK_DEVICE_HOURLY_CAP + 6):
            a = self.e.register("sybil_%d" % i); d = fetch(self.e, a, "victim")[1]["devices"][0]
            got.append(d["one_time_prekey"]); self.assertIsNotNone(d["signed_prekey"])   # podpisany klucz zawsze dostępny
        issued = [g for g in got if g]
        self.assertEqual(len(issued), S.OTK_DEVICE_HOURLY_CAP); self.assertEqual(len({g["key_id"] for g in issued}), len(issued))
        self.assertEqual(otk_count(self.e, v), 100 - S.OTK_DEVICE_HOURLY_CAP)
        self.e.clock.t += 3601                         # po godzinie limit się odnawia
        late = self.e.register("late_one"); self.assertIsNotNone(fetch(self.e, late, "victim")[1]["devices"][0]["one_time_prekey"])

    def test_requester_cap_limits_how_many_pools_one_account_can_touch(self):
        victims = []
        for i in range(5):
            v = self.e.register("vic_%d" % i); self.e.call("PUT", "/v1/keys", KEYS(range(10)), v["access_token"]); victims.append(v)
        atk = self.e.register("attacker")
        with mock.patch.object(S, "OTK_REQUESTER_HOURLY_CAP", 3):
            took = [fetch(self.e, atk, "vic_%d" % i)[1]["devices"][0]["one_time_prekey"] is not None for i in range(5)]
        self.assertEqual(took, [True, True, True, False, False])
        self.assertEqual(sum(otk_count(self.e, v) for v in victims), 50 - 3)

    def test_concurrent_requests_never_hand_out_the_same_key_twice(self):
        v = self.victim(5); reqs = [self.e.register("req_%d" % i) for i in range(12)]; out = []; lock = threading.Lock()
        def go(r):
            st, d = fetch(self.e, r, "victim")
            with lock: out.append((st, d["devices"][0]["one_time_prekey"]))
        ts = [threading.Thread(target=go, args=(r,)) for r in reqs]; [t.start() for t in ts]; [t.join() for t in ts]
        self.assertTrue(all(st == 200 for st, _ in out))
        ids = [k["key_id"] for _, k in out if k]
        self.assertEqual(len(ids), 5); self.assertEqual(len(set(ids)), 5)          # 5 kluczy, 5 różnych wydań, reszta bez klucza
        self.assertEqual(otk_count(self.e, v), 0)

    def test_concurrent_requests_from_one_account_consume_one_key(self):
        v = self.victim(20); atk = self.e.register("attacker"); out = []; lock = threading.Lock()
        def go():
            d = fetch(self.e, atk, "victim")[1]["devices"][0]["one_time_prekey"]
            with lock: out.append(d["key_id"])
        ts = [threading.Thread(target=go) for _ in range(10)]; [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(len(set(out)), 1); self.assertEqual(otk_count(self.e, v), 19)

    def test_lost_response_is_recoverable_by_asking_again(self):
        v = self.victim(3); a = self.e.register("alice")
        first = fetch(self.e, a, "victim")[1]["devices"][0]["one_time_prekey"]          # załóżmy, że odpowiedź zginęła w sieci
        again = fetch(self.e, a, "victim")[1]["devices"][0]["one_time_prekey"]
        self.assertEqual(first, again); self.assertEqual(otk_count(self.e, v), 2)

    def test_claim_expires_after_24h_and_a_fresh_key_is_issued(self):
        self.victim(3); a = self.e.register("alice")
        k1 = fetch(self.e, a, "victim")[1]["devices"][0]["one_time_prekey"]["key_id"]
        self.e.clock.t += S.OTK_CLAIM_TTL + 1
        a = {**a, **self.e.call("POST", "/v1/auth/refresh", {"refresh_token": a["refresh_token"]})[1]}   # token dostępu wygasł po 15 min
        k2 = fetch(self.e, a, "victim")[1]["devices"][0]["one_time_prekey"]["key_id"]
        self.assertNotEqual(k1, k2)

    def test_blocked_requester_gets_no_one_time_key_and_consumes_none(self):
        v = self.victim(5); a = self.e.register("alice")
        self.e.call("PUT", "/v1/blocks/alice", token=v["access_token"])
        d = fetch(self.e, a, "victim")[1]["devices"][0]
        self.assertIsNone(d["one_time_prekey"]); self.assertIsNotNone(d["signed_prekey"]); self.assertEqual(otk_count(self.e, v), 5)

    def test_pool_empty_returns_signed_prekey_only(self):
        v = self.e.register("victim"); self.e.call("PUT", "/v1/keys", KEYS([]), v["access_token"]); a = self.e.register("alice")
        d = fetch(self.e, a, "victim")[1]["devices"][0]; self.assertIsNone(d["one_time_prekey"]); self.assertIsNotNone(d["signed_prekey"])

    def test_unknown_user_is_404_and_consumes_nothing(self):
        a = self.e.register("alice"); self.assertEqual(fetch(self.e, a, "nikt_taki")[0], 404)
        self.assertEqual(self.e.call("GET", "/v1/keys/victim")[0], 401)

    def test_replenishment_count_low_flag_and_upload(self):
        v = self.e.register("victim")
        st, c = self.e.call("GET", "/v1/keys/me/count", token=v["access_token"])
        self.assertEqual((c["one_time_prekeys"], c["low"], c["signed_prekey"]), (0, True, False))
        st, r = self.e.call("PUT", "/v1/keys", KEYS(range(S.OTK_LOW_WATERMARK)), v["access_token"])
        self.assertEqual((st, r["low"], r["one_time_prekeys"]), (200, False, S.OTK_LOW_WATERMARK))
        self.assertEqual(self.e.call("GET", "/v1/keys/me/count", token=v["access_token"])[1]["signed_prekey"], True)

    def test_oversized_upload_batch_is_rolled_back_atomically(self):
        v = self.e.register("victim")
        for chunk in range(2): self.e.call("PUT", "/v1/keys", {"one_time_prekeys": [{"key_id": chunk * 100 + i, "key": b64()} for i in range(100)]}, v["access_token"])
        before = otk_count(self.e, v)
        st, _ = self.e.call("PUT", "/v1/keys", {"signed_prekey": {"key": b64(), "signature": b64(64)}, "one_time_prekeys": [{"key_id": 5000 + i, "key": b64()} for i in range(5)]}, v["access_token"])
        self.assertEqual(st, 400); self.assertEqual(otk_count(self.e, v), before)
        self.assertFalse(self.e.call("GET", "/v1/keys/me/count", token=v["access_token"])[1]["signed_prekey"])   # podpisany klucz z tej samej paczki też wycofany

    def test_key_upload_is_rate_limited(self):
        v = self.e.register("victim")
        codes = [self.e.call("PUT", "/v1/keys", KEYS([i]), v["access_token"])[0] for i in range(S.PUT_KEYS_PER_HOUR + 2)]
        self.assertEqual(codes[:S.PUT_KEYS_PER_HOUR], [200] * S.PUT_KEYS_PER_HOUR); self.assertEqual(codes[-2:], [429, 429])

    def test_revoking_a_device_removes_its_claims_and_keys(self):
        v = self.victim(3); a = self.e.register("alice"); fetch(self.e, a, "victim")
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM otk_claims WHERE device_id=?", v["device_id"])[0][0], 1)
        st, l = self.e.call("POST", "/v1/auth/login", {"username": "victim", "auth_secret": v["secret"], "device": DEV}); self.assertEqual(st, 200)
        self.assertEqual(self.e.call("DELETE", "/v1/devices/" + v["device_id"], token=l["access_token"])[0], 200)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM otk_claims WHERE device_id=?", v["device_id"])[0][0], 0)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=?", v["device_id"])[0][0], 0)


# ============================================================ 2. logowanie
class LoginAbuseTests(unittest.TestCase):
    def setUp(self): self.e = Env(trusted_proxies=LOCAL)     # zaufane proxy lokalne: w testach "adres klienta" ustawiamy nagłówkiem
    def tearDown(self): self.e.close()

    def login(self, user, secret, ip, **extra):
        return self.e.call("POST", "/v1/auth/login", {"username": user, "auth_secret": secret, "device": DEV, **extra}, headers=XFF(ip))

    def test_attacker_cannot_lock_the_owner_out(self):
        a = self.e.register("alice")
        for _ in range(5): self.assertEqual(self.login("alice", b64(), "198.51.100.7")[0], 401)
        self.assertEqual(self.login("alice", b64(), "198.51.100.7")[0], 429)                  # atakujący zablokował siebie
        self.assertEqual(self.login("alice", a["secret"], "198.51.100.7")[0], 429)           # i tylko siebie
        self.assertEqual(self.login("alice", a["secret"], "203.0.113.9")[0], 200)            # właściciel z innego adresu wchodzi
        self.assertEqual(self.e.sql("SELECT failed_logins, locked_until FROM accounts")[0], (0, 0))   # konto nigdy nie jest blokowane w bazie

    def test_pair_lock_expires(self):
        a = self.e.register("alice")
        for _ in range(5): self.login("alice", b64(), "198.51.100.7")
        self.assertEqual(self.login("alice", a["secret"], "198.51.100.7")[0], 429)
        self.e.clock.t += S.LOGIN_FAIL_WINDOW + 1
        self.assertEqual(self.login("alice", a["secret"], "198.51.100.7")[0], 200)

    def test_credential_guessing_is_still_limited_per_name_and_address(self):
        self.e.register("alice")
        codes = [self.login("alice", b64(), "198.51.100.7")[0] for _ in range(8)]
        self.assertEqual(codes, [401] * 5 + [429] * 3)               # po 5 błędnych: koniec zgadywania z tego adresu

    def test_one_address_cannot_guess_many_usernames(self):
        for i in range(S.Config().login_fails_per_ip): self.assertEqual(self.login("user_%03d" % i, b64(), "198.51.100.7")[0], 401)
        self.assertEqual(self.login("user_999", b64(), "198.51.100.7")[0], 429)

    def test_distributed_guessing_is_capped_per_account(self):
        e = Env(trusted_proxies=LOCAL, login_fails_per_account=6)
        try:
            e.register("alice")
            codes = [e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": b64(), "device": DEV}, headers=XFF("198.51.100.%d" % i))[0] for i in range(1, 9)]
            self.assertEqual(codes, [401] * 6 + [429] * 2)           # wiele adresów, jedno konto: globalny sufit
        finally: e.close()

    def test_responses_do_not_reveal_whether_the_account_exists(self):
        self.e.register("alice")
        for _ in range(5):          # tyle samo błędów dla istniejącego i nieistniejącego
            self.login("alice", b64(), "198.51.100.1"); self.login("nikt", b64(), "198.51.100.1")
        self.assertEqual(self.login("alice", b64(), "198.51.100.1"), self.login("nikt", b64(), "198.51.100.1"))   # status i treść identyczne (429)
        r1 = self.login("alice", b64(), "198.51.100.2"); r2 = self.login("nikt", b64(), "198.51.100.2"); self.assertEqual((r1, r2), (r1, r1)); self.assertEqual(r1[0], 401)

    def test_scrypt_is_computed_for_unknown_users_too(self):
        self.e.register("alice"); calls = []; orig = self.e.app.hash_secret
        self.e.app.hash_secret = lambda s, salt: (calls.append(1), orig(s, salt))[1]
        self.login("alice", b64(), "198.51.100.1"); self.login("nikt", b64(), "198.51.100.2")
        self.assertEqual(len(calls), 2)

    def test_success_resets_only_the_callers_own_counter(self):
        a = self.e.register("alice")
        for _ in range(4): self.login("alice", b64(), "198.51.100.7")
        self.assertEqual(self.login("alice", a["secret"], "198.51.100.7")[0], 200)
        for _ in range(4): self.assertEqual(self.login("alice", b64(), "198.51.100.7")[0], 401)
        self.assertEqual(self.login("alice", a["secret"], "198.51.100.7")[0], 200)

    def test_login_rate_limit_counts_every_attempt_per_address(self):
        e = Env(trusted_proxies=LOCAL, login_per_10min=4)
        try:
            e.register("alice")
            codes = [e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": b64(), "device": DEV}, headers=XFF("198.51.100.1"))[0] for _ in range(6)]
            self.assertEqual(codes, [401] * 4 + [429] * 2)
        finally: e.close()

    def test_works_with_the_sqlite_shared_store_too(self):
        e = Env(trusted_proxies=LOCAL, rate_limit_backend="sqlite")
        try:
            a = e.register("alice")
            for _ in range(5): e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": b64(), "device": DEV}, headers=XFF("198.51.100.7"))
            self.assertEqual(e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": a["secret"], "device": DEV}, headers=XFF("198.51.100.7"))[0], 429)
            self.assertEqual(e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": a["secret"], "device": DEV}, headers=XFF("203.0.113.9"))[0], 200)
        finally: e.close()

    def test_delete_endpoint_cannot_be_used_to_guess_the_secret(self):
        a = self.e.register("alice")
        codes = [self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": b64()}, a["access_token"])[0] for _ in range(7)]
        self.assertEqual(codes, [403] * 5 + [429] * 2)
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=a["access_token"])[0], 200)


# ============================================================ 3. usuwanie konta
TABLES = ["accounts", "devices", "sessions", "signed_prekeys", "one_time_prekeys", "otk_claims", "kyber_prekeys", "kyber_claims", "messages", "blocks"]


def snapshot(e):
    c = sqlite3.connect(e.db)
    try: return {t: sorted(map(repr, c.execute("SELECT * FROM %s" % t).fetchall())) for t in TABLES}
    finally: c.close()


def refs(e, aid, dids):
    """Liczba wierszy odnoszących się do konta w KAŻDEJ tabeli."""
    q = ",".join("?" * len(dids)); c = sqlite3.connect(e.db)
    try:
        return {
            "accounts": c.execute("SELECT COUNT(*) FROM accounts WHERE id=?", (aid,)).fetchone()[0],
            "devices": c.execute("SELECT COUNT(*) FROM devices WHERE account_id=?", (aid,)).fetchone()[0],
            "sessions": c.execute("SELECT COUNT(*) FROM sessions WHERE account_id=?", (aid,)).fetchone()[0],
            "signed_prekeys": c.execute("SELECT COUNT(*) FROM signed_prekeys WHERE device_id IN (%s)" % q, dids).fetchone()[0],
            "one_time_prekeys": c.execute("SELECT COUNT(*) FROM one_time_prekeys WHERE device_id IN (%s)" % q, dids).fetchone()[0],
            "otk_claims": c.execute("SELECT COUNT(*) FROM otk_claims WHERE requester=? OR device_id IN (%s)" % q, (aid, *dids)).fetchone()[0],
            "kyber_prekeys": c.execute("SELECT COUNT(*) FROM kyber_prekeys WHERE device_id IN (%s)" % q, dids).fetchone()[0],
            "kyber_claims": c.execute("SELECT COUNT(*) FROM kyber_claims WHERE requester=? OR device_id IN (%s)" % q, (aid, *dids)).fetchone()[0],
            "messages": c.execute("SELECT COUNT(*) FROM messages WHERE sender_account=? OR recipient_account=?", (aid, aid)).fetchone()[0],
            "blocks": c.execute("SELECT COUNT(*) FROM blocks WHERE blocker=? OR blocked=?", (aid, aid)).fetchone()[0]}
    finally: c.close()


class DeletionAtomicityTests(unittest.TestCase):
    POINTS = ["delete:begin", "delete:after_messages", "delete:after_claims", "delete:before_account_row", "delete:before_commit"]

    def setUp(self):
        self.fail_at = None
        def fault(p):
            if p == self.fail_at: raise RuntimeError("symulowana awaria w " + p)
        self.e = Env(fault=fault)
        e = self.e
        self.a = e.register("alice"); self.b = e.register("bob"); self.c = e.register("carol")
        st, self.a2 = e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": self.a["secret"], "device": {"name": "tablet", "identity_key": b64()}})
        self.dids = [self.a["device_id"], self.a2["device_id"]]
        KY = lambda: {"kyber_prekeys": [{"key_id": i, "key": b64(1500), "signature": b64(64)} for i in range(3)] + [{"key_id": 99, "key": b64(1500), "signature": b64(64), "last_resort": True}]}
        e.call("PUT", "/v1/keys", {**KEYS(range(5)), **KY()}, self.a["access_token"]); e.call("PUT", "/v1/keys", {**KEYS(range(5)), **KY()}, self.b["access_token"])
        fetch(e, self.b, "alice"); fetch(e, self.a, "bob")                      # alice jako cel i jako pytająca w otk_claims
        e.send(self.a, self.b); e.send(self.b, self.a); e.send(self.b, self.c)
        e.call("PUT", "/v1/blocks/bob", token=self.a["access_token"]); e.call("PUT", "/v1/blocks/alice", token=self.c["access_token"])
    def tearDown(self): self.e.close()

    def test_fixture_really_has_data_in_every_table(self):
        r = refs(self.e, self.a["account_id"], self.dids)
        self.assertTrue(all(v > 0 for v in r.values()), r)

    def test_failure_at_any_point_rolls_everything_back(self):
        for point in self.POINTS:
            with self.subTest(point=point):
                before = snapshot(self.e); self.fail_at = point
                st, _ = self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": self.a["secret"]}, self.a["access_token"])
                self.assertEqual(st, 500)
                self.fail_at = None
                self.assertEqual(snapshot(self.e), before)                       # żaden wiersz nie zniknął ani nie zmienił się
                self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=self.a["access_token"])[0], 200)   # konto i sesja nadal działają
                self.e.app.rl.reset("del:" + self.a["account_id"])                # limit prób usuwania nie jest tu przedmiotem testu

    def test_after_a_failed_attempt_a_retry_succeeds_completely(self):
        self.fail_at = "delete:before_commit"
        self.assertEqual(self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": self.a["secret"]}, self.a["access_token"])[0], 500)
        self.fail_at = None
        self.assertEqual(self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": self.a["secret"]}, self.a["access_token"])[0], 200)
        self.assertEqual(refs(self.e, self.a["account_id"], self.dids), {t: 0 for t in TABLES})

    def test_deletion_removes_all_related_data_and_leaves_others_intact(self):
        others_before = refs(self.e, self.b["account_id"], [self.b["device_id"]])
        self.assertEqual(self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": self.a["secret"]}, self.a["access_token"])[0], 200)
        self.assertEqual(refs(self.e, self.a["account_id"], self.dids), {t: 0 for t in TABLES})
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=self.a2["access_token"])[0], 401)      # druga sesja też nieważna
        self.assertEqual(self.e.call("POST", "/v1/auth/refresh", {"refresh_token": self.a2["refresh_token"]})[0], 401)
        # dane bobu: jego klucze i blokada carol->alice zostają; jego wiadomość do carol zostaje
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=?", self.b["device_id"])[0][0] > 0, True)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM messages WHERE sender_account=? AND recipient_account=?", self.b["account_id"], self.c["account_id"])[0][0], 1)
        self.assertEqual(len(self.e.call("GET", "/v1/messages/pending", token=self.c["access_token"])[1]["messages"]), 1)

    def test_failed_key_upload_leaves_no_partial_state(self):
        v = self.e.register("dave"); self.e.call("PUT", "/v1/keys", KEYS(range(3)), v["access_token"])
        with mock.patch.object(S, "MAX_OTK_TOTAL", 4):
            st, _ = self.e.call("PUT", "/v1/keys", {"signed_prekey": {"key": b64(), "signature": b64(64)}, "one_time_prekeys": [{"key_id": 50 + i, "key": b64()} for i in range(3)]}, v["access_token"])
        self.assertEqual(st, 400); self.assertEqual(otk_count(self.e, v), 3)


# ============================================================ 4. proxy i adresy klientów
class ClientIpTests(unittest.TestCase):
    T = tuple(__import__("ipaddress").ip_network(n) for n in ("10.0.0.0/8", "127.0.0.1/32"))

    def test_header_is_ignored_when_the_peer_is_not_a_trusted_proxy(self):
        self.assertEqual(client_ip("203.0.113.5", "1.2.3.4", self.T), "203.0.113.5")
        self.assertEqual(client_ip("203.0.113.5", "1.2.3.4", ()), "203.0.113.5")

    def test_header_is_ignored_without_any_configured_proxy_even_from_localhost(self):
        self.assertEqual(client_ip("127.0.0.1", "1.2.3.4", ()), "127.0.0.1")

    def test_rightmost_untrusted_hop_wins_and_client_supplied_prefix_is_ignored(self):
        self.assertEqual(client_ip("127.0.0.1", "9.9.9.9, 1.2.3.4", self.T), "1.2.3.4")           # 9.9.9.9 wpisał klient
        self.assertEqual(client_ip("127.0.0.1", "9.9.9.9, 1.2.3.4, 10.1.1.1", self.T), "1.2.3.4") # za nim własny proxy
        self.assertEqual(client_ip("10.2.2.2", "1.2.3.4", self.T), "1.2.3.4")

    def test_malformed_or_missing_header_falls_back_to_the_peer(self):
        for bad in ["", "nie-ip", "1.2.3.4, ???", "1.2.3.4,,"]:
            self.assertEqual(client_ip("127.0.0.1", bad, self.T), "127.0.0.1", bad)

    def test_all_hops_trusted_falls_back_to_the_peer(self):
        self.assertEqual(client_ip("127.0.0.1", "10.1.1.1, 10.2.2.2", self.T), "127.0.0.1")

    def test_ipv6_is_bucketed_per_slash_64_and_mapped_ipv4_is_unwrapped(self):
        a = client_ip("2001:db8:1:2:aaaa::1", "", ()); b = client_ip("2001:db8:1:2:bbbb::9", "", ()); c = client_ip("2001:db8:1:3::1", "", ())
        self.assertEqual(a, b); self.assertNotEqual(a, c)
        self.assertEqual(client_ip("::ffff:203.0.113.5", "", ()), "203.0.113.5")

    def test_invalid_peer(self): self.assertEqual(client_ip("garbage", "1.2.3.4", self.T), "invalid")


class ProxyIntegrationTests(unittest.TestCase):
    def reg(self, e, i, headers):
        return e.call("POST", "/v1/accounts", {"username": "user_%s" % i, "auth_secret": b64(), "device": DEV}, headers=headers)[0]

    def test_spoofed_forwarded_for_does_not_evade_limits_by_default(self):
        e = Env(register_per_hour=2)
        try: self.assertEqual([self.reg(e, i, XFF("1.2.3.%d" % i)) for i in range(4)], [201, 201, 429, 429])
        finally: e.close()

    def test_spoofed_prefix_does_not_evade_limits_behind_a_trusted_proxy(self):
        e = Env(register_per_hour=2, trusted_proxies=["127.0.0.1/32"])
        try:   # proxy dopisuje prawdziwy adres (198.51.100.9) na końcu; klient dokleja fałszywe z lewej
            codes = [self.reg(e, i, XFF("7.7.7.%d, 198.51.100.9" % i)) for i in range(4)]
            self.assertEqual(codes, [201, 201, 429, 429])
        finally: e.close()

    def test_different_real_clients_behind_a_trusted_proxy_are_counted_separately(self):
        e = Env(register_per_hour=1, trusted_proxies=["127.0.0.1/32"])
        try: self.assertEqual([self.reg(e, i, XFF("198.51.100.%d" % i)) for i in range(3)], [201, 201, 201])
        finally: e.close()

    def test_legacy_boolean_setting_is_rejected_at_startup(self):
        with mock.patch.dict(os.environ, {"SHYFR_TRUSTED_PROXY": "true"}):
            with self.assertRaises(SystemExit): Config.from_env()

    def test_trusted_proxies_are_parsed_from_the_environment(self):
        with mock.patch.dict(os.environ, {"SHYFR_TRUSTED_PROXIES": "127.0.0.1/32, ::1/128"}, clear=False):
            os.environ.pop("SHYFR_TRUSTED_PROXY", None)
            self.assertEqual([str(n) for n in Config.from_env().trusted_proxies], ["127.0.0.1/32", "::1/128"])

    def test_invalid_cidr_fails_loudly(self):
        with self.assertRaises(ValueError): Config(trusted_proxies=["to-nie-cidr"])


# ============================================================ 5. doręczanie wiadomości
class DeliverySemanticsTests(Base):
    def pending(self, who, q=""): return self.e.call("GET", "/v1/messages/pending" + q, token=who["access_token"])[1]

    def test_message_stays_in_the_queue_until_acknowledged(self):
        a = self.e.register("alice"); b = self.e.register("bob"); pl = b64(40); self.e.send(a, b, pl)
        for _ in range(3): self.assertEqual([m["payload"] for m in self.pending(b)["messages"]], [pl])   # klient padł przed ACK: wiadomość czeka
        self.assertEqual(self.e.call("GET", "/v1/messages/pending?after=0", token=b["access_token"])[1]["messages"][0]["payload"], pl)

    def test_client_that_crashed_after_a_later_ack_still_gets_the_earlier_message_with_after_zero(self):
        a = self.e.register("alice"); b = self.e.register("bob"); self.e.send(a, b, b64(20)); self.e.send(a, b, b64(21))
        m = self.pending(b)["messages"]
        self.e.call("POST", "/v1/messages/%s/ack" % m[1]["id"], token=b["access_token"])        # potwierdzono tylko drugą
        again = self.pending(b, "?after=0")["messages"]
        self.assertEqual([x["id"] for x in again], [m[0]["id"]])                                 # pierwsza nie zginęła
        self.assertEqual(self.pending(b, "?after=%d" % m[1]["seq"])["messages"], [])             # kursor stronicowania nie jest potwierdzeniem

    def test_batch_ack_reports_per_message_results_and_is_idempotent(self):
        a = self.e.register("alice"); b = self.e.register("bob"); c = self.e.register("carol")
        ids = [self.e.send(a, b)[1]["id"] for _ in range(3)]; foreign = self.e.send(a, c)[1]["id"]
        st, r = self.e.call("POST", "/v1/messages/ack", {"ids": ids + [foreign, "0" * 32, "zły"]}, b["access_token"])
        self.assertEqual(st, 200)
        self.assertEqual([r["results"][i] for i in ids], ["ok"] * 3)
        self.assertEqual((r["results"][foreign], r["results"]["0" * 32], r["results"]["zły"]), ("not_found",) * 3)   # cudzej nie ruszamy
        self.assertEqual(self.pending(b)["messages"], []); self.assertEqual(len(self.pending(c)["messages"]), 1)
        self.assertEqual(self.e.call("POST", "/v1/messages/ack", {"ids": ids}, b["access_token"])[1]["results"], {i: "ok" for i in ids})   # powtórka
        self.assertEqual(self.e.call("GET", "/v1/messages/%s/status" % ids[0], token=a["access_token"])[1]["state"], "delivered")

    def test_batch_ack_validation(self):
        b = self.e.register("bob")
        for bad in [{}, {"ids": []}, {"ids": "x"}, {"ids": [1]}, {"ids": ["0" * 32] * 101}]:
            self.assertEqual(self.e.call("POST", "/v1/messages/ack", bad, b["access_token"])[0], 400, bad)
        self.assertEqual(self.e.call("POST", "/v1/messages/ack", {"ids": ["0" * 32]})[0], 401)

    def test_batch_ack_reports_expired(self):
        e = Env(message_ttl=100)
        try:
            a = e.register("alice"); b = e.register("bob"); mid = e.send(a, b)[1]["id"]; e.clock.t += 101
            self.assertEqual(e.call("POST", "/v1/messages/ack", {"ids": [mid]}, b["access_token"])[1]["results"], {mid: "expired"})
        finally: e.close()

    def test_has_more_flag_and_full_drain_by_paging(self):
        a = self.e.register("alice"); b = self.e.register("bob")
        for _ in range(105): self.e.send(a, b)
        p1 = self.pending(b); self.assertEqual((len(p1["messages"]), p1["has_more"]), (100, True))
        self.e.call("POST", "/v1/messages/ack", {"ids": [m["id"] for m in p1["messages"]]}, b["access_token"])
        p2 = self.pending(b, "?after=0"); self.assertEqual((len(p2["messages"]), p2["has_more"]), (5, False))

    def test_retry_of_a_send_after_the_recipient_already_acked_is_still_idempotent(self):
        a = self.e.register("alice"); b = self.e.register("bob"); pl = b64(30)
        s1 = self.e.send(a, b, pl, "idem-after-ack-1"); mid = s1[1]["id"]
        self.e.call("POST", "/v1/messages/ack", {"ids": [mid]}, b["access_token"])
        s2 = self.e.send(a, b, pl, "idem-after-ack-1")                     # nadawca nie dostał odpowiedzi i ponawia
        self.assertEqual((s2[0], s2[1]["id"], s2[1]["state"]), (200, mid, "delivered"))
        self.assertEqual(self.pending(b)["messages"], [])                  # i nie ma duplikatu w skrzynce
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM messages")[0][0], 1)

    def test_concurrent_duplicate_sends_create_exactly_one_message(self):
        a = self.e.register("alice"); b = self.e.register("bob"); pl = b64(30); out = []; lock = threading.Lock()
        def go():
            r = self.e.send(a, b, pl, "idem-concurrent-1")
            with lock: out.append(r)
        ts = [threading.Thread(target=go) for _ in range(8)]; [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(st for st, _ in out).count(202), 1); self.assertEqual({r["id"] for _, r in out}, {out[0][1]["id"]})
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM messages")[0][0], 1)

    def test_revoked_device_queue_is_discarded_and_other_devices_keep_theirs(self):
        a = self.e.register("alice"); b = self.e.register("bob")
        st, b2 = self.e.call("POST", "/v1/auth/login", {"username": "bob", "auth_secret": b["secret"], "device": DEV})
        self.e.send(a, b); self.e.send(a, {"username": "bob", "device_id": b2["device_id"]})
        self.assertEqual(self.e.call("DELETE", "/v1/devices/" + b2["device_id"], token=b["access_token"])[0], 200)
        self.assertEqual(len(self.pending(b)["messages"]), 1)
        self.assertEqual(self.e.call("GET", "/v1/messages/pending", token=b2["access_token"])[0], 401)

    def test_session_expiry_does_not_lose_queued_messages(self):
        a = self.e.register("alice"); b = self.e.register("bob"); pl = b64(30); self.e.send(a, b, pl)
        self.e.clock.t += 901                                               # token dostępu wygasł, wiadomość nadal czeka
        self.assertEqual(self.e.call("GET", "/v1/messages/pending", token=b["access_token"])[0], 401)
        st, r = self.e.call("POST", "/v1/auth/refresh", {"refresh_token": b["refresh_token"]})
        self.assertEqual([m["payload"] for m in self.pending(r)["messages"]], [pl])

    def test_authenticated_requests_update_last_seen(self):
        a = self.e.register("alice"); self.e.clock.t += 500
        self.e.call("GET", "/v1/accounts/me", token=a["access_token"])
        st, d = self.e.call("GET", "/v1/devices", token=a["access_token"])
        self.assertEqual(d["devices"][0]["last_seen"], self.e.clock.t)


class RobustnessTests(Base):
    def test_negative_or_invalid_content_length_is_rejected(self):
        import http.client
        for v in ["-5", "abc"]:
            h = http.client.HTTPConnection(self.e.base.split("//")[1]); h.putrequest("POST", "/v1/accounts"); h.putheader("Content-Length", v); h.endheaders()
            self.assertEqual(h.getresponse().status, 400); h.close()


if __name__ == "__main__":
    unittest.main()


# ============================================================ prekeye post-kwantowe (PQXDH)
KY = lambda ids, lr=None: {"kyber_prekeys": [{"key_id": i, "key": b64(1568), "signature": b64(64)} for i in ids] + ([{"key_id": lr, "key": b64(1568), "signature": b64(64), "last_resort": True}] if lr is not None else [])}


class PqPrekeyTests(Base):
    def put(self, who, body): return self.e.call("PUT", "/v1/keys", body, who["access_token"])

    def test_large_kem_keys_are_accepted_and_returned_with_signed_prekey_id(self):
        v = self.e.register("victim"); a = self.e.register("alice")
        st, _ = self.put(v, {"signed_prekey": {"key_id": 7, "key": b64(), "signature": b64(64)}, **KY([1, 2], lr=100)}); self.assertEqual(st, 200)
        d = fetch(self.e, a, "victim")[1]["devices"][0]
        self.assertEqual(d["signed_prekey"]["key_id"], 7)
        self.assertEqual((d["kyber_prekey"]["key_id"], d["kyber_prekey"]["last_resort"]), (1, False)); self.assertGreater(len(d["kyber_prekey"]["key"]), 2000)

    def test_last_resort_is_served_when_one_time_kem_keys_run_out_and_is_never_consumed(self):
        v = self.e.register("victim"); self.put(v, {**KEYS([]), **KY([], lr=100)})
        for i in range(3):
            a = self.e.register("req_%d" % i); k = fetch(self.e, a, "victim")[1]["devices"][0]["kyber_prekey"]
            self.assertEqual((k["key_id"], k["last_resort"]), (100, True))
        self.assertTrue(self.e.call("GET", "/v1/keys/me/count", token=v["access_token"])[1]["kyber_last_resort"])

    def test_kem_one_time_keys_obey_the_same_drain_protection(self):
        v = self.e.register("victim"); self.put(v, {**KEYS([]), **KY(range(50), lr=900)}); atk = self.e.register("attacker")
        ids = {fetch(self.e, atk, "victim")[1]["devices"][0]["kyber_prekey"]["key_id"] for _ in range(20)}
        self.assertEqual(len(ids), 1)
        for i in range(S.OTK_DEVICE_HOURLY_CAP + 5): fetch(self.e, self.e.register("s_%d" % i), "victim")
        left = self.e.call("GET", "/v1/keys/me/count", token=v["access_token"])[1]["kyber_one_time"]
        self.assertGreaterEqual(left, 50 - S.OTK_DEVICE_HOURLY_CAP)

    def test_new_last_resort_replaces_the_old_one(self):
        v = self.e.register("victim"); self.put(v, KY([], lr=1)); self.put(v, KY([], lr=2)); a = self.e.register("alice")
        self.assertEqual(fetch(self.e, a, "victim")[1]["devices"][0]["kyber_prekey"]["key_id"], 2)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM kyber_prekeys WHERE last_resort=1")[0][0], 1)

    def test_validation(self):
        v = self.e.register("victim")
        for bad in [{"kyber_prekeys": "x"}, {"kyber_prekeys": [{"key_id": -1, "key": b64(), "signature": b64()}]},
                    {"kyber_prekeys": [{"key_id": 1, "key": "!!!", "signature": b64()}]}, {"kyber_prekeys": [{"key_id": 1, "key": b64(5000), "signature": b64()}]},
                    {"kyber_prekeys": [{"key_id": 1, "key": b64(), "signature": b64(), "last_resort": "yes"}]},
                    {**KY([], lr=1), "kyber_prekeys": KY([], lr=1)["kyber_prekeys"] * 2},
                    {"signed_prekey": {"key_id": -5, "key": b64(), "signature": b64()}}]:
            self.assertEqual(self.put(v, bad)[0], 400, str(bad)[:60])

    def test_blocked_requester_gets_no_kem_key(self):
        v = self.e.register("victim"); a = self.e.register("alice"); self.put(v, KY([1], lr=9)); self.e.call("PUT", "/v1/blocks/alice", token=v["access_token"])
        self.assertIsNone(fetch(self.e, a, "victim")[1]["devices"][0]["kyber_prekey"])
