import base64, json, os, secrets, shutil, sqlite3, tempfile, threading, time, unittest, urllib.error, urllib.request

from shyfr_server.server import Config, make_server


def b64(n=32):
    return base64.urlsafe_b64encode(secrets.token_bytes(n)).decode().rstrip("=")


class Clock:
    def __init__(self): self.t = 1_800_000_000
    def __call__(self): return self.t


class Env:
    def __init__(self, **kw):
        self.tmp = tempfile.mkdtemp(); self.db = os.path.join(self.tmp, "t.db"); self.clock = Clock()
        self.kw = dict(register_per_hour=1000, login_per_10min=1000, send_per_min=1000); self.kw.update(kw); self.start()

    def start(self):
        self.srv, self.app = make_server(Config(db_path=self.db, clock=self.clock, **self.kw))
        self.base = "http://127.0.0.1:%d" % self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def stop(self):
        self.srv.shutdown(); self.srv.server_close()

    def close(self):
        self.stop(); shutil.rmtree(self.tmp, ignore_errors=True)

    def call(self, method, path, body=None, token=None, raw=None, headers=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token: req.add_header("Authorization", "Bearer " + token)
        for k, v in (headers or {}).items(): req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=15) as r: return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def register(self, username, secret=None, device="Telefon"):
        secret = secret or b64(32)
        st, r = self.call("POST", "/v1/accounts", {"username": username, "auth_secret": secret, "device": {"name": device, "identity_key": b64(32)}})
        assert st == 201, (st, r)
        r["secret"] = secret; r["username"] = username
        return r

    def send(self, frm, to, payload=None, cmid=None):
        return self.call("POST", "/v1/messages", {"recipient_username": to["username"], "recipient_device_id": to["device_id"],
                                                   "client_msg_id": cmid or b64(12), "payload": payload or b64(40)}, frm["access_token"])

    def sql(self, q, *a):
        c = sqlite3.connect(self.db)
        try: return c.execute(q, a).fetchall()
        finally: c.close()


class Base(unittest.TestCase):
    def setUp(self): self.e = Env()
    def tearDown(self): self.e.close()


class AccountTests(Base):
    def test_register_and_use_token(self):
        a = self.e.register("alice")
        st, me = self.e.call("GET", "/v1/accounts/me", token=a["access_token"])
        self.assertEqual((st, me["username"], me["account_id"]), (200, "alice", a["account_id"]))

    def test_username_is_unique_case_insensitively_and_validated(self):
        self.e.register("alice")
        st, _ = self.e.call("POST", "/v1/accounts", {"username": "ALICE", "auth_secret": b64(), "device": {"name": "x", "identity_key": b64()}})
        self.assertEqual(st, 409)
        for bad in ["ab", "x' OR '1'='1", "a b", "ą_ę_ó", "a" * 25, "drop;table"]:
            st, _ = self.e.call("POST", "/v1/accounts", {"username": bad, "auth_secret": b64(), "device": {"name": "x", "identity_key": b64()}})
            self.assertEqual(st, 400, bad)

    def test_weak_or_missing_secret_is_rejected(self):
        for s in [None, "", "krótki", b64(8), b64(200), "!!!!"]:
            st, _ = self.e.call("POST", "/v1/accounts", {"username": "bob_" + secrets.token_hex(2), "auth_secret": s, "device": {"name": "x", "identity_key": b64()}})
            self.assertEqual(st, 400, s)

    def test_password_is_never_stored_only_a_scrypt_hash(self):
        s = b64(32); self.e.register("alice", secret=s)
        dump = json.dumps(self.e.sql("SELECT auth_salt, auth_hash FROM accounts"))
        self.assertNotIn(s, dump); self.assertEqual(len(self.e.sql("SELECT auth_hash FROM accounts")[0][0]), 64)

    def test_login_errors_are_identical_for_unknown_user_and_wrong_secret(self):
        self.e.register("alice")
        r1 = self.e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": b64(), "device": {"name": "x", "identity_key": b64()}})
        r2 = self.e.call("POST", "/v1/auth/login", {"username": "nikt", "auth_secret": b64(), "device": {"name": "x", "identity_key": b64()}})
        self.assertEqual(r1, r2); self.assertEqual(r1[0], 401)

    def test_register_is_rate_limited(self):
        e = Env(register_per_hour=3)
        try:
            codes = []
            for i in range(5):
                codes.append(e.call("POST", "/v1/accounts", {"username": "user_%d" % i, "auth_secret": b64(), "device": {"name": "x", "identity_key": b64()}})[0])
            self.assertEqual(codes, [201, 201, 201, 429, 429])
        finally: e.close()


