package com.etolv.neo4jtool.web;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.stereotype.Component;

/** Gives every new session the length the settings ask for: how long a sign-in lasts without any activity. */
@Component
class SessionLength implements HttpSessionListener {

    private static final int FALLBACK_HOURS = 12;

    private final SettingsStore settings;

    SessionLength(SettingsStore settings) {
        this.settings = settings;
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        int hours;
        try {
            hours = settings.get().sessionHours();
        } catch (RuntimeException e) {
            hours = FALLBACK_HOURS;
        }
        event.getSession().setMaxInactiveInterval(hours * 3600);
    }
}
