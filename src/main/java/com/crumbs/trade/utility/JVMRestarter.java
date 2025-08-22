package com.crumbs.trade.utility;

import java.io.File;


public class JVMRestarter {

    /**
     * Trigger a JVM restart in container environments like Fly.io.
     * This will exit the current JVM, letting Fly.io restart it fresh.
     */
    public static void restartJVM() {
        try {
            System.out.println("JVM hang detected. Exiting to trigger container restart...");
            // Optional: delay to allow logs to flush or cleanup
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Exit with non-zero status to signal failure
        System.exit(1);
    }
}

