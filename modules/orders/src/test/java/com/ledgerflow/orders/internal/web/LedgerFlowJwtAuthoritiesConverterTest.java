package com.ledgerflow.orders.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class LedgerFlowJwtAuthoritiesConverterTest {

  private final LedgerFlowJwtAuthoritiesConverter converter =
      new LedgerFlowJwtAuthoritiesConverter();

  @Test
  void combinesStandardScopesWithAllowlistedKeycloakRealmRoles() {
    Jwt jwt =
        jwtBuilder()
            .claim(
                "scope",
                "ledgerflow.orders.read ledgerflow.orders.write ledgerflow.operations.read")
            .claim(
                "realm_access",
                Map.of("roles", List.of("customer", "operator", "admin", "realm-manager")))
            .build();

    assertThat(converter.convert(jwt))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder(
            "SCOPE_ledgerflow.orders.read",
            "SCOPE_ledgerflow.orders.write",
            "SCOPE_ledgerflow.operations.read",
            "ROLE_customer",
            "ROLE_operator",
            "ROLE_admin");
  }

  @Test
  void ignoresMalformedOrUnapprovedRoleClaims() {
    Jwt malformed =
        jwtBuilder()
            .claim("scope", "ledgerflow.orders.read")
            .claim("realm_access", Map.of("roles", "customer"))
            .build();
    Jwt unapproved =
        jwtBuilder()
            .claim("realm_access", Map.of("roles", Arrays.asList("superuser", 42, null)))
            .build();

    assertThat(converter.convert(malformed))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("SCOPE_ledgerflow.orders.read");
    assertThat(converter.convert(unapproved)).isEmpty();
  }

  private Jwt.Builder jwtBuilder() {
    Instant now = Instant.parse("2026-07-14T07:31:00Z");
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .subject("security-test-subject")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300));
  }
}
