package me.modmuss50.optifabric.mod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.fabricmc.loader.util.mappings.TinyRemapperMappingsHelper;
import net.fabricmc.mapping.tree.ClassDef;
import net.fabricmc.mapping.tree.TinyTree;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.IMappingProvider.Member;
import net.fabricmc.tinyremapper.OutputConsumerPath.Builder;

import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.patcher.LambdaRebuiler;
import me.modmuss50.optifabric.patcher.PatchSplitter;
import me.modmuss50.optifabric.util.ZipUtils;

public class OptifineSetup {
	public static Pair<File, ClassCache> getRuntime() throws IOException {
		File workingDir = new File(FabricLoader.getInstance().getGameDirectory(), ".optifine");
		if (!workingDir.exists()) {
			workingDir.mkdirs();
		}

		File optifineModJar = OptifineVersion.findOptifineJar();
		byte[] modHash = fileHash(optifineModJar);

		File versionDir = new File(workingDir, OptifineVersion.version);
		if (!versionDir.exists()) {
			versionDir.mkdirs();
		}

		File remappedJar = new File(versionDir, "Optifine-mapped.jar");
		File optifinePatches = new File(versionDir, "Optifine.classes.gz");

		ClassCache classCache = null;
		if(remappedJar.exists() && optifinePatches.exists()){
			classCache = ClassCache.read(optifinePatches);
			//Validate that the classCache found is for the same input jar
			if(!Arrays.equals(classCache.getHash(), modHash)){
				System.out.println("Class cache is from a different optifine jar, deleting and re-generating");
				classCache = null;
				optifinePatches.delete();
			}
		}

		if (remappedJar.exists() && classCache != null) {
			System.out.println("Found existing patched optifine jar, using that");
			return Pair.of(remappedJar, classCache);
		}

		Path minecraftJar = getMinecraftJar();

		if (OptifineVersion.jarType == OptifineVersion.JarType.OPTIFINE_INSTALLER) {
			File optifineMod = new File(versionDir, "/Optifine-mod.jar");
			if (!optifineMod.exists()) {
				OptifineInstaller.extract(optifineModJar, optifineMod, minecraftJar.toFile());
			}
			optifineModJar = optifineMod;
		}

		System.out.println("Setting up optifine for the first time, this may take a few seconds.");

		//A jar without srgs
		File jarOfTheFree = new File(versionDir, "/Optifine-jarofthefree.jar");

		System.out.println("De-Volderfiying jar");

		//Find all the SRG named classes and remove them
		ZipUtils.transform(optifineModJar, (zip, zipEntry) -> {
			String name = zipEntry.getName();
			if(name.startsWith("com/mojang/blaze3d/platform/")){
				if(name.contains("$")){
					String[] split = name.replace(".class", "").split("\\$");
					if(split.length >= 2){
						if(split[1].length() > 2){
							return false;
						}
					}
				}
			}

			return !(name.startsWith("srg/") || name.startsWith("net/minecraft/"));
		}, jarOfTheFree);

		System.out.println("Finding lambdas to fix");
		LambdaRebuiler rebuilder = new LambdaRebuiler(jarOfTheFree, minecraftJar.toFile());
		rebuilder.buildLambdaMap();

		System.out.println("Remapping optifine with fixed lambda names");
		File lambdaFixJar = new File(versionDir, "/Optifine-lambdafix.jar");
		Path[] libraries = getLibs(minecraftJar);
		remapOptifine(jarOfTheFree, libraries, lambdaFixJar, rebuilder);

		String namespace = FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace();
		System.out.println("Remapping optifine from official to " + namespace);
		remapOptifine(lambdaFixJar, libraries, remappedJar, createMappings("official", namespace));

		classCache = PatchSplitter.generateClassCache(remappedJar, optifinePatches, modHash);

		if(true){
			//We are done, lets get rid of the stuff we no longer need
			lambdaFixJar.delete();
			jarOfTheFree.delete();

			if(OptifineVersion.jarType == OptifineVersion.JarType.OPTIFINE_INSTALLER){
				optifineModJar.delete();
			}
		}

		boolean extractClasses = Boolean.parseBoolean(System.getProperty("optifabric.extract", "false"));
		if(extractClasses){
			System.out.println("Extracting optifine classes");
			File optifineClasses = new File(versionDir, "optifine-classes");
			if(optifineClasses.exists()){
				FileUtils.deleteDirectory(optifineClasses);
			}
			ZipUtils.extract(remappedJar, optifineClasses);
		}

		return Pair.of(remappedJar, classCache);
	}

	private static void remapOptifine(File input, Path[] libraries, File output, IMappingProvider mappings) throws IOException {
		remapOptifine(input.toPath(), libraries, output.toPath(), mappings);
	}