class SessionTests(Base):
    def test_requests_without_valid_token_are_rejected(self):
        for tok in [None, "", "zmyslony", "a" * 500]:
            self.assertEqual(self.e.call("GET", "/v1/devices", token=tok)[0], 401)

    def test_access_token_expires_and_refresh_issues_a_new_one(self):
        a = self.e.register("alice")
        self.e.clock.t += 901
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=a["access_token"])[0], 401)
        st, r = self.e.call("POST", "/v1/auth/refresh", {"refresh_token": a["refresh_token"]})
        self.assertEqual(st, 200); self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=r["access_token"])[0], 200)

    def test_refresh_rotates_and_reuse_of_an_old_token_kills_the_session(self):
        a = self.e.register("alice")
        st, r1 = self.e.call("POST", "/v1/auth/refresh", {"refresh_token": a["refresh_token"]})
        self.assertEqual(st, 200)
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=a["access_token"])[0], 401)           # stary dostęp już nieważny
        self.assertEqual(self.e.call("POST", "/v1/auth/refresh", {"refresh_token": a["refresh_token"]})[0], 401)   # ponowne użycie starego
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=r1["access_token"])[0], 401)           # cała sesja unieważniona
        self.assertEqual(self.e.call("POST", "/v1/auth/refresh", {"refresh_token": r1["refresh_token"]})[0], 401)

    def test_logout_revokes_the_session(self):
        a = self.e.register("alice")
        self.assertEqual(self.e.call("POST", "/v1/auth/logout", token=a["access_token"])[0], 200)
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=a["access_token"])[0], 401)
        self.assertEqual(self.e.call("POST", "/v1/auth/refresh", {"refresh_token": a["refresh_token"]})[0], 401)

    def test_tokens_are_stored_only_as_hashes(self):
        a = self.e.register("alice")
        dump = json.dumps(self.e.sql("SELECT access_hash, refresh_hash FROM sessions"))
        self.assertNotIn(a["access_token"], dump); self.assertNotIn(a["refresh_token"], dump)

    def test_second_device_login_and_device_listing(self):
        a = self.e.register("alice")
        st, r = self.e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": a["secret"], "device": {"name": "Tablet", "identity_key": b64()}})
        self.assertEqual(st, 200); self.assertNotEqual(r["device_id"], a["device_id"])
        st, d = self.e.call("GET", "/v1/devices", token=r["access_token"])
        self.assertEqual(sorted(x["name"] for x in d["devices"]), ["Tablet", "Telefon"])
        self.assertEqual([x["name"] for x in d["devices"] if x["current"]], ["Tablet"])


