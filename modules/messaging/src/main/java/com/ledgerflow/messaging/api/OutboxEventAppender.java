package com.ledgerflow.messaging.api;

import java.util.UUID;

public interface OutboxEventAppender {

  UUID appendPaymentCaptured(AppendPaymentCapturedEvent event);
}
