package com.tomcat.iface;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import com.tomcat.core.db.TeamDatabase;
import com.tomcat.core.db.TeamDatabase.OperatorRole;
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

public final class TeamServer {

    private final ServerConfig Config;
    private final ListenerMode Mode;
    private final TeamDatabase Db;
    private final Gson Json = new Gson();
    private final int MaxLogs;
    private final Path BaseDir;

    private TomcatServer Server;
    private HttpServer HttpSrv;
    private Instant ServerStartTime;

    private final List<String> Logs = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, TokenInfo> Tokens = new ConcurrentHashMap<>();

    private static final long TokenTtlMs = 8 * 60 * 60 * 1000L;

    private record TokenInfo(String Username, OperatorRole Role, long ExpiresAt) {
        boolean Valid() {
            return System.currentTimeMillis() < ExpiresAt;
        }
    }

    public TeamServer(ServerConfig Config, ListenerMode Mode) {
        this.Config = Config;
        this.Mode = Mode;
        this.MaxLogs = Config.GetMaxLogEntries();
        this.Db = TeamDatabase.Connect(Config);
        this.BaseDir = ResolveBaseDir();
    }

    private Path ResolveBaseDir() {
        try {
            Path Jar = Paths.get(
                TeamServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).getParent();
            for (Path P : new Path[] { Jar, Jar != null ? Jar.getParent() : null }) if (
                P != null && Files.exists(P.resolve("config"))
            ) return P.toAbsolutePath();
        } catch (Exception Ignored) {}
        return Paths.get("").toAbsolutePath();
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
        Logger.Info("TeamServer started on http://" + Host + ":" + Port);
        Logger.Info("Default credentials  : admin / admin  (change immediately)");
        Logger.Info("Web UI               : http://" + Host + ":" + Port + "/");
        Logger.Info("API base             : http://" + Host + ":" + Port + "/api/");
        AddLog("TeamServer initialized — mode=" + Mode.name());
    }

    private void RegisterRoutes() {
        HttpSrv.createContext("/", new StaticHandler());
        HttpSrv.createContext("/api/auth/login", E -> Route(E, this::AuthLogin, false));
        HttpSrv.createContext("/api/auth/logout", E -> Route(E, this::AuthLogout, true));
        HttpSrv.createContext("/api/server/status", E -> Route(E, this::ApiStatus, true));
        HttpSrv.createContext("/api/server/start", E -> Route(E, this::ApiStart, true));
        HttpSrv.createContext("/api/server/stop", E -> Route(E, this::ApiStop, true));
        HttpSrv.createContext("/api/agents", E -> Route(E, this::ApiAgents, true));
        HttpSrv.createContext("/api/agents/kill", E -> Route(E, this::ApiKill, true));
        HttpSrv.createContext("/api/agents/note", E -> Route(E, this::ApiNote, true));
        HttpSrv.createContext("/api/command/execute", E -> Route(E, this::ApiExec, true));
        HttpSrv.createContext("/api/command/broadcast", E -> Route(E, this::ApiBroadcast, true));
        HttpSrv.createContext("/api/command/broadcastall", E -> Route(E, this::ApiBroadcastAll, true));
        HttpSrv.createContext("/api/command/history", E -> Route(E, this::ApiCmdHist, true));
        HttpSrv.createContext("/api/sessions/history", E -> Route(E, this::ApiSessHist, true));
        HttpSrv.createContext("/api/logs", E -> Route(E, this::ApiLogs, true));
        HttpSrv.createContext("/api/team/operators", E -> Route(E, this::ApiOperators, true));
        HttpSrv.createContext("/api/team/operators/create", E -> Route(E, this::ApiOpCreate, true));
        HttpSrv.createContext("/api/team/operators/role", E -> Route(E, this::ApiOpRole, true));
        HttpSrv.createContext("/api/team/operators/delete", E -> Route(E, this::ApiOpDelete, true));
        HttpSrv.createContext("/api/team/operators/password", E -> Route(E, this::ApiOpPassword, true));
    }

    @FunctionalInterface
    interface Handler {
        String Handle(HttpExchange E, TokenInfo T) throws Exception;
    }

