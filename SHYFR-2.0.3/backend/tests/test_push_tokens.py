"""Etap 41: token push urządzenia. Serwer trzyma tylko token i usuwa go przy wylogowaniu, odwołaniu urządzenia i usunięciu konta."""
import unittest
from tests.test_server import Base

TOKEN = "fcm:" + "A" * 40


def tokens(e): return e.sql("SELECT device_id, provider, token FROM push_tokens")


class PushTokenTests(Base):
    def put(self, who, tok=TOKEN, prov="fcm"):
        return self.e.call("PUT", "/v1/devices/me/push-token", {"provider": prov, "token": tok}, who["access_token"])

    def test_register_and_update_keeps_one_row_per_device(self):
        a = self.e.register("alice")
        self.assertEqual(self.put(a)[0], 200)
        self.assertEqual(self.put(a, TOKEN + "2")[0], 200)
        rows = tokens(self.e)
        self.assertEqual(len(rows), 1); self.assertEqual(rows[0][2], TOKEN + "2")

    def test_requires_authentication(self):
        self.assertEqual(self.e.call("PUT", "/v1/devices/me/push-token", {"provider": "fcm", "token": TOKEN})[0], 401)

    def test_rejects_unknown_provider_and_malformed_token(self):
        a = self.e.register("alice")
        self.assertEqual(self.put(a, prov="apns")[0], 400)
        self.assertEqual(self.put(a, tok="short")[0], 400)
        self.assertEqual(self.put(a, tok="x" * 30 + "<script>")[0], 400)
        self.assertEqual(tokens(self.e), [])

    def test_delete_removes_token(self):
        a = self.e.register("alice"); self.put(a)
        self.assertEqual(self.e.call("DELETE", "/v1/devices/me/push-token", None, a["access_token"])[0], 200)
        self.assertEqual(tokens(self.e), [])

    def test_logout_removes_token(self):
        a = self.e.register("alice"); self.put(a)
        self.assertEqual(self.e.call("POST", "/v1/auth/logout", {}, a["access_token"])[0], 200)
        self.assertEqual(tokens(self.e), [])

    def test_account_deletion_removes_token(self):
        a = self.e.register("alice"); self.put(a)
        st, _ = self.e.call("DELETE", "/v1/accounts/me", {"auth_secret": a["secret"]}, a["access_token"])
        self.assertEqual(st, 200); self.assertEqual(tokens(self.e), [])

    def test_other_account_cannot_touch_my_token(self):
        a = self.e.register("alice"); b = self.e.register("bob"); self.put(a)
        self.e.call("DELETE", "/v1/devices/me/push-token", None, b["access_token"])
        self.assertEqual(len(tokens(self.e)), 1)

    def test_schema_migration_4_applied(self):
        st, r = self.e.call("GET", "/readyz")
        self.assertEqual(st, 200); self.assertGreaterEqual(r["schema_version"], 4)


if __name__ == "__main__":
    unittest.main()
