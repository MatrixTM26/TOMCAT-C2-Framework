# TOMCAT C2 Framework - Enterprise-Grade Engineering Blueprint

This document specifies the advanced architectural specifications and complex engineering requirements to upgrade the TOMCAT C2 Framework into a mature, resilient, and enterprise-ready Red Teaming infrastructure.

---

## 1. Native Database Pooling & Granular RBAC Engine

To guarantee zero-dependency deployment and extreme transaction throughput without third-party connection managers, the storage layer must employ low-level concurrency primitives.

### Action Items:

- [ ] **Thread-Safe Connection Pool Core:**
    - Implement an explicit `java.util.concurrent.ArrayBlockingQueue<Connection>` initialized deterministically at system startup.
    - Develop an automated **Health-Check Engine** using a dedicated daemon background thread that continuously validates pooled connections via non-blocking queries (`connection.isValid(timeout)`). Broken pipes must be dropped and seamlessly re-allocated natively.
    - Enforce a strict strict lease-and-release pattern with explicit timeout limits to prevent thread starvation and application deadlocks.
- [ ] **Bitmask / Matrix-Based RBAC & Schema Enforcement:**
    - Design and deploy a strict PostgreSQL relational database structure separating `operators`, `roles`, and `permissions`.
    - Enforce a programmatic validation layer checking user capability bitmasks on every single inbound state change requests:
        - **SUPERADMIN:** `0x0F` (`READ` | `WRITE` | `EXEC` | `KILL_OPERATOR`)
        - **ADMIN:** `0x07` (`READ` | `WRITE` | `EXEC`)
        - **MODERATOR:** `0x03` (`READ` | `WRITE`)
- [ ] **Append-Only Immutable Auditing:**
    - Implement an asynchronous logging subsystem using a non-blocking consumer pattern (`LinkedBlockingQueue`). Every console input or payload delivery must be captured, cryptographically hashed, and written into a PostgreSQL append-only audit table with microsecond-precision timestamps mapped to `ZonedDateTime.now(ZoneId.of("Asia/Jakarta"))`.

---

## 2. Multi-Threaded Reactive Team Server & Multi-Operator Orchestration

An enterprise team server must function as a high-availability centralized controller capable of synchronizing states across multiple operators simultaneously.

### Action Items:

- [ ] **Asynchronous Event Loop Architecture:**
    - Migrate the listening engine from traditional Blocking I/O (`BIO`) to Java Non-blocking I/O (`NIO`) utilizing `java.nio.channels.Selector` and `SocketChannel`. This allows a single thread to multiplex thousands of active agent connections efficiently.
- [ ] **State Synchronization Framework:**
    - Implement custom data transfer objects serialized over a secure communication layer (e.g., custom encapsulated native sockets or internal streaming pipelines) to instantly broadcast agent status changes, new check-ins, and command outputs to all connected operators in real-time.
- [ ] **Cryptographic Session Token Management:**
    - Develop a native token generation engine using `java.security.SecureRandom`. Operator sessions must be authenticated via time-bound, cryptographically signed tokens containing role claims that are validated by server-side interceptors on every interaction loop.

---

## 3. Advanced Malleable Protocol Evasion & Jitter Orchestration

Continuous network streams or static patterns are instantly categorized as malicious by modern Network Detection and Response (NDR) appliances. Traffic must blend in completely with standard corporate patterns.

### Action Items:

- [ ] **Malleable C2 Profiling Engine:**
    - Build a custom parsing engine that reads external configuration profiles. The Team Server and Agent must dynamically adapt their communication interfaces based on these definitions (e.g., transforming communication to mimic legitimate JSON APIs, SOAP XML requests, or standard file-download structures).
- [ ] **Advanced Timing Jitter (Mathematical Distribution):**
    - Discard standard static sleep functions. Implement a dynamic randomized sleep engine leveraging a Gaussian/Normal distribution formula or randomized deviation bounds:
      $$\text{NextCallback} = \text{BaseInterval} \times \left(1 \pm \left(\text{SecureRandom} \times \frac{\text{JitterPercentage}}{100}\right)\right)$$
    - _Why:_ Completely neutralizes time-series heuristics and frequency analysis algorithms utilized by advanced SIEM platforms.
