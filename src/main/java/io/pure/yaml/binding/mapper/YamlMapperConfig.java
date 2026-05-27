package io.pure.yaml.binding.mapper;

import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.core.parser.YamlProcessingLimits;

public record YamlMapperConfig(
    boolean writeNulls,
    boolean failOnUnknownProperties,
    YamlInclude.Include defaultInclusion,
    YamlProcessingLimits processingLimits
) {
    public static YamlMapperConfig defaults() {
        return new YamlMapperConfig(true, true, YamlInclude.Include.ALWAYS, YamlProcessingLimits.defaults());
    }
}
