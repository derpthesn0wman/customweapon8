package com.example.weapons;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The chain axe:
 *  - hit the same target N times with FULLY CHARGED swings and they get chained in place for a while
 *  - hit two different people in a row and they get chained together: damage to one is also dealt to the other
 *
 * Config (on the weapon):
 *   chain:
 *     hits, hit-timeout, require-full-charge, duration
 *     damage-link: window, duration, cooldown, share, include-mobs
 */
public class ChainManager implements Listener {

    private static final Color CHAIN_DARK = Color.fromRGB(105, 105, 115);
    private static final Color CHAIN_MID = Color.fromRGB(160, 160, 170);
    private static final Color CHAIN_LIGHT = Color.fromRGB(225, 225, 235);

    /** A partial swing deals 20% + 80% x strength^2 of full damage, so 0.97 means "essentially fully charged". */
    private static final double FULL_CHARGE_RATIO = 0.97;

    private final WeaponPlugin plugin;
    private final WeaponManager manager;
    private final Attribute attackDamage;

    private final Map<UUID, Map<UUID, HitCount>> hitCounts = new HashMap<>(); // attacker -> target -> hits
    private final Map<UUID, Long> chained = new HashMap<>();                   // entity -> chained until (ms)
    private final Map<UUID, Link> links = new HashMap<>();                     // entity -> link (both ends)
    private final Map<UUID, LastHit> lastHits = new HashMap<>();               // attacker -> previous target
    private final Map<UUID, Long> linkCooldowns = new HashMap<>();             // attacker -> next allowed link (ms)
    private boolean mirroring = false;
    private BukkitTask task;

    private static class HitCount {
        int count = 0;
        long last = 0;
    }

    private static class LastHit {
        final UUID target;
        final long time;

        LastHit(UUID target, long time) {
            this.target = target;
            this.time = time;
        }
    }

    private static class Link {
        final UUID a;
        final UUID b;
        final UUID owner;
        final long until;
        final double share;

        Link(UUID a, UUID b, UUID owner, long until, double share) {
            this.a = a;
            this.b = b;
            this.owner = owner;
            this.until = until;
            this.share = share;
        }

        UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    public ChainManager(WeaponPlugin plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.attackDamage = resolveAttribute("GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE");
    }

    /** Attributes were renamed in newer versions, so look them up by name at runtime. */
    private static Attribute resolveAttribute(String... names) {
        for (String name : names) {
            try {
                Object value = Attribute.class.getField(name).get(null);
                if (value instanceof Attribute) return (Attribute) value;
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(chained.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity living) removeRoot(living);
        }
        chained.clear();
        links.clear();
        hitCounts.clear();
        lastHits.clear();
        linkCooldowns.clear();
    }

    // ---------- Axe hits ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (mirroring) return; // mirrored link damage isn't a real hit
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (target.equals(attacker)) return;

        Weapon weapon = manager.getWeapon(attacker.getInventory().getItemInMainHand());
        if (weapon == null || weapon.getChainConfig() == null) return;

        ConfigurationSection cfg = weapon.getChainConfig();
        long now = System.currentTimeMillis();

        countHit(attacker, target, cfg, now, event.getDamage());
        tryLink(attacker, target, cfg.getConfigurationSection("damage-link"), now);
    }

    /** Was this swing fully charged? Partial swings deal visibly less damage than the attacker's full damage. */
    private boolean isFullyCharged(Player attacker, double dealt) {
        if (attackDamage == null) return true; // can't tell on this version - don't block the axe
        AttributeInstance inst = attacker.getAttribute(attackDamage);
        if (inst == null) return true;
        double full = inst.getValue();
        if (full <= 0) return true;
        return dealt >= full * FULL_CHARGE_RATIO;
    }

    /** N fully charged hits on the same target chains them in place. */
    private void countHit(Player attacker, LivingEntity target, ConfigurationSection cfg, long now, double dealt) {
        if (chained.containsKey(target.getUniqueId())) return;

        if (cfg.getBoolean("require-full-charge", true) && !isFullyCharged(attacker, dealt)) {
            actionBar(attacker, "&cWait for a full swing to stack chains");
            return;
        }

        int needed = Math.max(1, cfg.getInt("hits", 10));
        long timeoutMs = (long) (cfg.getDouble("hit-timeout", 8) * 1000);

        HitCount hc = hitCounts
                .computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(target.getUniqueId(), k -> new HitCount());

        if (hc.count > 0 && now - hc.last > timeoutMs) hc.count = 0;
        hc.count++;
        hc.last = now;

        // A small chain-link burst on the target for every stack
        Location mid = target.getLocation().add(0, target.getHeight() / 2.0, 0);
        Fx.ringDust(target.getWorld(), mid, 0.5 + 0.04 * hc.count, Fx.n(10 + hc.count), CHAIN_DARK, CHAIN_LIGHT, 1.2f);
        target.getWorld().playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 0.6f + 0.08f * hc.count);