class DeviceAndKeyTests(Base):
    def test_revoking_a_device_ends_its_sessions_and_drops_its_queue(self):
        a = self.e.register("alice"); b = self.e.register("bob")
        st, b2 = self.e.call("POST", "/v1/auth/login", {"username": "bob", "auth_secret": b["secret"], "device": {"name": "Tablet", "identity_key": b64()}})
        self.assertEqual(self.e.send(a, b)[0], 202)
        self.assertEqual(self.e.call("DELETE", "/v1/devices/" + b["device_id"], token=b2["access_token"])[0], 200)
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=b["access_token"])[0], 401)
        self.assertEqual(self.e.call("POST", "/v1/auth/refresh", {"refresh_token": b["refresh_token"]})[0], 401)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM messages WHERE recipient_device=? AND payload IS NOT NULL", b["device_id"])[0][0], 0)
        self.assertEqual(self.e.send(a, b)[0], 404)       # do unieważnionego urządzenia nie da się już pisać
        st, keys = self.e.call("GET", "/v1/keys/bob", token=a["access_token"])
        self.assertEqual([d["device_id"] for d in keys["devices"]], [b2["device_id"]])

    def test_cannot_revoke_someone_elses_device(self):
        a = self.e.register("alice"); b = self.e.register("bob")
        self.assertEqual(self.e.call("DELETE", "/v1/devices/" + b["device_id"], token=a["access_token"])[0], 404)
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=b["access_token"])[0], 200)

    def test_one_time_prekeys_are_handed_out_once_per_requester(self):
        # Zmieniono w etapie 39: stary test zakładał, że KAŻDE pobranie zużywa klucz (to była luka: wyczerpywanie puli cudzego konta).
        a = self.e.register("alice"); c = self.e.register("carol"); d = self.e.register("dave"); b = self.e.register("bob")
        st, r = self.e.call("PUT", "/v1/keys", {"signed_prekey": {"key": b64(), "signature": b64(64)}, "one_time_prekeys": [{"key_id": i, "key": b64()} for i in (1, 2, 3)]}, b["access_token"])
        self.assertEqual((st, r["one_time_prekeys"]), (200, 3))
        got = []
        for who in (a, c, d, a):                      # alice pytająca drugi raz dostaje TEN SAM klucz co za pierwszym razem
            st, k = self.e.call("GET", "/v1/keys/bob", token=who["access_token"])
            got.append(k["devices"][0]["one_time_prekey"]["key_id"])
        self.assertEqual(got, [1, 2, 3, 1])
        self.assertIsNotNone(k["devices"][0]["signed_prekey"]); self.assertEqual(k["devices"][0]["device_id"], b["device_id"])
        self.assertEqual(self.e.call("GET", "/v1/keys/me/count", token=b["access_token"])[1]["one_time_prekeys"], 0)

    def test_key_upload_validation_and_limits(self):
        b = self.e.register("bob")
        bad = [{"one_time_prekeys": [{"key_id": "x", "key": b64()}]}, {"one_time_prekeys": [{"key_id": 1, "key": "!!"}]},
               {"one_time_prekeys": [{"key_id": i, "key": b64()} for i in range(101)]}, {"signed_prekey": {"key": b64()}}, {"signed_prekey": "x"},
               {"one_time_prekeys": [{"key_id": True, "key": b64()}]}]
        for body in bad: self.assertEqual(self.e.call("PUT", "/v1/keys", body, b["access_token"])[0], 400, body)
        for chunk in range(2): self.assertEqual(self.e.call("PUT", "/v1/keys", {"one_time_prekeys": [{"key_id": chunk * 100 + i, "key": b64()} for i in range(100)]}, b["access_token"])[0], 200)
        self.assertEqual(self.e.call("PUT", "/v1/keys", {"one_time_prekeys": [{"key_id": 999, "key": b64()}]}, b["access_token"])[0], 400)   # limit łączny

    def test_key_directory_requires_login_and_hides_nothing_secret(self):
        self.e.register("bob")
        self.assertEqual(self.e.call("GET", "/v1/keys/bob")[0], 401)
        self.assertEqual(self.e.call("GET", "/v1/keys/bob%27%20OR%20%271%27%3D%271", token="x")[0], 404)   # próba wstrzyknięcia w ścieżce nie pasuje do żadnej trasy


