package com.tomcat.core.event;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EventManager {
    public enum EventType {
        ServerStarted, ServerStopped,
        AgentConnected, AgentDisconnected, AgentRemoved,
        Error, CommandExecuted
    }

    @FunctionalInterface
    public interface EventListener {
        void OnEvent(EventType Type, Map<String, Object> Data);
    }

    private final CopyOnWriteArrayList<EventListener> Listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService Executor = Executors.newCachedThreadPool(R -> {
        Thread T = new Thread(R, "EventDispatcher");
        T.setDaemon(true);
        return T;
    });

    public void AddListener(EventListener Listener) {
        Listeners.add(Listener);
    }

    public void RemoveListener(EventListener Listener) {
        Listeners.remove(Listener);
    }

    public void Trigger(EventType Type, Map<String, Object> Data) {
        List<EventListener> Snapshot = new ArrayList<>(Listeners);
        Snapshot.forEach(L -> Executor.submit(() -> {
            try { L.OnEvent(Type, Data); } catch (Exception Ignored) {}
        }));
    }

    public void Trigger(EventType Type) {
        Trigger(Type, new HashMap<>());
    }

    public static Map<String, Object> BuildData(Object... KeyValuePairs) {
        Map<String, Object> Data = new LinkedHashMap<>();
        for (int I = 0; I + 1 < KeyValuePairs.length; I += 2) {
            Data.put(String.valueOf(KeyValuePairs[I]), KeyValuePairs[I + 1]);
        }
        return Data;
    }

    public void Shutdown() {
        Executor.shutdown();
    }
}