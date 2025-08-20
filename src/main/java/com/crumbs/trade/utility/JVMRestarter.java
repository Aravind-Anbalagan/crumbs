package com.crumbs.trade.utility;

import java.io.File;
import java.io.IOException;

public class JVMRestarter {

    public static void restartJVM() {
        try {
            // Path to current Java executable
            String java = System.getProperty("java.home") + "/bin/java";

            // Path to the currently running JAR
            String jarPath = new File(
                    JVMRestarter.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).getPath();

            System.out.println("Restarting JVM...");
            // Launch a new JVM process
            new ProcessBuilder(java, "-jar", jarPath)
                    .inheritIO() // optional: inherit stdout/stderr
                    .start();

            // Exit current JVM
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to restart JVM", e);
        }
    }
}
