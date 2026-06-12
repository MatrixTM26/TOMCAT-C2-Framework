package com.tomcat.core.session;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SessionManager {

    private final ConcurrentHashMap<Integer, Session> Sessions = new ConcurrentHashMap<>();
    private final AtomicInteger Counter = new AtomicInteger(0);

    public int Add(Session S) {
        int Id = Counter.incrementAndGet();
        S.SetId(Id);
        Sessions.put(Id, S);
        return Id;
    }

    public Optional<Session> Get(int Id) {
        return Optional.ofNullable(Sessions.get(Id));
    }

    public void Remove(int Id) {
        Session S = Sessions.remove(Id);
        if (S != null) {
            try {
                S.GetSocket().close();
            } catch (Exception Ignored) {}
        }
    }

    public List<Session> GetAll() {
        return new ArrayList<>(Sessions.values());
    }

    public int Count() {
        return Sessions.size();
    }

    public int CountByType(Session.Type Type) {
        return (int) Sessions.values().stream().filter(S -> S.GetSessionType() == Type).count();
    }

    public void Clear() {
        Sessions.values()
            .forEach(S -> {
                try {
                    S.GetSocket().close();
                } catch (Exception Ignored) {}
            });
        Sessions.clear();
    }

    public boolean Exists(int Id) {
        return Sessions.containsKey(Id);
    }

    public Map<String, Integer> GetStats() {
        Map<String, Integer> Stats = new HashMap<>();
        Stats.put("Total", Count());
        Stats.put("TOMCAT", CountByType(Session.Type.TOMCAT));
        Stats.put("METERPRETER", CountByType(Session.Type.METERPRETER));
        Stats.put("REVERSE_SHELL", CountByType(Session.Type.REVERSE_SHELL));
        return Stats;
    }
}
