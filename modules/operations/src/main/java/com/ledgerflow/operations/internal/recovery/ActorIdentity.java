package com.ledgerflow.operations.internal.recovery;

import java.net.URL;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

record ActorIdentity(String issuer, String subject, String clientId) {

  static ActorIdentity authenticated(JwtAuthenticationToken authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new IllegalArgumentException("authenticated JWT identity is required");
    }
    URL issuer = authentication.getToken().getIssuer();
    String subject = authentication.getToken().getSubject();
    String clientId = authentication.getToken().getClaimAsString("azp");
    if (clientId == null) {
      clientId = authentication.getToken().getClaimAsString("client_id");
    }
    requireSafe(issuer == null ? null : issuer.toString(), 500, "issuer");
    requireSafe(subject, 200, "subject");
    if (clientId != null) {
      requireSafe(clientId, 200, "client ID");
    }
    return new ActorIdentity(issuer.toString(), subject, clientId);
  }

  private static void requireSafe(String value, int maximum, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("authenticated " + name + " is invalid");
    }
  }
}
