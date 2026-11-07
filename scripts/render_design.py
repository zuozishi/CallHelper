"""Render the offline design source and verify its demo interactions."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/design/v1'
IDS = ['home', 'incoming', 'directory', 'sync', 'appearance', 'setup']

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', default='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe')
    args = parser.parse_args()
    (OUT / 'screens').mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=args.browser, headless=True)
        page = browser.new_page(viewport={'width': 1510, 'height': 2400}, device_scale_factor=2)
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto((OUT / 'index.html').as_uri())
        page.evaluate('document.fonts.ready')
        page.locator('.board').screenshot(path=str(OUT / 'overview.png'))
        for name in IDS:
            page.locator('#' + name).screenshot(path=str(OUT / 'screens' / (name + '.png')))
        # Exercise independent user-visible states before exporting supplements.
        page.locator('#search').fill('陈亦宁')
        assert page.locator('#result-count').inner_text() == '1 位'
        assert '陈亦宁' in page.locator('#people-list').inner_text()
        page.locator('#search').fill('13800000003')
        assert '周予安' in page.locator('#people-list').inner_text()
        page.locator('#search').fill('不存在的人员')
        assert '未找到相关人员' in page.locator('#people-list').inner_text()
        page.locator('#directory').screenshot(path=str(OUT / 'screens/directory-empty.png'))
        page.locator('#search').fill('')
        page.locator('[data-filter="星河科技"]').click()
        assert page.locator('#result-count').inner_text() == '2 位'
        page.locator('[data-filter="all"]').click()
        page.locator('#hide-float').click()
        assert not page.locator('#call-float').is_visible()
        page.locator('#show-float').click()
        assert page.locator('#call-float').is_visible()
        page.locator('[data-theme="light"]').click()
        assert 'light-preview' in page.locator('#mini-preview').get_attribute('class')
        page.locator('#department-switch').click()
        assert not page.locator('#mini-preview .department').is_visible()
        page.locator('#department-switch').click()
        page.locator('#simulate-fail').click()
        assert '失败' in page.locator('#sync-title').inner_text()
        page.locator('#sync').screenshot(path=str(OUT / 'screens/sync-failed.png'))
        page.locator('#sync-button').click()
        page.wait_for_function("document.querySelector('#sync-title').textContent === '已同步'")
        page.locator('#overlay-switch').click()
        assert page.locator('#home-state').inner_text() == '已暂停'
        page.locator('#overlay-switch').click()
        page.set_viewport_size({'width': 430, 'height': 950})
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        assert not errors, errors
        report = {'status': 'passed', 'browser': browser.version, 'screens': IDS,
                  'checks': ['中文姓名搜索', '号码搜索', '空结果', '单位筛选', '关闭及恢复悬浮窗',
                             '浅色预览', '部门开关', '同步失败', '重试成功', '识别暂停', '430px 预览无横向溢出'],
                  'page_errors': errors, 'scope': 'HTML 设计原型；不代表 Android 功能或设备验收'}
        (OUT / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False))
        browser.close()

if __name__ == '__main__':
    main()
