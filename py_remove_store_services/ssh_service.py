# -*- coding: utf-8 -*-
"""
SSH / SFTP 文件共享服务（基于 paramiko）。
- 端口：基准端口+1（默认 10001）
- 账号：testuser / testpass
- 同时支持：
  (a) SFTP 子系统：可浏览/上传/下载 share_files 里的文件；
  (b) exec 通道：客户端执行命令时，用 subprocess 真正执行（Windows shell=True，支持 dir 等命令），
      并回传 stdout / stderr / 退出码。
- 首次启动自动生成 RSA 主机密钥 host_key，之后复用。
"""
import logging
import os
import socket
import subprocess
import threading
import posixpath

import paramiko
from paramiko import SFTPServer
from paramiko.sftp import SFTP_OK, SFTP_NO_SUCH_FILE, SFTP_FAILURE, SFTP_PERMISSION_DENIED
from paramiko.sftp_handle import SFTPHandle
from paramiko.sftp_attr import SFTPAttributes

from common import SHARE_ROOT, USERNAME, PASSWORD, HOST_KEY_FILE

log = logging.getLogger("ssh")

# 只允许指定账号登录
ALLOWED_USERS = {USERNAME: PASSWORD}


def _map_path(path):
    """把 SFTP 协议路径（posix，以 / 开头）映射到本机 share_files 下的真实路径，防逃逸。"""
    if not path or path == ".":
        path = "/"
    p = posixpath.normpath(path)
    if p == ".":
        p = "/"
    if not p.startswith("/"):
        p = "/" + p
    real = os.path.normpath(os.path.join(SHARE_ROOT, p.lstrip("/")))
    root = os.path.normpath(SHARE_ROOT)
    if not (real == root or real.startswith(root + os.sep)):
        real = root
    return real


def _sftp_abspath(path):
    """把任意 SFTP 路径规范化为以 / 开头的 POSIX 绝对路径（仅协议路径，不映射到本地文件系统）。"""
    if not path or path == ".":
        return "/"
    p = posixpath.normpath(path)
    if p == ".":
        return "/"
    if not p.startswith("/"):
        p = "/" + p
    return p


class _FileHandle(SFTPHandle):
    """包装一个本地文件句柄，供 SFTP 读写。"""

    def __init__(self, file):
        super().__init__()
        self.file = file

    def close(self):
        self.file.close()

    def read(self, offset, length):
        self.file.seek(offset)
        return self.file.read(length)

    def write(self, offset, data):
        self.file.seek(offset)
        self.file.write(data)
        return len(data)

    def stat(self):
        try:
            st = os.fstat(self.file.fileno())
            return SFTPAttributes.from_stat(st)
        except OSError:
            return SFTP_FAILURE


class SFTPInterface(paramiko.SFTPServerInterface):
    """把 SFTP 客户端的路径映射到本机 share_files 目录。"""

    def canonicalize(self, path):
        # 必须回传 POSIX 风格的 SFTP 绝对路径（如 "/"、"/multi_files"），
        # 不能返回 _map_path 得到的本机 Windows 路径，否则 JSch 等客户端会据此拼出错误路径。
        return _sftp_abspath(path)

    def list_folder(self, path):
        real = _map_path(path)
        try:
            out = []
            for name in os.listdir(real):
                full = os.path.join(real, name)
                st = os.lstat(full)
                attr = SFTPAttributes.from_stat(st)
                attr.filename = name
                out.append(attr)
            return out
        except OSError:
            return SFTP_NO_SUCH_FILE

    def stat(self, path):
        real = _map_path(path)
        try:
            return SFTPAttributes.from_stat(os.stat(real))
        except OSError:
            return SFTP_NO_SUCH_FILE

    def lstat(self, path):
        real = _map_path(path)
        try:
            return SFTPAttributes.from_stat(os.lstat(real))
        except OSError:
            return SFTP_NO_SUCH_FILE

    def open(self, path, flags, attr):
        real = _map_path(path)
        try:
            f = os.open(real, flags, 0o666)
            # 根据 flags 决定打开模式
            if flags & os.O_RDWR:
                mode = "r+b"
            elif flags & os.O_WRONLY:
                mode = "wb"
            else:
                mode = "rb"
            return _FileHandle(os.fdopen(f, mode))
        except OSError:
            return SFTP_PERMISSION_DENIED

    def remove(self, path):
        real = _map_path(path)
        try:
            os.remove(real)
            return SFTP_OK
        except OSError:
            return SFTP_FAILURE

    def rename(self, oldpath, newpath):
        old = _map_path(oldpath)
        new = _map_path(newpath)
        try:
            os.rename(old, new)
            return SFTP_OK
        except OSError:
            return SFTP_FAILURE

    def mkdir(self, path, attr):
        real = _map_path(path)
        try:
            os.mkdir(real)
            return SFTP_OK
        except OSError:
            return SFTP_FAILURE

    def rmdir(self, path):
        real = _map_path(path)
        try:
            os.rmdir(real)
            return SFTP_OK
        except OSError:
            return SFTP_FAILURE

    def chattr(self, path, attr):
        # 测试用，简化处理
        return SFTP_OK

    def readlink(self, path):
        return SFTP_FAILURE

    def symlink(self, target_path, path):
        return SFTP_FAILURE


