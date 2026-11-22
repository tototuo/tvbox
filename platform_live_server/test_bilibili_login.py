import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import requests

from bilibili_login import BilibiliLogin, sessdata_expires_at


def fake_response(payload, cookies=None):
    response = mock.Mock()
    response.json.return_value = payload
    response.raise_for_status.return_value = None
    jar = requests.cookies.RequestsCookieJar()
    for name, value in (cookies or {}).items():
        jar.set(name, value)
    response.cookies = jar
    return response


class BilibiliLoginTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "bilibili_cookie.txt"
        self.changes = []
        self.login = BilibiliLogin(self.path, self.changes.append)

    def tearDown(self):
        self.directory.cleanup()

    def test_waiting_and_scanned_states(self):
        with mock.patch("bilibili_login.requests.get", return_value=fake_response({"data": {"code": 86101}})):
            self.assertEqual(self.login.poll("k")["state"], "waiting")
        with mock.patch("bilibili_login.requests.get", return_value=fake_response({"data": {"code": 86090}})):
            self.assertEqual(self.login.poll("k")["state"], "scanned")
        with mock.patch("bilibili_login.requests.get", return_value=fake_response({"data": {"code": 86038}})):
            self.assertEqual(self.login.poll("k")["state"], "expired")
        self.assertFalse(self.path.exists())

    def test_success_saves_cookie_privately_and_notifies(self):
        responses = [
            fake_response({"data": {"code": 0}}, {"SESSDATA": "s%2C1", "bili_jct": "j", "DedeUserID": "42"}),
            fake_response({"data": {"isLogin": True, "uname": "小明"}}),
        ]
        with mock.patch("bilibili_login.requests.get", side_effect=responses):
            result = self.login.poll("k")
        self.assertEqual(result, {"state": "success", "message": "已登录：小明"})
        self.assertEqual(self.login.saved_cookie(), "SESSDATA=s%2C1; bili_jct=j; DedeUserID=42")
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o600)
        self.assertEqual(self.changes, ["SESSDATA=s%2C1; bili_jct=j; DedeUserID=42"])

    def test_logout_removes_cookie(self):
        self.path.write_text("SESSDATA=x")
        self.login.logout()
        self.assertFalse(self.path.exists())
        self.assertEqual(self.changes, [""])
        self.assertEqual(self.login.status(), {"loggedIn": False, "name": "", "verified": True, "expiresAt": ""})

    def test_sessdata_expiry(self):
        import time
        expected = time.strftime("%Y-%m-%d", time.localtime(1795000000))
        self.assertEqual(sessdata_expires_at("DedeUserID=1; SESSDATA=abc%2C1795000000%2Cxy*12"), expected)
        self.assertEqual(sessdata_expires_at("SESSDATA=broken"), "")

    def test_status_unverified_on_network_error(self):
        self.path.write_text("SESSDATA=abc%2C1795000000%2Cx")
        with mock.patch("bilibili_login.requests.get", side_effect=requests.ConnectionError("down")):
            status = self.login.status()
        self.assertTrue(status["loggedIn"])
        self.assertFalse(status["verified"])


if __name__ == "__main__":
    unittest.main()
