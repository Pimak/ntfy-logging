package com.example.ntfyspring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * An ordinary service that knows nothing about ntfy: it just logs an ERROR when an order fails.
 * The starter-installed appender on the root logger turns that log into a push notification —
 * alerting needs no code change in application classes.
 */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  /** Simulates an order whose payment step fails, producing a plain ERROR log with an exception. */
  public void placeOrder(String orderId) {
    try {
      chargeCard(orderId);
    } catch (IllegalStateException e) {
      log.error("Order {} failed", orderId, e);
    }
  }

  private void chargeCard(String orderId) {
    throw new IllegalStateException("payment gateway timeout for order " + orderId);
  }
}
