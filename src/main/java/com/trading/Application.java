package com.trading;

import io.github.cdimascio.dotenv.Dotenv;
import io.micronaut.runtime.Micronaut;

public class Application {

    public static void main(String[] args) {
        // Load .env file BEFORE Micronaut starts
        try {
            Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

            dotenv.entries().forEach(entry -> {
                // Only set if not already in environment
                if (System.getenv(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });

            System.out.println("✓ Loaded environment variables from .env file");
        } catch (Exception e) {
            System.err.println("⚠ Warning: Could not load .env file - " + e.getMessage());
        }

        Micronaut.run(Application.class, args);
    }
}