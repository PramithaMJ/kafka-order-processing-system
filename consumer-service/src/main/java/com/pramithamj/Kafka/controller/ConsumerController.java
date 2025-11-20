package com.pramithamj.kafka.controller;

import com.pramithamj.kafka.aggregation.RunningAverageCalculator;
import com.pramithamj.kafka.consumer.OrderConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for monitoring consumer service
 */
@RestController
@RequestMapping("/api/consumer")
@RequiredArgsConstructor
public class ConsumerController {

    private final OrderConsumer orderConsumer;
    private final RunningAverageCalculator averageCalculator;

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "consumer-service");
        return ResponseEntity.ok(response);
    }

    /**
     * Get consumer statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("ordersProcessed", averageCalculator.getOrderCount());
        stats.put("totalAmount", averageCalculator.getTotalAmount());
        stats.put("runningAverage", averageCalculator.getCurrentAverage());
        stats.put("detailedStats", orderConsumer.getStatistics());
        return ResponseEntity.ok(stats);
    }

    /**
     * Reset statistics
     */
    @PostMapping("/stats/reset")
    public ResponseEntity<Map<String, String>> resetStatistics() {
        averageCalculator.reset();
        Map<String, String> response = new HashMap<>();
        response.put("message", "Statistics reset successfully");
        return ResponseEntity.ok(response);
    }

    /**
     * Get running average details
     */
    @GetMapping("/average")
    public ResponseEntity<Map<String, Object>> getRunningAverage() {
        Map<String, Object> average = new HashMap<>();
        average.put("currentAverage", averageCalculator.getCurrentAverage());
        average.put("orderCount", averageCalculator.getOrderCount());
        average.put("totalAmount", averageCalculator.getTotalAmount());
        average.put("statistics", averageCalculator.getStatistics());
        return ResponseEntity.ok(average);
    }
}
