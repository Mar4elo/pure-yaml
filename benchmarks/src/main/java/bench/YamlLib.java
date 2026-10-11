package bench;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import bench.model.Records;
import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.core.parser.YamlProcessingLimits;

/**
 * The same operations on each library. {@link #permissive()} raises the size, depth and alias limits
 * of every library so that the large inputs are accepted; {@link #defaults()} uses each library as
 * shipped, for the edge cases.
 */
public interface YamlLib {
    String name();

    /** Parses to the library's own tree (YamlNode, SnakeYAML Node, JsonNode). */
    Object parseTree(String text);

    /** Parses to maps, lists and scalars. */
    Object parseUntyped(String text);

    Records bindRecords(String text);

    String write(Object value);

    /** Parses every document of a stream to maps, lists and scalars. */
    int parseStream(String text);

    static YamlLib of(String name, boolean permissive) {
        return switch (name) {
            case "pure" -> new Pure(permissive);
            case "snakeyaml" -> new Snake(permissive);
            case "jackson" -> new Jackson(permissive);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static List<YamlLib> permissive() {
        return List.of(new Pure(true), new Snake(true), new Jackson(true));
    }

    static List<YamlLib> defaults() {
        return List.of(new Pure(false), new Snake(false), new Jackson(false));
    }

    final class Pure implements YamlLib {
        private final YamlMapper mapper;
        private final YamlProcessingLimits limits;

        Pure(boolean permissive) {
            limits = permissive
                ? new YamlProcessingLimits(10_000, 1_000_000, 100_000, 200_000_000, 1_000, 100_000_000)
                : YamlProcessingLimits.defaults();
            mapper = YamlMapper.builder().processingLimits(limits).resolveAliases(true).build();
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
        public Records bindRecords(String text) {
            return mapper.readValue(text, Records.class);
        }

        @Override
        public String write(Object value) {
            return mapper.writeValueAsString(value);
        }

        @Override
        public int parseStream(String text) {
            int count = 0;
            for (var document : io.pure.yaml.core.Yaml.parseStream(text, limits).documents()) {
                mapper.readValue(document.root(), Object.class);
                count++;
            }
            return count;
        }
    }

    final class Snake implements YamlLib {
        private final Yaml yaml;

        Snake(boolean permissive) {
            LoaderOptions options = new LoaderOptions();
            if (permissive) {
                options.setCodePointLimit(200_000_000);
                options.setMaxAliasesForCollections(1_000_000);
                options.setNestingDepthLimit(10_000);
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
        public Records bindRecords(String text) {
            return yaml.loadAs(text, Records.class);
        }

        @Override
        public String write(Object value) {
            return yaml.dump(value);
        }

        @Override
        public int parseStream(String text) {
            int count = 0;
            for (Object ignored : yaml.loadAll(text)) {
                count++;
            }
            return count;
        }
    }

    final class Jackson implements YamlLib {
        private final YAMLMapper mapper;

        Jackson(boolean permissive) {
            YAMLFactory factory;
            if (permissive) {
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

        @Override
        public int parseStream(String text) {
            try (MappingIterator<Object> documents = mapper.readerFor(new TypeReference<Object>() {
            }).readValues(text)) {
                List<Object> all = new ArrayList<>();
                documents.forEachRemaining(all::add);
                return all.size();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
