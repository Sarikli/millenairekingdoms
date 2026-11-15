package com.millenairekingdoms.client;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Dedicated chronicle, with the actual previous management screen retained as its parent. */
final class ChronicleScreen extends Screen {
    final Screen parent;
    final UUID session = UUID.randomUUID();
    UUID scope, selected, next, villageScope;
    String kind = "";
    long from = 0, to = Long.MAX_VALUE, serial;
    private CompoundTag view = new CompoundTag();
    private List<HistoryEvent> events = List.of();
    private final List<UUID> previous = new ArrayList<>();
    private UUID cursor, shownCursor, lastShown;
    private ChronicleRequestPayload pending;
    private int retryTicks;
    private KingdomScreen.RowList entries, prose;
    private int left, top, pw, ph;
    private Object languageState;
    ChronicleScreen(Screen parent, UUID scope, UUID village) {
        super(t("title"));
        this.parent = parent;
        this.scope = scope;
        this.villageScope = village;
    }
    static Component t(String key, Object... args) {
        return ClientText.component("millenairekingdoms.history." + key, args);
    }
    void request(UUID cursor) {
        this.cursor = cursor;
        serial++;
        retryTicks = 0;
        pending = new ChronicleRequestPayload(session, serial, "CHRONICLE", Optional.ofNullable(scope), kind, from, to,
                Optional.ofNullable(cursor), "", Optional.empty(), Optional.ofNullable(villageScope));
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(pending);
    }
    @Override
    public void tick() {
        if (retryTicks > 0 && --retryTicks == 0 && pending != null)
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(pending);
        if (languageState != net.minecraft.locale.Language.getInstance()) {
            languageState = net.minecraft.locale.Language.getInstance();
            rebuildWidgets();
        }
    }
    void refresh() {
        previous.clear();
        request(null);
    }
    void receive(CompoundTag incoming) {
        if (!com.millenairekingdoms.compat.KNbt.getUUID(incoming, "session").equals(session) || incoming.getLongOr("serial", 0L) != serial)
            return;
        if (incoming.getIntOr("retryAfter", 0) > 0) {
            retryTicks = Math.min(20, incoming.getIntOr("retryAfter", 0));
            return;
        }
        pending = null;
        retryTicks = 0;
        view = incoming;
        events = incoming.getListOrEmpty("events").stream().map(t -> HistoryEvent.load((CompoundTag) t)).toList();
        next = com.millenairekingdoms.compat.KNbt.hasUUID(incoming, "next") ? com.millenairekingdoms.compat.KNbt.getUUID(incoming, "next") : null;
        if (events.stream().noneMatch(e -> e.id().equals(selected)))
            selected = events.isEmpty() ? null : events.getFirst().id();
        rebuildWidgets();
    }
    @Override
    protected void init() {
        languageState = net.minecraft.locale.Language.getInstance();
        int entryScroll = entries != null && Objects.equals(shownCursor, cursor) ? entries.scroll : 0;
        int proseScroll = prose != null && Objects.equals(lastShown, selected) ? prose.scroll : 0;
        shownCursor = cursor;
        lastShown = selected;
        pw = Math.min(740, width - 16);
        ph = Math.min(420, height - 16);
        left = (width - pw) / 2;
        top = (height - ph) / 2;
        int third = (pw - 24) / 3;
        button(left + 8, top + 25, third, t("kingdom"),
                () -> { minecraft.gui.setScreen(new ChronicleFilterScreen(this, true)); });
        button(left + 10 + third, top + 25, third, t("world"), () -> {
            scope = null;
            villageScope = null;
            refresh();
        });
        button(left + 12 + 2 * third, top + 25, third, t("atlas"),
                () -> HistoryClient.openAtlas(this, scope, null, null));
        button(left + 8, top + 47, 74, t("filters"), () -> minecraft.gui.setScreen(new ChronicleFilterScreen(this, false)));
        int mainTop = top + 70, bottom = top + ph - 48, listWidth = Math.max(96, (pw - 24) * 2 / 5);
        entries = new KingdomScreen.RowList(left + 8, mainTop, listWidth, Math.max(22, bottom - mainTop), 22);
        for (var e : events) {
            String date = ClientText.component("millenairekingdoms.history.day", e.day()).getString();
            var account = ChronicleNarrator.render(e, scope, ClientText::resolve);
            entries.add(
                    Component.literal(date + " · " + account.heading()), 0xFF493321, e.id().equals(selected), () -> {
                        selected = e.id();
                        rebuildWidgets();
                    });
        }
        prose = new KingdomScreen.RowList(
                left + 16 + listWidth, mainTop, pw - listWidth - 24, Math.max(22, bottom - mainTop));
        var chosen = events.stream().filter(e -> e.id().equals(selected)).findFirst().orElse(null);
        if (scope != null) {
            prose.addWrapped(font,
                    view.contains("founded") ? t("founded", view.getLongOr("founded", 0L)) : t("founding_unknown"),
                    0xFF705237);
            if (view.getBooleanOr("fallen", false))
                prose.addWrapped(font, t("fallen"), 0xFF705237);
        }
        if (chosen != null) {
            var account = ChronicleNarrator.render(chosen, scope, ClientText::resolve);
            prose.addWrapped(font, Component.literal(account.heading()), 0xFF493321);
            prose.addWrapped(font, t("voice", account.perspective(), account.culture()), 0xFF705237);
            prose.addWrapped(font, Component.literal(account.prose()), 0xFF493321);
            for (var realm : chosen.realms())
                prose.add(Component.literal(realm.name()), 0xFF62442B, false,
                        () -> HistoryClient.openAtlas(this, realm.id(), chosen.id(), null));
            for (var place : chosen.places())
                prose.add(t(place.located() ? "place" : "place_hidden", place.name(), place.x(), place.z()), 0xFF705237,
                        false,
                        place.located() ? () -> HistoryClient.openAtlas(this, scope, chosen.id(), place.id()) : null);
        } else
            prose.addWrapped(font, t(view.contains("error") ? "unavailable" : "empty"), 0xFF493321);
        entries.scroll = entryScroll;
        prose.scroll = proseScroll;
        int fy = top + ph - 43, half = (pw - 20) / 2;
        button(left + 8, fy, half, t("previous"), () -> {
            var back = previous.removeLast();
            request(back);
        }).active = !previous.isEmpty();
        button(left + 12 + half, fy, half, t("next"), () -> {
            previous.add(cursor);
            request(next);
        }).active = next != null;
        int by = top + ph - 22;
        button(left + 8, by, 74, Component.translatable("gui.back"), this::onClose);
        button(left + pw - 82, by, 74, t("refresh"), () -> request(cursor));
        int mapWidth = Math.min(120, pw - 172);
        button(left + (pw - mapWidth) / 2, by, mapWidth, t("map_event"),
                () -> HistoryClient.openAtlas(this, scope, chosen.id(), null))
                .active = chosen != null && chosen.places().stream().anyMatch(HistoryEvent.Place::located);
    }
    private String language() {
        return minecraft.options.languageCode;
    }
    private Button button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, w, 18).build());
    }
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
        g.fill(left, top, left + pw, top + ph, 0xFFF0DDB0);
        g.outline(left, top, pw, ph, 0xFF62442B);
    }
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        super.extractRenderState(g, mx, my, tick);
        g.text(font, title, left + 8, top + 9, 0xFF493321, false);
        String context =
                view.getStringOr("name", "") + (view.contains("villageName") ? " · " + view.getStringOr("villageName", "") : "");
        g.text(font, font.plainSubstrByWidth(context, pw - 100), left + 88, top + 52, 0xFF705237, false);
        entries.render(g, font, mx, my);
        prose.render(g, font, mx, my);
    }
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent kEvent, boolean kDouble) {
        double x = kEvent.x(), y = kEvent.y();
        int b = kEvent.button();
        return super.mouseClicked(kEvent, kDouble) || b == 0 && (entries.click(x, y) || prose.click(x, y));
    }
    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        return entries.scroll(x, y, dy) || prose.scroll(x, y, dy) || super.mouseScrolled(x, y, dx, dy);
    }
    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
