package bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import bench.model.Address;
import bench.model.Record;
import bench.model.Records;

/**
 * Deterministic inputs; every library reads exactly the same text.
 */
public final class Data {
    /** Real-world documents downloaded by fetch-data.sh, see benchmarks/README.md. */
    public static final List<String> REAL_DOCUMENTS = List.of(
        "openapi-github.yaml", "cert-manager.yaml", "helm-values.yaml", "faker-ru.yml", "ingress-nginx.yaml", "compose.yaml"
    );

    private Data() {
    }

    /** Reads a real-world document from the data directory (system property bench.data, default "data"). */
    public static String real(String name) {
        Path path = Path.of(System.getProperty("bench.data", "data"), name);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Run fetch-data.sh first: cannot read " + path.toAbsolutePath(), exception);
        }
    }

    /** A typical application configuration, about 1.5 KB. */
    public static String smallConfig() {
        return """
            # Application configuration
            server:
              host: 0.0.0.0
              port: 8080
              tls:
                enabled: true
                certificate: /etc/certs/server.pem
                protocols: [TLSv1.2, TLSv1.3]
            database:
              url: jdbc:postgresql://db.internal:5432/app
              username: app
              pool: {min: 2, max: 20, timeout: 30s}
              migrations:
                enabled: true
                locations:
                  - classpath:db/migration
                  - filesystem:/opt/app/migrations
            cache:
              provider: redis
              nodes:
                - redis-1.internal:6379
                - redis-2.internal:6379
              ttl: 600
            features: [search, export, audit, notifications]
            logging:
              level: INFO
              pattern: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
              loggers:
                org.hibernate: WARN
                io.pure: DEBUG
            banner: |
              Welcome to the application
              Version 1.2 (build 2026-10-11)
            limits:
              - name: requests
                value: 1000
                window: 60
              - name: uploads
                value: 50
                window: 3600
              - name: exports
                value: 5
                window: 86400
            metadata:
              owner: platform-team
              contact: platform@example.com
              tags: {environment: production, region: eu-west-1, tier: critical}
            """;
    }

    /** {@code items:} followed by {@code count} records in block style; 500 records are about 100 KB. */
    public static String records(int count) {
        StringBuilder text = new StringBuilder("items:\n");
        for (int i = 0; i < count; i++) {
            text.append("  - id: ").append(i).append('\n')
                .append("    name: \"User ").append(i).append("\"\n")
                .append("    email: user").append(i).append("@example.com\n")
                .append("    active: ").append(i % 3 != 0).append('\n')
                .append("    score: ").append(i % 100).append(".5\n")
                .append("    tags: [alpha, beta, gamma]\n")
                .append("    address:\n")
                .append("      street: ").append(i).append(" Main Street\n")
                .append("      city: City").append(i % 50).append('\n')
                .append("      zip: \"").append(String.format("%05d", i % 100000)).append("\"\n");
        }
        return text.toString();
    }

    /** The same records as a single-line JSON document. */
    public static String recordsJson(int count) {
        StringBuilder text = new StringBuilder("{\"items\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append("{\"id\":").append(i)
                .append(",\"name\":\"User ").append(i).append('"')
                .append(",\"email\":\"user").append(i).append("@example.com\"")
                .append(",\"active\":").append(i % 3 != 0)
                .append(",\"score\":").append(i % 100).append(".5")
                .append(",\"tags\":[\"alpha\",\"beta\",\"gamma\"]")
                .append(",\"address\":{\"street\":\"").append(i).append(" Main Street\",\"city\":\"City").append(i % 50)
                .append("\",\"zip\":\"").append(String.format("%05d", i % 100000)).append("\"}}");
        }
        return text.append("]}").toString();
    }

    public static Records recordObjects(int count) {
        List<Record> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Record record = new Record();
            record.setId(i);
            record.setName("User " + i);
            record.setEmail("user" + i + "@example.com");
            record.setActive(i % 3 != 0);
            record.setScore(i % 100 + 0.5);
            record.setTags(List.of("alpha", "beta", "gamma"));
            Address address = new Address();
            address.setStreet(i + " Main Street");
            address.setCity("City" + (i % 50));
            address.setZip(String.format("%05d", i % 100000));
            record.setAddress(address);
            items.add(record);
        }
        Records records = new Records();
        records.setItems(items);
        return records;
    }

    /** One anchored mapping referenced by {@code aliases} aliases. */
    public static String anchors(int aliases) {
        StringBuilder text = new StringBuilder("""
            defaults: &defaults
              timeout: 30
              retries: 3
              tags: [a, b, c]
            services:
            """);
        for (int i = 0; i < aliases; i++) {
            text.append("  - name: service").append(i).append("\n    settings: *defaults\n");
        }
        return text.toString();
    }

    /** {@code count} literal block scalars of 20 lines each. */
    public static String blockScalars(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("- |\n");
            for (int line = 0; line < 20; line++) {
                text.append("  line ").append(line).append(" of block ").append(i).append(": some text here\n");
            }
        }
        return text.toString();
    }

    /** A stream of {@code count} small documents. */
    public static String multiDocument(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("---\nid: ").append(i).append("\nname: document ").append(i).append("\nenabled: true\n");
        }
        return text.toString();
    }

    /** The "billion laughs" input: 10^{@code levels} nodes once expanded. */
    public static String billionLaughs(int levels) {
        StringBuilder text = new StringBuilder("a0: &a0 [lol, lol, lol, lol, lol, lol, lol, lol, lol, lol]\n");
        for (int level = 1; level <= levels; level++) {
            text.append('a').append(level).append(": &a").append(level).append(" [");
            for (int i = 0; i < 10; i++) {
                text.append(i > 0 ? ", " : "").append("*a").append(level - 1);
            }
            text.append("]\n");
        }
        return text.toString();
    }

    public static String deepFlow(int depth) {
        return "[".repeat(depth) + "x" + "]".repeat(depth);
    }

    public static String deepBlock(int depth) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            text.append(" ".repeat(i)).append("k:\n");
        }
        return text.append(" ".repeat(depth)).append("v\n").toString();
    }
}
