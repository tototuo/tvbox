import hashlib
import json
import os
import tempfile
import unittest
from unittest import mock

import tvbox_live_sync

from tvbox_live_sync import build_output, first_uri, looks_like_html, parse_entries, stream_url

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
        alive = {"http://a/1.m3u8": 2.0, "http://b/1.m3u8": 0.5, "http://c/2.m3u8": 1.0}
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
        self.assertTrue(looks_like_html(b"<!DOCTYPE html><html>"))
        self.assertFalse(looks_like_html(b"FLV\x01"))


class SkipUnchangedTest(unittest.TestCase):
    def run_main(self, status_hash, *extra):
        with tempfile.TemporaryDirectory() as directory:
            output = os.path.join(directory, "live.txt")
            with open(output, "w", encoding="utf-8") as handle:
                handle.write("old")
            with open(os.path.join(directory, "live.status.json"), "w", encoding="utf-8") as handle:
                json.dump({"sourceHash": status_hash, "aliveUrls": 1}, handle)
            with mock.patch.object(tvbox_live_sync, "download_source", return_value=("src", SAMPLE)), \
                    mock.patch.object(tvbox_live_sync.Checker, "check", return_value=0.1) as check:
                code = tvbox_live_sync.main(["--output", output, *extra])
                with open(output, encoding="utf-8") as handle:
                    return code, check.call_count, handle.read()

    def test_skips_when_remote_unchanged(self):
        same = hashlib.sha256(SAMPLE.encode("utf-8")).hexdigest()
        self.assertEqual(self.run_main(same), (0, 0, "old"))

    def test_scans_when_remote_changed_or_forced(self):
        same = hashlib.sha256(SAMPLE.encode("utf-8")).hexdigest()
        for status_hash, extra in (("different", ()), (same, ("--force",))):
            code, checks, content = self.run_main(status_hash, *extra)
            self.assertEqual((code, checks), (0, 4))  # 5 行里有 1 个重复地址
            self.assertIn("CCTV1,http://a/1.m3u8", content)


if __name__ == "__main__":
    unittest.main()
