"""Build and render the review-only About / application update design."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/design/about-update-v1'
A = '{http://schemas.android.com/apk/res/android}'


def icon(name):
    if name == 'info':
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v6M12 7v1"/></svg>'
    root = ET.parse(ROOT / f'app/src/main/res/drawable/ic_{name}.xml').getroot()
    paths = []
    for p in root.findall('path'):
        attrs = {'d': p.get(A+'pathData'), 'fill': 'none' if p.get(A+'fillColor') in ('@android:color/transparent', '#00000000') else ('#3482FF' if name == 'app' and not paths else 'currentColor')}
        for src, dest in [('strokeWidth','stroke-width'),('strokeLineCap','stroke-linecap'),('strokeLineJoin','stroke-linejoin')]:
            if p.get(A+src): attrs[dest] = p.get(A+src)
        if p.get(A+'strokeColor'): attrs['stroke'] = 'currentColor'
        paths.append('<path '+' '.join(f'{k}="{v}"' for k,v in attrs.items())+'/>')
    size = root.get(A+'viewportWidth')
    return f'<svg viewBox="0 0 {size} {size}" aria-hidden="true">'+''.join(paths)+'</svg>'


def nav():
    return '<nav aria-label="主导航">'+''.join(f'<button aria-label="{label}" '+('aria-current="page" class="selected"' if key=='info' else '')+'>'+icon(key)+'</button>' for key,label in [('home','首页'),('people','人员库'),('sync','数据同步'),('settings','设置'),('info','关于')])+'</nav>'


def row(symbol, title, value, url):
    return f'<a class="row" href="{url}" target="_blank" rel="noreferrer"><span class="rowicon">{icon(symbol)}</span><span class="grow">{title}</span><span class="value">{value}</span>{icon("arrow")}</a>'


def about():
    return f'''<h2>关于</h2><div class="brand">{icon('app')}<h3>来电名片</h3></div>
    <div class="card update-card"><div class="row"><span>当前版本</span><span class="value">v0.2.0</span></div><button class="primary check">{icon('sync')}<span>检查更新</span></button><p class="check-state" aria-live="polite" hidden></p></div>
    <div class="card links">{row('folder','GitHub','zuozishi / CallHelper','https://github.com/zuozishi/CallHelper')}{row('people','作者','zuozishi','https://github.com/zuozishi')}</div>
    <div class="card"><button class="row history-trigger"><span class="rowicon">{icon('preview')}</span><span class="grow">更新记录</span>{icon('arrow')}</button></div>'''


NOTES = '<li>新增关于页与应用版本检查</li><li>支持应用内下载并调起安装</li><li>优化启动检查与下载体验</li>'


def sheet(kind):
    if kind == 'found':
        return f'''<div class="shade"></div><section class="sheet" role="dialog" aria-label="发现新版本"><span class="handle"></span><div class="sheet-top"><span class="eyebrow blue">应用更新</span><button class="close" aria-label="关闭更新提示">{icon('close')}</button></div><h3>发现新版本</h3><p class="meta">v0.2.0 → v0.2.1 <span>· 18.6 MB</span></p><ul>{NOTES}</ul><button class="primary download">下载更新</button><button class="secondary later">稍后</button></section>'''
    if kind == 'download':
        return f'''<div class="shade"></div><section class="sheet" role="dialog" aria-label="下载更新"><span class="handle"></span><div class="sheet-top"><span class="eyebrow blue">应用更新</span><button class="close" aria-label="收起下载进度">{icon('close')}</button></div><h3>正在下载</h3><p class="meta">v0.2.1</p><div class="progress-info"><strong>62%</strong><span>11.5 / 18.6 MB</span></div><div class="track" role="progressbar" aria-label="安装包下载进度" aria-valuenow="62" aria-valuemin="0" aria-valuemax="100"><div></div></div><p class="download-note">下载完成后自动打开安装器</p><button class="primary hide-download">收起</button><button class="secondary cancel-download">取消下载</button></section>'''
    return f'''<div class="shade"></div><section class="sheet" role="dialog" aria-label="允许安装更新"><span class="handle"></span><div class="permission-icon">{icon('shield')}</div><h3>允许安装更新</h3><p class="permission-copy">安装包已下载。请在系统设置中允许<br>「来电名片」安装应用。</p><button class="primary authorize">前往设置</button><button class="secondary later">稍后</button></section>'''


def history():
    return f'''<div class="history-head"><button aria-label="返回关于" class="back">{icon('back')}</button><h2>更新记录</h2></div><div class="release-title"><strong>0.2.0</strong><span class="badge">当前版本</span><p>2026-11-08</p></div><div class="card release"><h4>功能更新</h4><ul><li>支持加密全量人员库同步</li><li>支持扫码导入与导出同步配置</li><li>支持组织逐级浏览与全库搜索</li><li>适配小米原生背屏联系人卡片</li></ul><h4>体验与安全</h4><ul><li>更新界面图标</li><li>增加人员库过期提醒</li><li>移除安装包内的人员数据与配置</li></ul></div><a class="release-link" href="https://github.com/zuozishi/CallHelper/releases" target="_blank" rel="noreferrer">在 GitHub 查看{icon('arrow')}</a>'''


def phone(key, dark=False, overlay=None):
    return f'''<article class="phone {'dark' if dark else ''}" id="{key}"><div class="status"><b>9:41</b><span>▂▄▆ ▰</span></div><main class="content">{history() if key=='history' else about()}</main>{nav()}<div class="gesture"></div>{sheet(overlay) if overlay else ''}</article>'''


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/'screens').mkdir(exist_ok=True)
    css = '''
*{box-sizing:border-box}body{margin:0;background:#e9edf3;color:#191b20;font:14px "MiSans","Microsoft YaHei",sans-serif}button,a{-webkit-tap-highlight-color:transparent}button{font:inherit;border:0;cursor:pointer;color:inherit}a{color:inherit;text-decoration:none}button:focus-visible,a:focus-visible{outline:3px solid #3482ff;outline-offset:2px}svg{width:24px;height:24px;flex-shrink:0;display:block}button{display:flex;align-items:center;justify-content:center}header{display:flex;justify-content:space-between;align-items:end;margin-bottom:32px}h1{font-size:34px;margin:10px 0;font-weight:650;letter-spacing:-1px}header p{color:#647084;margin:0}.eyebrow{font-size:12px;letter-spacing:2px;color:#647084}.edition{text-align:right;font-size:12px;line-height:1.9;color:#647084}.board{padding:42px;width:1350px;margin:auto}.grid{display:grid;grid-template-columns:repeat(3,390px);gap:30px 36px}.caption{display:flex;gap:12px;align-items:center;font-size:14px;font-weight:600;margin-bottom:14px}.caption span{font-size:12px;color:#748198}.phone{--bg:#f5f5f5;--surface:#fff;--fg:#191b20;--muted:#68717d;position:relative;background:var(--bg);color:var(--fg);width:390px;height:844px;border-radius:38px;overflow:hidden;box-shadow:0 8px 26px #283c5210;border:1px solid #ffffffc9}.phone.dark{--bg:#101216;--surface:#252a34;--fg:#f3f5f8;--muted:#b0b8c5;border-color:#343a43}.status{height:56px;padding:16px 25px;display:flex;justify-content:space-between;font-size:13px}.content{padding:12px 22px 130px;height:700px;overflow:auto;scrollbar-width:none}h2{font-size:32px;font-weight:600;line-height:1.3;margin:0 0 20px}h3{font-size:23px;margin:0 0 8px;font-weight:600}.brand{display:flex;align-items:center;flex-direction:column;padding:13px 0 29px}.brand>svg{width:80px;height:80px;color:#fff;margin-bottom:17px}.brand h3{font-size:24px;margin-bottom:5px}.muted,.value{color:var(--muted);font-size:13px}.card{background:var(--surface);border-radius:24px;padding:0 18px;margin-bottom:16px}.row{display:flex;gap:12px;align-items:center;width:100%;min-height:64px;background:transparent;text-align:left;font-size:15px}.row>svg{width:18px;height:18px;color:var(--muted)}.grow{flex:1}.value{margin-left:auto}.rowicon{color:var(--fg)}.rowicon svg{width:22px;height:22px}.links .row+.row{border-top:1px solid #80808016}.update-card{padding-bottom:18px}.primary{width:100%;height:50px;border-radius:16px;background:#3482ff;color:white;gap:9px;font-size:16px;font-weight:600}.primary svg{width:20px;height:20px}.secondary{width:100%;height:48px;background:transparent;margin-top:8px;font-size:15px;color:var(--muted)}nav{position:absolute;left:22px;right:22px;bottom:28px;height:64px;display:flex;gap:0;padding:4px;background:linear-gradient(140deg,#ffffffc9,#e2e8ef9e);border:1px solid #fff9;border-radius:999px;box-shadow:0 5px 20px #20324815,inset 0 1px #fff;backdrop-filter:blur(18px)}nav button{flex:1;min-width:48px;background:transparent;border-radius:99px;color:#66717e}nav button.selected{color:#3482ff;background:linear-gradient(135deg,#d6e6faab,#d4deed80);box-shadow:inset 0 1px #fff9}.dark nav{background:linear-gradient(140deg,#353d4ad9,#222935d9);border-color:#ffffff18;box-shadow:0 5px 20px #0003,inset 0 1px #ffffff22}.dark nav button{color:#b8c0ce}.dark nav button.selected{color:#83b3ff;background:#45618c55;box-shadow:inset 0 1px #ffffff22}.gesture{position:absolute;bottom:10px;left:132px;width:126px;height:5px;border-radius:4px;background:var(--fg);opacity:.85}.shade{position:absolute;inset:0;background:#10162255;backdrop-filter:blur(2px)}.sheet{position:absolute;bottom:0;left:0;right:0;border-radius:32px 32px 0 0;background:var(--surface);padding:12px 24px 28px;box-shadow:0 -10px 50px #10162215}.sheet h3{font-size:26px;line-height:1.3}.handle{display:block;width:32px;height:4px;background:#858b943d;border-radius:5px;margin:0 auto 16px}.sheet-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.blue{color:#3482ff;letter-spacing:1px}.close{width:48px;height:48px;background:transparent;color:var(--muted);margin-right:-12px}.meta{color:var(--muted);font-size:14px;margin:8px 0 23px}.meta span{margin-left:8px}ul{padding-left:20px;line-height:1.9;margin:0 0 24px;font-size:14px}li{padding-left:2px;margin:6px 0}.progress-info{display:flex;justify-content:space-between;align-items:baseline;margin-top:30px}.progress-info strong{font-size:32px;font-weight:600}.progress-info span{color:var(--muted);font-size:13px}.track{margin-top:15px;height:7px;background:#3482ff20;border-radius:20px;overflow:hidden}.track div{width:62%;height:100%;background:#3482ff;border-radius:20px}.download-note{color:var(--muted);font-size:13px;margin:17px 0 28px}.permission-icon{width:58px;height:58px;border-radius:19px;background:#3482ff13;color:#3482ff;display:grid;place-items:center;margin:20px auto}.permission-icon svg{width:30px;height:30px}.sheet:has(.permission-icon){text-align:center}.permission-copy{font-size:15px;line-height:1.9;color:var(--muted);margin:14px 0 27px}.history-head{display:flex;align-items:center;gap:7px;margin-left:-10px;margin-bottom:30px}.history-head h2{font-size:29px;margin:0}.back{height:48px;width:40px;background:transparent}.release-title{margin-bottom:18px}.release-title strong{font-size:28px;font-weight:600}.badge{font-size:11px;border-radius:7px;background:#3482ff16;color:#3482ff;padding:5px 8px;vertical-align:middle;margin-left:10px}.release-title p{font-size:13px;color:var(--muted);margin:9px 0}.release{padding:20px}.release h4{font-size:15px;margin:0 0 12px}.release ul{margin-bottom:22px}.release ul:last-child{margin-bottom:0}.release-link{display:flex;align-items:center;justify-content:center;min-height:48px;gap:6px;color:#3482ff;font-size:13px}.release-link svg{width:17px;height:17px}.check-state{margin:12px 0 0;font-size:13px;color:var(--muted);text-align:center}.check-state:empty{display:none}.note{font-size:12px;line-height:1.7;color:#647084;margin:13px 3px 0}.foot{margin-top:32px;display:flex;justify-content:space-between;font-size:12px;color:#647084}.controls{display:flex;align-items:center;gap:8px;margin:0 0 28px;font-size:12px;color:#647084}.controls select,.controls button{font:inherit;padding:8px 12px;border-radius:8px;border:1px solid #cdd5df;background:#fff}.banner{background:#dfe8f3;border-radius:12px;padding:14px 18px;margin-top:24px;font-size:13px;color:#43536a} [hidden]{display:none!important}@media(max-width:900px){.board{width:auto;padding:24px}.grid{grid-template-columns:390px;justify-content:center}header{display:block}.edition{text-align:left;margin-top:15px}}'''
    specs = [('about','01','关于 · 默认','版本、仓库、作者与更新记录集中展示。',False,None),('found','02','发现新版本','启动检查发现新版本时展示；版本与包大小为设计示例。',False,'found'),('download','03','下载更新','收起后继续下载；完成并校验后自动打开系统安装器。',False,'download'),('dark','04','关于 · 深色','跟随应用外观；保留五个图标入口与选中状态。',True,None),('history','05','更新记录','当前版本内容节选自项目 CHANGELOG；支持查看 GitHub。',False,None),('permission','06','首次安装授权','仅未授权时出现；返回应用后继续调起系统安装器。',False,'permission')]
    html = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>来电名片 · 关于与更新</title><style>'+css+'</style><div class="board"><header><div><div class="eyebrow">CALLHELPER / ABOUT & UPDATES</div><h1>关于与应用更新</h1><p>延续现有 Miuix 界面与液态玻璃导航</p></div><div class="edition">UI 设计 V1 · 2026.11.08<br>390 × 844 · 设计评审稿</div></header><div class="controls"><label for="scenario">检查更新演示</label><select id="scenario"><option value="new">发现新版</option><option value="latest">已是最新</option><option value="fail">网络不可用</option></select><button id="startup">模拟启动检查</button><span>仅用于评审</span></div><div class="grid">'
    for key,num,title,note,dark,overlay in specs:
        html += f'<section><div class="caption"><span>{num}</span>{title}</div>'+phone(key,dark,overlay)+f'<p class="note">{note}</p></section>'
    html += '</div><div class="banner">启动检查失败：静默结束，无提示、无警告。Release 信息与 APK 均优先 gh-proxy.com，失败后自动回退 GitHub。</div><div class="foot"><span>保留必要业务信息 · 图标具有无障碍名称 · 操作区域 ≥ 48dp</span><span>新版号、包大小与下载进度均为示例</span></div></div>'
    html += '''<script>
const first=document.querySelector('#about');
const foundTemplate=document.querySelector('#found .shade').outerHTML+document.querySelector('#found .sheet').outerHTML;
function dismiss(p){p.querySelectorAll('.shade,.sheet').forEach(e=>e.remove())}
document.querySelectorAll('.close,.later,.hide-download').forEach(b=>b.onclick=()=>dismiss(b.closest('.phone')));
document.querySelectorAll('.check').forEach(b=>b.onclick=()=>{const p=b.closest('.phone'),s=p.querySelector('.check-state'),mode=document.querySelector('#scenario').value;dismiss(p);s.hidden=false;if(mode==='new'){s.textContent='发现新版本 v0.2.1';p.insertAdjacentHTML('beforeend',foundTemplate);p.querySelector('.download').onclick=()=>{dismiss(p);s.textContent='正在下载 · 62%';};p.querySelector('.close').onclick=()=>dismiss(p);p.querySelector('.later').onclick=()=>dismiss(p)}else s.textContent=mode==='fail'?'无法连接，请重试':'已是最新版本';});
document.querySelector('#startup').onclick=()=>{if(document.querySelector('#scenario').value==='new')first.querySelector('.check').click()};
document.querySelectorAll('.history-trigger').forEach(b=>b.onclick=()=>document.querySelector('#history').scrollIntoView({behavior:'smooth',block:'center'}));
document.querySelectorAll('.back').forEach(b=>b.onclick=()=>first.scrollIntoView({behavior:'smooth',block:'center'}));
document.querySelectorAll('.cancel-download').forEach(b=>b.onclick=()=>dismiss(b.closest('.phone')));
document.querySelectorAll('.download').forEach(b=>b.onclick=()=>document.querySelector('#download').scrollIntoView({behavior:'smooth',block:'center'}));
document.querySelectorAll('.authorize').forEach(b=>b.onclick=()=>{b.textContent='系统安装设置（示意）';});
</script></html>'''
    (OUT/'index.html').write_text(html,encoding='utf-8')
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless=True)
        page=browser.new_page(viewport={'width':1350,'height':2150},device_scale_factor=1.5)
        errors=[]
        page.on('pageerror',lambda e: errors.append(str(e)))
        page.goto((OUT/'index.html').as_uri())
        page.evaluate('document.fonts.ready')
        page.locator('.board').screenshot(path=str(OUT/'overview.png'))
        for key,*_ in specs: page.locator('#'+key).screenshot(path=str(OUT/'screens'/f'{key}.png'))
        # Verify the relevant distinction: automatic network failure stays silent.
        page.select_option('#scenario','fail'); page.click('#startup')
        assert page.locator('#about .check-state').is_hidden()
        page.click('#about .check')
        assert page.locator('#about .check-state').inner_text()=='无法连接，请重试'
        page.locator('#about').screenshot(path=str(OUT/'screens/manual-error.png'))
        page.select_option('#scenario','latest'); page.click('#about .check')
        assert page.locator('#about .check-state').inner_text()=='已是最新版本'
        page.locator('#about').screenshot(path=str(OUT/'screens/latest.png'))
        page.select_option('#scenario','new'); page.click('#about .check')
        assert page.locator('#about .sheet').is_visible()
        page.click('#about .later')
        assert page.locator('#about .sheet').count()==0
        overflow=page.locator('.phone').evaluate_all('(els)=>els.filter(e=>e.scrollWidth>e.clientWidth).map(e=>e.id)')
        assert not errors and not overflow,(errors,overflow)
        (OUT/'validation.json').write_text(json.dumps({'javascript_errors':errors,'horizontal_overflow':overflow,'checked':['startup network failure is silent','manual failure feedback','latest feedback','new release dismissal'],'scope':'offline review prototype; no real download or installation'},ensure_ascii=False,indent=2),encoding='utf-8')
        browser.close()
    print(OUT/'overview.png')


if __name__=='__main__': main()
