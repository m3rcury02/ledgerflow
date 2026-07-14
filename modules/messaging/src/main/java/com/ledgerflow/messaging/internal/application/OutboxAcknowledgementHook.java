package com.ledgerflow.messaging.internal.application;

import java.util.UUID;

@FunctionalInterface
public interface OutboxAcknowledgementHook {

  void afterBrokerAcknowledgement(UUID eventId);
}
