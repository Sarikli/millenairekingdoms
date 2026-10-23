package com.millenairekingdoms.command;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.util.KingdomException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.VillageSavedData;

/** Name/UUID parsing only. Resolves exact names in the executing dimension; authority and political rules
 * remain in the same services used by the GUI. Ambiguous village names never choose a target silently. */
public final class PoliticalArguments {
    private PoliticalArguments() {}

    public static UUID village(CommandContext<CommandSourceStack> c, String key) {
        return village(c.getSource().getLevel(), StringArgumentType.getString(c, key));
    }
    public static UUID village(ServerLevel level, String name) {
        var manager = VillageSavedData.get(level).getVillageManager();
        UUID id = uuid(name);
        // Retain UUID-based history/status queries even after a village disappears. Mutating services check existence.
        if (id != null) return id;
        var matches = manager.getAllVillages().stream().filter(v -> v.getVillageName() != null
                && v.getVillageName().equalsIgnoreCase(name.strip())).toList();
        if (matches.isEmpty()) throw new KingdomException("millenairekingdoms.command.unknown_village", "Unknown village: %s", name);
        if (matches.size() != 1) throw new KingdomException("millenairekingdoms.command.ambiguous_village",
                "Several villages are named %s; use a village UUID.", name);
        return matches.getFirst().getId().uuid();
    }
    public static UUID kingdom(CommandContext<CommandSourceStack> c, String key) {
        return PoliticsService.resolveKingdom(KingdomSavedData.get(c.getSource().getLevel()), StringArgumentType.getString(c, key)).id();
    }
    public static RequiredArgumentBuilder<CommandSourceStack, String> villageArgument(String key) {
        return Commands.argument(key, StringArgumentType.string()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                VillageSavedData.get(c.getSource().getLevel()).getVillageManager().getAllVillages().stream()
                        .flatMap(v -> Stream.of(v.getId().uuid().toString(), v.getVillageName())).filter(s -> s != null && !s.isBlank())
                        .distinct().map(StringArgumentType::escapeIfRequired), b));
    }
    public static RequiredArgumentBuilder<CommandSourceStack, String> kingdomArgument(String key) {
        return Commands.argument(key, StringArgumentType.string()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                KingdomSavedData.get(c.getSource().getLevel()).kingdoms().stream()
                        .flatMap(k -> Stream.of(k.id().toString(), k.name())).map(StringArgumentType::escapeIfRequired), b));
    }
    private static UUID uuid(String text) {
        try { return UUID.fromString(text.strip()); } catch (IllegalArgumentException invalid) { return null; }
    }
}
