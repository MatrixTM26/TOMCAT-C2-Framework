// —————————————————————————————————————————————————————————————————————————————
// TOMCAT C2 Framework — Frontend Script
// Supports both WebApp (no-auth) and TeamServer (Bearer token auth)
// —————————————————————————————————————————————————————————————————————————————

const IS_TEAMSERVER = (function () {
    try {
        return !!localStorage.getItem("tc2_mode")
            ? localStorage.getItem("tc2_mode") === "teamserver"
            : false;
    } catch (e) {
        return false;
    }
})();

const state = {
    activeSection: "dashboard",
    serverRunning: false,
    serverHost: "0.0.0.0",
    serverPort: 4444,
    serverAddress: "",
    sessionKey: "",
    uptime: "",
    serverStartedAt: null,
    agentList: [],
    selectedAgentId: null,
    cmdOutput: [],
    logs: [],
    lastLogCount: 0,
    lastScrollY: 0,
    startBusy: false,
    token: null,
    operator: null,
    role: null
};

let pollInterval = null;
let clockInterval = null;
let uptimeInterval = null;

const pageTitles = {
    dashboard: "Dashboard",
    server: "Server Config",
    agents: "Agents",
    command: "Console",
    logs: "Logs",
    about: "About",
    team: "Team"
};

const quickCmds = [
    { cmd: "SYSINFO", icon: "fas fa-info", label: "Sys Info" },
    { cmd: "ls -la", icon: "fas fa-folder-open", label: "List Files" },
    { cmd: "ifconfig", icon: "fas fa-network-wired", label: "Network" },
    { cmd: "whoami", icon: "fas fa-user-circle", label: "User Info" },
    { cmd: "ps aux", icon: "fas fa-tasks", label: "Processes" },
    { cmd: "SCREENSHOT", icon: "fas fa-camera", label: "Screenshot" },
    { cmd: "ELEVATE", icon: "fas fa-arrow-up", label: "Elevate" }
];

// —— Auth helpers —————————————————————————————————————————————————————————————

function LoadToken() {
    try {
        state.token = localStorage.getItem("tc2_token");
        state.operator = localStorage.getItem("tc2_operator");
        state.role = localStorage.getItem("tc2_role");
    } catch (e) {}
}

function SaveToken(token, operator, role) {
    state.token = token;
    state.operator = operator;
    state.role = role;
    try {
        localStorage.setItem("tc2_token", token);
        localStorage.setItem("tc2_operator", operator);
        localStorage.setItem("tc2_role", role);
    } catch (e) {}
}

function ClearToken() {
    state.token = null;
    state.operator = null;
    state.role = null;
    try {
        localStorage.removeItem("tc2_token");
        localStorage.removeItem("tc2_operator");
        localStorage.removeItem("tc2_role");
    } catch (e) {}
}

function AuthHeaders(extra) {
    let h = Object.assign({ "Content-Type": "application/json" }, extra || {});
    if (state.token) h["Authorization"] = "Bearer " + state.token;
    return h;
}

// —— Core fetch wrapper ————————————————————————————————————————————————————————

async function Api(path, opts) {
    opts = opts || {};
    opts.headers = AuthHeaders(opts.headers);
    let r = await fetch(path, opts);
    if (r.status === 401) {
        ClearToken();
        ShowLogin("Session expired — please login again");
        throw new Error("Unauthorized");
    }
    return r;
}

// —— Login modal (TeamServer only) —————————————————————————————————————————————

function ShowLogin(msg) {
    let existing = document.getElementById("login-modal");
    if (existing) existing.remove();

    let overlay = document.createElement("div");
    overlay.id = "login-modal";
    overlay.style.cssText =
        "position:fixed;inset:0;background:rgba(10,12,18,0.93);display:flex;" +
        "align-items:center;justify-content:center;z-index:9999;";

    overlay.innerHTML = `
        <div style="background:#0f1621;border:1px solid rgba(45,212,160,0.25);border-radius:12px;
                    padding:40px 36px;width:340px;max-width:94vw;text-align:center;">
            <div style="font-family:'Tourney',sans-serif;font-size:22px;color:#2dd4a0;
                        letter-spacing:4px;margin-bottom:4px;">TOMCAT C2</div>
            <div style="font-size:11px;color:#475569;letter-spacing:2px;margin-bottom:28px;">
                TEAMSERVER — OPERATOR LOGIN
            </div>
            ${msg ? `<div style="color:#f87171;font-size:12px;margin-bottom:16px;">${escHtml(msg)}</div>` : ""}
            <input id="login-user" type="text" placeholder="Username"
                   style="width:100%;box-sizing:border-box;background:#1e2335;border:1px solid rgba(45,212,160,0.2);
                          border-radius:6px;padding:10px 14px;color:#e2e8f0;font-size:13px;margin-bottom:10px;outline:none;">
            <input id="login-pass" type="password" placeholder="Password"
                   style="width:100%;box-sizing:border-box;background:#1e2335;border:1px solid rgba(45,212,160,0.2);
                          border-radius:6px;padding:10px 14px;color:#e2e8f0;font-size:13px;margin-bottom:18px;outline:none;">
            <button id="login-btn" onclick="DoLogin()"
                    style="width:100%;background:#2dd4a0;color:#0a0c12;border:none;border-radius:6px;
                           padding:11px;font-weight:700;font-size:13px;cursor:pointer;letter-spacing:1px;">
                LOGIN
            </button>
            <div id="login-err" style="color:#f87171;font-size:12px;margin-top:12px;min-height:16px;"></div>
        </div>`;

    document.body.appendChild(overlay);

    let passEl = document.getElementById("login-pass");
    if (passEl)
        passEl.addEventListener("keydown", function (e) {
            if (e.key === "Enter") DoLogin();
        });
    let userEl = document.getElementById("login-user");
    if (userEl) {
        userEl.focus();
        userEl.addEventListener("keydown", function (e) {
            if (e.key === "Enter")
                document.getElementById("login-pass").focus();
        });
    }
}

