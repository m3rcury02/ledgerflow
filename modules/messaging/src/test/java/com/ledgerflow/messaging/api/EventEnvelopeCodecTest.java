package com.ledgerflow.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EventEnvelopeCodecTest {

  @Test
  void serializesTheCanonicalVersionedEnvelopeAndRoundTrips() {
    PaymentCapturedEventV1 event = event();
    EventEnvelopeCodec codec = new EventEnvelopeCodec(new ObjectMapper());

    String payload = codec.serialize(event);

    assertThat(payload)
        .isEqualTo(
            "{\"eventId\":\"01980d4f-7b4b-7000-8000-000000000001\","
                + "\"eventType\":\"com.ledgerflow.payment.captured\",\"schemaVersion\":1,"
                + "\"aggregateId\":\"01980d4f-7b4b-7000-8000-000000000002\","
                + "\"correlationId\":\"order-123\","
                + "\"causationId\":\"01980d4f-7b4b-7000-8000-000000000003\","
                + "\"occurredAt\":\"2026-07-13T15:00:00Z\",\"data\":{"
                + "\"orderId\":\"01980d4f-7b4b-7000-8000-000000000004\","
                + "\"paymentId\":\"01980d4f-7b4b-7000-8000-000000000002\","
                + "\"ledgerTransactionId\":\"01980d4f-7b4b-7000-8000-000000000005\","
                + "\"amountMinor\":2599,\"currency\":\"INR\","
                + "\"capturedAt\":\"2026-07-13T15:00:00Z\"}}");
    assertThat(codec.deserialize(payload)).isEqualTo(event);
    assertThat(codec.hash(payload)).hasSize(32);
  }

  private PaymentCapturedEventV1 event() {
    UUID paymentId = UUID.fromString("01980d4f-7b4b-7000-8000-000000000002");
    Instant occurredAt = Instant.parse("2026-07-13T15:00:00Z");
    return new PaymentCapturedEventV1(
        UUID.fromString("01980d4f-7b4b-7000-8000-000000000001"),
        PaymentCapturedEventV1.TYPE,
        PaymentCapturedEventV1.SCHEMA_VERSION,
        paymentId,
        "order-123",
        UUID.fromString("01980d4f-7b4b-7000-8000-000000000003"),
        occurredAt,
        new PaymentCapturedDataV1(
            UUID.fromString("01980d4f-7b4b-7000-8000-000000000004"),
            paymentId,
            UUID.fromString("01980d4f-7b4b-7000-8000-000000000005"),
            2599,
            "INR",
            occurredAt));
  }
}
