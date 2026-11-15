package com.millenairekingdoms.client;

import static com.millenairekingdoms.client.KingdomScreen.BAD;
import static com.millenairekingdoms.client.KingdomScreen.MUTED;
import static com.millenairekingdoms.client.KingdomScreen.TEXT;
import static com.millenairekingdoms.client.KingdomScreen.TITLE;
import static com.millenairekingdoms.client.KingdomScreen.coins;
import static com.millenairekingdoms.client.KingdomScreen.t;

import com.millenairekingdoms.network.KingdomActionPayload;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Royal construction and shipment sub-page layouts for {@link KingdomScreen}.
 */
final class KingdomRoyalTabs {
    private KingdomRoyalTabs() {}

    static void constructionTab(KingdomScreen screen) {
        int x = screen.x0(), y = screen.y0(), w = screen.contentWidth(), half = w * 11 / 20;
        var c = screen.view.getCompoundOrEmpty("construction");
        var villages = c.getListOrEmpty("villages");
        if (!com.millenairekingdoms.compat.KNbt.hasUUID(c, "village") || villages.isEmpty()) {
            screen.wrapped(x, y, constructionReason(c.getStringOr("reason", "")), w, BAD, 5);
            return;
        }
        int index = 0;
        for (int n = 0; n < villages.size(); n++)
            if (com.millenairekingdoms.compat.KNbt.getUUID(villages.getCompoundOrEmpty(n), "id").equals(com.millenairekingdoms.compat.KNbt.getUUID(c, "village")))
                index = n;
        var village = villages.getCompoundOrEmpty(index);
        var next = villages.getCompoundOrEmpty((index + 1) % villages.size());
        Component villageLabel = t("construction.village", village.getStringOr("name", ""));
        if (!c.getBooleanOr("available", false))
            villageLabel = Component.empty().append(villageLabel).append(" · ").append(t("construction.unavailable"));
        screen.button(x, y, w, villageLabel,
                b
                -> screen.send(KingdomActionPayload.of("CONSTRUCTION_SELECT", com.millenairekingdoms.compat.KNbt.getUUID(next, "id"))),
                villages.size() > 1, null);
        y += 21;
        screen.text(x, y, t("construction.plans"), TITLE, half - 4);
        int rx = x + half + 2, rw = w - half - 2;
        screen.text(rx, y, t("construction.current"), TITLE, rw);
        var plans = c.getListOrEmpty("plans");
        CompoundTag selected = null;
        for (Tag raw : plans) {
            var plan = (CompoundTag) raw;
            if (plan.getStringOr("key", "").equals(screen.selectedConstructionPlan))
                selected = plan;
        }
        if (selected == null && !plans.isEmpty()) {
            selected = plans.getCompoundOrEmpty(0);
            screen.selectedConstructionPlan = selected.getStringOr("key", "");
        }
        int listHeight = Math.max(33, screen.bottom() - y - 63);
        var choices = screen.list(x, y + 11, half - 4, listHeight);
        for (Tag raw : plans) {
            var plan = (CompoundTag) raw;
            String id = plan.getStringOr("key", "");
            choices.add(Component.empty().append(constructionPlanName(plan)).append(" · " + coins(plan.getLongOr("fee", 0L))),
                    plan.getBooleanOr("canOrder", false) ? TEXT : MUTED, id.equals(screen.selectedConstructionPlan), () -> {
                        screen.selectedConstructionPlan = id;
                        screen.rebuild();
                    });
        }
        if (selected != null) {
            var plan = selected;
            var purchase =
                    Button.builder(t(plan.getBooleanOr("upgrade", false)
                                                   ? (c.getBooleanOr("naturalPending", false) ? "construction.queue_upgrade"
                                                                                     : "construction.upgrade_order")
                                                   : (c.getBooleanOr("naturalPending", false) ? "construction.queue_order"
                                                                                     : "construction.order"),
                                           coins(plan.getLongOr("fee", 0L))),
                                  b
                                  -> screen.send(new KingdomActionPayload("CONSTRUCTION_ORDER",
                                          Optional.of(com.millenairekingdoms.compat.KNbt.getUUID(c, "village")), Optional.of(com.millenairekingdoms.compat.KNbt.getUUID(plan, "offer")), 0,
                                          plan.getStringOr("plan", ""))))
                            .bounds(x, y + listHeight + 14, half - 4, 16)
                            .build();
            purchase.active = plan.getBooleanOr("canOrder", false);
            Component tip = constructionPlanName(plan);
            if (!purchase.active)
                tip = Component.empty().append(tip).append("\n").append(constructionReason(plan.getStringOr("reason", "")));
            else if (c.getBooleanOr("naturalPending", false))
                tip = Component.empty().append(tip).append("\n").append(t("construction.waiting_note"));
            purchase.setTooltip(Tooltip.create(tip));
            screen.widget(purchase);
        } else
            screen.wrapped(x, y + listHeight + 14, constructionReason(c.getStringOr("reason", "")), half - 4, MUTED, 2);
        int ry = y + 11;
        var order = c.getCompoundOrEmpty("order");
        if (com.millenairekingdoms.compat.KNbt.hasUUID(order, "id")) {
            ry = screen.line(rx, ry, ClientText.building(order.getStringOr("plan", ""), order.getStringOr("name", "")), rw);
            ry = screen.line(rx, ry, t("construction.stage." + order.getStringOr("stage", "")), rw, TITLE);
            if (!order.getStringOr("blocked", "").equals("NONE"))
                ry = screen.wrapped(rx, ry, t("construction.blocked." + order.getStringOr("blocked", "")), rw, BAD, 2);
            if (order.contains("progress"))
                ry = screen.line(rx, ry, t("construction.progress", order.getIntOr("progress", 0)), rw);
            ry = screen.line(rx, ry, t("construction.price", coins(order.getLongOr("fee", 0L))), rw);
            screen.button(rx, ry, rw,
                    t(screen.confirmConstructionCancel ? "construction.confirm_cancel" : "construction.cancel"), b -> {
                        if (screen.confirmConstructionCancel) {
                            screen.confirmConstructionCancel = false;
                            screen.send(KingdomActionPayload.of("CONSTRUCTION_CANCEL", com.millenairekingdoms.compat.KNbt.getUUID(order, "id")));
                        } else {
                            screen.confirmConstructionCancel = true;
                            screen.rebuild();
                        }
                    }, order.getBooleanOr("canCancel", false), t("construction.cancel_unavailable"));
            ry += 20;
            if (order.getBooleanOr("canResume", false))
                screen.button(rx, ry, rw, t("construction.resume"),
                        b
                        -> screen.send(KingdomActionPayload.of("CONSTRUCTION_RESUME", com.millenairekingdoms.compat.KNbt.getUUID(order, "id"))),
                        true, null);
        } else {
            ry = screen.wrapped(rx, ry,
                    t(c.getBooleanOr("stateKnown", false) ? "construction.no_order" : "construction.order_unknown"), rw, MUTED,
                    2);
            if (c.contains("reason"))
                screen.wrapped(rx, ry, constructionReason(c.getStringOr("reason", "")), rw, BAD, 4);
        }
        screen.wrapped(x, screen.bottom() - 25,
                t(order.getStringOr("stage", "").equals("WAITING") || !com.millenairekingdoms.compat.KNbt.hasUUID(order, "id") && c.getBooleanOr("naturalPending", false)
                                ? "construction.waiting_note"
                                : order.getBooleanOr("refundable", false) ? "construction.refund_note"
                                                                 : "construction.patronage_note"),
                w, MUTED, 2);
    }

