package com.ledgerflow.orders.internal.web;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

final class LedgerFlowJwtAuthoritiesConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final Set<String> ALLOWED_REALM_ROLES = Set.of("customer", "operator", "admin");

  private final JwtGrantedAuthoritiesConverter scopeAuthorities =
      new JwtGrantedAuthoritiesConverter();

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopeAuthorities.convert(jwt));
    Object realmAccess = jwt.getClaim("realm_access");
    if (!(realmAccess instanceof Map<?, ?> realmClaims)) {
      return authorities;
    }
    Object roles = realmClaims.get("roles");
    if (!(roles instanceof Collection<?> roleClaims)) {
      return authorities;
    }
    roleClaims.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(ALLOWED_REALM_ROLES::contains)
        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
        .forEach(authorities::add);
    return authorities;
  }
}
