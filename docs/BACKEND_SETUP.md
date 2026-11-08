# 后端服务搭建指南

本文依据当前 Android 客户端实现编写。仓库没有现有业务后端源码；下方是可独立搭建的最小参考服务，以外部 CSV 快照作为数据源，不包含员工管理、数据库适配或登录系统。先用虚构数据联调，再接入有权使用的组织数据。

客户端依据：[下载与解密](../app/src/main/java/com/zuozishi/callhelper/data/EmployeeExport.kt)、[CSV 校验](../app/src/main/java/com/zuozishi/callhelper/data/PersonnelCsv.kt)、[同步配置](../app/src/main/java/com/zuozishi/callhelper/data/DirectorySyncConfig.kt)。

## 1. 接口约定

| 项目 | 要求 |
| --- | --- |
| URL | 如 `https://directory.example.com/api/employee/export`，需替换域名 |
| 请求 | `GET`，客户端发送 `Accept: text/plain`，不携带 Cookie、Authorization 或客户端证书 |
| 成功响应 | HTTP 200，`Content-Type: text/plain; charset=us-ascii`，正文是标准 Base64 文本 |
| 缓存 | 响应设置 `Cache-Control: no-store`，反向代理关闭缓存 |
| TLS | 使用客户端系统信任的有效证书及完整证书链，不跳过证书验证 |
| 跳转 | 返回最终接口响应，客户端不跟随 301/302 等重定向 |
| 超时 | 客户端连接 15 秒、读取 30 秒，服务应在此范围内完成 |
| 失败 | 非 200；不要将异常堆栈、密钥或人员值返回给用户 |

URL 不超过 1024 字符，不接受内嵌用户名密码或 `#fragment`。正文不是 JSON，不要包裹引号、添加 `data:` 前缀、使用 URL-safe Base64 或插入换行。客户端只会去除正文首尾空白。

### 加密封包

```text
Base64(version || nonce || tag || ciphertext)

version      1 字节，固定 0x01
nonce       12 字节，每次加密重新随机生成
tag         16 字节认证标签
ciphertext  UTF-8 CSV 的 AES-256-GCM 密文
AAD         version 的原始单字节 b"\x01"
key         32 字节随机密钥；配置给客户端时使用标准 Base64
```