        if (hc.count >= needed) {
            hc.count = 0;
            chain(attacker, target, cfg.getDouble("duration", 4), now);
        } else {
            actionBar(attacker, "&7Chain: &f" + hc.count + "&7/&f" + needed);
        }
    }

    private void chain(Player attacker, LivingEntity target, double seconds, long now) {
        long until = now + (long) (seconds * 1000);
        chained.put(target.getUniqueId(), until);
        applyRoot(target, until);
        bindEffect(target);

        manager.message(attacker, "chain-bound-attacker", "{target}", target.getName());
        if (target instanceof Player victim) {
            manager.message(victim, "chain-bound-victim", "{player}", attacker.getName());
        }
    }

    /** Hitting two different people in a row (within the window) chains them together. */
    private void tryLink(Player attacker, LivingEntity target, ConfigurationSection cfg, long now) {
        if (cfg == null) return;
        if (!(target instanceof Player) && !cfg.getBoolean("include-mobs", false)) return;

        UUID attackerId = attacker.getUniqueId();
        long windowMs = (long) (cfg.getDouble("window", 5) * 1000);
        LastHit last = lastHits.get(attackerId);

        if (last != null && !last.target.equals(target.getUniqueId()) && now - last.time <= windowMs) {
            Entity previous = Bukkit.getEntity(last.target);
            Long cooldownEnd = linkCooldowns.get(attackerId);
            boolean onCooldown = cooldownEnd != null && now < cooldownEnd;

            if (previous instanceof LivingEntity other && other.isValid() && !other.isDead()
                    && !links.containsKey(other.getUniqueId()) && !links.containsKey(target.getUniqueId())
                    && !onCooldown) {

                long until = now + (long) (cfg.getDouble("duration", 10) * 1000);
                Link link = new Link(other.getUniqueId(), target.getUniqueId(), attackerId, until,
                        cfg.getDouble("share", 1.0));
                links.put(link.a, link);
                links.put(link.b, link);
                linkCooldowns.put(attackerId, now + (long) (cfg.getDouble("cooldown", 15) * 1000));
                lastHits.remove(attackerId);

                linkEffect(other, target);
                manager.message(attacker, "chain-linked-attacker", "{a}", other.getName(), "{b}", target.getName());
                if (other instanceof Player p) {
                    manager.message(p, "chain-linked-victim", "{other}", target.getName());
                }
                if (target instanceof Player p) {
                    manager.message(p, "chain-linked-victim", "{other}", other.getName());
                }
                return;
            }
        }
        lastHits.put(attackerId, new LastHit(target.getUniqueId(), now));
    }

    // ---------- Shared damage ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (mirroring || links.isEmpty()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        Link link = links.get(victim.getUniqueId());
        if (link == null) return;

        double amount = event.getFinalDamage() * link.share;
        if (amount <= 0) return;

        Entity otherEntity = Bukkit.getEntity(link.other(victim.getUniqueId()));
        if (!(otherEntity instanceof LivingEntity other) || !other.isValid() || other.isDead()) {
            endLink(link);
            return;
        }

        Player owner = Bukkit.getPlayer(link.owner);
        mirroring = true;
        ActiveAbilities.TRUE_DAMAGE.add(other.getUniqueId()); // the amount was already reduced once, don't reduce it again
        try {
            other.setNoDamageTicks(0);
            if (owner != null && owner.isOnline()) {
                other.damage(amount, owner);
            } else {
                other.damage(amount);
            }
        } finally {
            ActiveAbilities.TRUE_DAMAGE.remove(other.getUniqueId());
            mirroring = false;
        }

