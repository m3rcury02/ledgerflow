package com.ledgerflow.ledger.internal.persistence;

import com.ledgerflow.ledger.internal.application.JournalNotFoundException;
import com.ledgerflow.ledger.internal.domain.EntrySide;
import com.ledgerflow.ledger.internal.domain.JournalEntry;
import com.ledgerflow.ledger.internal.domain.JournalPosting;
import com.ledgerflow.ledger.internal.domain.JournalType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLedgerStore {

  private final JdbcClient jdbcClient;

  public JdbcLedgerStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredJournal> findPaymentCapture(UUID paymentId) {
    return findHeader(
            """
            SELECT *
            FROM ledger_transactions
            WHERE journal_type = 'PAYMENT_CAPTURE' AND payment_id = :referenceId
            """,
            paymentId)
        .map(this::load);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredJournal> findCorrection(UUID originalTransactionId) {
    return findHeader(
            """
            SELECT *
            FROM ledger_transactions
            WHERE journal_type = 'CORRECTION' AND reversal_of_transaction_id = :referenceId
            """,
            originalTransactionId)
        .map(this::load);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public StoredJournal lock(UUID transactionId) {
    JournalHeader header =
        jdbcClient
            .sql("SELECT * FROM ledger_transactions WHERE id = :transactionId FOR UPDATE")
            .param("transactionId", transactionId)
            .query(this::mapHeader)
            .optional()
            .orElseThrow(JournalNotFoundException::new);
    return load(header);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public StoredJournal insert(JournalPosting posting, Instant postedAt) {
    JournalHeader header =
        jdbcClient
            .sql(
                """
                INSERT INTO ledger_transactions (
                    journal_type, source_type, source_id, payment_id, order_id, currency,
                    reversal_of_transaction_id, description, correlation_id, created_by, posted_at
                ) VALUES (
                    :journalType, :sourceType, :sourceId, :paymentId, :orderId, :currency,
                    :reversalId, :description, :correlationId, :createdBy, :postedAt
                )
                RETURNING *
                """)
            .param("journalType", posting.journalType().name())
            .param("sourceType", posting.sourceType())
            .param("sourceId", posting.sourceId())
            .param("paymentId", posting.paymentId())
            .param("orderId", posting.orderId())
            .param("currency", posting.currency())
            .param("reversalId", posting.reversesTransactionId(), java.sql.Types.OTHER)
            .param("description", posting.description())
            .param("correlationId", posting.correlationId())
            .param("createdBy", posting.actor())
            .param("postedAt", databaseTimestamp(postedAt))
            .query(this::mapHeader)
            .single();

    for (JournalEntry entry : posting.entries()) {
      int inserted =
          jdbcClient
              .sql(
                  """
                  INSERT INTO ledger_entries (
                      transaction_id, account_id, side, amount_minor, currency, created_at
                  )
                  SELECT :transactionId, id, :side, :amountMinor, :currency, :createdAt
                  FROM ledger_accounts
                  WHERE code = :accountCode
                  """)
              .param("transactionId", header.transactionId())
              .param("side", entry.side().databaseCode())
              .param("amountMinor", entry.amountMinor())
              .param("currency", entry.currency())
              .param("createdAt", databaseTimestamp(postedAt))
              .param("accountCode", entry.accountCode())
              .update();
      if (inserted != 1) {
        throw new IllegalStateException("Journal account does not exist");
      }
    }
    return load(header);
  }

  private Optional<JournalHeader> findHeader(String sql, UUID referenceId) {
    return jdbcClient.sql(sql).param("referenceId", referenceId).query(this::mapHeader).optional();
  }

  private StoredJournal load(JournalHeader header) {
    List<JournalEntry> entries =
        jdbcClient
            .sql(
                """
                SELECT a.code, e.side, e.amount_minor, e.currency
                FROM ledger_entries e
                JOIN ledger_accounts a ON a.id = e.account_id
                WHERE e.transaction_id = :transactionId
                ORDER BY e.id
                """)
            .param("transactionId", header.transactionId())
            .query(this::mapEntry)
            .list();
    JournalPosting posting =
        new JournalPosting(
            header.journalType(),
            header.sourceType(),
            header.sourceId(),
            header.paymentId(),
            header.orderId(),
            header.currency(),
            header.reversesTransactionId(),
            header.description(),
            header.correlationId(),
            header.createdBy(),
            entries);
    return new StoredJournal(header.transactionId(), posting, header.postedAt());
  }

  private JournalHeader mapHeader(ResultSet resultSet, int rowNumber) throws SQLException {
    return new JournalHeader(
        resultSet.getObject("id", UUID.class),
        JournalType.valueOf(resultSet.getString("journal_type")),
        resultSet.getString("source_type"),
        resultSet.getObject("source_id", UUID.class),
        resultSet.getObject("payment_id", UUID.class),
        resultSet.getObject("order_id", UUID.class),
        resultSet.getString("currency"),
        resultSet.getObject("reversal_of_transaction_id", UUID.class),
        resultSet.getString("description"),
        resultSet.getString("correlation_id"),
        resultSet.getString("created_by"),
        resultSet.getTimestamp("posted_at").toInstant());
  }

  private JournalEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
    return new JournalEntry(
        resultSet.getString("code"),
        EntrySide.fromDatabaseCode(resultSet.getString("side")),
        resultSet.getLong("amount_minor"),
        resultSet.getString("currency"));
  }

  private OffsetDateTime databaseTimestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private record JournalHeader(
      UUID transactionId,
      JournalType journalType,
      String sourceType,
      UUID sourceId,
      UUID paymentId,
      UUID orderId,
      String currency,
      UUID reversesTransactionId,
      String description,
      String correlationId,
      String createdBy,
      Instant postedAt) {}
}