    private static Component constructionPlanName(CompoundTag plan) {
        return plan.getBooleanOr("upgrade", false)
                ? t("construction.upgrade_choice", ClientText.building(plan.getStringOr("plan", ""), plan.getStringOr("name", "")),
                          plan.getIntOr("targetLevel", 0) + 1, plan.getIntOr("x", 0), plan.getIntOr("z", 0))
                : ClientText.building(plan.getStringOr("plan", ""), plan.getStringOr("name", ""));
    }

    private static Component constructionReason(String reason) {
        return t("construction.reason." + (reason.isEmpty() ? "NO_ELIGIBLE_PLAN" : reason));
    }

    static void shipmentTab(KingdomScreen screen) {
        int x = screen.x0(), y = screen.y0(), w = screen.contentWidth(), lw = w / 2 - 3, rx = x + w / 2 + 3,
            rw = w / 2 - 3;
        var s = screen.view.getCompoundOrEmpty("shipments");
        var villages = s.getListOrEmpty("villages");
        if (!com.millenairekingdoms.compat.KNbt.hasUUID(s, "source") || !com.millenairekingdoms.compat.KNbt.hasUUID(s, "target") || villages.size() < 2) {
            screen.wrapped(x, y, shipmentReason(s.getStringOr("reason", "")), w, BAD, 5);
            return;
        }
        shipmentVillage(screen, x, y, w, s, villages, true);
        shipmentVillage(screen, x, y + 20, w, s, villages, false);
        y += 42;
        var products = s.getListOrEmpty("products");
        if (!products.isEmpty()) {
            shipmentButton(screen, x, y, lw, shipmentItem(s.getStringOr("product", "")),
                    b
                    -> screen.openScreen(new ShipmentProductScreen(screen, products, s.getStringOr("product", ""))),
                    true,
                    Component.empty()
                            .append(t("shipment.choose_product"))
                            .append("\n")
                            .append(shipmentStockDetails(s) == null ? Component.empty() : shipmentStockDetails(s)));
        } else
            screen.text(x, y, t("shipment.no_goods"), MUTED, lw);
        int sy = y + 20;
        if (screen.shipmentQuantity.isEmpty())
            screen.shipmentQuantity = Integer.toString(s.getIntOr("quantity", 0));
        screen.shipmentQuantityBox =
                new EditBox(screen.font(), x, sy, Math.max(30, lw - 85), 16, t("shipment.quantity"));
        screen.shipmentQuantityBox.setMaxLength(6);
        // portage 26.3 : EditBox.setFilter n'existe plus (saisie vérifiée à l'envoi) — screen.shipmentQuantityBox.setFilter(value -> value.matches("[0-9]*"));
        screen.shipmentQuantityBox.setValue(screen.shipmentQuantity);
        screen.widget(screen.shipmentQuantityBox);
        shipmentButton(screen, x + lw - 81, sy, 81, t("shipment.set_quantity"), b -> {
            String value = screen.shipmentQuantityBox.getValue();
            long quantity = value.isEmpty() ? 0 : Long.parseLong(value);
            screen.send(
                    new KingdomActionPayload("SHIPMENT_QUANTITY", Optional.empty(), Optional.empty(), quantity, ""));
        }, true, t("shipment.quantity_hint", s.getIntOr("maximumQuantity", 0)));
        sy += 20;
        if (s.contains("physical")) {
            sy = screen.line(x, sy, t("shipment.stock_summary", s.getIntOr("physical", 0), s.getIntOr("retained", 0)), lw);
            sy = screen.line(x, sy, t("shipment.available", s.getIntOr("available", 0)), lw);
        }
        if (com.millenairekingdoms.compat.KNbt.hasUUID(s, "offer")) {
            sy = screen.line(x, sy, t("shipment.price", coins(s.getLongOr("fee", 0L))), lw);
            sy = screen.line(x, sy, t("shipment.time", seconds(s.getLongOr("travelTicks", 0L))), lw);
            var purchase =
                    Button.builder(t("shipment.order"),
                                  b -> screen.send(KingdomActionPayload.of("SHIPMENT_ORDER", com.millenairekingdoms.compat.KNbt.getUUID(s, "offer"))))
                            .bounds(x, sy + 2, lw, 16)
                            .build();
            purchase.active =
                    s.getBooleanOr("canOrder", false) && screen.shipmentQuantity.equals(Integer.toString(s.getIntOr("quantity", 0)));
            purchase.setTooltip(
                    Tooltip.create(s.getBooleanOr("canOrder", false) ? t("shipment.apply_quantity") : shipmentReason(s)));
            screen.widget(purchase);
            screen.shipmentQuantityBox.setResponder(value -> {
                screen.shipmentQuantity = value;
                purchase.active = s.getBooleanOr("canOrder", false) && value.equals(Integer.toString(s.getIntOr("quantity", 0)));
            });
            sy += 22;
        }
        if (s.contains("reason"))
            screen.wrapped(x, sy, shipmentReason(s), lw, BAD, Math.max(1, (screen.bottom() - sy) / 11));
        var orders = s.getListOrEmpty("orders");
        if (orders.isEmpty()) {
            screen.wrapped(rx, y, t(s.getBooleanOr("stateKnown", false) ? "shipment.no_order" : "shipment.order_unknown"), rw,
                    MUTED, 4);
            return;
        }
        int index = 0;
        for (int n = 0; n < orders.size(); n++)
            if (com.millenairekingdoms.compat.KNbt.getUUID(orders.getCompoundOrEmpty(n), "id").equals(screen.selectedShipmentOrder))
                index = n;
        var order = orders.getCompoundOrEmpty(index);
        screen.selectedShipmentOrder = com.millenairekingdoms.compat.KNbt.getUUID(order, "id");
        var next = orders.getCompoundOrEmpty((index + 1) % orders.size());
        shipmentButton(screen, rx, y, rw, t("shipment.to", order.getStringOr("destination", "")), b -> {
            screen.selectedShipmentOrder = com.millenairekingdoms.compat.KNbt.getUUID(next, "id");
            screen.confirmShipmentCancel = false;
            screen.rebuild();
        }, orders.size() > 1, shipmentItem(order.getStringOr("product", "")));
        int ry = y + 20;
        ry = screen.line(rx, ry, t("shipment.stage." + order.getStringOr("stage", "")), rw, TITLE);
        if (!order.getStringOr("blocked", "").equals("NONE"))
            ry = screen.wrapped(rx, ry, t("shipment.blocked." + order.getStringOr("blocked", "")), rw, BAD, 2);
        ry = screen.line(rx, ry, t("shipment.delivered", order.getIntOr("delivered", 0), order.getIntOr("quantity", 0)), rw);
        ry = screen.line(rx, ry, t("shipment.held_returned", order.getIntOr("held", 0), order.getIntOr("returned", 0)), rw);
        if (order.getStringOr("stage", "").equals("IN_TRANSIT"))
            ry = screen.line(rx, ry, t("shipment.remaining", seconds(order.getLongOr("remainingTicks", 0L))), rw);
        shipmentButton(screen, rx, ry + 2, rw,
                t(screen.confirmShipmentCancel ? "shipment.confirm_cancel" : "shipment.cancel"),
                b
                -> {
                    if (screen.confirmShipmentCancel) {
                        screen.confirmShipmentCancel = false;
                        screen.send(KingdomActionPayload.of("SHIPMENT_CANCEL", com.millenairekingdoms.compat.KNbt.getUUID(order, "id")));
                    } else {
                        screen.confirmShipmentCancel = true;
                        screen.rebuild();
                    }
                },
                order.getBooleanOr("canCancel", false),
                t(order.getBooleanOr("refundable", false) ? "shipment.refund_note" : "shipment.earned_fee_note"));
        ry += 21;
        if (order.getBooleanOr("canResume", false))
            shipmentButton(screen, rx, ry, rw, t("shipment.resume"),
                    b
                    -> screen.send(KingdomActionPayload.of("SHIPMENT_RESUME", com.millenairekingdoms.compat.KNbt.getUUID(order, "id"))),
                    true, t("shipment.resume_note"));
    }

