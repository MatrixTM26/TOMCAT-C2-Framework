package com.tomcat.core.db;

import com.tomcat.core.output.Logger;
import com.tomcat.utils.ServerConfig;
import java.util.*;

public abstract class TeamDatabase {

    public enum OperatorRole {
        ADMIN,
        OPERATOR,
        VIEWER;

        public boolean CanExecute() {
            return this == ADMIN || this == OPERATOR;
        }

        public boolean CanBroadcast() {
            return this == ADMIN || this == OPERATOR;
        }

        public boolean CanKill() {
            return this == ADMIN;
        }

        public boolean CanManage() {
            return this == ADMIN;
        }

        public boolean CanView() {
            return true;
        }

        public static OperatorRole FromString(String S) {
            if (S == null) return VIEWER;
            return switch (S.trim().toUpperCase()) {
                case "ADMIN" -> ADMIN;
                case "OPERATOR" -> OPERATOR;
                default -> VIEWER;
            };
        }
    }

    public static TeamDatabase Connect(ServerConfig Config) {
        String Type = Config.GetDbType().toLowerCase();
        try {
            return switch (Type) {
                case "postgresql", "postgres" -> new PostgresDatabase(Config);
                case "mongodb", "mongo" -> new MongoDatabase(Config);
                default -> {
                    Logger.Info("DB disabled — using in-memory store");
                    yield new MemoryDatabase();
                }
            };
        } catch (Exception E) {
            Logger.Warn("DB connection failed (" + Type + "): " + E.getMessage() + " — fallback to memory");
            return new MemoryDatabase();
        }
    }

    public abstract boolean IsConnected();

    public abstract void SaveLog(String Entry);

    public abstract void SaveCommandLog(int AgentId, String Operator, String Command, String Output, boolean Success);

    public abstract void SaveSessionEvent(Map<String, Object> Data, String Event);

    public abstract List<Map<String, Object>> GetCommandHistory(int AgentId, int Limit);

    public abstract List<Map<String, Object>> GetSessionHistory(int Limit);

    public abstract void SetAgentNote(int AgentId, String Note);

    public abstract String GetAgentNote(int AgentId);

    public abstract boolean CreateOperator(String Username, String PasswordHash, OperatorRole Role);

    public abstract boolean ValidateOperator(String Username, String PasswordHash);

    public abstract OperatorRole GetOperatorRole(String Username);

    public abstract List<Map<String, Object>> GetOperators();

    public abstract boolean UpdateOperatorRole(String Username, OperatorRole Role);

    public abstract boolean DeleteOperator(String Username);

    public abstract void Close();

    public static String HashPassword(String Password) {
        try {
            java.security.MessageDigest Md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] Hash = Md.digest((Password + "TOMCAT-C2-SALT").getBytes("UTF-8"));
            StringBuilder Hex = new StringBuilder();
            for (byte B : Hash) Hex.append(String.format("%02x", B));
            return Hex.toString();
        } catch (Exception E) {
            throw new RuntimeException("Hash failed: " + E.getMessage());
        }
    }
}
