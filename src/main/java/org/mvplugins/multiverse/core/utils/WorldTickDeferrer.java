package org.mvplugins.multiverse.core.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.dumptruckman.minecraft.util.Logging;
import jakarta.inject.Inject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jvnet.hk2.annotations.Service;
import org.mvplugins.multiverse.core.MultiverseCore;

/**
 * Bridges Bukkit calls that are bound to a specific tick thread on Folia (global region or a location's
 * owning region) so they can be safely called from any thread.
 * <p>
 * On regular Paper/Spigot, the global region and every location's region are all just the main thread, so
 * this has no practical effect beyond what already worked.
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
     * <p>
     * Only safe to call from a thread that is not itself responsible for driving the global region (e.g. not
     * during plugin onEnable, before the server has started ticking).
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
        return blockOn(future -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> complete(future, action)));
    }

    /**
     * Runs the action on the region thread that owns the given location, blocking the calling thread until it
     * completes and returning its result. If already on that region's thread, runs immediately with no dispatch.
     * <p>
     * Reading or writing block/chunk state (e.g. {@code Block#getType()}) requires being on the specific
     * region that owns that location - the global region thread does not count.
     *
     * @param location The location whose owning region the action must run on.
     * @param action   The action to run.
     * @param <T> The return type of the action.
     * @return The result of the action.
     */
    public <T> T runOnRegionThread(Location location, Supplier<T> action) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            return action.get();
        }
        Logging.fine("Blocking on dispatch to region thread for %s...", location);
        return blockOn(future -> Bukkit.getRegionScheduler().execute(plugin, location, () -> complete(future, action)));
    }

    private <T> void complete(CompletableFuture<T> future, Supplier<T> action) {
        try {
            future.complete(action.get());
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
    }

    private <T> T blockOn(java.util.function.Consumer<CompletableFuture<T>> dispatch) {
        CompletableFuture<T> future = new CompletableFuture<>();
        dispatch.accept(future);
        try {
            return future.get(BLOCKING_DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for tick thread dispatch", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for tick thread dispatch", e);
        }
    }
}
