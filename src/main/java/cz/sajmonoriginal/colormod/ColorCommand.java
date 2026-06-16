/*
 * In-game {@code /color} command.
 *
 * <p>Suggestions are rebuilt every invocation from {@link Color#palette()} so
 * an admin who edits {@code colors.palette} in the config doesn't need to
 * restart for the new colours to be tab-completable.
 *
 * <p>{@code /color} alone prints usage; {@code /color <name>} sets the colour;
 * {@code /color none} clears it. Writes go through {@link ColorService}, which
 * persists to the HTTP backend (source of truth) and mirrors to LuckPerms for
 * downstream chat formatters.
 */
package cz.sajmonoriginal.colormod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import cz.sajmonoriginal.colormod.events.NameEventHandler;
import cz.sajmonoriginal.colormod.scoreboard.ColorTeams;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class ColorCommand {
    private ColorCommand() {}

    // Suggestions and usage hide tier entries from non-ops. The colour name
    // shouldn't even leak via tab-complete, otherwise a curious player would
    // see "owner" suggested even though /color owner would be denied. Ops see
    // everything (they can assign tiers).
    private static final SuggestionProvider<CommandSourceStack> COLOR_SUGGEST = (ctx, builder) -> {
        boolean op = ctx.getSource().hasPermission(2);
        List<String> all = new ArrayList<>();
        for (Color c : Color.palette()) {
            if (c.isTier() && !op) continue;
            all.add(c.name());
        }
        all.add("none");
        return SharedSuggestionProvider.suggest(all, builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("color")
                .executes(ColorCommand::usage)
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests(COLOR_SUGGEST)
                        .executes(ctx -> setColor(
                                ctx.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))));
    }

    private static int usage(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        boolean op = ctx.getSource().hasPermission(2);
        StringBuilder sb = new StringBuilder("Usage: /color <name>   available: ");
        for (Color c : Color.palette()) {
            if (c.isTier() && !op) continue;
            sb.append(c.name()).append(", ");
        }
        sb.append("none");
        final String s = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(s).withColor(0xAAAAAA), false);
        return 1;
    }

    private static int setColor(CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();

        Color target = "none".equalsIgnoreCase(name) ? null : Color.byName(name);
        // Tier entries (palette has lp_group_override, e.g. privileged "owner")
        // are only assignable by ops. For non-ops we treat them as if they don't
        // exist at all (same "Unknown colour" response as a typo) so the existence
        // of the tier name isn't even leaked via the error message.
        if (target != null && target.isTier() && !src.hasPermission(2)) target = null;
        if (target == null && !"none".equalsIgnoreCase(name)) {
            src.sendFailure(Component.literal("Unknown colour: " + name));
            return 0;
        }

        final Color finalTarget = target;
        ColorService.setColor(player.getUUID(), finalTarget).whenComplete((r, ex) -> {
            if (ex != null) {
                ColorMod.LOG.error("[colormod] /color failed for {}", player.getGameProfile().getName(), ex);
                player.sendSystemMessage(Component.literal("Failed to update your colour. Try again later.")
                        .withColor(0xFF5555));
                return;
            }
            // Two-pronged update on the server thread:
            //  - ColorTeams.apply moves the player into the matching team, so the
            //    above-head nameplate switches to the closest 16-colour ChatFormatting.
            //  - NameEventHandler.refresh invalidates the cached display + tab name
            //    so the NameFormat / TabListNameFormat events re-fire, picking up
            //    the new TRUE-RGB component on next read.
            player.getServer().execute(() -> {
                ColorTeams.apply(player, finalTarget);
                NameEventHandler.refresh(player);
            });

            Component msg = (finalTarget == null)
                    ? Component.literal("Your name colour has been cleared.").withColor(0xAAAAAA)
                    : Component.literal("Your name colour is now " + finalTarget.name() + ".").withColor(finalTarget.hex());
            player.sendSystemMessage(msg);
        });
        return 1;
    }
}
