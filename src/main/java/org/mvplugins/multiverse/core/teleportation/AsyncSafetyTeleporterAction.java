package org.mvplugins.multiverse.core.teleportation;

import co.aikar.commands.BukkitCommandIssuer;
import com.dumptruckman.minecraft.util.Logging;
import io.papermc.lib.PaperLib;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mvplugins.multiverse.core.MultiverseCore;
import org.mvplugins.multiverse.core.destination.DestinationInstance;
import org.mvplugins.multiverse.core.event.MVTeleportDestinationEvent;
import org.mvplugins.multiverse.core.utils.result.AsyncAttempt;
import org.mvplugins.multiverse.core.utils.result.AsyncAttemptsAggregate;
import org.mvplugins.multiverse.core.utils.result.Attempt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Teleports one or more entity safely to a location.
 */
public final class AsyncSafetyTeleporterAction {

    @NotNull
    private final MultiverseCore multiverseCore;
    private final BlockSafety blockSafety;
    private final TeleportQueue teleportQueue;
    private final PluginManager pluginManager;

    private final @NotNull Either<Location, DestinationInstance<?, ?>> locationOrDestination;
    private boolean checkSafety;
    private PassengerMode passengerMode = PassengerModes.DEFAULT;
    private @Nullable CommandSender teleporter = null;

    AsyncSafetyTeleporterAction(
            @NotNull MultiverseCore multiverseCore,
            @NotNull BlockSafety blockSafety,
            @NotNull TeleportQueue teleportQueue,
            @NotNull PluginManager pluginManager,
            @NotNull Either<Location, DestinationInstance<?, ?>> locationOrDestination) {
        this.multiverseCore = multiverseCore;
        this.blockSafety = blockSafety;
        this.teleportQueue = teleportQueue;
        this.pluginManager = pluginManager;
        this.locationOrDestination = locationOrDestination;
        this.checkSafety = locationOrDestination.fold(
                location -> true,
                destination -> destination != null && destination.checkTeleportSafety()
        );
    }

    /**
     * Sets whether to check for safe location before teleport.
     *
     * @param checkSafety Whether to check for safe location
     * @return The same {@link AsyncSafetyTeleporterAction} to be chained
     */
    public AsyncSafetyTeleporterAction checkSafety(boolean checkSafety) {
        this.checkSafety = checkSafety;
        return this;
    }

    /**
     * Sets the passenger mode
     *
     * @param passengerMode The passenger mode
     * @return The same {@link AsyncSafetyTeleporterAction} to be chained
     * 
     * @since 5.1
     */
    @ApiStatus.AvailableSince("5.1")
    public AsyncSafetyTeleporterAction passengerMode(@NotNull PassengerMode passengerMode) {
        this.passengerMode = passengerMode;
        return this;
    }

    /**
     * Sets the teleporter.
     *
     * @param issuer The issuer
     * @return The same {@link AsyncSafetyTeleporterAction} to be chained
     */
    public AsyncSafetyTeleporterAction by(@NotNull BukkitCommandIssuer issuer) {
        return by(issuer.getIssuer());
    }

    /**
     * Sets the teleporter.
     *
     * @param teleporter The teleporter
     * @return The same {@link AsyncSafetyTeleporterAction} to be chained
     */
    public AsyncSafetyTeleporterAction by(@NotNull CommandSender teleporter) {
        this.teleporter = teleporter;
        return this;
    }

    /**
     * Teleport multiple entities
     *
     * @param teleportees The entities to teleport
     * @param <T>   The entity type
     * @return A list of async futures that represent the teleportation result of each entity
     */
    public <T extends Entity> AsyncAttemptsAggregate<Void, TeleportFailureReason> teleport(@NotNull List<T> teleportees) {
        return AsyncAttemptsAggregate.allOfAggregate(teleportees.stream().map(this::teleportSingle).toList());
    }

