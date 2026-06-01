package com.tomcat.core.server;

import com.tomcat.core.crypto.CertificateManager;
import com.tomcat.core.event.EventManager;
import com.tomcat.core.event.EventManager.EventType;
import com.tomcat.core.output.Logger;
import com.tomcat.core.session.Session;
import com.tomcat.utils.ServerConfig;
import java.io.IOException;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.net.ssl.*;

public class TomcatServer extends BaseServer {

    private final boolean UseMtls;
    private ServerSocket ServerSock;
    private SSLServerSocket SslServerSock;
    private Thread AcceptThread;
    private final ExecutorService ThreadPool;

    public TomcatServer(String Host, int Port, boolean UseMtls, ServerConfig Config) {
        super(Host, Port, Config);
        this.UseMtls = UseMtls;
        this.ThreadPool = Executors.newCachedThreadPool(R -> {
            Thread T = new Thread(R, "AgentHandler");
            T.setDaemon(true);
            return T;
        });
    }

    @Override
    public boolean[] StartServer() {
        try {
            if (UseMtls) {
                CertificateManager CertMgr = new CertificateManager("Certs", Config.GetKeystorePassword());
                SSLContext Ctx = CertMgr.CreateServerSslContext();
                SSLServerSocketFactory Factory = Ctx.getServerSocketFactory();
                SslServerSock = (SSLServerSocket) Factory.createServerSocket(Port, 10, InetAddress.getByName(Host));
                SslServerSock.setNeedClientAuth(true);
                SslServerSock.setEnabledProtocols(new String[] { "TLSv1.3", "TLSv1.2" });
                Logger.Messages("MTLS Server Socket Created");
            } else {
                ServerSock = new ServerSocket(Port, 10, InetAddress.getByName(Host));
                ServerSock.setReuseAddress(true);
            }
            Running = true;
            String Mode = UseMtls ? "MTLS" : "TCP";
            String Msg = "Server Started On " + Host + ":" + Port + " [" + Mode + "]";
            Events.Trigger(
                EventType.ServerStarted,
                EventManager.BuildData("Host", Host, "Port", Port, "Key", new String(Crypto.GetKey()), "Mode", Mode)
            );
            Logger.Messages(Msg);
            return new boolean[] { true };
        } catch (Exception E) {
            Logger.ErrorMsg("Error Starting Server: " + E.getMessage());
            return new boolean[] { false };
        }
    }

    @Override
    public void StopServer() {
        Running = false;
        try {
            if (SslServerSock != null) SslServerSock.close();
            if (ServerSock != null) ServerSock.close();
        } catch (IOException Ignored) {}
        if (AcceptThread != null) AcceptThread.interrupt();
        Sessions.Clear();
        ThreadPool.shutdown();
        Events.Trigger(EventType.ServerStopped);
    }

    @Override
    public void AcceptConnections() {
        AcceptThread = Thread.currentThread();
        while (Running) {
            try {
                Socket Client = UseMtls ? SslServerSock.accept() : ServerSock.accept();
                ThreadPool.submit(() -> HandleAgent(Client));
            } catch (SocketTimeoutException Ignored) {} catch (IOException E) {
                if (Running) Logger.ErrorMsg("Accept error: " + E.getMessage());
                break;
            }
        }
    }

    private void HandleAgent(Socket Client) {
        int SessionId = -1;
        try {
            Map<String, Object> Info = DoHandshake(Client);
            if (Info == null) {
                Client.close();
                return;
            }
            Session S = new Session();
            S.SetSocket(Client);
            S.SetRemoteAddress(Client.getRemoteSocketAddress().toString());
            S.SetSessionType(Session.Type.TOMCAT);
            S.SetOs(GetStr(Info, "os"));
            S.SetHostname(GetStr(Info, "hostname"));
            S.SetUser(GetStr(Info, "user"));
            S.SetArch(GetStr(Info, "architecture"));
            S.SetAgentIp(GetStr(Info, "agentip"));
            S.SetShellMode(GetStr(Info, "shellmode", "Standard"));
            S.SetEncrypted(true);
            S.SetMtlsEnabled(UseMtls);

            String AgentName = "Agent-" + (Sessions.Count() + 1);
            if (UseMtls && Client instanceof SSLSocket) {
                SSLSession Ssl = ((SSLSocket) Client).getSession();
                if (Ssl.getPeerCertificates().length > 0) {
                    String Dn =
                        ((java.security.cert.X509Certificate) Ssl.getPeerCertificates()[0]).getSubjectX500Principal()
                            .getName();
                    for (String Part : Dn.split(",")) {
                        if (Part.trim().startsWith("CN=")) {
                            AgentName = Part.trim().substring(3);
                            break;
                        }
                    }
                    S.SetCertCn(AgentName);
                }
            }
            if (!GetStr(Info, "hostname").equals("Unknown")) {
                AgentName = GetStr(Info, "hostname");
            }
            S.SetAgentName(AgentName);
            SessionId = Sessions.Add(S);
            CommandLocks.put(SessionId, new Object());
            Events.Trigger(
                EventType.AgentConnected,
                EventManager.BuildData(
                    "ID",
                    SessionId,
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
                    "MTLSEnabled",
                    S.IsMtlsEnabled(),
                    "CertCN",
                    S.GetCertCn(),
                    "ShellMode",
                    S.GetShellMode()
                )
            );
            final int FinalId = SessionId;
            MonitorSession(FinalId, Client);
        } catch (Exception E) {
            Logger.ErrorMsg("Agent handshake error: " + E.getMessage());
            try {
                Client.close();
            } catch (IOException Ignored) {}
            if (SessionId > 0) RemoveSession(SessionId);
        }
    }

    private String GetStr(Map<String, Object> Map, String Key) {
        return GetStr(Map, Key, "Unknown");
    }

    private String GetStr(Map<String, Object> Map, String Key, String Default) {
        Object Val = Map.get(Key);
        return Val != null ? Val.toString() : Default;
    }

    public Map<String, Integer> GetSessionStats() {
        Map<String, Integer> Stats = Sessions.GetStats();
        Stats.put("Port", Port);
        return Stats;
    }
}
