package me.modmuss50.optifabric.compat.fabricrendering.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.WorldRenderer;

import me.modmuss50.optifabric.compat.InterceptingMixin;
import me.modmuss50.optifabric.compat.Shim;

@Mixin(WorldRenderer.class)
@InterceptingMixin("net/fabricmc/fabric/mixin/client/rendering/MixinWorldRenderer")
abstract class WorldRendererMixin {
	@Inject(method = "render", require = 2,
			at = @At(value = "INVOKE", 
					target = "Lnet/minecraft/client/particle/ParticleManager;renderParticles(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/render/LightmapTextureManager;Lnet/minecraft/client/render/Camera;FLnet/minecraft/client/render/Frustum;)V"))
	private void onReallyRenderParticles(CallbackInfo call) {
		onRenderParticles(call);
	}

	@Shim
	private native void onRenderParticles(CallbackInfo call);
}