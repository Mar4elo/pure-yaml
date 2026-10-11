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
 * Parsing real-world documents (see fetch-data.sh): every document of the file, to the library's
 * tree and to maps and lists.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 3, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 3)
public class RealDocumentBenchmark {
    @Param({"pure", "snakeyaml", "engine", "jackson"})
    public String lib;

    @Param({"openapi-github.yaml", "cert-manager.yaml", "helm-values.yaml", "faker-ru.yml", "ingress-nginx.yaml", "compose.yaml"})
    public String document;

    private YamlLib yaml;
    private String text;

    @Setup
    public void setup() {
        yaml = YamlLib.of(lib, YamlLib.Mode.BENCHMARK);
        text = Data.real(document);
    }

    @Benchmark
    public int tree() {
        return yaml.parseTreeAll(text);
    }

    @Benchmark
    public int untyped() {
        return yaml.parseUntypedAll(text);
    }
}
