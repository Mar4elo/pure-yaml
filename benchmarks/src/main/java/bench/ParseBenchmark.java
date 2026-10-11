package bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Parsing synthetic inputs: a 1 KB configuration, 500 records (100 KB) and 50 000 records (10.8 MB)
 * in block style, 10 000 records as single-line JSON (1.9 MB), 10 000 aliases (450 KB), 2 000 block
 * scalars (1.5 MB) and a stream of 1 000 documents (45 KB).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 3, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
public class ParseBenchmark {
    @Param({"pure", "snakeyaml", "engine", "jackson"})
    public String lib;

    private YamlLib yaml;
    private String small;
    private String medium;
    private String large;
    private String json;
    private String anchors;
    private String blocks;
    private String stream;

    @Setup
    public void setup() {
        yaml = YamlLib.of(lib, YamlLib.Mode.BENCHMARK);
        small = Data.smallConfig();
        medium = Data.records(500);
        large = Data.records(50_000);
        json = Data.recordsJson(10_000);
        anchors = Data.anchors(10_000);
        blocks = Data.blockScalars(2_000);
        stream = Data.multiDocument(1_000);
    }

    @Benchmark
    public Object tree_small() {
        return yaml.parseTree(small);
    }

    @Benchmark
    public Object tree_medium() {
        return yaml.parseTree(medium);
    }

    @Benchmark
    @Warmup(iterations = 5, time = 3)
    @Measurement(iterations = 5, time = 5)
    public Object tree_large() {
        return yaml.parseTree(large);
    }

    @Benchmark
    public Object untyped_small() {
        return yaml.parseUntyped(small);
    }

    @Benchmark
    public Object untyped_medium() {
        return yaml.parseUntyped(medium);
    }

    @Benchmark
    @Warmup(iterations = 5, time = 3)
    @Measurement(iterations = 5, time = 5)
    public Object untyped_large() {
        return yaml.parseUntyped(large);
    }

    @Benchmark
    public Object json_untyped() {
        return yaml.parseUntyped(json);
    }

    @Benchmark
    public Object anchors_untyped() {
        return yaml.parseUntyped(anchors);
    }

    @Benchmark
    public Object blockScalars_tree() {
        return yaml.parseTree(blocks);
    }

    @Benchmark
    public int stream_1000() {
        return yaml.parseUntypedAll(stream);
    }
}
