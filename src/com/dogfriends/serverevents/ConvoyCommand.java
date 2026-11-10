package com.dogfriends.serverevents;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ConvoyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ADMIN_SUBCOMMANDS = Arrays.asList(
            "addpoint", "clearpoints", "listpoints", "spawn", "stop", "reload", "airdrop", "cleanup", "testdrop");
    private static final String USAGE =
            "Uso: /convoy <addpoint|clearpoints|listpoints|spawn|stop|reload|airdrop|cleanup|testdrop|track>";

    private final ConvoyManager manager;

    public ConvoyCommand(ConvoyManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        // "track" es el unico subcomando para TODOS los jugadores (permiso default true).
        if (sub.equals("track")) {
            if (!sender.hasPermission("serverevents.convoy.track")) {
                sender.sendMessage(ChatColor.RED + "No tienes permiso para usar este comando.");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Solo un jugador puede usar este comando.");
                return true;
            }
            if (manager.giveTracker(player)) {
                player.sendMessage(ChatColor.GREEN + "Tienes el rastreador: la brujula apunta al convoy.");
            } else {
                player.sendMessage(ChatColor.RED + "No hay ningun convoy activo ahora mismo.");
            }
            return true;
        }

        if (!sender.hasPermission("serverevents.convoy.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para usar este comando.");
            return true;
        }
        if (sub.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + USAGE);
            return true;
        }

        switch (sub) {
            case "addpoint" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Solo un jugador puede usar este comando.");
                    return true;
                }
                manager.addWaypoint(player.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Waypoint agregado (" + manager.waypointCount() + " en total).");
            }
            case "clearpoints" -> {
                manager.clearWaypoints();
                sender.sendMessage(ChatColor.GREEN + "Waypoints del convoy eliminados.");
            }
            case "listpoints" -> {
                sender.sendMessage(ChatColor.YELLOW + "Waypoints (" + manager.waypointCount() + "):\n" + manager.describeWaypoints());
            }
            case "spawn" -> {
                if (manager.hasActiveConvoy()) {
                    sender.sendMessage(ChatColor.RED + "Ya hay un convoy activo.");
                } else {
                    manager.forceSpawn();
                    sender.sendMessage(ChatColor.GREEN + "Convoy generado.");
                }
            }
            case "stop" -> {
                manager.forceStop();
                sender.sendMessage(ChatColor.GREEN + "Convoy detenido.");
            }
            case "reload" -> {
                manager.reload();
                sender.sendMessage(ChatColor.GREEN + "Configuracion de ServerEvents recargada.");
            }
            case "airdrop" -> {
                if (sender instanceof Player player) {
                    manager.testAirdrop(player.getLocation());
                    sender.sendMessage(ChatColor.GREEN + "Airdrop de prueba generado.");
                } else if (!org.bukkit.Bukkit.getOnlinePlayers().isEmpty()) {
                    Player anyPlayer = org.bukkit.Bukkit.getOnlinePlayers().iterator().next();
                    manager.testAirdrop(anyPlayer.getLocation());
                    sender.sendMessage(ChatColor.GREEN + "Airdrop de prueba generado cerca de " + anyPlayer.getName() + ".");
                } else {
                    sender.sendMessage(ChatColor.RED + "No hay jugadores conectados para ubicar el airdrop de prueba.");
                }
            }
            case "cleanup" -> {
                int removed = manager.cleanupOrphans();
                sender.sendMessage(ChatColor.GREEN + "Limpieza: " + removed
                        + " entidades/cofres huerfanos del evento eliminados.");
            }
            case "testdrop" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Solo un jugador puede usar este comando.");
                    return true;
                }
                manager.testWeaponDrops(player);
            }
            default -> sender.sendMessage(ChatColor.YELLOW + USAGE);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("serverevents.convoy.admin")) {
                options.addAll(ADMIN_SUBCOMMANDS);
            }
            if (sender.hasPermission("serverevents.convoy.track")) {
                options.add("track");
            }
            return options;
        }
        return Collections.emptyList();
    }
}
