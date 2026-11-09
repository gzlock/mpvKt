# -*- coding: utf-8 -*-
"""
SMB 文件共享服务（基于 impacket 的 SimpleSMBServer）。
- 端口：基准端口+2（默认 10002）
- 账号：testuser / testpass（同时允许匿名/guest 登录）
- 共享名：share
- 共享根目录：share_files
注意：impacket 自带的 SMB 服务端实现 SMB2 协商，但部分 Android SMB 客户端（如 smbj）
      可能因签名/加密协商不完全兼容。如连不上，请在 README 列出的限制里排查。
"""
import logging

from impacket.smbserver import SimpleSMBServer
from impacket.ntlm import compute_lmhash, compute_nthash

from common import SHARE_ROOT, USERNAME, PASSWORD

log = logging.getLogger("smb")

SHARE_NAME = "share"


def start_smb(port, host="0.0.0.0"):
    server = SimpleSMBServer(listenAddress=host, listenPort=port)

    # 映射共享目录
    server.addShare(SHARE_NAME, SHARE_ROOT, "mpvKt test share", readOnly="no")

    # 注入账号密码（impacket 内部用 NTLM 哈希校验）
    raw_server = server.getServer()
    lm_hash = compute_lmhash(PASSWORD)
    nt_hash = compute_nthash(PASSWORD)
    # addCredential(name, uid, lmhash, nthash)
    raw_server.addCredential(USERNAME, "", lm_hash.hex(), nt_hash.hex())

    log.info("SMB 监听 %s:%d  共享名=%s  根目录=%s", host, port, SHARE_NAME, SHARE_ROOT)
    # serve_forever 阻塞，放在子线程里跑
    server.start()
    return server


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from common import get_base_port
    start_smb(get_base_port() + 2)
