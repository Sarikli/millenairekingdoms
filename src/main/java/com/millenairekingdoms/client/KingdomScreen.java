package com.millenairekingdoms.client;

import com.millenairekingdoms.network.KingdomActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * The kingdom screen uses Millénaire's own parchment and the server's snapshot. It decides nothing:
 * buttons only send requests, disabled buttons only mirror what the server reported, and every answer comes back as a
 * new snapshot. Millénaire's own village and chief screens are untouched.
 */
public final class KingdomScreen extends Screen {
    private Object languageState;
    private static final Identifier PARCHMENT =
            Identifier.fromNamespaceAndPath("millenaire", "textures/gui/village_chief.png");
    // Millénaire's parchment palette (PanelRenderHelper).
    static final int TITLE = 0xFF2A1A00, TEXT = 0xFF333333, MUTED = 0xFF6E5B3E, GOOD = 0xFF2B6A1E, BAD = 0xFF8A1C1C,
                     LINE = 0xFFAA8866;
    enum Tab { KINGDOM, MILITARY, DIPLOMACY, ECONOMY, RULERSHIP, CONSTRUCTION, SHIPMENTS }
    private static final List<Tab> MAIN_TABS =
            List.of(Tab.KINGDOM, Tab.MILITARY, Tab.DIPLOMACY, Tab.ECONOMY, Tab.RULERSHIP);
    private static Tab lastTab = Tab.KINGDOM;

    CompoundTag view;
    private Tab tab = lastTab;
    private UUID selectedTarget, selectedSource, selectedKingdom, selectedRequest, joinKingdom, enemyKingdom;
    private String amount = "", kingdomName = "";
    private boolean confirmAbdicate;
    boolean confirmConstructionCancel;
    String selectedConstructionPlan;
    String shipmentQuantity = "";
    UUID selectedShipmentOrder;
    boolean confirmShipmentCancel;
    private int left, top, panelWidth, panelHeight;
    private final List<Text> texts = new ArrayList<>();
    private final List<RowList> lists = new ArrayList<>();
    private EditBox amountBox, nameBox;
    EditBox shipmentQuantityBox;
    /** Scroll positions survive the rebuild after each click or snapshot. */
    private final java.util.Map<String, Integer> scrolls = new java.util.HashMap<>();
    private Tab listTab = tab;

    private record
            Text(int x, int y, Component text, int color, int width, net.minecraft.util.FormattedCharSequence visual) {}

    KingdomScreen(CompoundTag view) {
        super(Component.translatable("millenairekingdoms.gui.title"));
        this.view = view;
    }

