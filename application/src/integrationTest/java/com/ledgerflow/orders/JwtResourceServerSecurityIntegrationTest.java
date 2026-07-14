package com.ledgerflow.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(JwtResourceServerSecurityIntegrationTest.TestJwtDecoderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtResourceServerSecurityIntegrationTest extends PostgreSqlIntegrationTest {

  private static final String ISSUER = "https://issuer.security-test.invalid";
  private static final String AUDIENCE = "ledgerflow-api";
  private static final KeyPair SIGNING_KEY = generateRsaKey();

  @Autowired private MockMvc mockMvc;

  @Test
  void acceptsOnlyAValidSignedTokenWithExactAudienceScopeAndRole() throws Exception {
    String token =
        token(
            SIGNING_KEY,
            ISSUER,
            List.of(AUDIENCE),
            "valid-customer",
            "ledgerflow.orders.read",
            List.of("customer"),
            Instant.now().minusSeconds(5),
            Instant.now().plusSeconds(300));

    mockMvc
        .perform(
            get("/api/v1/orders/{orderId}", UUID.randomUUID())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource_not_found"));
  }

  @Test
  void rejectsWrongIssuerAudienceSignatureAndExpiredTokens() throws Exception {
    Instant now = Instant.now();
    List<String> roles = List.of("customer");
    assertUnauthorized(
        token(
            SIGNING_KEY,
            "https://wrong-issuer.invalid",
            List.of(AUDIENCE),
            "wrong-issuer",
            "ledgerflow.orders.read",
            roles,
            now.minusSeconds(5),
            now.plusSeconds(300)));
    assertUnauthorized(
        token(
            SIGNING_KEY,
            ISSUER,
            List.of("wrong-audience"),
            "wrong-audience",
            "ledgerflow.orders.read",
            roles,
            now.minusSeconds(5),
            now.plusSeconds(300)));
    assertUnauthorized(
        token(
            generateRsaKey(),
            ISSUER,
            List.of(AUDIENCE),
            "wrong-signature",
            "ledgerflow.orders.read",
            roles,
            now.minusSeconds(5),
            now.plusSeconds(300)));
    assertUnauthorized(
        token(
            SIGNING_KEY,
            ISSUER,
            List.of(AUDIENCE),
            "expired",
            "ledgerflow.orders.read",
            roles,
            now.minusSeconds(600),
            now.minusSeconds(300)));
  }

  @Test
  void rejectsAValidTokenThatLacksEitherTheScopeOrTheAllowlistedRole() throws Exception {
    Instant now = Instant.now();
    String noScope =
        token(
            SIGNING_KEY,
            ISSUER,
            List.of(AUDIENCE),
            "no-scope",
            "openid",
            List.of("customer"),
            now.minusSeconds(5),
            now.plusSeconds(300));
    String noRole =
        token(
            SIGNING_KEY,
            ISSUER,
            List.of(AUDIENCE),
            "no-role",
            "ledgerflow.orders.read",
            List.of("realm-manager"),
            now.minusSeconds(5),
            now.plusSeconds(300));

    assertForbidden(noScope);
    assertForbidden(noRole);
  }

  private void assertUnauthorized(String token) throws Exception {
    mockMvc
        .perform(
            get("/api/v1/orders/{orderId}", UUID.randomUUID())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
  }

  private void assertForbidden(String token) throws Exception {
    mockMvc
        .perform(
            get("/api/v1/orders/{orderId}", UUID.randomUUID())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("forbidden"));
  }

  private static String token(
      KeyPair keyPair,
      String issuer,
      List<String> audiences,
      String subject,
      String scope,
      List<String> roles,
      Instant issuedAt,
      Instant expiresAt)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audiences)
            .subject(subject)
            .issueTime(Date.from(issuedAt))
            .notBeforeTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .claim("scope", scope)
            .claim("realm_access", Map.of("roles", roles))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
    return jwt.serialize();
  }

  private static KeyPair generateRsaKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException("RSA test key generation failed", exception);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestJwtDecoderConfiguration {

    @Bean
    JwtDecoder securityTestJwtDecoder() {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic())
              .signatureAlgorithm(SignatureAlgorithm.RS256)
              .build();
      OAuth2TokenValidator<Jwt> issuerAndTime = JwtValidators.createDefaultWithIssuer(ISSUER);
      OAuth2TokenValidator<Jwt> audience =
          new JwtClaimValidator<List<String>>("aud", values -> values.contains(AUDIENCE));
      decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTime, audience));
      return decoder;
    }
  }
}