不要将字符 `"1"` 当作版本字节，也不要把 nonce 或 tag 单独编码后拼接。Python `AESGCM.encrypt` 返回 `ciphertext || tag`，发送前必须调整顺序；同一密钥下不得重复使用 nonce。API 行为见 [cryptography AEAD 官方文档](https://cryptography.io/en/49.0.0/hazmat/primitives/aead/)。

### CSV 字段

| 字段 | 必需列 | 内容 |
| --- | --- | --- |
| `Id` | 是 | 稳定人员标识，值必填 |
| `Phone` | 是 | 可规范化的完整号码，值必填 |
| `Name` | 是 | 姓名，值必填 |
| `Post` | 是 | 岗位，允许空值 |
| `TopOrgName` | 是 | 所属单位，允许空值 |
| `FullOrgName` | 是 | 完整组织路径，建议用 `/` 分隔，允许空值 |
| `AvatarUrl` | 否 | HTTPS 头像绝对地址，缺失或无效时显示姓氏占位 |

表头区分大小写、不能重复，可重排列或增加其他列。使用 UTF-8（可选 BOM），每行列数与表头一致；逗号、双引号和换行按 CSV 规则转义。六个核心字段各不超过 2000 字符，明文不超过 20 MiB；解析上限为 100001 个逻辑行（含表头，空行也占计数），至少一条有效人员记录。

号码允许 3–20 位数字及开头的 `+`，会移除空格、括号、点和连字符；11 位国内号码的 `+86` / `0086` 前缀会归一化。接口同步允许重复 `Id` 表示多条身份，共享号码保留全部身份，不要按号码随意去重。无效的一条记录也会使整份同步失败。

以下均为虚构数据，只在服务器独立数据目录创建，不放入 Android 项目：

```csv
Id,Phone,Name,Post,TopOrgName,FullOrgName,AvatarUrl
demo-001,10001,示例人员甲,工程师,示例公司,示例集团/示例公司/研发部,
demo-002,10002,示例人员乙,行政,示例公司,示例集团/示例公司/综合部,
```

## 2. 准备独立运行目录

以下以具备 systemd、Python 3、Nginx 的 Linux 主机为例，使用有 sudo 权限的管理账户执行。先通过发行版包管理器安装 `python3`、`python3-venv` 和 `nginx`。命令中的 `callhelper` 用户与目录名需保持一致；若用户已存在，跳过创建步骤。

```bash
sudo useradd --system --home /opt/callhelper-export --shell /usr/sbin/nologin callhelper
sudo install -d -m 755 /opt/callhelper-export
sudo install -d -o root -g callhelper -m 750 /etc/callhelper-export /var/lib/callhelper-export
sudo python3 -m venv /opt/callhelper-export/.venv
sudo /opt/callhelper-export/.venv/bin/python -m pip install cryptography gunicorn
sudo /opt/callhelper-export/.venv/bin/python -m pip freeze | sudo tee /opt/callhelper-export/requirements.lock.txt > /dev/null
```

锁文件记录本次解析到的依赖版本，用于重建相同环境；更新依赖后重新联调。该锁文件和服务代码在服务器独立管理，不把运行数据复制进 Android 仓库。

将 CSV 保存为 `/var/lib/callhelper-export/people.csv`，设置为 `root:callhelper`、权限 `640`。可先在独立暂存目录创建上述虚构 CSV，再安装到运行目录：

```bash
sudo install -o root -g callhelper -m 640 /path/to/people.csv /var/lib/callhelper-export/people.csv
```

生成密钥文件，不在终端打印密钥：

```bash
sudo /opt/callhelper-export/.venv/bin/python - <<'PY'
import base64
import os
from pathlib import Path

path = Path('/etc/callhelper-export/key.b64')
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'wb') as output:
    output.write(base64.b64encode(os.urandom(32)) + b'\n')
PY
sudo chown root:callhelper /etc/callhelper-export/key.b64
sudo chmod 640 /etc/callhelper-export/key.b64
```

已有密钥文件时生成命令会失败，防止意外覆盖。通过可信管理渠道读取并分发此 Base64 密钥，不将其放进工单、公开日志或 Git。

## 3. 参考 WSGI 服务

将以下代码保存为 `/opt/callhelper-export/export_service.py`，所有者 `root:root`、权限 `644`。每次请求读取快照并生成新 nonce；导出内容只在内存中加密，不产生明文临时文件。该服务只做基本 CSV 结构校验，号码等业务规则仍需按第 1 节在数据生产流程校验。

```python
import base64
import csv
import io
import os
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_BYTES = 20 * 1024 * 1024
REQUIRED = {'Id', 'Phone', 'Name', 'Post', 'TopOrgName', 'FullOrgName'}
CSV_PATH = Path(os.environ['CALLHELPER_CSV_PATH'])
KEY_PATH = Path(os.environ['CALLHELPER_KEY_PATH'])
KEY = base64.b64decode(KEY_PATH.read_bytes().strip(), validate=True)
if len(KEY) != 32:
    raise RuntimeError('Invalid export key length')
csv.field_size_limit(MAX_BYTES)


def export_body():
    with CSV_PATH.open('rb') as source:
        plain = source.read(MAX_BYTES + 1)
    if not plain or len(plain) > MAX_BYTES:
        raise ValueError('Invalid CSV size')
    rows = csv.reader(io.StringIO(plain.decode('utf-8-sig'), newline=''), strict=True)
    header = [value.strip() for value in next(rows)]
    if len(set(header)) != len(header) or not REQUIRED.issubset(header):
        raise ValueError('Invalid CSV header')
    count = 0
    for line, row in enumerate(rows, start=2):
        if line > 100001:
            raise ValueError('Too many rows')
        if not row or all(not value.strip() for value in row):
            continue
        if len(row) != len(header):
            raise ValueError('Invalid CSV row width')
        record = dict(zip(header, row))
        if any(not record[name].strip() for name in ('Id', 'Phone', 'Name')):
            raise ValueError('Missing required value')
        if any(len(record[name].strip()) > 2000 for name in REQUIRED):
            raise ValueError('Field too long')
        count += 1
    if count == 0:
        raise ValueError('No personnel records')
    version = b'\x01'
    nonce = os.urandom(12)
    encrypted = AESGCM(KEY).encrypt(nonce, plain, version)
    return base64.b64encode(version + nonce + encrypted[-16:] + encrypted[:-16])


def application(environ, start_response):
    headers = [('Content-Type', 'text/plain; charset=us-ascii'),
               ('Cache-Control', 'no-store')]
    if environ.get('PATH_INFO') != '/api/employee/export':
        status, body = '404 Not Found', b'Not found'
    elif environ.get('REQUEST_METHOD') != 'GET':
        status, body = '405 Method Not Allowed', b'Method not allowed'
        headers.append(('Allow', 'GET'))
    else:
        try:
            body = export_body()
            status = '200 OK'
        except Exception:
            # Do not log exception values, CSV contents, URLs or keys.
            status, body = '503 Service Unavailable', b'Export unavailable'
    start_response(status, headers + [('Content-Length', str(len(body)))])
    return [body]
```

原业务系统若直接查询数据库，可替换 `export_body()` 的 CSV 读取部分；仍需保证快照一致性、UTF-8 编码与封包格式。不要把数据库连接信息写入代码或响应。

## 4. 使用 systemd 启动

保存为 `/etc/systemd/system/callhelper-export.service`：

```ini
[Unit]
Description=CallHelper encrypted directory export
After=network.target

[Service]
User=callhelper
Group=callhelper
WorkingDirectory=/opt/callhelper-export
Environment=CALLHELPER_CSV_PATH=/var/lib/callhelper-export/people.csv
Environment=CALLHELPER_KEY_PATH=/etc/callhelper-export/key.b64
ExecStart=/opt/callhelper-export/.venv/bin/gunicorn --bind 127.0.0.1:8000 --workers 2 --timeout 30 export_service:application
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now callhelper-export
sudo systemctl status callhelper-export --no-pager
curl --fail --silent --output /dev/null http://127.0.0.1:8000/api/employee/export
```

本机 HTTP 仅用于反向代理，不能填写到 Android 客户端。应用端口仅绑定回环地址；不要将 8000 端口开放到公网。Gunicorn 的代理与服务管理方式参见[官方部署说明](https://gunicorn.org/deploy/)。

## 5. 配置 HTTPS 反向代理

先为实际域名配置 DNS 和有效 TLS 证书，再将下面的 `server` 块加入 Nginx 已加载的配置目录。证书路径是示例，需换成主机上真实的证书链和私钥路径；证书签发、续期和网络访问范围由部署环境管理。

```nginx
server {
    listen 443 ssl;
    server_name directory.example.com;

    ssl_certificate /etc/ssl/callhelper/fullchain.pem;
    ssl_certificate_key /etc/ssl/callhelper/privkey.pem;

    location = /api/employee/export {
        proxy_pass http://127.0.0.1:8000;
        proxy_connect_timeout 5s;
        proxy_read_timeout 25s;
        proxy_cache off;
        proxy_buffering on;
        access_log off;
    }

    location / {
        return 404;
    }
}
```

```bash
sudo nginx -t
sudo systemctl reload nginx
```

客户端应配置最终的 `https://` 地址，不依赖 HTTP 跳转、登录页、网页验证码或额外请求头。当前协议为共享密钥加匿名 GET；优先将服务限制在受控内网或 VPN 内。若业务需要独立账号认证、撤销单个设备权限或审计身份，需要扩展客户端及接口协议。Nginx 的 `proxy_pass` 语义见[官方模块文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)。

## 6. 联调与手机配置

在 Android 仓库之外建立 Python 虚拟环境并安装 `cryptography`，使用该环境的 Python 执行仓库工具：

```powershell
python scripts/verify_remote_export.py
```

按提示输入最终 HTTPS URL 和 Base64 密钥；省略 `--url` 可避免地址进入命令历史。脚本在内存中解密，只输出状态、记录数、单位数与字段等聚合信息，不保存明文。它验证封包及基本解析，不能替代 Android 的严格字段校验；脚本可能跟随重定向，手机端不会，部署时应确认直接返回 200。

随后在手机「数据同步」配置并保存同一 URL / 密钥，手动同步，检查人员数量、组织路径与虚构号码查询。重启应用验证离线恢复；用测试服务模拟非 200 或错误密钥，确认原人员库仍可用。真实电话、权限、锁屏及背屏行为需要目标设备单独验收。

## 7. 更新、轮换与维护

- **更新快照**：先在服务器同一文件系统的私有暂存位置生成并校验 CSV，设置 `root:callhelper`、权限 `640`，再原子重命名覆盖 `people.csv`。不要直接截断正在读取的文件。每次 GET 读取最新文件，无需重启服务。
- **轮换密钥**：通过安全渠道生成和保存新密钥，原子替换 `key.b64` 后重启 `callhelper-export`，再向授权用户分发新配置。服务在进程启动时读取密钥；现有协议没有 key-id 或双密钥协商，切换期间旧配置同步会失败，但旧离线库保留。无需重建 APK。
- **撤销访问**：更换共享密钥不删除手机上已保存的数据。遗失设备或人员离职的数据处理需要组织管理机制。
- **备份与日志**：服务器 CSV 是明文敏感数据，应限制读取、备份权限及保留时间；只记录状态、耗时等必要指标，不记录响应正文、完整配置 URL、二维码或密钥。
- **容量与新鲜度**：这是逐次全量加密参考实现，大规模部署应测量并发、内存和耗时。200 与可解密不代表数据新鲜，需监控数据源最后成功导出时间；当前客户端没有服务器版本或时间戳防重放校验。

## 8. 排障

| 现象 | 检查 |
| --- | --- |
| HTTP 301/302 | 直接配置最终路径，关闭该接口登录跳转或补斜杠跳转 |
| HTTP 401/403 | 网关是否要求客户端尚不支持的身份凭据；确认 VPN / 内网策略 |
| HTTP 503 | CSV 路径、读取权限、UTF-8、必需字段和行列结构 |
| Nginx 502 | systemd 状态、本机 8000 端口、密钥文件权限及依赖安装 |
| TLS 失败 | 域名、证书链、有效期、手机时间与系统信任；不要关闭证书校验 |
| Base64 无效 | 返回了 JSON、HTML、代理错误页或带内部换行的正文 |
| 认证失败 | 32 字节密钥是否一致；检查 version、AAD、nonce 长度及 tag 顺序 |
| CSV 校验失败 | 检查单条记录号码、空值、字段长度、列数和 20 MiB 上限 |
| 头像不显示 | 地址为独立 HTTPS 资源、设备可直接访问；不能依赖导出请求的登录态 |

## 9. 本文示例验证范围

已在本地使用上文虚构 CSV 执行 WSGI 示例，验证标准 Base64、版本与 tag 布局、AES-GCM 解密还原、新 nonce、响应长度、禁用缓存，以及 404 / 405 / 503 分支。README 与本文的本地链接和代码围栏检查通过。

Linux 安装命令、systemd、Nginx、证书链与手机同步仍需在目标部署环境联调；本次未执行生产部署，也未将本地示例验证当作生产验收。