class SSHServer(paramiko.ServerInterface):
    # ---- 认证 ----
    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        if ALLOWED_USERS.get(username) == password:
            return paramiko.AUTH_SUCCESSFUL
        log.warning("SSH 认证失败: user=%r", username)
        return paramiko.AUTH_FAILED

    # ---- 通道 ----
    def check_channel_request(self, kind, chanid):
        if kind == "session":
            return paramiko.OPEN_SUCCEEDED
        return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    # ---- exec 命令：真正执行系统命令 ----
    def check_channel_exec_request(self, channel, command):
        # paramiko 5.x 传过来的 command 是 bytes
        if isinstance(command, bytes):
            command = command.decode("utf-8", errors="replace")
        log.info("收到 exec 请求: %r", command)

        def run():
            try:
                proc = subprocess.run(
                    command,
                    shell=True,           # Windows 下支持 dir / echo 等内建命令
                    capture_output=True,
                    text=True,
                    cwd=SHARE_ROOT,       # 在共享根目录执行
                    timeout=60,
                    encoding="gbk",       # Windows 中文命令输出默认 GBK
                    errors="replace",
                )
                if proc.stdout:
                    channel.send(proc.stdout)
                if proc.stderr:
                    channel.send(proc.stderr)
                channel.send_exit_status(proc.returncode)
            except Exception as e:  # noqa: BLE001
                try:
                    channel.send("server error: %s\n" % e)
                    channel.send_exit_status(1)
                except Exception:  # noqa: BLE001
                    pass
            finally:
                channel.close()

        threading.Thread(target=run, daemon=True).start()
        return True


def _load_or_create_host_key():
    if os.path.exists(HOST_KEY_FILE):
        return paramiko.RSAKey(filename=HOST_KEY_FILE)
    key = paramiko.RSAKey.generate(2048)
    key.write_private_key_file(HOST_KEY_FILE)
    log.info("已生成新的 SSH 主机密钥: %s", HOST_KEY_FILE)
    return key


def _handle_connection(client_sock, addr):
    transport = None
    try:
        transport = paramiko.Transport(client_sock)
        transport.add_server_key(_load_or_create_host_key())
        # 注册 sftp 子系统处理器（paramiko 会在收到 subsystem 请求时自动实例化）
        transport.set_subsystem_handler("sftp", SFTPServer, sftp_si=SFTPInterface)
        transport.start_server(server=SSHServer())
        # 挂住直到 transport 关闭
        transport.join()
    except Exception as e:  # noqa: BLE001
        log.debug("SSH 连接处理异常: %s", e)
    finally:
        try:
            if transport:
                transport.close()
        except Exception:  # noqa: BLE001
            pass


def start_ssh(port, host="0.0.0.0"):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))
    sock.listen(20)
    sock.settimeout(1.0)  # 让 accept 每秒醒来一次，便于 Ctrl+C 退出
    log.info("SSH/SFTP 监听 %s:%d  根目录=%s", host, port, SHARE_ROOT)

    while True:
        try:
            client, addr = sock.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        threading.Thread(target=_handle_connection, args=(client, addr), daemon=True).start()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from common import get_base_port
    start_ssh(get_base_port() + 1)
