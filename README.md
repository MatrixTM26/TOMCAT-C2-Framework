<div align="center">
    <img src="doc/pages/public/logo.png" width="50%" height="auto" alt="TOMCAT-C2 WEB UI">
</div>

# TOMCAT-C2-Framework

![AGPL](https://img.shields.io/badge/AGPL-v3-000000?style=for-the-badge&logo=gnu&logoColor=ffffff&labelColor=000000&color=03001a)
![OpenJDK](https://img.shields.io/badge/OpenJDK-17+-000000?style=for-the-badge&logo=openjdk&logoColor=ffee1a&labelColor=000000&color=03001a)
![Red Teaming](https://img.shields.io/badge/RED%20TEAMING-000000?style=for-the-badge&logo=keepassxc&logoColor=ff0000&labelColor=000000&color=03001a)
![Cyber Security](https://img.shields.io/badge/CYBER%20SECURITY-000000?style=for-the-badge&logo=socket&logoColor=009ceb&labelColor=000000&color=03001a)
![Cryptography](https://img.shields.io/badge/CRYPTOGRAPHY-000000?style=for-the-badge&logo=letsencrypt&logoColor=0eff39&labelColor=000000&color=03001a)
![Maven](https://img.shields.io/badge/Maven-000000?style=for-the-badge&logo=apachemaven&logoColor=ee6a2a&labelColor=000000&color=03001a)
![Networking](https://img.shields.io/badge/Networking-000000?style=for-the-badge&logo=cloudflare&logoColor=26ff7d&labelColor=000000&color=03001a)

> **_Author:_** _MatrixTM26_ **_GitHub:_** _[MatrixTM26](https://github.com/MatrixTM26)_

---

## <img src="https://raw.githubusercontent.com/FortAwesome/Font-Awesome/6.x/svgs/solid/circle-info.svg" width="18"> Overview

TOMCAT C2 is a modular, enterprise-grade Command & Control framework written in Java. It supports multiple interface modes (Web, CLI, JavaFX GUI), mutual TLS authentication using PKCS12 keystores, AES-256-GCM encrypted agent communication, and multi-protocol session handling.

### WEB UI

<div align="center">
    <img src="doc/pages/public/w1.png" width="100%" height="auto" alt="TOMCAT-C2 WEB UI">
    <hr />
    <img src="doc/pages/public/w2.png" width="100%" height="auto" alt="TOMCAT-C2 WEB UI">
    <hr />
    <img src="doc/pages/public/w3.png" width="100%" height="auto" alt="TOMCAT-C2 WEB UI">
    <hr />
    <img src="doc/pages/public/w4.png" width="100%" height="auto" alt="TOMCAT-C2 WEB UI">
    <hr />
    <img src="doc/pages/public/w5.png" width="100%" height="auto" alt="TOMCAT-C2 WEB UI">
    <hr />
    <img src="doc/pages/public/w6.png" width="100%" height="auto" alt="TOMCAT-C2 WEB UI">
</div>

---

## <img src="https://cdn.simpleicons.org/gnubash/ff0000" width="18"> Features

- **Multi-Interface Support** — Web Panel (HTTP), CLI, JavaFX GUI
- **AES-256-GCM Encryption** — All agent communication is encrypted end-to-end
- **Mutual TLS (mTLS)** — Agent authentication via PKCS12 certificates
- **Multi-Protocol Sessions** — TOMCAT agents, Meterpreter, Reverse Shells
- **Certificate Manager** — Full CA, server, and agent cert lifecycle management
- **File Transfer** — Upload and download files to/from agents
- **Session Management** — Thread-safe concurrent session handling
- **Event System** — Decoupled event-driven architecture
- **Cross-Platform** — Runs on Windows, Linux, macOS via JVM
- **Configurable** — All settings via `server.properties`

---

## <img src="https://cdn.simpleicons.org/gnubash/ff0000" width="18"> Installation & Usage

### 1. Clone the Repository

- MAIN
    > For normal usage, clone branch main

```bash
git clone --branch main https://github.com/MatrixTM26/TOMCAT-C2-Framework
cd TOMCAT-C2-Framework
```

- DEV
    > For contribution commit, pull request and development, push to branch dev

```bash
git clone --branch dev https://github.com/MatrixTM26/TOMCAT-C2-Framework
cd TOMCAT-C2-Framework
```

- MASTER
    > Only for owner/admin commit, pull request and development

### 2. Build the Project

#### Ready to use (Already compiled)

> Ready to use build (created by github action and ready to run file). located at `output/tomcat-c2.jar`

```bash
java -jar output/tomcat-c2.jar
```

[![Download JAR](https://img.shields.io/badge/Download%20Latest%20JAR-000000?style=for-the-badge&logo=electron&logoColor=07ff18&labelColor=000000&color=03001a)](https://github.com/MatrixTM26/TOMCAT-C2-Framework/releases/latest/download/tomcat-c2.jar)

#### Manual compile

> General Build

```bash
mvn clean package -q
```

> Specific Build

- **Linux & Termux**

    ```bash
    mvn clean package -Djavafx.platform=linux -q
    ```

- **Windows**

    ```bash
    mvn clean package -Djavafx.platform=windows -q
    ```

- **MacOS**

    ```bash
    mvn clean package -Djavafx.platform=macos -q
    ```

- **BSD**
    ```bash
    mvn clean package -Djavafx.platform=openbsd -q
    ```

### 3. Run the Server

```bash
# Web Panel Mode (Default)
java -jar target/tomcat-c2.jar

# CLI Mode
java -jar target/tomcat-c2.jar -C

# JavaFX GUI Mode
java -jar target/tomcat-c2.jar -G
```

---

## <img src="https://raw.githubusercontent.com/FortAwesome/Font-Awesome/6.x/svgs/solid/key.svg" width="18"> Certificate Management (MTLS)

### Initialize CA and Server Certificate

```bash
java -jar target/tomcat-c2.jar --init-certs
```

### Generate Agent Certificates

```bash
# Single Agent
java -jar target/tomcat-c2.jar \
  -a myagent -ah 192.168.1.10 -ap 4444 -am

# Multiple Agents
java -jar target/tomcat-c2.jar \
  -m -c 10 -u team -ah 192.168.1.10 -ap 4444 -am
```

---

## <img src="https://cdn.simpleicons.org/gnubash/ff0000" width="18"> Command Line Arguments

| Option                  | Description                               |
| ----------------------- | ----------------------------------------- |
| `-S, --host <addr>`     | C2 server bind address (default: 0.0.0.0) |
| `-p, --port <port>`     | Web panel port (default: 5000)            |
| `-T, --mtls`            | Enable Mutual TLS authentication          |
| `-M, --meterpreter`     | Enable multi-protocol mode                |
| `-C, --cli-mode`        | Start in CLI interface mode               |
| `-G, --gui-mode`        | Start in JavaFX GUI mode                  |
| `--init-certs`          | Initialize CA and server certificates     |
| `-a, --gen-agent <id>`  | Generate single agent certificate         |
| `-m, --gen-multi-agent` | Generate multiple agent certificates      |
| `-l, --list-agents`     | List all generated agents                 |

---

## <img src="https://raw.githubusercontent.com/FortAwesome/Font-Awesome/6.x/svgs/solid/circle-info.svg" width="18"> Interface Modes

- **Web Panel** — Access via browser at `http://localhost:5000`
- **CLI Mode** — Powerful terminal interface (`-C`)
- **JavaFX GUI** — Full desktop application with sidebar navigation (`-G`)

---

## <img src="https://raw.githubusercontent.com/FortAwesome/Font-Awesome/6.x/svgs/solid/shield-halved.svg" width="18"> Security Features

- **AES-256-GCM** encryption for all agent communication
- **Mutual TLS (mTLS)** with PKCS12 keystores
- Full certificate lifecycle management (CA → Server → Agent)

---

## <img src="https://cdn.simpleicons.org/readme/ff0000" width="18"> Documentation

- **Open:** [Sites](https://matrixtm26.github.io/TOMCAT-C2-Framework)

## <img src="https://cdn.simpleicons.org/github/ff0000" width="18"> Credit

- **Author:** [@MatrixTM26](https://github.com/MatrixTM26)
- **License:** [AGPL-V3](./LICENSE)

## <img src="https://cdn.simpleicons.org/githubsponsors/ff0000" width="18"> Support Me

[![Ko-fi](https://img.shields.io/badge/KO--FI-000000?style=for-the-badge&logo=kofi&logoColor=fff707)](https://ko-fi.com/MatrixTM26)
[![Trakteer](https://img.shields.io/badge/TRAKTEER-000000?style=for-the-badge&logo=buymeacoffee&logoColor=ff6a6a)](https://trakteer.id/MatrixTM26)
[![PayPal](https://img.shields.io/badge/PAYPAL-000000?style=for-the-badge&logo=paypal&logoColor=0000ff)](https://paypal.me/TeukuMaulana)

---

<p align="center">Copyright &copy;2023-2026 MatrixTM26 &middot; All Rights Reserved</p>
