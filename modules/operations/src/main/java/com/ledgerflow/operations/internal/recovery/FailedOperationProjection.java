package com.ledgerflow.operations.internal.recovery;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("failed_operation_projections")
public record FailedOperationProjection(
    @Id UUID operationId,
    String operationType,
    String status,
    String failureCode,
    String summary,
    Instant failedAt) {}
