# -*- coding: utf-8 -*-
"""
WebDAV 文件共享服务（基于 wsgidav）。
- 端口：基准端口+3（默认 10003）
- 账号：testuser / testpass（同时允许匿名）
- 共享根目录：share_files
- 支持 PROPFIND / GET / PUT 等 WebDAV 方法
"""
import logging
from wsgiref.simple_server import WSGIServer, WSGIRequestHandler

from wsgidav.wsgidav_app import WsgiDAVApp

from common import SHARE_ROOT, USERNAME, PASSWORD

log = logging.getLogger("webdav")


class QuietHandler(WSGIRequestHandler):
    """减少每个请求都打一行 access log。"""
    def log_message(self, format, *args):  # noqa: A002
        pass


def start_webdav(port, host="0.0.0.0"):
    config = {
        "host": host,
        "port": port,
        "provider_mapping": {"/": SHARE_ROOT},
        "http_authenticator": {
            "accept_basic": True,
            "accept_digest": False,
            "default_to_digest": False,
        },
        "simple_dc": {
            "user_mapping": {
                "*": {
                    USERNAME: {"password": PASSWORD, "roles": ["editor"]},
                    "anonymous": True,
                }
            }
        },
        "dir_browser": {"enable": True},
    }

    app = WsgiDAVApp(config)
    server = WSGIServer((host, port), QuietHandler)
    server.set_app(app)
    log.info("WebDAV 监听 http://%s:%d/  根目录=%s", host, port, SHARE_ROOT)
    # serve_forever 阻塞，放在子线程里跑
    server.serve_forever()
    return server


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from common import get_base_port
    start_webdav(get_base_port() + 3)
