package org.mvplugins.multiverse.core.world.entity;

import jakarta.inject.Inject;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpawnCategory;
import org.jetbrains.annotations.NotNull;
import org.jvnet.hk2.annotations.Service;
import org.mvplugins.multiverse.core.MultiverseCore;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;

import java.util.Set;
import java.util.function.Predicate;

@Service
public final class EntityPurger {

    private final MultiverseCore plugin;

    @Inject
    EntityPurger(@NotNull MultiverseCore plugin) {
        this.plugin = plugin;
    }

    public int purgeEntities(LoadedMultiverseWorld world) {
        return purgeEntitiesWithCondition(world, entity -> !world.getEntitySpawnConfig().shouldAllowSpawn(entity));
    }

    public int purgeEntities(LoadedMultiverseWorld world, SpawnCategory spawnCategory) {
        return purgeEntitiesWithCondition(world, entity -> entity.getSpawnCategory().equals(spawnCategory));
    }

    public int purgeEntities(LoadedMultiverseWorld world, SpawnCategory... spawnCategories) {
        Set<SpawnCategory> spawnCategoriesSet = Set.of(spawnCategories);
        return purgeEntitiesWithCondition(world, entity -> spawnCategoriesSet.contains(entity.getSpawnCategory()));
    }

    public int purgeAllEntities(LoadedMultiverseWorld world) {
        return purgeEntitiesWithCondition(world, entity -> true);
    }

    private int purgeEntitiesWithCondition(LoadedMultiverseWorld world, Predicate<Entity> condition) {
        return Math.toIntExact(world.getBukkitWorld()
                .map(bukkitWorld -> bukkitWorld.getEntities().stream()
                        .filter(entity -> !(entity instanceof Player))
                        .filter(condition)
                        // Removal must happen on the entity's own region thread, which may differ from
                        // whatever thread is iterating the world's entity list on Folia.
                        .peek(entity -> entity.getScheduler().run(plugin, task -> entity.remove(), null))
                        .count())
                .getOrElse(0L));
    }
}