async function DoLogin() {
    let userEl = document.getElementById("login-user");
    let passEl = document.getElementById("login-pass");
    let errEl = document.getElementById("login-err");
    let btn = document.getElementById("login-btn");
    if (!userEl || !passEl) return;
    let user = userEl.value.trim();
    let pass = passEl.value;
    if (!user || !pass) {
        if (errEl) errEl.textContent = "Enter username and password";
        return;
    }
    if (btn) {
        btn.disabled = true;
        btn.textContent = "Logging in...";
    }
    try {
        let r = await fetch("/api/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ Username: user, Password: pass })
        });
        let d = await r.json();
        if (d.Token) {
            SaveToken(d.Token, d.Username, d.Role);
            let modal = document.getElementById("login-modal");
            if (modal) modal.remove();
            addLog(
                "Logged in as " + d.Username + " [" + d.Role + "]",
                "success"
            );
            UpdateOperatorBadge();
            await BootstrapStatus();
        } else {
            if (errEl) errEl.textContent = d.Error || "Invalid credentials";
        }
    } catch (e) {
        if (errEl) errEl.textContent = "Connection error: " + e.message;
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = "LOGIN";
        }
    }
}

async function DoLogout() {
    try {
        await Api("/api/auth/logout", { method: "POST" });
    } catch (e) {}
    ClearToken();
    stopPolling();
    stopUptimeTicker();
    state.serverRunning = false;
    state.agentList = [];
    state.selectedAgentId = null;
    ShowLogin("Logged out");
}

function UpdateOperatorBadge() {
    let el = document.getElementById("operator-badge");
    let btn = document.getElementById("logout-btn");
    if (el) {
        if (state.operator) {
            el.style.display = "";
            el.textContent = state.operator + " [" + (state.role || "?") + "]";
        } else {
            el.style.display = "none";
        }
    }
    if (btn) btn.style.display = state.operator ? "" : "none";
}

// —— Utilities —————————————————————————————————————————————————————————————————

