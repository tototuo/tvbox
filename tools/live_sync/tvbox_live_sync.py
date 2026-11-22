"""TVBox 电视直播源同步：拉远程 txt 直播源 → 逐条检测 → 去掉失效地址 → 按速度排序后输出。

只用标准库，在 Mac mini 上由 crontab 定时运行：

    tvbox_live_sync.py --output ~/Software/AriaNg/tvbox/live.txt

输出格式与远程源一致（「分组,#genre#」+「频道,地址$线路信息」），电视端解析逻辑不变。
检测规则：
- m3u8：读播放列表；主列表就跟进第一个子列表；再下载第一个分片的开头，分片拿不到算失效
  （实测很多线路列表能打开、分片 404）
- 其他（flv / ts 直连等）：200 且开头是真正的媒体数据才算可用
- 直播必须在更新：播放列表的时间戳（PROGRAM-DATE-TIME）不能是半天以前的；隔一个分片时长再拉一次，
  内容必须变化；带 ENDLIST 的（会播完）也不要。实测有 CDN 把一年前的 3 个分片缓存着一直返回，
  电视上就是一小段反复重播
- 分片和直连流的开头必须像视频（TS / FLV / MP4 / ID3），「sign error」「链接请求出错」
  这类返回 200 的文字一律算失效
保护：本次可用数比上次少一半以上（多半是网络问题）时保留上次结果，不覆盖。
排序：同一频道按实测下载带宽从高到低排（m3u8 下载第一个完整分片，直连流读几秒），
  排第一的就是 App 里的线路 1。
补充：EXTRA_GROUPS 里的源只挑指定频道（如浙江地方台），合并别名后放进单独分组，一起检测排序；
  补充源拉不到不影响主源。
省流：远程内容（sha256）与上次扫描时相同且输出文件还在，就跳过这一轮；--force 强制重扫。
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import ssl
import subprocess
import statistics
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

DEFAULT_SOURCES = [
    "http://20.205.10.127:8787/tvbox/result.txt",
    "https://tv.iill.top/m3u/Gather",
]
@dataclass(frozen=True)
class ExtraGroup:
    """从补充源里挑出名字匹配 include 的频道，放进 group 分组。"""

    group: str
    sources: tuple[str, ...]
    include: re.Pattern
    exclude: re.Pattern
    aliases: dict  # 同一个台的不同叫法 → 统一名字
    order: tuple[str, ...]  # 分组内频道排序：先按这里的顺序，其余按名字
    exclude_url: re.Pattern = re.compile(r"qingting\.fm")  # 蜻蜓 FM 是电台，只有声音


EXTRA_GROUPS = [
    ExtraGroup(
        group="浙江地方",
        sources=(
            "https://cdn.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.txt",
            "https://live.zbds.top/tv/iptv4.txt",
        ),
        include=re.compile(r"浙江|钱江|之江|杭州|宁波|温州|绍兴|嘉兴|湖州|金华|台州|丽水|衢州|舟山|义乌|平湖|遂昌"),
        exclude=re.compile(r"卫视|少儿"),  # 浙江卫视、浙江少儿主源里已经有
        aliases={
            "浙江经济": "浙江经视", "浙江经济生活": "浙江经视",
            "浙江钱江": "钱江都市", "浙江钱江频道": "钱江都市", "浙江钱江都市": "钱江都市",
            "浙江教科": "浙江教科影视",
            "浙江民生": "浙江民生休闲", "浙江休闲台": "浙江民生休闲",
            "浙江公共新闻": "浙江新闻",
            "绍兴电视台公共": "绍兴公共",
            "浙江遂昌": "遂昌综合",
        },
        order=("浙江经视", "钱江都市", "浙江教科影视", "浙江民生休闲", "浙江新闻", "之江纪录",
               "浙江国际", "浙江数码时代", "浙江教育", "浙江留学", "浙江生活"),
    ),
]
EXTRA_SOURCE_TIMEOUT = 60  # live.zbds.top 很慢
USER_AGENT = "Mozilla/5.0 (Linux; Android 9; BRAVIA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
PROBE_BYTES = 2048
PLAYLIST_MAX_BYTES = 512 * 1024
SPEED_MAX_BYTES = 4 * 1024 * 1024  # 测速最多下载 4MB（一个高清分片一般 1~4MB）
SPEED_MAX_SECONDS = 6.0  # 或最多 6 秒，慢线路不拖长扫描
MIN_KEEP_RATIO = 0.5


@dataclass
class Entry:
    """直播源里的一行：分组标题行或频道线路行，raw 原样保留。"""

    raw: str
    group: str | None = None  # 分组行
    url: str | None = None  # 线路行


def stream_url(source_part: str) -> str:
    """与 App 的 LiveParser 一致：'$' 之前是地址。"""
    return source_part.split("$", 1)[0].strip()


def parse_entries(text: str) -> list[Entry]:
    entries: list[Entry] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or "," not in line:
            continue
        name, source = line.split(",", 1)
        if not name.strip() or not source.strip():
            continue
        if source.strip().lower() == "#genre#":
            entries.append(Entry(raw=line, group=name.strip()))
            continue
        url = stream_url(source)
        if url.startswith(("http://", "https://")):
            entries.append(Entry(raw=line, url=url))
    return entries


def build_output(entries: list[Entry], alive: dict[str, float]) -> tuple[str, int]:
    """alive：地址 → 下载带宽（字节/秒）。去掉失效和重复线路；同一频道内按带宽从高到低排；
    没有线路的分组整个去掉。"""
    out: list[str] = []
    kept = 0
    group_line: str | None = None
    block: list[tuple[str, str]] = []  # (频道名, 原始行)，保持频道首次出现的顺序

    def flush() -> None:
        nonlocal kept
        if group_line is None or not block:
            return
        order: list[str] = []
        by_channel: dict[str, list[str]] = {}
        for channel, raw in block:
            if channel not in by_channel:
                order.append(channel)
                by_channel[channel] = []
            url = stream_url(raw.split(",", 1)[1])
            # 同一频道里重复的地址只留第一条（App 也会按地址去重）
            if all(stream_url(existing.split(",", 1)[1]) != url for existing in by_channel[channel]):
                by_channel[channel].append(raw)
        out.append(group_line)
        for channel in order:
            lines = sorted(by_channel[channel], key=lambda raw: -alive[stream_url(raw.split(",", 1)[1])])
            out.extend(lines)
            kept += len(lines)

    for entry in entries:
        if entry.group is not None:
            flush()
            group_line, block = entry.raw, []
        elif entry.url in alive and group_line is not None:
            block.append((entry.raw.split(",", 1)[0].strip(), entry.raw))
    flush()
    return "\n".join(out) + ("\n" if out else ""), kept


class Checker:
    def __init__(self, timeout: float):
        self.timeout = timeout
        self.ssl_context = ssl.create_default_context()

    def fetch(self, url: str, limit: int | None = None) -> tuple[bytes, str]:
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
        with urllib.request.urlopen(request, timeout=self.timeout, context=self.ssl_context) as response:
            if response.status >= 400:
                raise OSError(f"HTTP {response.status}")
            data = response.read(limit) if limit else response.read(512 * 1024)
            return data, response.geturl()

    def download(self, url: str) -> tuple[bytes, int, float, str]:
        """边下边计时，到 SPEED_MAX_BYTES / SPEED_MAX_SECONDS / 结束为止。
        返回（前 PLAYLIST_MAX_BYTES 字节，总字节数，耗时含建连，最终地址）。"""
        started = time.monotonic()
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
        with urllib.request.urlopen(request, timeout=self.timeout, context=self.ssl_context) as response:
            if response.status >= 400:
                raise OSError(f"HTTP {response.status}")
            kept = bytearray()
            total = 0
            while total < SPEED_MAX_BYTES and time.monotonic() - started < SPEED_MAX_SECONDS:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if len(kept) < PLAYLIST_MAX_BYTES:
                    kept += chunk[: PLAYLIST_MAX_BYTES - len(kept)]
            return bytes(kept), total, max(time.monotonic() - started, 1e-3), response.geturl()

    def check(self, url: str) -> float | None:
        """可用返回下载带宽（字节/秒），失效返回 None。"""
        try:
            return self._check(url)
        except Exception:  # noqa: BLE001 - 任何网络/解析错误都算失效
            return None

    def _check(self, url: str) -> float | None:
        data, total, elapsed, final_url = self.download(url)
        if not data[:PROBE_BYTES].lstrip().startswith(b"#EXTM3U"):
            return total / elapsed if looks_like_media(data) else None  # flv / ts 直连
        playlist = data.decode("utf-8", "replace")
        playlist_url = final_url
        for _ in range(3):  # 主列表 → 子列表，最多跟两层
            uri = first_uri(playlist)
            if uri is None:
                return None
            target = urllib.parse.urljoin(final_url, uri)
            if "#EXT-X-STREAM-INF" not in playlist:
                if "#EXT-X-ENDLIST" in playlist or playlist_is_stale(playlist, time.time()):
                    return None
                fetched_at = time.monotonic()
                segment, total, elapsed, _ = self.download(target)
                if is_encrypted(playlist):  # 加密分片是随机字节，只要求不是文字 / 网页
                    ok = bool(segment) and not looks_like_text(segment)
                else:
                    ok = looks_like_media(segment)
                if not ok:
                    return None
                # 等一个分片时长再拉一次，直播的列表一定会变；不变就是死缓存
                wait = min(target_duration(playlist), LIVE_UPDATE_WAIT_MAX) + 1 - (time.monotonic() - fetched_at)
                if wait > 0:
                    time.sleep(wait)
                again, _ = self.fetch(playlist_url, PLAYLIST_MAX_BYTES)
                if again.decode("utf-8", "replace").strip() == playlist.strip():
                    return None
                return total / elapsed
            sub, final_url = self.fetch(target, PLAYLIST_MAX_BYTES)
            playlist_url = final_url
            playlist = sub.decode("utf-8", "replace")
            if not playlist.lstrip().startswith("#EXTM3U"):
                return None
        return None


TS_PACKET = 188
LIVE_UPDATE_WAIT_MAX = 10.0  # 等列表更新最多等这么久（TARGETDURATION 一般 2~10 秒）
STALE_PROGRAM_TIME_SECONDS = 12 * 3600  # 时间戳早于这么久算死缓存；放宽到半天，容忍服务器时区写错


def target_duration(playlist: str) -> float:
    for line in playlist.splitlines():
        if line.startswith("#EXT-X-TARGETDURATION:"):
            try:
                return float(line.split(":", 1)[1])
            except ValueError:
                break
    return 6.0


def playlist_is_stale(playlist: str, now: float) -> bool:
    """最后一个 PROGRAM-DATE-TIME 加上之后的分片时长，比现在早半天以上就是死缓存。没有时间戳返回 False。"""
    stamp: float | None = None
    for line in playlist.splitlines():
        if line.startswith("#EXT-X-PROGRAM-DATE-TIME:"):
            value = line.split(":", 1)[1].strip()
            value = re.sub(r"([+-]\d{2})(\d{2})$", r"\1:\2", value.replace("Z", "+00:00"))
            try:
                stamp = datetime.datetime.fromisoformat(value).timestamp()
            except ValueError:
                stamp = None
        elif line.startswith("#EXTINF:") and stamp is not None:
            try:
                stamp += float(line.split(":", 1)[1].split(",", 1)[0])
            except ValueError:
                pass
    return stamp is not None and now - stamp > STALE_PROGRAM_TIME_SECONDS


def is_encrypted(playlist: str) -> bool:
    return any(
        line.startswith("#EXT-X-KEY") and "METHOD=NONE" not in line.upper()
        for line in playlist.splitlines()
    )


def looks_like_text(data: bytes) -> bool:
    try:
        data[:256].decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def looks_like_media(data: bytes) -> bool:
    """开头是 TS / FLV / MP4 / ID3 / ADTS 等媒体数据。服务器声明什么类型不重要，只看内容。"""
    if not data:
        return False
    if data.startswith((b"FLV", b"ID3")) or data[4:8] in (b"ftyp", b"styp", b"moof", b"sidx"):
        return True
    if data[:2] in (b"\xff\xf1", b"\xff\xf9") or data.startswith(b"\x00\x00\x01\xba"):
        return True
    # TS：0x47 同步字节每 188 字节出现一次；允许前面有少量垃圾字节
    for offset in range(min(len(data), TS_PACKET)):
        if data[offset] != 0x47:
            continue
        follow = offset + TS_PACKET
        if follow < len(data) and data[follow] == 0x47:
            return True
    return False


def first_uri(playlist: str) -> str | None:
    for line in playlist.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            return line
    return None


def download_source(sources: list[str], timeout: float) -> tuple[str, str]:
    checker = Checker(timeout)
    last_error: Exception | None = None
    for source in sources:
        try:
            data, _ = checker.fetch(source)
            text = data.decode("utf-8-sig", "replace")
            if parse_entries(text):
                return source, text
            last_error = ValueError("没有可用线路")
        except Exception as error:  # noqa: BLE001
            last_error = error
    raise RuntimeError(f"所有直播源都拉取失败：{last_error}")


def iter_name_urls(text: str):
    """同时支持 txt（名字,地址）和 m3u（#EXTINF ... ,名字 + 下一行地址）。"""
    name = None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            name = line.rsplit(",", 1)[-1].strip()
        elif line.startswith(("http://", "https://")) and name:
            yield name, line
            name = None
        elif "," in line and not line.startswith("#"):
            channel, source = line.split(",", 1)
            # 有的源把多个地址用 # 拼在一行
            for url in stream_url(source).split("#"):
                url = url.strip()
                if url.startswith(("http://", "https://")):
                    yield channel.strip(), url


