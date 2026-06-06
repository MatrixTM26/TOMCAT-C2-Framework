package com.tomcat.core.db;

import com.google.gson.Gson;
import com.tomcat.core.output.Logger;
import com.tomcat.utils.ServerConfig;
import java.sql.*;
import java.util.*;

public final class PostgresDatabase extends TeamDatabase {

    private final Connection Conn;
    private static final Gson GsonInst = new Gson();

    public PostgresDatabase(ServerConfig Config) throws Exception {
        String Url = Config.GetDbUrl();
        String User = Config.GetDbUser();
        String Pass = Config.GetDbPassword();
        if (!Url.startsWith("jdbc:postgresql://") && !Url.startsWith("jdbc:postgres://")) {
            throw new Exception("Invalid PostgreSQL URL — must start with jdbc:postgresql://");
        }
        try {
            Class.forName("org.postgresql.Driver");
            Conn = DriverManager.getConnection(Url, User, Pass);
            Conn.setAutoCommit(true);
            Logger.Info("PostgreSQL connected: " + Url);
            InitSchema();
        } catch (ClassNotFoundException E) {
            throw new Exception("PostgreSQL driver not found — add postgresql jar to classpath");
        } catch (SQLException E) {
            throw new Exception("PostgreSQL connect failed [" + Url + "]: " + E.getMessage());
        }
    }

    private void InitSchema() throws SQLException {
        try (Statement St = Conn.createStatement()) {
            St.execute(
                """
                CREATE TABLE IF NOT EXISTS tc2_logs (
                    id      BIGSERIAL PRIMARY KEY,
                    entry   TEXT NOT NULL,
                    created TIMESTAMP DEFAULT NOW()
                )"""
            );
            St.execute(
                """
                CREATE TABLE IF NOT EXISTS tc2_commands (
                    id        BIGSERIAL PRIMARY KEY,
                    agent_id  INTEGER NOT NULL,
                    operator  VARCHAR(128),
                    command   TEXT NOT NULL,
                    output    TEXT,
                    success   BOOLEAN,
                    created   TIMESTAMP DEFAULT NOW()
                )"""
            );
            St.execute(
                """
                CREATE TABLE IF NOT EXISTS tc2_sessions (
                    id        BIGSERIAL PRIMARY KEY,
                    agent_id  VARCHAR(64),
                    hostname  VARCHAR(256),
                    os        VARCHAR(128),
                    username  VARCHAR(128),
                    ip        VARCHAR(64),
                    event     VARCHAR(32),
                    data      TEXT,
                    created   TIMESTAMP DEFAULT NOW()
                )"""
            );
            St.execute(
                """
                CREATE TABLE IF NOT EXISTS tc2_notes (
                    agent_id  INTEGER PRIMARY KEY,
                    note      TEXT,
                    updated   TIMESTAMP DEFAULT NOW()
                )"""
            );
            St.execute(
                """
                CREATE TABLE IF NOT EXISTS tc2_operators (
                    username      VARCHAR(128) PRIMARY KEY,
                    password_hash VARCHAR(64)  NOT NULL,
                    role          VARCHAR(32)  NOT NULL DEFAULT 'VIEWER',
                    created       TIMESTAMP DEFAULT NOW()
                )"""
            );
            St.execute(
                """
                INSERT INTO tc2_operators (username, password_hash, role)
                VALUES ('admin', '""" +
                HashPassword("admin") +
                """
                ', 'ADMIN')
                ON CONFLICT (username) DO NOTHING"""
            );
            Logger.Verbose("PostgreSQL schema ready");
        }
    }

    @Override
    public boolean IsConnected() {
        try {
            return Conn != null && !Conn.isClosed() && Conn.isValid(2);
        } catch (Exception E) {
            return false;
        }
    }

    @Override
    public void SaveLog(String Entry) {
        exec("INSERT INTO tc2_logs (entry) VALUES (?)", Entry);
    }

    @Override
    public void SaveCommandLog(int AgentId, String Operator, String Command, String Output, boolean Success) {
        exec(
            "INSERT INTO tc2_commands (agent_id,operator,command,output,success) VALUES (?,?,?,?,?)",
            AgentId,
            Operator,
            Command,
            Output,
            Success
        );
    }

    @Override
    public void SaveSessionEvent(Map<String, Object> Data, String Event) {
        exec(
            "INSERT INTO tc2_sessions (agent_id,hostname,os,username,ip,event,data) VALUES (?,?,?,?,?,?,?)",
            str(Data, "ID"),
            str(Data, "Hostname"),
            str(Data, "OS"),
            str(Data, "User"),
            str(Data, "AgentIP"),
            Event,
            GsonInst.toJson(Data)
        );
    }

