package com.dogfriends.serverevents;

import org.bukkit.plugin.java.JavaPlugin;

public class ServerEventsPlugin extends JavaPlugin {

    private RaidAlertListener raidAlertListener;
    private ConvoyManager convoyManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        raidAlertListener = new RaidAlertListener(this);
        getServer().getPluginManager().registerEvents(raidAlertListener, this);

        convoyManager = new ConvoyManager(this);
        getServer().getPluginManager().registerEvents(convoyManager, this);
        convoyManager.start();

        ConvoyCommand convoyCommand = new ConvoyCommand(convoyManager);
        getCommand("convoy").setExecutor(convoyCommand);
        getCommand("convoy").setTabCompleter(convoyCommand);

        getLogger().info("ServerEvents habilitado. Alertas de raid: " + raidAlertListener.isConfigured()
                + " | Convoy: " + convoyManager.isEnabled());
    }

    @Override
    public void onDisable() {
        if (convoyManager != null) {
            convoyManager.shutdown();
        }
    }
}
