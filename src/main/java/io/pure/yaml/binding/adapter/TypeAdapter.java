package io.pure.yaml.binding.adapter;

import java.lang.reflect.Type;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.core.model.YamlNode;

public interface TypeAdapter<T> {
    YamlNode toYaml(T value, YamlMapper mapper);

    T fromYaml(YamlNode node, Type type, YamlMapper mapper, String path);
}
