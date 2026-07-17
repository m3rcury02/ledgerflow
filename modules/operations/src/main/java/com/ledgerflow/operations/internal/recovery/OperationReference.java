package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import java.util.Locale;
import java.util.UUID;

record OperationReference(OperationType type, UUID sourceId) {

  String externalId() {
    return prefix(type) + "_" + sourceId;
  }

  static OperationReference parse(String externalId) {
    if (externalId == null || externalId.length() > 64) {
      throw new OperationNotFoundException();
    }
    int separator = externalId.indexOf('_');
    if (separator < 1 || separator == externalId.length() - 1) {
      throw new OperationNotFoundException();
    }
    try {
      OperationType type =
          switch (externalId.substring(0, separator)) {
            case "payment" -> OperationType.PAYMENT;
            case "outbox" -> OperationType.OUTBOX;
            case "dead-letter" -> OperationType.DEAD_LETTER;
            default -> throw new OperationNotFoundException();
          };
      return new OperationReference(type, UUID.fromString(externalId.substring(separator + 1)));
    } catch (IllegalArgumentException exception) {
      throw new OperationNotFoundException();
    }
  }

  private static String prefix(OperationType type) {
    return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
