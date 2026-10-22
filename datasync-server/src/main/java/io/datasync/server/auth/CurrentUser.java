package io.datasync.server.auth;

import jakarta.enterprise.context.RequestScoped;

/** The signed-in user of the current request, set by {@link AuthFilter}. */
@RequestScoped
public class CurrentUser {

    private Sessions.Session session;

    void set(Sessions.Session session) {
        this.session = session;
    }

    public Sessions.Session session() {
        return session;
    }

    public String username() {
        return session == null ? null : session.username();
    }
}
