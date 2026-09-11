package io.kestra.plugin.opensearch.shared;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.annotations.PluginProperty;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Enforces that credential-bearing connection properties stay masked.
 *
 * <p>The consumer plugins run {@code plugin-doc-lint} (`PROP-002: Secret field is not masked`), but that
 * linter derives its scope from a plugin marketplace {@code metadata/index.yaml} that this library
 * deliberately does not ship (it is a plain library, not a plugin — see AGENTS.md). So the masking on
 * these shared fields is enforced neither by OSS/EE (they no longer own the code) nor by the linter here.
 * This reflection check is the guardrail that a future edit dropping {@code secret = true} fails the build.
 */
class SecretMaskingTest {
    @Test
    void headersMustBeMasked() {
        assertSecret(OpensearchConnection.class, "headers");
    }

    @Test
    void basicAuthUsernameMustBeMasked() {
        assertSecret(OpensearchConnection.BasicAuth.class, "username");
    }

    @Test
    void basicAuthPasswordMustBeMasked() {
        assertSecret(OpensearchConnection.BasicAuth.class, "password");
    }

    private static void assertSecret(Class<?> type, String fieldName) {
        Field field;
        try {
            field = type.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Field `" + fieldName + "` no longer exists on " + type.getSimpleName()
                + " — update this masking guard if the property was intentionally renamed or removed.", e);
        }

        var pluginProperty = field.getAnnotation(PluginProperty.class);
        assertThat("`" + type.getSimpleName() + "." + fieldName + "` must be annotated with @PluginProperty",
            pluginProperty, notNullValue());
        assertThat("`" + type.getSimpleName() + "." + fieldName + "` carries credentials and must set @PluginProperty(secret = true)",
            pluginProperty.secret(), is(true));
    }
}
