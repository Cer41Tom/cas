package org.apereo.cas.web.flow;

import org.apereo.cas.web.support.WebUtils;

import lombok.val;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.webflow.context.servlet.ServletExternalContext;
import org.springframework.webflow.execution.Action;
import org.springframework.webflow.test.MockRequestContext;

import jakarta.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is {@link TerminateSessionActionTests}.
 *
 * @author Misagh Moayyed
 * @since 5.3.0
 */
@TestPropertySource(properties = "cas.tgc.crypto.enabled=false")
@Tag("WebflowActions")
class TerminateSessionActionTests extends AbstractWebflowActionsTests {
    @Autowired
    @Qualifier(CasWebflowConstants.ACTION_ID_TERMINATE_SESSION)
    private Action action;

    @Test
    void verifyTerminateAction() throws Throwable {
        val context = new MockRequestContext();
        context.setExternalContext(new ServletExternalContext(new MockServletContext(), new MockHttpServletRequest(), new MockHttpServletResponse()));
        WebUtils.putTicketGrantingTicketInScopes(context, "TGT-123456-something");
        assertEquals(CasWebflowConstants.TRANSITION_ID_SUCCESS, this.action.execute(context).getId());
        assertNotNull(WebUtils.getLogoutRequests(context));
    }

    @Test
    void verifyTerminateActionByCookie() throws Throwable {
        val context = new MockRequestContext();
        val request = new MockHttpServletRequest();
        request.setCookies(new Cookie("TGC", "TGT-123456-something"));
        context.setExternalContext(new ServletExternalContext(new MockServletContext(), request, new MockHttpServletResponse()));
        assertEquals(CasWebflowConstants.TRANSITION_ID_SUCCESS, action.execute(context).getId());
        assertNotNull(WebUtils.getLogoutRequests(context));
    }
}
