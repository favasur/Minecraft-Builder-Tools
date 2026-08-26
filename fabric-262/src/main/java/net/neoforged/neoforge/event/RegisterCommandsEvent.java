package net.neoforged.neoforge.event;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

public final class RegisterCommandsEvent {
    private final CommandDispatcher<CommandSourceStack> dispatcher;
    private final CommandBuildContext buildContext;

    public RegisterCommandsEvent(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        this.dispatcher = dispatcher;
        this.buildContext = buildContext;
    }

    public CommandDispatcher<CommandSourceStack> getDispatcher() {
        return dispatcher;
    }

    public CommandBuildContext getBuildContext() {
        return buildContext;
    }
}
