package me.modmuss50.optifabric.compat.indigo.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder.ChunkData;

import me.modmuss50.optifabric.compat.InterceptingMixin;

@Mixin(value = ChunkData.class, priority = 2000)
@InterceptingMixin("net/fabricmc/fabric/mixin/client/indigo/renderer/MixinChunkRenderData")
abstract class ChunkDataMixin {
	@Shadow
	private boolean empty;

	public void fabric_markPopulated(RenderLayer renderLayer) {
		empty = false;
		setLayerUsed(renderLayer);
	}

	@Shadow(remap = false)
	public abstract void setLayerUsed(RenderLayer renderLayer);
}