function escHtml(s) {
    return String(s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
}

function copyText(text, btnEl) {
    let done = function () {
        if (!btnEl) return;
        let orig = btnEl.innerHTML;
        btnEl.innerHTML = '<i class="fas fa-check"></i> Copied!';
        btnEl.disabled = true;
        setTimeout(function () {
            btnEl.innerHTML = orig;
            btnEl.disabled = false;
        }, 2000);
    };
    if (navigator.clipboard && window.isSecureContext)
        navigator.clipboard
            .writeText(text)
            .then(done)
            .catch(function () {
                fallbackCopy(text);
                done();
            });
    else {
        fallbackCopy(text);
        done();
    }
}

function fallbackCopy(text) {
    let ta = document.createElement("textarea");
    ta.value = text;
    ta.style.cssText = "position:fixed;opacity:0;top:0;left:0";
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try {
        document.execCommand("copy");
    } catch (e) {}
    document.body.removeChild(ta);
}

// —— Navigation ————————————————————————————————————————————————————————————————

function navigate(section) {
    state.activeSection = section;
    document.querySelectorAll(".section").forEach(function (el) {
        el.classList.remove("active");
    });
    let target = document.getElementById("section-" + section);
    if (target) target.classList.add("active");
    document.querySelectorAll("[data-nav]").forEach(function (el) {
        el.classList.toggle("active", el.dataset.nav === section);
    });
    let title = pageTitles[section] || "";
    ["mobile-title", "topbar-title"].forEach(function (id) {
        let el = document.getElementById(id);
        if (el) el.textContent = title;
    });
    if (typeof closeSidebar === "function") closeSidebar();
    if (section === "agents") updateTopology();
    if (section === "team") renderTeam();
}

// —— Clock & uptime ————————————————————————————————————————————————————————————

function updateClock() {
    let t = new Date().toLocaleTimeString("en-US", { hour12: false });
    ["topnav-clock", "mobile-clock"].forEach(function (id) {
        let el = document.getElementById(id);
        if (el) el.textContent = t;
    });
}

function tickUptime() {
    if (!state.serverStartedAt || !state.serverRunning) return;
    let elapsed = Math.floor(Date.now() / 1000 - state.serverStartedAt);
    let h = Math.floor(elapsed / 3600);
    let m = Math.floor((elapsed % 3600) / 60);
    let s = elapsed % 60;
    let display =
        String(h).padStart(2, "0") +
        ":" +
        String(m).padStart(2, "0") +
        ":" +
        String(s).padStart(2, "0");
    if (display !== state.uptime) {
        state.uptime = display;
        let el = document.getElementById("stat-uptime");
        if (el) el.textContent = display;
    }
    let now = Date.now();
    uptimeInterval = setTimeout(tickUptime, 1000 - (now % 1000) || 1000);
}

function startUptimeTicker() {
    if (uptimeInterval) {
        clearTimeout(uptimeInterval);
        uptimeInterval = null;
    }
    tickUptime();
}

function stopUptimeTicker() {
    if (uptimeInterval) {
        clearTimeout(uptimeInterval);
        uptimeInterval = null;
    }
    state.serverStartedAt = null;
    state.uptime = "";
    let el = document.getElementById("stat-uptime");
    if (el) el.textContent = "00:00:00";
}

// —— Server UI —————————————————————————————————————————————————————————————————

function updateServerBtns() {
    let running = state.serverRunning;
    document.querySelectorAll(".server-toggle").forEach(function (btn) {
        btn.classList.toggle("online", running);
    });
    let cardBtn = document.getElementById("server-toggle-btn");
    if (cardBtn)
        cardBtn.innerHTML = running
            ? '<i class="fas fa-stop"></i> Stop Server'
            : '<i class="fas fa-play"></i> Start Server';
}

function updateSphere() {
    let online = state.serverRunning;
    let wrap = document.getElementById("sphere-wrap");
    let pulse = document.querySelector(".sphere-pulse");
    if (wrap) wrap.classList.toggle("online", online);
    if (pulse) pulse.classList.toggle("active", online);
    let val = document.getElementById("sphere-val");
    if (val) {
        val.textContent = online ? "ONLINE" : "OFFLINE";
        val.classList.toggle("online", online);
    }
    let detail = document.getElementById("sphere-detail");
    if (detail)
        detail.textContent = online
            ? "Listening on " + state.serverAddress
            : "Server not running";
}

function updateStats() {
    let sv = document.getElementById("stat-server-status");
    if (sv) {
        sv.className =
            "stat-val " + (state.serverRunning ? "online" : "offline");
        sv.innerHTML =
            '<span class="dot' +
            (state.serverRunning ? " online" : "") +
            '"></span>' +
            (state.serverRunning ? "Online" : "Offline");
    }
    let agentsEl = document.getElementById("stat-agents");
    if (agentsEl) agentsEl.textContent = state.agentList.length;
    let connEl = document.getElementById("stat-connections");
    if (connEl) connEl.textContent = state.agentList.length;
    ["server-address-val", "server-address-val2"].forEach(function (id) {
        let el = document.getElementById(id);
        if (el) el.textContent = state.serverAddress || "XXX.XXX.XXX.XXX";
    });
    ["session-key-val", "session-key-val2"].forEach(function (id) {
        let el = document.getElementById(id);
        if (el)
            el.textContent = state.sessionKey
                ? state.sessionKey.substring(0, 32) +
                  (state.sessionKey.length > 32 ? "..." : "")
                : "XXXXXXXXXXXX";
    });
}

function updateAgentBadges() {
    let n = state.agentList.length;
    document.querySelectorAll(".agent-count-badge").forEach(function (el) {
        el.textContent = n;
        el.style.display = n ? "" : "none";
    });
}

// —— Agents ————————————————————————————————————————————————————————————————————

function renderAgentCards() {
    let container = document.getElementById("agent-cards");
    if (!container) return;
    if (!state.agentList.length) {
        container.innerHTML =
            '<div class="empty-state"><i class="fas fa-satellite-dish"></i>' +
            '<div class="empty-title">NO ACTIVE AGENTS</div>' +
            '<div class="empty-text">Waiting for agents to connect...</div></div>';
        return;
    }
    container.innerHTML = state.agentList
        .map(function (a) {
            return (
                '<div class="agent-card' +
                (state.selectedAgentId === a.ID ? " selected" : "") +
                '" data-id="' +
                a.ID +
                '">' +
                '<div class="agent-id">[ AGENT-' +
                escHtml(a.ID) +
                " ]</div>" +
                '<div class="agent-meta">' +
                '<span class="mk">HOST</span><span class="mv">' +
                escHtml(a.Hostname || "-") +
                "</span>" +
                '<span class="mk">OS</span><span class="mv">' +
                escHtml(a.OS || "-") +
                "</span>" +
                '<span class="mk">IP</span><span class="mv">' +
                escHtml(a.AgentIP || "-") +
                "</span>" +
                '<span class="mk">USER</span><span class="mv">' +
                escHtml(a.User || "-") +
                "</span>" +
                '<span class="mk">ENC</span><span class="mv">' +
                escHtml(a.Encrypted ? "YES" : "NO") +
                "</span>" +
                "</div>" +
                '<button class="btn sm full" onclick="selectAndGo(' +
                a.ID +
                ')">' +
                '<i class="fas fa-crosshairs"></i> Target Agent</button>' +
                "</div>"
            );
        })
        .join("");
    container.querySelectorAll(".agent-card").forEach(function (card) {
        card.addEventListener("click", function (e) {
            if (e.target.closest("button")) return;
            selectAgent(parseInt(card.dataset.id));
        });
    });
}

function updateTargetBadge() {
    let badge = document.getElementById("target-badge");
    if (!badge) return;
    if (state.selectedAgentId != null) {
        badge.className = "target-badge";
        badge.innerHTML =
            '<i class="fas fa-dot-circle"></i> AGENT-' + state.selectedAgentId;
    } else {
        badge.className = "target-badge none";
        badge.innerHTML = '<i class="fas fa-dot-circle"></i> NONE SELECTED';
    }
}

function selectAgent(id) {
    state.selectedAgentId = id;
    renderAgentCards();
    updateTargetBadge();
}
function selectAndGo(id) {
    selectAgent(id);
    navigate("command");
}

// —— Server start/stop —————————————————————————————————————————————————————————

async function toggleServer() {
    if (state.startBusy) return;
    state.startBusy = true;
    try {
        state.serverRunning ? await stopServer() : await startServer();
    } finally {
        setTimeout(function () {
            state.startBusy = false;
        }, 1500);
    }
}

async function startServer() {
    let hostEl = document.getElementById("input-host");
    let portEl = document.getElementById("input-port");
    let host = hostEl ? hostEl.value.trim() : state.serverHost;
    let port = portEl ? parseInt(portEl.value) : state.serverPort;
    state.serverHost = host;
    state.serverPort = port;
    addLog("Starting server on " + host + ":" + port + "...", "info");
    try {
        let r = await Api("/api/server/start", {
            method: "POST",
            body: JSON.stringify({ Host: host, Port: port })
        });
        let d = await r.json();
        if (d.Success) {
            state.serverRunning = true;
            state.serverAddress = d.Host + ":" + d.Port;
            state.sessionKey = d.Key || "";
            state.serverStartedAt = d.StartedAt || Date.now() / 1000;
            addLog(
                "Server started successfully on " + state.serverAddress,
                "success"
            );
            startPolling();
            startUptimeTicker();
            updateServerBtns();
            updateSphere();
            updateStats();
        } else {
            addLog(
                "Error: " + (d.Error || d.Error || d.Message || "Unknown error"),
                "error"
            );
        }
    } catch (e) {
        if (e.message !== "Unauthorized")
            addLog("Failed to reach API: " + e.message, "error");
    }
}

async function stopServer() {
    addLog("Stopping server...", "warn");
    try {
        let r = await Api("/api/server/stop", { method: "POST" });
        let d = await r.json();
        if (d.Success) {
            state.serverRunning = false;
            state.serverAddress = "";
            state.sessionKey = "";
            state.agentList = [];
            state.selectedAgentId = null;
            state.lastLogCount = 0;
            addLog("Server stopped", "warn");
            stopPolling();
            stopUptimeTicker();
            updateServerBtns();
            updateSphere();
            updateStats();
            renderAgentCards();
            updateTargetBadge();
            updateTopology();
        } else {
            addLog(
                "Stop error: " + (d.Error || d.Error || d.Message || "Unknown error"),
                "error"
            );
        }
    } catch (e) {
        if (e.message !== "Unauthorized")
            addLog("Failed to reach API: " + e.message, "error");
    }
}

// —— Polling ———————————————————————————————————————————————————————————————————

function startPolling() {
    if (pollInterval) clearInterval(pollInterval);
    pollInterval = setInterval(async function () {
        await refreshServerStatus();
        await refreshAgents();
        await refreshLogs();
    }, 1500);
}

function stopPolling() {
    if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = null;
    }
}

