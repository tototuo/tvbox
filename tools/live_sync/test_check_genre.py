import unittest

from check_genre import parse_groups, raw_url


class CheckGenreTest(unittest.TestCase):
    def test_github_blob_becomes_raw(self):
        self.assertEqual(
            raw_url("https://github.com/o/r/blob/main/%E7%AC%AC.txt"),
            "https://raw.githubusercontent.com/o/r/main/%E7%AC%AC.txt",
        )
        self.assertEqual(raw_url("http://host/a.txt"), "http://host/a.txt")

    def test_txt_groups_dedup_and_strip_metadata(self):
        text = "央视,#genre#\nCCTV1,http://a/1.m3u8$线路1\nCCTV1,http://a/1.m3u8$线路2\n卫视,#genre#\n湖南,http://b/h.m3u8\n"
        self.assertEqual(
            parse_groups(text),
            {"央视": [("CCTV1", "http://a/1.m3u8")], "卫视": [("湖南", "http://b/h.m3u8")]},
        )

    def test_m3u_group_title(self):
        text = '#EXTM3U\n#EXTINF:-1 group-title="少儿",卡通台\nhttp://c/k.m3u8\n#EXTINF:-1,无分组台\nhttp://d/x.m3u8\n'
        self.assertEqual(
            parse_groups(text),
            {"少儿": [("卡通台", "http://c/k.m3u8")], "未分组": [("无分组台", "http://d/x.m3u8")]},
        )


if __name__ == "__main__":
    unittest.main()
