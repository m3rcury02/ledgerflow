package com.ledgerflow;

import com.ledgerflow.notifications.api.DeadLetterReplay;
import com.ledgerflow.notifications.api.ReplayOutcome;
import com.ledgerflow.notifications.api.ReplayResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledgerflow.replay", name = "command-enabled", havingValue = "true")
class ReplayCommandConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReplayCommandConfiguration.class);

  @Bean
  ApplicationRunner deadLetterReplayCommand(
      DeadLetterReplay deadLetterReplay,
      Environment environment,
      ConfigurableApplicationContext applicationContext) {
    return arguments -> {
      UUID recordId =
          UUID.fromString(environment.getRequiredProperty("ledgerflow.replay.dead-letter-id"));
      String actor = environment.getRequiredProperty("ledgerflow.replay.actor");
      String reason = environment.getRequiredProperty("ledgerflow.replay.reason");
      ReplayResult result = deadLetterReplay.replay(recordId, actor, reason);
      if (result.outcome() != ReplayOutcome.PUBLISHED) {
        throw new IllegalStateException(
            "Dead-letter replay did not receive a broker acknowledgement; inspect replay audit");
      }
      LOGGER.info(
          "Dead-letter replay published: recordId={}, replayRequestId={}, correlationId={}",
          recordId,
          result.replayRequestId(),
          result.processingCorrelationId());
      applicationContext.close();
    };
  }
}
