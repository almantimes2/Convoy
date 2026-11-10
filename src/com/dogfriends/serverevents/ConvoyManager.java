package com.dogfriends.serverevents;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class ConvoyManager implements Listener {

    // El tick principal corre cada tick (0.05s) para que el movimiento por velocidad de
    // los golems se vea fluido; la logica pesada (escoltas, particulas, vida) esta limitada
    // a cada HEAVY_EVERY ticks para no gastar CPU cada tick.
    private static final int TICK_INTERVAL = 1;
    private static final int HEAVY_EVERY = 10; // 0.5s
    private static final int AIRDROP_TICK_INTERVAL = 20; // 1s airdrop lock/wave ticker
    // El cliente interpola el descenso del airdrop entre teleports (setTeleportDuration),
    // asi que con menos paquetes el descenso se ve mas fluido.
    private static final int DESCENT_TICK_INTERVAL = 5;
    // Velocidad base (bloques/tick) del convoy; se multiplica por convoy.speed.
    private static final double BASE_MOVE_SPEED = 0.2;
    private static final String ALLY_TEAM_NAME = "convoy_allies";
    private static final String AIRDROP_GLOW_TEAM_NAME = "convoy_airdrop_glow";
    // Tope DURO de Minecraft para el atributo generic.max_health de cualquier entidad (no es
    // configurable, es del motor): setHealth() tira IllegalArgumentException por encima de esto.
    private static final double MAX_VANILLA_HEALTH = 1024.0;

    private enum State { IDLE, MOVING, WAITING }

    private static class ItemLootEntry {
        final Material material;
        final double chance;
        final int minAmount;
        final int maxAmount;
        // "NOMBRE_ENCANTAMIENTO:nivel" (ej. MENDING:1, SHARPNESS:5). Vacio si el item no
        // lleva encantamientos. En ENCHANTED_BOOK se guardan como encantamientos de libro.
        final List<String> enchantments;

        ItemLootEntry(Material material, double chance, int minAmount, int maxAmount, List<String> enchantments) {
            this.material = material;
            this.chance = chance;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.enchantments = enchantments;
        }
    }

    private static class CommandReward {
        final String command;
        final double chance;

        CommandReward(String command, double chance) {
            this.command = command;
            this.chance = chance;
        }
    }

    // Drop de armas/municion de QualityArmory al morir un escolta. "item" es el campo
    // "name:" interno de los yml de QA (ak47, 9mm, shell...), NO el nombre de archivo.
    private static class WeaponDropEntry {
        final String item;
        final double chance;
        final int minAmount;
        final int maxAmount;

        WeaponDropEntry(String item, double chance, int minAmount, int maxAmount) {
            this.item = item;
            this.chance = chance;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
        }
    }

    // Un escolta pendiente de aparecer: se encola cuando se dispara una oleada y se va
    // sacando de 2 en 2 (con particulas blancas de aviso) en processDefenderSpawns.
    private static class PendingDefender {
        final EntityType type;
        final Material weapon;
        Location loc;
        boolean telegraphed = false;
        // true = es el "Jefe Contrabandista" de esta oleada (mas vida, nombre propio, glow).
        boolean isBoss = false;

        PendingDefender(EntityType type, Material weapon) {
            this.type = type;
            this.weapon = weapon;
        }
    }

    private static class Airdrop {
        // descent
        BlockDisplay fallingDisplay;
        World world;
        double fixedX;
        double fixedZ;
        double startY;
        double targetY;
        long descentStartMillis;
        long descentDurationMillis;
        // landed state
        Location blockLocation;
        // Inventario VIRTUAL de 54 casillas (6 filas, el maximo real que permite Bukkit), no
        // ligado al tile-entity real del cofre (que de por si solo soporta 27/54). Se abre
        // a mano al hacer click derecho.
        Inventory virtualInventory;
        long unlockAtMillis;
        long expireAtMillis;
        TextDisplay countdown;
        boolean unlockedAnnounced = false;
        long lastWaveMillis;
        // 0 = nadie lo ha abierto todavia; al abrirse arranca el temporizador de limpieza.
        long openedAtMillis = 0;
        // Igual que pendingDefenders del convoy, pero POR AIRDROP (puede haber mas de uno
        // activo a la vez): telegrafia con particulas blancas antes de aparecer, de a lotes.
        final ArrayDeque<PendingDefender> pendingWaveMobs = new ArrayDeque<>();
        long lastWaveDripMillis = 0;
    }

    private final ServerEventsPlugin plugin;
    private final Random random = new Random();
    private Economy economy;
    // Etiqueta persistente en TODAS las entidades del evento: permite reconocer y limpiar
    // huerfanos (de crashes/reinicios a mitad de evento) incluso en otra sesion del server.
    private final NamespacedKey entityTag;
    // Etiqueta en el item de la brujula rastreadora del convoy.
    private final NamespacedKey trackerTag;
    // data.yml: ubicaciones de cofres de airdrop activos, para limpiarlos tras un crash.
    private final File dataFile;

    private boolean enabled;
    private int intervalMinMinutes;
    private int intervalMaxMinutes;
    private int noKillTimeoutMinutes;
    private int golemCount;
    private double golemMaxHealth;
    private String displayName;
    private double moveSpeed;
    private double arrivalThreshold;
    private double formationMaxSpread;
    private int waitTicksAtStop;
    private double moneyMin;
    private double moneyMax;
    private final List<ItemLootEntry> itemRewardEntries = new ArrayList<>();
    private final List<CommandReward> commandRewards = new ArrayList<>();

    private double defenderTriggerRadius;
    private double defenderMaxDistance;
    private long defenderWaveIntervalMillis;
    private int defenderMaxTotal;
    private int pillagerCount;
    private int vindicatorCount;
    private int witchCount;
    private int ravagerCount;
    private int evokerCount;
    private int ghastCount;
    private int blazeCount;
    private int creeperCount;
    private int defenderSpawnBatchSize;
    private long defenderDripIntervalMillis;
    private double defenderSpawnMinRadius;
    private double defenderSpawnMaxRadius;

    // Jefe Contrabandista: cada N oleadas de refuerzos, se suma UN defensor especial (mas
    // vida, nombre propio, brilla) como objetivo claro en vez de oleadas infinitas sin hito.
    private boolean waveBossEnabled;
    private int waveBossEveryWaves;
    private EntityType waveBossEntityType;
    private Material waveBossWeapon;
    private String waveBossDisplayName;
    private double waveBossHealthMultiplier;
    private double waveBossMoneyBonusMin;
    private double waveBossMoneyBonusMax;

    private int retryIfEmptyMinutes;
    private boolean damageRewardsEnabled;

    private boolean trackerEnabled;
    private int trackerUpdateSeconds;
    private String trackerItemName;
    private String messageTrackHint;

    private boolean scalingEnabled;
    private double scalingCountRadius;
    private double defendersPerExtraPlayer;
    private double defendersMaxMultiplier;
    private double golemHealthPerExtraPlayer;
    private double golemHealthMaxMultiplier;
    // Vida real de los golems de ESTE convoy (golem-max-health x multiplicador por jugadores).
    private double effectiveGolemHealth;

    // Convoy Blindado: variante rara (chance baja) con mas golems, mas vida y mejor botin,
    // como evento especial dentro del evento. Se decide una vez por spawn en trySpawnConvoy().
    private boolean armoredEnabled;
    private double armoredChance;
    private double armoredGolemCountMultiplier;
    private double armoredHealthMultiplier;
    private double armoredRewardMultiplier;
    private String armoredDisplayName;
    private String messageArmoredSpawn;
    // Valores efectivos DE ESTE spawn (normal o blindado): golemCount/displayName son la
    // config base, estos son los que realmente se usan al generar y recompensar el convoy.
    private int currentGolemCount;
    private String currentDisplayName;
    private double currentRewardMultiplier = 1.0;

    // Armas (guns) de QualityArmory: SOLO dropean los golems del cargamento al morir.
    private boolean golemWeaponDropsEnabled;
    private final List<WeaponDropEntry> golemWeaponDropEntries = new ArrayList<>();
    // Municion de QualityArmory: SOLO dropea de pillagers (escolta o de las oleadas del airdrop).
    private boolean ammoDropsEnabled;
    private final List<WeaponDropEntry> ammoDropEntries = new ArrayList<>();
    private boolean qaAvailable;
    private boolean smartSpawnerAvailable;

    private boolean healthHologramEnabled;
    private boolean particlesEnabled;
    private boolean soundsEnabled;
    private boolean bossbarEnabled;

    private String messageSpawn;
    private String messageDefendersSpawned;
    private String messageWaveBossKilled;
    private String messageGolemKilled;
    private String messageAllDestroyed;
    private String messageEscaped;

    private boolean airdropEnabled;
    private int airdropUnlockMinutes;
    private int airdropExpireMinutesAfterUnlock;
    private long airdropRemoveMillisAfterOpen;
    private int airdropFallHeight;
    private int airdropFallDurationSeconds;
    // true = convoy subterraneo (metro/tuneles): escolta y airdrop se quedan en la MISMA capa
    // de los golems (sin caida del cielo ni spawn en superficie).
    private boolean undergroundMode;
    // true = aunque el convoy sea normal, si hay techo encima de donde murio, el airdrop es
    // un cofre normal en ese punto en vez de caer del cielo.
    private boolean airdropGroundIfRoofed;
    private final List<ItemLootEntry> airdropLootEntries = new ArrayList<>();
    private boolean smartSpawnerDropEnabled;
    private double smartSpawnerDropChance;
    private int smartSpawnerDropMinAmount;
    private int smartSpawnerDropMaxAmount;
    private String messageAirdropIncoming;
    private String messageAirdropLanded;
    private String messageAirdropUnlocked;
    private String messageAirdropLocked;

    private boolean airdropWaveEnabled;
    private long airdropWaveIntervalMillis;
    private int airdropWavePillagers;
    private int airdropWaveVindicators;
    private int airdropWaveWitches;
    private int airdropWaveRavagers;
    private int airdropWaveEvokers;
    private int airdropWaveGhasts;
    private int airdropWaveBlazes;
    private String messageAirdropWave;

    private final List<Location> waypoints = new ArrayList<>();
    private final List<LivingEntity> golems = new ArrayList<>();
    private final Set<UUID> golemIds = new HashSet<>();
    private final Map<UUID, TextDisplay> healthDisplays = new HashMap<>();
    // Ultima vida mostrada en el holograma de cada golem, para no reenviar el texto
    // (paquetes a todos los jugadores cercanos) cuando la vida no cambio.
    private final Map<UUID, Integer> lastShownHealth = new HashMap<>();
    // Momento del ultimo golpe recibido (o de la ultima regeneracion) por golem: en las
    // paradas, si pasan 10s sin un golpe nuevo, regenera y este timestamp se reinicia.
    private final Map<UUID, Long> golemLastEventMillis = new HashMap<>();
    private static final long GOLEM_REGEN_INTERVAL_MILLIS = 10_000L;
    private static final double GOLEM_REGEN_AMOUNT = 10.0;
    private final List<LivingEntity> defenders = new ArrayList<>();
    private final Set<UUID> defenderIds = new HashSet<>();
    private final ArrayDeque<PendingDefender> pendingDefenders = new ArrayDeque<>();
    private long lastDefenderWaveMillis = 0;
    private long lastDefenderDripMillis = 0;
    // Cuantas oleadas de refuerzos van en ESTE convoy (para el Jefe Contrabandista cada N).
    private int defenderWaveCount = 0;
    private final Set<UUID> waveBossIds = new HashSet<>();
    private Team allyTeam;
    // Dano acumulado por jugador a cada golem, para repartir recompensas por aporte
    // en vez de darselas solo al del ultimo golpe.
    private final Map<UUID, Map<UUID, Double>> golemDamage = new HashMap<>();
    // Mobs de las oleadas de recuperacion del airdrop (antes no se rastreaban).
    private final List<LivingEntity> waveMobs = new ArrayList<>();
    private final Set<UUID> waveMobIds = new HashSet<>();
    private int trackerUpdateCounter = 0;

    private State state = State.IDLE;
    private Location convoyPosition;
    private int waypointIndex = 0;
    private int direction = 1;
    private int waitTicksRemaining = 0;
    private BossBar bossBar;
    private double lastBossProgress = -1;
    private int heavyCounter = 0;
    // Deteccion de golem atascado (muro, terreno nuevo, etc), POR GOLEM: posicion de
    // referencia y cuantos ciclos "pesados" seguidos paso sin avanzar de verdad. Cada golem
    // se compara contra su propia posicion anterior (no contra el grupo), asi se detecta
    // tanto al lider como a cualquier rezagado que se trabe en otro obstaculo distinto.
    private final Map<UUID, Location> golemStuckCheckPos = new HashMap<>();
    private final Map<UUID, Integer> golemStuckCycles = new HashMap<>();
    private static final int STUCK_CYCLES_THRESHOLD = 4; // 4 x 0.5s = ~2s sin avance real

    private BukkitTask tickTask;
    private BukkitTask timeoutTask;
    private BukkitTask scheduleTask;

    private final List<Airdrop> descendingAirdrops = new ArrayList<>();
    private final List<Airdrop> activeAirdrops = new ArrayList<>();
    private BukkitTask descentTickTask;
    private BukkitTask airdropTickTask;
    private Team airdropGlowTeam;

    public ConvoyManager(ServerEventsPlugin plugin) {
        this.plugin = plugin;
        this.entityTag = new NamespacedKey(plugin, "convoy-entity");
        this.trackerTag = new NamespacedKey(plugin, "convoy-tracker");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        loadConfig();
        setupEconomy();
    }

    private void setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        this.economy = rsp != null ? rsp.getProvider() : null;
    }

    private void loadConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("convoy.enabled", true);
        intervalMinMinutes = cfg.getInt("convoy.interval-min-minutes", 120);
        intervalMaxMinutes = Math.max(intervalMinMinutes, cfg.getInt("convoy.interval-max-minutes", 240));
        noKillTimeoutMinutes = cfg.getInt("convoy.no-kill-timeout-minutes", 15);
        golemCount = Math.max(1, cfg.getInt("convoy.golem-count", 5));
        golemMaxHealth = cfg.getDouble("convoy.golem-max-health", 750.0);
        displayName = color(cfg.getString("convoy.display-name", "&6&lEscolta de Contrabando"));
        moveSpeed = BASE_MOVE_SPEED * cfg.getDouble("convoy.speed", 1.0);
        arrivalThreshold = cfg.getDouble("convoy.arrival-threshold", 3.0);
        formationMaxSpread = cfg.getDouble("convoy.formation-max-spread", 10.0);
        waitTicksAtStop = (int) (cfg.getDouble("convoy.wait-minutes-at-stops", 0.5) * 60 * 20);
        moneyMin = cfg.getDouble("convoy.money-reward.min", 500);
        moneyMax = Math.max(moneyMin, cfg.getDouble("convoy.money-reward.max", 1500));

        healthHologramEnabled = cfg.getBoolean("convoy.health-hologram", true);
        particlesEnabled = cfg.getBoolean("convoy.effects.particles", true);
        soundsEnabled = cfg.getBoolean("convoy.effects.sounds", true);
        bossbarEnabled = cfg.getBoolean("convoy.effects.bossbar", true);

        defenderTriggerRadius = cfg.getDouble("convoy.defenders.trigger-radius", 15.0);
        defenderMaxDistance = cfg.getDouble("convoy.defenders.max-distance-from-cargo", 40.0);
        defenderWaveIntervalMillis = (long) (cfg.getDouble("convoy.defenders.wave-interval-seconds", 30.0) * 1000L);
        defenderMaxTotal = cfg.getInt("convoy.defenders.max-total", 40);
        pillagerCount = cfg.getInt("convoy.defenders.pillagers", 8);
        vindicatorCount = cfg.getInt("convoy.defenders.vindicators", 3);
        witchCount = cfg.getInt("convoy.defenders.witches", 2);
        ravagerCount = cfg.getInt("convoy.defenders.ravagers", 2);
        evokerCount = cfg.getInt("convoy.defenders.evokers", 1);
        ghastCount = cfg.getInt("convoy.defenders.ghasts", 2);
        blazeCount = cfg.getInt("convoy.defenders.blazes", 2);
        creeperCount = cfg.getInt("convoy.defenders.creepers", 3);
        defenderSpawnBatchSize = Math.max(1, cfg.getInt("convoy.defenders.spawn-batch-size", 2));
        defenderDripIntervalMillis = (long) (cfg.getDouble("convoy.defenders.spawn-batch-interval-seconds", 1.0) * 1000L);
        defenderSpawnMinRadius = cfg.getDouble("convoy.defenders.spawn-min-radius", 6.0);
        defenderSpawnMaxRadius = Math.max(defenderSpawnMinRadius, cfg.getDouble("convoy.defenders.spawn-max-radius", 10.0));

        waveBossEnabled = cfg.getBoolean("convoy.defenders.boss.enabled", true);
        waveBossEveryWaves = Math.max(1, cfg.getInt("convoy.defenders.boss.every-waves", 3));
        waveBossEntityType = parseEntityType(cfg.getString("convoy.defenders.boss.entity", "RAVAGER"), EntityType.RAVAGER);
        waveBossWeapon = parseMaterialOrNull(cfg.getString("convoy.defenders.boss.weapon", ""));
        waveBossDisplayName = color(cfg.getString("convoy.defenders.boss.display-name", "&4&l☠ Jefe Contrabandista ☠"));
        waveBossHealthMultiplier = Math.max(1.0, cfg.getDouble("convoy.defenders.boss.health-multiplier", 4.0));
        waveBossMoneyBonusMin = cfg.getDouble("convoy.defenders.boss.money-bonus-min", 300);
        waveBossMoneyBonusMax = Math.max(waveBossMoneyBonusMin, cfg.getDouble("convoy.defenders.boss.money-bonus-max", 800));

        retryIfEmptyMinutes = Math.max(1, cfg.getInt("convoy.retry-if-empty-minutes", 5));
        damageRewardsEnabled = cfg.getBoolean("convoy.damage-rewards", true);

        trackerEnabled = cfg.getBoolean("convoy.tracker.enabled", true);
        trackerUpdateSeconds = Math.max(1, cfg.getInt("convoy.tracker.update-seconds", 5));
        trackerItemName = color(cfg.getString("convoy.tracker.item-name", "&6&lRastreador de Convoy"));
        messageTrackHint = color(cfg.getString("convoy.messages.track-hint",
                "&c&l[CONVOY] &fUsa &6/convoy track &fpara recibir una brujula que apunta al convoy."));

        scalingEnabled = cfg.getBoolean("convoy.scaling.enabled", true);
        scalingCountRadius = cfg.getDouble("convoy.scaling.count-radius", 30.0);
        defendersPerExtraPlayer = cfg.getDouble("convoy.scaling.defenders-per-extra-player", 0.5);
        defendersMaxMultiplier = Math.max(1.0, cfg.getDouble("convoy.scaling.defenders-max-multiplier", 3.0));
        golemHealthPerExtraPlayer = cfg.getDouble("convoy.scaling.golem-health-per-extra-player", 0.1);
        golemHealthMaxMultiplier = Math.max(1.0, cfg.getDouble("convoy.scaling.golem-health-max-multiplier", 1.5));

        armoredEnabled = cfg.getBoolean("convoy.armored.enabled", true);
        armoredChance = cfg.getDouble("convoy.armored.chance", 0.15);
        armoredGolemCountMultiplier = Math.max(1.0, cfg.getDouble("convoy.armored.golem-count-multiplier", 1.6));
        armoredHealthMultiplier = Math.max(1.0, cfg.getDouble("convoy.armored.health-multiplier", 1.5));
        armoredRewardMultiplier = Math.max(1.0, cfg.getDouble("convoy.armored.reward-multiplier", 2.0));
        armoredDisplayName = color(cfg.getString("convoy.armored.display-name", "&4&l☠ Convoy Blindado ☠"));

        golemWeaponDropsEnabled = cfg.getBoolean("convoy.golem-weapon-drops.enabled", true);
        golemWeaponDropEntries.clear();
        loadWeaponDropList(cfg.getList("convoy.golem-weapon-drops.drops"), golemWeaponDropEntries);

        ammoDropsEnabled = cfg.getBoolean("convoy.ammo-drops.enabled", true);
        ammoDropEntries.clear();
        loadWeaponDropList(cfg.getList("convoy.ammo-drops.drops"), ammoDropEntries);

        itemRewardEntries.clear();
        loadItemLootList(cfg.getList("convoy.item-rewards"), itemRewardEntries);

        commandRewards.clear();
        List<?> cmdList = cfg.getList("convoy.command-rewards");
        if (cmdList != null) {
            for (Object obj : cmdList) {
                if (obj instanceof java.util.Map<?, ?> map) {
                    Object cmdObj = map.get("command");
                    if (cmdObj == null) continue;
                    double chance = map.get("chance") instanceof Number n ? n.doubleValue() : 1.0;
                    commandRewards.add(new CommandReward(String.valueOf(cmdObj), chance));
                }
            }
        }

        messageSpawn = color(cfg.getString("convoy.messages.spawn",
                "&c&l[CONVOY] &fUna escolta de contrabando fue detectada en %coords%! Encuentrala y eliminala para saquearla."));
        messageArmoredSpawn = color(cfg.getString("convoy.messages.armored-spawn",
                "&4&l[CONVOY] &f¡Un convoy BLINDADO fue detectado en %coords%! Mas escolta, mas vida y el doble de botin."));
        messageDefendersSpawned = color(cfg.getString("convoy.messages.defenders-spawned",
                "&4&l[CONVOY] &fRefuerzos de la escolta armada llegaron a defender el cargamento!"));
        messageWaveBossKilled = color(cfg.getString("convoy.messages.wave-boss-killed",
                "&4&l[CONVOY] &f%killer% elimino al Jefe Contrabandista!"));
        messageGolemKilled = color(cfg.getString("convoy.messages.golem-killed",
                "&a&l[CONVOY] &f%killer% destruyo una escolta del cargamento! (%remaining% restantes)"));
        messageAllDestroyed = color(cfg.getString("convoy.messages.all-destroyed",
                "&2&l[CONVOY] &fEl cargamento fue completamente destruido!"));
        messageEscaped = color(cfg.getString("convoy.messages.escaped",
                "&7&l[CONVOY] &fEl cargamento logro escapar sin ser detectado..."));

        airdropEnabled = cfg.getBoolean("convoy.airdrop.enabled", true);
        airdropUnlockMinutes = cfg.getInt("convoy.airdrop.unlock-minutes", 5);
        airdropExpireMinutesAfterUnlock = cfg.getInt("convoy.airdrop.expire-minutes-after-unlock", 30);
        airdropRemoveMillisAfterOpen = cfg.getInt("convoy.airdrop.remove-seconds-after-open", 10) * 1000L;
        undergroundMode = cfg.getBoolean("convoy.underground", false);
        airdropGroundIfRoofed = cfg.getBoolean("convoy.airdrop.ground-if-roofed", true);
        airdropFallHeight = cfg.getInt("convoy.airdrop.fall-height", 60);
        airdropFallDurationSeconds = cfg.getInt("convoy.airdrop.fall-duration-seconds", 20);
        airdropLootEntries.clear();
        loadItemLootList(cfg.getList("convoy.airdrop.loot"), airdropLootEntries);

        smartSpawnerDropEnabled = cfg.getBoolean("convoy.airdrop.smart-spawner-drop.enabled", true);
        smartSpawnerDropChance = cfg.getDouble("convoy.airdrop.smart-spawner-drop.chance", 0.1);
        smartSpawnerDropMinAmount = cfg.getInt("convoy.airdrop.smart-spawner-drop.min-amount", 1);
        smartSpawnerDropMaxAmount = Math.max(smartSpawnerDropMinAmount, cfg.getInt("convoy.airdrop.smart-spawner-drop.max-amount", 1));

        messageAirdropIncoming = color(cfg.getString("convoy.airdrop.messages.incoming",
                "&e&l[AIRDROP] &fUn cargamento de emergencia esta cayendo del cielo!"));
        messageAirdropLanded = color(cfg.getString("convoy.airdrop.messages.landed",
                "&e&l[AIRDROP] &fEl airdrop aterrizo en %coords%! Se desbloqueara en %minutes% minutos."));
        messageAirdropUnlocked = color(cfg.getString("convoy.airdrop.messages.unlocked",
                "&a&l[AIRDROP] &fEl airdrop ya se puede abrir!"));
        messageAirdropLocked = color(cfg.getString("convoy.airdrop.messages.locked-interact",
                "&cEste airdrop se desbloquea en %time%"));

        airdropWaveEnabled = cfg.getBoolean("convoy.airdrop.waves.enabled", true);
        airdropWaveIntervalMillis = cfg.getInt("convoy.airdrop.waves.interval-minutes", 1) * 60_000L;
        airdropWavePillagers = cfg.getInt("convoy.airdrop.waves.pillagers", 6);
        airdropWaveVindicators = cfg.getInt("convoy.airdrop.waves.vindicators", 3);
        airdropWaveWitches = cfg.getInt("convoy.airdrop.waves.witches", 2);
        airdropWaveRavagers = cfg.getInt("convoy.airdrop.waves.ravagers", 1);
        airdropWaveEvokers = cfg.getInt("convoy.airdrop.waves.evokers", 1);
        airdropWaveGhasts = cfg.getInt("convoy.airdrop.waves.ghasts", 0);
        airdropWaveBlazes = cfg.getInt("convoy.airdrop.waves.blazes", 0);
        messageAirdropWave = color(cfg.getString("convoy.airdrop.messages.wave",
                "&4&l[AIRDROP] &fUna oleada de contrabandistas llego a recuperar el cargamento caido!"));

        waypoints.clear();
        List<String> wpStrings = cfg.getStringList("convoy.waypoints");
        for (String s : wpStrings) {
            Location loc = parseLocation(s);
            if (loc != null) {
                waypoints.add(loc);
            }
        }
    }

    private EntityType parseEntityType(String raw, EntityType fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return EntityType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Tipo de entidad invalido en configuracion: " + raw);
            return fallback;
        }
    }

    private Material parseMaterialOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Material.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Material invalido en configuracion: " + raw);
            return null;
        }
    }

    private void loadWeaponDropList(List<?> rawList, List<WeaponDropEntry> target) {
        if (rawList == null) return;
        for (Object obj : rawList) {
            if (obj instanceof java.util.Map<?, ?> map) {
                Object itemObj = map.get("item");
                if (itemObj == null) continue;
                double chance = map.get("chance") instanceof Number n ? n.doubleValue() : 0.1;
                int minA = map.get("min-amount") instanceof Number n ? n.intValue() : 1;
                int maxA = map.get("max-amount") instanceof Number n ? n.intValue() : 1;
                target.add(new WeaponDropEntry(String.valueOf(itemObj), chance, minA, Math.max(minA, maxA)));
            }
        }
    }

    private void loadItemLootList(List<?> rawList, List<ItemLootEntry> target) {
        if (rawList == null) return;
        for (Object obj : rawList) {
            if (obj instanceof java.util.Map<?, ?> map) {
                try {
                    Material material = Material.valueOf(String.valueOf(map.get("material")).toUpperCase());
                    double chance = map.get("chance") instanceof Number n ? n.doubleValue() : 0.5;
                    int minA = map.get("min-amount") instanceof Number n ? n.intValue() : 1;
                    int maxA = map.get("max-amount") instanceof Number n ? n.intValue() : 1;
                    List<String> enchants = new ArrayList<>();
                    Object enchObj = map.get("enchantments");
                    if (enchObj instanceof List<?> enchList) {
                        for (Object e : enchList) {
                            enchants.add(String.valueOf(e));
                        }
                    }
                    target.add(new ItemLootEntry(material, chance, minA, Math.max(minA, maxA), enchants));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Material invalido en configuracion de loot: " + map.get("material"));
                }
            }
        }
    }

    // Construye el ItemStack real de una entrada de loot, aplicando sus encantamientos
    // (si tiene). En ENCHANTED_BOOK se guardan como encantamientos "guardados" del libro;
    // en cualquier otro item se aplican directamente (nivel sin limite vanilla).
    private ItemStack buildLootItem(ItemLootEntry entry, int amount) {
        ItemStack stack = new ItemStack(entry.material, amount);
        if (entry.enchantments.isEmpty()) {
            return stack;
        }
        if (entry.material == Material.ENCHANTED_BOOK) {
            var meta = (org.bukkit.inventory.meta.EnchantmentStorageMeta) stack.getItemMeta();
            for (String raw : entry.enchantments) {
                applyEnchant(raw, (name, level) -> meta.addStoredEnchant(name, level, true));
            }
            stack.setItemMeta(meta);
        } else {
            var meta = stack.getItemMeta();
            for (String raw : entry.enchantments) {
                applyEnchant(raw, (name, level) -> meta.addEnchant(name, level, true));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private interface EnchantApplier {
        void apply(org.bukkit.enchantments.Enchantment enchant, int level);
    }

    private void applyEnchant(String raw, EnchantApplier applier) {
        String[] parts = raw.split(":");
        org.bukkit.enchantments.Enchantment enchant = org.bukkit.enchantments.Enchantment.getByKey(
                org.bukkit.NamespacedKey.minecraft(parts[0].trim().toLowerCase()));
        if (enchant == null) {
            plugin.getLogger().warning("Encantamiento invalido en configuracion de loot: " + raw);
            return;
        }
        int level = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
        applier.apply(enchant, level);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String formatCoords(Location loc) {
        return loc.getWorld().getName() + " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }

    private Location parseLocation(String s) {
        try {
            String[] parts = s.split(",");
            World world = Bukkit.getWorld(parts[0].trim());
            if (world == null) return null;
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            return new Location(world, x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveWaypoints() {
        List<String> serialized = new ArrayList<>();
        for (Location loc : waypoints) {
            serialized.add(loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ());
        }
        plugin.getConfig().set("convoy.waypoints", serialized);
        plugin.saveConfig();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start() {
        legacyScanExpiresAtMillis = System.currentTimeMillis() + LEGACY_SCAN_WINDOW_MILLIS;
        validateWeaponDrops();
        smartSpawnerAvailable = SmartSpawnersHook.isAvailable();
        if (smartSpawnerDropEnabled && !smartSpawnerAvailable) {
            plugin.getLogger().warning("smart-spawner-drop esta activado pero SmartSpawner no esta instalado: no se agregaran spawners al airdrop.");
        }
        int removed = cleanupOrphans();
        if (removed > 0) {
            plugin.getLogger().info("Limpieza inicial: " + removed
                    + " entidades/cofres huerfanos de eventos anteriores eliminados.");
        }
        if (!enabled) {
            return;
        }
        scheduleNext();
    }

    // Valida al arranque que los nombres de golem-weapon-drops/ammo-drops existan en
    // QualityArmory, para enterarse en consola de inmediato (y no descubrir en juego que
    // no dropea nada).
    private void validateWeaponDrops() {
        qaAvailable = Bukkit.getPluginManager().getPlugin("QualityArmory") != null;
        validateItemDropList("golem-weapon-drops", golemWeaponDropsEnabled, golemWeaponDropEntries);
        validateItemDropList("ammo-drops", ammoDropsEnabled, ammoDropEntries);
    }

    private void validateItemDropList(String configLabel, boolean enabled, List<WeaponDropEntry> entries) {
        if (!enabled || entries.isEmpty()) {
            return;
        }
        if (!qaAvailable) {
            plugin.getLogger().warning(configLabel + " esta activado pero QualityArmory no esta instalado: no se soltaran items.");
            return;
        }
        for (WeaponDropEntry e : entries) {
            try {
                if (!QualityArmoryHook.itemExists(e.item)) {
                    plugin.getLogger().warning(configLabel + ": el item '" + e.item
                            + "' NO existe en QualityArmory (usa el campo 'name:' interno del yml, ej: ak47, 9mm, shell). Se ignorara.");
                }
            } catch (Throwable t) {
                plugin.getLogger().warning(configLabel + ": error consultando QualityArmory: " + t.getMessage());
                return;
            }
        }
    }

    public void shutdown() {
        cancelTasks();
        removeAllEntities();
        if (descentTickTask != null) {
            descentTickTask.cancel();
            descentTickTask = null;
        }
        if (airdropTickTask != null) {
            airdropTickTask.cancel();
            airdropTickTask = null;
        }
        for (Airdrop a : descendingAirdrops) {
            if (a.fallingDisplay != null && a.fallingDisplay.isValid()) {
                a.fallingDisplay.remove();
            }
        }
        for (Airdrop a : activeAirdrops) {
            if (a.countdown != null && a.countdown.isValid()) {
                a.countdown.remove();
            }
            if (a.fallingDisplay != null && a.fallingDisplay.isValid()) {
                a.fallingDisplay.remove();
            }
        }
        descendingAirdrops.clear();
        activeAirdrops.clear();
        for (LivingEntity e : waveMobs) {
            if (e.isValid()) e.remove();
        }
        waveMobs.clear();
        waveMobIds.clear();
        if (airdropGlowTeam != null) {
            airdropGlowTeam.unregister();
            airdropGlowTeam = null;
        }
    }

    private void cancelTasks() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        if (timeoutTask != null) { timeoutTask.cancel(); timeoutTask = null; }
        if (scheduleTask != null) { scheduleTask.cancel(); scheduleTask = null; }
    }

    private void removeAllEntities() {
        for (LivingEntity e : golems) {
            if (e.isValid()) e.remove();
        }
        for (TextDisplay d : healthDisplays.values()) {
            if (d.isValid()) d.remove();
        }
        for (LivingEntity e : defenders) {
            if (e.isValid()) e.remove();
        }
        golems.clear();
        golemIds.clear();
        healthDisplays.clear();
        lastShownHealth.clear();
        golemLastEventMillis.clear();
        golemStuckCheckPos.clear();
        golemStuckCycles.clear();
        defenders.clear();
        defenderIds.clear();
        pendingDefenders.clear();
        golemDamage.clear();
        lastDefenderWaveMillis = 0;
        lastDefenderDripMillis = 0;
        lastBossProgress = -1;
        defenderWaveCount = 0;
        waveBossIds.clear();
        state = State.IDLE;
        removeBossBar();
        removeTrackerCompasses();
        if (allyTeam != null) {
            allyTeam.unregister();
            allyTeam = null;
        }
    }

    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }

    private void scheduleNext() {
        int minTicks = intervalMinMinutes * 60 * 20;
        int maxTicks = intervalMaxMinutes * 60 * 20;
        long delayTicks = minTicks + (long) (random.nextDouble() * (maxTicks - minTicks));
        scheduleTask = Bukkit.getScheduler().runTaskLater(plugin, this::trySpawnConvoy, delayTicks);
    }

    private void trySpawnConvoy() {
        if (waypoints.size() < 2) {
            plugin.getLogger().warning("No hay suficientes waypoints de convoy configurados (usa /convoy addpoint). Reintentando mas tarde.");
            scheduleNext();
            return;
        }
        // Server vacio: no gastar el evento (ni CPU) sin nadie que lo pueda jugar.
        // Se reintenta en poco tiempo en vez de esperar el intervalo completo.
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            scheduleTask = Bukkit.getScheduler().runTaskLater(plugin, this::trySpawnConvoy, retryIfEmptyMinutes * 60L * 20L);
            return;
        }

        // Vida de los golems escalada por jugadores conectados (grupo grande = mas duro).
        double healthMult = 1.0;
        if (scalingEnabled) {
            int online = Bukkit.getOnlinePlayers().size();
            healthMult = Math.min(golemHealthMaxMultiplier, 1.0 + Math.max(0, online - 1) * golemHealthPerExtraPlayer);
        }
        effectiveGolemHealth = golemMaxHealth * healthMult;

        // Convoy Blindado: variante rara, decidida una sola vez por spawn. Mas golems, mas
        // vida (encima del escalado por jugadores) y mejor botin (currentRewardMultiplier).
        boolean armored = armoredEnabled && random.nextDouble() < armoredChance;
        currentGolemCount = armored ? Math.max(1, (int) Math.round(golemCount * armoredGolemCountMultiplier)) : golemCount;
        currentDisplayName = armored ? armoredDisplayName : displayName;
        currentRewardMultiplier = armored ? armoredRewardMultiplier : 1.0;
        if (armored) {
            effectiveGolemHealth *= armoredHealthMultiplier;
        }
        // Clamp al tope duro del motor: escalado por jugadores + blindado pueden combinarse
        // por encima de 1024 (ej. 750 base x1.5 escalado x1.5 blindado = 1687), y golem.
        // setHealth() tira excepcion (no clampea solo) si se le pasa un valor mayor a eso.
        effectiveGolemHealth = Math.min(MAX_VANILLA_HEALTH, effectiveGolemHealth);

        convoyPosition = waypoints.get(0).clone();
        waypointIndex = 1;
        direction = 1;
        state = State.MOVING;
        waitTicksRemaining = 0;
        lastDefenderWaveMillis = 0;
        lastDefenderDripMillis = 0;
        lastBossProgress = -1;
        heavyCounter = 0;
        golemStuckCheckPos.clear();
        golemStuckCycles.clear();
        pendingDefenders.clear();
        lastShownHealth.clear();
        defenderWaveCount = 0;
        waveBossIds.clear();

        var scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team existingTeam = scoreboard.getTeam(ALLY_TEAM_NAME);
        if (existingTeam != null) {
            existingTeam.unregister();
        }
        allyTeam = scoreboard.registerNewTeam(ALLY_TEAM_NAME);
        allyTeam.setAllowFriendlyFire(false);
        allyTeam.setColor(ChatColor.GOLD);

        for (int i = 0; i < currentGolemCount; i++) {
            spawnGolem(i);
        }

        if (bossbarEnabled) {
            BarColor barColor = armored ? BarColor.RED : BarColor.GREEN;
            bossBar = Bukkit.createBossBar(ChatColor.stripColor(currentDisplayName), barColor, BarStyle.SEGMENTED_10);
            for (Player p : Bukkit.getOnlinePlayers()) {
                bossBar.addPlayer(p);
            }
            bossBar.setProgress(1.0);
        }

        String spawnMsg = armored ? messageArmoredSpawn : messageSpawn;
        Bukkit.broadcastMessage(spawnMsg.replace("%coords%", formatCoords(waypoints.get(0))));
        if (trackerEnabled) {
            Bukkit.broadcastMessage(messageTrackHint);
        }
        if (soundsEnabled) {
            playGlobalSound(Sound.ENTITY_IRON_GOLEM_REPAIR, 1.0f, 0.8f);
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, this::onTimeout, noKillTimeoutMinutes * 60L * 20L);
    }

    private void spawnGolem(int index) {
        Location spawnLoc = waypoints.get(0).clone();
        if (index > 0) {
            double angle = (2 * Math.PI / Math.max(1, currentGolemCount - 1)) * (index - 1);
            spawnLoc.add(Math.cos(angle) * 1.8, 0, Math.sin(angle) * 1.8);
        }
        LivingEntity golem = (LivingEntity) spawnLoc.getWorld().spawnEntity(spawnLoc, EntityType.IRON_GOLEM);
        golem.setCustomName(currentDisplayName);
        golem.setCustomNameVisible(false);
        golem.setPersistent(true);
        golem.setRemoveWhenFarAway(false);
        var maxHealthAttr = golem.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(effectiveGolemHealth);
        }
        golem.setHealth(effectiveGolemHealth);
        golem.setGlowing(true);
        // Sube el step-height (vanilla: 0.6, no alcanza para subir un bloque entero) para que
        // el golem pase solo escalones/bordillos de terreno de 1 bloque sin quedar empujando
        // contra el borde (romper bloques en breakBlocksAhead queda como ultimo recurso).
        var stepHeightAttr = golem.getAttribute(Attribute.STEP_HEIGHT);
        if (stepHeightAttr != null) {
            stepHeightAttr.setBaseValue(1.0);
        }
        // NO usar setAI(false): en Paper eso tambien congela la fisica/velocidad y el golem
        // deja de moverse por completo (setVelocity no lo desplaza). En su lugar dejamos la
        // IA activa (para que travel()/gravedad sigan funcionando) pero le quitamos TODOS los
        // goals (atacar, vagar, mirar alrededor...) con la Mob Goals API de Paper, asi el
        // golem no ataca a la escolta ni a nadie, no vaga ni se distrae, y el plugin lo mueve
        // manualmente por velocidad hacia el waypoint.
        golem.setAI(true);
        Bukkit.getMobGoals().removeAllGoals((Mob) golem);
        tagEntity(golem);
        golems.add(golem);
        golemIds.add(golem.getUniqueId());
        golemLastEventMillis.put(golem.getUniqueId(), System.currentTimeMillis());
        if (allyTeam != null) {
            allyTeam.addEntity(golem);
        }

        if (healthHologramEnabled) {
            TextDisplay display = (TextDisplay) spawnLoc.getWorld().spawnEntity(spawnLoc.clone().add(0, 2.6, 0), EntityType.TEXT_DISPLAY);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setPersistent(true);
            tagEntity(display);
            applyHealthText(golem, display);
            healthDisplays.put(golem.getUniqueId(), display);
            // Montado como pasajero: el cliente lo mueve junto al golem solo, sin necesidad
            // de teletransportar el holograma cada tick desde el servidor.
            golem.addPassenger(display);
        }
    }

    private void applyHealthText(LivingEntity golem, TextDisplay display) {
        int hp = (int) Math.ceil(Math.max(0, golem.getHealth()));
        lastShownHealth.put(golem.getUniqueId(), hp);
        display.text(Component.text("❤ " + hp + " / " + (int) effectiveGolemHealth, NamedTextColor.RED));
    }

    private void tick() {
        purgeInvalidGolems();
        if (golems.isEmpty()) {
            return;
        }
        // Server vacio: el evento se congela (sin movimiento, particulas ni oleadas)
        // hasta que alguien vuelva a conectarse. Costo por tick ~cero.
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        updateConvoyPositionFromGolems();

        if (state == State.WAITING) {
            waitTicksRemaining -= TICK_INTERVAL;
            haltGolems();
            if (waitTicksRemaining <= 0) {
                advanceWaypoint();
                state = State.MOVING;
            }
        } else if (state == State.MOVING) {
            Location target = waypoints.get(waypointIndex);
            if (convoyPosition.distanceSquared(target) <= arrivalThreshold * arrivalThreshold) {
                state = State.WAITING;
                waitTicksRemaining = waitTicksAtStop;
                haltGolems();
                if (soundsEnabled) {
                    playGlobalSound(Sound.ENTITY_IRON_GOLEM_STEP, 1.0f, 0.6f);
                }
            } else {
                driveGolems(target);
            }
        }

        correctStragglerGolems();

        heavyCounter++;
        if (heavyCounter % HEAVY_EVERY == 0) {
            long now = System.currentTimeMillis();
            purgeInvalidDefenders();
            updateHealthDisplays();
            updateBossBar();
            spawnAmbientParticles();
            checkForNearbyPlayers(now);
            processDefenderSpawns(now);
            removeStragglerDefenders();
            if (state == State.MOVING) {
                checkStuckGolems();
            } else {
                golemStuckCheckPos.clear();
                golemStuckCycles.clear();
                regenerateStoppedGolems(now);
            }
            trackerUpdateCounter++;
            if (trackerEnabled && trackerUpdateCounter >= trackerUpdateSeconds * 2) {
                trackerUpdateCounter = 0;
                updateTrackerCompasses();
            }
        }
    }

    private void purgeInvalidGolems() {
        golems.removeIf(e -> {
            if (e.isValid() && !e.isDead()) {
                return false;
            }
            UUID id = e.getUniqueId();
            golemIds.remove(id);
            lastShownHealth.remove(id);
            golemDamage.remove(id);
            golemLastEventMillis.remove(id);
            golemStuckCheckPos.remove(id);
            golemStuckCycles.remove(id);
            TextDisplay display = healthDisplays.remove(id);
            if (display != null && display.isValid()) {
                display.remove();
            }
            return true;
        });
    }

    // Los escoltas usan setRemoveWhenFarAway(true): si despawnean solos (jugadores lejos)
    // NO disparan EntityDeathEvent, asi que sin esta purga la lista acumularia entradas
    // muertas hasta llenar el limite max-total y bloquear refuerzos para siempre.
    private void purgeInvalidDefenders() {
        if (defenders.isEmpty()) {
            return;
        }
        defenders.removeIf(e -> {
            if (e.isValid() && !e.isDead()) {
                return false;
            }
            defenderIds.remove(e.getUniqueId());
            return true;
        });
    }

    // El "lider" es el golem MAS ADELANTADO (mas cerca del waypoint actual), no el promedio
    // del grupo: si se usara el promedio, un golem atascado arrastraria la posicion de
    // referencia hacia atras y correctStragglerGolems terminaria regresando a TODOS los
    // demas hacia el atascado. Con el lider, los que avanzan no se frenan por el que quedo
    // atras (que igual se corrige solo hacia el lider en correctStragglerGolems).
    private void updateConvoyPositionFromGolems() {
        if (golems.isEmpty()) return;
        if (waypoints.isEmpty() || waypointIndex < 0 || waypointIndex >= waypoints.size()) {
            convoyPosition = golems.get(0).getLocation();
            return;
        }
        Location target = waypoints.get(waypointIndex);
        LivingEntity leader = golems.get(0);
        double bestDistSq = Double.MAX_VALUE;
        for (LivingEntity g : golems) {
            double distSq = g.getLocation().distanceSquared(target);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                leader = g;
            }
        }
        convoyPosition = leader.getLocation();
    }

    // Mueve a los golems (sin IA) por velocidad hacia el waypoint actual y los orienta
    // hacia donde caminan. Conserva la componente Y de la velocidad para que la gravedad
    // siga actuando (no vuelan) y el auto-step de la entidad suba escalones de 1 bloque.
    private void driveGolems(Location target) {
        for (LivingEntity golem : golems) {
            if (!golem.isValid() || golem.isDead()) continue;
            Vector dir = target.toVector().subtract(golem.getLocation().toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 1.0E-4) {
                continue;
            }
            dir.normalize().multiply(moveSpeed);
            Vector cur = golem.getVelocity();
            golem.setVelocity(new Vector(dir.getX(), cur.getY(), dir.getZ()));
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            golem.setRotation(yaw, 0f);
        }
    }

    private void haltGolems() {
        for (LivingEntity golem : golems) {
            if (!golem.isValid()) continue;
            Vector cur = golem.getVelocity();
            golem.setVelocity(new Vector(0, cur.getY(), 0));
        }
    }

    // En las paradas del convoy, cada golem que pase GOLEM_REGEN_INTERVAL_MILLIS (10s) sin
    // recibir un golpe se cura GOLEM_REGEN_AMOUNT (10 de vida) y el reloj se reinicia; un
    // golpe nuevo (onGolemAnyDamage) tambien reinicia el reloj, cancelando la regen en curso.
    private void regenerateStoppedGolems(long now) {
        if (state != State.WAITING) return;
        for (LivingEntity golem : golems) {
            if (!golem.isValid() || golem.isDead()) continue;
            double health = golem.getHealth();
            if (health >= effectiveGolemHealth) continue;
            UUID id = golem.getUniqueId();
            long lastEvent = golemLastEventMillis.getOrDefault(id, now);
            if (now - lastEvent >= GOLEM_REGEN_INTERVAL_MILLIS) {
                golem.setHealth(Math.min(effectiveGolemHealth, health + GOLEM_REGEN_AMOUNT));
                golemLastEventMillis.put(id, now);
            }
        }
    }

    // Compara la posicion del convoy contra la de hace un ciclo (0.5s): si en varios ciclos
    // seguidos no avanzo lo minimo esperado hacia el waypoint (un muro, una construccion
    // nueva, un desnivel que el step-height no alcanza a subir...) se "atraviesa" el
    // obstaculo en vez de quedar atascado para siempre.
    // Compara la posicion de CADA golem contra su propia posicion de hace un ciclo (0.5s):
    // el que no avanzo lo minimo esperado durante varios ciclos seguidos (bloqueado por un
    // muro, una construccion nueva, etc) rompe los bloques que tiene enfrente para pasar.
    private void checkStuckGolems() {
        if (waypoints.isEmpty()) return;
        Location target = waypoints.get(waypointIndex);
        double expectedMin = moveSpeed * HEAVY_EVERY * 0.3;
        double expectedMinSq = expectedMin * expectedMin;
        for (LivingEntity golem : golems) {
            if (!golem.isValid() || golem.isDead()) continue;
            UUID id = golem.getUniqueId();
            Location cur = golem.getLocation();
            Location last = golemStuckCheckPos.get(id);
            if (last == null) {
                golemStuckCheckPos.put(id, cur.clone());
                golemStuckCycles.put(id, 0);
                continue;
            }
            if (cur.distanceSquared(last) < expectedMinSq) {
                int cycles = golemStuckCycles.getOrDefault(id, 0) + 1;
                if (cycles >= STUCK_CYCLES_THRESHOLD) {
                    breakBlocksAhead(golem, target);
                    cycles = 0;
                }
                golemStuckCycles.put(id, cycles);
            } else {
                golemStuckCycles.put(id, 0);
            }
            golemStuckCheckPos.put(id, cur.clone());
        }
    }

    // Rompe (sin drops, para no regar loot ni incentivar farmear bloques con esto) la
    // columna de bloques justo delante del golem, en la direccion en la que intenta
    // avanzar, para que pueda atravesar el obstaculo que lo tiene atascado.
    private void breakBlocksAhead(LivingEntity golem, Location target) {
        Vector dir = target.toVector().subtract(golem.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            return;
        }
        dir.normalize();
        Location feet = golem.getLocation();
        World world = feet.getWorld();
        int bx = feet.getBlockX() + (int) Math.round(dir.getX());
        int bz = feet.getBlockZ() + (int) Math.round(dir.getZ());
        int by = feet.getBlockY();
        boolean brokeAny = false;
        for (int dy = 0; dy <= 2; dy++) {
            Block block = world.getBlockAt(bx, by + dy, bz);
            Material type = block.getType();
            // getHardness() < 0 = irrompible en vanilla (bedrock, marcos de portal...): no tocar.
            if (type != Material.AIR && type.isSolid() && type.getHardness() >= 0) {
                block.setType(Material.AIR);
                brokeAny = true;
            }
        }
        if (!brokeAny) {
            return;
        }
        Location fx = new Location(world, bx + 0.5, by + 1, bz + 0.5);
        if (particlesEnabled) {
            world.spawnParticle(Particle.CLOUD, fx, 16, 0.3, 0.6, 0.3, 0.03);
        }
        if (soundsEnabled) {
            world.playSound(fx, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.7f);
        }
    }

    private void correctStragglerGolems() {
        if (golems.size() <= 1) return;
        double maxSpreadSq = formationMaxSpread * formationMaxSpread;
        for (LivingEntity golem : golems) {
            if (golem.getLocation().distanceSquared(convoyPosition) > maxSpreadSq) {
                Location corrected = convoyPosition.clone();
                corrected.setYaw(golem.getLocation().getYaw());
                golem.teleport(corrected);
                // El teleport expulsa a los pasajeros: volver a montar el holograma de vida.
                TextDisplay display = healthDisplays.get(golem.getUniqueId());
                if (display != null && display.isValid()) {
                    display.teleport(corrected.clone().add(0, 2.6, 0));
                    golem.addPassenger(display);
                }
            }
        }
    }

    private void updateHealthDisplays() {
        for (LivingEntity golem : golems) {
            TextDisplay display = healthDisplays.get(golem.getUniqueId());
            if (display == null || !display.isValid()) continue;
            int hp = (int) Math.ceil(Math.max(0, golem.getHealth()));
            Integer last = lastShownHealth.get(golem.getUniqueId());
            if (last == null || last != hp) {
                applyHealthText(golem, display);
            }
        }
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        double total = 0;
        double max = golems.size() * effectiveGolemHealth;
        for (LivingEntity g : golems) {
            total += Math.max(0, g.getHealth());
        }
        double progress = max > 0 ? Math.max(0, Math.min(1, total / max)) : 0;
        // setProgress/setColor mandan paquetes a todos los jugadores: solo cuando de verdad cambia.
        if (Math.abs(progress - lastBossProgress) > 0.0005) {
            lastBossProgress = progress;
            bossBar.setProgress(progress);
            bossBar.setColor(progress > 0.5 ? BarColor.GREEN : progress > 0.25 ? BarColor.YELLOW : BarColor.RED);
        }
    }

    private void spawnAmbientParticles() {
        if (!particlesEnabled) return;
        for (LivingEntity golem : golems) {
            golem.getWorld().spawnParticle(Particle.DUST, golem.getLocation().add(0, 1.2, 0), 3, 0.3, 0.4, 0.3,
                    new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.1f));
        }
    }

    private void playGlobalSound(Sound sound, float volume, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    private void advanceWaypoint() {
        int size = waypoints.size();
        if (size <= 1) return;
        waypointIndex += direction;
        if (waypointIndex >= size) {
            waypointIndex = size - 2;
            direction = -1;
        } else if (waypointIndex < 0) {
            waypointIndex = 1;
            direction = 1;
        }
    }

    private void checkForNearbyPlayers(long now) {
        if (golems.isEmpty()) return;
        // Si todavia se esta soltando la oleada anterior (de 2 en 2), no encolar otra.
        if (!pendingDefenders.isEmpty()) return;
        if (defenders.size() >= defenderMaxTotal) return;
        if (now - lastDefenderWaveMillis < defenderWaveIntervalMillis) return;

        Location center = convoyPosition;
        double radiusSq = defenderTriggerRadius * defenderTriggerRadius;
        boolean playerNear = false;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= radiusSq) {
                playerNear = true;
                break;
            }
        }
        if (!playerNear) return;

        enqueueDefenderWave();
        lastDefenderWaveMillis = now;
        if (!pendingDefenders.isEmpty()) {
            Bukkit.broadcastMessage(messageDefendersSpawned);
            if (soundsEnabled) {
                playGlobalSound(Sound.ENTITY_RAVAGER_ROAR, 1.0f, 1.0f);
            }
        }
    }

    private void enqueueDefenderWave() {
        // Oleada escalada por cuantos jugadores hay realmente cerca del convoy:
        // solo = tamano base, grupo = mas escoltas (hasta el multiplicador maximo).
        double mult = 1.0;
        if (scalingEnabled) {
            int near = countPlayersNear(convoyPosition, scalingCountRadius);
            mult = Math.min(defendersMaxMultiplier, 1.0 + Math.max(0, near - 1) * defendersPerExtraPlayer);
        }
        addPending(EntityType.PILLAGER, Material.CROSSBOW, scaled(pillagerCount, mult));
        addPending(EntityType.VINDICATOR, Material.IRON_AXE, scaled(vindicatorCount, mult));
        addPending(EntityType.WITCH, null, scaled(witchCount, mult));
        addPending(EntityType.RAVAGER, null, scaled(ravagerCount, mult));
        addPending(EntityType.EVOKER, null, scaled(evokerCount, mult));
        addPending(EntityType.GHAST, null, scaled(ghastCount, mult));
        addPending(EntityType.BLAZE, null, scaled(blazeCount, mult));
        addPending(EntityType.CREEPER, null, scaled(creeperCount, mult));

        defenderWaveCount++;
        if (waveBossEnabled && defenderWaveCount % waveBossEveryWaves == 0) {
            addPendingBoss();
        }
    }

    private int scaled(int base, double mult) {
        return base <= 0 ? 0 : (int) Math.round(base * mult);
    }

    private int countPlayersNear(Location center, double radius) {
        double radiusSq = radius * radius;
        int n = 0;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= radiusSq) {
                n++;
            }
        }
        return n;
    }

    private void addPending(EntityType type, Material weapon, int count) {
        for (int i = 0; i < count; i++) {
            // Respeta el limite total contando lo ya vivo + lo ya encolado.
            if (defenders.size() + pendingDefenders.size() >= defenderMaxTotal) {
                return;
            }
            pendingDefenders.add(new PendingDefender(type, weapon));
        }
    }

    // Agrega UN Jefe Contrabandista de mas a la oleada (ademas de la composicion normal),
    // cada convoy.defenders.boss.every-waves oleadas. Mismo limite total que el resto.
    private void addPendingBoss() {
        if (defenders.size() + pendingDefenders.size() >= defenderMaxTotal) {
            return;
        }
        PendingDefender boss = new PendingDefender(waveBossEntityType, waveBossWeapon);
        boss.isBoss = true;
        pendingDefenders.add(boss);
    }

    // Suelta a los escoltas de 2 en 2 (defenderSpawnBatchSize): en cada ciclo aparecen los
    // que se marcaron el ciclo anterior con particulas blancas, y se marca el siguiente par.
    private void processDefenderSpawns(long now) {
        if (pendingDefenders.isEmpty()) return;
        if (now - lastDefenderDripMillis < defenderDripIntervalMillis) return;
        lastDefenderDripMillis = now;

        // 1. Aparecen los que ya estaban marcados (con su marcador de particulas visible).
        List<PendingDefender> ready = new ArrayList<>();
        for (PendingDefender pd : pendingDefenders) {
            if (pd.telegraphed) {
                ready.add(pd);
            }
        }
        for (PendingDefender pd : ready) {
            pendingDefenders.remove(pd);
            spawnDefenderAt(pd);
        }

        // 2. Marca el siguiente lote con particulas blancas en el suelo (apareceran al
        //    proximo ciclo, dando el aviso visual "van a salir aqui").
        int marked = 0;
        for (PendingDefender pd : pendingDefenders) {
            if (marked >= defenderSpawnBatchSize) break;
            if (!pd.telegraphed) {
                pd.loc = pickDefenderSpawnLoc();
                pd.telegraphed = true;
                showWhiteSpawnMarker(pd.loc);
                marked++;
            }
        }
    }

    // Ubicacion ALREDEDOR del convoy (nunca en el centro): anillo entre min y max radio.
    // Se ajusta la Y al bloque solido mas alto de esa columna (en vez de copiar la Y del
    // convoy) para que no aparezcan enterrados en terreno irregular y se maten solos.
    private Location pickDefenderSpawnLoc() {
        Location center = convoyPosition;
        if (undergroundMode) {
            return pickLayerSpawnLoc(center, defenderSpawnMinRadius, defenderSpawnMaxRadius);
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double span = Math.max(0.1, defenderSpawnMaxRadius - defenderSpawnMinRadius);
        double dist = defenderSpawnMinRadius + random.nextDouble() * span;
        double x = center.getX() + Math.cos(angle) * dist;
        double z = center.getZ() + Math.sin(angle) * dist;
        World world = center.getWorld();
        int groundY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        return new Location(world, x, groundY + 1, z);
    }

    // Hay techo (solido) por encima de la ubicacion: tunel, cueva, metro, edificio...
    private boolean isRoofed(Location loc) {
        World world = loc.getWorld();
        int highest = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return highest > loc.getBlockY() + 2;
    }

    // Busca, en la columna (x,z), un hueco donde pararse (pies+cabeza libres, suelo solido)
    // cerca de refY, para respetar la misma capa del convoy. null si no hay.
    private Location findStandableNear(World world, double x, double z, int refY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int[] offsets = {0, 1, -1, 2, -2, 3, -3};
        for (int dy : offsets) {
            int y = refY + dy;
            if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 2) continue;
            Block feet = world.getBlockAt(bx, y, bz);
            Block head = world.getBlockAt(bx, y + 1, bz);
            Block below = world.getBlockAt(bx, y - 1, bz);
            if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                    && below.getType().isSolid()) {
                return new Location(world, bx + 0.5, y, bz + 0.5);
            }
        }
        return null;
    }

    // Spawn en la MISMA capa que el convoy (anillo entre min y max radio, probando varios
    // angulos hasta dar con un hueco libre). Si no hay ninguno, usa la posicion del convoy.
    private Location pickLayerSpawnLoc(Location center, double minR, double maxR) {
        World world = center.getWorld();
        double span = Math.max(0.1, maxR - minR);
        for (int i = 0; i < 16; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minR + random.nextDouble() * span;
            Location spot = findStandableNear(world, center.getX() + Math.cos(angle) * dist,
                    center.getZ() + Math.sin(angle) * dist, center.getBlockY());
            if (spot != null) return spot;
        }
        // Tunel estrecho: acercarse progresivamente al convoy.
        for (double d = minR - 1; d >= 1; d -= 1) {
            double angle = random.nextDouble() * Math.PI * 2;
            Location spot = findStandableNear(world, center.getX() + Math.cos(angle) * d,
                    center.getZ() + Math.sin(angle) * d, center.getBlockY());
            if (spot != null) return spot;
        }
        return center.clone();
    }

    private void showWhiteSpawnMarker(Location loc) {
        World world = loc.getWorld();
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 0.1, 0), 30, 0.6, 0.05, 0.6,
                new Particle.DustOptions(Color.WHITE, 1.6f));
        world.spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.6, 0), 14, 0.25, 0.7, 0.25, 0.01);
        if (soundsEnabled) {
            world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.7f);
        }
    }

    private void spawnDefenderAt(PendingDefender pd) {
        Location loc = pd.loc != null ? pd.loc : pickDefenderSpawnLoc();
        World world = loc.getWorld();
        Location spawnLoc = loc.clone();
        if (pd.type == EntityType.GHAST && !undergroundMode) {
            spawnLoc.add(0, 4, 0);
        }
        LivingEntity entity = (LivingEntity) world.spawnEntity(spawnLoc, pd.type);
        entity.setRemoveWhenFarAway(true);
        tagEntity(entity);
        if (pd.weapon != null) {
            EntityEquipment eq = entity.getEquipment();
            if (eq != null) eq.setItemInMainHand(new ItemStack(pd.weapon));
        }
        if (pd.isBoss) {
            entity.setCustomName(waveBossDisplayName);
            entity.setCustomNameVisible(true);
            entity.setGlowing(true);
            var maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) {
                double bossHealth = Math.min(MAX_VANILLA_HEALTH, maxHealthAttr.getBaseValue() * waveBossHealthMultiplier);
                maxHealthAttr.setBaseValue(bossHealth);
                entity.setHealth(bossHealth);
            }
            waveBossIds.add(entity.getUniqueId());
        }
        defenders.add(entity);
        defenderIds.add(entity.getUniqueId());
        if (allyTeam != null) {
            allyTeam.addEntity(entity);
        }
        Player target = nearestPlayer(loc);
        if (target != null && entity instanceof Mob mob) {
            mob.setTarget(target);
        }
        // Estallido blanco al aparecer, para rematar el efecto del marcador.
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 0.6, 0), 20, 0.4, 0.7, 0.4,
                new Particle.DustOptions(Color.WHITE, 1.4f));
    }

    private Player nearestPlayer(Location center) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : center.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(center);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private void removeStragglerDefenders() {
        if (defenders.isEmpty() || defenderMaxDistance <= 0) return;
        double maxDistSq = defenderMaxDistance * defenderMaxDistance;
        List<LivingEntity> snapshot = new ArrayList<>(defenders);
        for (LivingEntity e : snapshot) {
            if (!e.isValid() || e.isDead()) continue;
            if (!e.getWorld().equals(convoyPosition.getWorld())) continue;
            if (e.getLocation().distanceSquared(convoyPosition) > maxDistSq) {
                e.setHealth(0);
            }
        }
    }

    private void onTimeout() {
        // Nadie conectado: el convoy espera congelado en vez de "escaparse" sin publico.
        if (!golems.isEmpty() && Bukkit.getOnlinePlayers().isEmpty()) {
            timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, this::onTimeout, noKillTimeoutMinutes * 60L * 20L);
            return;
        }
        if (!golems.isEmpty()) {
            Bukkit.broadcastMessage(messageEscaped);
            if (soundsEnabled) {
                playGlobalSound(Sound.ENTITY_VILLAGER_NO, 1.0f, 0.7f);
            }
        }
        cancelTasks();
        removeAllEntities();
        scheduleNext();
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (golemIds.contains(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bossBar != null) {
            bossBar.addPlayer(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAllyDamage(EntityDamageByEntityEvent event) {
        if (golems.isEmpty() && defenders.isEmpty() && waveMobs.isEmpty()) {
            return;
        }
        UUID victimId = event.getEntity().getUniqueId();
        boolean victimIsAlly = golemIds.contains(victimId) || defenderIds.contains(victimId)
                || waveMobIds.contains(victimId);
        if (!victimIsAlly) {
            return;
        }
        UUID attackerId = resolveAllyAttackerId(event.getDamager());
        if (attackerId == null) {
            return;
        }
        boolean attackerIsAlly = golemIds.contains(attackerId) || defenderIds.contains(attackerId)
                || waveMobIds.contains(attackerId);
        if (attackerIsAlly) {
            event.setCancelled(true);
        }
    }

    // Los creepers de la escolta siguen dañando jugadores al explotar, pero no rompen
    // terreno/construcciones: consistente con que el resto del evento (golems, airdrop)
    // ya evita destruir el mundo por accidente.
    @EventHandler(ignoreCancelled = true)
    public void onDefenderCreeperExplode(EntityExplodeEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (defenderIds.contains(id) || waveMobIds.contains(id)) {
            event.blockList().clear();
        }
    }

    // Registra el dano de cada jugador a cada golem (en MONITOR: solo cuenta el dano que de
    // verdad se aplico) para repartir recompensas por aporte y no por el ultimo golpe.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolemDamageRecord(EntityDamageByEntityEvent event) {
        if (!damageRewardsEnabled || golems.isEmpty()) {
            return;
        }
        UUID victimId = event.getEntity().getUniqueId();
        if (!golemIds.contains(victimId)) {
            return;
        }
        Player attacker = resolvePlayerAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        golemDamage.computeIfAbsent(victimId, k -> new HashMap<>())
                .merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    // Cualquier golpe real (de jugador, proyectil, explosion, etc - no solo de otro
    // jugador) reinicia el reloj de regeneracion: los 10s sin golpes vuelven a empezar.
    // EntityDamageEvent es la clase base, asi que cubre EntityDamageByEntityEvent tambien.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolemAnyDamage(EntityDamageEvent event) {
        if (golemIds.isEmpty()) {
            return;
        }
        UUID id = event.getEntity().getUniqueId();
        if (golemIds.contains(id)) {
            golemLastEventMillis.put(id, System.currentTimeMillis());
        }
    }

    private Player resolvePlayerAttacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        // Cubre flechas, balas de QualityArmory (proyectiles con shooter jugador), tridentes...
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    private UUID resolveAllyAttackerId(org.bukkit.entity.Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity livingShooter) {
                return livingShooter.getUniqueId();
            }
            return null;
        }
        if (damager instanceof EvokerFangs fangs) {
            LivingEntity owner = fangs.getOwner();
            return owner != null ? owner.getUniqueId() : null;
        }
        return damager.getUniqueId();
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();

        if (waveMobIds.remove(id)) {
            waveMobs.removeIf(e -> e.getUniqueId().equals(id));
            // Municion SOLO de pillagers (armas SOLO de golems, ver mas abajo).
            if (event.getEntity().getType() == EntityType.PILLAGER) {
                rollItemDrops(event.getEntity().getLocation(), ammoDropsEnabled, ammoDropEntries);
            }
            return;
        }

        if (defenderIds.contains(id)) {
            defenderIds.remove(id);
            defenders.removeIf(e -> e.getUniqueId().equals(id));
            if (event.getEntity().getType() == EntityType.PILLAGER) {
                rollItemDrops(event.getEntity().getLocation(), ammoDropsEnabled, ammoDropEntries);
            }
            if (waveBossIds.remove(id)) {
                Player bossKiller = event.getEntity().getKiller();
                if (bossKiller != null) {
                    if (economy != null) {
                        double bonus = (waveBossMoneyBonusMin
                                + random.nextDouble() * (waveBossMoneyBonusMax - waveBossMoneyBonusMin))
                                * currentRewardMultiplier;
                        economy.depositPlayer(bossKiller, bonus);
                    }
                    giveItemRewards(bossKiller, itemRewardEntries, currentRewardMultiplier);
                    Bukkit.broadcastMessage(messageWaveBossKilled.replace("%killer%", bossKiller.getName()));
                    if (soundsEnabled) {
                        playGlobalSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.8f);
                    }
                }
            }
            return;
        }

        if (!golemIds.contains(id)) {
            return;
        }
        golemIds.remove(id);
        golems.removeIf(e -> e.getUniqueId().equals(id));
        lastShownHealth.remove(id);
        golemLastEventMillis.remove(id);
        golemStuckCheckPos.remove(id);
        golemStuckCycles.remove(id);
        TextDisplay display = healthDisplays.remove(id);
        if (display != null && display.isValid()) {
            display.remove();
        }
        // Armas de QualityArmory SOLO de los golems del cargamento: con hasta 40 escoltas
        // vivas a la vez, dejar que cualquiera dropeara armas inundaba el suelo de items.
        rollItemDrops(event.getEntity().getLocation(), golemWeaponDropsEnabled, golemWeaponDropEntries);

        Map<UUID, Double> contributions = golemDamage.remove(id);
        Player killer = event.getEntity().getKiller();
        Player announced = killer;
        if (damageRewardsEnabled && contributions != null && !contributions.isEmpty()) {
            announced = distributeDamageRewards(contributions, killer);
        } else if (killer != null) {
            giveRewards(killer);
        }
        if (announced != null) {
            Bukkit.broadcastMessage(messageGolemKilled
                    .replace("%killer%", announced.getName())
                    .replace("%remaining%", String.valueOf(golems.size())));
        }

        if (golems.isEmpty()) {
            Bukkit.broadcastMessage(messageAllDestroyed);
            if (soundsEnabled) {
                playGlobalSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
            Location dropCenter = convoyPosition != null ? convoyPosition.clone() : event.getEntity().getLocation();
            cancelTasks();
            removeAllEntities();
            scheduleNext();
            if (airdropEnabled) {
                spawnAirdrop(dropCenter);
            }
        }
    }

    private void giveRewards(Player killer) {
        if (economy != null) {
            double amount = (moneyMin + random.nextDouble() * (moneyMax - moneyMin)) * currentRewardMultiplier;
            economy.depositPlayer(killer, amount);
        }
        for (CommandReward cr : commandRewards) {
            if (random.nextDouble() <= cr.chance) {
                String cmd = cr.command.replace("%player%", killer.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
        giveItemRewards(killer, itemRewardEntries, currentRewardMultiplier);
    }

    // Reparte por aporte de dano: el dinero se divide proporcionalmente entre todos los que
    // golpearon al golem (y siguen conectados); los items y comandos van completos al que
    // mas dano hizo. Devuelve al "protagonista" para el anuncio en el chat.
    private Player distributeDamageRewards(Map<UUID, Double> contributions, Player fallback) {
        double total = 0;
        for (double d : contributions.values()) {
            total += d;
        }
        if (total <= 0) {
            if (fallback != null) {
                giveRewards(fallback);
            }
            return fallback;
        }
        double moneyRoll = (moneyMin + random.nextDouble() * (moneyMax - moneyMin)) * currentRewardMultiplier;
        Player top = null;
        double topDamage = -1;
        for (Map.Entry<UUID, Double> entry : contributions.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (economy != null) {
                economy.depositPlayer(p, moneyRoll * (entry.getValue() / total));
            }
            if (entry.getValue() > topDamage) {
                topDamage = entry.getValue();
                top = p;
            }
        }
        if (top == null) {
            top = fallback;
        }
        if (top != null) {
            for (CommandReward cr : commandRewards) {
                if (random.nextDouble() <= cr.chance) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cr.command.replace("%player%", top.getName()));
                }
            }
            giveItemRewards(top, itemRewardEntries, currentRewardMultiplier);
        }
        return top;
    }

    // Suelta armas/municion de QualityArmory (via su API, no comandos) donde murio el
    // escolta. Solo si hay un jugador cerca: evita regar loot cuando mueren solos (por
    // ejemplo el auto-kill de lejania).
    // where puede ser un golem (armas) o un pillager (municion) segun quien haya muerto;
    // el llamador ya filtro cual lista corresponde.
    private void rollItemDrops(Location where, boolean enabled, List<WeaponDropEntry> entries) {
        if (!enabled || !qaAvailable || entries.isEmpty()) {
            return;
        }
        if (countPlayersNear(where, 24) == 0) {
            return;
        }
        for (WeaponDropEntry entry : entries) {
            if (random.nextDouble() > entry.chance) {
                continue;
            }
            ItemStack stack;
            try {
                stack = QualityArmoryHook.getItem(entry.item);
            } catch (Throwable t) {
                plugin.getLogger().warning("item-drops: error obteniendo '" + entry.item + "' de QualityArmory: " + t.getMessage());
                continue;
            }
            if (stack == null) {
                continue;
            }
            int amount = entry.minAmount + random.nextInt(Math.max(1, entry.maxAmount - entry.minAmount + 1));
            stack.setAmount(Math.max(1, amount));
            where.getWorld().dropItemNaturally(where, stack);
        }
    }

    // /convoy testdrop: dropea una unidad de cada entrada configurada (armas de golem +
    // municion de pillager) a los pies del admin, para verificar el sistema sin esperar
    // un evento.
    public void testWeaponDrops(Player p) {
        qaAvailable = Bukkit.getPluginManager().getPlugin("QualityArmory") != null;
        if (!qaAvailable) {
            p.sendMessage(ChatColor.RED + "QualityArmory no esta instalado: no hay drops de items.");
            return;
        }
        if (golemWeaponDropEntries.isEmpty() && ammoDropEntries.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "No hay entradas en convoy.golem-weapon-drops ni convoy.ammo-drops en el config.");
            return;
        }
        p.sendMessage(ChatColor.GOLD + "-- Armas (solo dropean golems) --");
        testWeaponDropList(p, golemWeaponDropEntries);
        p.sendMessage(ChatColor.GOLD + "-- Municion (solo dropea de pillagers) --");
        testWeaponDropList(p, ammoDropEntries);
    }

    private void testWeaponDropList(Player p, List<WeaponDropEntry> entries) {
        for (WeaponDropEntry entry : entries) {
            ItemStack stack = null;
            try {
                stack = QualityArmoryHook.getItem(entry.item);
            } catch (Throwable ignored) {
            }
            if (stack == null) {
                p.sendMessage(ChatColor.RED + " X '" + entry.item
                        + "' NO existe en QualityArmory (usa el campo name: del yml, ej: ak47, 9mm, shell).");
                continue;
            }
            stack.setAmount(Math.max(1, entry.minAmount));
            p.getWorld().dropItemNaturally(p.getLocation(), stack);
            p.sendMessage(ChatColor.GREEN + " OK '" + entry.item + "' (dropeado a tus pies, chance " + entry.chance + ").");
        }
    }

    private void giveItemRewards(Player player, List<ItemLootEntry> entries, double amountMultiplier) {
        for (ItemLootEntry entry : entries) {
            if (random.nextDouble() <= entry.chance) {
                int amount = entry.minAmount + random.nextInt(Math.max(1, entry.maxAmount - entry.minAmount + 1));
                if (amountMultiplier != 1.0) {
                    amount = Math.max(1, (int) Math.round(amount * amountMultiplier));
                }
                // addItem parte cantidades mayores al stack en varios stacks automaticamente.
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(buildLootItem(entry, amount));
                for (ItemStack extra : leftover.values()) {
                    player.getWorld().dropItem(player.getLocation(), extra);
                }
            }
        }
    }

    // --- Brujula rastreadora ---

    // Da una brujula que apunta al convoy (lodestone sin bloque). Se actualiza sola cada
    // tracker.update-seconds y desaparece del inventario cuando el convoy termina.
    public boolean giveTracker(Player p) {
        if (!trackerEnabled || golems.isEmpty() || convoyPosition == null) {
            return false;
        }
        for (ItemStack item : p.getInventory().getContents()) {
            if (isTrackerItem(item)) {
                return true; // ya tiene uno; no duplicar
            }
        }
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.setDisplayName(trackerItemName);
        meta.setLodestoneTracked(false);
        meta.setLodestone(convoyPosition);
        meta.getPersistentDataContainer().set(trackerTag, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(compass);
        for (ItemStack extra : leftover.values()) {
            p.getWorld().dropItem(p.getLocation(), extra);
        }
        return true;
    }

    private boolean isTrackerItem(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(trackerTag, PersistentDataType.BYTE);
    }

    private void updateTrackerCompasses() {
        if (convoyPosition == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : p.getInventory().getContents()) {
                if (isTrackerItem(item)) {
                    CompassMeta meta = (CompassMeta) item.getItemMeta();
                    meta.setLodestoneTracked(false);
                    meta.setLodestone(convoyPosition);
                    item.setItemMeta(meta);
                }
            }
        }
    }

    private void removeTrackerCompasses() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                if (isTrackerItem(contents[i])) {
                    p.getInventory().setItem(i, null);
                }
            }
        }
    }

    // --- Limpieza de huerfanos (crash/reinicio a mitad de evento) ---

    private void tagEntity(Entity e) {
        e.getPersistentDataContainer().set(entityTag, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isTagged(Entity e) {
        return e.getPersistentDataContainer().has(entityTag, PersistentDataType.BYTE);
    }

    private Set<UUID> buildTrackedIds() {
        Set<UUID> tracked = new HashSet<>(golemIds);
        tracked.addAll(defenderIds);
        tracked.addAll(waveMobIds);
        for (TextDisplay d : healthDisplays.values()) {
            tracked.add(d.getUniqueId());
        }
        for (Airdrop a : descendingAirdrops) {
            if (a.fallingDisplay != null) tracked.add(a.fallingDisplay.getUniqueId());
        }
        for (Airdrop a : activeAirdrops) {
            if (a.fallingDisplay != null) tracked.add(a.fallingDisplay.getUniqueId());
            if (a.countdown != null) tracked.add(a.countdown.getUniqueId());
        }
        return tracked;
    }

    // Barre los mundos cargados y elimina toda entidad del evento que no pertenezca al
    // evento ACTUAL (huerfanos de crashes/reinicios), mas los cofres de airdrop guardados
    // en data.yml y los equipos de scoreboard rancios. Devuelve cuantas cosas elimino.
    public int cleanupOrphans() {
        Set<UUID> tracked = buildTrackedIds();
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (tracked.contains(e.getUniqueId())) {
                    continue;
                }
                if (isTagged(e) || isLegacyConvoyEntity(e)) {
                    e.remove();
                    removed++;
                }
            }
        }
        removed += cleanupStoredChests();
        cleanupStaleTeams();
        return removed;
    }

    // Reconoce entidades de versiones anteriores del plugin (sin etiqueta persistente):
    // por nombre del golem, por membresia en los equipos del evento, o por el texto
    // caracteristico de los hologramas.
    private boolean isLegacyConvoyEntity(Entity e) {
        EntityType type = e.getType();
        if (type == EntityType.IRON_GOLEM && displayName.equals(e.getCustomName())) {
            return true;
        }
        if (e instanceof TextDisplay td && td.text() instanceof TextComponent tc) {
            String content = tc.content();
            if (content.startsWith("❤") || content.contains("Se desbloquea en:") || content.contains("Desbloqueado")) {
                return true;
            }
        }
        if (type == EntityType.IRON_GOLEM || type == EntityType.BLOCK_DISPLAY
                || type == EntityType.PILLAGER || type == EntityType.VINDICATOR || type == EntityType.WITCH
                || type == EntityType.RAVAGER || type == EntityType.EVOKER
                || type == EntityType.GHAST || type == EntityType.BLAZE) {
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(e.getUniqueId().toString());
            return team != null
                    && (ALLY_TEAM_NAME.equals(team.getName()) || AIRDROP_GLOW_TEAM_NAME.equals(team.getName()));
        }
        return false;
    }

    private void cleanupStaleTeams() {
        var scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (allyTeam == null) {
            Team t = scoreboard.getTeam(ALLY_TEAM_NAME);
            if (t != null) t.unregister();
        }
        if (airdropGlowTeam == null) {
            Team t = scoreboard.getTeam(AIRDROP_GLOW_TEAM_NAME);
            if (t != null) t.unregister();
        }
    }

    // Al cargar chunks nuevos, elimina cualquier entidad huerfana del evento que estuviera
    // en zona descargada durante la limpieza inicial. isLegacyConvoyEntity (sin tag) solo
    // puede encontrar algo real durante los primeros minutos tras actualizar el plugin: de
    // ahi en adelante esta puramente heredando costo (un lookup de scoreboard por cada
    // pillager/golem/witch/etc que spawnee NATURALMENTE en cualquier chunk, para siempre).
    // Por eso se apaga sola pasado LEGACY_SCAN_WINDOW_MILLIS y solo queda el chequeo isTagged
    // (gratis: un solo PDC.has()), que es el que de verdad importa en steady-state.
    private static final long LEGACY_SCAN_WINDOW_MILLIS = 30 * 60 * 1000L;
    private long legacyScanExpiresAtMillis;

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        boolean checkLegacy = System.currentTimeMillis() < legacyScanExpiresAtMillis;
        Set<UUID> tracked = null;
        for (Entity e : event.getEntities()) {
            if (!isTagged(e) && !(checkLegacy && isLegacyConvoyEntity(e))) {
                continue;
            }
            if (tracked == null) {
                tracked = buildTrackedIds();
            }
            if (!tracked.contains(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    // --- Persistencia de cofres de airdrop (data.yml) ---

    private List<String> loadStoredChests() {
        if (!dataFile.exists()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(YamlConfiguration.loadConfiguration(dataFile).getStringList("airdrop-chests"));
    }

    private void saveStoredChests(List<String> list) {
        YamlConfiguration data = new YamlConfiguration();
        data.set("airdrop-chests", list);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar data.yml: " + e.getMessage());
        }
    }

    private String serializeBlockLoc(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private void rememberAirdropChest(Location loc) {
        List<String> list = loadStoredChests();
        String s = serializeBlockLoc(loc);
        if (!list.contains(s)) {
            list.add(s);
            saveStoredChests(list);
        }
    }

    private void forgetAirdropChest(Location loc) {
        List<String> list = loadStoredChests();
        if (list.remove(serializeBlockLoc(loc))) {
            saveStoredChests(list);
        }
    }

    private int cleanupStoredChests() {
        List<String> stored = loadStoredChests();
        if (stored.isEmpty()) {
            return 0;
        }
        Set<String> live = new HashSet<>();
        for (Airdrop a : activeAirdrops) {
            if (a.blockLocation != null) {
                live.add(serializeBlockLoc(a.blockLocation));
            }
        }
        int removed = 0;
        List<String> remaining = new ArrayList<>();
        for (String s : stored) {
            if (live.contains(s)) {
                remaining.add(s); // airdrop activo: no tocar
                continue;
            }
            Location loc = parseLocation(s);
            if (loc != null && loc.getBlock().getType() == Material.CHEST) {
                Block block = loc.getBlock();
                if (block.getState() instanceof Chest chestState) {
                    chestState.getBlockInventory().clear();
                }
                block.setType(Material.AIR);
                removed++;
            }
        }
        saveStoredChests(remaining);
        return removed;
    }

    // --- Airdrop ---

    private void ensureAirdropGlowTeam() {
        if (airdropGlowTeam != null) {
            return;
        }
        var scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team existing = scoreboard.getTeam(AIRDROP_GLOW_TEAM_NAME);
        airdropGlowTeam = existing != null ? existing : scoreboard.registerNewTeam(AIRDROP_GLOW_TEAM_NAME);
        airdropGlowTeam.setColor(ChatColor.YELLOW);
    }

    // Airdrop "normal": sin caida del cielo, el cofre aparece donde murio el convoy (o en el
    // hueco libre mas cercano de esa misma capa). Se usa en modo subterraneo o con techo encima.
    private void spawnGroundAirdrop(Location hint) {
        World world = hint.getWorld();
        Location spot = findStandableNear(world, hint.getBlockX() + 0.5, hint.getBlockZ() + 0.5, hint.getBlockY());
        for (int r = 1; spot == null && r <= 4; r++) {
            for (int dx = -r; dx <= r && spot == null; dx++) {
                for (int dz = -r; dz <= r && spot == null; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    spot = findStandableNear(world, hint.getBlockX() + dx + 0.5, hint.getBlockZ() + dz + 0.5, hint.getBlockY());
                }
            }
        }
        if (spot == null) {
            spot = hint.clone();
        }
        double fixedX = spot.getBlockX();
        double fixedZ = spot.getBlockZ();
        double y = spot.getBlockY();

        ensureAirdropGlowTeam();
        BlockDisplay display = (BlockDisplay) world.spawnEntity(new Location(world, fixedX, y, fixedZ), EntityType.BLOCK_DISPLAY);
        display.setBlock(Material.CHEST.createBlockData());
        display.setGlowing(true);
        tagEntity(display);
        airdropGlowTeam.addEntity(display);

        Airdrop airdrop = new Airdrop();
        airdrop.fallingDisplay = display;
        airdrop.world = world;
        airdrop.fixedX = fixedX;
        airdrop.fixedZ = fixedZ;
        airdrop.startY = y;
        airdrop.targetY = y;
        landAirdrop(airdrop);
    }

    private void spawnAirdrop(Location groundHint) {
        if (undergroundMode || (airdropGroundIfRoofed && isRoofed(groundHint))) {
            spawnGroundAirdrop(groundHint);
            return;
        }
        World world = groundHint.getWorld();
        int groundY = world.getHighestBlockYAt(groundHint.getBlockX(), groundHint.getBlockZ());
        // Coordenadas enteras (no +0.5) para que el BlockDisplay quede alineado exactamente
        // con la misma celda de bloque donde va a aterrizar el cofre real.
        double fixedX = groundHint.getBlockX();
        double fixedZ = groundHint.getBlockZ();
        // targetY es groundY + 1 (ENCIMA del bloque mas alto), no groundY: si no, el cofre
        // reemplaza/rompe la superficie donde aterriza.
        double targetY = groundY + 1;
        double startY = targetY + airdropFallHeight;

        ensureAirdropGlowTeam();
        BlockDisplay display = (BlockDisplay) world.spawnEntity(
                new Location(world, fixedX, startY, fixedZ), EntityType.BLOCK_DISPLAY);
        display.setBlock(Material.CHEST.createBlockData());
        display.setGlowing(true);
        // El cliente interpola el movimiento entre teleports: descenso fluido con menos paquetes.
        display.setTeleportDuration(DESCENT_TICK_INTERVAL);
        tagEntity(display);
        airdropGlowTeam.addEntity(display);

        Airdrop airdrop = new Airdrop();
        airdrop.fallingDisplay = display;
        airdrop.world = world;
        airdrop.fixedX = fixedX;
        airdrop.fixedZ = fixedZ;
        airdrop.startY = startY;
        airdrop.targetY = targetY;
        airdrop.descentStartMillis = System.currentTimeMillis();
        airdrop.descentDurationMillis = Math.max(1, airdropFallDurationSeconds) * 1000L;

        descendingAirdrops.add(airdrop);

        Bukkit.broadcastMessage(messageAirdropIncoming);
        if (soundsEnabled) {
            playGlobalSound(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.6f);
        }
        ensureDescentTicker();
    }

    public void testAirdrop(Location location) {
        spawnAirdrop(location);
    }

    private void ensureDescentTicker() {
        if (descentTickTask != null) {
            return;
        }
        descentTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickDescendingAirdrops, DESCENT_TICK_INTERVAL, DESCENT_TICK_INTERVAL);
    }

    private void tickDescendingAirdrops() {
        long now = System.currentTimeMillis();
        Iterator<Airdrop> it = descendingAirdrops.iterator();
        while (it.hasNext()) {
            Airdrop a = it.next();
            double progress = Math.min(1.0, (now - a.descentStartMillis) / (double) a.descentDurationMillis);
            double y = a.startY - (a.startY - a.targetY) * progress;
            Location current = new Location(a.world, a.fixedX, y, a.fixedZ);
            if (a.fallingDisplay != null && a.fallingDisplay.isValid()) {
                a.fallingDisplay.teleport(current);
            }
            if (particlesEnabled) {
                a.world.spawnParticle(Particle.FLAME, current.clone().add(0, 0.4, 0), 6, 0.2, 0.5, 0.2, 0.01);
                a.world.spawnParticle(Particle.CLOUD, current.clone().add(0, -0.3, 0), 4, 0.15, 0.3, 0.15, 0.01);
            }
            if (progress >= 1.0) {
                it.remove();
                landAirdrop(a);
            }
        }
        if (descendingAirdrops.isEmpty() && descentTickTask != null) {
            descentTickTask.cancel();
            descentTickTask = null;
        }
    }

    private void landAirdrop(Airdrop a) {
        Block block = new Location(a.world, a.fixedX, a.targetY, a.fixedZ).getBlock();
        block.setType(Material.CHEST);
        a.blockLocation = block.getLocation();

        // El BlockDisplay se queda (con glow) pegado exactamente sobre el cofre real, en vez
        // de eliminarse, para que el efecto de brillo se siga viendo despues de aterrizar.
        if (a.fallingDisplay != null && a.fallingDisplay.isValid()) {
            a.fallingDisplay.teleport(a.blockLocation.clone());
        }

        long now = System.currentTimeMillis();
        a.unlockAtMillis = now + airdropUnlockMinutes * 60_000L;
        a.expireAtMillis = a.unlockAtMillis + airdropExpireMinutesAfterUnlock * 60_000L;
        a.lastWaveMillis = now;
        activeAirdrops.add(a);
        // Guardado en data.yml: si el server crashea con el airdrop activo, la limpieza
        // inicial del proximo arranque quita este cofre en vez de dejarlo tirado.
        rememberAirdropChest(a.blockLocation);

        fillAirdropChest(a);

        Bukkit.broadcastMessage(messageAirdropLanded
                .replace("%minutes%", String.valueOf(airdropUnlockMinutes))
                .replace("%coords%", formatCoords(a.blockLocation)));
        if (soundsEnabled) {
            playGlobalSound(Sound.BLOCK_ANVIL_LAND, 1.0f, 0.8f);
        }

        TextDisplay countdown = (TextDisplay) a.world.spawnEntity(
                block.getLocation().clone().add(0.5, 1.8, 0.5), EntityType.TEXT_DISPLAY);
        countdown.setBillboard(Display.Billboard.CENTER);
        countdown.setShadowed(true);
        countdown.setPersistent(true);
        tagEntity(countdown);
        a.countdown = countdown;

        ensureAirdropTicker();
    }

    // El cofre visible en el mundo (bloque CHEST) es solo decorativo: el loot real vive en
    // este inventario VIRTUAL de 54 casillas (6 filas: el maximo que permite Bukkit para un
    // inventario custom, igual que un cofre doble). Se abre a mano en onAirdropInteract.
    private void fillAirdropChest(Airdrop airdrop) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Cargamento del Airdrop"));
        for (ItemLootEntry entry : airdropLootEntries) {
            if (random.nextDouble() <= entry.chance) {
                int amount = entry.minAmount + random.nextInt(Math.max(1, entry.maxAmount - entry.minAmount + 1));
                inv.addItem(buildLootItem(entry, amount));
            }
        }
        if (smartSpawnerDropEnabled && smartSpawnerAvailable && random.nextDouble() <= smartSpawnerDropChance) {
            int amount = smartSpawnerDropMinAmount
                    + random.nextInt(Math.max(1, smartSpawnerDropMaxAmount - smartSpawnerDropMinAmount + 1));
            ItemStack spawner = SmartSpawnersHook.randomSpawnerItem(random);
            if (spawner != null) {
                spawner.setAmount(Math.max(1, amount));
                inv.addItem(spawner);
            }
        }
        airdrop.virtualInventory = inv;
    }

    private void ensureAirdropTicker() {
        if (airdropTickTask != null) {
            return;
        }
        airdropTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAirdrops, AIRDROP_TICK_INTERVAL, AIRDROP_TICK_INTERVAL);
    }

    private void tickAirdrops() {
        long now = System.currentTimeMillis();
        purgeInvalidWaveMobs();
        Iterator<Airdrop> it = activeAirdrops.iterator();
        while (it.hasNext()) {
            Airdrop a = it.next();
            if (now >= a.expireAtMillis) {
                removeAirdropDisplays(a);
                clearAndRemoveChest(a);
                forgetAirdropChest(a.blockLocation);
                it.remove();
                continue;
            }
            // Si el cofre ya no existe (roto tras desbloquearse, explosion, etc), limpiar
            // los hologramas y volcar el loot virtual al suelo para que no se pierda.
            if (a.blockLocation.getBlock().getType() != Material.CHEST) {
                removeAirdropDisplays(a);
                dropVirtualInventory(a);
                forgetAirdropChest(a.blockLocation);
                it.remove();
                continue;
            }
            // Ya lo abrieron: pasado el tiempo configurado, desaparece - pero SOLO si ya lo
            // vaciaron. Si lo abrieron y dejaron items sin sacar, NO se destruye: se sigue
            // revisando cada tick hasta que este vacio (el limite absoluto de mas arriba,
            // expire-minutes-after-unlock, lo termina limpiando igual si nunca lo vacian).
            if (a.openedAtMillis > 0 && now - a.openedAtMillis >= airdropRemoveMillisAfterOpen
                    && (a.virtualInventory == null || a.virtualInventory.isEmpty())) {
                removeAirdropDisplays(a);
                clearAndRemoveChest(a);
                forgetAirdropChest(a.blockLocation);
                it.remove();
                continue;
            }
            if (!a.unlockedAnnounced && now >= a.unlockAtMillis) {
                a.unlockedAnnounced = true;
                Bukkit.broadcastMessage(messageAirdropUnlocked);
                if (soundsEnabled) {
                    playGlobalSound(Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                }
                // El texto "Desbloqueado" se fija una sola vez aqui, en vez de reenviarlo
                // identico cada segundo.
                if (a.countdown != null && a.countdown.isValid()) {
                    a.countdown.text(Component.text(ChatColor.GREEN + "" + ChatColor.BOLD + "Desbloqueado"));
                }
            }
            if (airdropWaveEnabled && now < a.unlockAtMillis && now - a.lastWaveMillis >= airdropWaveIntervalMillis) {
                spawnAirdropWave(a);
                a.lastWaveMillis = now;
            }
            // Suelta (telegrafiados de a poco, mismo efecto que la escolta del convoy) los
            // contrabandistas ya encolados por spawnAirdropWave, sin importar si esta oleada
            // fue la que se acaba de encolar o una anterior que todavia se estaba goteando.
            processAirdropWaveSpawns(a, now);
            if (now < a.unlockAtMillis && a.countdown != null && a.countdown.isValid()) {
                a.countdown.text(Component.text(formatCountdownColored(a.unlockAtMillis - now)));
            }
        }
        if (activeAirdrops.isEmpty() && descendingAirdrops.isEmpty()) {
            // Ya no queda ningun airdrop: retirar tambien a los contrabandistas que sigan vivos.
            for (LivingEntity e : waveMobs) {
                if (e.isValid()) e.remove();
            }
            waveMobs.clear();
            waveMobIds.clear();
            if (airdropTickTask != null) {
                airdropTickTask.cancel();
                airdropTickTask = null;
            }
        }
    }

    private void purgeInvalidWaveMobs() {
        if (waveMobs.isEmpty()) {
            return;
        }
        waveMobs.removeIf(e -> {
            if (e.isValid() && !e.isDead()) {
                return false;
            }
            waveMobIds.remove(e.getUniqueId());
            return true;
        });
    }

    private void removeAirdropDisplays(Airdrop a) {
        if (a.countdown != null && a.countdown.isValid()) {
            a.countdown.remove();
        }
        if (a.fallingDisplay != null && a.fallingDisplay.isValid()) {
            a.fallingDisplay.remove();
        }
    }

    private void clearAndRemoveChest(Airdrop a) {
        if (a.virtualInventory != null) {
            for (HumanEntity viewer : new ArrayList<>(a.virtualInventory.getViewers())) {
                viewer.closeInventory();
            }
            a.virtualInventory.clear();
        }
        a.blockLocation.getBlock().setType(Material.AIR);
    }

    // Al romperse el cofre por explosion u otro medio (no por /convoy break normal, que ya
    // pasa por onAirdropBreak), el loot virtual no cae solo como el de un cofre real: hay
    // que volcarlo a mano para que no desaparezca sin razon.
    private void dropVirtualInventory(Airdrop a) {
        if (a.virtualInventory == null) return;
        for (HumanEntity viewer : new ArrayList<>(a.virtualInventory.getViewers())) {
            viewer.closeInventory();
        }
        Location dropAt = a.blockLocation.clone().add(0.5, 0.5, 0.5);
        for (ItemStack item : a.virtualInventory.getContents()) {
            if (item != null) {
                dropAt.getWorld().dropItemNaturally(dropAt, item);
            }
        }
        a.virtualInventory.clear();
    }

    // Encola (NO aparecen todavia) los contrabandistas de esta oleada de recuperacion.
    // processAirdropWaveSpawns() los va soltando de a lotes con el mismo aviso de
    // particulas blancas que usa la escolta armada del convoy.
    private void spawnAirdropWave(Airdrop a) {
        addPendingWaveMob(a, EntityType.PILLAGER, Material.CROSSBOW, airdropWavePillagers);
        addPendingWaveMob(a, EntityType.VINDICATOR, Material.IRON_AXE, airdropWaveVindicators);
        addPendingWaveMob(a, EntityType.WITCH, null, airdropWaveWitches);
        addPendingWaveMob(a, EntityType.RAVAGER, null, airdropWaveRavagers);
        addPendingWaveMob(a, EntityType.EVOKER, null, airdropWaveEvokers);
        addPendingWaveMob(a, EntityType.GHAST, null, airdropWaveGhasts);
        addPendingWaveMob(a, EntityType.BLAZE, null, airdropWaveBlazes);

        Bukkit.broadcastMessage(messageAirdropWave);
        if (soundsEnabled) {
            playGlobalSound(Sound.ENTITY_RAVAGER_ROAR, 1.0f, 0.9f);
        }
    }

    private void addPendingWaveMob(Airdrop a, EntityType type, Material weapon, int count) {
        for (int i = 0; i < count; i++) {
            a.pendingWaveMobs.add(new PendingDefender(type, weapon));
        }
    }

    // Mismo patron de goteo que processDefenderSpawns, pero POR AIRDROP: reusa el tamano de
    // lote y el intervalo configurados para la escolta del convoy (convoy.defenders.spawn-
    // batch-*), asi el efecto es identico al de "aparecen los escoltas" sin duplicar config.
    private void processAirdropWaveSpawns(Airdrop a, long now) {
        if (a.pendingWaveMobs.isEmpty()) return;
        if (now - a.lastWaveDripMillis < defenderDripIntervalMillis) return;
        a.lastWaveDripMillis = now;

        List<PendingDefender> ready = new ArrayList<>();
        for (PendingDefender pd : a.pendingWaveMobs) {
            if (pd.telegraphed) {
                ready.add(pd);
            }
        }
        for (PendingDefender pd : ready) {
            a.pendingWaveMobs.remove(pd);
            spawnWaveMobAt(a, pd);
        }

        int marked = 0;
        for (PendingDefender pd : a.pendingWaveMobs) {
            if (marked >= defenderSpawnBatchSize) break;
            if (!pd.telegraphed) {
                pd.loc = pickAirdropWaveSpawnLoc(a, pd.type);
                pd.telegraphed = true;
                showWhiteSpawnMarker(pd.loc);
                marked++;
            }
        }
    }

    private Location pickAirdropWaveSpawnLoc(Airdrop a, EntityType type) {
        Location center = a.blockLocation.clone().add(0.5, 1, 0.5);
        if (undergroundMode || isRoofed(center)) {
            return pickLayerSpawnLoc(center, 3, 6);
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 3 + random.nextDouble() * 3;
        Location loc = center.clone().add(Math.cos(angle) * dist, type == EntityType.GHAST ? 4 : 0, Math.sin(angle) * dist);
        return loc;
    }

    private void spawnWaveMobAt(Airdrop a, PendingDefender pd) {
        Location loc = pd.loc != null ? pd.loc : pickAirdropWaveSpawnLoc(a, pd.type);
        World world = loc.getWorld();
        LivingEntity e = (LivingEntity) world.spawnEntity(loc, pd.type);
        e.setRemoveWhenFarAway(true);
        tagEntity(e);
        waveMobs.add(e);
        waveMobIds.add(e.getUniqueId());
        if (pd.weapon != null) {
            EntityEquipment eq = e.getEquipment();
            if (eq != null) eq.setItemInMainHand(new ItemStack(pd.weapon));
        }
        Player target = nearestPlayer(loc);
        if (target != null && e instanceof Mob mob) {
            mob.setTarget(target);
        }
        // Estallido blanco al aparecer, igual que la escolta del convoy.
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 0.6, 0), 20, 0.4, 0.7, 0.4,
                new Particle.DustOptions(Color.WHITE, 1.4f));
    }

    private String formatCountdownColored(long millisRemaining) {
        // Ultimo minuto: cambia a rojo para avisar que se esta por desbloquear.
        ChatColor urgencyColor = millisRemaining <= 60_000L ? ChatColor.RED : ChatColor.YELLOW;
        return urgencyColor + "" + ChatColor.BOLD + "Se desbloquea en: " + formatTimePlain(millisRemaining);
    }

    private String formatTimePlain(long millisRemaining) {
        long totalSeconds = Math.max(0, millisRemaining / 1000);
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return m + ":" + String.format("%02d", s);
    }

    // El bloque del cofre es solo decorativo: el click derecho SIEMPRE se cancela (para que
    // no abra el inventario real de 27 casillas) y en su lugar se abre a mano el inventario
    // virtual de 54 casillas con el loot de verdad.
    @EventHandler(ignoreCancelled = true)
    public void onAirdropInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.CHEST) {
            return;
        }
        for (Airdrop a : activeAirdrops) {
            if (isSameBlock(a.blockLocation, clicked.getLocation())) {
                event.setCancelled(true);
                long now = System.currentTimeMillis();
                if (now < a.unlockAtMillis) {
                    event.getPlayer().sendMessage(messageAirdropLocked.replace("%time%", formatTimePlain(a.unlockAtMillis - now)));
                    return;
                }
                if (a.virtualInventory != null) {
                    event.getPlayer().openInventory(a.virtualInventory);
                }
                return;
            }
        }
    }

    // Marca el momento en que alguien abre de verdad el cofre del airdrop, para que
    // desaparezca (cofre + glow + holograma) pasado remove-seconds-after-open. Se compara
    // por IDENTIDAD del inventario virtual (ya no tiene location, al no ser un cofre real).
    @EventHandler(ignoreCancelled = true)
    public void onAirdropOpen(InventoryOpenEvent event) {
        if (activeAirdrops.isEmpty()) {
            return;
        }
        for (Airdrop a : activeAirdrops) {
            if (a.openedAtMillis == 0 && event.getInventory().equals(a.virtualInventory)) {
                a.openedAtMillis = System.currentTimeMillis();
                return;
            }
        }
    }

    // Sin esto, un jugador podia romper el cofre bloqueado y llevarse el loot sin
    // esperar el desbloqueo. Despues de desbloquearse si se puede romper (el tick
    // detecta que el cofre ya no esta y limpia los hologramas).
    @EventHandler(ignoreCancelled = true)
    public void onAirdropBreak(BlockBreakEvent event) {
        if (activeAirdrops.isEmpty()) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.CHEST) {
            return;
        }
        for (Airdrop a : activeAirdrops) {
            if (isSameBlock(a.blockLocation, block.getLocation())) {
                long now = System.currentTimeMillis();
                if (now < a.unlockAtMillis) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(messageAirdropLocked.replace("%time%", formatTimePlain(a.unlockAtMillis - now)));
                }
                return;
            }
        }
    }

    private boolean isSameBlock(Location a, Location b) {
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    // --- Admin command support ---

    public void addWaypoint(Location loc) {
        waypoints.add(loc);
        saveWaypoints();
    }

    public void clearWaypoints() {
        waypoints.clear();
        saveWaypoints();
    }

    public int waypointCount() {
        return waypoints.size();
    }

    public String describeWaypoints() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            Location l = waypoints.get(i);
            sb.append(i).append(": ").append(l.getWorld().getName())
                    .append(" ").append((int) l.getX()).append(",").append((int) l.getY()).append(",").append((int) l.getZ())
                    .append("\n");
        }
        return sb.toString();
    }

    public boolean hasActiveConvoy() {
        return !golems.isEmpty();
    }

    public void forceSpawn() {
        if (hasActiveConvoy()) {
            return;
        }
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
        trySpawnConvoy();
    }

    public void forceStop() {
        cancelTasks();
        removeAllEntities();
        scheduleNext();
    }

    public void reload() {
        loadConfig();
        setupEconomy();
        validateWeaponDrops();
        smartSpawnerAvailable = SmartSpawnersHook.isAvailable();
    }
}
