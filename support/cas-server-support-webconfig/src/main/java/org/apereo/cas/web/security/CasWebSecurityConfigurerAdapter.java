package org.apereo.cas.web.security;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.core.monitor.ActuatorEndpointProperties;
import org.apereo.cas.configuration.model.core.monitor.JaasSecurityActuatorEndpointsMonitorProperties;
import org.apereo.cas.configuration.model.core.monitor.LdapSecurityActuatorEndpointsMonitorProperties;
import org.apereo.cas.util.LdapUtils;
import org.apereo.cas.util.function.FunctionUtils;
import org.apereo.cas.web.CasWebSecurityConfigurer;
import org.apereo.cas.web.CasWebSecurityConstants;
import org.apereo.cas.web.security.authentication.EndpointLdapAuthenticationProvider;
import org.apereo.cas.web.security.authentication.IpAddressAuthorizationManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jooq.lambda.Unchecked;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.jaas.JaasAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This is {@link CasWebSecurityConfigurerAdapter}.
 *
 * @author Misagh Moayyed
 * @since 6.0.0
 */
@Slf4j
@Order(CasWebSecurityConstants.SECURITY_CONFIGURATION_ORDER)
@RequiredArgsConstructor
public class CasWebSecurityConfigurerAdapter implements DisposableBean {
    private final CasConfigurationProperties casProperties;

    private final SecurityProperties securityProperties;

    private final WebEndpointProperties webEndpointProperties;

    private final ObjectProvider<PathMappedEndpoints> pathMappedEndpoints;

    private final List<CasWebSecurityConfigurer> protocolEndpointWebSecurityConfigurers;

    private final SecurityContextRepository securityContextRepository;

    private EndpointLdapAuthenticationProvider endpointLdapAuthenticationProvider;

    private static List<String> prepareProtocolEndpoint(final String endpoint) {
        val baseEndpoint = StringUtils.prependIfMissing(endpoint, "/");
        return List.of(baseEndpoint.concat("**"), StringUtils.appendIfMissing(endpoint, "/").concat("**"));
    }

    private static void configureJaasAuthenticationProvider(final HttpSecurity http,
                                                            final JaasSecurityActuatorEndpointsMonitorProperties jaas)
        throws Exception {
        val provider = new JaasAuthenticationProvider();
        provider.setLoginConfig(jaas.getLoginConfig());
        provider.setLoginContextName(jaas.getLoginContextName());
        provider.setRefreshConfigurationOnStartup(jaas.isRefreshConfigurationOnStartup());
        provider.afterPropertiesSet();
        http.authenticationProvider(provider);
    }

    @Override
    public void destroy() {
        FunctionUtils.doIfNotNull(endpointLdapAuthenticationProvider, EndpointLdapAuthenticationProvider::destroy);
    }

    /**
     * Configure web security for spring security.
     *
     * @param web web security
     */
    public void configureWebSecurity(final WebSecurity web) {
    }

