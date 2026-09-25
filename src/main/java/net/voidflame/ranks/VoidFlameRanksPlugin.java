package net.voidflame.ranks;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class VoidFlameRanksPlugin extends JavaPlugin implements Listener {
    private static final String GUI_MAIN = "§8VoidFlame Ranks";
    private static final String GUI_RANKS = "§8Ranks";
    private static final String GUI_PLAYERS = "§8Players";
    private static final String GUI_PERMS = "§8Permissions";
    private static final String GUI_TESTER = "§8Rank Tester";

    private Object storage;
    private Method put;
    private Method get;
    private Method query;
    private RankService ranks;
    private final Map<UUID, ChatInput> inputs = new ConcurrentHashMap<>();

    public record Rank(String id, String name, String prefix, String suffix, int weight, String parent) {
        public Rank normalized() {
            return new Rank(id.toLowerCase(Locale.ROOT), name, prefix, suffix, weight,
                    parent == null || parent.isBlank() ? null : parent.toLowerCase(Locale.ROOT));
        }
    }

    private record ChatInput(InputType type, String rankId, String playerId) {}
    private enum InputType { CREATE_RANK, RENAME, PREFIX, SUFFIX, WEIGHT, PARENT, ADD_PERMISSION, REMOVE_PERMISSION, PLAYER_RANK }

    public static final class RankService {
        private final VoidFlameRanksPlugin plugin;
        private final Map<String, Rank> cache = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> permissions = new ConcurrentHashMap<>();
        private volatile boolean loaded;

        RankService(VoidFlameRanksPlugin plugin) { this.plugin = plugin; }

        public boolean isLoaded() { return loaded; }
        public Collection<Rank> getRanks() { return cache.values().stream().sorted(Comparator.comparingInt(Rank::weight).reversed()).toList(); }
        public Rank getRank(String id) { return cache.get(id.toLowerCase(Locale.ROOT)); }

        public CompletableFuture<Void> load() {
            return plugin.query("SELECT data_key, data_value FROM module_data WHERE module='ranks'")
                    .thenCompose(rows -> {
                        cache.clear();
                        permissions.clear();
                        for (Map<String, Object> row : rows) {
                            String key = String.valueOf(row.get("data_key"));
                            String value = String.valueOf(row.get("data_value"));
                            if (key.startsWith("rank.")) {
                                Rank r = decodeRank(key.substring(5), value);
                                if (r != null) cache.put(r.id(), r);
                            } else if (key.startsWith("perm.")) {
                                permissions.put(key.substring(5), new LinkedHashSet<>(Arrays.asList(value.split("\\n", -1))));
                                permissions.get(key.substring(5)).removeIf(String::isBlank);
                            }
                        }
                        if (cache.isEmpty()) return createDefaults();
                        loaded = true;
                        return CompletableFuture.completedFuture(null);
                    });
        }

        private CompletableFuture<Void> createDefaults() {
            List<Rank> defaults = List.of(
                    new Rank("owner","Owner","§4Owner","",1000,null),
                    new Rank("co_owner","Co Owner","§cCo Owner","",950,"owner"),
                    new Rank("head_manager","Head Manager","§cHead Manager","",900,"co_owner"),
                    new Rank("senior_manager","Senior Manager","§6Senior Manager","",850,"head_manager"),
                    new Rank("manager","Manager","§6Manager","",800,"senior_manager"),
                    new Rank("jr_manager","Jr Manager","§eJr Manager","",750,"manager"),
                    new Rank("head_developer","Head Developer","§bHead Developer","",700,"jr_manager"),
                    new Rank("senior_developer","Senior Developer","§3Senior Developer","",650,"head_developer"),
                    new Rank("developer","Developer","§3Developer","",600,"senior_developer"),
                    new Rank("jr_developer","Jr Developer","§bJr Developer","",550,"developer"),
                    new Rank("head_admin","Head Admin","§cHead Admin","",500,"jr_developer"),
                    new Rank("senior_admin","Senior Admin","§cSenior Admin","",450,"head_admin"),
                    new Rank("admin","Admin","§cAdmin","",400,"senior_admin"),
                    new Rank("jr_admin","Jr Admin","§eJr Admin","",350,"admin"),
                    new Rank("head_mod","Head Mod","§9Head Mod","",300,"jr_admin"),
                    new Rank("senior_mod","Senior Mod","§9Senior Mod","",250,"head_mod"),
                    new Rank("mod","Mod","§9Mod","",200,"senior_mod"),
                    new Rank("jr_mod","Jr Mod","§bJr Mod","",150,"mod"),
                    new Rank("head_helper","Head Helper","§aHead Helper","",120,"jr_mod"),
                    new Rank("senior_helper","Senior Helper","§2Senior Helper","",100,"head_helper"),
                    new Rank("helper","Helper","§aHelper","",80,"senior_helper"),
                    new Rank("jr_helper","Jr Helper","§aJr Helper","",60,"helper"),
                    new Rank("vip","VIP","§6VIP","",20,"jr_helper"),
                    new Rank("mvp","MVP","§dMVP","",10,"vip"),
                    new Rank("member","Member","§7Member","",1,"mvp")
            );
            CompletableFuture<Void> f = CompletableFuture.completedFuture(null);
            for (Rank rank : defaults) {
                cache.put(rank.id(), rank);
                f = f.thenCompose(v -> saveRank(rank));
            }
            loaded = true;
            return f;
        }

        public CompletableFuture<Void> saveRank(Rank rank) {
            Rank r = rank.normalized();
            cache.put(r.id(), r);
            return plugin.put("rank." + r.id(), encodeRank(r));
        }

        public CompletableFuture<Void> deleteRank(String id) {
            Rank r = getRank(id);
            if (r == null || "member".equals(r.id())) return CompletableFuture.failedFuture(new IllegalArgumentException("This rank cannot be deleted."));
            String safe = id.toLowerCase(Locale.ROOT);
            cache.remove(safe);
            permissions.remove(safe);
            return plugin.query("DELETE FROM module_data WHERE module='ranks' AND (data_key=? OR data_key=?)", "rank."+safe, "perm."+safe)
                    .thenCompose(v -> plugin.query("SELECT data_key,data_value FROM module_data WHERE module='ranks' AND data_key LIKE 'rank.%'"))
                    .thenAccept(rows -> {
                        for (Map<String,Object> row: rows) {
                            String key=String.valueOf(row.get("data_key")).substring(5);
                            Rank x=cache.get(key);
                            if (x != null && safe.equals(x.parent())) cache.put(key, new Rank(x.id(),x.name(),x.prefix(),x.suffix(),x.weight(),r.parent()));
                        }
                    });
        }

        public CompletableFuture<Void> setPlayerRank(UUID uuid, String rankId) {
            if (getRank(rankId) == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown rank."));
            return plugin.put("player."+uuid, rankId.toLowerCase(Locale.ROOT)).thenRun(() -> plugin.applyRank(uuid));
        }

        public CompletableFuture<String> getPlayerRank(UUID uuid) {
            return plugin.get("player."+uuid).thenApply(v -> v == null ? "member" : v.toLowerCase(Locale.ROOT));
        }

        public CompletableFuture<Void> setPermissions(String rankId, Collection<String> values) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String p: values) if (p != null && !p.isBlank()) set.add(p.toLowerCase(Locale.ROOT));
            permissions.put(rankId, set);
            return plugin.put("perm."+rankId, String.join("\n", set));
        }

        public Set<String> permissions(String rankId) { return Collections.unmodifiableSet(permissions.getOrDefault(rankId, Set.of())); }

        public Set<String> effectivePermissions(String rankId) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            Set<String> seen = new HashSet<>();
            String current = rankId;
            while (current != null && seen.add(current)) {
                result.addAll(permissions(current));
                Rank r = getRank(current);
                current = r == null ? null : r.parent();
            }
            return result;
        }

        public String display(Player player, String rankId) {
            Rank r = getRank(rankId);
            if (r == null) r = getRank("member");
            return color(r.prefix()) + " " + color(player.getName()) + color(r.suffix());
        }

        private static String encodeRank(Rank r) {
            return String.join("|", b64(r.name()), b64(r.prefix()), b64(r.suffix()), String.valueOf(r.weight()), b64(r.parent()==null?"":r.parent()));
        }

        private static Rank decodeRank(String id, String value) {
            try {
                String[] p=value.split("\\|",-1);
                if(p.length<5)return null;
                String parent=unb64(p[4]);
                return new Rank(id,unb64(p[0]),unb64(p[1]),unb64(p[2]),Integer.parseInt(p[3]),parent.isBlank()?null:parent);
            } catch(Exception e){ return null; }
        }

        private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        private static String unb64(String s) { return new String(Base64.getDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8); }
        private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!connectStorage()) {
            getLogger().severe("VoidFlame-Core storage unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ranks = new RankService(this);
        getServer().getServicesManager().register(RankService.class, ranks, this, ServicePriority.High);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ranks").setExecutor((sender, command, label, args) -> { if (!(sender instanceof Player p)) return true; if (!p.hasPermission("voidflame.ranks.admin")) { p.sendMessage("§cNo permission."); return true; } openMain(p); return true; });
        getCommand("rank").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("voidflame.ranks.admin")) { p.sendMessage("§cNo permission."); return true; }
            if (args.length >= 2 && args[0].equalsIgnoreCase("set")) {
                Player target=Bukkit.getPlayerExact(args[1]);
                if(target==null){p.sendMessage("§cPlayer must be online for this command.");return true;}
                String id=args.length>=3?args[2]:"member";
                ranks.setPlayerRank(target.getUniqueId(),id).thenRun(() -> Bukkit.getScheduler().runTask(this,()->{p.sendMessage("§aRank updated.");applyRank(target.getUniqueId());})).exceptionally(e->{p.sendMessage("§c"+root(e).getMessage());return null;});
                return true;
            }
            openMain(p); return true;
        });
        ranks.load().exceptionally(e->{getLogger().severe("Unable to load ranks: "+root(e).getMessage());return null;});
        getLogger().info("VoidFlame-Ranks enabled.");
    }

    private boolean connectStorage() {
        try {
            Class<?> type=Class.forName("net.voidflame.core.storage.StorageService");
            RegisteredServiceProvider<?> reg=getServer().getServicesManager().getRegistration(type);
            if(reg==null)return false;
            storage=reg.getProvider();
            put=type.getMethod("put",String.class,String.class,String.class);
            get=type.getMethod("get",String.class,String.class);
            query=type.getMethod("query",String.class,Object[].class);
            return true;
        } catch(ReflectiveOperationException e){ return false; }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> put(String key,String value){ try{return (CompletableFuture<Void>)put.invoke(storage,"ranks",key,value);}catch(ReflectiveOperationException e){return CompletableFuture.failedFuture(e);} }
    @SuppressWarnings("unchecked")
    private CompletableFuture<String> get(String key){ try{return (CompletableFuture<String>)get.invoke(storage,"ranks",key);}catch(ReflectiveOperationException e){return CompletableFuture.failedFuture(e);} }
    @SuppressWarnings("unchecked")
    private CompletableFuture<List<Map<String,Object>>> query(String sql,Object... args){ try{return (CompletableFuture<List<Map<String,Object>>>)query.invoke(storage,sql,args);}catch(ReflectiveOperationException e){return CompletableFuture.failedFuture(e);} }

    private void applyRank(UUID uuid) {
        Player p=Bukkit.getPlayer(uuid);
        if(p==null)return;
        ranks.getPlayerRank(uuid).thenAccept(id -> Bukkit.getScheduler().runTask(this,()->{
            Rank r=ranks.getRank(id);
            if(r==null)r=ranks.getRank("member");
            p.setDisplayName(ChatColor.translateAlternateColorCodes('&',r.prefix()+" "+p.getName()+r.suffix()));
            p.getEffectivePermissions().forEach(x -> {});
            for(String perm:ranks.effectivePermissions(r.id())) p.addAttachment(this,perm,true);
        }));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { applyRank(e.getPlayer().getUniqueId()); }

    private void openMain(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,GUI_MAIN);
        item(inv,11,Material.NAME_TAG,"§bRanks","§7Manage server ranks.");
        item(inv,13,Material.PLAYER_HEAD,"§aPlayers","§7Manage player ranks.");
        item(inv,15,Material.COMPARATOR,"§eRank Tester","§7Inspect effective permissions.");
        item(inv,22,Material.BOOK,"§dPermissions","§7Manage rank permissions.");
        p.openInventory(inv);
    }

    private void openRanks(Player p) {
        List<Rank> rs=new ArrayList<>(ranks.getRanks());
        Inventory inv=Bukkit.createInventory(null,54,GUI_RANKS);
        int slot=0;
        for(Rank r:rs) { if(slot>=45)break; item(inv,slot++,Material.NAME_TAG,"§f"+r.name(),"§7ID: §f"+r.id(),"§7Weight: §f"+r.weight(),"§7Parent: §f"+(r.parent()==null?"None":r.parent()),"§8Click to edit"); }
        item(inv,49,Material.EMERALD,"§aCreate Rank","§7Click then type the name in chat.");
        item(inv,53,Material.BARRIER,"§cBack");
        p.openInventory(inv);
    }

    private void openRankEditor(Player p, Rank r) {
        Inventory inv=Bukkit.createInventory(null,36,"§8Edit: "+r.name());
        item(inv,10,Material.NAME_TAG,"§bName","§7"+r.name(),"§8Click to rename");
        item(inv,12,Material.PAPER,"§bPrefix","§7"+r.prefix(),"§8Click to change");
        item(inv,14,Material.PAPER,"§bSuffix","§7"+r.suffix(),"§8Click to change");
        item(inv,16,Material.ANVIL,"§bWeight","§7"+r.weight(),"§8Click to change");
        item(inv,19,Material.LEAD,"§bParent","§7"+(r.parent()==null?"None":r.parent()),"§8Click to change");
        item(inv,21,Material.COMPARATOR,"§dPermissions","§7"+ranks.permissions(r.id()).size()+" direct permissions");
        item(inv,23,Material.PLAYER_HEAD,"§aPlayers","§7Assign this rank to a player");
        item(inv,31,Material.REDSTONE_BLOCK,"§cDelete Rank");
        item(inv,35,Material.BARRIER,"§cBack");
        p.openInventory(inv);
    }

    private void openPermissions(Player p, Rank r) {
        Inventory inv=Bukkit.createInventory(null,54,GUI_PERMS+" §f"+r.name());
        int slot=0;
        for(String perm:ranks.permissions(r.id())) { if(slot>=45)break; item(inv,slot++,Material.PAPER,"§f"+perm,"§8Click to remove"); }
        item(inv,49,Material.EMERALD,"§aAdd Permission","§7Click then type it in chat.");
        item(inv,53,Material.BARRIER,"§cBack");
        p.openInventory(inv);
    }

    private void openPlayers(Player p) {
        Inventory inv=Bukkit.createInventory(null,54,GUI_PLAYERS);
        List<Player> players=new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName,String.CASE_INSENSITIVE_ORDER));
        int slot=0;
        for(Player target:players){if(slot>=45)break; item(inv,slot++,Material.PLAYER_HEAD,"§f"+target.getName(),"§7Click to assign a rank");}
        item(inv,53,Material.BARRIER,"§cBack");
        p.openInventory(inv);
    }

    private void openTester(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,GUI_TESTER);
        item(inv,11,Material.NAME_TAG,"§eSelect Rank","§7Inspect effective permissions.");
        item(inv,15,Material.PLAYER_HEAD,"§aSelect Player","§7Inspect their assigned rank.");
        item(inv,22,Material.BARRIER,"§cBack");
        p.openInventory(inv);
    }

    private void begin(Player p, ChatInput input, String message) {
        inputs.put(p.getUniqueId(),input);
        p.closeInventory();
        p.sendMessage("§e"+message+" §7Type §ccancel §7to abort.");
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        ChatInput input=inputs.remove(e.getPlayer().getUniqueId());
        if(input==null)return;
        e.setCancelled(true);
        String value=e.getMessage().trim();
        Bukkit.getScheduler().runTask(this,()->handleInput(e.getPlayer(),input,value));
    }

    private void handleInput(Player p, ChatInput in, String value) {
        if(value.equalsIgnoreCase("cancel")){p.sendMessage("§7Cancelled.");openMain(p);return;}
        try {
            switch(in.type()) {
                case CREATE_RANK -> {
                    String id=value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+","_");
                    if(ranks.getRank(id)!=null) throw new IllegalArgumentException("Rank already exists.");
                    ranks.saveRank(new Rank(id,value,"&7"+value,"",Math.max(2,ranks.getRanks().stream().mapToInt(Rank::weight).min().orElse(1)-1),"member"))
                            .thenRun(()->Bukkit.getScheduler().runTask(this,()->{p.sendMessage("§aRank created.");openRanks(p);}));
                }
                case RENAME -> update(p,in.rankId(),r->new Rank(r.id(),value,r.prefix(),r.suffix(),r.weight(),r.parent()));
                case PREFIX -> update(p,in.rankId(),r->new Rank(r.id(),r.name(),value,r.suffix(),r.weight(),r.parent()));
                case SUFFIX -> update(p,in.rankId(),r->new Rank(r.id(),r.name(),r.prefix(),value,r.weight(),r.parent()));
                case WEIGHT -> { int n=Integer.parseInt(value); update(p,in.rankId(),r->new Rank(r.id(),r.name(),r.prefix(),r.suffix(),n,r.parent())); }
                case PARENT -> { String parent=value.equalsIgnoreCase("none")?null:value.toLowerCase(Locale.ROOT); if(parent!=null&&ranks.getRank(parent)==null)throw new IllegalArgumentException("Unknown parent rank."); if(parent!=null&&parent.equals(in.rankId()))throw new IllegalArgumentException("A rank cannot inherit itself."); update(p,in.rankId(),r->new Rank(r.id(),r.name(),r.prefix(),r.suffix(),r.weight(),parent)); }
                case ADD_PERMISSION -> { List<String> x=new ArrayList<>(ranks.permissions(in.rankId()));x.add(value);ranks.setPermissions(in.rankId(),x).thenRun(()->{p.sendMessage("§aPermission added.");openPermissions(p,ranks.getRank(in.rankId()));}); }
                case REMOVE_PERMISSION -> { List<String>x=new ArrayList<>(ranks.permissions(in.rankId()));x.removeIf(s->s.equalsIgnoreCase(value));ranks.setPermissions(in.rankId(),x).thenRun(()->openPermissions(p,ranks.getRank(in.rankId()))); }
                case PLAYER_RANK -> { String id=value.toLowerCase(Locale.ROOT); if(ranks.getRank(id)==null)throw new IllegalArgumentException("Unknown rank."); Player target=Bukkit.getPlayer(in.playerId()); if(target==null)throw new IllegalArgumentException("Player is offline."); ranks.setPlayerRank(target.getUniqueId(),id).thenRun(()->{applyRank(target.getUniqueId());p.sendMessage("§aRank assigned.");openPlayers(p);}); }
            }
        } catch(Exception ex){p.sendMessage("§c"+root(ex).getMessage());}
    }

    private void update(Player p,String id,java.util.function.Function<Rank,Rank> fn) {
        Rank r=ranks.getRank(id); if(r==null){p.sendMessage("§cRank not found.");return;}
        ranks.saveRank(fn.apply(r)).thenRun(()->Bukkit.getScheduler().runTask(this,()->{p.sendMessage("§aSaved.");openRankEditor(p,ranks.getRank(id));}));
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()==null)return;
        String title=e.getView().getTitle();
        if(!(title.equals(GUI_MAIN)||title.equals(GUI_RANKS)||title.startsWith("§8Edit:")||title.startsWith(GUI_PERMS)||title.equals(GUI_PLAYERS)||title.equals(GUI_TESTER)))return;
        e.setCancelled(true);
        if(title.equals(GUI_MAIN)){
            if(e.getRawSlot()==11)openRanks(p);
            else if(e.getRawSlot()==13)openPlayers(p);
            else if(e.getRawSlot()==15)openTester(p);
            else if(e.getRawSlot()==22) { if(ranks.getRank("member")!=null)openPermissions(p,ranks.getRank("member")); }
            return;
        }
        if(title.equals(GUI_RANKS)){
            if(e.getRawSlot()==49){begin(p,new ChatInput(InputType.CREATE_RANK,null,null),"Enter the new rank name.");return;}
            if(e.getRawSlot()==53){openMain(p);return;}
            List<Rank> rs=new ArrayList<>(ranks.getRanks()); int s=e.getRawSlot(); if(s>=0&&s<45&&s<rs.size())openRankEditor(p,rs.get(s)); return;
        }
        if(title.startsWith("§8Edit:")){
            String name=ChatColor.stripColor(title).substring("Edit: ".length());
            Rank r=ranks.getRanks().stream().filter(x->x.name().equals(name)).findFirst().orElse(null); if(r==null)return;
            switch(e.getRawSlot()){
                case 10->begin(p,new ChatInput(InputType.RENAME,r.id(),null),"Enter the new rank name.");
                case 12->begin(p,new ChatInput(InputType.PREFIX,r.id(),null),"Enter the new prefix. Use & for colors.");
                case 14->begin(p,new ChatInput(InputType.SUFFIX,r.id(),null),"Enter the new suffix. Use & for colors.");
                case 16->begin(p,new ChatInput(InputType.WEIGHT,r.id(),null),"Enter the new weight.");
                case 19->begin(p,new ChatInput(InputType.PARENT,r.id(),null),"Enter parent rank ID or none.");
                case 21->openPermissions(p,r);
                case 23->openPlayers(p);
                case 31->{ if(r.id().equals("member")){p.sendMessage("§cMember cannot be deleted.");return;} ranks.deleteRank(r.id()).thenRun(()->Bukkit.getScheduler().runTask(this,()->{p.sendMessage("§aRank deleted.");openRanks(p);})); }
                case 35->openRanks(p);
            }
            return;
        }
        if(title.startsWith(GUI_PERMS)){
            String rankName=ChatColor.stripColor(title).substring("Permissions ".length()).trim();
            Rank r=ranks.getRanks().stream().filter(x->x.name().equals(rankName)).findFirst().orElse(null); if(r==null)return;
            if(e.getRawSlot()==49){begin(p,new ChatInput(InputType.ADD_PERMISSION,r.id(),null),"Enter a permission.");return;}
            if(e.getRawSlot()==53){openRankEditor(p,r);return;}
            List<String> ps=new ArrayList<>(ranks.permissions(r.id()));int s=e.getRawSlot();if(s>=0&&s<45&&s<ps.size()){begin(p,new ChatInput(InputType.REMOVE_PERMISSION,r.id(),null),"Type the exact permission to remove: "+ps.get(s));}
            return;
        }
        if(title.equals(GUI_PLAYERS)){
            if(e.getRawSlot()==53){openMain(p);return;}
            List<Player> ps=new ArrayList<>(Bukkit.getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName,String.CASE_INSENSITIVE_ORDER)).toList());int s=e.getRawSlot();if(s>=0&&s<45&&s<ps.size()){Player target=ps.get(s);begin(p,new ChatInput(InputType.PLAYER_RANK,null,target.getName()),"Enter rank ID for "+target.getName()+".");}
            return;
        }
        if(title.equals(GUI_TESTER)){
            if(e.getRawSlot()==22){openMain(p);return;}
            if(e.getRawSlot()==11){openRanks(p);return;}
            if(e.getRawSlot()==15){openPlayers(p);return;}
        }
    }

    private void item(Inventory inv,int slot,Material material,String name,String... lore){
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);meta.setLore(Arrays.asList(lore));item.setItemMeta(meta);inv.setItem(slot,item);
    }

    private static Throwable root(Throwable t){while(t.getCause()!=null)t=t.getCause();return t;}
}
