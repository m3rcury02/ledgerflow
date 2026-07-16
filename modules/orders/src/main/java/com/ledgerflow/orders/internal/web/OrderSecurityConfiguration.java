package com.ledgerflow.orders.internal.web;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(OrderApiSecurityProperties.class)
public class OrderSecurityConfiguration {

  private static final String API_CONTENT_SECURITY_POLICY =
      "default-src 'none'; frame-ancestors 'none'; " + "base-uri 'none'; form-action 'none'";

  @Bean
  SecurityFilterChain orderSecurityFilterChain(
      HttpSecurity http,
      SecurityProblemHandler securityProblemHandler,
      OrderApiSecurityProperties securityProperties,
      WriteRateLimiter writeRateLimiter,
      ProblemDetailsFactory problemDetailsFactory,
      ObjectMapper objectMapper)
      throws Exception {
    ApiProtectionFilter apiProtectionFilter =
        new ApiProtectionFilter(
            securityProperties, writeRateLimiter, problemDetailsFactory, objectMapper);
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.POST, "/api/v1/orders")
                    .access(requires("ledgerflow.orders.write", "customer", "admin"))
                    .requestMatchers(HttpMethod.GET, "/api/v1/orders/*")
                    .access(requires("ledgerflow.orders.read", "customer", "admin"))
                    .requestMatchers(HttpMethod.GET, "/api/v1/operator/**")
                    .access(requires("ledgerflow.operations.read", "operator", "admin"))
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/operator/failed-operations/*/break-glass-retry")
                    .access(requires("ledgerflow.operations.break-glass", "operator", "admin"))
                    .requestMatchers(HttpMethod.POST, "/api/v1/operator/failed-operations/*/retry")
                    .access(requires("ledgerflow.operations.retry", "operator", "admin"))
                    .anyRequest()
                    .denyAll())
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives(API_CONTENT_SECURITY_POLICY))
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .addHeaderWriter(
                        new StaticHeadersWriter(
                            "Permissions-Policy",
                            "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                    .addHeaderWriter(
                        new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-site"))
                    .addHeaderWriter(
                        new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin")))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(securityProblemHandler)
                    .accessDeniedHandler(securityProblemHandler))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    .authenticationEntryPoint(securityProblemHandler)
                    .accessDeniedHandler(securityProblemHandler))
        .addFilterAfter(apiProtectionFilter, AuthorizationFilter.class);
    return http.build();
  }

  @Bean
  WriteRateLimiter orderWriteRateLimiter(OrderApiSecurityProperties properties) {
    return new WriteRateLimiter(properties, Clock.systemUTC());
  }

  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new LedgerFlowJwtAuthoritiesConverter());
    return converter;
  }

  private AuthorizationManager<RequestAuthorizationContext> requires(
      String scope, String... roles) {
    return AuthorizationManagers.allOf(
        AuthorityAuthorizationManager.hasAuthority("SCOPE_" + scope),
        AuthorityAuthorizationManager.hasAnyRole(roles));
  }
}
