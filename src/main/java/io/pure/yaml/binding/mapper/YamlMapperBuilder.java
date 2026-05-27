package io.pure.yaml.binding.mapper;

import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.core.parser.YamlProcessingLimits;

public final class YamlMapperBuilder {
    private boolean writeNulls = true;
    private boolean failOnUnknownProperties = true;
    private YamlInclude.Include defaultInclusion = YamlInclude.Include.ALWAYS;
    private YamlProcessingLimits processingLimits = YamlProcessingLimits.defaults();

    public YamlMapperBuilder writeNulls(boolean writeNulls) {
        this.writeNulls = writeNulls;
        return this;
    }

    public YamlMapperBuilder failOnUnknownProperties(boolean failOnUnknownProperties) {
        this.failOnUnknownProperties = failOnUnknownProperties;
        return this;
    }

    public YamlMapperBuilder defaultInclusion(YamlInclude.Include defaultInclusion) {
        this.defaultInclusion = defaultInclusion;
        return this;
    }

    public YamlMapperBuilder processingLimits(YamlProcessingLimits processingLimits) {
        this.processingLimits = processingLimits;
        return this;
    }

    public YamlMapper build() {
        return new YamlMapper(new YamlMapperConfig(writeNulls, failOnUnknownProperties, defaultInclusion, processingLimits));
    }
}
