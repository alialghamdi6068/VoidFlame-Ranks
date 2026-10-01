package net.voidflame.ranks;

import net.voidflame.core.storage.StorageService;
import net.voidflame.core.api.AuditLogService;

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
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

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

    private StorageService storage;
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
        private final Map<UUID, String> playerRanks = new ConcurrentHashMap<>();
        private volatile boolean loaded;

        RankService(VoidFlameRanksPlugin plugin) { this.plugin = plugin; }

        public boolean isLoaded() { return loaded; }
        public Collection<Rank> getRanks() { return cache.values().stream().sorted(Comparator.comparingInt(Rank::weight).reversed()).toList(); }
        public Rank getRank(String id) { return cache.get(id.toLowerCase(Locale.ROOT)); }

        public CompletableFuture<Void> load() {
            playerRanks.clear();
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
            List<Rank> defaults = configuredDefaults();
            CompletableFuture<Void> f = CompletableFuture.completedFuture(null);
            Map<String, Set<String>> defaultPermissions = defaultDuelPermissions();
            for (Rank rank : defaults) {
                cache.put(rank.id(), rank);
                f = f.thenCompose(v -> saveRank(rank));
                Set<String> perms = defaultPermissions.get(rank.id());
                if (perms != null && !perms.isEmpty()) {
                    permissions.put(rank.id(), new LinkedHashSet<>(perms));
                    f = f.thenCompose(v -> plugin.put("perm." + rank.id(), String.join("\n", perms)));
                }
            }
            loaded = true;
            return f;
        }

        private List<Rank> configuredDefaults() {
            var section = plugin.getConfig().getConfigurationSection("ranks");
            if (section == null) {
                return List.of(
                        new Rank("owner","Owner","§5Owner","",900,null),
                        new Rank("manager","Manager","§6Manager","",800,"owner"),
                        new Rank("developer","Developer","§bDeveloper","",700,"manager"),
                        new Rank("admin","Admin","§cAdmin","",600,"developer"),
                        new Rank("moderator","Moderator","§9Moderator","",500,"admin"),
                        new Rank("helper","Helper","§aHelper","",400,"moderator"),
                        new Rank("mvp","MVP","§dMVP","",300,"helper"),
                        new Rank("vip","VIP","§6VIP","",200,"mvp"),
                        new Rank("player","Player","§7Player","",100,null)
                );
            }
            List<Rank> defaults = new ArrayList<>();
            for (String id : section.getKeys(false)) {
                var rank = section.getConfigurationSection(id);
                if (rank == null) continue;
                String name = rank.getString("name", id);
                String prefix = rank.getString("prefix", "&7" + name);
                String suffix = rank.getString("suffix", "");
                int weight = rank.getInt("weight", 100);
                String parent = rank.getString("parent", null);
                defaults.add(new Rank(id, name, prefix, suffix, weight, parent));
            }
            defaults.sort(Comparator.comparingInt(Rank::weight).reversed());
            if (defaults.stream().noneMatch(r -> r.id().equalsIgnoreCase("player"))) {
                defaults.add(new Rank("player", "Player", "&7Player", "", 100, null));
            }
            return defaults;
        }

        private Map<String, Set<String>> defaultDuelPermissions() {
            Set<String> player = new LinkedHashSet<>(List.of(
                    "voidflame.spawn","voidflame.duel","voidflame.duel.accept","voidflame.queue",
                    "voidflame.spectate","voidflame.stats","voidflame.leaderboard","voidflame.kits",
                    "voidflame.party","voidflame.report","voidflame.settings"
            ));
            Set<String> vip = new LinkedHashSet<>(player);
            vip.addAll(List.of("voidflame.duel.private","voidflame.party.create","voidflame.party.private"));
            Set<String> mvp = new LinkedHashSet<>(vip);
            Set<String> helper = new LinkedHashSet<>(mvp);
            helper.addAll(List.of("voidflame.staff","voidflame.staff.chat","voidflame.spectate.others","voidflame.report.view","voidflame.report.handle"));
            Set<String> moderator = new LinkedHashSet<>(helper);
            moderator.addAll(List.of("voidflame.staffmode","voidflame.staff.freeze","voidflame.staff.vanish","voidflame.duel.forceend","voidflame.logs.view"));
            Set<String> admin = new LinkedHashSet<>(moderator);
            admin.addAll(List.of("voidflame.arena.manage","voidflame.kit.manage","voidflame.duel.forcematch","voidflame.elo.modify","voidflame.player.rank","voidflame.world.manage","voidflame.logs.manage","voidflame.reload"));
            Set<String> developer = new LinkedHashSet<>(admin);
            developer.addAll(List.of("voidflame.developer","voidflame.debug","voidflame.test","voidflame.command.override"));
            Set<String> manager = new LinkedHashSet<>(developer);
            manager.addAll(List.of("voidflame.staff.manage","voidflame.ranks.manage","voidflame.security.manage","voidflame.server.manage"));
            Set<String> owner = new LinkedHashSet<>(manager);
            owner.add("*");

            Map<String, Set<String>> result = new HashMap<>();
            result.put("player", player);
            result.put("vip", vip);
            result.put("mvp", mvp);
            result.put("helper", helper);
            result.put("moderator", moderator);
            result.put("admin", admin);
            result.put("developer", developer);
            result.put("manager", manager);
            result.put("owner", owner);
            return result;
        }

        public CompletableFuture<Void> saveRank(Rank rank) {
            Rank r = rank.normalized();
            cache.put(r.id(), r);
            return plugin.put("rank." + r.id(), encodeRank(r));
        }

        public CompletableFuture<Void> deleteRank(String id) {
            Rank r = getRank(id);
            if (r == null || "player".equals(r.id())) return CompletableFuture.failedFuture(new IllegalArgumentException("This rank cannot be deleted."));
            String safe = id.toLowerCase(Locale.ROOT);
            cache.remove(safe);
            permissions.remove(safe);
            return plugin.query("DELETE FROM module_data WHERE module='ranks' AND (data_key=? OR data_key=?)", "rank."+safe, "perm."+safe)
                    .thenCompose(v -> plugin.query("SELECT data_key,data_value FROM module_data WHERE module='ranks' AND data_key LIKE 'rank.%'"))
                    .thenAccept(rows -> {
                        for (Map<String,Object> row: rows) {
                            String key=String.valueOf(row.get("data_key")).substring(5);
                            Rank x=cache.get(key);
                            if (x != null && safe.equals(x.parent())) {
                                Rank updated = new Rank(x.id(), x.name(), x.prefix(), x.suffix(), x.weight(), r.parent());
                                cache.put(key, updated);
                                plugin.put("rank." + key, encodeRank(updated));
                            }
                        }
                    });
        }

        public CompletableFuture<Void> setPlayerRank(UUID uuid, String rankId) {
            if (getRank(rankId) == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown rank."));
            String normalized = rankId.toLowerCase(Locale.ROOT);
            return plugin.put("player."+uuid, normalized).thenRun(() -> {
                playerRanks.put(uuid, normalized);
                plugin.applyRank(uuid);
                var registration = plugin.getServer().getServicesManager().getRegistration(AuditLogService.class);
                if (registration != null && registration.getProvider() != null) {
                    registration.getProvider().log(uuid.toString(), "RANK_CHANGE", uuid.toString(), "rank=" + rankId.toLowerCase(Locale.ROOT));
                }
            });
        }

        public CompletableFuture<String> getPlayerRank(UUID uuid) {
            String cached = playerRanks.get(uuid);
            if (cached != null) return CompletableFuture.completedFuture(cached);
            return plugin.get("player."+uuid).thenApply(v -> {
                String normalized = v == null ? "player" : v.toLowerCase(Locale.ROOT);
                playerRanks.put(uuid, normalized);
                return normalized;
            });
        }

        public String getPlayerRankCached(UUID uuid) {
            return playerRanks.getOrDefault(uuid, "player");
        }

        public CompletableFuture<Void> setPermissions(String rankId, Collection<String> values) {
            if (getRank(rankId) == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown rank."));
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String p: values) if (p != null && !p.isBlank()) set.add(p.toLowerCase(Locale.ROOT));
            permissions.put(rankId, set);
            return plugin.put("perm."+rankId, String.join("\n", set));
        }

        public Set<String> permissions(String rankId) { return Collections.unmodifiableSet(permissions.getOrDefault(rankId, Set.of())); }

        public boolean wouldCreateCycle(String childId, String parentId) {
            String current = parentId == null ? null : parentId.toLowerCase(Locale.ROOT);
            Set<String> seen = new HashSet<>();
            while (current != null && seen.add(current)) {
                if (current.equalsIgnoreCase(childId)) return true;
                Rank rank = getRank(current);
                current = rank == null ? null : rank.parent();
            }
            return current != null;
        }

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
            if (r == null) r = getRank("player");
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
                OfflinePlayer target=Bukkit.getOfflinePlayer(args[1]);
                String id=args.length>=3?args[2]:"player";
                ranks.setPlayerRank(target.getUniqueId(),id).thenRun(() -> Bukkit.getScheduler().runTask(this,()->{p.sendMessage("§aRank updated for §f"+target.getName()+"§a."); if(target.isOnline()) applyRank(target.getUniqueId());})).exceptionally(e->{p.sendMessage("§c"+root(e).getMessage());return null;});
                return true;
            }
            openMain(p); return true;
        });
        ranks.load().exceptionally(e->{getLogger().severe("Unable to load ranks: "+root(e).getMessage());return null;});
        getLogger().info("VoidFlame-Ranks enabled.");
    }

    private boolean connectStorage(){ var registration=getServer().getServicesManager().getRegistration(StorageService.class); if(registration==null)return false; storage=registration.getProvider(); return storage!=null; }

    private CompletableFuture<Void> put(String key,String value){ return storage.put("ranks",key,value); }
    private CompletableFuture<String> get(String key){ return storage.get("ranks",key); }
    private CompletableFuture<List<Map<String,Object>>> query(String sql,Object... args){ return storage.query(sql,args); }

    private final Map<UUID, List<org.bukkit.permissions.PermissionAttachment>> rankAttachments = new ConcurrentHashMap<>();

    private void applyRank(UUID uuid) {
        Player p=Bukkit.getPlayer(uuid);
        if(p==null)return;
        ranks.getPlayerRank(uuid).thenAccept(id -> Bukkit.getScheduler().runTask(this,()->{
            Rank r=ranks.getRank(id);
            if(r==null)r=ranks.getRank("player");

            List<org.bukkit.permissions.PermissionAttachment> old = rankAttachments.remove(uuid);
            if (old != null) {
                for (org.bukkit.permissions.PermissionAttachment attachment : old) p.removeAttachment(attachment);
            }

            p.setDisplayName(ChatColor.translateAlternateColorCodes('&',r.prefix()+" "+p.getName()+r.suffix()));

            List<org.bukkit.permissions.PermissionAttachment> attachments = new ArrayList<>();
            for(String perm:ranks.effectivePermissions(r.id())) {
                org.bukkit.permissions.PermissionAttachment attachment = p.addAttachment(this, perm, true);
                attachments.add(attachment);
            }
            rankAttachments.put(uuid, attachments);
        }));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { applyRank(e.getPlayer().getUniqueId()); }

    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        List<org.bukkit.permissions.PermissionAttachment> old = rankAttachments.remove(uuid);
        if (old != null) {
            for (org.bukkit.permissions.PermissionAttachment attachment : old) e.getPlayer().removeAttachment(attachment);
        }
    }

    private void openMain(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,GUI_MAIN);
        item(inv,11,Material.NAME_TAG,"§bRanks","§7Manage server ranks.");
        item(inv,13,Material.PLAYER_HEAD,"§aPlayers","§7Manage player ranks.");
        item(inv,15,Material.COMPARATOR,"§eRank Tester","§7Inspect effective permissions.");
        item(inv,22,Material.BOOK,"§dPermissions","§7Manage rank permissions.");
        p.openInventory(inv);
    }

    private void openRanks(Player p) { openRanks(p, 1); }

    private void openRanks(Player p, int page) {
        List<Rank> rs=new ArrayList<>(ranks.getRanks());
        int pageSize=Math.max(1, Math.min(45, getConfig().getInt("settings.gui-page-size",45)));
        int pages=Math.max(1,(rs.size()+pageSize-1)/pageSize);
        page=Math.max(1,Math.min(page,pages));
        Inventory inv=Bukkit.createInventory(null,54,GUI_RANKS+" §7Page "+page);
        int start=(page-1)*pageSize;
        for(int i=0;i<pageSize && start+i<rs.size();i++){
            Rank r=rs.get(start+i);
            item(inv,i,Material.NAME_TAG,"§f"+r.name(),"§7ID: §f"+r.id(),"§7Weight: §f"+r.weight(),"§7Parent: §f"+(r.parent()==null?"None":r.parent()),"§8Click to edit");
        }
        if(page>1)item(inv,45,Material.ARROW,"§ePrevious");
        item(inv,49,Material.EMERALD,"§aCreate Rank","§7Click then type the name in chat.");
        if(page<pages)item(inv,50,Material.ARROW,"§eNext");
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

    private void openPlayers(Player p) { openPlayers(p,1); }

    private void openPlayers(Player p, int page) {
        List<OfflinePlayer> players=new ArrayList<>(Arrays.asList(Bukkit.getOfflinePlayers()));
        players.removeIf(x -> x.getName()==null);
        players.sort(Comparator.comparing(OfflinePlayer::getName,String.CASE_INSENSITIVE_ORDER));
        int pageSize=Math.max(1,Math.min(45,getConfig().getInt("settings.gui-page-size",45)));
        int pages=Math.max(1,(players.size()+pageSize-1)/pageSize);
        page=Math.max(1,Math.min(page,pages));
        Inventory inv=Bukkit.createInventory(null,54,GUI_PLAYERS+" §7Page "+page);
        int start=(page-1)*pageSize;
        for(int i=0;i<pageSize&&start+i<players.size();i++){
            OfflinePlayer target=players.get(start+i);
            item(inv,i,Material.PLAYER_HEAD,"§f"+target.getName(),"§7Click to assign a rank");
        }
        if(page>1)item(inv,45,Material.ARROW,"§ePrevious");
        if(page<pages)item(inv,50,Material.ARROW,"§eNext");
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
                    ranks.saveRank(new Rank(id,value,"&7"+value,"",Math.max(2,ranks.getRanks().stream().mapToInt(Rank::weight).min().orElse(1)-1),"player"))
                            .thenRun(()->Bukkit.getScheduler().runTask(this,()->{p.sendMessage("§aRank created.");openRanks(p);}));
                }
                case RENAME -> update(p,in.rankId(),r->new Rank(r.id(),value,r.prefix(),r.suffix(),r.weight(),r.parent()));
                case PREFIX -> update(p,in.rankId(),r->new Rank(r.id(),r.name(),value,r.suffix(),r.weight(),r.parent()));
                case SUFFIX -> update(p,in.rankId(),r->new Rank(r.id(),r.name(),r.prefix(),value,r.weight(),r.parent()));
                case WEIGHT -> { int n=Integer.parseInt(value); update(p,in.rankId(),r->new Rank(r.id(),r.name(),r.prefix(),r.suffix(),n,r.parent())); }
                case PARENT -> { String parent=value.equalsIgnoreCase("none")?null:value.toLowerCase(Locale.ROOT); if(parent!=null&&ranks.getRank(parent)==null)throw new IllegalArgumentException("Unknown parent rank."); if(parent!=null&&ranks.wouldCreateCycle(in.rankId(), parent))throw new IllegalArgumentException("That parent would create an inheritance cycle."); update(p,in.rankId(),r->new Rank(r.id(),r.name(),r.prefix(),r.suffix(),r.weight(),parent)); }
                case ADD_PERMISSION -> { List<String> x=new ArrayList<>(ranks.permissions(in.rankId()));x.add(value);ranks.setPermissions(in.rankId(),x).thenRun(()->{p.sendMessage("§aPermission added.");openPermissions(p,ranks.getRank(in.rankId()));}); }
                case REMOVE_PERMISSION -> { List<String>x=new ArrayList<>(ranks.permissions(in.rankId()));x.removeIf(s->s.equalsIgnoreCase(value));ranks.setPermissions(in.rankId(),x).thenRun(()->openPermissions(p,ranks.getRank(in.rankId()))); }
                case PLAYER_RANK -> { String id=value.toLowerCase(Locale.ROOT); if(ranks.getRank(id)==null)throw new IllegalArgumentException("Unknown rank."); OfflinePlayer target=Bukkit.getOfflinePlayer(in.playerId()); if(target.getName()==null)throw new IllegalArgumentException("Player has no known profile."); ranks.setPlayerRank(target.getUniqueId(),id).thenRun(()->{applyRank(target.getUniqueId());p.sendMessage("§aRank assigned.");openPlayers(p);}); }
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
            else if(e.getRawSlot()==22) { if(ranks.getRank("player")!=null)openPermissions(p,ranks.getRank("player")); }
            return;
        }
        if(title.startsWith(GUI_RANKS)){
            int page=parsePage(title);
            if(e.getRawSlot()==49){begin(p,new ChatInput(InputType.CREATE_RANK,null,null),"Enter the new rank name.");return;}
            if(e.getRawSlot()==53){openMain(p);return;}
            if(e.getRawSlot()==45){openRanks(p,page-1);return;}
            if(e.getRawSlot()==50){openRanks(p,page+1);return;}
            List<Rank> rs=new ArrayList<>(ranks.getRanks());
            int pageSize=Math.max(1,Math.min(45,getConfig().getInt("settings.gui-page-size",45)));
            int index=(page-1)*pageSize+e.getRawSlot();
            if(e.getRawSlot()>=0&&e.getRawSlot()<pageSize&&index<rs.size())openRankEditor(p,rs.get(index));
            return;
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
                case 31->{ if(r.id().equals("player")){p.sendMessage("§cPlayer rank cannot be deleted.");return;} ranks.deleteRank(r.id()).thenRun(()->Bukkit.getScheduler().runTask(this,()->{p.sendMessage("§aRank deleted.");openRanks(p);})); }
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
        if(title.startsWith(GUI_PLAYERS)){
            int page=parsePage(title);
            if(e.getRawSlot()==53){openMain(p);return;}
            if(e.getRawSlot()==45){openPlayers(p,page-1);return;}
            if(e.getRawSlot()==50){openPlayers(p,page+1);return;}
            List<OfflinePlayer> ps=new ArrayList<>(Arrays.asList(Bukkit.getOfflinePlayers()));
            ps.removeIf(x -> x.getName()==null);
            ps.sort(Comparator.comparing(OfflinePlayer::getName,String.CASE_INSENSITIVE_ORDER));
            int pageSize=Math.max(1,Math.min(45,getConfig().getInt("settings.gui-page-size",45)));
            int index=(page-1)*pageSize+e.getRawSlot();
            if(e.getRawSlot()>=0&&e.getRawSlot()<pageSize&&index<ps.size()){
                OfflinePlayer target=ps.get(index);
                begin(p,new ChatInput(InputType.PLAYER_RANK,null,target.getName()),"Enter rank ID for "+target.getName()+".");
            }
            return;
        }
        if(title.equals(GUI_TESTER)){
            if(e.getRawSlot()==22){openMain(p);return;}
            if(e.getRawSlot()==11){openRanks(p);return;}
            if(e.getRawSlot()==15){openPlayers(p);return;}
        }
    }

    private int parsePage(String title) {
        int marker=title.lastIndexOf("Page ");
        if(marker<0)return 1;
        try{return Math.max(1,Integer.parseInt(ChatColor.stripColor(title.substring(marker+5)).trim()));}
        catch(NumberFormatException ignored){return 1;}
    }

    private void item(Inventory inv,int slot,Material material,String name,String... lore){
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);meta.setLore(Arrays.asList(lore));item.setItemMeta(meta);inv.setItem(slot,item);
    }

    private static Throwable root(Throwable t){while(t.getCause()!=null)t=t.getCause();return t;}
}
