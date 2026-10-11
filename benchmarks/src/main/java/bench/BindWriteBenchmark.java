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
 * Binding YAML to Java objects and writing them: 500 records (100 KB) and 50 000 records (10.8 MB).
 * snakeyaml-engine is not included because it has no object binding.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 3, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
public class BindWriteBenchmark {
    @Param({"pure", "snakeyaml", "jackson"})
    public String lib;

    private YamlLib yaml;
    private String medium;
    private String large;
    private Records mediumObjects;
    private Records largeObjects;

    @Setup
    public void setup() {
        yaml = YamlLib.of(lib, YamlLib.Mode.BENCHMARK);
        medium = Data.records(500);
        large = Data.records(50_000);
        mediumObjects = Data.recordObjects(500);
        largeObjects = Data.recordObjects(50_000);
    }

    @Benchmark
    public Object bind_medium() {
        return yaml.bindRecords(medium);
    }

    @Benchmark
    @Warmup(iterations = 5, time = 3)
    @Measurement(iterations = 5, time = 5)
    public Object bind_large() {
        return yaml.bindRecords(large);
    }

    @Benchmark
    public Object write_medium() {
        return yaml.write(mediumObjects);
    }

    @Benchmark
    @Warmup(iterations = 5, time = 3)
    @Measurement(iterations = 5, time = 5)
    public Object write_large() {
        return yaml.write(largeObjects);
    }
}