    private void Route(HttpExchange E, Handler H, boolean NeedAuth) {
        try {
            E.getResponseHeaders().add("Content-Type", "application/json");
            E.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            E.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            E.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            if (E.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                E.sendResponseHeaders(200, -1);
                return;
            }
            TokenInfo Token = null;
            if (NeedAuth) {
                String Auth = E.getRequestHeaders().getFirst("Authorization");
                if (Auth == null || !Auth.startsWith("Bearer ")) {
                    Write(E, 401, Map.of("Error", "Missing Authorization header"));
                    return;
                }
                Token = Tokens.get(Auth.substring(7));
                if (Token == null || !Token.Valid()) {
                    Write(E, 401, Map.of("Error", "Token expired — please login again"));
                    return;
                }
            }
            byte[] Body = H.Handle(E, Token).getBytes("UTF-8");
            E.sendResponseHeaders(200, Body.length);
            try (OutputStream O = E.getResponseBody()) {
                O.write(Body);
            }
        } catch (Exception Ex) {
            try {
                byte[] B = Json.toJson(Map.of("Error", String.valueOf(Ex.getMessage()))).getBytes("UTF-8");
                E.sendResponseHeaders(500, B.length);
                try (OutputStream O = E.getResponseBody()) {
                    O.write(B);
                }
            } catch (IOException Ignored) {}
        }
    }

    private void Write(HttpExchange E, int Status, Object Body) throws IOException {
        byte[] B = Json.toJson(Body).getBytes("UTF-8");
        E.sendResponseHeaders(Status, B.length);
        try (OutputStream O = E.getResponseBody()) {
            O.write(B);
        }
    }

    private String AuthLogin(HttpExchange E, TokenInfo Ignored) throws Exception {
        Map<String, Object> B = Body(E);
        String User = Str(B, "Username", "");
        String Pass = Str(B, "Password", "");
        if (User.isEmpty() || Pass.isEmpty()) return Json.toJson(Map.of("Error", "Username and Password required"));
        if (!Db.ValidateOperator(User, TeamDatabase.HashPassword(Pass))) return Json.toJson(
            Map.of("Error", "Invalid credentials")
        );
        OperatorRole Role = Db.GetOperatorRole(User);
        String Token = GenToken();
        Tokens.put(Token, new TokenInfo(User, Role, System.currentTimeMillis() + TokenTtlMs));
        Logger.Info("Operator login: " + User + " [" + Role + "]");
        AddLog("[AUTH] Login: " + User + " [" + Role + "]");
        return Json.toJson(
            Map.of("Token", Token, "Role", Role.name(), "Username", User, "ExpiresIn", TokenTtlMs / 1000)
        );
    }

    private String AuthLogout(HttpExchange E, TokenInfo T) throws Exception {
        String Auth = E.getRequestHeaders().getFirst("Authorization");
        if (Auth != null && Auth.startsWith("Bearer ")) Tokens.remove(Auth.substring(7));
        return Json.toJson(Map.of("Success", true));
    }

    private String ApiStatus(HttpExchange E, TokenInfo T) {
        boolean Up = Server != null && Server.IsRunning();
        Map<String, Object> R = new LinkedHashMap<>();
        R.put("Status", Up ? "Online" : "Offline");
        R.put("Mode", Mode.name());
        R.put("Host", Up ? Server.GetHost() : Config.GetServerHost());
        R.put("Port", Up ? Server.GetPort() : Config.GetServerPort());
        R.put("Agents", Up ? Server.GetSessions().Count() : 0);
        R.put("Uptime", Uptime());
        R.put("Operator", T.Username());
        R.put("Role", T.Role().name());
        R.put("DbType", Config.GetDbType());
        R.put("DbOnline", Db.IsConnected());
        // Guard: only expose key when running
        if (Up) R.put("Key", Server.GetKeyBase64());
        return Json.toJson(R);
    }

    private String ApiStart(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanManage()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        if (Server != null && Server.IsRunning()) return Json.toJson(Map.of("Error", "Server already running"));
        Map<String, Object> B = Body(E);
        String Host = Str(B, "Host", Config.GetServerHost());
        int Port = Num(B, "Port", Config.GetServerPort());
        Server = new TomcatServer(Host, Port, Mode, Config);
        Server.AddEventListener(this::OnEvent);
        boolean[] R = Server.StartServer();
        if (!R[0]) return Json.toJson(Map.of("Error", "Failed to start server"));
        ServerStartTime = Instant.now();
        new Thread(Server::AcceptConnections, "AcceptConnections").start();
        AddLog("[+] Server started on " + Host + ":" + Port + " by " + T.Username());
        return Json.toJson(Map.of("Success", true, "Host", Host, "Port", Port));
    }

