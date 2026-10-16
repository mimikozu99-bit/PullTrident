package me.example.pulltrident;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PullTridentPlugin extends JavaPlugin implements Listener {

    private NamespacedKey tridentKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tridentKey = new NamespacedKey(this, "pull_trident");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ---------- Предмет ----------

    private ItemStack createTrident() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Трезубец Притяжения", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                Component.text("Притягивает врага к владельцу", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(tridentKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isPullTrident(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(tridentKey, PersistentDataType.BYTE);
    }

    // ---------- Команда ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Укажите игрока.", NamedTextColor.RED));
            return true;
        }
        target.getInventory().addItem(createTrident())
                .values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Трезубец выдан: " + target.getName(), NamedTextColor.GREEN));
        return true;
    }

    // ---------- Бросок и кулдаун ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        if (!(event.getProjectile() instanceof Trident)) return;
        if (!isPullTrident(event.getItemStack())) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long until = cooldowns.getOrDefault(player.getUniqueId(), 0L);

        if (now < until) {
            event.setCancelled(true);
            double left = (until - now) / 1000.0;
            player.sendActionBar(Component.text(
                    String.format("Кулдаун: %.1f c", left), NamedTextColor.RED));
            Bukkit.getScheduler().runTask(this, player::updateInventory);
            return;
        }

        long cdMs = (long) (getConfig().getDouble("cooldown-seconds", 8) * 1000);
        cooldowns.put(player.getUniqueId(), now + cdMs);
    }

    // ---------- Попадание ----------

    @EventHandler(ignoreCancelled = true)
    public void onHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        if (!isPullTrident(trident.getItemStack())) return;
        if (!(trident.getShooter() instanceof Player shooter)) return;
        if (!(event.getHitEntity() instanceof LivingEntity victim)) return;
        if (victim.equals(shooter)) return;
        if (victim instanceof Player vp && vp.getGameMode() == GameMode.SPECTATOR) return;

        startPull(shooter, victim);
    }

    private void startPull(Player shooter, LivingEntity victim) {
        final double maxSpeed = getConfig().getDouble("pull-speed", 1.5);
        final int maxTicks = getConfig().getInt("max-pull-ticks", 30);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!shooter.isOnline() || shooter.isDead() || victim.isDead() || !victim.isValid()
                        || !shooter.getWorld().equals(victim.getWorld())) {
                    cancel();
                    return;
                }

                Location dest = destination(shooter, victim);
                Vector diff = dest.toVector().subtract(victim.getLocation().toVector());
                boolean arrived = diff.length() < 0.35;

                if (arrived || ticks++ >= maxTicks) {
                    finish(dest);
                    cancel();
                    return;
                }

                // Скорость зависит только от расстояния и конфига, а не от броска
                Vector vel = diff.multiply(0.35);
                if (vel.length() > maxSpeed) vel.normalize().multiply(maxSpeed);
                if (vel.length() < 0.4) vel.normalize().multiply(0.4);
                victim.setVelocity(vel);
            }

            private void finish(Location dest) {
                if (isSafe(dest)) {
                    dest.setYaw(victim.getLocation().getYaw());
                    dest.setPitch(victim.getLocation().getPitch());
                    victim.teleport(dest);
                }
                victim.setVelocity(new Vector(0, 0, 0));
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /** Точка ровно в 1 блоке от игрока по линии игрок -> враг (по горизонтали). */
    private Location destination(Player shooter, LivingEntity victim) {
        Location s = shooter.getLocation();
        Vector dir = victim.getLocation().toVector().subtract(s.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = s.getDirection().setY(0);
            if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        Location dest = s.clone().add(dir);
        dest.setY(s.getY());
        return dest;
    }

    private boolean isSafe(Location loc) {
        return !loc.getBlock().getType().isSolid()
                && !loc.clone().add(0, 1, 0).getBlock().getType().isSolid();
    }
}
