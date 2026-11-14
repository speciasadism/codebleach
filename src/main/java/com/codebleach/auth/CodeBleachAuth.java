package com.codebleach.auth;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeBleachAuth extends JavaPlugin implements Listener {
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> tries = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private HttpClient http;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        getServer().getPluginManager().registerEvents(this, this);
        // keep passwords out of the server log
        ((org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger()).addFilter(new AbstractFilter() {
            @Override
            public Filter.Result filter(LogEvent e) {
                String m = e.getMessage().getFormattedMessage().toLowerCase();
                boolean hit = m.contains("issued server command: /login") || m.contains("issued server command: /l ")
                        || m.contains("issued server command: /register") || m.contains("issued server command: /reg ");
                return hit ? Filter.Result.DENY : Filter.Result.NEUTRAL;
            }
        });
    }

    private boolean locked(Player p) { return !authed.contains(p.getUniqueId()); }
    private static void say(Player p, String s) { p.sendActionBar(Component.text(s, NamedTextColor.GRAY)); }
    private static String ip(Player p) { return p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress(); }
    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private String base() { String s = getConfig().getString("site-url", ""); return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String s = sessions.get(p.getName().toLowerCase());
        if (s != null) {
            String[] x = s.split("\\|");
            if (x[0].equals(ip(p)) && Long.parseLong(x[1]) > System.currentTimeMillis()) { authed.add(p.getUniqueId()); return; }
        }
        p.showTitle(Title.title(
                Component.text("\u2726 ", NamedTextColor.AQUA).append(Component.text("CODE", NamedTextColor.WHITE))
                        .append(Component.text("/", NamedTextColor.RED)).append(Component.text("BLEACH", NamedTextColor.WHITE)),
                Component.text("/register <password> <password>  or  /login <password>", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMinutes(10), Duration.ofMillis(300))));
        long ticks = getConfig().getLong("login-timeout-seconds", 60) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && locked(p)) p.kick(Component.text("Login timed out."));
        }, ticks);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { authed.remove(e.getPlayer().getUniqueId()); tries.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!locked(e.getPlayer())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t != null && (f.getBlockX() != t.getBlockX() || f.getBlockY() != t.getBlockY() || f.getBlockZ() != t.getBlockZ())) e.setTo(f);
    }
    @EventHandler public void onInteract(PlayerInteractEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onChat(AsyncChatEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler public void onHit(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && locked(p)) e.setCancelled(true); }

    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String c = e.getMessage().toLowerCase().split(" ")[0];
        if (!c.equals("/login") && !c.equals("/l") && !c.equals("/register") && !c.equals("/reg")) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p) || !locked(p)) return true;
        if (c.getName().equalsIgnoreCase("register")) {
            if (a.length != 2) { say(p, "Usage: /register <password> <password>"); return true; }
            if (!a[0].equals(a[1])) { say(p, "Passwords do not match."); return true; }
            call(p, "/api/mc/register", a[0], (code, body) -> {
                if (code == 200) ok(p);
                else if (code == 409) say(p, "Already registered. Use /login.");
                else if (code == 403) say(p, "You are not whitelisted.");
                else if (code == 400) say(p, errText(body));
                else fail(p, code);
            });
            return true;
        }
        if (a.length != 1) { say(p, "Usage: /login <password>"); return true; }
        call(p, "/api/mc/verify", a[0], (code, body) -> {
            if (code == 200) ok(p);
            else if (code == 401) wrong(p);
            else if (code == 429) say(p, "Too many attempts. Try again later.");
            else fail(p, code);
        });
        return true;
    }

    private void call(Player p, String path, String password, BiConsumer<Integer, String> done) {
        String json = "{\"username\":\"" + esc(p.getName()) + "\",\"password\":\"" + esc(password) + "\"}";
        HttpRequest rq = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json").header("x-api-key", getConfig().getString("api-key", ""))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        http.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((r, ex) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (p.isOnline()) done.accept(r == null ? -1 : r.statusCode(), r == null ? "" : r.body());
                }));
    }

    private void ok(Player p) {
        UUID id = p.getUniqueId();
        authed.add(id);
        tries.remove(id);
        long min = getConfig().getLong("session-minutes", 10);
        if (min > 0) sessions.put(p.getName().toLowerCase(), ip(p) + "|" + (System.currentTimeMillis() + min * 60000L));
        p.clearTitle();
        say(p, "\u2726 Logged in.");
    }

    private void wrong(Player p) {
        int max = getConfig().getInt("max-attempts", 5), n = tries.merge(p.getUniqueId(), 1, Integer::sum);
        if (n >= max) p.kick(Component.text("Too many wrong passwords."));
        else say(p, "Wrong password, or you have not registered.");
    }

    private void fail(Player p, int code) {
        say(p, "Login server unavailable. Try again shortly.");
        if (code == 403) getLogger().warning("Website rejected the api-key. Check api-key and MC_KEY.");
    }

    private static String errText(String body) {
        Matcher m = Pattern.compile("\"error\":\"([^\"]*)\"").matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "Could not register.";
    }
}
