package com.pramithamj.kafka.consumer;

import com.pramithamj.kafka.aggregation.RunningAverageCalculator;
import com.pramithamj.kafka.dlq.DLQHandler;
import com.pramithamj.kafka.model.Order;
import com.pramithamj.kafka.retry.RetryHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Main consumer for processing orders from Kafka
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderConsumer {

    private final RunningAverageCalculator averageCalculator;
    private final RetryHandler retryHandler;
    private final DLQHandler dlqHandler;

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    /**
     * Main consumer for orders topic
     */
    @KafkaListener(
        topics = "${kafka.topic.orders}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrder(
            @Payload Order order,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            ConsumerRecord<String, Order> record,
            Acknowledgment acknowledgment) {
        
        try {
            log.info("Received order: orderId={}, customerId={}, totalAmount=${}, partition={}, offset={}", 
                    order.getOrderId(), order.getCustomerId(), order.getTotalAmount(), partition, offset);

            // Process the order
            processOrder(order);

            // Update running average
            double newAverage = averageCalculator.addAmount(order.getTotalAmount());
            
            long processed = processedCount.incrementAndGet();
            
            // Log statistics every 10 orders
            if (processed % 10 == 0) {
                log.info("Processing Statistics: {}", averageCalculator.getStatistics());
            }

            // Manually commit the offset
            acknowledgment.acknowledge();
            
            log.debug("Order processed successfully: orderId={}", order.getOrderId());

        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error processing order: orderId={}, error={}", 
                    order.getOrderId(), e.getMessage(), e);
            
            // Send to retry topic
            retryHandler.sendToRetry(order, e, 0);
            
            // Still acknowledge to prevent reprocessing by this consumer
            acknowledgment.acknowledge();
        }
    }

    /**
     * Consumer for retry topic
     */
    @KafkaListener(
        topics = "${kafka.topic.orders-retry}",
        groupId = "${spring.kafka.consumer.group-id}-retry",
        containerFactory = "retryKafkaListenerContainerFactory"
    )
    public void consumeRetryOrder(
            @Payload Order order,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = "retry-count", required = false) Integer retryCount,
            Acknowledgment acknowledgment) {
        
        int currentRetryCount = (retryCount != null) ? retryCount : 1;
        
        log.info("Retrying order: orderId={}, retryCount={}, partition={}, offset={}", 
                order.getOrderId(), currentRetryCount, partition, offset);

        try {
            // Add backoff delay
            long backoffDelay = retryHandler.calculateBackoffDelay(currentRetryCount);
            log.debug("Applying backoff delay: {}ms", backoffDelay);
            Thread.sleep(backoffDelay);

            // Retry processing
            processOrder(order);

            // Update running average
            averageCalculator.addAmount(order.getTotalAmount());
            
            processedCount.incrementAndGet();
            
            acknowledgment.acknowledge();
            
            log.info("Order retry successful: orderId={}, retryCount={}", 
                    order.getOrderId(), currentRetryCount);

        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Order retry failed: orderId={}, retryCount={}, error={}", 
                    order.getOrderId(), currentRetryCount, e.getMessage(), e);
            
            if (retryHandler.shouldRetry(currentRetryCount)) {
                // Send to retry topic again
                retryHandler.sendToRetry(order, e, currentRetryCount);
            } else {
                // Max retries reached, send to DLQ
                log.warn("Max retries reached, sending to DLQ: orderId={}", order.getOrderId());
                dlqHandler.sendToDLQ(order, e, currentRetryCount);
            }
            
            acknowledgment.acknowledge();
        }
    }

    /**
     * Consumer for Dead Letter Queue
     */
    @KafkaListener(
        topics = "${kafka.topic.orders-dlq}",
        groupId = "${spring.kafka.consumer.group-id}-dlq",
        containerFactory = "dlqKafkaListenerContainerFactory"
    )
    public void consumeDLQ(
            @Payload Order order,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        log.warn("Processing order from DLQ: orderId={}, customerId={}, totalAmount=${}", 
                order.getOrderId(), order.getCustomerId(), order.getTotalAmount());

        // In production, DLQ messages would be:
        // - Logged to a database for investigation
        // - Sent to a monitoring system
        // - Available for manual review and reprocessing
        
        // For now, just log and acknowledge
        log.info("DLQ order logged for manual review: orderId={}", order.getOrderId());
        
        acknowledgment.acknowledge();
    }

    /**
     * Process the order (business logic)
     * 
     * @param order The order to process
     * @throws Exception if processing fails
     */
    private void processOrder(Order order) throws Exception {
        // Simulate order processing
        log.debug("Processing order business logic: orderId={}", order.getOrderId());
        
        // Validation
        if (order.getTotalAmount() <= 0) {
            throw new IllegalArgumentException("Invalid order amount: " + order.getTotalAmount());
        }
        
        if (order.getQuantity() <= 0) {
            throw new IllegalArgumentException("Invalid order quantity: " + order.getQuantity());
        }

        // Simulate some processing time
        Thread.sleep(100);

        // Simulate occasional failures for testing retry/DLQ
        // In production, real failures would occur naturally
        if (shouldSimulateFailure(order)) {
            throw new RuntimeException("Simulated processing failure");
        }

        // Business logic would go here:
        // - Inventory check
        // - Payment processing
        // - Order fulfillment
        // - Notification sending
        
        log.debug("Order processing completed: orderId={}", order.getOrderId());
    }

    /**
     * Simulate failures for testing (5% failure rate)
     * Remove this in production
     */
    private boolean shouldSimulateFailure(Order order) {
        // Fail orders where orderId ends with specific patterns
        String orderId = order.getOrderId().toString();
        return orderId.endsWith("FAIL") || 
               (orderId.hashCode() % 20 == 0); // ~5% failure rate
    }

    /**
     * Get processing statistics
     */
    public String getStatistics() {
        return String.format(
            "Processed: %d | Errors: %d | Success Rate: %.2f%% | %s",
            processedCount.get(),
            errorCount.get(),
            calculateSuccessRate(),
            averageCalculator.getStatistics()
        );
    }

    private double calculateSuccessRate() {
        long total = processedCount.get();
        if (total == 0) return 0.0;
        long errors = errorCount.get();
        return ((total - errors) * 100.0) / total;
    }
}
