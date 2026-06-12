package com.tomcat.core.session;

import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Session {

    public enum Type {
        TOMCAT,
        METERPRETER,
        REVERSE_SHELL,
        UNKNOWN,
    }

    private static final DateTimeFormatter Fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private int Id;
    private Socket Socket;
    private String RemoteAddress;
    private Type SessionType;
    private String AgentName;
    private String Os;
    private String Hostname;
    private String User;
    private String Arch;
    private String AgentIp;
    private String JoinedAt;
    private String ShellMode;
    private boolean MtlsEnabled;
    private String CertCn;
    private boolean RawMode;
    private boolean Encrypted;
    private String Status;

    public Session() {
        JoinedAt = LocalDateTime.now().format(Fmt);
        SessionType = Type.TOMCAT;
        Status = "Online";
        Encrypted = true;
        ShellMode = "Standard";
        Os = "Unknown";
        Hostname = "Unknown";
        User = "Unknown";
        Arch = "Unknown";
        AgentIp = "Unknown";
        CertCn = "N/A";
    }

    public int GetId() {
        return Id;
    }

    public void SetId(int Id) {
        this.Id = Id;
    }

    public java.net.Socket GetSocket() {
        return Socket;
    }

    public void SetSocket(java.net.Socket Socket) {
        this.Socket = Socket;
    }

    public String GetRemoteAddress() {
        return RemoteAddress;
    }

    public void SetRemoteAddress(String RemoteAddress) {
        this.RemoteAddress = RemoteAddress;
    }

    public Type GetSessionType() {
        return SessionType;
    }

    public void SetSessionType(Type SessionType) {
        this.SessionType = SessionType;
    }

    public String GetAgentName() {
        return AgentName;
    }

    public void SetAgentName(String AgentName) {
        this.AgentName = AgentName;
    }

    public String GetOs() {
        return Os;
    }

    public void SetOs(String Os) {
        this.Os = Os;
    }

    public String GetHostname() {
        return Hostname;
    }

    public void SetHostname(String Hostname) {
        this.Hostname = Hostname;
    }

    public String GetUser() {
        return User;
    }

    public void SetUser(String User) {
        this.User = User;
    }

    public String GetArch() {
        return Arch;
    }

    public void SetArch(String Arch) {
        this.Arch = Arch;
    }

    public String GetAgentIp() {
        return AgentIp;
    }

    public void SetAgentIp(String AgentIp) {
        this.AgentIp = AgentIp;
    }

    public String GetJoinedAt() {
        return JoinedAt;
    }

    public void SetJoinedAt(String JoinedAt) {
        this.JoinedAt = JoinedAt;
    }

    public String GetShellMode() {
        return ShellMode;
    }

    public void SetShellMode(String ShellMode) {
        this.ShellMode = ShellMode;
    }

    public boolean IsMtlsEnabled() {
        return MtlsEnabled;
    }

    public void SetMtlsEnabled(boolean MtlsEnabled) {
        this.MtlsEnabled = MtlsEnabled;
    }

    public String GetCertCn() {
        return CertCn;
    }

    public void SetCertCn(String CertCn) {
        this.CertCn = CertCn;
    }

    public boolean IsRawMode() {
        return RawMode;
    }

    public void SetRawMode(boolean RawMode) {
        this.RawMode = RawMode;
    }

    public boolean IsEncrypted() {
        return Encrypted;
    }

    public void SetEncrypted(boolean Encrypted) {
        this.Encrypted = Encrypted;
    }

    public String GetStatus() {
        return Status;
    }

    public void SetStatus(String Status) {
        this.Status = Status;
    }
}
