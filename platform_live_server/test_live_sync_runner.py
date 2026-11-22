import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import server


class LiveSyncRunnerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.base = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def test_disabled_without_config(self):
        self.assertIsNone(server.LiveSyncRunner.from_config(self.base / "live_sync.json"))
        config = self.base / "live_sync.json"
        config.write_text(json.dumps({"enabled": False}))
        self.assertIsNone(server.LiveSyncRunner.from_config(config))

    def test_config_values(self):
        config = self.base / "live_sync.json"
        config.write_text(json.dumps({"intervalMinutes": 5, "dataDir": "out", "mirrorDirs": [str(self.base / "m")]}))
        runner = server.LiveSyncRunner.from_config(config)
        self.assertEqual(runner.interval_seconds, 300)
        self.assertEqual(runner.data_dir, self.base / "out")
        self.assertEqual(runner.mirror_dirs, [self.base / "m"])

    def test_only_serves_known_files(self):
        runner = server.LiveSyncRunner(self.base, 900, 0, [])
        self.assertEqual(runner.file_path("live.txt"), self.base / "live.txt")
        self.assertIsNone(runner.file_path("../bilibili_cookie.txt"))
        self.assertIsNone(runner.file_path("sync.log"))

    def test_run_once_mirrors_and_survives_errors(self):
        data, mirror = self.base / "data", self.base / "mirror"
        data.mkdir()
        runner = server.LiveSyncRunner(data, 900, 0, [mirror])

        def fake_main(argv):
            (data / "live.txt").write_text("CCTV1,http://a/1.m3u8\n")
            (data / "live.status.json").write_text("{}")
            return 0

        with mock.patch.object(server.tvbox_live_sync, "main", side_effect=fake_main) as main:
            runner.run_once(force=True)
        main.assert_called_once_with(["--output", str(data / "live.txt"), "--force"])
        self.assertEqual((mirror / "live.txt").read_text(), "CCTV1,http://a/1.m3u8\n")

        with mock.patch.object(server.tvbox_live_sync, "main", side_effect=RuntimeError("网络断了")), \
                mock.patch.object(server.tvbox_live_sync, "log") as log:
            runner.run_once()  # 不抛异常
        self.assertIn("同步失败", log.call_args[0][0])


if __name__ == "__main__":
    unittest.main()
