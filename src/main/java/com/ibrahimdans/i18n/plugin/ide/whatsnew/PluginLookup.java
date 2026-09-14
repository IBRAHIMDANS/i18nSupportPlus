package com.ibrahimdans.i18n.plugin.ide.whatsnew;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds a plugin by id through public API, from Java on purpose.
 * <p>
 * Compiled against 2025.3, where {@link PluginId} is a Kotlin class, a Kotlin call to
 * {@code PluginId.getId(...)} goes through {@code PluginId.Companion} — a field 2025.1 does not
 * have, hence a {@code NoSuchFieldError} on every project opening there (#267). The workaround
 * scanned {@code PluginManagerCore.getPlugins()}, which the Marketplace verifier reports as
 * internal API. {@code javac} emits a plain {@code invokestatic PluginId.getId}, which both
 * versions carry: a static method in 2025.1, the {@code @JvmStatic} bridge in 2025.3.
 */
public final class PluginLookup {

    private PluginLookup() {
    }

    /** The descriptor of the plugin registered under {@code id}, or null when it is not loaded. */
    public static @Nullable IdeaPluginDescriptor find(@NotNull String id) {
        return PluginManagerCore.getPlugin(PluginId.getId(id));
    }
}
