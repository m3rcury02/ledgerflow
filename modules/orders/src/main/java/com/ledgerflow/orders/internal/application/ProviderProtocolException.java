package com.ledgerflow.orders.internal.application;

public final class ProviderProtocolException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String location;
  private final boolean replayed;

  public ProviderProtocolException(String location, boolean replayed) {
    super("The payment provider returned an invalid response");
    this.location = location;
    this.replayed = replayed;
  }

  public String location() {
    return location;
  }

  public boolean replayed() {
    return replayed;
  }
}
