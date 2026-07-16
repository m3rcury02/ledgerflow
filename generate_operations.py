import os

base_dir = "modules/operations/src/main/java/com/ledgerflow/operations/internal/recovery"
os.makedirs(base_dir, exist_ok=True)

with open(f"{base_dir}/OperatorRetryCommand.java", "w") as f:
    f.write("""package com.ledgerflow.operations.internal.recovery;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("operator_retry_commands")
public record OperatorRetryCommand(
        @Id UUID id,
        byte[] idempotencyKeyHash,
        byte[] requestHash,
        UUID operationId,
        String operationType,
        String status,
        String reason,
        boolean breakGlass,
        String approvalReference,
        String actorIssuer,
        String actorSubject,
        String actorClientId,
        String leaseOwner,
        Instant leaseUntil,
        String failureCode,
        Instant createdAt,
        Instant resolvedAt
) {
    public OperatorRetryCommand withStatus(String status) {
        return new OperatorRetryCommand(id, idempotencyKeyHash, requestHash, operationId, operationType, status, reason, breakGlass, approvalReference, actorIssuer, actorSubject, actorClientId, leaseOwner, leaseUntil, failureCode, createdAt, resolvedAt);
    }
    public OperatorRetryCommand withLease(String leaseOwner, Instant leaseUntil) {
        return new OperatorRetryCommand(id, idempotencyKeyHash, requestHash, operationId, operationType, "IN_PROGRESS", reason, breakGlass, approvalReference, actorIssuer, actorSubject, actorClientId, leaseOwner, leaseUntil, failureCode, createdAt, resolvedAt);
    }
    public OperatorRetryCommand withResolution(String status, String failureCode, Instant resolvedAt) {
        return new OperatorRetryCommand(id, idempotencyKeyHash, requestHash, operationId, operationType, status, reason, breakGlass, approvalReference, actorIssuer, actorSubject, actorClientId, null, null, failureCode, createdAt, resolvedAt);
    }
}
""")

with open(f"{base_dir}/FailedOperationProjection.java", "w") as f:
    f.write("""package com.ledgerflow.operations.internal.recovery;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("failed_operation_projections")
public record FailedOperationProjection(
        @Id UUID operationId,
        String operationType,
        String status,
        String failureCode,
        String summary,
        Instant failedAt
) {}
""")

with open(f"{base_dir}/OperatorRetryCommandRepository.java", "w") as f:
    f.write("""package com.ledgerflow.operations.internal.recovery;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorRetryCommandRepository extends CrudRepository<OperatorRetryCommand, UUID> {
    Optional<OperatorRetryCommand> findByIdempotencyKeyHash(byte[] idempotencyKeyHash);
    
    @Query("SELECT * FROM operator_retry_commands WHERE status = 'PENDING' OR (status = 'IN_PROGRESS' AND lease_until < :now) LIMIT :batchSize FOR UPDATE SKIP LOCKED")
    List<OperatorRetryCommand> claimPendingCommands(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
""")

with open(f"{base_dir}/FailedOperationProjectionRepository.java", "w") as f:
    f.write("""package com.ledgerflow.operations.internal.recovery;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FailedOperationProjectionRepository extends CrudRepository<FailedOperationProjection, UUID> {
    @Query("SELECT * FROM failed_operation_projections ORDER BY failed_at DESC LIMIT :limit")
    List<FailedOperationProjection> findAllPaginated(@Param("limit") int limit);
}
""")

with open(f"{base_dir}/OperatorRecoveryService.java", "w") as f:
    f.write("""package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationRetryHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

@Service
public class OperatorRecoveryService {
    private final OperatorRetryCommandRepository repository;
    private final Map<String, OperationRetryHandler> handlers;
    private final Clock clock;

    public OperatorRecoveryService(OperatorRetryCommandRepository repository, List<OperationRetryHandler> handlerList, Clock clock) {
        this.repository = repository;
        this.handlers = handlerList.stream().collect(Collectors.toMap(OperationRetryHandler::getSupportedOperationType, h -> h));
        this.clock = clock;
    }

    @Transactional
    public OperatorRetryCommand submitRetry(UUID operationId, String operationType, String idempotencyKey, String requestBody, String reason, boolean breakGlass, String approvalReference, String issuer, String subject, String clientId) {
        byte[] keyHash = hash(idempotencyKey);
        byte[] reqHash = hash(requestBody);
        
        return repository.findByIdempotencyKeyHash(keyHash).orElseGet(() -> {
            var command = new OperatorRetryCommand(
                    null, keyHash, reqHash, operationId, operationType, "PENDING", reason, breakGlass, approvalReference, issuer, subject, clientId, null, null, null, clock.instant(), null
            );
            return repository.save(command);
        });
    }

    private byte[] hash(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
""")

