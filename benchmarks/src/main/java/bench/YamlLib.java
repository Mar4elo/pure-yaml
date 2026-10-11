package bench;

import java.io.StringReader;
import java.util.List;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.schema.CoreSchema;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import bench.model.Records;
import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.core.parser.YamlProcessingLimits;

/**
 * The same operations on each library.
 */
public interface YamlLib {
    /**
     * How a library is configured.
     */
    enum Mode {
        /** Each library exactly as shipped. */
        DEFAULTS,
        /** Defaults, except that each library is set up to resolve 10 000 aliases where it can. */
        ALIASES_ENABLED,
        /**
         * Raised size, depth and alias limits so that every benchmark input is accepted, and the YAML 1.2
         * core schema for snakeyaml-engine (its default is the JSON schema), so that all libraries resolve
         * scalars the same way.
         */
        BENCHMARK
    }

    String name();

    /** Parses one document to the library's own tree (YamlNode, SnakeYAML Node, JsonNode). */
    Object parseTree(String text);

    /** Parses one document to maps, lists and scalars. */
    Object parseUntyped(String text);

    /** Parses every document of a stream to the library's own tree; returns the number of documents. */
    int parseTreeAll(String text);

    /** Parses every document of a stream to maps, lists and scalars; returns the number of documents. */
    int parseUntypedAll(String text);

    /** Binds to Java objects; not supported by snakeyaml-engine. */
    Records bindRecords(String text);

    /** Writes Java objects; not supported by snakeyaml-engine. */
    String write(Object value);

    List<String> NAMES = List.of("pure", "snakeyaml", "engine", "jackson");

    static YamlLib of(String name, Mode mode) {
        return switch (name) {
            case "pure" -> new Pure(mode);
            case "snakeyaml" -> new Snake(mode);
            case "engine" -> new Engine(mode);
            case "jackson" -> new Jackson(mode);
            default -> throw new IllegalArgumentException(name);
        };
    }

    final class Pure implements YamlLib {
        private final YamlMapper mapper;
        private final YamlProcessingLimits limits;

        Pure(Mode mode) {
            limits = mode == Mode.BENCHMARK
                ? new YamlProcessingLimits(10_000, 1_000_000, 100_000, 200_000_000, 1_000, 100_000_000)
                : YamlProcessingLimits.defaults();
            // Alias resolution is off by default.
            mapper = YamlMapper.builder().processingLimits(limits).resolveAliases(mode != Mode.DEFAULTS).build();
        }

        @Override
        public String name() {
            return "pure";
        }

        @Override
        public Object parseTree(String text) {
            return io.pure.yaml.core.Yaml.parse(text, limits);
        }

        @Override
        public Object parseUntyped(String text) {
            return mapper.readValue(text, Object.class);
        }

        @Override
        public int parseTreeAll(String text) {
            return io.pure.yaml.core.Yaml.parseStream(text, limits).documents().size();
        }

        @Override
        public int parseUntypedAll(String text) {
            int count = 0;
            for (var document : io.pure.yaml.core.Yaml.parseStream(text, limits).documents()) {
                mapper.readValue(document.root(), Object.class);
                count++;
            }
            return count;
        }

        @Override
        public Records bindRecords(String text) {
            return mapper.readValue(text, Records.class);
        }

        @Override
        public String write(Object value) {
            return mapper.writeValueAsString(value);
        }
    }

    final class Snake implements YamlLib {
        private final Yaml yaml;

        Snake(Mode mode) {
            LoaderOptions options = new LoaderOptions();
            if (mode == Mode.BENCHMARK) {
                options.setCodePointLimit(200_000_000);
                options.setMaxAliasesForCollections(1_000_000);
                options.setNestingDepthLimit(10_000);
            } else if (mode == Mode.ALIASES_ENABLED) {
                options.setMaxAliasesForCollections(20_000);
            }
            yaml = new Yaml(options);
        }

        @Override
        public String name() {
            return "snakeyaml";
        }

