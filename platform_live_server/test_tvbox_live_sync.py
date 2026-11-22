import hashlib
import json
import os
import tempfile
import time
import unittest
from unittest import mock

import tvbox_live_sync

from tvbox_live_sync import build_output, first_uri, is_encrypted, looks_like_media, looks_like_text, parse_entries, stream_url

SAMPLE = """央视卫视,#genre#
CCTV1,http://a/1.m3u8$LR•IPV4•29『线路1』
CCTV1,http://b/1.m3u8$LR•IPV4•29『线路2』
CCTV2,http://c/2.m3u8$LR•IPV4•29『线路1』
CCTV1,http://a/1.m3u8$LR•IPV4•29『线路9』
香港频道,#genre#
翡翠台,http://d/tvb.m3u8
not a line
CCTV1,rtmp://ignored
"""


class LiveSyncTest(unittest.TestCase):
    def test_stream_url_matches_app_parser(self):
        self.assertEqual(stream_url("http://x/1.m3u8?a=1&$8M$LR•IPV4•29『线路3』"), "http://x/1.m3u8?a=1&")

    def test_parse_keeps_groups_and_http_lines_only(self):
        entries = parse_entries(SAMPLE)
        self.assertEqual([e.group for e in entries if e.group], ["央视卫视", "香港频道"])
        self.assertEqual(len([e for e in entries if e.url]), 5)

    def test_drops_dead_lines_sorts_by_speed_and_empty_groups(self):
        # 值是下载带宽（字节/秒），越大越靠前
        alive = {"http://a/1.m3u8": 1e5, "http://b/1.m3u8": 2e6, "http://c/2.m3u8": 5e5}
        content, kept = build_output(parse_entries(SAMPLE), alive)
        self.assertEqual(kept, 3)
        self.assertEqual(
            content.splitlines(),
            [
                "央视卫视,#genre#",
                "CCTV1,http://b/1.m3u8$LR•IPV4•29『线路2』",
                "CCTV1,http://a/1.m3u8$LR•IPV4•29『线路1』",
                "CCTV2,http://c/2.m3u8$LR•IPV4•29『线路1』",
            ],
        )

    def test_helpers(self):
        self.assertEqual(first_uri("#EXTM3U\n#EXTINF:5,\nseg1.ts\nseg2.ts"), "seg1.ts")
        self.assertIsNone(first_uri("#EXTM3U\n#EXT-X-ENDLIST"))
        ts = (b"\x47" + b"\x00" * 187) * 3
        self.assertTrue(looks_like_media(ts))
        self.assertTrue(looks_like_media(b"\x00\x01" + ts))  # 前面带少量垃圾字节
        self.assertTrue(looks_like_media(b"FLV\x01\x05"))
        self.assertTrue(looks_like_media(b"ID3\x04\x00"))
        self.assertTrue(looks_like_media(b"\x00\x00\x00\x18ftypiso5"))
        # 返回 200 的文字 / 网页都不算
        self.assertFalse(looks_like_media("链接请求出错".encode()))
        self.assertFalse(looks_like_media(b"sign error"))
        self.assertFalse(looks_like_media(b"<!DOCTYPE html><html>"))
        self.assertFalse(looks_like_media(b"Gone"))
        self.assertFalse(looks_like_media(b""))
        self.assertTrue(is_encrypted('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="k"\nseg.ts'))
        self.assertFalse(is_encrypted("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\nseg.ts"))
        self.assertTrue(looks_like_text(b"sign error"))
        self.assertFalse(looks_like_text(bytes(range(128, 256))))


