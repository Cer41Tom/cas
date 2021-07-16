package org.apereo.cas.support.saml.web.idp.profile.sso;

import org.apereo.cas.mock.MockTicketGrantingTicket;
import org.apereo.cas.support.saml.BaseSamlIdPConfigurationTests;
import org.apereo.cas.support.saml.SamlProtocolConstants;
import org.apereo.cas.support.saml.SamlUtils;
import org.apereo.cas.support.saml.services.SamlRegisteredService;
import org.apereo.cas.support.saml.services.idp.metadata.SamlRegisteredServiceServiceProviderMetadataFacade;
import org.apereo.cas.support.saml.web.idp.profile.slo.SamlIdPHttpRedirectDeflateEncoder;
import org.apereo.cas.util.EncodingUtils;

import lombok.SneakyThrows;
import lombok.val;
import net.shibboleth.utilities.java.support.net.URLBuilder;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObjectBuilder;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Issuer;
import org.pac4j.core.context.JEEContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is {@link SSOSamlIdPPostProfileHandlerControllerTests}.
 *
 * @author Misagh Moayyed
 * @since 6.2.0
 */
@Tag("SAML")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
    "cas.tgc.crypto.enabled=false",
    "cas.authn.saml-idp.metadata.file-system.location=file:src/test/resources/metadata"
})
public class SSOSamlIdPPostProfileHandlerControllerTests extends BaseSamlIdPConfigurationTests {
    @Autowired
    @Qualifier("ssoPostProfileHandlerController")
    private SSOSamlIdPPostProfileHandlerController controller;

    private SamlRegisteredService samlRegisteredService;

    @BeforeEach
    public void beforeEach() {
        samlRegisteredService = getSamlRegisteredServiceFor(false, false,
            false, "https://cassp.example.org");
        servicesManager.save(samlRegisteredService);
    }

    @Test
    @Order(1)
    public void verifyPostSignRequest() throws Exception {
        val request = new MockHttpServletRequest();
        request.setMethod("POST");
        val response = new MockHttpServletResponse();
        val authnRequest = signAuthnRequest(request, response, getAuthnRequest());
        val xml = SamlUtils.transformSamlObject(openSamlConfigBean, authnRequest).toString();
        request.addParameter(SamlProtocolConstants.PARAMETER_SAML_REQUEST, EncodingUtils.encodeBase64(xml));
        val mv = controller.handleSaml2ProfileSsoPostRequest(response, request);
        assertEquals(HttpStatus.FOUND, mv.getStatus());
    }

    @Test
    @Order(2)
    public void verifyRedirectRequest() throws Exception {
        val request = new MockHttpServletRequest();
        request.setMethod("GET");
        val response = new MockHttpServletResponse();
        val authnRequest = signAuthnRequest(request, response, getAuthnRequest());

        val encoder = new SamlIdPHttpRedirectDeflateEncoder("https://cas.example.org/login", authnRequest);
        encoder.doEncode();
        val queryStrings = StringUtils.remove(encoder.getRedirectUrl(), "https://cas.example.org/login?");
        new URLBuilder(encoder.getRedirectUrl())
            .getQueryParams().forEach(param -> request.addParameter(param.getFirst(), param.getSecond()));
        request.setQueryString(queryStrings);

        val mv = controller.handleSaml2ProfileSsoRedirectRequest(response, request);
        assertEquals(HttpStatus.FOUND, mv.getStatus());
    }

    @Test
    @Order(3)
    public void verifyPutRequest() {
        val request = new MockHttpServletRequest();
        request.setMethod("PUT");
        val response = new MockHttpServletResponse();
        val mv = controller.handleSaml2ProfileSsoRedirectHeadRequest(response, request);
        assertEquals(HttpStatus.BAD_REQUEST, mv.getStatus());
    }

    @Test
    @Order(3)
    public void verifyBadRequest() throws Exception {
        val request = new MockHttpServletRequest();
        request.setMethod("POST");
        val response = new MockHttpServletResponse();
        request.addParameter(SamlProtocolConstants.PARAMETER_SAML_REQUEST, "Bad Data");
        val mv = controller.handleSaml2ProfileSsoPostRequest(response, request);
        assertEquals(HttpStatus.BAD_REQUEST, mv.getStatus());
    }

    @Test
    @Order(4)
    public void verifyPostRequest() throws Exception {
        val request = new MockHttpServletRequest();
        request.setMethod("POST");
        val response = new MockHttpServletResponse();
        val authnRequest = getAuthnRequest();
        val xml = SamlUtils.transformSamlObject(openSamlConfigBean, authnRequest).toString();
        request.addParameter(SamlProtocolConstants.PARAMETER_SAML_REQUEST, EncodingUtils.encodeBase64(xml));
        val mv = controller.handleSaml2ProfileSsoPostRequest(response, request);
        assertEquals(HttpStatus.FOUND, mv.getStatus());
    }

