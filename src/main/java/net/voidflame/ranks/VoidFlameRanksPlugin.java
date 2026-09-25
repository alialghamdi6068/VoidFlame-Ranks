package net.voidflame.ranks;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class VoidFlameRanksPlugin extends JavaPlugin implements Listener {
    private Object storage;
    private Method put;
    private Method get;

    @Override
    public void onEnable() {
        if (!connectStorage()) {
            getLogger().severe("VoidFlame-Core storage service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("VoidFlame-Ranks enabled. Persistent data is provided by VoidFlame-Core.");
    }

    private boolean connectStorage() {
        try {
            Class<?> type = Class.forName("net.voidflame.core.storage.StorageService");
            RegisteredServiceProvider<?> registration = getServer().getServicesManager().getRegistration(type);
            if (registration == null) return false;
            storage = registration.getProvider();
            put = type.getMethod("put", String.class, String.class, String.class);
            get = type.getMethod("get", String.class, String.class);
            return true;
        } catch (ReflectiveOperationException ex) {
            getLogger().severe("Unable to connect to VoidFlame-Core storage: " + ex.getMessage());
            return false;
        }
    }

    public CompletableFuture<Void> put(String key, String value) {
        try {
            return (CompletableFuture<Void>) put.invoke(storage, "ranks", key, value);
        } catch (ReflectiveOperationException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    public CompletableFuture<String> get(String key) {
        try {
            return (CompletableFuture<String>) get.invoke(storage, "ranks", key);
        } catch (ReflectiveOperationException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
}
