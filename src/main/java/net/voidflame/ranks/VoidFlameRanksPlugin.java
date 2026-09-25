package net.voidflame.ranks;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class VoidFlameRanksPlugin extends JavaPlugin implements Listener {
    private Object storage;
    private Method put, get;
    private RankService ranks;

    public record Rank(String id, String prefix) {}

    public static final class RankService {
        private final VoidFlameRanksPlugin plugin;
        RankService(VoidFlameRanksPlugin plugin){ this.plugin=plugin; }
        public CompletableFuture<String> getRank(UUID uuid) { return plugin.get("rank:"+uuid).thenApply(v -> v == null ? "member" : v); }
        public CompletableFuture<Void> setRank(UUID uuid, String rank) { return plugin.put("rank:"+uuid, rank.toLowerCase()); }
        public String prefix(String rank) {
            return switch(rank.toLowerCase()) {
                case "founder" -> "Founder";
                case "head_manager" -> "Head Manager";
                case "manager" -> "Manager";
                case "head_helper" -> "Head Helper";
                case "senior_helper" -> "Senior Helper";
                case "helper" -> "Helper";
                case "developer" -> "Developer";
                default -> "Member";
            };
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!connectStorage()) { getLogger().severe("VoidFlame-Core storage unavailable."); getServer().getPluginManager().disablePlugin(this); return; }
        ranks = new RankService(this);
        getServer().getServicesManager().register(RankService.class, ranks, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this,this);
        getLogger().info("VoidFlame-Ranks enabled.");
    }

    private boolean connectStorage() {
        try {
            Class<?> type=Class.forName("net.voidflame.core.storage.StorageService");
            RegisteredServiceProvider<?> reg=getServer().getServicesManager().getRegistration(type);
            if(reg==null)return false; storage=reg.getProvider(); put=type.getMethod("put",String.class,String.class,String.class); get=type.getMethod("get",String.class,String.class); return true;
        } catch(ReflectiveOperationException e){return false;}
    }
    public CompletableFuture<Void> put(String key,String value){try{return (CompletableFuture<Void>)put.invoke(storage,"ranks",key,value);}catch(ReflectiveOperationException e){return CompletableFuture.failedFuture(e);}}
    public CompletableFuture<String> get(String key){try{return (CompletableFuture<String>)get.invoke(storage,"ranks",key);}catch(ReflectiveOperationException e){return CompletableFuture.failedFuture(e);}}
    @EventHandler public void onJoin(PlayerJoinEvent e){ get("rank:"+e.getPlayer().getUniqueId()).thenAccept(rank -> { if(rank!=null) e.getPlayer().setDisplayName(ChatColor.GRAY+"["+ranks.prefix(rank)+"] "+e.getPlayer().getName()); }); }
    @Override public void onDisable(){getServer().getServicesManager().unregister(RankService.class,this);}
}
