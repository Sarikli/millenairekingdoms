package com.millenairekingdoms.client;

import com.millenairekingdoms.atlas.AtlasColors;
import com.millenairekingdoms.atlas.AtlasLabels;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Independent full-screen atlas. No terrain requests, world mutation or external mapping mod. */
final class AtlasScreen extends Screen {
    private Object languageState;
    private record Pending(String mode, UUID kingdom, UUID cursor, int generation) {}
    private final Screen parent;
    private final UUID session = UUID.randomUUID(), event, village;
    private final Map<UUID, CompoundTag> sites = new LinkedHashMap<>(), kingdoms = new LinkedHashMap<>(),
                                         wars = new LinkedHashMap<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Map<Long, Pending> waiting = new HashMap<>();
    private long serial, ticks, lastSent = -100, lastRefresh;
    private int generation, restarts;
    private String revision = "", dimension = "minecraft:overworld", requestedDimension = "", status = "loading";
    private final com.millenairekingdoms.atlas.AtlasSnapshotAssembler assembler =
            new com.millenairekingdoms.atlas.AtlasSnapshotAssembler();
    private boolean hasFrame;
    private CompoundTag info = new CompoundTag(), pin = new CompoundTag();
    private UUID selectedKingdom, selectedVillage;
    private double centerX, centerZ, zoom = .09;
    private boolean centered, showPanel = true, showInfo, dragging;
    private UUID paneRealm;
    private boolean paneInfo;
    private KingdomScreen.RowList rows;
    private int mapLeft, mapTop, mapRight, mapBottom, panelX, panelWidth;
    AtlasScreen(Screen parent, UUID kingdom, UUID event, UUID village) {
        super(t("title"));
        this.parent = parent;
        this.selectedKingdom = kingdom;
        this.event = event;
        this.village = village;
        this.showInfo = kingdom != null;
    }
    static Component t(String key, Object... args) {
        return ClientText.component("millenairekingdoms.atlas." + key, args);
    }
    void refresh() {
        generation++;
        queue.clear();
        waiting.clear();
        restarts = 0;
        status = "loading";
        queue.add(new Pending("ATLAS", null, null, generation));
    }
    @Override
    public void tick() {
        ticks++;
        if (minecraft.level != null && !requestedDimension.equals(minecraft.level.dimension().identifier().toString())) {
            requestedDimension = minecraft.level.dimension().identifier().toString();
            hasFrame = false;
            sites.clear();
            kingdoms.clear();
            wars.clear();
            info = new CompoundTag();
            pin = new CompoundTag();
            centered = false;
            refresh();
        }
        if (languageState != net.minecraft.locale.Language.getInstance()) {
            languageState = net.minecraft.locale.Language.getInstance();
            rebuildWidgets();
        }
        if ((status.equals("ready") || status.equals("empty")) && queue.isEmpty() && waiting.isEmpty()
                && ticks - lastRefresh >= 100)
            refresh();
        if ((status.equals("ready") || status.equals("empty")) && ticks - lastRefresh >= 100 && waiting.isEmpty()
                && !queue.isEmpty() && queue.stream().noneMatch(p -> p.mode.equals("ATLAS"))) {
            queue.addFirst(new Pending("ATLAS", null, null, generation));
            lastRefresh = ticks;
        }
        if (!waiting.isEmpty() && ticks - lastSent >= 200) {
            generation++;
            waiting.clear();
            queue.clear();
            status = "unavailable";
            return;
        }
        if (!waiting.isEmpty() || ticks - lastSent < 5 || queue.isEmpty())
            return;
        lastSent = ticks;
        var p = queue.remove();
        if (p.generation != generation)
            return;
        long id = ++serial;
        waiting.put(id, p);
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new ChronicleRequestPayload(session, id, p.mode, Optional.ofNullable(p.kingdom), "", 0, Long.MAX_VALUE,
                        Optional.ofNullable(p.cursor), "", Optional.ofNullable(event), Optional.ofNullable(village)));
    }
    void receive(CompoundTag view) {
        if (!com.millenairekingdoms.compat.KNbt.hasUUID(view, "session") || !com.millenairekingdoms.compat.KNbt.getUUID(view, "session").equals(session))
            return;
        var pending = waiting.remove(view.getLongOr("serial", 0L));
        if (pending == null || pending.generation != generation)
            return;
        if (view.getIntOr("retryAfter", 0) > 0) {
            lastSent = ticks + Math.max(0, Math.min(200, view.getIntOr("retryAfter", 0)) - 5);
            queue.addFirst(pending);
            return;
        }
        if (view.contains("error")) {
            status = "unavailable";
            return;
        }
        if (pending.mode.equals("INFO")) {
            if (!pending.kingdom.equals(selectedKingdom))
                return;
            var incoming = view.getCompoundOrEmpty("info");
            boolean same = com.millenairekingdoms.compat.KNbt.hasUUID(info, "id") && com.millenairekingdoms.compat.KNbt.getUUID(info, "id").equals(pending.kingdom)
                    && incoming.getStringOr("memberRevision", "").equals(info.getStringOr("memberRevision", ""));
            if (pending.cursor != null && !same) {
                queue.add(new Pending("INFO", selectedKingdom, null, generation));
                return;
            }
            if (same) {
                var oldMembers = info.getListOrEmpty("members");
                var fresh = incoming.getListOrEmpty("members");
                if (pending.cursor != null) {
                    var ids = new HashSet<UUID>();
                    for (var raw : oldMembers)
                        ids.add(com.millenairekingdoms.compat.KNbt.getUUID((CompoundTag) raw, "id"));
                    for (var raw : fresh)
                        if (ids.add(com.millenairekingdoms.compat.KNbt.getUUID((CompoundTag) raw, "id")))
                            oldMembers.add(raw.copy());
                    if (oldMembers.size() > com.millenairekingdoms.atlas.AtlasDiscovery.LIMIT) {
                        status = "unavailable";
                        return;
                    }
                    incoming.put("members", oldMembers);
                } else if (oldMembers.size() > fresh.size()) {
                    incoming.put("members", oldMembers);
                    if (com.millenairekingdoms.compat.KNbt.hasUUID(info, "next"))
                        com.millenairekingdoms.compat.KNbt.putUUID(incoming, "next", com.millenairekingdoms.compat.KNbt.getUUID(info, "next"));
                    else
                        incoming.remove("next");
                }
            }
            info = incoming;
            rebuildWidgets();
            return;
        }

        if (pending.cursor == null && hasFrame && view.getStringOr("revision", "").equals(revision)) {
            pin = view.getCompoundOrEmpty("historicalPin").copy();
            status = sites.isEmpty() ? "empty" : "ready";
            lastRefresh = ticks;
            if (selectedKingdom != null)
                queue.add(new Pending("INFO", selectedKingdom, null, generation));
            return;
        }
        com.millenairekingdoms.atlas.AtlasSnapshotAssembler.Frame frame;
        try {
            frame = assembler.accept(view, pending.cursor);
        } catch (com.millenairekingdoms.atlas.AtlasSnapshotAssembler.Changed changed) {
            if (++restarts > 3) {
                status = "changed";
                return;
            }
            generation++;
            queue.clear();
            waiting.clear();
            queue.add(new Pending("ATLAS", null, null, generation));
            return;
        } catch (IllegalArgumentException malformed) {
            status = "unavailable";
            return;
        }
        if (frame == null) {
            queue.add(new Pending("ATLAS", null, com.millenairekingdoms.compat.KNbt.getUUID(view, "next"), generation));
            return;
        }
        var newSites = frame.settlements();
        var newKingdoms = frame.kingdoms();
        var newWars = frame.wars();
        var newPin = frame.pin();
        if (!dimension.equals(frame.dimension()))
            centered = false;
        sites.clear();
        kingdoms.clear();
        wars.clear();
        newSites.forEach(s -> sites.put(com.millenairekingdoms.compat.KNbt.getUUID(s, "id"), s));
        newKingdoms.forEach(k -> kingdoms.put(com.millenairekingdoms.compat.KNbt.getUUID(k, "id"), k));
        newWars.forEach(w -> wars.put(com.millenairekingdoms.compat.KNbt.getUUID(w, "id"), w));
        pin = newPin;
        revision = frame.revision();
        dimension = frame.dimension();
        hasFrame = true;
        if (!centered) {
            var focus = sites.values()
                                .stream()
                                .filter(site
                                        -> selectedKingdom != null && com.millenairekingdoms.compat.KNbt.hasUUID(site, "kingdom")
                                                && com.millenairekingdoms.compat.KNbt.getUUID(site, "kingdom").equals(selectedKingdom))
                                .sorted(Comparator.comparing(site -> !site.getBooleanOr("capital", false)))
                                .findFirst()
                                .orElse(sites.values().stream().findFirst().orElse(null));
            if (focus != null) {
                centerX = focus.getIntOr("x", 0);
                centerZ = focus.getIntOr("z", 0);
            } else {
                centerX = view.getIntOr("playerX", 0);
                centerZ = view.getIntOr("playerZ", 0);
            }
            if (!pin.isEmpty()) {
                centerX = pin.getIntOr("x", 0);
                centerZ = pin.getIntOr("z", 0);
            }
            centered = true;
        }
        if (selectedVillage != null && sites.containsKey(selectedVillage)) {
            var selected = sites.get(selectedVillage);
            selectedKingdom = com.millenairekingdoms.compat.KNbt.hasUUID(selected, "kingdom") ? com.millenairekingdoms.compat.KNbt.getUUID(selected, "kingdom") : null;
        }
        status = sites.isEmpty() ? "empty" : "ready";
        lastRefresh = ticks;
        rebuildWidgets();
        if (selectedKingdom != null)
            queue.add(new Pending("INFO", selectedKingdom, null, generation));
    }

    private static void collect(Map<UUID, CompoundTag> map, CompoundTag view, String key) {
        for (var raw : view.getListOrEmpty(key)) {
            var tag = (CompoundTag) raw;
            if (com.millenairekingdoms.compat.KNbt.hasUUID(tag, "id"))
                map.put(com.millenairekingdoms.compat.KNbt.getUUID(tag, "id"), tag);
        }
    }
    @Override
    protected void init() {
        requestedDimension = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
        languageState = net.minecraft.locale.Language.getInstance();
        int retainedScroll =
                rows != null && Objects.equals(paneRealm, selectedKingdom) && paneInfo == showInfo ? rows.scroll : 0;
        paneRealm = selectedKingdom;
        paneInfo = showInfo;
        panelWidth = Math.min(210, Math.max(104, width / 3));
        mapLeft = 6;
        mapTop = 46;
        mapRight = width - 6 - (showPanel ? panelWidth + 8 : 0);
        mapBottom = height - 26;
        panelX = mapRight + 8;
        button(6, 6, 54, Component.translatable("gui.back"), this::onClose);
        button(64, 6, 84, ChronicleScreen.t("title"), () -> HistoryClient.open(this, selectedKingdom));
        button(152, 6, 64, ChronicleScreen.t("refresh"), this::refresh);
        button(220, 6, Math.min(94, width - 226), t(showPanel ? "hide_panel" : "show_panel"), () -> {
            showPanel = !showPanel;
            rebuildWidgets();
        });
        rows = null;
        if (!showPanel)
            return;
        int half = (panelWidth - 4) / 2;
        button(panelX, mapTop, half, t("kingdoms"), () -> {
            showInfo = false;
            rebuildWidgets();
        });
        button(panelX + half + 4, mapTop, half, t("information"), () -> {
            showInfo = true;
            rebuildWidgets();
        });
        rows = new KingdomScreen.RowList(
                panelX, mapTop + 23, panelWidth, Math.max(22, mapBottom - mapTop - 69), showInfo ? 11 : 20);
        if (showInfo)
            buildInfo();
        else
            for (var k : kingdoms.values().stream().sorted(Comparator.comparing(k -> k.getStringOr("name", ""))).toList())
                rows.add(com.millenairekingdoms.localization.PublicText.of("%s", k.getStringOr("name", "")),
                        k.getIntOr("color", 0), com.millenairekingdoms.compat.KNbt.getUUID(k, "id").equals(selectedKingdom),
                        () -> selectKingdom(com.millenairekingdoms.compat.KNbt.getUUID(k, "id"), true));
        if (!showInfo)
            rows.add(t("independent"), AtlasColors.NEUTRAL, selectedKingdom == null, () -> {
                selectedKingdom = null;
                selectedVillage = null;
                info = new CompoundTag();
                rebuildWidgets();
            });
        rows.scroll = retainedScroll;
        button(panelX, mapBottom - 41, panelWidth, t(selectedVillage == null ? "chronicle" : "village_history"),
                () -> HistoryClient.open(this, selectedKingdom, selectedVillage));
        if (showInfo && com.millenairekingdoms.compat.KNbt.hasUUID(info, "next"))
            button(panelX, mapBottom - 20, panelWidth, t("more_members"),
                    () -> queue.add(new Pending("INFO", selectedKingdom, com.millenairekingdoms.compat.KNbt.getUUID(info, "next"), generation)));
    }
    private void buildInfo() {
        if (info.isEmpty()) {
            if (selectedVillage != null && sites.containsKey(selectedVillage)) {
                var site = sites.get(selectedVillage);
                line(com.millenairekingdoms.localization.PublicText.of("%s", site.getStringOr("name", "")));
                line(t("independent"));
                line(com.millenairekingdoms.localization.PublicText.of("%s", site.getStringOr("typeName", "")));
            } else
                line(t("select"));
            return;
        }
        line(com.millenairekingdoms.localization.PublicText.of("%s", info.getStringOr("name", "")));
        line(t("culture", ClientText.culture(info.getStringOr("culture", ""), info.getStringOr("cultureName", ""))));
        line(Component.translatable("millenairekingdoms.gui.personality",
                Component.translatable("millenairekingdoms.gui.personality." + info.getStringOr("ethos", ""))));
        line(info.contains("founded") ? ChronicleScreen.t("founded", info.getLongOr("founded", 0L))
                                      : ChronicleScreen.t("founding_unknown"));
        if (info.getBooleanOr("fallen", false))
            line(ChronicleScreen.t("fallen"));
        else {
            line(t("capital", info.getStringOr("capital", "")));
            line(t("members", info.getIntOr("memberCount", 0)));
            line(info.getStringOr("ruler", "").isBlank() ? t("npc_government") : t("ruler", info.getStringOr("ruler", "")));
            line(info.getBooleanOr("war", false) ? t("war", info.getStringOr("opponent", "")) : t("peace"));
            if (info.getBooleanOr("economicRights", false)) {
                line(t("income", info.getLongOr("income", 0L)));
                line(t("owed", info.getLongOr("owed", 0L)));
                line(info.getBooleanOr("storageLoaded", false) ? t("treasury", info.getLongOr("treasury", 0L))
                                                      : t("storage_unloaded"));
            }
            for (var raw : info.getListOrEmpty("members")) {
                var member = (CompoundTag) raw;
                rows.add(com.millenairekingdoms.localization.PublicText.of("%s", member.getStringOr("name", "")), 0xFF493321,
                        false, () -> {
                            centerX = member.getIntOr("x", 0);
                            centerZ = member.getIntOr("z", 0);
                            selectedVillage = com.millenairekingdoms.compat.KNbt.getUUID(member, "id");
                        });
                rows.addWrapped(font,
                        com.millenairekingdoms.localization.PublicText.of("%s", member.getStringOr("typeName", "")),
                        0xFF705237);
            }
        }
    }
    private void line(Component text) {
        rows.addWrapped(font, text, 0xFF493321);
    }
    private Button button(int x, int y, int w, Component text, Runnable action) {
        return addRenderableWidget(Button.builder(text, b -> action.run()).bounds(x, y, Math.max(20, w), 18).build());
    }
    private void selectKingdom(UUID id, boolean center) {
        selectedKingdom = id;
        selectedVillage = null;
        info = new CompoundTag();
        showInfo = true;
        if (center) {
            var capital = sites.values()
                                  .stream()
                                  .filter(s -> com.millenairekingdoms.compat.KNbt.hasUUID(s, "kingdom") && com.millenairekingdoms.compat.KNbt.getUUID(s, "kingdom").equals(id))
                                  .sorted(Comparator.comparing(s -> !s.getBooleanOr("capital", false)))
                                  .findFirst()
                                  .orElse(null);
            if (capital != null) {
                centerX = capital.getIntOr("x", 0);
                centerZ = capital.getIntOr("z", 0);
            }
        }
        queue.add(new Pending("INFO", id, null, generation));
        rebuildWidgets();
    }
    private float sx(double x) {
        return (float) ((mapLeft + mapRight) / 2.0 + (x - centerX) * zoom);
    }
    private float sy(double z) {
        return (float) ((mapTop + mapBottom) / 2.0 + (z - centerZ) * zoom);
    }
    private double wx(double x) {
        return centerX + (x - (mapLeft + mapRight) / 2.0) / zoom;
    }
    private double wz(double y) {
        return centerZ + (y - (mapTop + mapBottom) / 2.0) / zoom;
    }
    private boolean inside(double x, double y) {
        return x >= mapLeft && x < mapRight && y >= mapTop && y < mapBottom;
    }
    private CompoundTag hover(double x, double y) {
        if (!inside(x, y))
            return null;
        CompoundTag nearest = null;
        double best = 144;
        for (var site : sites.values()) {
            double dx = sx(site.getIntOr("x", 0)) - x, dy = sy(site.getIntOr("z", 0)) - y, d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                nearest = site;
            }
        }
        return nearest;
    }
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
        g.fill(0, 0, width, height, 0xFFF0DDB0);
        g.fill(mapLeft, mapTop, mapRight, mapBottom, 0xFFCEC29E);
        g.enableScissor(mapLeft, mapTop, mapRight, mapBottom);
        var labels = new AtlasLabels();
        var visible =
                sites.values()
                        .stream()
                        .filter(site -> inside(sx(site.getIntOr("x", 0)), sy(site.getIntOr("z", 0))))
                        .sorted(Comparator.comparing((CompoundTag site) -> !com.millenairekingdoms.compat.KNbt.getUUID(site, "id").equals(selectedVillage))
                                        .thenComparing(site -> !site.getBooleanOr("capital", false))
                                        .thenComparing(site -> com.millenairekingdoms.compat.KNbt.getUUID(site, "id")))
                        .toList();
        // Reserve every marker first. Labels cannot cover even low-zoom or independent markers.
        for (var site : visible) {
            int x = Math.round(sx(site.getIntOr("x", 0))), y = Math.round(sy(site.getIntOr("z", 0)));
            labels.reserve(new AtlasLabels.Box(x - 12, y - 16, x + 12, y + 13));
        }
        for (var site : visible) {
            int x = Math.round(sx(site.getIntOr("x", 0))), y = Math.round(sy(site.getIntOr("z", 0))),
                color = site.getIntOr("politicalColor", 0);
            boolean capital = site.getBooleanOr("capital", false), selected = com.millenairekingdoms.compat.KNbt.getUUID(site, "id").equals(selectedVillage);
            if (zoom < .025 && !capital && !selected)
                g.fill(x - 3, y - 3, x + 3, y + 3, color);
            else {
                if (selected)
                    g.outline(x - 12, y - 12, 24, 24, 0xFFFFFFFF);
                g.fill(x - 10, y - 10, x + 10, y + 10, color);
                g.outline(x - 10, y - 10, 20, 20, 0xFF1C1915);
                var icon = Identifier.tryParse(site.getStringOr("icon", ""));
                g.item(new ItemStack(icon != null && BuiltInRegistries.ITEM.containsKey(icon)
                                             ? BuiltInRegistries.ITEM.getValue(icon)
                                             : Items.MAP),
                        x - 8, y - 8);
                if (capital) {
                    g.fill(x - 6, y - 13, x + 6, y - 10, color);
                    g.fill(x - 6, y - 15, x - 4, y - 12, color);
                    g.fill(x - 1, y - 16, x + 1, y - 12, color);
                    g.fill(x + 4, y - 15, x + 6, y - 12, color);
                }
            }
        }
        for (var site : visible) {
            boolean selected = com.millenairekingdoms.compat.KNbt.getUUID(site, "id").equals(selectedVillage);
            if (zoom < .025 && !site.getBooleanOr("capital", false) && !selected)
                continue;
            int x = Math.round(sx(site.getIntOr("x", 0))), y = Math.round(sy(site.getIntOr("z", 0)));
            var name = font.plainSubstrByWidth(site.getStringOr("name", ""), 150);
            var realm = font.plainSubstrByWidth(
                    com.millenairekingdoms.compat.KNbt.hasUUID(site, "kingdom") ? site.getStringOr("kingdomName", "") : t("independent").getString(), 150);
            int w = Math.max(font.width(name), font.width(realm)) + 6;
            int left = x + 13, top = y - 10;
            if (left + w > mapRight)
                left = x - 13 - w;
            var box = new AtlasLabels.Box(left, top, left + w, top + 23);
            if (left < mapLeft || top < mapTop || left + w > mapRight || top + 23 > mapBottom || !labels.add(box))
                continue;
            g.fill(left, top, left + w, top + 23, 0xEB201D19);
            g.text(font, name, left + 3, top + 3, 0xFFF8EFDB, false);
            g.text(font, realm, left + 3, top + 13, site.getIntOr("politicalColor", 0), false);
        }
        if (!pin.isEmpty()) {
            int x = Math.round(sx(pin.getIntOr("x", 0))), y = Math.round(sy(pin.getIntOr("z", 0)));
            g.outline(x - 12, y - 12, 24, 24, 0xFFFFFF66);
            g.centeredText(
                    font, t("historical_pin", pin.getStringOr("name", ""), pin.getLongOr("day", 0L)), x, y + 14, 0xFFFFFF66);
        }
        g.disableScissor();
        g.outline(mapLeft, mapTop, mapRight - mapLeft, mapBottom - mapTop, 0xFF62442B);
    }
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        super.extractRenderState(g, mx, my, tick);
        g.text(font,
                net.minecraft.locale.Language.getInstance().getVisualOrder(
                        font.substrByWidth(title, Math.max(10, width - 16))),
                8, 30, 0xFF493321, false);
        g.text(font, font.plainSubstrByWidth(ChronicleScreen.t("current_map").getString(), width - 16), 8,
                height - 17, 0xFF493321, false);
        if (!status.equals("ready"))
            g.text(font, t(status), mapLeft + 8, mapTop + 8, 0xFF493321, false);
        if (rows != null)
            rows.render(g, font, mx, my);
        var hovered = hover(mx, my);
        if (hovered != null)
            g.setTooltipForNextFrame(font,
                    List.of(com.millenairekingdoms.localization.PublicText.of("%s", hovered.getStringOr("name", "")),
                            com.millenairekingdoms.compat.KNbt.hasUUID(hovered, "kingdom") ? Component.literal(hovered.getStringOr("kingdomName", ""))
                                                                 .withColor(hovered.getIntOr("politicalColor", 0))
                                                       : t("independent"),
                            com.millenairekingdoms.localization.PublicText.of("%s", hovered.getStringOr("typeName", "")),
                            t(hovered.getBooleanOr("capital", false) ? "capital_marker" : "village_marker")),
                    Optional.empty(), net.minecraft.world.item.ItemStack.EMPTY, mx, my);
    }
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent kEvent, boolean kDouble) {
        double x = kEvent.x(), y = kEvent.y();
        int button = kEvent.button();
        if (super.mouseClicked(kEvent, kDouble))
            return true;
        if (button == 0 && rows != null && rows.click(x, y))
            return true;
        if (button == 0 && inside(x, y)) {
            var hovered = hover(x, y);
            if (hovered != null) {
                UUID village = com.millenairekingdoms.compat.KNbt.getUUID(hovered, "id");
                if (com.millenairekingdoms.compat.KNbt.hasUUID(hovered, "kingdom"))
                    selectKingdom(com.millenairekingdoms.compat.KNbt.getUUID(hovered, "kingdom"), false);
                else {
                    selectedKingdom = null;
                    info = new CompoundTag();
                    showInfo = true;
                }
                selectedVillage = village;
                rebuildWidgets();
            } else {
                dragging = true;
            }
            return true;
        }
        return false;
    }
    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent kEvent, double dx, double dy) {
        double x = kEvent.x(), y = kEvent.y();
        int button = kEvent.button();
        if (button == 0 && dragging) {
            centerX -= dx / zoom;
            centerZ -= dy / zoom;
            return true;
        }
        return super.mouseDragged(kEvent, dx, dy);
    }
    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent kEvent) {
        double x = kEvent.x(), y = kEvent.y();
        int button = kEvent.button();
        dragging = false;
        return super.mouseReleased(kEvent);
    }
    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (rows != null && rows.scroll(x, y, dy))
            return true;
        if (inside(x, y)) {
            double beforeX = wx(x), beforeZ = wz(y);
            zoom = Math.max(.01, Math.min(4, zoom * Math.pow(1.2, dy)));
            centerX = beforeX - (x - (mapLeft + mapRight) / 2.0) / zoom;
            centerZ = beforeZ - (y - (mapTop + mapBottom) / 2.0) / zoom;
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }
    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
    Screen previous() {
        return parent;
    }
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