    /**
     * Teleports one parent entity. Multiple entities may be teleported depending on {@link #passengerMode(PassengerMode)}.
     *
     * @param teleportee The entity to teleport
     * @return A list of async future that represents the teleportation result
     *
     * @since 5.1
     */
    @ApiStatus.AvailableSince("5.1")
    public AsyncAttemptsAggregate<Void, TeleportFailureReason> teleportSingle(@NotNull Entity teleportee) {
        var localTeleporter = this.teleporter == null ? teleportee : this.teleporter;

        Attempt<Location, TeleportFailureReason> locationAttempt = getLocation(teleportee);
        if (locationAttempt.isFailure()) {
            return AsyncAttemptsAggregate.allOf(AsyncAttempt.fromAttempt(locationAttempt.map(ignore -> (Void) null)));
        }

        // The safety check may need to dispatch to a different region than the one this thread currently owns
        // (e.g. a cross-world teleport), so it's async - it must not be blocked on here.
        CompletableFuture<AsyncAttemptsAggregate<Void, TeleportFailureReason>> deferred =
                doSafetyCheck(locationAttempt.get()).thenApply(safetyAttempt -> {
                    if (safetyAttempt.isFailure()) {
                        return AsyncAttemptsAggregate.<Void, TeleportFailureReason>allOf(
                                AsyncAttempt.fromAttempt(safetyAttempt.map(ignore -> (Void) null)));
                    }
                    if (teleportee instanceof Player player) {
                        this.teleportQueue.addToQueue(localTeleporter, player);
                    }
                    return doAsyncTeleport(teleportee, safetyAttempt.get());
                });

        return AsyncAttemptsAggregate.<Void, TeleportFailureReason>fromFuture(deferred)
                .thenRun(() -> {
                    if (teleportee instanceof Player player) {
                        this.teleportQueue.popFromQueue(player.getName());
                    }
                });
    }

    /**
     * Teleports one entity
     *
     * @param teleportee The entity to teleport
     * @return An async future that represents the teleportation result
     *
     * @deprecated Use {@link #teleportSingle(Entity)} instead, as teleport of single entity may result in multiple
     *             teleports due to vehicle and passengers based on {@link #passengerMode(PassengerMode)}.
     */
    @Deprecated(forRemoval = true, since = "5.1")
    @ApiStatus.ScheduledForRemoval(inVersion = "6.0")
    public AsyncAttempt<Void, TeleportFailureReason> teleport(@NotNull Entity teleportee) {
        return AsyncAttempt.ofFuture(teleportSingle(teleportee).toCompletableFuture().handle((aggregate, throwable) ->
                throwable != null
                        ? Attempt.failure(TeleportFailureReason.TELEPORT_FAILED_EXCEPTION)
                        : aggregate.hasFailure()
                                ? Attempt.failure(TeleportFailureReason.TELEPORT_FAILED)
                                : Attempt.success(null)));
    }

    private Attempt<Location, TeleportFailureReason> getLocation(@NotNull Entity teleportee) {
        return this.locationOrDestination.fold(
                this::parseLocation,
                destination -> parseDestination(teleportee, destination)
        );
    }

    private Attempt<Location, TeleportFailureReason> parseLocation(@Nullable Location location) {
        if (location == null) {
            return Attempt.failure(TeleportFailureReason.NULL_LOCATION);
        }
        //todo: if its a UnloadedWorldLocation, check worldManager if it an unloadedWorld
        return Try.of(() -> location.getWorld().getName())
                .map(ignore -> Attempt.<Location, TeleportFailureReason>success(location))
                .getOrElse(Attempt.failure(TeleportFailureReason.NULL_WORLD));
    }

    private Attempt<Location, TeleportFailureReason> parseDestination(
            @NotNull Entity teleportee, @Nullable DestinationInstance<?, ?> destination) {
        if (destination == null) {
            return Attempt.failure(TeleportFailureReason.NULL_DESTINATION);
        }
        MVTeleportDestinationEvent event = new MVTeleportDestinationEvent(destination, teleportee, teleporter);
        this.pluginManager.callEvent(event);
        if (event.isCancelled()) {
            return Attempt.failure(TeleportFailureReason.EVENT_CANCELLED);
        }
        return parseLocation(destination.getLocation(teleportee).getOrNull());
    }