    private static void shipmentVillage(
            KingdomScreen screen, int x, int y, int width, CompoundTag s, ListTag villages, boolean source) {
        String key = source ? "source" : "target";
        UUID current = com.millenairekingdoms.compat.KNbt.getUUID(s, key), other = com.millenairekingdoms.compat.KNbt.getUUID(s, source ? "target" : "source");
        int index = 0;
        for (int n = 0; n < villages.size(); n++)
            if (com.millenairekingdoms.compat.KNbt.getUUID(villages.getCompoundOrEmpty(n), "id").equals(current))
                index = n;
        var village = villages.getCompoundOrEmpty(index);
        CompoundTag next = village;
        for (int offset = 1; offset < villages.size(); offset++) {
            var candidate = villages.getCompoundOrEmpty((index + offset) % villages.size());
            if (source || !com.millenairekingdoms.compat.KNbt.getUUID(candidate, "id").equals(other)) {
                next = candidate;
                break;
            }
        }
        UUID nextId = com.millenairekingdoms.compat.KNbt.getUUID(next, "id");
        Component label = t("shipment." + key, village.getStringOr("name", ""));
        if (!village.getBooleanOr("loaded", false))
            label = Component.empty().append(label).append(" · ").append(t("shipment.unavailable"));
        shipmentButton(screen, x, y, width, label,
                b
                -> screen.send(KingdomActionPayload.of(source ? "SHIPMENT_SOURCE" : "SHIPMENT_TARGET", nextId)),
                !nextId.equals(current),
                Component.empty().append(label).append("\n").append(
                        t(source ? "shipment.source_note" : "shipment.target_note")));
    }

