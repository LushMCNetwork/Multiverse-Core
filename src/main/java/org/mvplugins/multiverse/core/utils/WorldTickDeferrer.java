package org.mvplugins.multiverse.core.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.dumptruckman.minecraft.util.Logging;
import jakarta.inject.Inject;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jvnet.hk2.annotations.Service;
import org.mvplugins.multiverse.core.MultiverseCore;

/**
 * Bridges world-mutating Bukkit calls (create/unload world) onto the global region thread.
 * <p>
 * On Folia, {@code Bukkit.createWorld}/{@code Bukkit.unloadWorld} may only be called from the global region
 * thread. On regular Paper/Spigot, the "global region thread" is just the main thread, so this has no
 * practical effect beyond what already worked.
 */
@Service
public final class WorldTickDeferrer {

    private static final long BLOCKING_DISPATCH_TIMEOUT_SECONDS = 30;

    private final MultiverseCore plugin;

    @Inject
    WorldTickDeferrer(@NotNull MultiverseCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs the action on the global region thread, immediately if already on it, otherwise dispatches it there.
     * Does not wait for the action to complete.
     *
     * @param action The action to run.
     */
    public void deferWorldTick(Runnable action) {
        if (Bukkit.isGlobalTickThread()) {
            action.run();
            return;
        }
        Logging.fine("Dispatching action to global region thread...");
        Bukkit.getGlobalRegionScheduler().execute(plugin, action);
    }

    /**
     * Runs the action on the global region thread and blocks the calling thread until it completes, returning
     * its result. If already on the global region thread, runs immediately with no dispatch.
     *
     * @param action The action to run.
     * @param <T> The return type of the action.
     * @return The result of the action.
     */
    public <T> T runOnGlobalRegionThread(Supplier<T> action) {
        if (Bukkit.isGlobalTickThread()) {
            return action.get();
        }

        Logging.fine("Blocking on dispatch to global region thread...");
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });

        try {
            return future.get(BLOCKING_DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for global region thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for global region thread", e);
        }
    }
}
