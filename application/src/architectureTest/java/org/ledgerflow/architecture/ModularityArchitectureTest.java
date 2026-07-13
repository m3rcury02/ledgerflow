package org.ledgerflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.LedgerFlowApplication;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityArchitectureTest {

  private static final Set<String> EXPECTED_MODULES =
      Set.of("ledger", "messaging", "notifications", "operations", "orders", "payments");

  @Test
  void verifiesSpringModulithBoundaries() {
    var modules = ApplicationModules.of(LedgerFlowApplication.class).verify();

    EXPECTED_MODULES.forEach(
        expected -> assertThat(modules.getModuleByName(expected)).as(expected).isPresent());
    assertThat(modules.stream()).hasSize(EXPECTED_MODULES.size());
  }
}
