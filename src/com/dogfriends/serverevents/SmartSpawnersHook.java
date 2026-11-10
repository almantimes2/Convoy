package com.dogfriends.serverevents;

import github.nighter.smartspawner.api.SmartSpawnerAPI;
import github.nighter.smartspawner.api.SmartSpawnerProvider;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

// Acceso a la API de SmartSpawner aislado en su propia clase: Java solo la carga si de
// verdad se llama, asi el plugin no revienta cuando SmartSpawner no esta instalado (es
// softdepend). NUNCA llamar a randomSpawnerItem sin comprobar antes isAvailable().
final class SmartSpawnersHook {

    // Mobs comunes que tienen sentido como spawner de loot. Se evitan tipos raros/no
    // espaneables (jefes, entidades sin IA, etc) para no generar un spawner inutil.
    private static final EntityType[] SPAWNABLE_POOL = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER,
            EntityType.ENDERMAN, EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
            EntityType.RAVAGER, EntityType.BLAZE, EntityType.CAVE_SPIDER, EntityType.SILVERFISH,
            EntityType.WITHER_SKELETON, EntityType.STRAY, EntityType.HUSK, EntityType.DROWNED,
            EntityType.MAGMA_CUBE, EntityType.SLIME, EntityType.PHANTOM, EntityType.GUARDIAN
    };

    private SmartSpawnersHook() {
    }

    static boolean isAvailable() {
        try {
            return SmartSpawnerProvider.getAPI() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // Crea un item de spawner de un mob al azar del pool de arriba. Devuelve null si la
    // API no responde (plugin desactualizado, error interno, etc).
    static ItemStack randomSpawnerItem(Random random) {
        try {
            SmartSpawnerAPI api = SmartSpawnerProvider.getAPI();
            if (api == null) return null;
            EntityType type = SPAWNABLE_POOL[random.nextInt(SPAWNABLE_POOL.length)];
            return api.createSpawnerItem(type);
        } catch (Throwable t) {
            return null;
        }
    }
}
