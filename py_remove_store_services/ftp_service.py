# -*- coding: utf-8 -*-
"""
FTP 文件共享服务（基于 pyftpdlib）。
- 端口：基准端口（默认 10000）
- 账号：testuser / testpass（可读写）
- 同时允许匿名只读访问
- 共享根目录：share_files
"""
import logging
import os

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

from common import SHARE_ROOT, USERNAME, PASSWORD

log = logging.getLogger("ftp")


def start_ftp(port, host="0.0.0.0"):
    authorizer = DummyAuthorizer()
    # 授权用户：读写权限（elradfmwMT）
    authorizer.add_user(USERNAME, PASSWORD, SHARE_ROOT, perm="elradfmwMT")
    # 匿名只读（el = 列目录 + 下载）
    authorizer.add_anonymous(SHARE_ROOT, perm="el")

    handler = FTPHandler
    handler.authorizer = authorizer
    handler.banner = "mpvKt test FTP server ready."
    # 被动模式端口范围（供 Android 客户端数据连接）
    handler.passive_ports = range(20000, 20010)

    server = FTPServer((host, port), handler)
    server.max_cons = 32
    server.max_cons_per_ip = 8
    log.info("FTP 监听 %s:%d  根目录=%s", host, port, SHARE_ROOT)
    # serve_forever 会阻塞，放在子线程里跑
    server.serve_forever()
    return server


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from common import get_base_port
    start_ftp(get_base_port())
