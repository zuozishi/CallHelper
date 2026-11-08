"""Export the organization-browser design; checks only the HTML prototype."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/design/directory-hierarchy-v1'

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless=True)
    page = browser.new_page(viewport={'width': 1400, 'height': 1200}, device_scale_factor=2)
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.goto((OUT / 'index.html').as_uri())
    page.evaluate('document.fonts.ready')
    page.locator('.board').screenshot(path=str(OUT / 'overview.png'))
    for i, name in enumerate(['units', 'departments', 'people']):
        page.locator(f'#screen-{i}').screenshot(path=str(OUT / f'{name}.png'))
    screen = page.locator('#screen-0')
    screen.locator('[data-go="company"]').click()
    screen.locator('[data-go="rd"]').click()
    screen.locator('[data-go="mobile"]').click()
    assert screen.locator('.person').count() == 6
    screen.locator('.up').click()
    assert '质量保障组' in screen.locator('.body').inner_text()
    screen.locator('.breadcrumb [data-go="root"]').click()
    assert screen.locator('.folder').count() == 4
    screen.locator('input').fill('沈星遥')
    assert screen.locator('.person').count() == 1
    assert '移动开发组' in screen.locator('.body').inner_text()
    screen.locator('input').fill('138 0000 0005')
    assert '沈星遥' in screen.locator('.body').inner_text()
    screen.locator('input').fill('不存在的人员')
    assert '未找到相关人员' in screen.locator('.body').inner_text()
    screen.locator('input').fill('')
    assert screen.locator('.folder').count() == 4
    person_screen = page.locator('#screen-2')
    person_screen.locator('.person').first.click()
    assert person_screen.locator('.drawer').is_visible()
    person_screen.get_by_role('button', name='关闭详情').click()
    assert not person_screen.locator('.drawer').is_visible()
    page.set_viewport_size({'width': 390, 'height': 950})
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
    assert not errors, errors
    result = {'status': 'passed', 'checks': ['逐层进入组织', '返回上一级', '面包屑回跳', '全库姓名搜索', '号码搜索', '空结果', '清空恢复目录', '人员详情打开与关闭', '390px 无横向溢出'], 'scope': '离线 HTML 设计原型，不代表 Android 实现或性能验收', 'errors': errors}
    (OUT / 'validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    browser.close()
