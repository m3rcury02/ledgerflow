package com.ledgerflow.operations.internal.recovery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface FailedOperationProjectionRepository
    extends CrudRepository<FailedOperationProjection, UUID> {
  @Query("SELECT * FROM failed_operation_projections ORDER BY failed_at DESC LIMIT :limit")
  List<FailedOperationProjection> findAllPaginated(@Param("limit") int limit);
}
