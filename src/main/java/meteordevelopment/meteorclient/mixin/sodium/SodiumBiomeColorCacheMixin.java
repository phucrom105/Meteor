/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.jellysquid.mods.sodium.client.world.biome.BiomeColorCache;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Ambience;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.world.biome.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BiomeColorCache.class, remap = false)
public class SodiumBiomeColorCacheMixin {
    @Unique
    private Ambience ambience;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo info) {
        ambience = Modules.get().get(Ambience.class);
    }

    @ModifyExpressionValue(method = "updateColorBuffers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/Biome;getGrassColorAt(DD)I", remap = true), require = 0)
    private int modify_getGrassColorAt(int color) {
        return ambience.isActive() && ambience.customGrassColor.get() ? ambience.grassColor.get().getPacked() : color;
    }

    @ModifyExpressionValue(method = "updateColorBuffers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/Biome;getFoliageColor()I", remap = true), require = 0)
    private int modify_getFoliageColor(int color) {
        return ambience.isActive() && ambience.customFoliageColor.get() ? ambience.foliageColor.get().getPacked() : color;
    }

    @ModifyExpressionValue(method = "updateColorBuffers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/Biome;getWaterColor()I", remap = true), require = 0)
    private int modify_getWaterColor(int color) {
        return ambience.isActive() && ambience.customWaterColor.get() ? ambience.waterColor.get().getPacked() : color;
    }

    @ModifyExpressionValue(method = "updateColorBuffers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/ColorResolver;getColor(Lnet/minecraft/world/biome/Biome;DD)I", remap = true), require = 0)
    private int modify_resolvedColor(int color, @Local(argsOnly = true) ColorResolver resolver) {
        if (!ambience.isActive()) return color;

        if (resolver == BiomeColors.GRASS_COLOR && ambience.customGrassColor.get()) {
            return ambience.grassColor.get().getPacked();
        }
        if (resolver == BiomeColors.FOLIAGE_COLOR && ambience.customFoliageColor.get()) {
            return ambience.foliageColor.get().getPacked();
        }
        if (resolver == BiomeColors.WATER_COLOR && ambience.customWaterColor.get()) {
            return ambience.waterColor.get().getPacked();
        }

        return color;
    }
}
