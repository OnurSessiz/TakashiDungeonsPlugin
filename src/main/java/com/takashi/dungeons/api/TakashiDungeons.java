package com.takashi.dungeons.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;

/**
 * The way in.
 *
 * <p>A static accessor over Bukkit's {@code ServicesManager} rather than a cast of
 * {@code getPlugin("TakashiDungeons")}: the cast needs the class to be loadable at the moment the
 * addon's own class is verified, which is the thing that turns a soft dependency into a hard one
 * on a server where this plugin is absent.
 *
 * <pre>{@code
 * // plugin.yml: softdepend: [TakashiDungeons]   (never depend:)
 * TakashiDungeons.optional().ifPresent(api -> getLogger().info(
 *         "Dungeons API " + api.apiVersion() + " found"));
 * }</pre>
 *
 * <p>Ask for it in {@code onEnable} at the earliest — the service is registered during this
 * plugin's own enable, and a {@code softdepend} guarantees only that this plugin enables first.
 * Holding the returned object is fine; it lives as long as the server does.
 */
public final class TakashiDungeons {

    /**
     * The API, or empty when TakashiDungeons is not installed — or is installed and failed to
     * enable, which is the case people forget: a plugin that threw during enable is still in the
     * plugin list and still answers {@code getPlugin}.
     */
    public static Optional<TakashiDungeonsAPI> optional() {
        RegisteredServiceProvider<TakashiDungeonsAPI> registration =
                Bukkit.getServicesManager().getRegistration(TakashiDungeonsAPI.class);
        return registration == null ? Optional.empty() : Optional.of(registration.getProvider());
    }

    /**
     * The API, or an exception.
     *
     * <p>For an addon that genuinely cannot work without it and has said so with {@code depend:}
     * in its own {@code plugin.yml}. Anything using {@code softdepend:} wants {@link #optional()}
     * — catching this to decide whether a plugin is installed is an exception used as a boolean.
     *
     * @throws IllegalStateException if TakashiDungeons is not present and enabled
     */
    public static TakashiDungeonsAPI api() {
        return optional().orElseThrow(() -> new IllegalStateException(
                "TakashiDungeons is not installed, or has not enabled yet. Add it to your "
                        + "plugin.yml as depend/softdepend, and ask for the API no earlier than "
                        + "your own onEnable."));
    }

    private TakashiDungeons() {
    }
}
