import unittest

from release_notes import extract_notes


class ReleaseNotesTest(unittest.TestCase):
    def test_extracts_only_requested_version_including_subheadings(self):
        text = ('# 更新记录\n\n## 0.3.0 · 2026-11-09\n\n- 新版\n\n'
                '## 0.2.0 · 2026-11-08\n\n### 更新\n\n- 修复来电\n\n'
                '升级说明。\n\n## 0.1.0\n\n- 旧版\n')
        notes = extract_notes(text, '0.2.0')
        self.assertIn('### 更新\n\n- 修复来电', notes)
        self.assertIn('升级说明。', notes)
        self.assertNotIn('新版', notes)
        self.assertNotIn('旧版', notes)

    def test_missing_empty_or_duplicate_version_rejected(self):
        for text in ['## 0.1.0\n\n- 旧版', '## 0.2.0\n\n## 0.1.0\n- 旧版',
                     '## 0.2.0\n- 第一项\n## 0.2.0\n- 重复项']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                extract_notes(text, '0.2.0')

    def test_version_prefix_does_not_match_other_version(self):
        with self.assertRaises(ValueError):
            extract_notes('## 0.2.00\n- 其他版本', '0.2.0')


if __name__ == '__main__':
    unittest.main()
