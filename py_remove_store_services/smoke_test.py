# -*- coding: utf-8 -*-
"""冒烟测试：FTP 列目录 / SFTP 列目录+exec / WebDAV PROPFIND+GET。"""
import ftplib
import urllib.request
import urllib.error
import base64

import paramiko

BASE = "127.0.0.1"
FTP_PORT = 10000
SSH_PORT = 10001
WEBDAV_PORT = 10003
USER = "testuser"
PASS = "testpass"

print("=== 1) FTP 登录列目录 ===")
try:
    ftp = ftplib.FTP()
    ftp.connect(BASE, FTP_PORT, timeout=5)
    ftp.login(USER, PASS)
    print("FTP 登录成功:", ftp.getwelcome())
    files = []
    ftp.retrlines("LIST", files.append)
    for f in files[:10]:
        print("  ", f)
    ftp.quit()
    print("FTP OK\n")
except Exception as e:
    print("FTP FAIL:", repr(e), "\n")

print("=== 2) SFTP 列目录 ===")
sftp_ok = False
try:
    t = paramiko.Transport((BASE, SSH_PORT))
    t.connect(username=USER, password=PASS)
    sftp = paramiko.SFTPClient.from_transport(t)
    print("SFTP 连接成功，根目录列表:")
    for f in sftp.listdir("/"):
        print("  ", f)
    sftp.close()
    t.close()
    sftp_ok = True
    print("SFTP OK\n")
except Exception as e:
    print("SFTP FAIL:", repr(e), "\n")

print("=== 3) SSH exec 命令 ===")
try:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(BASE, port=SSH_PORT, username=USER, password=PASS, timeout=5)
    stdin, stdout, stderr = c.exec_command("echo hello_from_ssh && cd . && dir /b")
    out = stdout.read().decode("gbk", errors="replace")
    err = stderr.read().decode("gbk", errors="replace")
    code = stdout.channel.recv_exit_status()
    print("exit code:", code)
    print("stdout:", out[:500])
    if err:
        print("stderr:", err[:300])
    c.close()
    print("SSH exec OK\n")
except Exception as e:
    print("SSH exec FAIL:", repr(e), "\n")

print("=== 4) WebDAV PROPFIND + GET ===")
try:
    # PROPFIND
    req = urllib.request.Request(f"http://{BASE}:{WEBDAV_PORT}/", method="PROPFIND")
    auth = base64.b64encode(f"{USER}:{PASS}".encode()).decode()
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("Depth", "1")
    with urllib.request.urlopen(req, timeout=5) as r:
        body = r.read().decode("utf-8", errors="replace")
        print("PROPFIND status:", r.status, "bytes:", len(body))
        # 抓一下 displayname
        import re
        names = re.findall(r"<displayname>([^<]*)</displayname>", body)
        print("displaynames:", names[:10])
    # GET 一个文件
    req2 = urllib.request.Request(f"http://{BASE}:{WEBDAV_PORT}/multi_files/sample-5s.mp4")
    req2.add_header("Authorization", f"Basic {auth}")
    with urllib.request.urlopen(req2, timeout=5) as r2:
        data = r2.read(64)
        print("GET sample-5s.mp4 status:", r2.status, "first bytes:", len(data))
    print("WebDAV OK\n")
except Exception as e:
    print("WebDAV FAIL:", repr(e), "\n")