    @Test
    @Order(5)
    public void verifyPostRequestWithSso() throws Exception {
        val response = new MockHttpServletResponse();
        val tgt = new MockTicketGrantingTicket("casuser");
        ticketRegistry.addTicket(tgt);
        ticketGrantingTicketCookieGenerator.addCookie(response, tgt.getId());
        val request = new MockHttpServletRequest();
        request.setCookies(response.getCookies());
        request.setMethod("POST");
        val authnRequest = getAuthnRequest();
        val xml = SamlUtils.transformSamlObject(openSamlConfigBean, authnRequest).toString();
        request.addParameter(SamlProtocolConstants.PARAMETER_SAML_REQUEST, EncodingUtils.encodeBase64(xml));
        samlIdPDistributedSessionStore.set(new JEEContext(request, response),
            SamlProtocolConstants.PARAMETER_SAML_RELAY_STATE, "relay-state");
        val mv = controller.handleSaml2ProfileSsoPostRequest(response, request);
        assertNull(mv);
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertNotNull(response.getContentAsString());
    }

    @Test
    @Order(6)
    public void verifyPostRequestWithSsoForcedAuthn() throws Exception {
        val response = new MockHttpServletResponse();
        val tgt = new MockTicketGrantingTicket("casuser");
        ticketRegistry.addTicket(tgt);
        ticketGrantingTicketCookieGenerator.addCookie(response, tgt.getId());
        val request = new MockHttpServletRequest();
        request.setCookies(response.getCookies());
        request.setMethod("POST");
        val authnRequest = getAuthnRequest();
        authnRequest.setForceAuthn(true);
        val xml = SamlUtils.transformSamlObject(openSamlConfigBean, authnRequest).toString();
        request.addParameter(SamlProtocolConstants.PARAMETER_SAML_REQUEST, EncodingUtils.encodeBase64(xml));
        samlIdPDistributedSessionStore.set(new JEEContext(request, response),
            SamlProtocolConstants.PARAMETER_SAML_RELAY_STATE, "relay-state");
        val mv = controller.handleSaml2ProfileSsoPostRequest(response, request);
        assertEquals(HttpStatus.FOUND, mv.getStatus());
    }

    @Test
    @Order(7)
    public void verifyPostRequestWithUnknownCookie() throws Exception {
        val response = new MockHttpServletResponse();
        val tgt = new MockTicketGrantingTicket("casuser");
        ticketGrantingTicketCookieGenerator.addCookie(response, tgt.getId());
        val request = new MockHttpServletRequest();
        request.setCookies(response.getCookies());
        request.setMethod("POST");
        val authnRequest = getAuthnRequest();
        val xml = SamlUtils.transformSamlObject(openSamlConfigBean, authnRequest).toString();
        request.addParameter(SamlProtocolConstants.PARAMETER_SAML_REQUEST, EncodingUtils.encodeBase64(xml));
        samlIdPDistributedSessionStore.set(new JEEContext(request, response),
            SamlProtocolConstants.PARAMETER_SAML_RELAY_STATE, "relay-state");
        val mv = controller.handleSaml2ProfileSsoPostRequest(response, request);
        assertEquals(HttpStatus.FOUND, mv.getStatus());
    }

    @SneakyThrows
    private AuthnRequest signAuthnRequest(final HttpServletRequest request,
                                          final HttpServletResponse response,
                                          final AuthnRequest authnRequest) {
        val adaptor = SamlRegisteredServiceServiceProviderMetadataFacade
            .get(samlRegisteredServiceCachingMetadataResolver, samlRegisteredService,
                samlRegisteredService.getServiceId()).get();
        return samlIdPObjectSigner.encode(authnRequest, samlRegisteredService,
            adaptor, response, request, SAMLConstants.SAML2_POST_BINDING_URI, authnRequest, new MessageContext());
    }

    private AuthnRequest getAuthnRequest() {
        var builder = (SAMLObjectBuilder) openSamlConfigBean.getBuilderFactory()
            .getBuilder(AuthnRequest.DEFAULT_ELEMENT_NAME);
        var authnRequest = (AuthnRequest) builder.buildObject();
        builder = (SAMLObjectBuilder) openSamlConfigBean.getBuilderFactory()
            .getBuilder(Issuer.DEFAULT_ELEMENT_NAME);
        val issuer = (Issuer) builder.buildObject();
        issuer.setValue(samlRegisteredService.getServiceId());
        authnRequest.setIssuer(issuer);
        return authnRequest;
    }
}
