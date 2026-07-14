package com.ledgerflow.operations.api;

/** Tracks bounded application work so shutdown can wait for in-flight operations. */
public interface WorkTracker {

  WorkToken begin(String operation);

  boolean isAcceptingWork();
}
