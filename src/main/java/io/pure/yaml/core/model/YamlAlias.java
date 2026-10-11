package io.pure.yaml.core.model;

import java.util.Objects;

/**
 * An alias node ({@code *name}).
 *
 * @param alias  anchor name the alias refers to
 * @param target the anchored node the parser resolved the alias to, or {@code null} for aliases built by hand
 */
public record YamlAlias(String alias, YamlNode target) implements YamlNode {
    public YamlAlias(String alias) {
        this(alias, null);
    }

    // The target is compared by identity: it is a reference into the same graph, not part of this node's value.
    @Override
    public boolean equals(Object other) {
        return other instanceof YamlAlias that && Objects.equals(alias, that.alias) && target == that.target;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(alias) * 31 + System.identityHashCode(target);
    }

    @Override
    public String toString() {
        return "YamlAlias[*" + alias + "]";
    }
}
