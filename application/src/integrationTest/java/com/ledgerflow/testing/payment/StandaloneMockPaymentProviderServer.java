package com.ledgerflow.testing.payment;

/**
 * Runs {@link MockPaymentProviderServer} as a standalone local process for the performance and
 * failure experiments in {@code performance/} (see docs/plans/portfolio-extension-execplan.md,
 * Milestone 2). The automated test suite never uses this class; it starts the fixture directly per
 * test.
 */
public final class StandaloneMockPaymentProviderServer {

  private static final int DEFAULT_PORT = 8090;

  private StandaloneMockPaymentProviderServer() {
    // Not instantiated; main() is the only entry point.
  }

  public static void main(String[] args) throws InterruptedException {
    int port = DEFAULT_PORT;
    String portProperty = System.getenv("MOCK_PROVIDER_PORT");
    if (portProperty != null && !portProperty.isBlank()) {
      port = Integer.parseInt(portProperty.trim());
    }
    MockPaymentProviderServer server = new MockPaymentProviderServer(port);
    System.out.println("MOCK_PROVIDER_BASE_URL=" + server.baseUrl());
    System.out.flush();
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    Thread.currentThread().join();
  }
}
