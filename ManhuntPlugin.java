package com.manhunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class ManhuntPlugin extends JavaPlugin implements Listener, TabExecutor {

    // ---- Ayarlar (saniye) ----
    private static final int SWAP_INTERVAL = 600;   // 10 dk
    private static final int TELEPORT_DELAY = 3;    // sol tiktan sonra isinlanma gecikmesi

    private enum State { IDLE, COUNTDOWN, READY, TELEPORTING }

    private State state = State.IDLE;
    private UUID runnerId;
    private UUID selectedId;     // pusulayla secili avci
    private UUID pendingTarget;  // sol tiklanan avci
    private int remaining;
    private int tpRemaining;
    private BukkitTask task;
    private NamespacedKey compassKey;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        compassKey = new NamespacedKey(this, "swap_compass");
        getCommand("manhunt").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
    }

    // ================= Pusula =================

    private ItemStack createCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Yer Degistirme Pusulasi", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Sag tik: avci sec", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Sol tik: yer degis (sure dolunca)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    private void removeCompasses(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isCompass(contents[i])) inv.setItem(i, null);
        }
    }

    private void giveCompass(Player p) {
        removeCompasses(p);
        var left = p.getInventory().addItem(createCompass());
        left.values().forEach(i -> p.getWorld().dropItem(p.getLocation(), i));
    }

    // ================= Komutlar =================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Kullanim: /manhunt baslat <isim> | durdur | pusula", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "baslat" -> {
                if (!sender.isOp() && !sender.hasPermission("manhunt.admin")) {
                    sender.sendMessage(Component.text("Yetkin yok.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Kullanim: /manhunt baslat <kacanin ismi>", NamedTextColor.YELLOW));
                    return true;
                }
                startGame(sender, args[1]);
            }
            case "durdur" -> {
                if (!sender.isOp() && !sender.hasPermission("manhunt.admin")) {
                    sender.sendMessage(Component.text("Yetkin yok.", NamedTextColor.RED));
                    return true;
                }
                if (state == State.IDLE) {
                    sender.sendMessage(Component.text("Aktif oyun yok.", NamedTextColor.RED));
                    return true;
                }
                stopGame(Component.text("Manhunt durduruldu.", NamedTextColor.YELLOW));
            }
            case "pusula" -> {
                // Kacan pusulayi kaybederse yeniden alir
                if (state != State.IDLE && sender instanceof Player p && p.getUniqueId().equals(runnerId)) {
                    giveCompass(p);
                } else {
                    sender.sendMessage(Component.text("Sadece kacan kullanabilir.", NamedTextColor.RED));
                }
            }
            default -> sender.sendMessage(Component.text("Kullanim: /manhunt baslat <isim> | durdur | pusula", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("baslat", "durdur", "pusula").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("baslat")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    // ================= Oyun akisi =================

    private void startGame(CommandSender sender, String name) {
        if (state != State.IDLE) {
            sender.sendMessage(Component.text("Zaten bir oyun var. /manhunt durdur", NamedTextColor.RED));
            return;
        }
        Player runner = Bukkit.getPlayerExact(name);
        if (runner == null) {
            sender.sendMessage(Component.text("Oyuncu bulunamadi: " + name, NamedTextColor.RED));
            return;
        }
        runnerId = runner.getUniqueId();
        if (hunters().isEmpty()) {
            runnerId = null;
            sender.sendMessage(Component.text("En az bir avci gerekli.", NamedTextColor.RED));
            return;
        }

        state = State.COUNTDOWN;
        remaining = SWAP_INTERVAL;
        selectedId = null;
        pendingTarget = null;

        giveCompass(runner);

        runner.showTitle(Title.title(
                Component.text("KACAN SENSIN!", NamedTextColor.GREEN),
                Component.text("Pusulayla avci sec, sure dolunca sol tikla", NamedTextColor.GRAY)));
        for (Player h : hunters()) {
            h.showTitle(Title.title(
                    Component.text("AVCISIN!", NamedTextColor.RED),
                    Component.text("Kacan: " + runner.getName(), NamedTextColor.GRAY)));
        }
        Bukkit.broadcast(Component.text("Manhunt basladi! Kacan: " + runner.getName(), NamedTextColor.GOLD));

        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    private void stopGame(Component message) {
        Player runner = runnerId == null ? null : Bukkit.getPlayer(runnerId);
        if (runner != null) removeCompasses(runner);
        state = State.IDLE;
        runnerId = null;
        selectedId = null;
        pendingTarget = null;
        if (task != null) {
            task.cancel();
            task = null;
        }
        Bukkit.broadcast(message);
    }

    private Player selected() {
        if (selectedId == null) return null;
        Player p = Bukkit.getPlayer(selectedId);
        if (p == null || !p.isOnline() || p.getUniqueId().equals(runnerId) || p.getGameMode() == GameMode.SPECTATOR) {
            selectedId = null;
            return null;
        }
        return p;
    }

    private Component selectedText() {
        Player s = selected();
        return s == null ? Component.text("yok", NamedTextColor.DARK_GRAY) : Component.text(s.getName(), NamedTextColor.YELLOW);
    }

    private void tick() {
        Player runner = runnerId == null ? null : Bukkit.getPlayer(runnerId);
        if (runner == null || !runner.isOnline()) {
            stopGame(Component.text("Kacan oyundan cikti, Manhunt bitti.", NamedTextColor.RED));
            return;
        }

        // Pusula secili avciyi gostersin (ayni dunyadaysa)
        Player sel = selected();
        if (sel != null && sel.getWorld().equals(runner.getWorld())) {
            runner.setCompassTarget(sel.getLocation());
        }

        switch (state) {
            case COUNTDOWN -> {
                remaining--;
                if (remaining <= 0) {
                    state = State.READY;
                    runner.playSound(runner.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
                    runner.showTitle(Title.title(
                            Component.text("YER DEGISIMI HAZIR!", NamedTextColor.GOLD),
                            Component.text("Sol tikladigin anda degisim baslar", NamedTextColor.YELLOW)));
                } else {
                    // Sadece kacan gorur
                    runner.sendActionBar(Component.text("Yer degisimine: ", NamedTextColor.GRAY)
                            .append(Component.text(formatTime(remaining), NamedTextColor.YELLOW))
                            .append(Component.text("  |  Secili: ", NamedTextColor.GRAY))
                            .append(selectedText()));
                }
            }
            case READY -> runner.sendActionBar(Component.text("HAZIR! Sol tik = yer degis  |  Secili: ", NamedTextColor.GREEN)
                    .append(selectedText()));
            case TELEPORTING -> {
                Player target = pendingTarget == null ? null : Bukkit.getPlayer(pendingTarget);
                if (target == null || !target.isOnline()) {
                    pendingTarget = null;
                    selectedId = null;
                    state = State.READY;
                    runner.sendMessage(Component.text("Secilen avci oyundan cikti, yeniden sec ve sol tikla.", NamedTextColor.RED));
                    return;
                }
                tpRemaining--;
                if (tpRemaining <= 0) {
                    performSwap(runner, target);
                } else {
                    showSwapWarning(runner, target, tpRemaining);
                }
            }
            default -> { }
        }
    }

    // ================= Pusula tiklari =================

    private void cycleHunter(Player runner) {
        if (state != State.COUNTDOWN && state != State.READY) return;
        List<Player> hs = hunters();
        if (hs.isEmpty()) {
            runner.sendActionBar(Component.text("Avci yok.", NamedTextColor.RED));
            return;
        }
        int idx = -1;
        for (int i = 0; i < hs.size(); i++) {
            if (hs.get(i).getUniqueId().equals(selectedId)) { idx = i; break; }
        }
        Player next = hs.get((idx + 1) % hs.size());
        selectedId = next.getUniqueId();
        if (next.getWorld().equals(runner.getWorld())) runner.setCompassTarget(next.getLocation());
        runner.playSound(runner.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
        runner.sendActionBar(Component.text("Secili avci: ", NamedTextColor.GRAY)
                .append(Component.text(next.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" (" + ((idx + 1) % hs.size() + 1) + "/" + hs.size() + ")", NamedTextColor.DARK_GRAY)));
    }

    private void confirmSwap(Player runner) {
        if (state == State.COUNTDOWN) {
            runner.sendActionBar(Component.text("Sure henuz dolmadi: " + formatTime(remaining), NamedTextColor.RED));
            return;
        }
        if (state != State.READY) return;
        Player target = selected();
        if (target == null) {
            runner.sendActionBar(Component.text("Once sag tikla bir avci sec!", NamedTextColor.RED));
            return;
        }
        state = State.TELEPORTING;
        pendingTarget = target.getUniqueId();
        tpRemaining = TELEPORT_DELAY;
        showSwapWarning(runner, target, tpRemaining);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (state == State.IDLE || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(runnerId) || !isCompass(e.getItem())) return;

        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            cycleHunter(p);
        } else if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            e.setCancelled(true);
            confirmSwap(p);
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        // Pusulayla bir varliga vurmak da sol tik sayilir
        if (state == State.IDLE || !(e.getDamager() instanceof Player p)) return;
        if (!p.getUniqueId().equals(runnerId) || !isCompass(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        confirmSwap(p);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isCompass(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Oyun disindayken kalan pusulayi temizle
        if (state == State.IDLE || !e.getPlayer().getUniqueId().equals(runnerId)) removeCompasses(e.getPlayer());
    }

    // ================= Yer degistirme =================

    private void showSwapWarning(Player runner, Player target, int seconds) {
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO);

        runner.showTitle(Title.title(
                Component.text(String.valueOf(seconds), NamedTextColor.GOLD),
                Component.text(target.getName() + " ile yer degisiyorsun", NamedTextColor.YELLOW), times));
        runner.playSound(runner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);

        // Avcilar sadece bu 3 saniyede gorur
        for (Player h : hunters()) {
            h.showTitle(Title.title(
                    Component.text("! " + seconds + " !", NamedTextColor.RED),
                    Component.text(runner.getName() + ", " + target.getName() + " ile yer degisiyor", NamedTextColor.GRAY), times));
            h.playSound(h.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        }
    }

    private void performSwap(Player runner, Player target) {
        Location a = runner.getLocation();
        Location b = target.getLocation();

        runner.teleport(b);
        target.teleport(a);
        runner.setFallDistance(0f);
        target.setFallDistance(0f);

        runner.playSound(runner.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);

        Bukkit.broadcast(Component.text("Yer degisti: " + runner.getName() + " <-> " + target.getName(), NamedTextColor.GOLD));

        state = State.COUNTDOWN;
        remaining = SWAP_INTERVAL;
        pendingTarget = null;
    }

    private List<Player> hunters() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(runnerId)) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            list.add(p);
        }
        list.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        return list;
    }

    private String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return m > 0 ? m + " dk " + s + " sn" : s + " sn";
    }

    // ================= Bitis kosullari =================

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (state != State.IDLE && e.getPlayer().getUniqueId().equals(runnerId)) {
            e.getDrops().removeIf(this::isCompass);
            stopGame(Component.text("Avcilar kazandi! Kacan " + e.getPlayer().getName() + " oldu.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent e) {
        if (state == State.IDLE || e.getEntityType() != EntityType.ENDER_DRAGON) return;
        Player runner = Bukkit.getPlayer(runnerId);
        if (runner == null) return;

        // Ejderhayi bir avci oldurduyse sayilmaz
        Player killer = e.getEntity().getKiller();
        if (killer != null && !killer.getUniqueId().equals(runnerId)) return;

        launchFireworks(runner);
        runner.showTitle(Title.title(
                Component.text("KAZANDIN!", NamedTextColor.GREEN),
                Component.text("Ender Dragon oldu", NamedTextColor.GOLD)));
        stopGame(Component.text("Kacan kazandi! " + runner.getName() + " Ender Dragon'u oldurdu.", NamedTextColor.GREEN));
    }

    private void launchFireworks(Player runner) {
        final UUID id = runner.getUniqueId();
        final Location[] last = { runner.getLocation() };
        final int[] count = { 0 };
        final Color[] colors = { Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.WHITE };
        final FireworkEffect.Type[] types = { FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR,
                FireworkEffect.Type.BURST, FireworkEffect.Type.BALL };

        Bukkit.getScheduler().runTaskTimer(this, t -> {
            if (count[0]++ >= 14) {
                t.cancel();
                return;
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) last[0] = p.getLocation();
            World w = last[0].getWorld();
            if (w == null) {
                t.cancel();
                return;
            }
            // Oyuncunun 7-9 blok ustunde patlat (patlama hasari vermesin)
            Location loc = last[0].clone().add(random.nextDouble() * 4 - 2, 7 + random.nextDouble() * 2, random.nextDouble() * 4 - 2);
            Firework fw = w.spawn(loc, Firework.class, f -> {
                FireworkMeta meta = f.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(types[random.nextInt(types.length)])
                        .withColor(colors[random.nextInt(colors.length)], colors[random.nextInt(colors.length)])
                        .withFade(colors[random.nextInt(colors.length)])
                        .flicker(true).trail(true).build());
                meta.setPower(0);
                f.setFireworkMeta(meta);
            });
            fw.detonate();
        }, 0L, 8L);
    }
}