class LivenessTest(unittest.TestCase):
    def test_stale_program_date_time(self):
        old = "#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2025-12-30T11:12:02+0800\n#EXTINF:8.00\na.ts\n"
        now = 1795000000.0  # 2026-11 左右
        self.assertTrue(tvbox_live_sync.playlist_is_stale(old, now))
        fresh = time.strftime("%Y-%m-%dT%H:%M:%S+0000", time.gmtime(now - 60))
        self.assertFalse(tvbox_live_sync.playlist_is_stale(f"#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:{fresh}\n", now))
        self.assertFalse(tvbox_live_sync.playlist_is_stale("#EXTM3U\n#EXTINF:8,\na.ts\n", now))  # 没时间戳不判

    def test_target_duration(self):
        self.assertEqual(tvbox_live_sync.target_duration("#EXTM3U\n#EXT-X-TARGETDURATION:8\n"), 8.0)
        self.assertEqual(tvbox_live_sync.target_duration("#EXTM3U\n"), 6.0)

    def test_splits_hash_joined_urls(self):
        pairs = list(tvbox_live_sync.iter_name_urls("浙江经视,http://a/1.m3u8#https://b/2.m3u8\n"))
        self.assertEqual(pairs, [("浙江经视", "http://a/1.m3u8"), ("浙江经视", "https://b/2.m3u8")])


class ExtraGroupTest(unittest.TestCase):
    def test_picks_matching_channels_merges_aliases_and_orders(self):
        group = tvbox_live_sync.EXTRA_GROUPS[0]
        txt = "浙江频道,#genre#\n浙江经济生活,http://a/1.m3u8\n浙江卫视,http://w/1.m3u8\n湖南经视,http://h/1.m3u8\n嘉兴新闻综合,http://j/1.m3u8$x\n"
        m3u = '#EXTM3U\n#EXTINF:-1 group-title="x",浙江经视\nhttp://b/1.m3u8\n#EXTINF:-1,浙江经济\nhttp://a/1.m3u8\n'
        entries = tvbox_live_sync.pick_extra_entries(group, [txt, m3u])
        self.assertEqual(
            [e.raw for e in entries],
            ["浙江地方,#genre#", "浙江经视,http://a/1.m3u8", "浙江经视,http://b/1.m3u8", "嘉兴新闻综合,http://j/1.m3u8"],
        )
        self.assertEqual(tvbox_live_sync.pick_extra_entries(group, ["CCTV1,http://x/1.m3u8"]), [])


class SkipUnchangedTest(unittest.TestCase):
    def run_main(self, status_hash, *extra):
        with tempfile.TemporaryDirectory() as directory:
            output = os.path.join(directory, "live.txt")
            with open(output, "w", encoding="utf-8") as handle:
                handle.write("old")
            with open(os.path.join(directory, "live.status.json"), "w", encoding="utf-8") as handle:
                json.dump({"sourceHash": status_hash, "aliveUrls": 1}, handle)
            with mock.patch.object(tvbox_live_sync, "download_source", return_value=("src", SAMPLE)), \
                    mock.patch.object(tvbox_live_sync, "download_extras", return_value=([], "")), \
                    mock.patch.object(tvbox_live_sync.Checker, "check", return_value=0.1) as check:
                code = tvbox_live_sync.main(["--output", output, *extra])
                with open(os.path.join(directory, "live.status.json"), encoding="utf-8") as handle:
                    self.last_status = json.load(handle)
                with open(output, encoding="utf-8") as handle:
                    return code, check.call_count, handle.read()

    def test_skips_when_remote_unchanged(self):
        same = hashlib.sha256(SAMPLE.encode("utf-8")).hexdigest()
        self.assertEqual(self.run_main(same), (0, 0, "old"))
        self.assertEqual(self.last_status["lastResult"], "跳过（远程未变化）")
        self.assertEqual(self.last_status["aliveUrls"], 1)  # 保留上次统计
        self.assertIn("checkedAt", self.last_status)

    def test_scans_when_remote_changed_or_forced(self):
        same = hashlib.sha256(SAMPLE.encode("utf-8")).hexdigest()
        for status_hash, extra in (("different", ()), (same, ("--force",))):
            code, checks, content = self.run_main(status_hash, *extra)
            self.assertEqual((code, checks), (0, 4))  # 5 行里有 1 个重复地址
            self.assertIn("CCTV1,http://a/1.m3u8", content)


if __name__ == "__main__":
    unittest.main()