async function refreshServerStatus() {
    try {
        let d = await (await Api("/api/server/status")).json();
        if (d.Status === "Online") {
            state.serverRunning = true;
            if (d.Host && d.Port) state.serverAddress = d.Host + ":" + d.Port;
            if (d.Key) state.sessionKey = d.Key;
            if (d.StartedAt && d.StartedAt !== state.serverStartedAt) {
                state.serverStartedAt = d.StartedAt;
                startUptimeTicker();
            } else if (!state.serverStartedAt) {
                state.serverStartedAt = Date.now() / 1000;
                startUptimeTicker();
            }
            updateStats();
        } else if (d.Status === "Offline" && state.serverRunning) {
            state.serverRunning = false;
            addLog("Server went offline unexpectedly", "error");
            stopUptimeTicker();
            updateServerBtns();
            updateSphere();
            updateStats();
        }
    } catch (e) {}
}

async function refreshAgents() {
    try {
        let d = await (await Api("/api/agents")).json();
        let incoming = d.Agents || [];
        let curIds = new Set(
            state.agentList.map(function (a) {
                return a.ID;
            })
        );
        let newIds = new Set(
            incoming.map(function (a) {
                return a.ID;
            })
        );
        incoming.forEach(function (a) {
            if (!curIds.has(a.ID))
                addLog(
                    "Agent connected: " +
                        (a.Hostname || a.ID) +
                        " (" +
                        (a.AgentIP || "") +
                        ")",
                    "success"
                );
        });
        state.agentList = incoming;
        renderAgentCards();
        updateStats();
        updateAgentBadges();
        updateTopology();
        if (state.selectedAgentId && !newIds.has(state.selectedAgentId)) {
            addLog("Agent-" + state.selectedAgentId + " disconnected", "warn");
            state.selectedAgentId = null;
            updateTargetBadge();
        }
    } catch (e) {}
}