	private static void remapOptifine(Path input, Path[] libraries, Path output, IMappingProvider mappings) throws IOException {
		Files.deleteIfExists(output);

		TinyRemapper remapper = TinyRemapper.newRemapper().withMappings(mappings).renameInvalidLocals(FabricLoader.getInstance().isDevelopmentEnvironment()).rebuildSourceFilenames(true).build();

		try (OutputConsumerPath outputConsumer = new Builder(output).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readInputs(input);
			remapper.readClassPath(libraries);

			remapper.apply(outputConsumer);
		} catch (Exception e) {
			throw new RuntimeException("Failed to remap jar", e);
		} finally {
			remapper.finish();
		}
	}

	//Optifine currently has two fields that match the same name as Yarn mappings, we'll rename Optifine's to something else
	private static IMappingProvider createMappings(String from, String to) {
		TinyTree normalMappings = FabricLauncherBase.getLauncher().getMappingConfiguration().getMappings();

		Map<String, ClassDef> nameToClass = normalMappings.getClasses().stream().collect(Collectors.toMap(clazz -> clazz.getName("intermediary"), Function.identity()));
		Map<Member, String> extraFields = new HashMap<>();

		ClassDef rebuildTask = nameToClass.get("net/minecraft/class_846$class_851$class_4578");
		ClassDef builtChunk = nameToClass.get("net/minecraft/class_846$class_851");
		extraFields.put(new Member(rebuildTask.getName(from), "this$1", 'L' + builtChunk.getName(from) + ';'), "field_20839");

		ClassDef particleManager = nameToClass.get("net/minecraft/class_702");
		particleManager.getFields().stream().filter(field -> "field_3835".equals(field.getName("intermediary"))).forEach(field -> {
			extraFields.put(new Member(particleManager.getName(from), field.getName(from), "Ljava/util/Map;"), field.getName(to));
		});

		//In dev
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			ClassDef option = nameToClass.get("net/minecraft/class_316");
			ClassDef cyclingOption = nameToClass.get("net/minecraft/class_4064");
			extraFields.put(new Member(option.getName(from), "CLOUDS", 'L' + cyclingOption.getName(from) + ';'), "CLOUDS_OF");

			ClassDef worldRenderer = nameToClass.get("net/minecraft/class_761");
			extraFields.put(new Member(worldRenderer.getName(from), "renderDistance", "I"), "renderDistance_OF");
		}

		//In prod
		return (out) -> {
			TinyRemapperMappingsHelper.create(normalMappings, from, to).load(out);

			extraFields.forEach(out::acceptField);
		};
	}

	//Gets the minecraft librarys
	private static Path[] getLibs(Path minecraftJar) {
		Path[] libs = FabricLauncherBase.getLauncher().getLoadTimeDependencies().stream().map(url -> {
			try {
				return UrlUtil.asPath(url);
			} catch (UrlConversionException e) {
				throw new RuntimeException(e);
			}
		}).filter(Files::exists).toArray(Path[]::new);

		out: if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			Path launchJar = getLaunchMinecraftJar();

			for (int i = 0, end = libs.length; i < end; i++) {
				Path lib = libs[i];

				if (launchJar.equals(lib)) {
					libs[i] = minecraftJar;
					break out;
				}
			}

			//Can't find the launch jar apparently, remapping will go wrong if it is left in
			throw new IllegalStateException("Unable to find Minecraft jar (at " + launchJar + ") in classpath: " + Arrays.toString(libs));
		}

		return libs;
	}

	//Gets the offical minecraft jar
	private static Path getMinecraftJar() {
		String givenJar = System.getProperty("optifabric.mc-jar");
		if (givenJar != null) {
			File givenJarFile = new File(givenJar);

			if (givenJarFile.exists()) {
				return givenJarFile.toPath();
			} else {
				System.err.println("Supplied Minecraft jar at " + givenJar + " doesn't exist, falling back");
			}
		}

		Path minecraftJar = getLaunchMinecraftJar();

		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			Path officialNames = minecraftJar.resolveSibling(String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion));

			if (Files.notExists(officialNames)) {
				Path parent = minecraftJar.getParent().resolveSibling(String.format("minecraft-%s-client.jar", OptifineVersion.minecraftVersion));

				if (Files.notExists(parent)) {
					throw new AssertionError("Unable to find Minecraft dev jar! Tried " + officialNames + " and " + parent);
				}

				officialNames = parent;
			}

			minecraftJar = officialNames;
		}

		return minecraftJar;
	}

	private static Path getLaunchMinecraftJar() {
		List<Path> contextJars = ((net.fabricmc.loader.FabricLoader) FabricLoader.getInstance()).getGameProvider().getGameContextJars();
		if (contextJars.isEmpty()) throw new IllegalStateException("Start has no context?");
		return contextJars.get(0);
	}

	private static byte[] fileHash(File input) throws IOException {
		try (InputStream is = new FileInputStream(input)) {
			return DigestUtils.md5(is);
		}
	}
}
