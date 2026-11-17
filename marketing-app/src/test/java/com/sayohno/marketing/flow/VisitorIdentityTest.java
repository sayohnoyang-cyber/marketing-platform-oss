package com.sayohno.marketing.flow;

import com.sayohno.marketing.common.web.VisitorIdentity;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class VisitorIdentityTest {
    @Test void cookieIsSharedButCallerHeadersCannotForgeTheTrackedVisitor() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Visitor-Id","caller-chosen-identity");
        var response = new MockHttpServletResponse();
        String id = VisitorIdentity.resolve(request,response);
        assertNotEquals("caller-chosen-identity",id);
        assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
        var repeat = new MockHttpServletRequest();
        repeat.setCookies(new Cookie("marketing_visitor",id));
        assertEquals(id,VisitorIdentity.resolve(repeat,new MockHttpServletResponse()));
        request.addHeader("User-Agent","Googlebot");
        assertFalse(VisitorIdentity.humanCandidate(request));
        repeat.addHeader("User-Agent","Mozilla/5.0 Chrome");
        assertTrue(VisitorIdentity.humanCandidate(repeat));
    }
}