async function refreshLogs() {
    try {
        let d = await (await Api("/api/logs")).json();
        let serverLogs = d.Logs || [];
        if (serverLogs.length > state.lastLogCount) {
            serverLogs.slice(state.lastLogCount).forEach(function (entry) {
                let msg =
                    typeof entry === "string"
                        ? entry
                        : entry.Message || String(entry);
                let level =
                    typeof entry === "object" ? entry.Level || "info" : "info";
                let ts = typeof entry === "object" ? entry.Time : null;
                addLog("[SERVER] " + msg, level, ts);
            });
            state.lastLogCount = serverLogs.length;
        }
    } catch (e) {}
}

// —— Logs ——————————————————————————————————————————————————————————————————————

function addLog(msg, level, time) {
    if (!level) level = "info";
    let ts = time || new Date().toLocaleTimeString("en-US", { hour12: false });
    state.logs.push({ msg: msg, level: level, time: ts });
    if (state.logs.length > 500) state.logs = state.logs.slice(-500);
    renderLogs();
}

function renderLogs() {
    let el = document.getElementById("log-container");
    if (!el) return;
    if (!state.logs.length) {
        el.innerHTML =
            '<div class="empty-state" style="min-height:140px"><i class="fas fa-clipboard-list"></i>' +
            '<div class="empty-text">No log entries yet</div></div>';
        return;
    }
    el.innerHTML = state.logs
        .map(function (e) {
            return (
                '<div class="log-entry ' +
                escHtml(e.level) +
                '">' +
                '<span class="log-time">[' +
                escHtml(e.time) +
                "] [" +
                escHtml(e.level.toUpperCase()) +
                "]</span>" +
                '<span class="log-msg">' +
                escHtml(e.msg) +
                "</span></div>"
            );
        })
        .join("");
    el.scrollTop = el.scrollHeight;
}

function copyLogs(btnEl) {
    copyText(
        state.logs
            .map(function (l) {
                return (
                    "[" + l.time + "] [" + l.level.toUpperCase() + "] " + l.msg
                );
            })
            .join("\n"),
        btnEl
    );
}

function clearLogs() {
    state.logs = [];
    state.lastLogCount = 0;
    Api("/api/logs/clear", { method: "POST" }).catch(function () {});
    renderLogs();
}

// —— Terminal ——————————————————————————————————————————————————————————————————

function appendOutput(text, type) {
    if (!type) type = "out";
    state.cmdOutput.push({ text: text, type: type });
    let el = document.getElementById("terminal-output");
    if (el) {
        text.split("\n").forEach(function (line, i) {
            let div = document.createElement("div");
            div.className = "term-line " + type;
            div.textContent = i > 0 && line !== "" ? "  " + line : line;
            el.appendChild(div);
        });
        el.scrollTop = el.scrollHeight;
    }
}

async function executeCommand() {
    let inp = document.getElementById("cmd-input");
    let raw = inp ? inp.value.trim() : "";
    if (!raw) return;
    if (!state.selectedAgentId) {
        appendOutput(
            "[!] No agent selected. Go to Agents and target one first.",
            "err"
        );
        return;
    }
    inp.value = "";
    appendOutput("> " + raw, "cmd");
    try {
        let r = await Api("/api/command/execute", {
            method: "POST",
            body: JSON.stringify({
                AgentId: state.selectedAgentId,
                Command: raw,
                Operator: state.operator || "system"
            })
        });
        let d = await r.json();
        appendOutput(
            d.Success ? d.Output || "" : "[!] " + d.Output,
            d.Success ? "out" : "err"
        );
    } catch (e) {
        if (e.message !== "Unauthorized")
            appendOutput("[!] Request failed: " + e.message, "err");
    }
    appendOutput("—".repeat(48), "sep");
}

function quickCommand(cmd) {
    if (!state.selectedAgentId) {
        appendOutput("[!] No agent selected.", "err");
        navigate("command");
        return;
    }
    let inp = document.getElementById("cmd-input");
    if (inp) inp.value = cmd;
    executeCommand();
}

function copyOutput(btnEl) {
    copyText(
        state.cmdOutput
            .map(function (l) {
                return l.text;
            })
            .join("\n"),
        btnEl
    );
}

function clearOutput() {
    state.cmdOutput = [];
    let el = document.getElementById("terminal-output");
    if (el) el.innerHTML = "";
}

// —— File transfer —————————————————————————————————————————————————————————————

