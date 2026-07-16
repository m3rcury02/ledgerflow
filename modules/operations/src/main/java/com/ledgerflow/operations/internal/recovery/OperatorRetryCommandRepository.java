package com.ledgerflow.operations.internal.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface OperatorRetryCommandRepository extends CrudRepository<OperatorRetryCommand, UUID> {
  @Query("SELECT * FROM operator_retry_commands WHERE idempotency_key_hash = :hash")
  Optional<OperatorRetryCommand> findByIdempotencyKeyHash(@Param("hash") byte[] hash);

  @Query(
      "SELECT * FROM operator_retry_commands WHERE status = 'PENDING' OR "
          + "(status = 'IN_PROGRESS' AND lease_until < :now) LIMIT :batchSize "
          + "FOR UPDATE SKIP LOCKED")
  List<OperatorRetryCommand> claimPendingCommands(
      @Param("now") Instant now, @Param("batchSize") int batchSize);
}
