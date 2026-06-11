package com.tomcat.core.db;

import com.google.gson.Gson;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.tomcat.core.output.Logger;
import com.tomcat.utils.ServerConfig;
import java.util.*;
import org.bson.Document;

public final class MongoDatabase extends TeamDatabase {

    private static final Gson GsonInst = new Gson();

    private final MongoClient Client;
    private final com.mongodb.client.MongoDatabase MongoDatabaseRef;
    private final MongoCollection<Document> ColLogs;
    private final MongoCollection<Document> ColCommands;
    private final MongoCollection<Document> ColSessions;
    private final MongoCollection<Document> ColNotes;
    private final MongoCollection<Document> ColOperators;

    public MongoDatabase(ServerConfig Config) throws Exception {
        String Url = Config.GetDbUrl();
        if (!Url.startsWith("mongodb://") && !Url.startsWith("mongodb+srv://")) {
            throw new Exception("Invalid MongoDB URL — must start with mongodb:// or mongodb+srv://");
        }
        try {
            Client = MongoClients.create(Url);
            MongoDatabaseRef = Client.getDatabase(Config.GetDbName());
            ColLogs = MongoDatabaseRef.getCollection("tc2_logs");
            ColCommands = MongoDatabaseRef.getCollection("tc2_commands");
            ColSessions = MongoDatabaseRef.getCollection("tc2_sessions");
            ColNotes = MongoDatabaseRef.getCollection("tc2_notes");
            ColOperators = MongoDatabaseRef.getCollection("tc2_operators");
            ColCommands.createIndex(Indexes.descending("created"));
            ColSessions.createIndex(Indexes.descending("created"));
            ColOperators.createIndex(Indexes.ascending("username"), new IndexOptions().unique(true));
            SeedDefaultAdmin();
            Logger.Info("MongoDB connected: " + Url + "/" + Config.GetDbName());
        } catch (Exception E) {
            throw new Exception("MongoDB connect failed: " + E.getMessage());
        }
    }

    private void SeedDefaultAdmin() {
        try {
            if (ColOperators.find(Filters.eq("username", "admin")).first() == null) {
                ColOperators.insertOne(
                    new Document()
                        .append("username", "admin")
                        .append("password_hash", HashPassword("admin"))
                        .append("role", OperatorRole.ADMIN.name())
                        .append("created", new java.util.Date())
                );
            }
        } catch (Exception Ignored) {}
    }

    @Override
    public boolean IsConnected() {
        try {
            Client.listDatabaseNames().first();
            return true;
        } catch (Exception E) {
            return false;
        }
    }

    @Override
    public void SaveLog(String Entry) {
        try {
            ColLogs.insertOne(new Document("entry", Entry).append("created", new java.util.Date()));
        } catch (Exception E) {
            Logger.Error("Mongo SaveLog: " + E.getMessage());
        }
    }

    @Override
    public void SaveCommandLog(int AgentId, String Operator, String Command, String Output, boolean Success) {
        try {
            ColCommands.insertOne(
                new Document()
                    .append("agent_id", AgentId)
                    .append("operator", Operator)
                    .append("command", Command)
                    .append("output", Output)
                    .append("success", Success)
                    .append("created", new java.util.Date())
            );
        } catch (Exception E) {
            Logger.Error("Mongo SaveCommandLog: " + E.getMessage());
        }
    }

