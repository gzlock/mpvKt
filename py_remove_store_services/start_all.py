# -*- coding: utf-8 -*-
"""
一键启动全部四个文件共享服务：
  FTP     -> BASE_PORT     (默认 10000)
  SSH/SFTP-> BASE_PORT + 1 (默认 10001)
  SMB     -> BASE_PORT + 2 (默认 10002)
  WebDAV  -> BASE_PORT + 3 (默认 10003)

用法：
  python start_all.py                # 默认 10000 起
  python start_all.py --port 20000   # 从 20000 起
  set BASE_PORT=30000 && python start_all.py

Ctrl+C 优雅退出。
"""
import logging
import os
import sys
import threading
import time

from common import (
    SHARE_ROOT,
    USERNAME,
    PASSWORD,
    get_base_port,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("launcher")


def main():
    base = get_base_port()
    ftp_port = base
    ssh_port = base + 1
    smb_port = base + 2
    webdav_port = base + 3

    print("=" * 64)
    print(" mpvKt 远程存储测试服务 - 端口映射表")
    print("=" * 64)
    print(f"  FTP      : ftp://<host>:{ftp_port}    账号 {USERNAME} / {PASSWORD}（匿名可读）")
    print(f"  SSH/SFTP : ssh://<host>:{ssh_port}    账号 {USERNAME} / {PASSWORD}")
    print(f"  SMB      : \\\\<host>\\{ 'share' } 端口 {smb_port}  账号 {USERNAME} / {PASSWORD}")
    print(f"  WebDAV   : http://<host>:{webdav_port}/   账号 {USERNAME} / {PASSWORD}（匿名可读）")
    print("-" * 64)
    print(f"  共享根目录: {SHARE_ROOT}")
    print("  Android 模拟器里 <host> 填 10.0.2.2；真机连电脑局域网 IP。")
    print("  按 Ctrl+C 停止全部服务。")
    print("=" * 64)

    threads = []

    # 1) FTP
    try:
        import ftp_service
        t = threading.Thread(target=ftp_service.start_ftp, args=(ftp_port,), daemon=True, name="ftp")
        t.start()
        threads.append(("FTP", t))
    except Exception as e:  # noqa: BLE001
        log.error("FTP 启动失败: %s", e)

    time.sleep(0.5)

    # 2) SSH/SFTP
    try:
        import ssh_service
        t = threading.Thread(target=ssh_service.start_ssh, args=(ssh_port,), daemon=True, name="ssh")
        t.start()
        threads.append(("SSH", t))
    except Exception as e:  # noqa: BLE001
        log.error("SSH 启动失败: %s", e)

    time.sleep(0.5)

    # 3) SMB
    try:
        import smb_service
        t = threading.Thread(target=smb_service.start_smb, args=(smb_port,), daemon=True, name="smb")
        t.start()
        threads.append(("SMB", t))
    except Exception as e:  # noqa: BLE001
        log.error("SMB 启动失败: %s", e)

    time.sleep(0.5)

    # 4) WebDAV
    try:
        import webdav_service
        t = threading.Thread(target=webdav_service.start_webdav, args=(webdav_port,), daemon=True, name="webdav")
        t.start()
        threads.append(("WebDAV", t))
    except Exception as e:  # noqa: BLE001
        log.error("WebDAV 启动失败: %s", e)

    print(f"\n[OK] 已启动 {len(threads)}/4 个服务，按 Ctrl+C 退出。\n")

    # 主线程挂住，等待 Ctrl+C
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n收到 Ctrl+C，正在关闭所有服务 ...")
        # daemon 线程会随主进程退出一起结束
        time.sleep(0.5)
        print("已退出。")
        sys.exit(0)


if __name__ == "__main__":
    main()
