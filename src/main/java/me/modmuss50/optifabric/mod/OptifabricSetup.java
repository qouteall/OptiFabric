package me.modmuss50.optifabric.mod;

import java.io.File;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.google.common.base.MoreObjects;

import org.apache.commons.lang3.tuple.Pair;

import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import org.spongepowered.asm.mixin.Mixins;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.util.version.SemanticVersionImpl;
import net.fabricmc.loader.util.version.SemanticVersionPredicateParser;

import me.modmuss50.optifabric.mod.OptifineVersion.JarType;
import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.util.RemappingUtils;

import com.chocohead.mm.api.ClassTinkerers;

public class OptifabricSetup implements Runnable {
	public static File optifineRuntimeJar = null;

	//This is called early on to allow us to get the transformers in beofore minecraft starts
	@Override
	public void run() {
		OptifineInjector injector;
		try {
			Pair<File, ClassCache> runtime = OptifineSetup.getRuntime();
			optifineRuntimeJar = runtime.getLeft();

			//Add the optifine jar to the classpath, as
			ClassTinkerers.addURL(runtime.getLeft().toURI().toURL());

			injector = new OptifineInjector(runtime.getRight());
			injector.setup();
		} catch (Throwable e) {
			if (!OptifabricError.hasError()) {
				OptifineVersion.jarType = JarType.INTERNAL_ERROR;
				OptifabricError.setError("Failed to load optifine, check the log for more info \n\n " + e.getMessage());
			}
			throw new RuntimeException("Failed to setup optifine", e);
		}

		BooleanSupplier particlesPresent = new BooleanSupplier() {
			private boolean haveLooked, isPresent;

			@Override
			public boolean getAsBoolean() {
				if (!haveLooked) {
					isPresent = injector.predictFuture(RemappingUtils.getClassName("class_702")).filter(node -> {//ParticleManager
						//(MatrixStack, VertexConsumerProvider$Immediate, LightmapTextureManager, Camera, Frustum)
						String desc = RemappingUtils.mapMethodDescriptor("(Lnet/minecraft/class_4587;Lnet/minecraft/class_4597$class_4598;"
																		+ "Lnet/minecraft/class_765;Lnet/minecraft/class_4184;FLnet/minecraft/class_4604;)V");

						for (MethodNode method : node.methods) {
							if ("renderParticles".equals(method.name) && desc.equals(method.desc)) {
								return true;
							}
						}

						return false;
					}).isPresent();
					haveLooked = true;
				}

				return isPresent;
			}
		};

		if (isPresent("fabric-renderer-api-v1")) {
			Mixins.addConfiguration("optifabric.compat.fabric-renderer-api.mixins.json");
		}

		if (isPresent("fabric-rendering-v1", ">=1.5.0") && particlesPresent.getAsBoolean()) {
			Mixins.addConfiguration("optifabric.compat.fabric-rendering.mixins.json");
		}

		if (isPresent("fabric-renderer-indigo")) {
			Mixins.addConfiguration("optifabric.compat.indigo.mixins.json");

			injector.predictFuture(RemappingUtils.getClassName("class_846$class_849")).ifPresent(node -> {//ChunkBuilder$ChunkData
				String nonEmptyLayers = RemappingUtils.mapFieldName("class_846$class_849", "field_4450", "Ljava/util/Set;");

				for (FieldNode field : node.fields) {
					if (nonEmptyLayers.equals(field.name) && "Ljava/util/Set;".equals(field.desc)) {
						return;
					}
				}

				Mixins.addConfiguration("optifabric.compat.indigo.extra-mixins.json");
			});
		}

		if (isPresent("fabric-item-api-v1", ">=1.1.0")) {
			Mixins.addConfiguration("optifabric.compat.fabric-item-api.mixins.json");
		}

		Mixins.addConfiguration("optifabric.optifine.mixins.json");

		if (isPresent("cloth-client-events-v0")) {
			Mixins.addConfiguration("optifabric.compat.cloth.mixins.json");
		}

		if (isPresent("clothesline")) {
			Mixins.addConfiguration("optifabric.compat.clothesline.mixins.json");
		}

		if (isPresent("trumpet-skeleton")) {
			Mixins.addConfiguration("optifabric.compat.trumpet-skeleton.mixins.json");
		}

		if (isPresent("multiconnect", ">1.3.14")) {
			Mixins.addConfiguration("optifabric.compat.multiconnect.mixins.json");
		}

		if (isPresent("now-playing", ">=1.1.0")) {
			Mixins.addConfiguration("optifabric.compat.now-playing.mixins.json");
		}

		if (isPresent("origins", mod -> compareVersions(Pattern.compile("^1\\.16(\\.\\d)?-").matcher(mod.getVersion().getFriendlyString()).find() ? ">=1.16-0.2.0" : ">=0.4.1", mod))) {
			if (isPresent("origins", mod -> !Pattern.compile("^1\\.16(\\.\\d)?-").matcher(mod.getVersion().getFriendlyString()).find() || compareVersions(">=1.16.3-0.4.0", mod))) {
				Mixins.addConfiguration("optifabric.compat.origins.mixins.json");
			}

			injector.predictFuture(RemappingUtils.getClassName("class_979")).ifPresent(node -> {//ElytraFeatureRenderer
				String desc = RemappingUtils.mapMethodDescriptor("(Lnet/minecraft/class_1799;Lnet/minecraft/class_1309;)Z"); //ItemStack, LivingEntity

				for (MethodNode method : node.methods) {
					if ("shouldRender".equals(method.name) && desc.equals(method.desc)) {
						Mixins.addConfiguration("optifabric.compat.origins.extra-mixins.json");
						break;
					}
				}
			});
		}

		if (isPresent("staffofbuilding")) {
			Mixins.addConfiguration("optifabric.compat.staffofbuilding.mixins.json");
		}

		if (isPresent("sandwichable")) {
			Mixins.addConfiguration("optifabric.compat.sandwichable.mixins.json");
		}

		if (isPresent("astromine", "<1.6")) {//Only needed for the 1.16.1 versions
			Mixins.addConfiguration("optifabric.compat.astromine.mixins.json");
		}

		if (isPresent("carpet")) {
			Mixins.addConfiguration("optifabric.compat.carpet.mixins.json");

			if (particlesPresent.getAsBoolean()) {
				Mixins.addConfiguration("optifabric.compat.carpet.extra-mixins.json");
			}
		}

		if (isPresent("hctm-base")) {
			Mixins.addConfiguration("optifabric.compat.hctm.mixins.json");
		}

		if (isPresent("mubble", "<4.0-pre5")) {
			Mixins.addConfiguration("optifabric.compat.mubble.mixins.json");
		}

		if (isPresent("dawn", ">=1.3 <=1.4")) {
			Mixins.addConfiguration("optifabric.compat.dawn.older-mixins.json");
		} else if (isPresent("dawn", ">1.4 <1.5")) {
			Mixins.addConfiguration("optifabric.compat.dawn.old-mixins.json");
		} else if (isPresent("dawn", ">=1.5")) {
			Mixins.addConfiguration("optifabric.compat.dawn.mixins.json");
		}

		if (isPresent("phormat")) {
			Mixins.addConfiguration("optifabric.compat.phormat.mixins.json");
		}

		if (isPresent("chat_heads", "<0.2")) {
			Mixins.addConfiguration("optifabric.compat.chat-heads.mixins.json");
		}

		if (isPresent("mmorpg")) {
			Mixins.addConfiguration("optifabric.compat.age-of-exile.mixins.json");
		}

		if (isPresent("charm", ">=2.0 <2.1")) {
			Mixins.addConfiguration("optifabric.compat.charm-old.mixins.json");
		} else if (isPresent("charm", ">=2.1")) {
			Mixins.addConfiguration("optifabric.compat.charm.mixins.json");
		}

		if (isPresent("voxelmap")) {
			Mixins.addConfiguration("optifabric.compat.voxelmap.mixins.json");
		}

		if (isPresent("appliedenergistics2")) {
			Mixins.addConfiguration("optifabric.compat.ae2.mixins.json");
		}

		if (isPresent("images", "=0.3.0")) {
			Mixins.addConfiguration("optifabric.compat.images-old.mixins.json");
		} else if (isPresent("images", ">=0.3.1")) {
			Mixins.addConfiguration("optifabric.compat.images.mixins.json");
		}

		if (isPresent("architectury", ">=1.0.20")) {
			Mixins.addConfiguration("optifabric.compat.architectury-B.mixins.json");
		} else if (isPresent("architectury", ">=1.0.4")) {
			Mixins.addConfiguration("optifabric.compat.architectury-A.mixins.json");
		}
	}