        // Damage visibly travels down the chain from one target to the other
        if (victim.getWorld().equals(other.getWorld())) {
            World w = victim.getWorld();
            Location from = victim.getLocation().add(0, victim.getHeight() / 2.0, 0);
            Location to = other.getLocation().add(0, other.getHeight() / 2.0, 0);
            Fx.line(w, from, to, 0.35, Particle.ELECTRIC_SPARK);
            Fx.burst(w, from, 20, 0.25, Particle.CRIT);
            Fx.burst(w, to, 30, 0.3, Particle.CRIT);
            Fx.ringDust(w, to, 0.6, Fx.n(14), CHAIN_LIGHT, CHAIN_DARK, 1.5f);
            w.playSound(to, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1f, 1.3f);
        }
    }

    // ---------- Keeping chained players in place ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (chained.isEmpty() || !chained.containsKey(event.getPlayer().getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location fixed = to.clone();
            fixed.setX(from.getX());
            fixed.setZ(from.getZ());
            event.setTo(fixed); // can still look around and fall, but can't walk
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!chained.containsKey(event.getPlayer().getUniqueId())) return;
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        chained.remove(id);
        Link link = links.get(id);
        if (link != null) endLink(link);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (chained.remove(id) != null) removeRoot(event.getPlayer());
        hitCounts.remove(id);
        lastHits.remove(id);
        Link link = links.get(id);
        if (link != null) endLink(link);
    }

    // ---------- Periodic: visuals + expiry ----------

    private void tick() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, Long>> it = chained.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Entity e = Bukkit.getEntity(entry.getKey());

            if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                it.remove();
                continue;
            }
            if (now >= entry.getValue()) {
                it.remove();
                removeRoot(living);
                breakEffect(living);
                continue;
            }
            applyRoot(living, entry.getValue()); // keep the effect topped up (milk, etc.)
            chainVisuals(living, now);
        }

        for (Link link : new HashSet<>(links.values())) {
            Entity ea = Bukkit.getEntity(link.a);
            Entity eb = Bukkit.getEntity(link.b);
            boolean ok = ea instanceof LivingEntity && eb instanceof LivingEntity
                    && ea.isValid() && eb.isValid() && !ea.isDead() && !eb.isDead() && now < link.until;
            if (!ok) {
                endLink(link);
                continue;
            }
            linkVisuals((LivingEntity) ea, (LivingEntity) eb);
        }

        // Forget stale hit counts
        long cutoff = now - 60_000L;
        hitCounts.values().removeIf(map -> {
            map.values().removeIf(hc -> hc.last < cutoff);
            return map.isEmpty();
        });
    }

    private void endLink(Link link) {
        links.remove(link.a);
        links.remove(link.b);
        for (UUID id : new UUID[]{link.a, link.b}) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity living && living.isValid()) breakEffect(living);
        }
    }

    // ---------- Root (slowness) ----------

    private void applyRoot(LivingEntity entity, long untilMs) {
        PotionEffectType slow = PotionEffectType.getByKey(NamespacedKey.minecraft("slowness"));
        if (slow == null) return;
        int ticks = (int) Math.max(1, (untilMs - System.currentTimeMillis()) / 50) + 10;
        entity.addPotionEffect(new PotionEffect(slow, ticks, 255, true, false, false));
    }

    private void removeRoot(LivingEntity entity) {
        PotionEffectType slow = PotionEffectType.getByKey(NamespacedKey.minecraft("slowness"));
        if (slow != null) entity.removePotionEffect(slow);
    }

    // ---------- Visuals: a real-looking chain ----------

    /**
     * Draws a chain between two points: oval links that alternate orientation, hanging in a gentle curve
     * (sag), with a spark that travels along it. sag = 0 gives a taut chain.
     */
    private void chainRope(World w, Location from, Location to, double sag) {
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 0.4 || len > 48) return;

        Vector dir = d.clone().normalize();
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.0001) side = new Vector(1, 0, 0);
        side.normalize();
        Vector vertical = side.clone().crossProduct(dir).normalize();

        int links = (int) Math.max(2, Math.min(90, Math.ceil(len / 0.42)));
        int pointsPerLink = Math.max(4, Math.min(8, Fx.n(6)));
        long pulse = (System.currentTimeMillis() / 100) % links;

        for (int i = 0; i <= links; i++) {
            double t = (double) i / links;
            Location c = from.clone().add(dir.clone().multiply(len * t));
            if (sag > 0) c.add(0, -Math.sin(Math.PI * t) * sag, 0);

            Vector plane = (i % 2 == 0) ? vertical : side;
            Color tint = (i % 2 == 0) ? CHAIN_DARK : CHAIN_MID;
            for (int k = 0; k < pointsPerLink; k++) {
                double a = 2 * Math.PI * k / pointsPerLink;
                Vector off = dir.clone().multiply(Math.cos(a) * 0.2).add(plane.clone().multiply(Math.sin(a) * 0.1));
                Fx.dust(w, c.clone().add(off), 1, 0, tint, CHAIN_LIGHT, 1.0f);
            }
            if (i == pulse) Fx.spawn(w, Particle.ELECTRIC_SPARK, c, 3, 0.08, 0.08, 0.08, 0.02);
        }
    }

    /** Dust ring that expands over several ticks. */
    private void dustShockwave(World w, Location c, double maxRadius, int ticks, Color from, Color to, float size) {
        final Location center = c.clone();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double r = maxRadius * t / ticks;
                int pts = Math.min(160, Fx.n((int) Math.max(14, r * 10)));
                Fx.ringDust(w, center, r, pts, from, to, size);
                if (t >= ticks) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** What a chained-in-place target looks like: shackles, chains to the ground, orbiting links, pulses, ash. */
    private void chainVisuals(LivingEntity e, long now) {
        World w = e.getWorld();
        Location base = e.getLocation();
        double h = Math.max(1.0, e.getHeight());
        Location mid = base.clone().add(0, h / 2.0, 0);
        long t = now / 50; // ticks, for animation

        // 1) Shackles: rings at the ankles, waist, chest and neck
        double[] heights = {0.15, h * 0.45, h * 0.7, h * 0.92};
        for (double y : heights) {
            Fx.ringDust(w, base.clone().add(0, y, 0), 0.5, Fx.n(10), CHAIN_DARK, CHAIN_LIGHT, 1.3f);
        }

        // 2) Taut chains from the body down to ground anchors, with a spark at each anchor
        int anchors = 5;
        for (int i = 0; i < anchors; i++) {
            double angle = i * 2 * Math.PI / anchors + Math.PI / 5;
            Location body = base.clone().add(Math.cos(angle) * 0.35, h * 0.5, Math.sin(angle) * 0.35);
            Location anchor = base.clone().add(Math.cos(angle) * 2.0, 0.05, Math.sin(angle) * 2.0);
            chainRope(w, body, anchor, 0);
            Fx.spawn(w, Particle.CRIT, anchor, 2, 0.1, 0.05, 0.1, 0.05);
            Fx.ringDust(w, anchor, 0.25, 6, CHAIN_LIGHT, CHAIN_DARK, 1.2f);
        }

        // 3) Links spiralling up around the target
        for (int s = 0; s < 3; s++) {
            for (int k = 0; k < 5; k++) {
                double a = (t + k) * 0.35 + s * 2 * Math.PI / 3;
                double y = (((t + k) * 0.5) % 10) / 10.0 * h;
                Fx.dust(w, base.clone().add(Math.cos(a) * 0.85, y, Math.sin(a) * 0.85), 1, 0,
                        CHAIN_MID, CHAIN_LIGHT, 1.4f);
            }
        }

        // 4) A pulse ring rolling out along the ground
        double r = 0.6 + (t % 20) / 20.0 * 2.0;
        Fx.ringDust(w, base.clone().add(0, 0.1, 0), r, Fx.n(26), CHAIN_MID, CHAIN_DARK, 1.5f);

        // 5) Ash drifting down, a thin column of smoke, and the odd metallic spark
        Fx.spawn(w, Particle.ASH, mid, Fx.n(6), 0.45, 0.7, 0.45, 0.01);
        Fx.spawn(w, Particle.WHITE_ASH, mid, Fx.n(3), 0.45, 0.7, 0.45, 0.01);
        Fx.spawn(w, Particle.CAMPFIRE_COSY_SMOKE, base.clone().add(0, 0.2, 0), 1, 0.25, 0, 0.25, 0.01);
        if (t % 10 < 5) {
            Fx.spawn(w, Particle.CRIT, mid, 3, 0.4, 0.5, 0.4, 0.1);
        }
    }

    /** A chain hanging between the two linked targets, with cuffs on both of them. */
    private void linkVisuals(LivingEntity a, LivingEntity b) {
        if (!a.getWorld().equals(b.getWorld())) return;
        World w = a.getWorld();
        Location from = a.getLocation().add(0, a.getHeight() * 0.55, 0);
        Location to = b.getLocation().add(0, b.getHeight() * 0.55, 0);

        double sag = Math.min(1.2, from.distance(to) * 0.12);
        chainRope(w, from, to, sag);

        Fx.ringDust(w, from, 0.5, Fx.n(10), CHAIN_DARK, CHAIN_LIGHT, 1.4f);
        Fx.ringDust(w, to, 0.5, Fx.n(10), CHAIN_DARK, CHAIN_LIGHT, 1.4f);
    }

    /** The moment of binding: a flash, a shock of grey dust, a slam ring, and chains whipping out to the ground. */
    private void bindEffect(LivingEntity e) {
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        Location ground = e.getLocation().add(0, 0.15, 0);

        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.dust(w, mid, Fx.n(80), 0.9, CHAIN_DARK, CHAIN_LIGHT, 1.8f);
        Fx.burst(w, mid, 60, 0.35, Particle.CRIT);
        Fx.burst(w, mid, 40, 0.2, Particle.ASH);
        Fx.burst(w, mid, 20, 0.25, Particle.ELECTRIC_SPARK);
        dustShockwave(w, ground, 4.0, 8, CHAIN_DARK, CHAIN_LIGHT, 2f);
        dustShockwave(w, ground, 2.5, 5, CHAIN_LIGHT, CHAIN_DARK, 1.6f);

        // Chains whip out from the target and bury themselves in the ground
        final int anchors = 5;
        final int steps = 6;
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double f = (double) t / steps;
                Location base = e.getLocation();
                double h = Math.max(1.0, e.getHeight());
                for (int i = 0; i < anchors; i++) {
                    double angle = i * 2 * Math.PI / anchors + Math.PI / 5;
                    Location body = base.clone().add(Math.cos(angle) * 0.35, h * 0.5, Math.sin(angle) * 0.35);
                    Location anchor = base.clone().add(Math.cos(angle) * 2.0, 0.05, Math.sin(angle) * 2.0);
                    Location tip = body.clone().add(anchor.toVector().subtract(body.toVector()).multiply(f));
                    chainRope(w, body, tip, 0);
                    if (t >= steps) Fx.burst(w, anchor, 8, 0.15, Particle.CRIT);
                }
                if (t >= steps) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 0.7f);
        w.playSound(mid, Sound.BLOCK_ANVIL_PLACE, 0.8f, 0.8f);
        w.playSound(mid, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.8f, 0.6f);
    }

    /** Two targets get chained together: the chain snaps across from one to the other. */
    private void linkEffect(LivingEntity a, LivingEntity b) {
        for (LivingEntity e : new LivingEntity[]{a, b}) {
            World w = e.getWorld();
            Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
            Fx.spawn(w, Particle.FLASH, mid, 1);
            Fx.burst(w, mid, 40, 0.3, Particle.CRIT);
            Fx.dust(w, mid, Fx.n(40), 0.6, CHAIN_DARK, CHAIN_LIGHT, 1.6f);
            Fx.ringDust(w, mid, 0.9, Fx.n(24), CHAIN_DARK, CHAIN_LIGHT, 1.8f);
            w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 1.0f);
        }
        if (!a.getWorld().equals(b.getWorld())) return;

        World w = a.getWorld();
        final int steps = 5;
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double f = (double) t / steps;
                Location from = a.getLocation().add(0, a.getHeight() * 0.55, 0);
                Location to = b.getLocation().add(0, b.getHeight() * 0.55, 0);
                Location tip = from.clone().add(to.toVector().subtract(from.toVector()).multiply(f));
                chainRope(w, from, tip, Math.min(1.2, from.distance(to) * 0.12) * f);
                if (t >= steps) {
                    w.playSound(to, Sound.BLOCK_ANVIL_PLACE, 0.7f, 1.2f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** The chains shatter: grey dust and ash everywhere, a ring of shards, a metallic clang. */
    private void breakEffect(LivingEntity e) {
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.dust(w, mid, Fx.n(70), 0.8, CHAIN_LIGHT, CHAIN_DARK, 1.6f);
        Fx.burst(w, mid, 60, 0.4, Particle.CRIT);
        Fx.burst(w, mid, 50, 0.25, Particle.ASH);
        dustShockwave(w, e.getLocation().add(0, 0.2, 0), 3.5, 6, CHAIN_LIGHT, CHAIN_DARK, 1.8f);
        w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.2f, 1.5f);
        w.playSound(mid, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.4f);
    }

    private void actionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text)));
    }
}
