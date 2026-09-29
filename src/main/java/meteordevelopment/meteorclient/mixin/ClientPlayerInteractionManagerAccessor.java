/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
    @Invoker("sendSequencedPacket")
    void meteor$sendSequencedPacket(ClientWorld world, SequencedPacketCreator packetCreator);

    @Accessor("currentBreakingProgress")
    float getBreakingProgress();

    @Accessor("currentBreakingProgress")
    void setCurrentBreakingProgress(float progress);

    @Accessor("currentBreakingPos")
    BlockPos getCurrentBreakingBlockPos();
}
