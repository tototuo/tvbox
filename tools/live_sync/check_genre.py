"""检测直播源里某个分组下所有地址的连通性（通用工具，检测逻辑复用 tvbox_live_sync.Checker）。

用法:
    python check_genre.py <直播源地址或本地文件> <分组名> [--workers 32] [--timeout 8]

- 支持 TVBox txt（「分组,#genre#」）和 m3u（#EXTINF 的 group-title）两种格式
- GitHub 的 blob 页面地址会自动换成 raw 地址
- 输出每个地址的结果（OK / FAIL、耗时、频道名），最后给出汇总；分组名找不到时列出所有分组
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from tvbox_live_sync import USER_AGENT, Checker, stream_url

GITHUB_HOSTS = ("github.com", "raw.githubusercontent.com")
DEFAULT_GITHUB_PROXY = "http://proxy-service-london.epicgames.net:3128"
GITHUB_BLOB = re.compile(r"^https://github\.com/([^/]+)/([^/]+)/blob/(.+)$")


def raw_url(source: str) -> str:
    match = GITHUB_BLOB.match(source)
    if match:
        owner, repo, rest = match.groups()
        return f"https://raw.githubusercontent.com/{owner}/{repo}/{rest}"
    return source


def parse_groups(text: str) -> dict[str, list[tuple[str, str]]]:
    """分组名 -> [(频道名, 地址)]，保持出现顺序，同一分组内地址去重。"""
    groups: dict[str, list[tuple[str, str]]] = {}
    lines = [line.strip() for line in text.splitlines()]
    if any(line.upper().startswith("#EXTINF") for line in lines):
        pending: tuple[str, str] | None = None
        for line in lines:
            if line.upper().startswith("#EXTINF"):
                group = re.search(r'group-title="([^"]*)"', line)
                pending = ((group.group(1) if group else "未分组").strip(), line.rsplit(",", 1)[-1].strip())
            elif line.startswith(("http://", "https://")) and pending:
                groups.setdefault(pending[0], []).append((pending[1], line))
                pending = None
    else:
        current: str | None = None
        for line in lines:
            if "," not in line:
                continue
            name, source = (part.strip() for part in line.split(",", 1))
            if source.lower() == "#genre#":
                current = name
                groups.setdefault(current, [])
            elif current is not None and source.startswith(("http://", "https://")):
                groups[current].append((name, stream_url(source)))
    return {
        name: list({url: (channel, url) for channel, url in reversed(items)}.values())[::-1]
        for name, items in groups.items()
    }


def load(source: str, timeout: float, proxy: str | None = None) -> str:
    """读取直播源文件；proxy 只用于这一步下载，检测流地址时不走代理。"""
    if os.path.exists(source):
        with open(source, encoding="utf-8-sig") as handle:
            return handle.read()
    handlers = [urllib.request.ProxyHandler({"http": proxy, "https": proxy} if proxy else {})]
    opener = urllib.request.build_opener(*handlers)
    request = urllib.request.Request(raw_url(source), headers={"User-Agent": USER_AGENT})
    with opener.open(request, timeout=timeout) as response:
        return response.read(8 * 1024 * 1024).decode("utf-8-sig", "replace")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source", help="直播源地址（http/https，GitHub blob 地址也行）或本地文件")
    parser.add_argument("genre", help="要检测的分组名（完全匹配）")
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--timeout", type=float, default=8, help="检测单个地址的超时秒数")
    parser.add_argument(
        "--proxy",
        help=f"下载 GitHub 上的直播源文件用的代理（默认 {DEFAULT_GITHUB_PROXY}，填 none 则直连）；检测地址永远直连",
    )
    parser.add_argument("--load-timeout", type=float, default=30, help="下载直播源文件的超时秒数")
    args = parser.parse_args(argv)

    # 检测流地址永远走本地网络：忽略 http_proxy/https_proxy 等环境变量
    urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
    proxy = args.proxy
    if proxy is None:
        host = urllib.parse.urlsplit(raw_url(args.source)).hostname or ""
        proxy = DEFAULT_GITHUB_PROXY if host in GITHUB_HOSTS else None
    elif proxy.lower() == "none":
        proxy = None
    try:
        text = load(args.source, args.load_timeout, proxy)
    except Exception as error:  # noqa: BLE001
        print(f"下载直播源失败：{error!r}\n网络慢可以加 --proxy 或调大 --load-timeout", file=sys.stderr)
        return 1
    groups = parse_groups(text)
    items = groups.get(args.genre)
    if not items:
        print(f"找不到分组「{args.genre}」。现有分组：")
        for name, entries in groups.items():
            print(f"  {name}（{len(entries)} 个地址）")
        return 2

    checker = Checker(args.timeout)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda item: checker.check(item[1]), items))
    ok = 0
    for (channel, url), seconds in zip(items, results):
        if seconds is None:
            print(f"FAIL  {'-':>6}  {channel}  {url}")
        else:
            ok += 1
            print(f"OK    {seconds:5.1f}s  {channel}  {url}")
    print(f"\n分组「{args.genre}」：{ok}/{len(items)} 个地址可用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