class MessageTests(Base):
    def test_full_lifecycle_and_payload_is_removed_after_delivery(self):
        a = self.e.register("alice"); b = self.e.register("bob"); payload = b64(60)
        st, r = self.e.send(a, b, payload); self.assertEqual((st, r["state"]), (202, "accepted"))
        st, p = self.e.call("GET", "/v1/messages/pending", token=b["access_token"])
        self.assertEqual([m["payload"] for m in p["messages"]], [payload]); self.assertEqual(p["messages"][0]["sender"]["username"], "alice")
        self.assertEqual(self.e.call("POST", "/v1/messages/%s/ack" % r["id"], token=b["access_token"])[0], 200)
        self.assertEqual(self.e.call("GET", "/v1/messages/%s/status" % r["id"], token=a["access_token"])[1]["state"], "delivered")
        self.assertEqual(self.e.call("GET", "/v1/messages/pending", token=b["access_token"])[1]["messages"], [])
        self.assertIsNone(self.e.sql("SELECT payload FROM messages WHERE id=?", r["id"])[0][0])
        self.assertEqual(self.e.call("POST", "/v1/messages/%s/read" % r["id"], token=b["access_token"])[0], 200)
        self.assertEqual(self.e.call("GET", "/v1/messages/%s/status" % r["id"], token=a["access_token"])[1]["state"], "read")

    def test_read_requires_prior_acknowledgement(self):
        a = self.e.register("alice"); b = self.e.register("bob"); r = self.e.send(a, b)[1]
        self.assertEqual(self.e.call("POST", "/v1/messages/%s/read" % r["id"], token=b["access_token"])[0], 409)

    def test_ack_is_idempotent(self):
        a = self.e.register("alice"); b = self.e.register("bob"); r = self.e.send(a, b)[1]
        for _ in range(3): self.assertEqual(self.e.call("POST", "/v1/messages/%s/ack" % r["id"], token=b["access_token"])[0], 200)

    def test_broken_object_level_authorization(self):
        a = self.e.register("alice"); b = self.e.register("bob"); c = self.e.register("carol"); r = self.e.send(a, b)[1]; mid = r["id"]
        for who in (c, a):   # obca osoba i sam nadawca nie mogą potwierdzać ani czytać cudzej skrzynki
            self.assertEqual(self.e.call("POST", "/v1/messages/%s/ack" % mid, token=who["access_token"])[0], 404)
            self.assertEqual(self.e.call("POST", "/v1/messages/%s/read" % mid, token=who["access_token"])[0], 404)
        for who in (c, b): self.assertEqual(self.e.call("GET", "/v1/messages/%s/status" % mid, token=who["access_token"])[0], 404)   # stan widzi tylko nadawca
        self.assertEqual(self.e.call("GET", "/v1/messages/pending", token=c["access_token"])[1]["messages"], [])
        self.assertEqual(len(self.e.call("GET", "/v1/messages/pending", token=b["access_token"])[1]["messages"]), 1)

    def test_idempotent_send_creates_one_message(self):
        a = self.e.register("alice"); b = self.e.register("bob"); pl = b64(30)
        s1 = self.e.send(a, b, pl, "klucz-idem-0001"); s2 = self.e.send(a, b, pl, "klucz-idem-0001")
        self.assertEqual((s1[0], s2[0], s1[1]["id"] == s2[1]["id"]), (202, 200, True))
        self.assertEqual(len(self.e.call("GET", "/v1/messages/pending", token=b["access_token"])[1]["messages"]), 1)
        self.assertEqual(self.e.send(a, b, b64(30), "klucz-idem-0001")[0], 409)   # ten sam klucz, inna treść

    def test_messages_arrive_in_order_and_paging_works(self):
        a = self.e.register("alice"); b = self.e.register("bob"); pls = [b64(20) for _ in range(5)]
        for p in pls: self.e.send(a, b, p)
        m = self.e.call("GET", "/v1/messages/pending", token=b["access_token"])[1]["messages"]
        self.assertEqual([x["payload"] for x in m], pls)
        later = self.e.call("GET", "/v1/messages/pending?after=%d" % m[2]["seq"], token=b["access_token"])[1]["messages"]
        self.assertEqual([x["payload"] for x in later], pls[3:])

    def test_queue_survives_a_server_restart(self):
        a = self.e.register("alice"); b = self.e.register("bob"); pl = b64(50); self.e.send(a, b, pl)
        self.e.stop(); self.e.start()
        m = self.e.call("GET", "/v1/messages/pending", token=b["access_token"])[1]["messages"]    # sesja też przetrwała w bazie
        self.assertEqual([x["payload"] for x in m], [pl])

    def test_unacknowledged_message_expires_and_is_not_delivered(self):
        e = Env(message_ttl=100)
        try:
            a = e.register("alice"); b = e.register("bob"); r = e.send(a, b)[1]
            e.clock.t += 101
            self.assertEqual(e.call("GET", "/v1/messages/pending", token=b["access_token"])[1]["messages"], [])
            self.assertEqual(e.call("GET", "/v1/messages/%s/status" % r["id"], token=a["access_token"])[1]["state"], "expired")
            self.assertEqual(e.call("POST", "/v1/messages/%s/ack" % r["id"], token=b["access_token"])[0], 410)
            self.assertIsNone(e.sql("SELECT payload FROM messages WHERE id=?", r["id"])[0][0])
        finally: e.close()

    def test_blocked_sender_is_not_told_and_nothing_is_delivered(self):
        a = self.e.register("alice"); c = self.e.register("carol")
        self.assertEqual(self.e.call("PUT", "/v1/blocks/alice", token=c["access_token"])[0], 200)
        st, r = self.e.send(a, c); self.assertEqual((st, r["state"]), (202, "accepted"))
        self.assertEqual(self.e.call("GET", "/v1/messages/pending", token=c["access_token"])[1]["messages"], [])
        self.assertEqual(self.e.call("GET", "/v1/messages/%s/status" % r["id"], token=a["access_token"])[1]["state"], "accepted")
        self.assertEqual(self.e.call("DELETE", "/v1/blocks/alice", token=c["access_token"])[0], 200)
        self.assertEqual(self.e.call("GET", "/v1/messages/pending", token=c["access_token"])[1]["messages"], [])   # wcześniejsze zostają porzucone
        r2 = self.e.send(a, c)[1]; self.assertEqual(len(self.e.call("GET", "/v1/messages/pending", token=c["access_token"])[1]["messages"]), 1)

    def test_validation(self):
        a = self.e.register("alice"); b = self.e.register("bob")
        base = {"recipient_username": "bob", "recipient_device_id": b["device_id"], "client_msg_id": b64(12), "payload": b64(10)}
        for patch in [{"payload": "!!!"}, {"payload": ""}, {"client_msg_id": "x"}, {"recipient_device_id": "zły"}, {"recipient_username": ""}]:
            self.assertEqual(self.e.call("POST", "/v1/messages", {**base, **patch}, a["access_token"])[0], 400, patch)
        self.assertEqual(self.e.call("POST", "/v1/messages", {**base, "payload": "A" * 90_001}, a["access_token"])[0], 413)
        self.assertEqual(self.e.call("POST", "/v1/messages", {**base, "recipient_username": "nikt_taki"}, a["access_token"])[0], 404)
        self.assertEqual(self.e.call("POST", "/v1/messages", {**base, "recipient_device_id": "0" * 32}, a["access_token"])[0], 404)
        self.assertEqual(self.e.call("POST", "/v1/messages", raw=b"{nie json", token=a["access_token"])[0], 400)
        self.assertEqual(self.e.call("POST", "/v1/messages", raw=b"[1,2]", token=a["access_token"])[0], 400)

    def test_send_is_rate_limited_per_account(self):
        e = Env(send_per_min=3)
        try:
            a = e.register("alice"); b = e.register("bob")
            self.assertEqual([e.send(a, b)[0] for _ in range(5)], [202, 202, 202, 429, 429])
        finally: e.close()

    def test_long_poll_returns_as_soon_as_a_message_arrives(self):
        a = self.e.register("alice"); b = self.e.register("bob"); out = {}
        def poll():
            t0 = time.time(); out["r"] = self.e.call("GET", "/v1/messages/pending?wait=10", token=b["access_token"]); out["dt"] = time.time() - t0
        th = threading.Thread(target=poll); th.start(); time.sleep(0.6); self.e.send(a, b); th.join(15)
        self.assertEqual(len(out["r"][1]["messages"]), 1); self.assertLess(out["dt"], 5)


