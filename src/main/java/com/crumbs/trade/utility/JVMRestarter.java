package com.crumbs.trade.utility;

import java.io.File;

public class JVMRestarter {

    public static void restartJVM() {
        try {
            // Path to current java executable
            String java = System.getProperty("java.home") + "/bin/java";

            // Path to the running JAR file
            String jarPath = new File(
                    JVMRestarter.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).getPath();

            System.out.println("Restarting JVM... old PID: " + ProcessHandle.current().pid());

            // Start new JVM process
            new ProcessBuilder(java, "-jar", jarPath)
                    .inheritIO()
                    .start();

            // Exit current JVM
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to restart JVM", e);
        }
    }
}

