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

import bench.model.Records;

/**
 * Throughput of the common operations. Inputs: a 1 KB configuration, 500 records (100 KB) and
 * 50 000 records (10.8 MB) in block style, 10 000 records as single-line JSON (1.9 MB), 10 000
 * aliases (450 KB), 2 000 block scalars (1.5 MB) and a stream of 1 000 documents (45 KB).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class YamlBenchmark {
    @Param({"pure", "snakeyaml", "jackson"})
    public String lib;

    private YamlLib yaml;
    private String small;
    private String medium;
    private String large;
    private String json;
    private String anchors;
    private String blocks;
    private String stream;
    private Records mediumObjects;
    private Records largeObjects;

    @Setup
    public void setup() {
        yaml = YamlLib.of(lib, true);
        small = Data.smallConfig();
        medium = Data.records(500);
        large = Data.records(50_000);
        json = Data.recordsJson(10_000);
        anchors = Data.anchors(10_000);
        blocks = Data.blockScalars(2_000);
        stream = Data.multiDocument(1_000);
        mediumObjects = Data.recordObjects(500);
        largeObjects = Data.recordObjects(50_000);
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
    public Object untyped_large() {
        return yaml.parseUntyped(large);
    }

    @Benchmark
    public Object bind_medium() {
        return yaml.bindRecords(medium);
    }

    @Benchmark
    public Object bind_large() {
        return yaml.bindRecords(large);
    }

    @Benchmark
    public Object write_medium() {
        return yaml.write(mediumObjects);
    }

    @Benchmark
    public Object write_large() {
        return yaml.write(largeObjects);
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
        return yaml.parseStream(stream);
    }
}
