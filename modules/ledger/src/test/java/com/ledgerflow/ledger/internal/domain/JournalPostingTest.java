package com.ledgerflow.ledger.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalPostingTest {

  @Test
  void acceptsTwoOrMoreEntriesWhenDebitsAndCreditsBalance() {
    JournalPosting posting =
        posting(
            List.of(
                entry("PAYMENT_CLEARING", EntrySide.DEBIT, 10_000),
                entry("MERCHANT_PAYABLE", EntrySide.CREDIT, 6_000),
                entry("ADJUSTMENT_ACCOUNT", EntrySide.CREDIT, 4_000)));

    assertThat(posting.entries()).hasSize(3);
    assertThat(posting.debitTotalMinor()).isEqualTo(10_000);
  }

  @Test
  void rejectsAnUnbalancedJournal() {
    assertThatThrownBy(
            () ->
                posting(
                    List.of(
                        entry("PAYMENT_CLEARING", EntrySide.DEBIT, 10_000),
                        entry("MERCHANT_PAYABLE", EntrySide.CREDIT, 9_999))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("debits must equal credits");
  }

  @Test
  void rejectsFewerThanTwoEntries() {
    assertThatThrownBy(() -> posting(List.of(entry("PAYMENT_CLEARING", EntrySide.DEBIT, 10_000))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least two");
  }

  @Test
  void rejectsMixedCurrencyAndNonPositiveMinorUnits() {
    assertThatThrownBy(() -> new JournalEntry("PAYMENT_CLEARING", EntrySide.DEBIT, 0, "INR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(
            () ->
                posting(
                    List.of(
                        entry("PAYMENT_CLEARING", EntrySide.DEBIT, 10_000),
                        new JournalEntry("MERCHANT_PAYABLE", EntrySide.CREDIT, 10_000, "USD"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one currency");
  }

  @Test
  void paymentCaptureUsesIntegerMinorUnitsAndTheApprovedAccounts() {
    UUID paymentId = UUID.randomUUID();

    JournalPosting posting =
        JournalPosting.paymentCapture(
            paymentId, UUID.randomUUID(), 25_990, "INR", "correlation-123", "ledger-system");

    assertThat(posting.sourceId()).isEqualTo(paymentId);
    assertThat(posting.entries())
        .containsExactly(
            entry("PAYMENT_CLEARING", EntrySide.DEBIT, 25_990),
            entry("MERCHANT_PAYABLE", EntrySide.CREDIT, 25_990));
  }

  private JournalPosting posting(List<JournalEntry> entries) {
    UUID paymentId = UUID.randomUUID();
    return new JournalPosting(
        JournalType.PAYMENT_CAPTURE,
        "PAYMENT_CAPTURE",
        paymentId,
        paymentId,
        UUID.randomUUID(),
        "INR",
        null,
        "Test posting",
        "correlation-123",
        "ledger-test",
        entries);
  }

  private JournalEntry entry(String account, EntrySide side, long amountMinor) {
    return new JournalEntry(account, side, amountMinor, "INR");
  }
}
