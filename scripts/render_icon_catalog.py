"""Render a nonprivate icon catalog directly from the Android vector resources."""
from html import escape
from pathlib import Path
import xml.etree.ElementTree as ET
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/design/icons-v2'
ANDROID = '{http://schemas.android.com/apk/res/android}'
SYMBOLS = {
    'home': '首页', 'people': '人员库', 'sync': '同步', 'settings': '设置',
    'search': '搜索', 'preview': '悬浮窗预览', 'phone': '拨号', 'folder': '组织',
    'shield': '来电识别', 'check': '已授权', 'close': '关闭', 'arrow': '进入',
    'back': '返回', 'system': '跟随系统', 'sun': '浅色', 'moon': '深色',
}


def svg(name):
    vector = ET.parse(ROOT / f'app/src/main/res/drawable/ic_{name}.xml').getroot()
    paths = []
    for path in vector.findall('path'):
        attrs = {'d': path.get(ANDROID + 'pathData')}
        for key, target in [('strokeWidth', 'stroke-width'), ('strokeLineCap', 'stroke-linecap'),
                            ('strokeLineJoin', 'stroke-linejoin'), ('fillAlpha', 'fill-opacity')]:
            if path.get(ANDROID + key):
                attrs[target] = path.get(ANDROID + key)
        attrs['fill'] = 'none' if path.get(ANDROID + 'fillColor') == '@android:color/transparent' else 'currentColor'
        if path.get(ANDROID + 'strokeColor'):
            attrs['stroke'] = 'currentColor'
        paths.append('<path ' + ' '.join(f'{k}="{escape(v)}"' for k, v in attrs.items()) + '/>')
    return '<svg viewBox="0 0 24 24" aria-hidden="true">' + ''.join(paths) + '</svg>'


def panel(dark=False):
    cells = ''.join(f'<div class="cell">{svg(name)}<span>{label}</span></div>' for name, label in SYMBOLS.items())
    nav = ''.join(f'<div class="tab {"active" if name == "home" else ""}">{svg(name)}</div>' for name in ['home', 'people', 'sync', 'settings'])
    return f'<section class="{"dark" if dark else "light"}"><h2>{"深色" if dark else "浅色"}</h2><div class="grid">{cells}</div><div class="nav">{nav}</div></section>'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    html = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>来电知人 · 图标</title>
<style>
*{box-sizing:border-box}body{margin:0;padding:40px;background:#e9edf3;color:#191b20;font-family:"Microsoft YaHei",sans-serif}
h1{font-size:28px;margin:0 0 10px}p{color:#657184;font-size:14px;margin:0 0 28px}.boards{display:flex;gap:24px}
section{flex:1;min-width:0;border-radius:28px;padding:26px;background:#f5f5f5}h2{font-size:17px;margin:0 0 24px}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.cell{height:88px;border-radius:16px;background:#fff;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#3482ff}
svg{display:block;width:24px;height:24px;flex-shrink:0}.cell span{font-size:11px;color:#647084}.dark{background:#101216;color:#f5f7fa}.dark .cell{background:#252a34;color:#91b9fa}.dark .cell span{color:#b2becf}
.nav{display:flex;border-radius:40px;background:#fff;margin-top:26px;padding:4px;height:64px}.tab{flex:1;display:grid;place-items:center;color:#191b20}.tab svg{width:26px;height:26px}.tab.active{border-radius:32px;background:#eaf2ff;color:#3482ff}
.dark .nav{background:#252a34}.dark .tab{color:#f5f7fa}.dark .tab.active{background:#22324c;color:#91b9fa}
footer{font-size:12px;color:#657184;margin-top:22px}
</style><h1>来电知人 · 图标优化</h1><p>统一圆角线性矢量 · 24dp 网格 · 1.8dp 线宽</p><div class="boards">'''
    html += panel() + panel(True) + '</div><footer>从 Android 矢量资源生成的图标预览；底部导航为静态示意，实际应用保留液态玻璃效果。</footer></html>'
    (OUT / 'index.html').write_text(html, encoding='utf-8')
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless=True)
        page = browser.new_page(viewport={'width': 1120, 'height': 760}, device_scale_factor=2)
        page.goto((OUT / 'index.html').as_uri())
        page.evaluate('document.fonts.ready')
        page.screenshot(path=str(OUT / 'overview.png'), full_page=True)
        browser.close()
    print(OUT / 'overview.png')


if __name__ == '__main__':
    main()