    private CompletableFuture<Attempt<Location, TeleportFailureReason>> doSafetyCheck(@NotNull Location location) {
        if (!this.checkSafety) {
            return CompletableFuture.completedFuture(Attempt.success(location));
        }
        return blockSafety.findSafeSpawnLocation(location).thenApply(safeLocation -> safeLocation == null
                ? Attempt.failure(TeleportFailureReason.UNSAFE_LOCATION)
                : Attempt.success(safeLocation));
    }

    private AsyncAttemptsAggregate<Void, TeleportFailureReason> doAsyncTeleport(
            @NotNull Entity teleportee,
            @NotNull Location location
    ) {
        if (passengerMode.isDismountVehicle() && teleportee.isInsideVehicle()) {
            Entity vehicle = teleportee.getVehicle();
            if (vehicle != null) {
                return doVehicleTeleport(teleportee, location, vehicle);
            }
        }

        List<Entity> passengers = teleportee.getPassengers();
        if (passengerMode.isDismountPassengers() && !passengers.isEmpty()) {
            passengers.forEach(teleportee::removePassenger);
            if (passengerMode.isPassengersFollow()) {
                return doPassengersTeleport(teleportee, location, passengers);
            }
        }

        return AsyncAttemptsAggregate.allOf(doSingleTeleport(teleportee, location));
    }

    private AsyncAttemptsAggregate<Void, TeleportFailureReason> doVehicleTeleport(
            @NotNull Entity teleportee,
            @NotNull Location location,
            @NotNull Entity vehicle
    ) {
        if (passengerMode.isVehicleFollow()) {
            return doPassengersTeleport(vehicle, location, vehicle.getPassengers());
        }
        teleportee.leaveVehicle();
        return doAsyncTeleport(teleportee, location);
    }

    private AsyncAttemptsAggregate<Void, TeleportFailureReason> doPassengersTeleport(
            @NotNull Entity teleportee,
            @NotNull Location location,
            @NotNull List<Entity> passengers
    ) {
        List<Entity> toTeleport = new ArrayList<>(passengers);
        toTeleport.addFirst(teleportee);

        return AsyncAttemptsAggregate.allOfAggregate(toTeleport.stream()
                        .map(passenger -> doAsyncTeleport(passenger, location))
                        .toList())
                .onSuccess(() -> teleportee.getScheduler().run(multiverseCore, task -> {
                    passengers.forEach(teleportee::addPassenger);
                    Logging.finer("Mounted %d passengers to %s", passengers.size(), teleportee.getName());
                }, null));
    }

    private AsyncAttempt<Void, TeleportFailureReason> doSingleTeleport(
            @NotNull Entity teleportee,
            @NotNull Location location
    ) {
        return AsyncAttempt.of(PaperLib.teleportAsync(teleportee, location), exception -> {
            Logging.warning("Failed to teleport %s to %s: %s",
                    teleportee.getName(), location, exception.getMessage());
            return Attempt.failure(TeleportFailureReason.TELEPORT_FAILED_EXCEPTION);
        }).mapAttempt(success -> {
            if (success) {
                applyPostTeleportVelocity(teleportee);
                Logging.finer("Teleported async %s to %s", teleportee.getName(), location);
                return Attempt.success(null);
            }
            Logging.warning("Failed to async teleport %s to %s", teleportee.getName(), location);
            return Attempt.failure(TeleportFailureReason.TELEPORT_FAILED);
        });
    }

    private void applyPostTeleportVelocity(@NotNull Entity teleportee) {
        locationOrDestination.peek(destination ->
                destination.getVelocity(teleportee).peek(velocity ->
                        teleportee.getScheduler().runDelayed(
                                multiverseCore, task -> teleportee.setVelocity(velocity), null, 1L)));
    }
}