async function downloadFile() {
    let src = (document.getElementById("adv-source") || {}).value || "";
    src = src.trim();
    if (!state.selectedAgentId) {
        appendOutput("[!] No agent selected", "err");
        return;
    }
    if (!src) {
        appendOutput("[!] Specify source path", "err");
        return;
    }
    appendOutput("[+] Downloading: " + src, "cmd");
    try {
        let r = await Api("/api/command/execute", {
            method: "POST",
            body: JSON.stringify({
                AgentId: state.selectedAgentId,
                Command: "download " + src,
                Operator: state.operator || "system"
            })
        });
        let d = await r.json();
        appendOutput(
            d.Success ? d.Output : "[!] " + d.Output,
            d.Success ? "ok" : "err"
        );
    } catch (e) {
        appendOutput("[!] Download failed", "err");
    }
}

async function uploadFile() {
    let src = (document.getElementById("adv-source") || {}).value || "";
    let dst = (document.getElementById("adv-dest") || {}).value || "";
    src = src.trim();
    dst = dst.trim();
    if (!state.selectedAgentId) {
        appendOutput("[!] No agent selected", "err");
        return;
    }
    if (!src) {
        appendOutput("[!] Specify source path", "err");
        return;
    }
    if (!dst) {
        appendOutput("[!] Specify destination path", "err");
        return;
    }
    appendOutput("[+] Uploading: " + src + " → " + dst, "cmd");
    try {
        let r = await Api("/api/command/execute", {
            method: "POST",
            body: JSON.stringify({
                AgentId: state.selectedAgentId,
                Command: "upload " + dst,
                Operator: state.operator || "system"
            })
        });
        let d = await r.json();
        appendOutput(
            d.Success ? d.Output : "[!] " + d.Output,
            d.Success ? "ok" : "err"
        );
    } catch (e) {
        appendOutput("[!] Upload failed", "err");
    }
}

// —— Team management ———————————————————————————————————————————————————————————

async function renderTeam() {
    let container = document.getElementById("team-container");
    if (!container) return;
    try {
        let r = await Api("/api/team/operators");
        let d = await r.json();
        let ops = d.Operators || [];
        if (!ops.length) {
            container.innerHTML =
                '<div class="empty-state"><i class="fas fa-users-slash"></i><div class="empty-text">No operators found</div></div>';
            return;
        }
        container.innerHTML =
            '<table style="width:100%;border-collapse:collapse;font-size:13px;">' +
            "<thead><tr>" +
            '<th style="text-align:left;padding:8px;color:#2dd4a0;border-bottom:1px solid rgba(45,212,160,0.15);">USERNAME</th>' +
            '<th style="text-align:left;padding:8px;color:#2dd4a0;border-bottom:1px solid rgba(45,212,160,0.15);">ROLE</th>' +
            '<th style="text-align:left;padding:8px;color:#2dd4a0;border-bottom:1px solid rgba(45,212,160,0.15);">CREATED</th>' +
            '<th style="text-align:right;padding:8px;color:#2dd4a0;border-bottom:1px solid rgba(45,212,160,0.15);">ACTIONS</th>' +
            "</tr></thead><tbody>" +
            ops
                .map(function (op) {
                    let isAdmin = op.Username === "admin";
                    let isSelf = op.Username === state.operator;
                    return (
                        "<tr>" +
                        '<td style="padding:8px;color:#e2e8f0;">' +
                        escHtml(op.Username) +
                        (isSelf
                            ? ' <span style="color:#2dd4a0;font-size:10px;">[YOU]</span>'
                            : "") +
                        "</td>" +
                        '<td style="padding:8px;"><span style="background:rgba(45,212,160,0.1);color:#2dd4a0;padding:2px 8px;border-radius:4px;font-size:11px;">' +
                        escHtml(op.Role) +
                        "</span></td>" +
                        '<td style="padding:8px;color:#475569;font-size:11px;">' +
                        escHtml(op.CreatedAt || "-") +
                        "</td>" +
                        '<td style="padding:8px;text-align:right;">' +
                        (!isAdmin
                            ? '<button class="btn sm btn-outline-red" onclick="DeleteOperator(\'' +
                              escHtml(op.Username) +
                              '\')" style="font-size:11px;"><i class="fas fa-trash"></i></button>'
                            : "") +
                        "</td></tr>"
                    );
                })
                .join("") +
            "</tbody></table>";
    } catch (e) {
        if (e.message !== "Unauthorized")
            container.innerHTML =
                '<div style="color:#f87171;padding:16px;">Error loading operators</div>';
    }
}

async function CreateOperator() {
    let user = (document.getElementById("new-op-user") || {}).value || "";
    let pass = (document.getElementById("new-op-pass") || {}).value || "";
    let role =
        (document.getElementById("new-op-role") || {}).value || "OPERATOR";
    user = user.trim();
    if (!user || !pass) {
        addLog("Username and password required", "error");
        return;
    }
    if (pass.length < 8) {
        addLog("Password must be at least 8 characters", "error");
        return;
    }
    try {
        let r = await Api("/api/team/operators/create", {
            method: "POST",
            body: JSON.stringify({ Username: user, Password: pass, Role: role })
        });
        let d = await r.json();
        if (d.Success) {
            addLog("Operator created: " + user + " [" + role + "]", "success");
            let el1 = document.getElementById("new-op-user");
            if (el1) el1.value = "";
            let el2 = document.getElementById("new-op-pass");
            if (el2) el2.value = "";
            renderTeam();
        } else {
            addLog("Create failed: " + (d.Error || d.Error || d.Message), "error");
        }
    } catch (e) {
        if (e.message !== "Unauthorized")
            addLog("Error: " + e.message, "error");
    }
}

