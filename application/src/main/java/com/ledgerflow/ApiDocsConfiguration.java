package com.ledgerflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Serves the hand-written {@code ledgerflow.yaml} contract via a static Swagger UI, gated behind a
 * dedicated env var so it is never exposed unless explicitly turned on for a local/demo run. This
 * whole class registers no beans - not even a permissive one - unless the flag is set, rather than
 * registering a chain that merely permits everything.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledgerflow.api-docs", name = "enabled", havingValue = "true")
public class ApiDocsConfiguration {

  // Swagger UI's bundled JS injects a <style> element at runtime (syntax highlighting for the
  // "try it out" response viewer), so style-src needs 'unsafe-inline'. No inline <script>s are
  // used anywhere in apidocs/, so script-src stays locked to 'self'.
  private static final String API_DOCS_CONTENT_SECURITY_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
          + "base-uri 'none'; form-action 'none'";

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityFilterChain apiDocsSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/docs/**", "/openapi/ledgerflow.yaml", "/webjars/swagger-ui/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp -> csp.policyDirectives(API_DOCS_CONTENT_SECURITY_POLICY)));
    return http.build();
  }

  @Bean
  ApiDocsController apiDocsController() {
    return new ApiDocsController();
  }
}
