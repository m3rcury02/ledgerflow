package com.ledgerflow.notifications.api;

import java.util.UUID;

public interface DeadLetterReplay {

  ReplayResult replay(UUID deadLetterRecordId, String actor, String reason);
}