class DeletionTests(Base):
    def test_account_deletion_removes_personal_data(self):
        a = self.e.register("alice"); b = self.e.register("bob")
        self.e.call("PUT", "/v1/keys", {"signed_prekey": {"key": b64(), "signature": b64(64)}, "one_time_prekeys": [{"key_id": 1, "key": b64()}]}, a["access_token"])
        self.e.send(a, b); self.e.send(b, a)
        self.assertEqual(self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": b64()}, a["access_token"])[0], 403)   # zły sekret
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=a["access_token"])[0], 200)
        self.assertEqual(self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": a["secret"]}, a["access_token"])[0], 200)
        aid = a["account_id"]
        for table, col in [("accounts", "id"), ("devices", "account_id"), ("sessions", "account_id")]:
            self.assertEqual(self.e.sql("SELECT COUNT(*) FROM %s WHERE %s=?" % (table, col), aid)[0][0], 0, table)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM messages WHERE sender_account=? OR recipient_account=?", aid, aid)[0][0], 0)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM signed_prekeys WHERE device_id=?", a["device_id"])[0][0], 0)
        self.assertEqual(self.e.sql("SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=?", a["device_id"])[0][0], 0)
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=a["access_token"])[0], 401)
        self.assertEqual(self.e.call("POST", "/v1/auth/login", {"username": "alice", "auth_secret": a["secret"], "device": {"name": "x", "identity_key": b64()}})[0], 401)
        self.assertEqual(self.e.call("GET", "/v1/accounts/alice", token=b["access_token"])[0], 404)