	private static boolean isPresent(String modID) {
		return FabricLoader.getInstance().isModLoaded(modID);
	}

	private static boolean isPresent(String modID, String versionRange) {
		return isPresent(modID, modMetadata -> compareVersions(versionRange, modMetadata));
	}

	private static boolean isPresent(String modID, Predicate<ModMetadata> extraChecks) {
		if (!isPresent(modID)) return false;

		Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(modID);
		ModMetadata modMetadata = modContainer.map(ModContainer::getMetadata).orElseThrow(() ->
			new RuntimeException("Failed to get mod container for " + modID + ", something has broke badly.")
		);

		return extraChecks.test(modMetadata);
	}

	private static boolean compareVersions(String versionRange, ModMetadata mod) {
		try {
			Predicate<SemanticVersionImpl> predicate = SemanticVersionPredicateParser.create(versionRange);
			SemanticVersionImpl version = new SemanticVersionImpl(mod.getVersion().getFriendlyString(), false);
			return predicate.test(version);
		} catch (@SuppressWarnings("deprecation") net.fabricmc.loader.util.version.VersionParsingException e) {
			System.err.println("Error comparing the version for ".concat(MoreObjects.firstNonNull(mod.getName(), mod.getId())));
			e.printStackTrace();
			return false; //Let's just gamble on the version not being valid also not being a problem
		}
	}
}
