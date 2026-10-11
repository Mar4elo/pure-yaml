package io.pure.yaml.binding.mapper;

import java.util.Objects;

import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.core.parser.YamlProcessingLimits;

public final class YamlMapperBuilder {
    private boolean writeNulls = true;
    private boolean failOnUnknownProperties = true;
    private YamlInclude.Include defaultInclusion = YamlInclude.Include.ALWAYS;
    private YamlProcessingLimits processingLimits = YamlProcessingLimits.defaults();
    private boolean coerceQuotedScalars = true;
    private boolean resolveAliases;

    public YamlMapperBuilder writeNulls(boolean writeNulls) {
        this.writeNulls = writeNulls;
        return this;
    }

    public YamlMapperBuilder failOnUnknownProperties(boolean failOnUnknownProperties) {
        this.failOnUnknownProperties = failOnUnknownProperties;
        return this;
    }

    public YamlMapperBuilder defaultInclusion(YamlInclude.Include defaultInclusion) {
        this.defaultInclusion = Objects.requireNonNull(defaultInclusion, "defaultInclusion");
        return this;
    }

    public YamlMapperBuilder processingLimits(YamlProcessingLimits processingLimits) {
        this.processingLimits = Objects.requireNonNull(processingLimits, "processingLimits");
        return this;
    }

    /**
     * Whether quoted scalars such as {@code "8080"} or {@code "true"} may be read into numeric and boolean targets.
     * Enabled by default; disable it to require unquoted values, as the YAML 1.2 core schema does.
     */
    public YamlMapperBuilder coerceQuotedScalars(boolean coerceQuotedScalars) {
        this.coerceQuotedScalars = coerceQuotedScalars;
        return this;
    }

    /**
     * Whether aliases ({@code *name}) are read as the anchored node they refer to. Disabled by default;
     * when enabled, the number of nodes read through aliases is bounded by
     * {@link YamlProcessingLimits#maxAliasExpansion()} and the nesting depth by {@link YamlProcessingLimits#maxDepth()}.
     */
    public YamlMapperBuilder resolveAliases(boolean resolveAliases) {
        this.resolveAliases = resolveAliases;
        return this;
    }

    public YamlMapper build() {
        return new YamlMapper(new YamlMapperConfig(
            writeNulls,
            failOnUnknownProperties,
            defaultInclusion,
            processingLimits,
            coerceQuotedScalars,
            resolveAliases
        ));
    }
}
