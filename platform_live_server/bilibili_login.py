"""B站扫码登录：电视上显示 B站官方登录二维码，用户用 B站 App 扫码确认后，
把登录 Cookie 保存在服务端本机文件里，供 B站直播解析使用（登录后才能拿到原画 / 蓝光）。

Cookie 只保存在运行本服务的电脑上（权限 600），不会返回给电视端。
"""
from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Callable

import requests

from platform_common import PlatformResolverError

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)
GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
COOKIE_NAMES = ("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")

# poll 接口 data.code 含义
POLL_SUCCESS = 0
POLL_EXPIRED = 86038
POLL_SCANNED = 86090
POLL_WAITING = 86101


class BilibiliLogin:
    def __init__(
        self,
        cookie_path: Path,
        on_cookie_changed: Callable[[str], None],
        timeout_seconds: int = 10,
    ):
        self.cookie_path = cookie_path
        self.on_cookie_changed = on_cookie_changed
        self.timeout_seconds = timeout_seconds
        self._lock = threading.Lock()

    def saved_cookie(self) -> str:
        try:
            return self.cookie_path.read_text(encoding="utf-8").strip()
        except OSError:
            return ""

    def generate(self) -> dict[str, str]:
        data = self._get_data(GENERATE_URL, {})
        url = str(data.get("url") or "")
        key = str(data.get("qrcode_key") or "")
        if not url or not key:
            raise PlatformResolverError("B站未返回登录二维码")
        return {"url": url, "qrcodeKey": key}

    def poll(self, qrcode_key: str) -> dict[str, object]:
        """返回 {"state": waiting|scanned|expired|success, "message": ...}。成功时保存 Cookie。"""
        response = requests.get(
            POLL_URL,
            params={"qrcode_key": qrcode_key},
            headers={"User-Agent": USER_AGENT, "Referer": "https://www.bilibili.com/"},
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        payload = response.json()
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict):
            raise PlatformResolverError("B站扫码状态响应异常")
        code = data.get("code")
        if code == POLL_WAITING:
            return {"state": "waiting", "message": "请用 B站 App 扫码"}
        if code == POLL_SCANNED:
            return {"state": "scanned", "message": "已扫码，请在手机上确认登录"}
        if code == POLL_EXPIRED:
            return {"state": "expired", "message": "二维码已过期，请重新获取"}
        if code != POLL_SUCCESS:
            return {"state": "error", "message": str(data.get("message") or f"扫码失败：{code}")}
        cookie = "; ".join(
            f"{name}={response.cookies.get(name)}" for name in COOKIE_NAMES if response.cookies.get(name)
        )
        if "SESSDATA=" not in cookie:
            raise PlatformResolverError("B站登录成功但没有返回 Cookie")
        self._save(cookie)
        status = self.status()
        return {"state": "success", "message": f"已登录：{status.get('name') or 'B站账号'}"}

    def status(self) -> dict[str, object]:
        cookie = self.saved_cookie()
        if not cookie:
            return {"loggedIn": False, "name": ""}
        try:
            data = self._get_data(NAV_URL, {"Cookie": cookie})
        except (PlatformResolverError, requests.RequestException):
            return {"loggedIn": True, "name": ""}  # 网络问题时不误报“已退出”
        if not data.get("isLogin"):
            return {"loggedIn": False, "name": "", "expired": True}
        return {"loggedIn": True, "name": str(data.get("uname") or "")}

    def logout(self) -> None:
        with self._lock:
            try:
                self.cookie_path.unlink()
            except FileNotFoundError:
                pass
        self.on_cookie_changed("")

    def _save(self, cookie: str) -> None:
        with self._lock:
            temp = self.cookie_path.with_suffix(".tmp")
            fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(cookie + "\n")
            os.replace(temp, self.cookie_path)
        self.on_cookie_changed(cookie)

    def _get_data(self, url: str, extra_headers: dict[str, str]) -> dict:
        response = requests.get(
            url,
            headers={"User-Agent": USER_AGENT, "Referer": "https://www.bilibili.com/", **extra_headers},
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        payload = response.json()
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict):
            raise PlatformResolverError("B站登录接口响应异常")
        return data
