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
import net.minecraft.resources.ResourceLocation;

/**
 * Royal construction and shipment sub-page layouts for {@link KingdomScreen}.
 */
final class KingdomRoyalTabs {
    private KingdomRoyalTabs() {}

    static void constructionTab(KingdomScreen screen) {
        int x = screen.x0(), y = screen.y0(), w = screen.contentWidth(), half = w * 11 / 20;
        var c = screen.view.getCompound("construction");
        var villages = c.getList("villages", Tag.TAG_COMPOUND);
        if (!c.hasUUID("village") || villages.isEmpty()) {
            screen.wrapped(x, y, constructionReason(c.getString("reason")), w, BAD, 5);
            return;
        }
        int index = 0;
        for (int n = 0; n < villages.size(); n++)
            if (villages.getCompound(n).getUUID("id").equals(c.getUUID("village")))
                index = n;
        var village = villages.getCompound(index);
        var next = villages.getCompound((index + 1) % villages.size());
        Component villageLabel = t("construction.village", village.getString("name"));
        if (!c.getBoolean("available"))
            villageLabel = Component.empty().append(villageLabel).append(" · ").append(t("construction.unavailable"));
        screen.button(x, y, w, villageLabel,
                b
                -> screen.send(KingdomActionPayload.of("CONSTRUCTION_SELECT", next.getUUID("id"))),
                villages.size() > 1, null);
        y += 21;
        screen.text(x, y, t("construction.plans"), TITLE, half - 4);
        int rx = x + half + 2, rw = w - half - 2;
        screen.text(rx, y, t("construction.current"), TITLE, rw);
        var plans = c.getList("plans", Tag.TAG_COMPOUND);
        CompoundTag selected = null;
        for (Tag raw : plans) {
            var plan = (CompoundTag) raw;
            if (plan.getString("key").equals(screen.selectedConstructionPlan))
                selected = plan;
        }
        if (selected == null && !plans.isEmpty()) {
            selected = plans.getCompound(0);
            screen.selectedConstructionPlan = selected.getString("key");
        }
        int listHeight = Math.max(33, screen.bottom() - y - 63);
        var choices = screen.list(x, y + 11, half - 4, listHeight);
        for (Tag raw : plans) {
            var plan = (CompoundTag) raw;
            String id = plan.getString("key");
            choices.add(Component.empty().append(constructionPlanName(plan)).append(" · " + coins(plan.getLong("fee"))),
                    plan.getBoolean("canOrder") ? TEXT : MUTED, id.equals(screen.selectedConstructionPlan), () -> {
                        screen.selectedConstructionPlan = id;
                        screen.rebuild();
                    });
        }
        if (selected != null) {
            var plan = selected;
            var purchase =
                    Button.builder(t(plan.getBoolean("upgrade")
                                                   ? (c.getBoolean("naturalPending") ? "construction.queue_upgrade"
                                                                                     : "construction.upgrade_order")
                                                   : (c.getBoolean("naturalPending") ? "construction.queue_order"
                                                                                     : "construction.order"),
                                           coins(plan.getLong("fee"))),
                                  b
                                  -> screen.send(new KingdomActionPayload("CONSTRUCTION_ORDER",
                                          Optional.of(c.getUUID("village")), Optional.of(plan.getUUID("offer")), 0,
                                          plan.getString("plan"))))
                            .bounds(x, y + listHeight + 14, half - 4, 16)
                            .build();
            purchase.active = plan.getBoolean("canOrder");
            Component tip = constructionPlanName(plan);
            if (!purchase.active)
                tip = Component.empty().append(tip).append("\n").append(constructionReason(plan.getString("reason")));
            else if (c.getBoolean("naturalPending"))
                tip = Component.empty().append(tip).append("\n").append(t("construction.waiting_note"));
            purchase.setTooltip(Tooltip.create(tip));
            screen.widget(purchase);
        } else
            screen.wrapped(x, y + listHeight + 14, constructionReason(c.getString("reason")), half - 4, MUTED, 2);
        int ry = y + 11;
        var order = c.getCompound("order");
        if (order.hasUUID("id")) {
            ry = screen.line(rx, ry, ClientText.building(order.getString("plan"), order.getString("name")), rw);
            ry = screen.line(rx, ry, t("construction.stage." + order.getString("stage")), rw, TITLE);
            if (!order.getString("blocked").equals("NONE"))
                ry = screen.wrapped(rx, ry, t("construction.blocked." + order.getString("blocked")), rw, BAD, 2);
            if (order.contains("progress"))
                ry = screen.line(rx, ry, t("construction.progress", order.getInt("progress")), rw);
            ry = screen.line(rx, ry, t("construction.price", coins(order.getLong("fee"))), rw);
            screen.button(rx, ry, rw,
                    t(screen.confirmConstructionCancel ? "construction.confirm_cancel" : "construction.cancel"), b -> {
                        if (screen.confirmConstructionCancel) {
                            screen.confirmConstructionCancel = false;
                            screen.send(KingdomActionPayload.of("CONSTRUCTION_CANCEL", order.getUUID("id")));
                        } else {
                            screen.confirmConstructionCancel = true;
                            screen.rebuild();
                        }
                    }, order.getBoolean("canCancel"), t("construction.cancel_unavailable"));
            ry += 20;
            if (order.getBoolean("canResume"))
                screen.button(rx, ry, rw, t("construction.resume"),
                        b
                        -> screen.send(KingdomActionPayload.of("CONSTRUCTION_RESUME", order.getUUID("id"))),
                        true, null);
        } else {
            ry = screen.wrapped(rx, ry,
                    t(c.getBoolean("stateKnown") ? "construction.no_order" : "construction.order_unknown"), rw, MUTED,
                    2);
            if (c.contains("reason"))
                screen.wrapped(rx, ry, constructionReason(c.getString("reason")), rw, BAD, 4);
        }
        screen.wrapped(x, screen.bottom() - 25,
                t(order.getString("stage").equals("WAITING") || !order.hasUUID("id") && c.getBoolean("naturalPending")
                                ? "construction.waiting_note"
                                : order.getBoolean("refundable") ? "construction.refund_note"
                                                                 : "construction.patronage_note"),
                w, MUTED, 2);
    }