    @Override
    public void SaveSessionEvent(Map<String, Object> Data, String Event) {
        try {
            Document Doc = new Document(Data);
            Doc.append("event", Event).append("created", new java.util.Date());
            ColSessions.insertOne(Doc);
        } catch (Exception E) {
            Logger.Error("Mongo SaveSessionEvent: " + E.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> GetCommandHistory(int AgentId, int Limit) {
        try {
            FindIterable<Document> Cur = AgentId == 0
                ? ColCommands.find()
                : ColCommands.find(Filters.eq("agent_id", AgentId));
            return DocToList(Cur.sort(Sorts.descending("created")).limit(Limit));
        } catch (Exception E) {
            Logger.Error("Mongo GetCommandHistory: " + E.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Map<String, Object>> GetSessionHistory(int Limit) {
        try {
            return DocToList(ColSessions.find().sort(Sorts.descending("created")).limit(Limit));
        } catch (Exception E) {
            Logger.Error("Mongo GetSessionHistory: " + E.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void SetAgentNote(int AgentId, String Note) {
        try {
            ColNotes.replaceOne(
                Filters.eq("agent_id", AgentId),
                new Document("agent_id", AgentId).append("note", Note).append("updated", new java.util.Date()),
                new ReplaceOptions().upsert(true)
            );
        } catch (Exception E) {
            Logger.Error("Mongo SetAgentNote: " + E.getMessage());
        }
    }

    @Override
    public String GetAgentNote(int AgentId) {
        try {
            Document Doc = ColNotes.find(Filters.eq("agent_id", AgentId)).first();
            if (Doc == null) return "";
            Object V = Doc.get("note");
            return V != null ? V.toString() : "";
        } catch (Exception E) {
            Logger.Error("Mongo GetAgentNote: " + E.getMessage());
            return "";
        }
    }

    @Override
    public boolean CreateOperator(String Username, String PasswordHash, OperatorRole Role) {
        try {
            ColOperators.insertOne(
                new Document()
                    .append("username", Username)
                    .append("password_hash", PasswordHash)
                    .append("role", Role.name())
                    .append("created", new java.util.Date())
            );
            return true;
        } catch (Exception E) {
            Logger.Error("Mongo CreateOperator: " + E.getMessage());
            return false;
        }
    }

    @Override
    public boolean ValidateOperator(String Username, String PasswordHash) {
        try {
            return (
                ColOperators.find(
                    Filters.and(Filters.eq("username", Username), Filters.eq("password_hash", PasswordHash))
                ).first() !=
                null
            );
        } catch (Exception E) {
            return false;
        }
    }

    @Override
    public OperatorRole GetOperatorRole(String Username) {
        try {
            Document Doc = ColOperators.find(Filters.eq("username", Username)).first();
            if (Doc == null) return OperatorRole.VIEWER;
            return OperatorRole.FromString(Doc.getString("role"));
        } catch (Exception E) {
            return OperatorRole.VIEWER;
        }
    }

    @Override
    public List<Map<String, Object>> GetOperators() {
        try {
            List<Map<String, Object>> Result = new ArrayList<>();
            ColOperators.find()
                .forEach(Doc -> {
                    Map<String, Object> Row = new LinkedHashMap<>(Doc);
                    Row.remove("_id");
                    Row.remove("password_hash");
                    Result.add(Row);
                });
            return Result;
        } catch (Exception E) {
            Logger.Error("Mongo GetOperators: " + E.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public boolean UpdateOperatorRole(String Username, OperatorRole Role) {
        try {
            ColOperators.updateOne(Filters.eq("username", Username), Updates.set("role", Role.name()));
            return true;
        } catch (Exception E) {
            Logger.Error("Mongo UpdateOperatorRole: " + E.getMessage());
            return false;
        }
    }

    @Override
    public boolean UpdateOperatorPassword(String Username, String PasswordHash) {
        try {
            MongoDatabaseRef.getCollection("operators").updateOne(
                new org.bson.Document("username", Username),
                new org.bson.Document("$set", new org.bson.Document("passwordHash", PasswordHash))
            );
            return true;
        } catch (Exception E) {
            Logger.Error("Mongo UpdateOperatorPassword: " + E.getMessage());
            return false;
        }
    }

    @Override
    public boolean DeleteOperator(String Username) {
        if (Username.equals("admin")) return false;
        try {
            ColOperators.deleteOne(Filters.eq("username", Username));
            return true;
        } catch (Exception E) {
            Logger.Error("Mongo DeleteOperator: " + E.getMessage());
            return false;
        }
    }

    @Override
    public void Close() {
        try {
            Client.close();
        } catch (Exception Ignored) {}
    }

    private List<Map<String, Object>> DocToList(FindIterable<Document> Cursor) {
        List<Map<String, Object>> Result = new ArrayList<>();
        Cursor.forEach(Doc -> {
            Map<String, Object> R = new LinkedHashMap<>(Doc);
            R.remove("_id");
            Result.add(R);
        });
        return Result;
    }
}