        @Override
        public Object parseTree(String text) {
            return yaml.compose(new StringReader(text));
        }

        @Override
        public Object parseUntyped(String text) {
            return yaml.load(text);
        }

        @Override
        public int parseTreeAll(String text) {
            int count = 0;
            for (Object ignored : yaml.composeAll(new StringReader(text))) {
                count++;
            }
            return count;
        }

        @Override
        public int parseUntypedAll(String text) {
            int count = 0;
            for (Object ignored : yaml.loadAll(text)) {
                count++;
            }
            return count;
        }

        @Override
        public Records bindRecords(String text) {
            return yaml.loadAs(text, Records.class);
        }

        @Override
        public String write(Object value) {
            return yaml.dump(value);
        }
    }

    /**
     * snakeyaml-engine, the YAML 1.2 implementation by the author of SnakeYAML. It has no object binding.
     */
    final class Engine implements YamlLib {
        private final LoadSettings settings;

        Engine(Mode mode) {
            var builder = LoadSettings.builder();
            if (mode == Mode.BENCHMARK) {
                builder.setCodePointLimit(200_000_000).setMaxAliasesForCollections(1_000_000).setSchema(new CoreSchema());
            } else if (mode == Mode.ALIASES_ENABLED) {
                builder.setMaxAliasesForCollections(20_000);
            }
            settings = builder.build();
        }

        @Override
        public String name() {
            return "engine";
        }

        @Override
        public Object parseTree(String text) {
            return new Compose(settings).composeString(text).orElse(null);
        }

        @Override
        public Object parseUntyped(String text) {
            return new Load(settings).loadFromString(text);
        }

        @Override
        public int parseTreeAll(String text) {
            int count = 0;
            for (Object ignored : new Compose(settings).composeAllFromString(text)) {
                count++;
            }
            return count;
        }

        @Override
        public int parseUntypedAll(String text) {
            int count = 0;
            for (Object ignored : new Load(settings).loadAllFromString(text)) {
                count++;
            }
            return count;
        }

        @Override
        public Records bindRecords(String text) {
            throw new UnsupportedOperationException("snakeyaml-engine has no object binding");
        }

        @Override
        public String write(Object value) {
            throw new UnsupportedOperationException("snakeyaml-engine has no object binding");
        }
    }

    final class Jackson implements YamlLib {
        private final YAMLMapper mapper;

        Jackson(Mode mode) {
            YAMLFactory factory;
            if (mode == Mode.BENCHMARK) {
                LoaderOptions options = new LoaderOptions();
                options.setCodePointLimit(200_000_000);
                options.setMaxAliasesForCollections(1_000_000);
                options.setNestingDepthLimit(10_000);
                factory = YAMLFactory.builder()
                    .loaderOptions(options)
                    .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(10_000)
                        .maxStringLength(200_000_000)
                        .maxDocumentLength(200_000_000L)
                        .build())
                    .build();
            } else {
                // Jackson does not resolve aliases, so there is nothing to enable.
                factory = new YAMLFactory();
            }
            mapper = new YAMLMapper(factory);
        }

        @Override
        public String name() {
            return "jackson";
        }

        @Override
        public Object parseTree(String text) {
            try {
                return mapper.readTree(text);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Object parseUntyped(String text) {
            try {
                return mapper.readValue(text, Object.class);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public int parseTreeAll(String text) {
            return countAll(text, new TypeReference<JsonNode>() {
            });
        }

        @Override
        public int parseUntypedAll(String text) {
            return countAll(text, new TypeReference<Object>() {
            });
        }

        private <T> int countAll(String text, TypeReference<T> type) {
            try (MappingIterator<T> documents = mapper.readerFor(type).readValues(text)) {
                int count = 0;
                while (documents.hasNext()) {
                    documents.next();
                    count++;
                }
                return count;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Records bindRecords(String text) {
            try {
                return mapper.readValue(text, Records.class);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public String write(Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