    private static Component constructionPlanName(CompoundTag plan) {
        return plan.getBoolean("upgrade")
                ? t("construction.upgrade_choice", ClientText.building(plan.getString("plan"), plan.getString("name")),
                          plan.getInt("targetLevel") + 1, plan.getInt("x"), plan.getInt("z"))
                : ClientText.building(plan.getString("plan"), plan.getString("name"));
    }

    private static Component constructionReason(String reason) {
        return t("construction.reason." + (reason.isEmpty() ? "NO_ELIGIBLE_PLAN" : reason));
    }

    static void shipmentTab(KingdomScreen screen) {
        int x = screen.x0(), y = screen.y0(), w = screen.contentWidth(), lw = w / 2 - 3, rx = x + w / 2 + 3,
            rw = w / 2 - 3;
        var s = screen.view.getCompound("shipments");
        var villages = s.getList("villages", Tag.TAG_COMPOUND);
        if (!s.hasUUID("source") || !s.hasUUID("target") || villages.size() < 2) {
            screen.wrapped(x, y, shipmentReason(s.getString("reason")), w, BAD, 5);
            return;
        }
        shipmentVillage(screen, x, y, w, s, villages, true);
        shipmentVillage(screen, x, y + 20, w, s, villages, false);
        y += 42;
        var products = s.getList("products", Tag.TAG_COMPOUND);
        if (!products.isEmpty()) {
            shipmentButton(screen, x, y, lw, shipmentItem(s.getString("product")),
                    b
                    -> screen.openScreen(new ShipmentProductScreen(screen, products, s.getString("product"))),
                    true,
                    Component.empty()
                            .append(t("shipment.choose_product"))
                            .append("\n")
                            .append(shipmentStockDetails(s) == null ? Component.empty() : shipmentStockDetails(s)));
        } else
            screen.text(x, y, t("shipment.no_goods"), MUTED, lw);
        int sy = y + 20;
        if (screen.shipmentQuantity.isEmpty())
            screen.shipmentQuantity = Integer.toString(s.getInt("quantity"));
        screen.shipmentQuantityBox =
                new EditBox(screen.font(), x, sy, Math.max(30, lw - 85), 16, t("shipment.quantity"));
        screen.shipmentQuantityBox.setMaxLength(6);
        screen.shipmentQuantityBox.setFilter(value -> value.matches("[0-9]*"));
        screen.shipmentQuantityBox.setValue(screen.shipmentQuantity);
        screen.widget(screen.shipmentQuantityBox);
        shipmentButton(screen, x + lw - 81, sy, 81, t("shipment.set_quantity"), b -> {
            String value = screen.shipmentQuantityBox.getValue();
            long quantity = value.isEmpty() ? 0 : Long.parseLong(value);
            screen.send(
                    new KingdomActionPayload("SHIPMENT_QUANTITY", Optional.empty(), Optional.empty(), quantity, ""));
        }, true, t("shipment.quantity_hint", s.getInt("maximumQuantity")));
        sy += 20;
        if (s.contains("physical")) {
            sy = screen.line(x, sy, t("shipment.stock_summary", s.getInt("physical"), s.getInt("retained")), lw);
            sy = screen.line(x, sy, t("shipment.available", s.getInt("available")), lw);
        }
        if (s.hasUUID("offer")) {
            sy = screen.line(x, sy, t("shipment.price", coins(s.getLong("fee"))), lw);
            sy = screen.line(x, sy, t("shipment.time", seconds(s.getLong("travelTicks"))), lw);
            var purchase =
                    Button.builder(t("shipment.order"),
                                  b -> screen.send(KingdomActionPayload.of("SHIPMENT_ORDER", s.getUUID("offer"))))
                            .bounds(x, sy + 2, lw, 16)
                            .build();
            purchase.active =
                    s.getBoolean("canOrder") && screen.shipmentQuantity.equals(Integer.toString(s.getInt("quantity")));
            purchase.setTooltip(
                    Tooltip.create(s.getBoolean("canOrder") ? t("shipment.apply_quantity") : shipmentReason(s)));
            screen.widget(purchase);
            screen.shipmentQuantityBox.setResponder(value -> {
                screen.shipmentQuantity = value;
                purchase.active = s.getBoolean("canOrder") && value.equals(Integer.toString(s.getInt("quantity")));
            });
            sy += 22;
        }
        if (s.contains("reason"))
            screen.wrapped(x, sy, shipmentReason(s), lw, BAD, Math.max(1, (screen.bottom() - sy) / 11));
        var orders = s.getList("orders", Tag.TAG_COMPOUND);
        if (orders.isEmpty()) {
            screen.wrapped(rx, y, t(s.getBoolean("stateKnown") ? "shipment.no_order" : "shipment.order_unknown"), rw,
                    MUTED, 4);
            return;
        }
        int index = 0;
        for (int n = 0; n < orders.size(); n++)
            if (orders.getCompound(n).getUUID("id").equals(screen.selectedShipmentOrder))
                index = n;
        var order = orders.getCompound(index);
        screen.selectedShipmentOrder = order.getUUID("id");
        var next = orders.getCompound((index + 1) % orders.size());
        shipmentButton(screen, rx, y, rw, t("shipment.to", order.getString("destination")), b -> {
            screen.selectedShipmentOrder = next.getUUID("id");
            screen.confirmShipmentCancel = false;
            screen.rebuild();
        }, orders.size() > 1, shipmentItem(order.getString("product")));
        int ry = y + 20;
        ry = screen.line(rx, ry, t("shipment.stage." + order.getString("stage")), rw, TITLE);
        if (!order.getString("blocked").equals("NONE"))
            ry = screen.wrapped(rx, ry, t("shipment.blocked." + order.getString("blocked")), rw, BAD, 2);
        ry = screen.line(rx, ry, t("shipment.delivered", order.getInt("delivered"), order.getInt("quantity")), rw);
        ry = screen.line(rx, ry, t("shipment.held_returned", order.getInt("held"), order.getInt("returned")), rw);
        if (order.getString("stage").equals("IN_TRANSIT"))
            ry = screen.line(rx, ry, t("shipment.remaining", seconds(order.getLong("remainingTicks"))), rw);
        shipmentButton(screen, rx, ry + 2, rw,
                t(screen.confirmShipmentCancel ? "shipment.confirm_cancel" : "shipment.cancel"),
                b
                -> {
                    if (screen.confirmShipmentCancel) {
                        screen.confirmShipmentCancel = false;
                        screen.send(KingdomActionPayload.of("SHIPMENT_CANCEL", order.getUUID("id")));
                    } else {
                        screen.confirmShipmentCancel = true;
                        screen.rebuild();
                    }
                },
                order.getBoolean("canCancel"),
                t(order.getBoolean("refundable") ? "shipment.refund_note" : "shipment.earned_fee_note"));
        ry += 21;
        if (order.getBoolean("canResume"))
            shipmentButton(screen, rx, ry, rw, t("shipment.resume"),
                    b
                    -> screen.send(KingdomActionPayload.of("SHIPMENT_RESUME", order.getUUID("id"))),
                    true, t("shipment.resume_note"));
    }

