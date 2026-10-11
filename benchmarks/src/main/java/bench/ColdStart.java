package bench;

import java.lang.management.ManagementFactory;

/**
 * Parses a small configuration once and prints the milliseconds since the JVM started.
 */
public final class ColdStart {
    private ColdStart() {
    }

    public static void main(String[] args) {
        YamlLib lib = YamlLib.of(args[0], false);
        Object value = lib.parseUntyped(Data.smallConfig());
        long elapsed = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
        System.out.println("COLD|" + elapsed + "|" + value.getClass().getSimpleName());
    }
}
