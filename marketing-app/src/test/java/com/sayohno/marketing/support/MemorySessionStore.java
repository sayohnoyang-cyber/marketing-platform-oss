package com.sayohno.marketing.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.identity.session.SessionStore;
import com.sayohno.marketing.identity.session.UserSession;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class MemorySessionStore implements SessionStore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(String token, UserSession session, Duration ttl) {
        try {
            sessions.put(token, objectMapper.writeValueAsString(session));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public Optional<UserSession> find(String token) {
        String json = sessions.get(token);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, UserSession.class));
        } catch (Exception exception) {
            sessions.remove(token);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String token) {
        sessions.remove(token);
    }
}
