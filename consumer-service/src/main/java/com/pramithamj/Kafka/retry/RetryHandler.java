package com.pramithamj.kafka.retry;

import com.pramithamj.kafka.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Handles retry logic for failed order processing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topic.orders-retry}")
    private String retryTopic;

    @Value("${kafka.retry.max-attempts:3}")
    private int maxRetryAttempts;

    private static final String RETRY_COUNT_HEADER = "retry-count";

    /**
     * Send an order to the retry topic
     * 
     * @param order The order that failed processing
     * @param exception The exception that caused the failure
     * @param currentRetryCount Current retry attempt count
     */
    public void sendToRetry(Order order, Exception exception, int currentRetryCount) {
        if (currentRetryCount >= maxRetryAttempts) {
            log.warn("Max retry attempts ({}) reached for order: {}", 
                    maxRetryAttempts, order.getOrderId());
            return;
        }

        int nextRetryCount = currentRetryCount + 1;
        
        log.info("Sending order to retry topic: orderId={}, retryCount={}, reason={}", 
                order.getOrderId(), nextRetryCount, exception.getMessage());

        try {
            kafkaTemplate.send(retryTopic, order.getOrderId().toString(), order)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Order sent to retry topic successfully: orderId={}, partition={}, offset={}", 
                                order.getOrderId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to send order to retry topic: orderId={}, error={}", 
                                order.getOrderId(), ex.getMessage(), ex);
                    }
                });
        } catch (Exception e) {
            log.error("Exception while sending to retry topic: orderId={}", 
                    order.getOrderId(), e);
        }
    }

    /**
     * Check if the order should be retried
     * 
     * @param retryCount Current retry count
     * @return true if should retry, false otherwise
     */
    public boolean shouldRetry(int retryCount) {
        return retryCount < maxRetryAttempts;
    }

    /**
     * Calculate exponential backoff delay
     * 
     * @param retryCount Current retry count
     * @return Delay in milliseconds
     */
    public long calculateBackoffDelay(int retryCount) {
        // Exponential backoff: 2^retryCount * 1000ms
        return (long) Math.pow(2, retryCount) * 1000;
    }
}
