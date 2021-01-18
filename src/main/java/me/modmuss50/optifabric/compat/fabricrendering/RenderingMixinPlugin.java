package me.modmuss50.optifabric.compat.fabricrendering;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.fabricmc.tinyremapper.IMappingProvider.Member;

import me.modmuss50.optifabric.compat.InterceptingMixinPlugin;
import me.modmuss50.optifabric.util.RemappingUtils;

public class RenderingMixinPlugin extends InterceptingMixinPlugin {
	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if ("WorldRendererMixin".equals(mixinInfo.getName())) {
			Member renderParticles = RemappingUtils.mapMethod("class_702", "method_3049", //ParticleManager, renderParticles
												"(Lnet/minecraft/class_4587;Lnet/minecraft/class_4597$class_4598;Lnet/minecraft/class_765;Lnet/minecraft/class_4184;F)V");
			String renderDesc = "(Lnet/minecraft/class_4587;FJZLnet/minecraft/class_4184;Lnet/minecraft/class_757;Lnet/minecraft/class_765;Lnet/minecraft/class_1159;)V";
			String render = RemappingUtils.getMethodName("class_761", "method_22710", renderDesc); //(MatrixStack, Camera, GameRenderer, LightmapTextureManager, Matrix4f)
			renderDesc = RemappingUtils.mapMethodDescriptor(renderDesc);

			for (MethodNode method : targetClass.methods) {
				if (render.equals(method.name) && renderDesc.equals(method.desc)) {
					LabelNode skip = new LabelNode();

					InsnList extra = new InsnList();
					extra.add(new JumpInsnNode(Opcodes.GOTO, skip));
					extra.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, renderParticles.owner, renderParticles.name, renderParticles.desc, false));
					extra.add(skip);

					method.instructions.insertBefore(method.instructions.getLast(), extra);
					break;
				}
			}
		}

		super.preApply(targetClassName, targetClass, mixinClassName, mixinInfo);
	}
}