    @Override
    public List<Map<String, Object>> GetCommandHistory(int AgentId, int Limit) {
        return AgentId == 0
            ? query("SELECT * FROM tc2_commands ORDER BY created DESC LIMIT ?", Limit)
            : query("SELECT * FROM tc2_commands WHERE agent_id=? ORDER BY created DESC LIMIT ?", AgentId, Limit);
    }

    @Override
    public List<Map<String, Object>> GetSessionHistory(int Limit) {
        return query("SELECT * FROM tc2_sessions ORDER BY created DESC LIMIT ?", Limit);
    }

    @Override
    public void SetAgentNote(int AgentId, String Note) {
        exec(
            "INSERT INTO tc2_notes (agent_id,note) VALUES (?,?) ON CONFLICT (agent_id) DO UPDATE SET note=?,updated=NOW()",
            AgentId,
            Note,
            Note
        );
    }

    @Override
    public String GetAgentNote(int AgentId) {
        List<Map<String, Object>> Rows = query("SELECT note FROM tc2_notes WHERE agent_id=?", AgentId);
        if (Rows.isEmpty()) return "";
        Object V = Rows.get(0).get("note");
        return V != null ? V.toString() : "";
    }

    @Override
    public boolean CreateOperator(String Username, String PasswordHash, OperatorRole Role) {
        try {
            exec(
                "INSERT INTO tc2_operators (username,password_hash,role) VALUES (?,?,?)",
                Username,
                PasswordHash,
                Role.name()
            );
            return true;
        } catch (Exception E) {
            Logger.Error("CreateOperator failed: " + E.getMessage());
            return false;
        }
    }

    @Override
    public boolean ValidateOperator(String Username, String PasswordHash) {
        List<Map<String, Object>> Rows = query(
            "SELECT 1 FROM tc2_operators WHERE username=? AND password_hash=?",
            Username,
            PasswordHash
        );
        return !Rows.isEmpty();
    }

    @Override
    public OperatorRole GetOperatorRole(String Username) {
        List<Map<String, Object>> Rows = query("SELECT role FROM tc2_operators WHERE username=?", Username);
        if (Rows.isEmpty()) return OperatorRole.VIEWER;
        Object V = Rows.get(0).get("role");
        return OperatorRole.FromString(V != null ? V.toString() : "VIEWER");
    }

    @Override
    public List<Map<String, Object>> GetOperators() {
        return query("SELECT username,role,created FROM tc2_operators ORDER BY created");
    }

    @Override
    public boolean UpdateOperatorRole(String Username, OperatorRole Role) {
        exec("UPDATE tc2_operators SET role=? WHERE username=?", Role.name(), Username);
        return true;
    }

    @Override
    public boolean DeleteOperator(String Username) {
        if (Username.equals("admin")) return false;
        exec("DELETE FROM tc2_operators WHERE username=?", Username);
        return true;
    }

    @Override
    public void Close() {
        try {
            if (Conn != null && !Conn.isClosed()) Conn.close();
        } catch (Exception Ignored) {}
    }

    private void exec(String Sql, Object... Params) {
        try (PreparedStatement Ps = Conn.prepareStatement(Sql)) {
            for (int I = 0; I < Params.length; I++) Ps.setObject(I + 1, Params[I]);
            Ps.executeUpdate();
        } catch (SQLException E) {
            Logger.Error("DB exec failed: " + E.getMessage());
        }
    }

    private List<Map<String, Object>> query(String Sql, Object... Params) {
        List<Map<String, Object>> Rows = new ArrayList<>();
        try (PreparedStatement Ps = Conn.prepareStatement(Sql)) {
            for (int I = 0; I < Params.length; I++) Ps.setObject(I + 1, Params[I]);
            try (ResultSet Rs = Ps.executeQuery()) {
                ResultSetMetaData Meta = Rs.getMetaData();
                int Cols = Meta.getColumnCount();
                while (Rs.next()) {
                    Map<String, Object> Row = new LinkedHashMap<>();
                    for (int I = 1; I <= Cols; I++) Row.put(Meta.getColumnName(I), Rs.getObject(I));
                    Rows.add(Row);
                }
            }
        } catch (SQLException E) {
            Logger.Error("DB query failed: " + E.getMessage());
        }
        return Rows;
    }

    private static String str(Map<String, Object> M, String K) {
        Object V = M.get(K);
        return V != null ? V.toString() : "";
    }
}