    private static Component shipmentReason(String reason) {
        return t("shipment.reason." + (reason.isEmpty() ? "SERVICE_UNAVAILABLE" : reason));
    }

    private static Component shipmentReason(CompoundTag state) {
        return state.getStringOr("reason", "").equals("INSUFFICIENT_STOCK")
                ? t("shipment.insufficient_stock_detail", state.getIntOr("quantity", 0), state.getIntOr("available", 0),
                          state.getIntOr("retained", 0))
                : shipmentReason(state.getStringOr("reason", ""));
    }

    private static Component shipmentStockDetails(CompoundTag state) {
        if (!state.contains("physical"))
            return null;
        var summary = t("shipment.stock_summary", state.getIntOr("physical", 0), state.getIntOr("retained", 0));
        if (!state.contains("reserve"))
            return summary;
        var reserve = state.getCompoundOrEmpty("reserve");
        return summary.append("\n").append(
                t("shipment.stock_details", reserve.getIntOr("target", 0), reserve.getIntOr("construction", 0),
                        reserve.getIntOr("residents", 0), reserve.getIntOr("minimum", 0), reserve.getIntOr("nativeProtected", 0)));
    }

    private static Component shipmentItem(String id) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
        return item.<Component>map(i -> Component.translatable(i.getDescriptionId()))
                .orElseGet(() -> Component.literal(id));
    }

    private static long seconds(long ticks) {
        return ticks / 20 + (ticks % 20 == 0 ? 0 : 1);
    }

    private static void shipmentButton(KingdomScreen screen, int x, int y, int width, Component label,
            Consumer<Button> press, boolean active, Component hint) {
        var button = Button.builder(label, press::accept).bounds(x, y, width, 16).build();
        button.active = active;
        if (hint != null)
            button.setTooltip(Tooltip.create(hint));
        screen.widget(button);
    }
}
