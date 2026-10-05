package com.salestorm.simulation;

import com.salestorm.SalestormApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Standalone Main-Method Runner for the Flash Sale Simulation.
 * Boots the Spring Application context, runs the 10,000 request concurrent stress simulation,
 * prints the metrics report, and gracefully shuts down.
 */
public class FlashSaleSimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleSimulationRunner.class);

    public static void main(String[] args) {
        log.info("Starting SaleStorm Application Context for Flash Sale Simulation...");
        ConfigurableApplicationContext context = SpringApplication.run(SalestormApplication.class, args);

        try {
            log.info("SaleStorm context loaded successfully. Initiating simulation...");
            FlashSaleSimulationTest testInstance = context.getAutowireCapableBeanFactory().createBean(FlashSaleSimulationTest.class);
            testInstance.setUp();

            log.info("Running 10,000 Concurrent Request Flash Sale Simulation...");
            testInstance.test10kConcurrentFlashSaleSimulation();

            log.info("Running Broken Gate Oversell Protection Test...");
            testInstance.testGateOversellProtection();

            log.info("All flash sale simulation benchmarks executed successfully!");
        } catch (Exception e) {
            log.error("Simulation run encountered an error: {}", e.getMessage(), e);
        } finally {
            context.close();
            log.info("SaleStorm Application Context closed.");
        }
    }
}