class ProfileAndMigrationTests(Base):
    def test_profile_update_and_public_profile(self):
        a = self.e.register("alice"); b = self.e.register("bob")
        self.assertEqual(self.e.call("PATCH", "/v1/accounts/me", {"display_name": "Ala", "bio": "cześć"}, a["access_token"])[0], 200)
        st, p = self.e.call("GET", "/v1/accounts/alice", token=b["access_token"])
        self.assertEqual((p["display_name"], p["bio"]), ("Ala", "cześć")); self.assertNotIn("account_id", p)
        for bad in [{"display_name": ""}, {"display_name": "x" * 41}, {"bio": "x" * 301}, {}]:
            self.assertEqual(self.e.call("PATCH", "/v1/accounts/me", bad, a["access_token"])[0], 400, bad)

    def test_changing_the_name_does_not_change_the_account_identity(self):
        a = self.e.register("alice")
        self.e.call("PATCH", "/v1/accounts/me", {"display_name": "Zupełnie inna"}, a["access_token"])
        self.assertEqual(self.e.call("GET", "/v1/accounts/me", token=a["access_token"])[1]["account_id"], a["account_id"])

    def test_migrations_are_applied_once_and_are_idempotent(self):
        self.e.stop(); self.e.start(); self.e.stop(); self.e.start()
        self.assertEqual(self.e.sql("SELECT version FROM schema_migrations"), [(1,), (2,), (3,), (4,)])

    def test_health_check_and_unknown_routes(self):
        self.assertEqual(self.e.call("GET", "/healthz")[0], 200)
        self.assertEqual(self.e.call("GET", "/v1/nie-ma")[0], 404)
        self.assertEqual(self.e.call("PUT", "/healthz")[0], 404)


if __name__ == "__main__":
    unittest.main()