- [ ] **Multilayer Session Hybridization:**
    - Design the agent to switch dynamically between protocols. For instance, the agent can use high-frequency HTTP/HTTPS polling profiles for normal status heartbeats, but automatically spin up an independent, ephemeral Mutual-TLS (mTLS) socket tunnel when a continuous interactive full shell is required.

---

## 4. Decentralized Infrastructure Redundancy & Intelligent Failover

An implant must survive even if parts of the backend listening infrastructure are completely severed, blacklisted, or isolated by incident responders.

### Action Items:

- [ ] **Finite State Machine (FSM) Network Router:**
    - Program the agent connection manager as a rigorous FSM containing clear operational states: `STATE_PRIMARY_CHECKIN`, `STATE_FAILOVER_ROTATION`, `STATE_BACKOFF_SLEEP`, and `STATE_EMERGENCY_DGA`.
- [ ] **Dynamic Network Rotation Arrays:**
    - Store multiple redundant callback endpoints using structurally segregated layouts (e.g., mixing primary domains, cloud CDN edge paths, and raw disaster-recovery IP strings).
    - Upon intercepting `SocketTimeoutException` or `ConnectException`, the FSM must seamlessly execute index rotation logic while verifying that memory variables remain uncorrupted.
- [ ] **Exponential Backoff and Jittered Standby Deep Sleep:**
    - Implement a strict mathematical progression backoff algorithm. If all configured endpoints fail an operational circuit cycle, the sleep time increases exponentially up to a hard ceiling limit (e.g., checking in only once every 12 or 24 hours).
    - _Why:_ Keeps the implant completely silent inside memory during extended infrastructure outages, evading blue-team hunting sweeps.
- [ ] **Cryptographic Domain Generation Algorithm (DGA):**
    - Write a native DGA that computes daily emergency callback domains using an asymmetric seed value, a specific hash operation (such as SHA-256), and the current synchronized calendar date.

---

## 5. Defensive OpSec Hardening & Advanced Polymorphic In-Memory Protection

Java bytecode is historically vulnerable to immediate decompilation. Enterprise agents must be deeply hardened to hide their logic from forensic reverse engineering.

### Action Items:

- [ ] **Automated Build-Time Obfuscation Pipeline:**
    - Integrate industrial-grade bytecode manipulation into the build automation flow (`gradle` or kustom `install.sh`). The pipeline must execute deep identifier renaming, heavy flow obfuscation, control-flow flattening, and aggressive dead-code insertion.
- [ ] **Asymmetric Heap-String Encryption:**
    - All critical asset references (such as C2 domains, internal shell variables, and PKCS12 credentials) must be stored as encrypted byte arrays in the compiled biner.
    - _Requirement:_ Implement compile-time AES-GCM or dynamic multi-byte XOR masking. Strings must only exist as unmasked plain text in the heap memory during the exact execution millisecond, after which they must be immediately overwritten with null bytes.
- [ ] **Fileless Self-Packaging via Minimal Custom Runtime Images:**
    - Utilize `jlink` combined with strict modularization configurations (`module-info.java`) to construct ultra-stripped custom execution images.
    - Strip all non-essential debug symbols using `--strip-debug`, minimize data volumes using `--compress=2`, and strip C-headers to prevent any platform identity exposure. The runtime package should be easily deployable to memory-only structures or clean temporary paths.

---

## 6. Enterprise Red Team Capability Extensions

- [ ] **Chunked Asynchronous Encrypted File Exfiltration:**
    - Develop a streaming file upload/download engine. Large files must be split into small data chunks, compressed natively using `java.util.zip`, encrypted independently over the mTLS tunnel, and reconstructed at the server side to maintain low RAM usage.
- [ ] **Passive Host Environment Profiling:**
    - Implement an autonomous situational awareness module that queries target environment properties (such as OS build variations, running processes, active defense agents, and local network topologies) via stealthy native calls or clean system APIs before initiating core operations.
