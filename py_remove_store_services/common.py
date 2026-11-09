# -*- coding: utf-8 -*-
"""
公共配置：四个文件共享服务共用的账号、共享根目录等常量。
"""
import os

# 共享根目录：所有协议都把 share_files 对外暴露为根
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SHARE_ROOT = os.path.join(BASE_DIR, "share_files")

# 统一账号密码（FTP/WebDAV 同时允许匿名；SSH/SFTP/SMB 用账号密码）
USERNAME = "testuser"
PASSWORD = "testpass"

# 基准端口：FTP=BASE_PORT, SSH=BASE_PORT+1, SMB=BASE_PORT+2, WebDAV=BASE_PORT+3
DEFAULT_BASE_PORT = 10000

# SSH 主机密钥文件（首次启动自动生成）
HOST_KEY_FILE = os.path.join(BASE_DIR, "host_key")


def get_base_port():
    """读取基准端口：优先命令行参数 --port / -p，其次环境变量 BASE_PORT，最后默认 10000。"""
    import sys
    for i, arg in enumerate(sys.argv):
        if arg in ("--port", "-p") and i + 1 < len(sys.argv):
            try:
                return int(sys.argv[i + 1])
            except ValueError:
                pass
    env = os.environ.get("BASE_PORT")
    if env:
        try:
            return int(env)
        except ValueError:
            pass
    return DEFAULT_BASE_PORT