def pick_extra_entries(group: ExtraGroup, texts: list[str]) -> list[Entry]:
    """从补充源文本里挑出该分组的频道，返回「分组行 + 线路行」。没有匹配返回空。"""
    picked: dict[str, list[str]] = {}
    seen: set[str] = set()
    for text in texts:
        for name, url in iter_name_urls(text):
            if (
                not group.include.search(name)
                or group.exclude.search(name)
                or group.exclude_url.search(url)
                or url in seen
            ):
                continue
            seen.add(url)
            picked.setdefault(group.aliases.get(name, name), []).append(url)
    if not picked:
        return []
    rank = {name: index for index, name in enumerate(group.order)}
    names = sorted(picked, key=lambda name: (rank.get(name, len(rank)), name))
    entries = [Entry(raw=f"{group.group},#genre#", group=group.group)]
    for name in names:
        entries.extend(Entry(raw=f"{name},{url}", url=url) for url in picked[name])
    return entries


def download_extras(cache_dir: str) -> tuple[list[Entry], str]:
    """拉所有补充源，返回（条目，用于判断变化的原文）。
    每个源成功拉到就存一份到 cache_dir；拉不到（live.zbds.top 经常超时）用上次的，
    避免一会儿有一会儿没有、来回触发重扫。"""
    checker = Checker(EXTRA_SOURCE_TIMEOUT)
    entries: list[Entry] = []
    raw_texts: list[str] = []
    os.makedirs(cache_dir, exist_ok=True)
    for group in EXTRA_GROUPS:
        texts = []
        for source in group.sources:
            cache = os.path.join(cache_dir, hashlib.sha1(source.encode()).hexdigest()[:16] + ".txt")
            try:
                try:
                    data, _ = checker.fetch(source, 4 * 1024 * 1024)
                except Exception:  # noqa: BLE001
                    # Python 偶尔卡在不通的地址上（握手超时），curl 会同时试多个地址
                    data = subprocess.run(
                        ["curl", "-sfL", "-m", str(EXTRA_SOURCE_TIMEOUT), "-A", USER_AGENT, source],
                        capture_output=True, check=True, timeout=EXTRA_SOURCE_TIMEOUT + 5,
                    ).stdout
                text = data.decode("utf-8-sig", "replace")
                if not any(True for _ in iter_name_urls(text)):
                    raise ValueError("内容里没有线路")
                write_atomic(cache, text)
            except Exception as error:  # noqa: BLE001
                if not os.path.exists(cache):
                    print(f"补充源拉取失败且没有缓存（跳过）：{source}：{error}", flush=True)
                    continue
                print(f"补充源拉取失败，用上次缓存：{source}：{error}", flush=True)
                with open(cache, encoding="utf-8") as handle:
                    text = handle.read()
            texts.append(text)
        group_entries = pick_extra_entries(group, texts)
        entries.extend(group_entries)
        # 只拿挑出来的行判断变化，补充源里别的频道变了不触发重扫
        raw_texts.extend(entry.raw for entry in group_entries)
    return entries, "\n".join(raw_texts)