async function DeleteOperator(username) {
    if (!confirm("Delete operator: " + username + "?")) return;
    try {
        let r = await Api("/api/team/operators/delete", {
            method: "POST",
            body: JSON.stringify({ Username: username })
        });
        let d = await r.json();
        if (d.Success) {
            addLog("Operator deleted: " + username, "warn");
            renderTeam();
        } else addLog("Delete failed: " + (d.Error || d.Error || d.Message), "error");
    } catch (e) {
        if (e.message !== "Unauthorized")
            addLog("Error: " + e.message, "error");
    }
}

// —— Topology SVG ——————————————————————————————————————————————————————————————

function svgEl(tag, attrs) {
    let el = document.createElementNS("http://www.w3.org/2000/svg", tag);
    Object.entries(attrs || {}).forEach(function (e) {
        el.setAttribute(e[0], e[1]);
    });
    return el;
}

function updateTopology() {
    let svg = document.getElementById("topologySvg");
    if (!svg) return;
    svg.innerHTML = "";
    let W = svg.clientWidth || 600;
    let H = parseInt(svg.getAttribute("height")) || 300;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    let defs = svgEl("defs");
    let pat = svgEl("pattern", {
        id: "tgrid",
        width: 28,
        height: 28,
        patternUnits: "userSpaceOnUse"
    });
    pat.appendChild(
        svgEl("path", {
            d: "M 28 0 L 0 0 0 28",
            fill: "none",
            stroke: "rgba(45,212,160,0.04)",
            "stroke-width": "1"
        })
    );
    defs.appendChild(pat);
    svg.appendChild(defs);
    svg.appendChild(
        svgEl("rect", { width: W, height: H, fill: "url(#tgrid)" })
    );
    if (!state.serverRunning && !state.agentList.length) {
        let t = svgEl("text", {
            x: W / 2,
            y: H / 2,
            "text-anchor": "middle",
            fill: "rgba(45,212,160,0.2)",
            "font-family": "Orbitron,monospace",
            "font-size": "12",
            "letter-spacing": "4"
        });
        t.textContent = "NO CONNECTIONS";
        svg.appendChild(t);
        return;
    }
    let cx = W / 2,
        cy = H / 2;
    state.agentList.forEach(function (agent, i) {
        let angle =
            (2 * Math.PI * i) / Math.max(state.agentList.length, 1) -
            Math.PI / 2;
        let radius = Math.min(W, H) * 0.3;
        let ax = cx + radius * Math.cos(angle);
        let ay = cy + radius * Math.sin(angle);
        svg.appendChild(
            svgEl("line", {
                x1: cx,
                y1: cy,
                x2: ax,
                y2: ay,
                stroke: "rgba(45,212,160,0.15)",
                "stroke-width": "1",
                "stroke-dasharray": "5 5"
            })
        );
        let pid = "pkt" + i;
        svg.appendChild(
            svgEl("path", {
                id: pid,
                d: "M" + cx + "," + cy + " L" + ax + "," + ay,
                fill: "none"
            })
        );
        let pkt = svgEl("circle", { r: "3", fill: "#2dd4a0", opacity: "0.85" });
        let anim = svgEl("animateMotion", {
            dur: 1.8 + i * 0.4 + "s",
            repeatCount: "indefinite"
        });
        let mp = svgEl("mpath");
        mp.setAttribute("href", "#" + pid);
        anim.appendChild(mp);
        pkt.appendChild(anim);
        svg.appendChild(pkt);
        drawNode(
            svg,
            ax,
            ay,
            "AGENT-" + agent.ID,
            agent.AgentIP || "",
            false,
            function () {
                selectAndGo(agent.ID);
            },
            state.selectedAgentId === agent.ID
        );
    });
    drawNode(
        svg,
        cx,
        cy,
        "C2 SERVER",
        state.serverHost + ":" + state.serverPort,
        true
    );
}