    /**
     * Configure http security.
     *
     * @param http the http
     * @return the http security
     * @throws Exception the exception
     */
    public HttpSecurity configureHttpSecurity(final HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .headers(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requiresChannel(customizer -> customizer.requestMatchers(r -> r.getHeader("X-Forwarded-Proto") != null).requiresSecure());

        val patterns = protocolEndpointWebSecurityConfigurers.stream()
            .map(CasWebSecurityConfigurer::getIgnoredEndpoints)
            .flatMap(List<String>::stream)
            .map(CasWebSecurityConfigurerAdapter::prepareProtocolEndpoint)
            .flatMap(List::stream)
            .collect(Collectors.toList());

        patterns.add("/webjars/**");
        patterns.add("/themes/**");
        patterns.add("/js/**");
        patterns.add("/css/**");
        patterns.add("/images/**");
        patterns.add("/static/**");
        patterns.add("/error");
        patterns.add("/favicon.ico");
        patterns.add("/");
        patterns.add(webEndpointProperties.getBasePath());

        LOGGER.debug("Configuring protocol endpoints [{}] to exclude/ignore from http security", patterns);
        var requests = http.authorizeHttpRequests(customizer -> {
            val matchers = patterns.stream().map(AntPathRequestMatcher::new).toList().toArray(new RequestMatcher[0]);
            customizer.requestMatchers(matchers).permitAll();
        });

        protocolEndpointWebSecurityConfigurers.forEach(Unchecked.consumer(cfg -> cfg.configure(http)));

        val endpoints = casProperties.getMonitor().getEndpoints().getEndpoint();
        endpoints.forEach(Unchecked.biConsumer((key, endpointProps) -> {
            val endpoint = EndpointRequest.to(key);
            endpointProps.getAccess().forEach(Unchecked.consumer(
                access -> configureEndpointAccess(requests, access, endpointProps, endpoint)));
        }));
        configureEndpointAccessToDenyUndefined(requests);
        configureEndpointAccessForStaticResources(requests);
        configureEndpointAccessByFormLogin(requests);

        val jaas = casProperties.getMonitor().getEndpoints().getJaas();
        if (jaas.getLoginConfig() != null) {
            configureJaasAuthenticationProvider(http, jaas);
        } else {
            LOGGER.trace("No JAAS login config is defined to enable JAAS authentication");
        }

        val ldap = casProperties.getMonitor().getEndpoints().getLdap();
        if (StringUtils.isNotBlank(ldap.getLdapUrl()) && StringUtils.isNotBlank(ldap.getSearchFilter())) {
            configureLdapAuthenticationProvider(http, ldap);
        } else {
            LOGGER.trace("No LDAP url or search filter is defined to enable LDAP authentication");
        }

        http.securityContext(securityContext -> securityContext.securityContextRepository(securityContextRepository));
        protocolEndpointWebSecurityConfigurers.forEach(Unchecked.consumer(cfg -> cfg.finish(http)));
        return http;
    }


    protected void configureEndpointAccessToDenyUndefined(
        final HttpSecurity http) {
        val endpoints = casProperties.getMonitor().getEndpoints().getEndpoint().keySet();
        val endpointDefaults = casProperties.getMonitor().getEndpoints().getDefaultEndpointProperties();
        pathMappedEndpoints.getObject().forEach(endpoint -> {
            val rootPath = endpoint.getRootPath();
            if (endpoints.contains(rootPath)) {
                LOGGER.trace("Endpoint security is defined for endpoint [{}]", rootPath);
            } else {
                val defaultAccessRules = endpointDefaults.getAccess();
                LOGGER.trace("Endpoint security is NOT defined for endpoint [{}]. Using default security rules [{}]", rootPath, endpointDefaults);
                val endpointRequest = EndpointRequest.to(rootPath).excludingLinks();
                defaultAccessRules.forEach(Unchecked.consumer(access ->
                    configureEndpointAccess(http, access, endpointDefaults, endpointRequest)));
            }
        });
    }

    protected void configureEndpointAccessForStaticResources(final HttpSecurity requests) throws Exception {
        requests.authorizeHttpRequests(customizer -> {
            customizer.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll();
            customizer.requestMatchers(new AntPathRequestMatcher("/resources/**")).permitAll();
            customizer.requestMatchers(new AntPathRequestMatcher("/static/**")).permitAll();
        });
    }

    protected void configureEndpointAccessByFormLogin(final HttpSecurity http) throws Exception {
        if (casProperties.getMonitor().getEndpoints().isFormLoginEnabled()) {
            http.formLogin(customize -> customize.loginPage(CasWebSecurityConfigurer.ENDPOINT_URL_ADMIN_FORM_LOGIN).permitAll());
        } else {
            http.formLogin(AbstractHttpConfigurer::disable);
        }
    }

    protected void configureEndpointAccess(final HttpSecurity httpSecurity,
                                           final ActuatorEndpointProperties.EndpointAccessLevel access,
                                           final ActuatorEndpointProperties properties,
                                           final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        switch (access) {
            case AUTHORITY -> configureEndpointAccessByAuthority(httpSecurity, properties, endpoint);
            case ROLE -> configureEndpointAccessByRole(httpSecurity, properties, endpoint);
            case AUTHENTICATED -> configureEndpointAccessAuthenticated(httpSecurity, endpoint);
            case IP_ADDRESS -> configureEndpointAccessByIpAddress(httpSecurity, properties, endpoint);
            case PERMIT -> configureEndpointAccessPermitAll(httpSecurity, endpoint);
            case ANONYMOUS -> configureEndpointAccessAnonymously(httpSecurity, endpoint);
            default -> configureEndpointAccessToDenyAll(httpSecurity, endpoint);
        }
    }

    protected void configureEndpointAccessPermitAll(final HttpSecurity requests,
                                                    final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        requests.authorizeHttpRequests(customizer -> customizer.requestMatchers(endpoint).permitAll());
    }

    protected void configureEndpointAccessToDenyAll(final HttpSecurity requests,
                                                    final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        requests.authorizeHttpRequests(customizer -> customizer.requestMatchers(endpoint).denyAll());
    }

    protected void configureEndpointAccessAnonymously(final HttpSecurity requests,
                                                      final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {

        requests.authorizeHttpRequests(customizer -> customizer.requestMatchers(endpoint).permitAll());
    }

    protected void configureEndpointAccessByIpAddress(final HttpSecurity requests,
                                                      final ActuatorEndpointProperties properties,
                                                      final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        requests.authorizeHttpRequests(customizer -> customizer.requestMatchers(endpoint)
            .access(new IpAddressAuthorizationManager(casProperties, properties)));
    }

    protected void configureEndpointAccessAuthenticated(
        final HttpSecurity http,
        final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        http.authorizeHttpRequests(customizer ->
            customizer.requestMatchers(endpoint).authenticated());
        http.httpBasic(customizer -> {
        });
    }

    protected void configureEndpointAccessByRole(
        final HttpSecurity http,
        final ActuatorEndpointProperties properties,
        final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        http.authorizeHttpRequests(customizer ->
            customizer.requestMatchers(endpoint).hasAnyRole(properties.getRequiredRoles().toArray(ArrayUtils.EMPTY_STRING_ARRAY)));
        http.httpBasic(customizer -> {
        });
    }

    protected void configureEndpointAccessByAuthority(
        final HttpSecurity http,
        final ActuatorEndpointProperties properties,
        final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        http.authorizeHttpRequests(customizer ->
            customizer.requestMatchers(endpoint).hasAnyAuthority(properties.getRequiredAuthorities().toArray(ArrayUtils.EMPTY_STRING_ARRAY)));
        http.httpBasic(customizer -> {
        });
    }

    private boolean isLdapAuthorizationActive() {
        val ldap = casProperties.getMonitor().getEndpoints().getLdap();
        return StringUtils.isNotBlank(ldap.getBaseDn())
            && StringUtils.isNotBlank(ldap.getLdapUrl())
            && StringUtils.isNotBlank(ldap.getSearchFilter())
            && (StringUtils.isNotBlank(ldap.getLdapAuthz().getRoleAttribute())
            || StringUtils.isNotBlank(ldap.getLdapAuthz().getGroupAttribute()));
    }

    private void configureLdapAuthenticationProvider(final HttpSecurity http,
                                                     final LdapSecurityActuatorEndpointsMonitorProperties ldap) {
        if (isLdapAuthorizationActive()) {
            val connectionFactory = LdapUtils.newLdaptiveConnectionFactory(ldap);
            val authenticator = LdapUtils.newLdaptiveAuthenticator(ldap);
            this.endpointLdapAuthenticationProvider = new EndpointLdapAuthenticationProvider(ldap,
                securityProperties, connectionFactory, authenticator);
            http.authenticationProvider(endpointLdapAuthenticationProvider);
        }
    }
}