def write_atomic(path: str, content: str) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    fd, temp = tempfile.mkstemp(dir=directory, prefix=".tmp-")
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(content)
    os.chmod(temp, 0o644)
    os.replace(temp, path)


def read_status(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def save_status(path: str, previous: dict, **fields) -> None:
    """只更新检查时间和结果，保留上次扫描的统计（Homepage 卡片据此判断脚本还在跑）。"""
    write_atomic(path, json.dumps({**previous, **fields}, ensure_ascii=False, indent=2) + "\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", required=True, help="输出的 txt 直播源路径")
    parser.add_argument("--source", action="append", help="远程直播源，可多次指定；按顺序尝试")
    parser.add_argument("--workers", type=int, default=48)
    parser.add_argument("--timeout", type=float, default=8)
    parser.add_argument("--force", action="store_true", help="远程没变化也重新扫描")
    args = parser.parse_args(argv)

    status_path = os.path.splitext(args.output)[0] + ".status.json"
    previous = read_status(status_path)
    started = time.time()
    source, text = download_source(args.source or DEFAULT_SOURCES, args.timeout)
    extra_entries, extra_text = download_extras(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".extra-cache"))
    source_hash = hashlib.sha256((text + extra_text).encode("utf-8")).hexdigest()
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    unchanged = previous.get("sourceHash") == source_hash and os.path.exists(args.output)
    if unchanged and not args.force:
        save_status(status_path, previous, checkedAt=stamp, lastResult="跳过（远程未变化）")
        print(f"{stamp} 远程直播源没有变化，跳过本轮扫描", flush=True)
        return 0
    entries = parse_entries(text) + extra_entries
    urls = list(dict.fromkeys(entry.url for entry in entries if entry.url))
    checker = Checker(args.timeout)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = dict(zip(urls, pool.map(checker.check, urls)))
    alive = {url: speed for url, speed in results.items() if speed is not None}
    content, kept_lines = build_output(entries, alive)
    channels = len({entry.raw.split(",", 1)[0].strip() for entry in entries if entry.url in alive})

    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    previous_alive = int(previous.get("aliveUrls") or 0)
    if not alive or (previous_alive and len(alive) < previous_alive * MIN_KEEP_RATIO):
        save_status(status_path, previous, checkedAt=stamp, lastResult=f"保留上次（本次只有 {len(alive)} 个可用）")
        print(f"{stamp} 可用 {len(alive)}/{len(urls)}，比上次 {previous_alive} 少太多，保留上次结果", flush=True)
        return 1

    write_atomic(args.output, content)
    status = {
        "updatedAt": stamp,
        "checkedAt": stamp,
        "lastResult": "已更新",
        "source": source,
        "sourceHash": source_hash,
        "totalUrls": len(urls),
        "aliveUrls": len(alive),
        "keptLines": kept_lines,
        "channels": channels,
        "seconds": round(time.time() - started, 1),
        "medianMbps": round(statistics.median(alive.values()) * 8 / 1e6, 1),
    }
    write_atomic(status_path, json.dumps(status, ensure_ascii=False, indent=2) + "\n")
    print(f"{stamp} 来源 {source}：可用 {len(alive)}/{len(urls)} 个地址，{channels} 个频道，带宽中位数 {status['medianMbps']} Mbps，用时 {status['seconds']}s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