function drawNode(svg, x, y, label, sub, isServer, onClick, selected) {
    if (!onClick) onClick = null;
    if (!selected) selected = false;
    let g = svgEl("g");
    if (onClick) {
        g.style.cursor = "pointer";
        g.addEventListener("click", onClick);
    }
    let r = isServer ? 30 : 22;
    let color = isServer
        ? "#2dd4a0"
        : selected
          ? "#2dd4a0"
          : "rgba(148,163,184,0.4)";
    let fill = isServer ? "#1a2e24" : selected ? "#1d2e22" : "#1e2335";
    g.appendChild(
        svgEl("circle", {
            cx: x,
            cy: y,
            r: r + 7,
            fill: "none",
            stroke: isServer
                ? "rgba(45,212,160,0.25)"
                : selected
                  ? "rgba(45,212,160,0.4)"
                  : "rgba(148,163,184,0.12)",
            "stroke-width": "1",
            "stroke-dasharray": isServer ? "0" : "3 3"
        })
    );
    g.appendChild(
        svgEl("circle", {
            cx: x,
            cy: y,
            r: r,
            fill: fill,
            stroke: color,
            "stroke-width": "1.5"
        })
    );
    let fo = svgEl("foreignObject", {
        x: x - 12,
        y: y - 12,
        width: 24,
        height: 24
    });
    let div = document.createElement("div");
    div.style.cssText =
        "width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:13px;color:" +
        (isServer ? "#2dd4a0" : "#94a3b8") +
        ";";
    div.innerHTML =
        '<i class="fas ' + (isServer ? "fa-server" : "fa-laptop") + '"></i>';
    fo.appendChild(div);
    g.appendChild(fo);
    let lbl = svgEl("text", {
        x: x,
        y: y + r + 13,
        "text-anchor": "middle",
        fill: isServer ? "#2dd4a0" : "#cbd5e1",
        "font-family": "Orbitron,monospace",
        "font-size": "8.5",
        "font-weight": "700",
        "letter-spacing": "1"
    });
    lbl.textContent = label;
    g.appendChild(lbl);
    let sub2 = svgEl("text", {
        x: x,
        y: y + r + 23,
        "text-anchor": "middle",
        fill: "#475569",
        "font-family": "Share Tech Mono,monospace",
        "font-size": "8"
    });
    sub2.textContent = sub;
    g.appendChild(sub2);
    svg.appendChild(g);
}

// —— Scroll ————————————————————————————————————————————————————————————————————

function handleContentScroll(e) {
    let el = e.target;
    let st = el.scrollTop;
    let atBot = el.scrollHeight - st - el.clientHeight < 32;
    let down = st > state.lastScrollY;
    let bnav = document.querySelector(".bottom-nav");
    if (bnav) bnav.classList.toggle("hidden", atBot && down);
    if (bnav && !down) bnav.classList.remove("hidden");
    state.lastScrollY = st;
}

function renderQuickCmds() {
    let grid = document.getElementById("quick-grid");
    if (!grid) return;
    grid.innerHTML = quickCmds
        .map(function (qc) {
            return (
                '<button class="quick-btn" onclick="quickCommand(\'' +
                qc.cmd +
                "')\">" +
                '<i class="' +
                qc.icon +
                '"></i>' +
                qc.label +
                "</button>"
            );
        })
        .join("");
}

// —— Bootstrap —————————————————————————————————————————————————————————————————

async function BootstrapStatus() {
    try {
        let d = await (await Api("/api/server/status")).json();
        if (d.Status === "Online") {
            state.serverRunning = true;
            state.serverHost = d.Host;
            state.serverPort = d.Port;
            state.serverAddress = d.Host + ":" + d.Port;
            state.sessionKey = d.Key || "";
            state.serverStartedAt = d.StartedAt || null;
            updateServerBtns();
            updateSphere();
            updateStats();
            startPolling();
            if (state.serverStartedAt) startUptimeTicker();
            addLog(
                "Connected to running server at " + state.serverAddress,
                "success"
            );
        }
    } catch (e) {}
    updateTopology();
    navigate("dashboard");
}

// —— DOMContentLoaded ——————————————————————————————————————————————————————————

document.addEventListener("DOMContentLoaded", async function () {
    updateClock();
    clockInterval = setInterval(updateClock, 1000);
    renderQuickCmds();
    renderLogs();
    updateTargetBadge();

    document.querySelectorAll("[data-nav]").forEach(function (el) {
        el.addEventListener("click", function () {
            navigate(el.dataset.nav);
        });
    });

    let topbarServerBtn = document.getElementById("topbar-server-btn");
    if (topbarServerBtn)
        topbarServerBtn.addEventListener("click", toggleServer);
    let mobileServerBtn = document.getElementById("mobile-server-btn");
    if (mobileServerBtn)
        mobileServerBtn.addEventListener("click", toggleServer);
    let serverToggleBtn = document.getElementById("server-toggle-btn");
    if (serverToggleBtn)
        serverToggleBtn.addEventListener("click", toggleServer);

    let cmdInput = document.getElementById("cmd-input");
    if (cmdInput)
        cmdInput.addEventListener("keydown", function (e) {
            if (e.key === "Enter") executeCommand();
        });

    let contentEl = document.querySelector(".content");
    if (contentEl)
        contentEl.addEventListener("scroll", handleContentScroll, {
            passive: true
        });

    // Detect if TeamServer (has /api/auth/login endpoint)
    let isTeam = false;
    try {
        let probe = await fetch("/api/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: "{}"
        });
        isTeam = probe.status !== 404;
    } catch (e) {}

    if (isTeam) {
        LoadToken();
        UpdateOperatorBadge();
        if (!state.token) {
            ShowLogin();
        } else {
            // Validate token
            try {
                let r = await fetch("/api/server/status", {
                    headers: { Authorization: "Bearer " + state.token }
                });
                if (r.status === 401) {
                    ClearToken();
                    ShowLogin("Session expired");
                    return;
                }
                await BootstrapStatus();
            } catch (e) {
                await BootstrapStatus();
            }
        }
    } else {
        await BootstrapStatus();
    }
});
