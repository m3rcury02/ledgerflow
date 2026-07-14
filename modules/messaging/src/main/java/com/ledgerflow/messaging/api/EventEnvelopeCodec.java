package com.ledgerflow.messaging.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class EventEnvelopeCodec {

  private final ObjectMapper objectMapper;

  public EventEnvelopeCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String serialize(PaymentCapturedEventV1 event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Payment-captured event could not be serialized", exception);
    }
  }

  public PaymentCapturedEventV1 deserialize(String payload) {
    try {
      return objectMapper.readValue(payload, PaymentCapturedEventV1.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Payment-captured event is invalid", exception);
    }
  }

  public byte[] hash(String canonicalPayload) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