    private static void shipmentVillage(
            KingdomScreen screen, int x, int y, int width, CompoundTag s, ListTag villages, boolean source) {
        String key = source ? "source" : "target";
        UUID current = s.getUUID(key), other = s.getUUID(source ? "target" : "source");
        int index = 0;
        for (int n = 0; n < villages.size(); n++)
            if (villages.getCompound(n).getUUID("id").equals(current))
                index = n;
        var village = villages.getCompound(index);
        CompoundTag next = village;
        for (int offset = 1; offset < villages.size(); offset++) {
            var candidate = villages.getCompound((index + offset) % villages.size());
            if (source || !candidate.getUUID("id").equals(other)) {
                next = candidate;
                break;
            }
        }
        UUID nextId = next.getUUID("id");
        Component label = t("shipment." + key, village.getString("name"));
        if (!village.getBoolean("loaded"))
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
        return state.getString("reason").equals("INSUFFICIENT_STOCK")
                ? t("shipment.insufficient_stock_detail", state.getInt("quantity"), state.getInt("available"),
                          state.getInt("retained"))
                : shipmentReason(state.getString("reason"));
    }

    private static Component shipmentStockDetails(CompoundTag state) {
        if (!state.contains("physical"))
            return null;
        var summary = t("shipment.stock_summary", state.getInt("physical"), state.getInt("retained"));
        if (!state.contains("reserve", Tag.TAG_COMPOUND))
            return summary;
        var reserve = state.getCompound("reserve");
        return summary.append("\n").append(
                t("shipment.stock_details", reserve.getInt("target"), reserve.getInt("construction"),
                        reserve.getInt("residents"), reserve.getInt("minimum"), reserve.getInt("nativeProtected")));
    }

    private static Component shipmentItem(String id) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id));
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