    void update(CompoundTag next) {
        if (amountBox != null)
            amount = amountBox.getValue();
        if (nameBox != null)
            kingdomName = nameBox.getValue();
        if (shipmentQuantityBox != null)
            shipmentQuantity = shipmentQuantityBox.getValue();
        var oldShipping = view.getCompoundOrEmpty("shipments");
        var newShipping = next.getCompoundOrEmpty("shipments");
        if (oldShipping.getIntOr("quantity", 0) != newShipping.getIntOr("quantity", 0))
            shipmentQuantity = Integer.toString(newShipping.getIntOr("quantity", 0));
        if (!com.millenairekingdoms.compat.KNbt.hasUUID(oldShipping, "source") || !com.millenairekingdoms.compat.KNbt.hasUUID(newShipping, "source")
                || !com.millenairekingdoms.compat.KNbt.getUUID(oldShipping, "source").equals(com.millenairekingdoms.compat.KNbt.getUUID(newShipping, "source"))) {
            selectedShipmentOrder = null;
            confirmShipmentCancel = false;
        }
        var previous = view.getCompoundOrEmpty("construction");
        var current = next.getCompoundOrEmpty("construction");
        if (!com.millenairekingdoms.compat.KNbt.hasUUID(previous, "village") || !com.millenairekingdoms.compat.KNbt.hasUUID(current, "village")
                || !com.millenairekingdoms.compat.KNbt.getUUID(previous, "village").equals(com.millenairekingdoms.compat.KNbt.getUUID(current, "village"))) {
            selectedConstructionPlan = null;
            confirmConstructionCancel = false;
        }
        var oldOrder = previous.getCompoundOrEmpty("order");
        var newOrder = current.getCompoundOrEmpty("order");
        if (!com.millenairekingdoms.compat.KNbt.hasUUID(oldOrder, "id") || !com.millenairekingdoms.compat.KNbt.hasUUID(newOrder, "id")
                || !com.millenairekingdoms.compat.KNbt.getUUID(oldOrder, "id").equals(com.millenairekingdoms.compat.KNbt.getUUID(newOrder, "id")))
            confirmConstructionCancel = false;
        this.view = next;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        languageState = net.minecraft.locale.Language.getInstance();
        for (int i = 0; i < lists.size(); i++)
            scrolls.put(listTab + ":" + i, lists.get(i).scroll);
        listTab = tab;
        texts.clear();
        lists.clear();
        amountBox = null;
        nameBox = null;
        shipmentQuantityBox = null;
        panelWidth = Math.min(420, width - 16);
        panelHeight = Math.min(250, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int tabWidth = (panelWidth - 24) / MAIN_TABS.size();
        for (int index = 0; index < MAIN_TABS.size(); index++) {
            Tab t = MAIN_TABS.get(index);
            Button b = Button.builder(Component.translatable("millenairekingdoms.gui.tab."
                                              + t.name().toLowerCase(java.util.Locale.ROOT)),
                                     x -> navigate(t))
                               .bounds(left + 12 + index * tabWidth, top + 22, tabWidth - 2, 16)
                               .build();
            b.active = t != tab;
            if (t == Tab.RULERSHIP && isRoyalPage(tab))
                b.setTooltip(Tooltip.create(t("rulership_back")));
            addRenderableWidget(b);
        }
        addRenderableWidget(
                Button.builder(Component.translatable("millenairekingdoms.gui.refresh"),
                              x -> send(KingdomActionPayload.of("REFRESH")))
                        .bounds(left + panelWidth - 72, top + panelHeight - 22, 60, 16)
                        .build());
        addRenderableWidget(
                Button.builder(Component.translatable("millenairekingdoms.history.title"),
                              x -> HistoryClient.open(this, com.millenairekingdoms.compat.KNbt.hasUUID(view, "focus") ? com.millenairekingdoms.compat.KNbt.getUUID(view, "focus") : null))
                        .bounds(left + 12, top + panelHeight - 22, 88, 16)
                        .build());
        if (!view.contains("focus")) {
            noKingdom();
            return;
        }
        switch (tab) {
            case KINGDOM -> kingdomTab();
            case MILITARY -> militaryTab();
            case DIPLOMACY -> diplomacyTab();
            case ECONOMY -> economyTab();
            case RULERSHIP -> rulershipTab();
            case CONSTRUCTION -> KingdomRoyalTabs.constructionTab(this);
            case SHIPMENTS -> KingdomRoyalTabs.shipmentTab(this);
        }
    }

    int x0() {
        return left + 14;
    }
    int y0() {
        return top + 44;
    }
    int contentWidth() {
        return panelWidth - 28;
    }
    int bottom() {
        return top + panelHeight - 28;
    }
    private static boolean isRoyalPage(Tab page) {
        return page == Tab.CONSTRUCTION || page == Tab.SHIPMENTS;
    }
    private void navigate(Tab page) {
        tab = page;
        lastTab = isRoyalPage(page) ? Tab.RULERSHIP : page;
        confirmAbdicate = false;
        confirmConstructionCancel = false;
        confirmShipmentCancel = false;
        rebuildWidgets();
    }
    @Override
    public void onClose() {
        if (isRoyalPage(tab))
            navigate(Tab.RULERSHIP);
        else
            super.onClose();
    }

    void chooseShipmentProduct(String id) {
        send(new KingdomActionPayload("SHIPMENT_PRODUCT", Optional.empty(), Optional.empty(), 0, id));
    }

    // ---------------------------------------------------------------- tabs

    private void noKingdom() {
        int y = y0();
        text(x0(), y, t("none"), MUTED, contentWidth());
        y += 14;
        y = nearbyVillage(x0(), y, contentWidth());
    }

    private void kingdomTab() {
        CompoundTag k = view.getCompoundOrEmpty("kingdom");
        int x = x0(), y = y0(), half = contentWidth() / 2;
        y = line(x, y, t("capital", k.getStringOr("capital", "")), half);
        y = line(x, y, t("culture", ClientText.culture(k.getStringOr("cultureId", ""), k.getStringOr("culture", ""))), half);
        if (k.contains("personality"))
            y = line(x, y, t("personality", t("personality." + k.getStringOr("personality", ""))), half);
        if (k.getBooleanOr("personalityFallback", false))
            y = line(x, y, t("personality_fallback"), half, MUTED);
        y = line(x, y, t("coordinates", k.getIntOr("x", 0), k.getIntOr("y", 0), k.getIntOr("z", 0)), half);
        String ruler = k.getStringOr("ruler", "");
        y = line(x, y, ruler.isEmpty() ? t("ruler_none") : t("ruler", ruler), half);
        y = line(x, y, t("villages", k.getListOrEmpty("members").size()), half);
        y = line(x, y, warLine(), half);
        y = line(x, y, t("income", coins(k.getLongOr("income", 0L))), half);
        if (!k.getBooleanOr("active", false))
            y = line(x, y, t("inactive"), half, BAD);
        CompoundTag rights = view.getCompoundOrEmpty("rights");
        y += 4;
        text(x, y, t("rights"), TITLE, half);
        y += 11;
        if (view.getBooleanOr("isRuler", false))
            y = reason(x, y, rights, "decide", t("rights_ruler"), half);
        else
            y = wrapped(x, y, t("rights_viewer"), half, MUTED, 3);
        int lx = x + half + 6, lw = contentWidth() - half - 6;
        text(lx, y0(), t("members"), TITLE, lw);
        RowList members = list(lx, y0() + 12, lw, bottom() - 24 - (y0() + 12));
        for (Tag tag : k.getListOrEmpty("members")) {
            CompoundTag m = (CompoundTag) tag;
            members.add(m.getBooleanOr("capital", false)
                            ? t("member_capital", m.getStringOr("name", ""), m.getIntOr("level", 0))
                            : t("member", m.getStringOr("name", ""), m.getIntOr("level", 0), m.getLongOr("income", 0L)),
                    TEXT, false, null);
        }
        kingdomSwitcher(bottom() - 18);
    }

    /** Previous / next kingdom and "my kingdom": only changes what is shown. */
    private void kingdomSwitcher(int y) {
        ListTag all = view.getListOrEmpty("kingdoms");
        UUID focus = com.millenairekingdoms.compat.KNbt.getUUID(view, "focus");
        int index = 0;
        for (int i = 0; i < all.size(); i++)
            if (com.millenairekingdoms.compat.KNbt.getUUID(all.getCompoundOrEmpty(i), "id").equals(focus))
                index = i;
        int finalIndex = index;
        if (all.size() > 1) {
            button(x0(), y, 20, t("prev"),
                    b -> view(com.millenairekingdoms.compat.KNbt.getUUID(all.getCompoundOrEmpty((finalIndex - 1 + all.size()) % all.size()), "id")), true, null);
            button(x0() + 22, y, 20, t("next"),
                    b -> view(com.millenairekingdoms.compat.KNbt.getUUID(all.getCompoundOrEmpty((finalIndex + 1) % all.size()), "id")), true, null);
        }
        if (com.millenairekingdoms.compat.KNbt.hasUUID(view, "ruled") && !com.millenairekingdoms.compat.KNbt.getUUID(view, "ruled").equals(focus))
            button(x0() + 46, y, 100, t("my_kingdom"), b -> view(com.millenairekingdoms.compat.KNbt.getUUID(view, "ruled")), true, null);
    }

    private void militaryTab() {
        int x = x0(), y = y0(), w = contentWidth();
        boolean ruler = view.getBooleanOr("isRuler", false);
        CompoundTag rights = view.getCompoundOrEmpty("rights");
        y = line(x, y, warLine(), w);
        if (view.contains("war")) {
            CompoundTag war = view.getCompoundOrEmpty("war");
            y = line(x, y,
                    t(war.getBooleanOr("capitalOpen", false) ? "capital_open" : "capital_closed", war.getStringOr("defender", ""),
                            war.getIntOr("capitalHeld", 0), war.getIntOr("capitalRequired", 0), war.getIntOr("initial", 0)),
                    w);
            y = line(x, y,
                    t("war_progress", war.getIntOr("capitalWins", 0), war.getIntOr("conquered", 0),
                            Math.max(0, view.getLongOr("day", 0L) - war.getLongOr("startDay", 0L)),
                            Math.max(0, view.getLongOr("day", 0L) - war.getLongOr("idleSince", 0L))),
                    w);
        }
        y += 2;
        int listTop = y, listHeight = bottom() - 42 - listTop, half = w / 2;
        if (ruler) {
            text(x, listTop, t("targets"), TITLE, half);
            RowList targets = list(x, listTop + 11, half - 4, listHeight - 11);
            CompoundTag selected = null;
            for (Tag tag : view.getListOrEmpty("targets")) {
                CompoundTag e = (CompoundTag) tag;
                UUID id = com.millenairekingdoms.compat.KNbt.getUUID(e, "id");
                boolean isSelected = id.equals(selectedTarget);
                if (isSelected)
                    selected = e;
                String owner = e.contains("owner") ? e.getStringOr("owner", "") : "";
                MutableComponent row = owner.isEmpty() ? t("target_independent", e.getStringOr("name", ""))
                                                       : t("target_of", e.getStringOr("name", ""), owner);
                if (e.contains("required"))
                    row.append(" " + e.getIntOr("wins", 0) + "/" + e.getIntOr("required", 0));
                if (e.getBooleanOr("capital", false))
                    row.append(" ").append(t("capital_mark"));
                targets.add(row, e.getBooleanOr("ready", false) ? GOOD : TEXT, isSelected, () -> {
                    selectedTarget = id;
                    rebuildWidgets();
                });
            }
            if (targets.rows.isEmpty())
                targets.add(t("no_targets"), MUTED, false, null);
            int rx = x + half + 2, rw = w - half - 2, ry = listTop;
            if (selected != null) {
                ry = line(rx, ry, com.millenairekingdoms.localization.PublicText.of("%s", selected.getStringOr("name", "")),
                        rw, TITLE);
                ry = line(rx, ry, t("target_detail", selected.getIntOr("defence", 0), selected.getIntOr("distance", 0)), rw);
                if (selected.contains("required"))
                    ry = line(rx, ry, t("target_progress", selected.getIntOr("wins", 0), selected.getIntOr("required", 0)), rw);
                if (selected.getLongOr("cooldown", 0L) > 0)
                    ry = line(rx, ry, t("cooldown", selected.getLongOr("cooldown", 0L)), rw, BAD);
                if (selected.contains("status"))
                    ry = wrapped(rx, ry, json(selected.getStringOr("status", "")), rw, BAD, 3);
                else
                    ry = line(rx, ry, t("target_ready"), rw, GOOD);
            } else
                ry = line(rx, ry, t("select_target"), rw, MUTED);
            ry += 2;
            ry = sourceButton(rx, ry, rw);
            long quotedCost = 0L;
            if (selected != null && !rights.contains("order")) {
                CompoundTag effectiveSource = resolveEffectiveSource(selected);
                if (effectiveSource != null && effectiveSource.getIntOr("strength", 0) > 0) {
                    int s = effectiveSource.getIntOr("strength", 0);
                    double dist = Math.hypot((double) effectiveSource.getIntOr("x", 0) - selected.getIntOr("x", 0),
                            (double) effectiveSource.getIntOr("z", 0) - selected.getIntOr("z", 0));
                    int offset = view.contains("expeditionOffset") ? view.getIntOr("expeditionOffset", 0)
                                                                   : com.millenairekingdoms.economy.RoyalEconomyConfig
                                                                             .DEFAULT_EXPEDITION_DISTANCE_OFFSET;
                    int divisor = view.contains("expeditionDivisor") ? view.getIntOr("expeditionDivisor", 0)
                                                                     : com.millenairekingdoms.economy.RoyalEconomyConfig
                                                                               .DEFAULT_EXPEDITION_STRENGTH_DIVISOR;
                    var econSettings = new com.millenairekingdoms.economy.RoyalEconomyConfig.Settings(
                            32, 4, 1, 1, offset, divisor);
                    quotedCost = com.millenairekingdoms.economy.RoyalPrices.expedition(econSettings, s, dist);
                    ry = wrapped(rx, ry,
                            t("expedition_quote", effectiveSource.getStringOr("name", ""), s, Math.round(dist), quotedCost),
                            rw, TEXT, 2);
                    long spendable = view.contains("spendableTreasury") ? view.getLongOr("spendableTreasury", 0L) : -1L;
                    if (spendable < 0)
                        ry = line(rx, ry, t("expedition_treasury_unloaded"), rw, BAD);
                    else if (spendable < quotedCost)
                        ry = line(rx, ry, t("expedition_insufficient", quotedCost, spendable), rw, BAD);
                    else
                        ry = line(rx, ry, t("expedition_treasury", spendable), rw, GOOD);
                }
            }
            if (rights.contains("order")) {
                // A waiting order replaces the start button with "call off" and its status.
                CompoundTag o = rights.getCompoundOrEmpty("order");
                button(rx, ry, rw, t("cancel_raid"),
                        b -> send(KingdomActionPayload.of("CANCEL_RAID")), !o.getBooleanOr("npc", false), null);
                ry += 20;
                ry = wrapped(rx, ry,
                        t("order_line", o.getStringOr("target", ""), o.contains("source") ? o.getStringOr("source", "") : "-",
                                o.getLongOr("expiresIn", 0L) / 20,
                                com.millenairekingdoms.localization.PublicText.error(o.getStringOr("waiting", ""))),
                        rw, MUTED, Math.max(1, (bottom() - 22 - ry) / 10));
            } else {
                final long confirmCost = quotedCost;
                long spendable = view.contains("spendableTreasury") ? view.getLongOr("spendableTreasury", 0L) : -1L;
                CompoundTag effectiveSource = selected == null ? null : resolveEffectiveSource(selected);
                boolean sourceReady = selected != null && effectiveSource != null && !effectiveSource.getBooleanOr("busy", false)
                        && effectiveSource.getBooleanOr("carries", false) && effectiveSource.getIntOr("raiders", 0) > 0
                        && effectiveSource.getIntOr("strength", 0) > 0
                        && selected.getIntOr("defence", 0) < 2 * effectiveSource.getIntOr("strength", 0);
                boolean canAfford = confirmCost <= 0 || (spendable >= 0 && spendable >= confirmCost);
                Component blockHint = selected == null ? t("select_target")
                        : !sourceReady ? t("source_hint")
                        : (confirmCost > 0 && spendable < 0) ? t("expedition_treasury_unloaded")
                        : (confirmCost > 0 && spendable < confirmCost)
                                ? t("expedition_insufficient", confirmCost, spendable)
                                : null;
                button(rx, ry, rw, t("start_raid"),
                        b -> send(KingdomActionPayload.of("RAID", selectedTarget, selectedSource, confirmCost)),
                        selected != null && selected.getBooleanOr("ready", false) && sourceReady && canAfford, blockHint);
                ry += 20;
            }
        } else {
            y = line(x, y, t("ruler_only_raids"), w, MUTED);
            listTop = y;
        }
        int ly = ruler ? listTop + listHeight + 2 : listTop;
        for (Tag tag : view.getListOrEmpty("raids")) {
            CompoundTag r = (CompoundTag) tag;
            if (ly > bottom() - 32)
                break;
            ly = line(x, ly,
                    t(r.getBooleanOr("ours", false) ? "raid_ours" : "raid_theirs", r.getStringOr("attacker", ""),
                            r.getStringOr("target", ""), r.getIntOr("wins", 0), r.getIntOr("required", 0), r.getLongOr("ticks", 0L) / 20),
                    w, r.getBooleanOr("ours", false) ? GOOD : BAD);
        }
        warControls(bottom() - 18);
    }

    private CompoundTag resolveEffectiveSource(CompoundTag target) {
        ListTag sources = view.getListOrEmpty("sources");
        if (selectedSource != null) {
            for (int i = 0; i < sources.size(); i++) {
                CompoundTag s = sources.getCompoundOrEmpty(i);
                if (selectedSource.equals(com.millenairekingdoms.compat.KNbt.getUUID(s, "id")))
                    return s;
            }
            return null;
        }
        if (com.millenairekingdoms.compat.KNbt.hasUUID(target, "autoSource")) {
            UUID autoId = com.millenairekingdoms.compat.KNbt.getUUID(target, "autoSource");
            for (int i = 0; i < sources.size(); i++) {
                CompoundTag s = sources.getCompoundOrEmpty(i);
                if (autoId.equals(com.millenairekingdoms.compat.KNbt.getUUID(s, "id")))
                    return s;
            }
        }
        CompoundTag best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < sources.size(); i++) {
            CompoundTag s = sources.getCompoundOrEmpty(i);
            if (s.getBooleanOr("busy", false) || !s.getBooleanOr("carries", false) || s.getIntOr("raiders", 0) <= 0
                    || s.getIntOr("strength", 0) <= 0 || target.getIntOr("defence", 0) >= 2 * s.getIntOr("strength", 0))
                continue;
            double dx = (double) s.getIntOr("x", 0) - target.getIntOr("x", 0);
            double dz = (double) s.getIntOr("z", 0) - target.getIntOr("z", 0);
            double distSq = dx * dx + dz * dz;
            if (best == null || s.getIntOr("strength", 0) > best.getIntOr("strength", 0)
                    || (s.getIntOr("strength", 0) == best.getIntOr("strength", 0) && distSq < bestDistSq)) {
                best = s;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private int sourceButton(int x, int y, int w) {
        ListTag sources = view.getListOrEmpty("sources");
        Component label = t("source_auto");
        int index = -1;
        for (int i = 0; i < sources.size(); i++)
            if (com.millenairekingdoms.compat.KNbt.getUUID(sources.getCompoundOrEmpty(i), "id").equals(selectedSource))
                index = i;
        if (index >= 0) {
            CompoundTag s = sources.getCompoundOrEmpty(index);
            label = t("source", s.getStringOr("name", ""), s.getIntOr("strength", 0));
        } else
            selectedSource = null;
        int next = index + 1;
        button(x, y, w, label, b -> {
            selectedSource = next >= sources.size() ? null : com.millenairekingdoms.compat.KNbt.getUUID(sources.getCompoundOrEmpty(next), "id");
            rebuildWidgets();
        }, !sources.isEmpty(), t("source_hint"));
        return y + 20;
    }

    /** Declare or request war on a chosen kingdom, and make or offer peace. */
    private void warControls(int y) {
        boolean ruler = view.getBooleanOr("isRuler", false);
        CompoundTag rights = view.getCompoundOrEmpty("rights");
        int x = x0(), w = contentWidth();
        if (view.contains("war")) {
            if (ruler) {
                CompoundTag war = view.getCompoundOrEmpty("war");
                boolean attacking = war.getBooleanOr("attacking", false);
                button(x, y, 150,
                        t(attacking ? "make_peace"
                                        : war.getBooleanOr("peaceOffered", false) ? "peace_offered"
                                                                         : "offer_peace"),
                        b
                        -> send(KingdomActionPayload.of("PEACE")),
                        rights.contains("decideOk") && (attacking || !war.getBooleanOr("peaceOffered", false)),
                        denied(rights, "decide"));
            }
            return;
        }
        List<CompoundTag> others = new ArrayList<>();
        for (Tag tag : view.getListOrEmpty("kingdoms")) {
            CompoundTag k = (CompoundTag) tag;
            if (!com.millenairekingdoms.compat.KNbt.getUUID(k, "id").equals(com.millenairekingdoms.compat.KNbt.getUUID(view, "focus")) && k.getBooleanOr("active", false))
                others.add(k);
        }
        if (others.isEmpty())
            return;
        int index = 0;
        for (int i = 0; i < others.size(); i++)
            if (com.millenairekingdoms.compat.KNbt.getUUID(others.get(i), "id").equals(enemyKingdom))
                index = i;
        CompoundTag enemy = others.get(index);
        enemyKingdom = com.millenairekingdoms.compat.KNbt.getUUID(enemy, "id");
        int next = (index + 1) % others.size();
        Component label =
                t("against", enemy.getStringOr("name", ""), enemy.contains("relation") ? enemy.getIntOr("relation", 0) : "?");
        button(x, y, w - 154, label, b -> {
            enemyKingdom = com.millenairekingdoms.compat.KNbt.getUUID(others.get(next), "id");
            rebuildWidgets();
        }, others.size() > 1, null);
        UUID target = enemyKingdom;
        Component truce = enemy.contains("truce") ? t("truce_until", enemy.getLongOr("truce", 0L)) : null;
        if (ruler)
            button(x + w - 150, y, 150, t("declare_war"),
                    b
                    -> send(KingdomActionPayload.of("DECLARE_WAR", target)),
                    rights.contains("decideOk") && truce == null && !enemy.getBooleanOr("atWar", false),
                    truce != null ? truce : denied(rights, "decide"));
        else
            button(x + w - 150, y, 150, t("request_war"),
                    b
                    -> send(KingdomActionPayload.of("REQUEST_WAR", target)),
                    rights.contains("requestOk") && truce == null && !enemy.getBooleanOr("atWar", false),
                    truce != null ? truce : denied(rights, "request"));
    }

    private void diplomacyTab() {
        int x = x0(), y = y0(), w = contentWidth(), half = w * 11 / 20;
        boolean ruler = view.getBooleanOr("isRuler", false);
        text(x, y, t("known_kingdoms"), TITLE, half);
        RowList kingdoms = list(x, y + 11, half - 4, bottom() - 24 - (y + 11));
        for (Tag tag : view.getListOrEmpty("kingdoms")) {
            CompoundTag k = (CompoundTag) tag;
            UUID id = com.millenairekingdoms.compat.KNbt.getUUID(k, "id");
            boolean self = id.equals(com.millenairekingdoms.compat.KNbt.getUUID(view, "focus"));
            MutableComponent row = com.millenairekingdoms.localization.PublicText.of("%s", k.getStringOr("name", ""));
            if (self)
                row.append(" ").append(t("this_kingdom"));
            else {
                row.append(" · ").append(
                        k.contains("relation") ? t("relation", k.getIntOr("relation", 0)) : t("relation_unknown"));
                if (k.contains("war"))
                    row.append(" · ").append(
                            t(k.getStringOr("war", "").equals("attacking") ? "state_attacking" : "state_defending"));
                else if (k.contains("truce"))
                    row.append(" · ").append(t("truce_until", k.getLongOr("truce", 0L)));
                else if (k.getBooleanOr("atWar", false))
                    row.append(" · ").append(t("state_elsewhere"));
            }
            int color = self                                               ? MUTED
                    : k.contains("war")                                    ? BAD
                    : k.contains("relation") && k.getIntOr("relation", 0) >= 50 ? GOOD
                                                                           : TEXT;
            kingdoms.add(row, color, id.equals(selectedKingdom), () -> {
                selectedKingdom = id;
                rebuildWidgets();
            });
        }
        button(x, bottom() - 18, 110, t("view_kingdom"),
                b -> view(selectedKingdom), selectedKingdom != null, t("select_kingdom"));
        int rx = x + half + 2, rw = w - half - 2, ry = y;
        if (view.contains("war") && view.getCompoundOrEmpty("war").getBooleanOr("peaceOffered", false)) {
            CompoundTag war = view.getCompoundOrEmpty("war");
            ry = line(rx, ry, t("peace_was_offered", war.getStringOr("defender", "")), rw, GOOD);
            if (ruler && war.getBooleanOr("attacking", false)) {
                button(rx, ry, rw, t("accept_peace"), b -> send(KingdomActionPayload.of("PEACE")), true, null);
                ry += 20;
            }
        }
        if (ruler) {
            text(rx, ry, t("applications"), TITLE, rw);
            ry += 11;
            RowList requests = list(rx, ry, rw, 44);
            for (Tag tag : view.getListOrEmpty("requests")) {
                CompoundTag r = (CompoundTag) tag;
                UUID village = com.millenairekingdoms.compat.KNbt.getUUID(r, "village");
                requests.add(r.getBooleanOr("npc", false) ? t("application_npc", r.getStringOr("name", ""))
                                                 : t("application_player", r.getStringOr("name", ""), r.getStringOr("from", "")),
                        TEXT, village.equals(selectedRequest), () -> {
                            selectedRequest = village;
                            rebuildWidgets();
                        });
            }
            if (requests.rows.isEmpty())
                requests.add(t("no_applications"), MUTED, false, null);
            ry += 46;
            UUID request = selectedRequest;
            button(rx, ry, rw / 2 - 1, t("accept"),
                    b -> send(KingdomActionPayload.of("ACCEPT", request)), request != null, t("select_application"));
            button(rx + rw / 2 + 1, ry, rw / 2 - 1, t("refuse"),
                    b -> send(KingdomActionPayload.of("REFUSE", request)), request != null, t("select_application"));
            ry += 22;
        }
        nearbyVillage(rx, ry, rw);
    }

    /** The independent village the player stands in: join a kingdom or found one from an own developed village. */
    private int nearbyVillage(int x, int y, int w) {
        CompoundTag v = view.getCompoundOrEmpty("nearby");
        if (!v.contains("id"))
            return wrapped(x, y, t("no_nearby_village"), w, MUTED, 3);
        String owner = v.getStringOr("owner", "");
        y = wrapped(x, y,
                owner.isEmpty() ? t("nearby_npc", v.getStringOr("name", ""), v.getIntOr("level", 0))
                                : t("nearby_owned", v.getStringOr("name", ""), v.getIntOr("level", 0), owner),
                w, TEXT, 2);
        ListTag kingdoms = v.getListOrEmpty("kingdoms");
        if ((owner.isEmpty() || v.getBooleanOr("own", false)) && !kingdoms.isEmpty()) {
            int index = 0;
            for (int i = 0; i < kingdoms.size(); i++)
                if (com.millenairekingdoms.compat.KNbt.getUUID(kingdoms.getCompoundOrEmpty(i), "id").equals(joinKingdom))
                    index = i;
            CompoundTag k = kingdoms.getCompoundOrEmpty(index);
            joinKingdom = com.millenairekingdoms.compat.KNbt.getUUID(k, "id");
            int next = (index + 1) % kingdoms.size();
            button(x, y, w, t("join_choice", k.getStringOr("name", ""), k.getIntOr("relation", 0)), b -> {
                joinKingdom = com.millenairekingdoms.compat.KNbt.getUUID(kingdoms.getCompoundOrEmpty(next), "id");
                rebuildWidgets();
            }, kingdoms.size() > 1, null);
            y += 20;
            UUID chosen = joinKingdom;
            button(x, y, w, t("join"), b -> send(KingdomActionPayload.of("JOIN", chosen)), true, t("join_hint"));
            y += 20;
        }
        if (v.getBooleanOr("canFound", false)) {
            nameBox = new EditBox(font, x, y, w - 84, 16, t("kingdom_name"));
            nameBox.setMaxLength(64);
            nameBox.setValue(kingdomName);
            nameBox.setHint(t("kingdom_name"));
            addRenderableWidget(nameBox);
            button(x + w - 82, y, 82, t("found"),
                    b
                    -> send(new KingdomActionPayload(
                            "FOUND", Optional.empty(), Optional.empty(), 0, nameBox.getValue().strip())),
                    true, null);
            y += 20;
        }
        return y;
    }

    private void economyTab() {
        CompoundTag k = view.getCompoundOrEmpty("kingdom");
        CompoundTag rights = view.getCompoundOrEmpty("rights");
        int x = x0(), y = y0(), w = contentWidth(), half = w / 2;
        y = line(x, y, t("total_income", coins(k.getLongOr("income", 0L))), w);
        if (!k.contains("coins"))
            y = line(x, y, t("storage_hidden"), w, MUTED);
        else if (!k.getBooleanOr("storageLoaded", false))
            y = line(x, y, t("storage_unloaded"), w, BAD);
        else
            y = line(x, y,
                    t("storage", coins(k.getLongOr("coins", 0L)), k.getIntOr("gold", 0), k.getIntOr("silver", 0), k.getIntOr("bronze", 0)),
                    w);
        y = line(x, y, t("owed", coins(k.getLongOr("owed", 0L))), w, k.getLongOr("owed", 0L) > 0 ? BAD : MUTED);
        y += 2;
        text(x, y, t("village_incomes"), TITLE, half);
        RowList incomes = list(x, y + 11, half - 4, bottom() - (y + 11));
        for (Tag tag : k.getListOrEmpty("members")) {
            CompoundTag m = (CompoundTag) tag;
            incomes.add(m.getBooleanOr("capital", false)
                            ? t("income_capital", m.getStringOr("name", ""), m.getIntOr("level", 0), m.getLongOr("income", 0L))
                            : t("income_row", m.getStringOr("name", ""), m.getIntOr("level", 0), m.getLongOr("income", 0L)),
                    TEXT, false, null);
        }
        int rx = x + half + 2, rw = w - half - 2, ry = y;
        text(rx, ry, t("withdraw_title"), TITLE, rw);
        ry += 12;
        boolean allowed = rights.contains("withdrawOk");
        amountBox = new EditBox(font, rx, ry, rw - 46, 16, t("amount"));
        // portage 26.3 : EditBox.setFilter n'existe plus (saisie vérifiée à l'envoi) — amountBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,9}"));
        amountBox.setValue(amount);
        amountBox.setHint(t("amount"));
        addRenderableWidget(amountBox);
        long all = k.getLongOr("coins", 0L);
        button(rx + rw - 44, ry, 44, t("all"),
                b -> { amountBox.setValue(Long.toString(all)); }, allowed && all > 0, null);
        ry += 20;
        // Preset amounts, never above the stock the server reported.
        long[] presets = {1, 10, 64, 640, 4096};
        int pw = (rw - 4 * 2) / presets.length;
        for (int i = 0; i < presets.length; i++) {
            long preset = presets[i];
            button(rx + i * (pw + 2), ry, pw, Component.literal(coins(preset)),
                    b -> amountBox.setValue(Long.toString(preset)), allowed && preset <= all, null);
        }
        ry += 20;
        button(rx, ry, rw, t("withdraw"), b -> {
            String value = amountBox.getValue();
            long n = value.isEmpty() ? 0 : Long.parseLong(value);
            amount = value;
            send(new KingdomActionPayload("WITHDRAW", Optional.empty(), Optional.empty(), n, ""));
        }, allowed && all > 0, allowed ? t("storage_empty") : denied(rights, "withdraw"));
        ry += 22;
        if (!allowed)
            wrapped(rx, ry, denied(rights, "withdraw"), rw, BAD, 4);
        else
            wrapped(rx, ry, t("withdraw_note"), rw, MUTED, 4);
    }

    private void rulershipTab() {
        CompoundTag k = view.getCompoundOrEmpty("kingdom");
        CompoundTag rights = view.getCompoundOrEmpty("rights");
        int x = x0(), y = y0(), w = contentWidth();
        int half = (w - 4) / 2;
        boolean rulerHere = view.getBooleanOr("isRuler", false);
        button(x, y, half, t("tab.construction"),
                b -> navigate(Tab.CONSTRUCTION), rulerHere, t("construction.reason.RULER_ONLY"));
        button(x + half + 4, y, half, t("tab.shipments"),
                b -> navigate(Tab.SHIPMENTS), rulerHere, t("shipment.reason.RULER_ONLY"));
        var information = list(x, y + 21, w, bottom() - 24 - (y + 21));
        String ruler = k.getStringOr("ruler", "");
        information.addWrapped(font, ruler.isEmpty() ? t("ruler_none") : t("ruler", ruler), TEXT);
        information.addWrapped(font, t("reputation", k.getStringOr("capital", ""), rights.getIntOr("reputation", 0)), TITLE);
        information.addWrapped(font, t("needs_claim", rights.getIntOr("claimNeeds", 0)), TEXT);
        information.addWrapped(font, t("needs_decision", rights.getIntOr("decisionNeeds", 0)), TEXT);
        information.addWrapped(font, t("needs_request", rights.getIntOr("requestNeeds", 0)), TEXT);
        rulershipRight(information, rights, "claim", t("claim_possible"));
        if (rulerHere)
            rulershipRight(information, rights, "decide", t("decisions_possible"));
        rulershipRight(information, rights, "request", t("request_possible"));
        int by = bottom() - 18;
        button(x, by, half, t("claim"),
                b -> send(KingdomActionPayload.of("CLAIM")), rights.contains("claimOk"), denied(rights, "claim"));
        if (rulerHere)
            button(x + half + 4, by, half, t(confirmAbdicate ? "abdicate_confirm" : "abdicate"), b -> {
                if (confirmAbdicate) {
                    confirmAbdicate = false;
                    send(KingdomActionPayload.of("ABDICATE"));
                } else {
                    confirmAbdicate = true;
                    rebuildWidgets();
                }
            }, true, t("abdicate_hint"));
    }
    private void rulershipRight(RowList information, CompoundTag rights, String key, Component allowed) {
        boolean ok = rights.contains(key + "Ok");
        information.addWrapped(font, ok ? allowed : denied(rights, key), ok ? GOOD : BAD);
    }

    // ---------------------------------------------------------------- pieces

    private Component warLine() {
        if (!view.contains("war"))
            return t("war_none");
        CompoundTag war = view.getCompoundOrEmpty("war");
        return war.getBooleanOr("attacking", false) ? t("war_attacking", war.getStringOr("defender", ""))
                                           : t("war_defending", war.getStringOr("attacker", ""));
    }
    /** "ok" text when the server reported the right, else the server's reason in red. */
    private int reason(int x, int y, CompoundTag rights, String key, Component ok, int w) {
        if (rights.contains(key + "Ok"))
            return wrapped(x, y, ok, w, GOOD, 2);
        return wrapped(x, y, denied(rights, key), w, BAD, 2);
    }
    private Component denied(CompoundTag rights, String key) {
        if (!rights.contains(key))
            return null;
        String value = rights.getStringOr(key, "");
        return value.startsWith("{") || value.startsWith("\"") ? json(value) : Component.literal(value);
    }
    private Component json(String value) {
        try {
            return ClientText.numbers(com.millenairekingdoms.compat.KNbt.componentFromJson(value, minecraft.level.registryAccess()));
        } catch (RuntimeException broken) {
            return Component.literal(value);
        }
    }
    static MutableComponent t(String key, Object... args) {
        return ClientText.component("millenairekingdoms.gui." + key, args);
    }
    static String coins(long deniers) {
        long gold = deniers / 4096, rest = deniers % 4096, silver = rest / 64, bronze = rest % 64;
        StringBuilder b = new StringBuilder();
        if (gold > 0)
            b.append(com.millenairekingdoms.localization.LocalizedResources.number(
                             net.minecraft.client.Minecraft.getInstance().options.languageCode, gold))
                    .append("o ");
        if (silver > 0)
            b.append(com.millenairekingdoms.localization.LocalizedResources.number(
                             net.minecraft.client.Minecraft.getInstance().options.languageCode, silver))
                    .append("a ");
        if (bronze > 0 || b.isEmpty())
            b.append(com.millenairekingdoms.localization.LocalizedResources.number(
                             net.minecraft.client.Minecraft.getInstance().options.languageCode, bronze))
                    .append("d");
        return b.toString().strip();
    }
    private void view(UUID kingdom) {
        if (kingdom != null)
            send(KingdomActionPayload.of("VIEW", kingdom));
    }
    void send(KingdomActionPayload request) {
        KingdomClient.send(request);
    }
    void rebuild() {
        rebuildWidgets();
    }
    Font font() {
        return font;
    }
    void openScreen(Screen next) {
        minecraft.gui.setScreen(next);
    }
    <T extends net.minecraft.client.gui.components.events.GuiEventListener& net.minecraft.client.gui.components
                    .Renderable& net.minecraft.client.gui.narration.NarratableEntry> T
    widget(T w) {
        return addRenderableWidget(w);
    }

    int line(int x, int y, Component text, int w) {
        return line(x, y, text, w, TEXT);
    }
    int line(int x, int y, Component text, int w, int color) {
        text(x, y, text, color, w);
        return y + 11;
    }
    int wrapped(int x, int y, Component text, int w, int color, int maxLines) {
        if (text == null)
            return y;
        var parts = font.split(text, w);
        for (int i = 0; i < parts.size() && i < maxLines; i++) {
            var part = parts.get(i);
            texts.add(new Text(x, y, Component.empty(), color, w, part));
            y += 10;
        }
        return y + 1;
    }
    void text(int x, int y, Component text, int color, int width) {
        texts.add(new Text(x, y, text, color, width, null));
    }
    RowList list(int x, int y, int w, int h) {
        RowList l = new RowList(x, y, w, Math.max(12, h));
        l.scroll = scrolls.getOrDefault(tab + ":" + lists.size(), 0);
        lists.add(l);
        return l;
    }
    void button(int x, int y, int w, Component label, Consumer<Button> press, boolean active, Component whyNot) {
        Button b = Button.builder(label, press::accept).bounds(x, y, w, 16).build();
        b.active = active;
        if (!active && whyNot != null)
            b.setTooltip(Tooltip.create(whyNot));
        addRenderableWidget(b);
    }

    // ---------------------------------------------------------------- rendering and input

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, PARCHMENT, left, top, 0.0F, 0.0F, panelWidth, panelHeight, 256, 186, 256, 256);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        CompoundTag k = view.getCompoundOrEmpty("kingdom");
        Component heading = view.contains("focus")
                ? com.millenairekingdoms.localization.PublicText.of("%s", k.getStringOr("name", ""))
                : title;
        if (view.contains("focus") && isRoyalPage(tab))
            heading =
                    t("tab.rulership").append(" · ").append(t("tab." + tab.name().toLowerCase(java.util.Locale.ROOT)));
        g.text(
                font, font.plainSubstrByWidth(heading.getString(), panelWidth - 24), left + 12, top + 9, TITLE, false);
        g.fill(left + 12, top + 40, left + panelWidth - 12, top + 41, LINE);
        for (Text text : texts) {
            var parts = text.visual == null ? font.split(text.text, Math.max(1, text.width)) : List.of(text.visual);
            var visual = parts.isEmpty() ? net.minecraft.util.FormattedCharSequence.EMPTY : parts.getFirst();
            int x = net.minecraft.locale.Language.getInstance().isDefaultRightToLeft()
                    ? text.x + text.width - font.width(visual)
                    : text.x;
            g.text(font, visual, x, text.y, text.color, false);
        }
        for (RowList list : lists)
            list.render(g, font, mouseX, mouseY);
        if (view.contains("message")) {
            // History and Refresh own the footer edges; the reply wraps between them.
            Component message = json(view.getStringOr("message", ""));
            int color = message.getStyle().getColor() == null ? TEXT
                                                              : 0xFF000000 | message.getStyle().getColor().getValue();
            int messageLeft = left + 108;
            var lines = font.split(message, panelWidth - 188);
            int y = top + panelHeight - (lines.size() > 1 ? 24 : 18);
            for (int i = 0; i < Math.min(2, lines.size()); i++)
                g.text(font, lines.get(i), messageLeft, y + i * 10, color, false);
            if (mouseX >= messageLeft && mouseX < left + panelWidth - 80 && mouseY >= top + panelHeight - 26
                    && mouseY < top + panelHeight - 4)
                g.setTooltipForNextFrame(font, List.of(message), Optional.empty(), net.minecraft.world.item.ItemStack.EMPTY, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent kEvent, boolean kDouble) {
        double mouseX = kEvent.x(), mouseY = kEvent.y();
        int button = kEvent.button();
        if (super.mouseClicked(kEvent, kDouble))
            return true;
        for (RowList list : lists)
            if (list.click(mouseX, mouseY))
                return true;
        return false;
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (RowList list : lists)
            if (list.scroll(mouseX, mouseY, scrollY))
                return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override
    public void tick() {
        super.tick();
        if (languageState != net.minecraft.locale.Language.getInstance()) {
            languageState = net.minecraft.locale.Language.getInstance();
            rebuildWidgets();
        }
    }
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A small scrollable list of clickable rows drawn on the parchment. */
    static final class RowList {
        record Row(Component text, int color, boolean selected, Runnable click, net.minecraft.world.item.ItemStack icon,
                net.minecraft.util.FormattedCharSequence visual) {}
        final int x, y, w, h, rowHeight;
        final List<Row> rows = new ArrayList<>();
        int scroll;
        RowList(int x, int y, int w, int h) {
            this(x, y, w, h, 11);
        }
        RowList(int x, int y, int w, int h, int rowHeight) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.rowHeight = rowHeight;
        }
        void add(Component text, int color, boolean selected, Runnable click) {
            rows.add(new Row(
                    ClientText.numbers(text), color, selected, click, net.minecraft.world.item.ItemStack.EMPTY, null));
        }
        void addIcon(Component text, boolean selected, Runnable click, net.minecraft.world.item.ItemStack icon) {
            rows.add(new Row(ClientText.numbers(text), TEXT, selected, click, icon, null));
        }
        void addWrapped(Font font, Component text, int color) {
            if (text == null)
                return;
            for (var part : font.split(ClientText.numbers(text), Math.max(1, w - 10))) {
                // Font.split already supplies visual/BiDi order. Keep that sequence instead of reordering it twice.
                rows.add(
                        new Row(Component.empty(), color, false, null, net.minecraft.world.item.ItemStack.EMPTY, part));
            }
        }
        int visible() {
            return h / rowHeight;
        }
        void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            g.fill(x, y, x + w, y + h, 0x18000000);
            g.outline(x, y, w, h, LINE);
            scroll = Math.max(0, Math.min(scroll, rows.size() - visible()));
            for (int i = 0; i < visible() && scroll + i < rows.size(); i++) {
                Row row = rows.get(scroll + i);
                int ry = y + 1 + i * rowHeight;
                boolean hover =
                        row.click != null && mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowHeight;
                if (row.selected)
                    g.fill(x + 1, ry, x + w - 1, ry + rowHeight, 0x55A07850);
                else if (hover)
                    g.fill(x + 1, ry, x + w - 1, ry + rowHeight, 0x30A07850);
                int indent = row.icon.isEmpty() ? 3 : 23;
                boolean rtl = net.minecraft.locale.Language.getInstance().isDefaultRightToLeft();
                if (!row.icon.isEmpty())
                    g.item(row.icon, rtl ? x + w - 19 : x + 3, ry + 2);
                var parts =
                        row.visual == null ? font.split(row.text, Math.max(1, w - indent - 5)) : List.of(row.visual);
                var visual = parts.isEmpty() ? net.minecraft.util.FormattedCharSequence.EMPTY : parts.getFirst();
                int tx = rtl ? x + w - indent - 2 - font.width(visual) : x + indent;
                g.text(font, visual, tx, ry + (rowHeight - font.lineHeight) / 2, row.color, false);
                if (row.visual == null && font.width(row.text) > w - indent - 5 && mouseX >= x && mouseX < x + w
                        && mouseY >= ry && mouseY < ry + rowHeight)
                    g.setTooltipForNextFrame(font, List.of(row.text), Optional.empty(), net.minecraft.world.item.ItemStack.EMPTY, mouseX, mouseY);
            }
            if (rows.size() > visible()) {
                int bar = Math.max(8, h * visible() / rows.size());
                int by = y + (h - bar) * scroll / Math.max(1, rows.size() - visible());
                g.fill(x + w - 3, by, x + w - 1, by + bar, LINE);
            }
        }
        boolean click(double mouseX, double mouseY) {
            if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h)
                return false;
            int index = scroll + (int) ((mouseY - y - 1) / rowHeight);
            if (index >= 0 && index < rows.size() && rows.get(index).click != null) {
                rows.get(index).click.run();
                return true;
            }
            return true;
        }
        boolean scroll(double mouseX, double mouseY, double delta) {
            if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h)
                return false;
            scroll = Math.max(0, Math.min(rows.size() - visible(), scroll - (int) Math.signum(delta)));
            return true;
        }
    }
}
