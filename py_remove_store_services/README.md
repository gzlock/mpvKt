# mpvKt 远程存储测试服务

在本机用 Python 一键起 4 个常见文件共享服务（FTP / SSH+SFTP / SMB / WebDAV），
供 Android app 的「远程存储」功能联调测试。所有服务都把 `share_files/` 目录对外暴露为共享根。

## 目录结构

```
py_remove_store_service/
├── .venv/                 # 已建好的 Python 虚拟环境（Python 3.15）
├── share_files/           # 共享根目录（测试视频文件都在这里）
│   ├── multi_files/
│   └── single_file/
├── common.py              # 公共配置（账号、共享根、基准端口）
├── ftp_service.py         # FTP 服务
├── ssh_service.py         # SSH / SFTP 服务
├── smb_service.py         # SMB 服务
├── webdav_service.py      # WebDAV 服务
├── start_all.py           # 一键启动全部服务
├── start.bat              # Windows 双击启动器
├── requirements.txt       # 依赖清单
└── README.md
```

## 安装依赖

```powershell
.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## 一键启动

双击 `start.bat`，或命令行：

```powershell
.venv\Scripts\python.exe start_all.py
```

指定基准端口（可选）：

```powershell
.venv\Scripts\python.exe start_all.py --port 20000
# 或
$env:BASE_PORT="20000"; .venv\Scripts\python.exe start_all.py
```

按 `Ctrl+C` 停止全部服务。

## 端口 / 账号映射表（默认基准端口 10000）

| 协议     | 地址 / 端口              | 用户名     | 密码      | 匿名     | 说明 |
|----------|--------------------------|------------|-----------|----------|------|
| FTP      | `ftp://<host>:10000`     | testuser   | testpass  | 允许只读 | 被动模式端口 20000-20009 |
| SSH/SFTP | `ssh://<host>:10001`     | testuser   | testpass  | 不允许   | 同时支持 SFTP 子系统和 exec 命令 |
| SMB      | `\\<host>\share` 端口 10002 | testuser | testpass  | 允许     | 共享名 `share` |
| WebDAV   | `http://<host>:10003/`   | testuser   | testpass  | 允许只读 | 支持 PROPFIND / GET / PUT |

> 端口顺序：FTP=基准，SSH=基准+1，SMB=基准+2，WebDAV=基准+3。

## Android app 里怎么填

- **模拟器（Android Emulator）**：宿主机 IP 填 `10.0.2.2`（这是模拟器里访问宿主机的特殊地址）。
- **真机**：手机和电脑连同一个 Wi-Fi，填电脑的局域网 IP（`ipconfig` 查看，例如 `192.168.x.x`）。
- 各协议在 app 里的连接信息：
  - **FTP**：主机 `10.0.2.2`，端口 `10000`，用户名 `testuser`，密码 `testpass`。
  - **SFTP**：主机 `10.0.2.2`，端口 `10001`，用户名 `testuser`，密码 `testpass`。
  - **SMB**：主机 `10.0.2.2`，端口 `10002`，共享名 `share`，用户名 `testuser`，密码 `testpass`。
  - **WebDAV**：URL `http://10.0.2.2:10003/`，用户名 `testuser`，密码 `testpass`。

## SSH exec 命令通道

SSH 服务在 `check_channel_exec_request` 里用 `subprocess` 真正执行客户端发来的命令
（Windows 下 `shell=True`，支持 `dir`、`echo` 等内建命令），工作目录就是 `share_files/`，
执行完把 stdout / stderr / 退出码回传给客户端。app 里「服务器命令片段」功能就是走这条通道。

首次启动会在本目录生成 `host_key`（RSA 2048 主机密钥），之后复用；删掉即可重新生成。

## 已知限制

- **SMB 兼容性**：impacket 自带的 SMB 服务端实现 SMB2 协商，但对 Android 上的 smbj /
  SMB 客户端支持并不完整（签名、加密协商可能握手失败）。本服务能正常监听 10002 端口，
  Windows 资源管理器可访问；如果 app 里连不上 SMB，请优先用 FTP/SFTP/WebDAV 联调。
- **WebDAV**：用 Python 标准库 `wsgiref` 起服务，单线程，仅供测试，不要用于生产。
- **FTP 被动模式**：数据端口固定在 20000-20009，模拟器/真机直连本机时一般没问题。
