package com.fluxyBackend.support;

import com.fluxyBackend.controller.AuthResponse;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.entity.UserSession;
import com.fluxyBackend.security.SessionService;
import org.springframework.mock.web.MockHttpServletRequest;

/** Abre una sesión real para las pruebas: el filtro JWT exige una sesión activa. */
public final class TestAuth {

    private TestAuth() {
    }

    public static AuthResponse session(SessionService sessions, User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36");
        return sessions.create(user, UserSession.AuthMethod.PASSWORD, false, request);
    }

    public static String bearer(SessionService sessions, User user) {
        return "Bearer " + session(sessions, user).token;
    }
}
