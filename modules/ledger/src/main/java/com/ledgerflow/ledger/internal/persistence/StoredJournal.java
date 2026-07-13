package com.ledgerflow.ledger.internal.persistence;

import com.ledgerflow.ledger.internal.domain.JournalPosting;
import java.time.Instant;
import java.util.UUID;

public record StoredJournal(UUID transactionId, JournalPosting posting, Instant postedAt) {}
