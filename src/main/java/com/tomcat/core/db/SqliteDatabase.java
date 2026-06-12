package com.tomcat.core.db;

import com.tomcat.core.output.Logger;
import com.tomcat.utils.ServerConfig;
import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class SqliteDatabase extends TeamDatabase {

    private static final DateTimeFormatter Fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Connection Conn;
    private boolean Connected = false;

    public SqliteDatabase(ServerConfig Config) throws Exception {
        String DbDir = Config.GetDbPath();
        String DbFile = DbDir + "/tomcat_c2.db";
        Files.createDirectories(Paths.get(DbDir));
        Class.forName("org.sqlite.JDBC");
        Conn = DriverManager.getConnection("jdbc:sqlite:" + DbFile);
        Connected = true;
        CreateTables();
        SeedAdmin();
        Logger.Info("SQLite database: " + Paths.get(DbFile).toAbsolutePath());
    }

    private void CreateTables() throws Exception {
        try (Statement St = Conn.createStatement()) {
            St.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS Logs (
                    Id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    Entry     TEXT    NOT NULL,
                    CreatedAt TEXT    NOT NULL
                )"""
            );
            St.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS CommandLogs (
                    Id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    AgentId   INTEGER NOT NULL,
                    Operator  TEXT    NOT NULL,
                    Command   TEXT    NOT NULL,
                    Output    TEXT,
                    Success   INTEGER NOT NULL DEFAULT 0,
                    Timestamp TEXT    NOT NULL
                )"""
            );
            St.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS SessionEvents (
                    Id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    AgentId   TEXT,
                    Hostname  TEXT,
                    OS        TEXT,
                    User      TEXT,
                    AgentIP   TEXT,
                    Event     TEXT    NOT NULL,
                    Timestamp TEXT    NOT NULL
                )"""
            );
            St.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS AgentNotes (
                    AgentId   INTEGER PRIMARY KEY,
                    Note      TEXT    NOT NULL DEFAULT ''
                )"""
            );
            St.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS Operators (
                    Username     TEXT PRIMARY KEY,
                    PasswordHash TEXT NOT NULL,
                    Role         TEXT NOT NULL DEFAULT 'VIEWER',
                    CreatedAt    TEXT NOT NULL
                )"""
            );
        }
    }

    private void SeedAdmin() throws Exception {
        try (
            PreparedStatement Ps = Conn.prepareStatement(
                "INSERT OR IGNORE INTO Operators (Username, PasswordHash, Role, CreatedAt) VALUES (?,?,?,?)"
            )
        ) {
            Ps.setString(1, "admin");
            Ps.setString(2, HashPassword("admin"));
            Ps.setString(3, OperatorRole.ADMIN.name());
            Ps.setString(4, LocalDateTime.now().format(Fmt));
            Ps.executeUpdate();
        }
    }

    @Override
    public boolean IsConnected() {
        return Connected;
    }

    @Override
    public void SaveLog(String Entry) {
        try (PreparedStatement Ps = Conn.prepareStatement("INSERT INTO Logs (Entry, CreatedAt) VALUES (?, ?)")) {
            Ps.setString(1, Entry);
            Ps.setString(2, LocalDateTime.now().format(Fmt));
            Ps.executeUpdate();
        } catch (Exception E) {
            Logger.Verbose("SQLite SaveLog: " + E.getMessage());
        }
    }

    @Override
    public void SaveCommandLog(int AgentId, String Operator, String Command, String Output, boolean Success) {
        try (
            PreparedStatement Ps = Conn.prepareStatement(
                "INSERT INTO CommandLogs (AgentId,Operator,Command,Output,Success,Timestamp) VALUES (?,?,?,?,?,?)"
            )
        ) {
            Ps.setInt(1, AgentId);
            Ps.setString(2, Operator);
            Ps.setString(3, Command);
            Ps.setString(4, Output);
            Ps.setInt(5, Success ? 1 : 0);
            Ps.setString(6, LocalDateTime.now().format(Fmt));
            Ps.executeUpdate();
        } catch (Exception E) {
            Logger.Verbose("SQLite SaveCommandLog: " + E.getMessage());
        }
    }

    @Override
    public void SaveSessionEvent(Map<String, Object> Data, String Event) {
        try (
            PreparedStatement Ps = Conn.prepareStatement(
                "INSERT INTO SessionEvents (AgentId,Hostname,OS,User,AgentIP,Event,Timestamp) VALUES (?,?,?,?,?,?,?)"
            )
        ) {
            Ps.setString(1, Str(Data, "ID"));
            Ps.setString(2, Str(Data, "Hostname"));
            Ps.setString(3, Str(Data, "OS"));
            Ps.setString(4, Str(Data, "User"));
            Ps.setString(5, Str(Data, "AgentIP"));
            Ps.setString(6, Event);
            Ps.setString(7, LocalDateTime.now().format(Fmt));
            Ps.executeUpdate();
        } catch (Exception E) {
            Logger.Verbose("SQLite SaveSessionEvent: " + E.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> GetCommandHistory(int AgentId, int Limit) {
        List<Map<String, Object>> List = new ArrayList<>();
        String Sql = AgentId == 0
            ? "SELECT * FROM CommandLogs ORDER BY Id DESC LIMIT ?"
            : "SELECT * FROM CommandLogs WHERE AgentId=? ORDER BY Id DESC LIMIT ?";
        try (PreparedStatement Ps = Conn.prepareStatement(Sql)) {
            if (AgentId == 0) {
                Ps.setInt(1, Limit);
            } else {
                Ps.setInt(1, AgentId);
                Ps.setInt(2, Limit);
            }
            ResultSet Rs = Ps.executeQuery();
            while (Rs.next()) {
                Map<String, Object> Row = new LinkedHashMap<>();
                Row.put("AgentId", Rs.getInt("AgentId"));
                Row.put("Operator", Rs.getString("Operator"));
                Row.put("Command", Rs.getString("Command"));
                Row.put("Output", Rs.getString("Output"));
                Row.put("Success", Rs.getInt("Success") == 1);
                Row.put("Timestamp", Rs.getString("Timestamp"));
                List.add(Row);
            }
        } catch (Exception E) {
            Logger.Verbose("SQLite GetCommandHistory: " + E.getMessage());
        }
        return List;
    }

    @Override
    public List<Map<String, Object>> GetSessionHistory(int Limit) {
        List<Map<String, Object>> List = new ArrayList<>();
        try (PreparedStatement Ps = Conn.prepareStatement("SELECT * FROM SessionEvents ORDER BY Id DESC LIMIT ?")) {
            Ps.setInt(1, Limit);
            ResultSet Rs = Ps.executeQuery();
            while (Rs.next()) {
                Map<String, Object> Row = new LinkedHashMap<>();
                Row.put("ID", Rs.getString("AgentId"));
                Row.put("Hostname", Rs.getString("Hostname"));
                Row.put("OS", Rs.getString("OS"));
                Row.put("User", Rs.getString("User"));
                Row.put("AgentIP", Rs.getString("AgentIP"));
                Row.put("Event", Rs.getString("Event"));
                Row.put("Timestamp", Rs.getString("Timestamp"));
                List.add(Row);
            }
        } catch (Exception E) {
            Logger.Verbose("SQLite GetSessionHistory: " + E.getMessage());
        }
        return List;
    }

    @Override
    public void SetAgentNote(int AgentId, String Note) {
        try (
            PreparedStatement Ps = Conn.prepareStatement(
                "INSERT OR REPLACE INTO AgentNotes (AgentId, Note) VALUES (?, ?)"
            )
        ) {
            Ps.setInt(1, AgentId);
            Ps.setString(2, Note);
            Ps.executeUpdate();
        } catch (Exception E) {
            Logger.Verbose("SQLite SetAgentNote: " + E.getMessage());
        }
    }

    @Override
    public String GetAgentNote(int AgentId) {
        try (PreparedStatement Ps = Conn.prepareStatement("SELECT Note FROM AgentNotes WHERE AgentId=?")) {
            Ps.setInt(1, AgentId);
            ResultSet Rs = Ps.executeQuery();
            if (Rs.next()) return Rs.getString("Note");
        } catch (Exception E) {
            Logger.Verbose("SQLite GetAgentNote: " + E.getMessage());
        }
        return "";
    }

    @Override
    public boolean CreateOperator(String Username, String PasswordHash, OperatorRole Role) {
        try (
            PreparedStatement Ps = Conn.prepareStatement(
                "INSERT OR IGNORE INTO Operators (Username, PasswordHash, Role, CreatedAt) VALUES (?,?,?,?)"
            )
        ) {
            Ps.setString(1, Username);
            Ps.setString(2, PasswordHash);
            Ps.setString(3, Role.name());
            Ps.setString(4, LocalDateTime.now().format(Fmt));
            return Ps.executeUpdate() > 0;
        } catch (Exception E) {
            Logger.Verbose("SQLite CreateOperator: " + E.getMessage());
            return false;
        }
    }

    @Override
    public boolean ValidateOperator(String Username, String PasswordHash) {
        try (
            PreparedStatement Ps = Conn.prepareStatement("SELECT 1 FROM Operators WHERE Username=? AND PasswordHash=?")
        ) {
            Ps.setString(1, Username);
            Ps.setString(2, PasswordHash);
            return Ps.executeQuery().next();
        } catch (Exception E) {
            return false;
        }
    }

    @Override
    public OperatorRole GetOperatorRole(String Username) {
        try (PreparedStatement Ps = Conn.prepareStatement("SELECT Role FROM Operators WHERE Username=?")) {
            Ps.setString(1, Username);
            ResultSet Rs = Ps.executeQuery();
            if (Rs.next()) return OperatorRole.FromString(Rs.getString("Role"));
        } catch (Exception E) {
            Logger.Verbose("SQLite GetOperatorRole: " + E.getMessage());
        }
        return OperatorRole.VIEWER;
    }

    @Override
    public List<Map<String, Object>> GetOperators() {
        List<Map<String, Object>> List = new ArrayList<>();
        try (
            Statement St = Conn.createStatement();
            ResultSet Rs = St.executeQuery("SELECT Username, Role, CreatedAt FROM Operators")
        ) {
            while (Rs.next()) {
                Map<String, Object> Row = new LinkedHashMap<>();
                Row.put("Username", Rs.getString("Username"));
                Row.put("Role", Rs.getString("Role"));
                Row.put("CreatedAt", Rs.getString("CreatedAt"));
                List.add(Row);
            }
        } catch (Exception E) {
            Logger.Verbose("SQLite GetOperators: " + E.getMessage());
        }
        return List;
    }

    @Override
    public boolean UpdateOperatorRole(String Username, OperatorRole Role) {
        try (PreparedStatement Ps = Conn.prepareStatement("UPDATE Operators SET Role=? WHERE Username=?")) {
            Ps.setString(1, Role.name());
            Ps.setString(2, Username);
            return Ps.executeUpdate() > 0;
        } catch (Exception E) {
            Logger.Verbose("SQLite UpdateOperatorRole: " + E.getMessage());
            return false;
        }
    }

    @Override
    public boolean UpdateOperatorPassword(String Username, String PasswordHash) {
        try (PreparedStatement Ps = Conn.prepareStatement("UPDATE Operators SET PasswordHash=? WHERE Username=?")) {
            Ps.setString(1, PasswordHash);
            Ps.setString(2, Username);
            return Ps.executeUpdate() > 0;
        } catch (Exception E) {
            Logger.Verbose("SQLite UpdateOperatorPassword: " + E.getMessage());
            return false;
        }
    }

    @Override
    public boolean DeleteOperator(String Username) {
        if (Username.equals("admin")) return false;
        try (PreparedStatement Ps = Conn.prepareStatement("DELETE FROM Operators WHERE Username=?")) {
            Ps.setString(1, Username);
            return Ps.executeUpdate() > 0;
        } catch (Exception E) {
            Logger.Verbose("SQLite DeleteOperator: " + E.getMessage());
            return false;
        }
    }

    @Override
    public void Close() {
        try {
            if (Conn != null && !Conn.isClosed()) Conn.close();
        } catch (Exception Ignored) {}
        Connected = false;
    }

    private static String Str(Map<String, Object> M, String K) {
        Object V = M.get(K);
        return V != null ? V.toString() : "";
    }
}