    private String ApiStop(HttpExchange E, TokenInfo T) {
        if (!T.Role().CanManage()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return Json.toJson(Map.of("Error", "Server not running"));
        Server.StopServer();
        Server = null;
        ServerStartTime = null;
        AddLog("[!] Server stopped by " + T.Username());
        return Json.toJson(Map.of("Success", true));
    }

    private String ApiAgents(HttpExchange E, TokenInfo T) {
        if (Server == null || !Server.IsRunning()) return Json.toJson(Map.of("Agents", Collections.emptyList()));
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
        return Json.toJson(Map.of("Agents", List));
    }

    private String ApiExec(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanExecute()) return Json.toJson(Map.of("Error", "OPERATOR or ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return Json.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> B = Body(E);
        int AgentId = Num(B, "AgentId", 0);
        String Command = Str(B, "Command", "");
        if (AgentId == 0 || Command.isEmpty()) return Json.toJson(Map.of("Error", "AgentId and Command required"));
        AddLog("[>] [" + T.Username() + "] Agent-" + AgentId + " » " + Command);
        String[] R = Server.ExecuteCommand(AgentId, Command);
        boolean Ok = Boolean.parseBoolean(R[0]);
        Db.SaveCommandLog(AgentId, T.Username(), Command, R[1], Ok);
        AddLog(Ok ? "[+] " + R[1] : "[!] " + R[1]);
        return Json.toJson(Map.of("Success", Ok, "Output", R[1], "Command", Command));
    }

    private String ApiBroadcast(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanBroadcast()) return Json.toJson(Map.of("Error", "OPERATOR or ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return Json.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> B = Body(E);
        String Command = Str(B, "Command", "");
        @SuppressWarnings("unchecked")
        java.util.List<Object> Raw = (java.util.List<Object>) B.getOrDefault("AgentIds", new ArrayList<>());
        if (Command.isEmpty()) return Json.toJson(Map.of("Error", "Command required"));
        java.util.List<Integer> Ids = new ArrayList<>();
        for (Object O : Raw) {
            try {
                Ids.add((int) Double.parseDouble(O.toString()));
            } catch (Exception Ignored) {}
        }
        if (Ids.isEmpty()) return Json.toJson(Map.of("Error", "AgentIds required"));
        AddLog("[BROADCAST] [" + T.Username() + "] → " + Ids.size() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastCommand(Ids, Command);
        Map<String, Object> Out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            Out.put(String.valueOf(En.getKey()), Map.of("Success", Ok, "Output", En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), T.Username(), Command, En.getValue()[1], Ok);
        }
        return Json.toJson(Map.of("Success", true, "Results", Out, "Count", Results.size()));
    }

    private String ApiBroadcastAll(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanBroadcast()) return Json.toJson(Map.of("Error", "OPERATOR or ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return Json.toJson(Map.of("Error", "Server not running"));
        String Command = Str(Body(E), "Command", "");
        if (Command.isEmpty()) return Json.toJson(Map.of("Error", "Command required"));
        AddLog("[BROADCAST-ALL] [" + T.Username() + "] → " + Server.GetSessions().Count() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastAll(Command);
        Map<String, Object> Out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            Out.put(String.valueOf(En.getKey()), Map.of("Success", Ok, "Output", En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), T.Username(), Command, En.getValue()[1], Ok);
        }
        return Json.toJson(Map.of("Success", true, "Results", Out, "Count", Results.size()));
    }

    private String ApiCmdHist(HttpExchange E, TokenInfo T) throws Exception {
        Map<String, Object> B = Body(E);
        return Json.toJson(Map.of("History", Db.GetCommandHistory(Num(B, "AgentId", 0), Num(B, "Limit", 100))));
    }

    private String ApiSessHist(HttpExchange E, TokenInfo T) throws Exception {
        return Json.toJson(Map.of("Sessions", Db.GetSessionHistory(Num(Body(E), "Limit", 100))));
    }

    private String ApiKill(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanKill()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return Json.toJson(Map.of("Error", "Server not running"));
        int Id = Num(Body(E), "AgentId", 0);
        if (Id == 0) return Json.toJson(Map.of("Error", "AgentId required"));
        Server.RemoveSession(Id);
        AddLog("[KILL] [" + T.Username() + "] Agent-" + Id);
        return Json.toJson(Map.of("Success", true));
    }

    private String ApiNote(HttpExchange E, TokenInfo T) throws Exception {
        Map<String, Object> B = Body(E);
        Db.SetAgentNote(Num(B, "AgentId", 0), Str(B, "Note", ""));
        return Json.toJson(Map.of("Success", true));
    }

    private String ApiLogs(HttpExchange E, TokenInfo T) {
        return Json.toJson(Map.of("Logs", new ArrayList<>(Logs)));
    }

    private String ApiOperators(HttpExchange E, TokenInfo T) {
        if (!T.Role().CanManage()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        return Json.toJson(Map.of("Operators", Db.GetOperators()));
    }

    private String ApiOpCreate(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanManage()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        Map<String, Object> B = Body(E);
        String User = Str(B, "Username", "");
        String Pass = Str(B, "Password", "");
        String Role = Str(B, "Role", "OPERATOR");
        if (User.isEmpty() || Pass.isEmpty()) return Json.toJson(Map.of("Error", "Username and Password required"));
        if (Pass.length() < 8) return Json.toJson(Map.of("Error", "Password must be at least 8 characters"));
        OperatorRole R = OperatorRole.FromString(Role);
        if (!Db.CreateOperator(User, TeamDatabase.HashPassword(Pass), R)) return Json.toJson(
            Map.of("Error", "Username already exists")
        );
        AddLog("[TEAM] Created operator: " + User + " [" + R + "] by " + T.Username());
        return Json.toJson(Map.of("Success", true, "Username", User, "Role", R.name()));
    }

    private String ApiOpRole(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanManage()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        Map<String, Object> B = Body(E);
        String User = Str(B, "Username", "");
        String Role = Str(B, "Role", "");
        if (User.isEmpty() || Role.isEmpty()) return Json.toJson(Map.of("Error", "Username and Role required"));
        if (User.equals("admin")) return Json.toJson(Map.of("Error", "Cannot change admin role"));
        OperatorRole R = OperatorRole.FromString(Role);
        Db.UpdateOperatorRole(User, R);
        AddLog("[TEAM] Role updated: " + User + " → " + R + " by " + T.Username());
        return Json.toJson(Map.of("Success", true));
    }

    private String ApiOpDelete(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanManage()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        String User = Str(Body(E), "Username", "");
        if (User.isEmpty()) return Json.toJson(Map.of("Error", "Username required"));
        if (User.equals("admin")) return Json.toJson(Map.of("Error", "Cannot delete admin"));
        boolean Del = Db.DeleteOperator(User);
        if (Del) AddLog("[TEAM] Deleted operator: " + User + " by " + T.Username());
        return Json.toJson(Map.of("Success", Del));
    }

    private String ApiOpPassword(HttpExchange E, TokenInfo T) throws Exception {
        if (!T.Role().CanManage()) return Json.toJson(Map.of("Error", "ADMIN role required"));
        Map<String, Object> B = Body(E);
        String User = Str(B, "Username", "");
        String NewPass = Str(B, "Password", "");
        if (User.isEmpty() || NewPass.isEmpty()) return Json.toJson(Map.of("Error", "Username and Password required"));
        if (NewPass.length() < 8) return Json.toJson(Map.of("Error", "Password must be at least 8 characters"));
        boolean Ok = Db.UpdateOperatorPassword(User, TeamDatabase.HashPassword(NewPass));
        if (Ok) AddLog("[TEAM] Password changed: " + User + " by " + T.Username());
        return Json.toJson(Map.of("Success", Ok));
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

    private String GenToken() {
        byte[] B = new byte[32];
        new java.security.SecureRandom().nextBytes(B);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(B);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> Body(HttpExchange E) throws Exception {
        try (InputStream Is = E.getRequestBody()) {
            String S = new String(Is.readAllBytes(), "UTF-8");
            if (S.isEmpty()) return new HashMap<>();
            return Json.fromJson(S, Map.class);
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

    class StaticHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange E) throws IOException {
            String ReqPath = E.getRequestURI().getPath();
            Path Target = null;
            if (ReqPath.equals("/") || ReqPath.equals("/index.html")) {
                Path Tpl = ResolvePath(Config.GetTemplateDir() + "/index.html");
                if (Files.exists(Tpl)) Target = Tpl;
            }
            if (Target == null) {
                String Rel = ReqPath.startsWith("/static/") ? ReqPath.substring(7) : ReqPath;
                Path Stat = ResolvePath(Config.GetStaticDir() + Rel);
                if (Files.exists(Stat) && !Files.isDirectory(Stat)) Target = Stat;
            }
            if (Target == null) {
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

    public void Stop() {
        if (Server != null) Server.StopServer();
        if (HttpSrv != null) HttpSrv.stop(0);
        Db.Close();
    }
}
