package com.ledgerflow.operations.internal.recovery;

import java.time.Instant;
import java.util.UUID;

record BreakGlassApproval(UUID id, byte[] requestHash, Instant createdAt) {}
