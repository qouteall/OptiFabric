/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package me.modmuss50.optifabric.patcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Runnables;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.tinyremapper.IMappingProvider;

public class LambdaRebuiler implements IMappingProvider {
	private final File optifineFile;
	private final File minecraftClientFile;
	private final Map<Member, String> fixes = new HashMap<>();

	public LambdaRebuiler(File optifineFile, File minecraftClientFile) throws IOException {
		this.optifineFile = optifineFile;
		this.minecraftClientFile = minecraftClientFile;
	}

	public void buildLambdaMap() throws IOException {
		try (JarFile optifineJar = new JarFile(optifineFile); JarFile clientJar = new JarFile(minecraftClientFile)) {
			Enumeration<JarEntry> entrys = optifineJar.entries();

			while (entrys.hasMoreElements()) {
				JarEntry entry = entrys.nextElement();
				String name = entry.getName();

				if (name.endsWith(".class") && !name.startsWith("net/") && !name.startsWith("optifine/") && !name.startsWith("javax/")) {
					ClassNode classNode = ASMUtils.asClassNode(entry, optifineJar);
					ClassNode minecraftClass = ASMUtils.asClassNode(clientJar.getJarEntry(name), clientJar);

					if (!minecraftClass.name.equals(classNode.name)) {
						throw new RuntimeException("Something went wrong");
					}

					findLambdas(minecraftClass.name, minecraftClass.methods, classNode.methods);
				}
			}	
		}
	}

	private boolean findLambdas(String className, List<MethodNode> original, List<MethodNode> patched) {
		List<MethodComparison> commonMethods = new ArrayList<>();
		List<MethodNode> lostMethods = new ArrayList<>();
		List<MethodNode> gainedMethods = new ArrayList<>(); {
			Map<String, MethodNode> originalMethods = original.stream().collect(Collectors.toMap(method -> method.name.concat(method.desc), Function.identity()));
			Map<String, MethodNode> patchedMethods = patched.stream().collect(Collectors.toMap(method -> method.name.concat(method.desc), Function.identity()));

			for (String methodName : Sets.union(originalMethods.keySet(), patchedMethods.keySet())) {
				MethodNode originalMethod = originalMethods.get(methodName);
				MethodNode patchedMethod = patchedMethods.get(methodName);

				if (originalMethod != null) {
					if (patchedMethod != null) {//Both have the method
						commonMethods.add(new MethodComparison(originalMethod, patchedMethod));
					} else {//Just the original has the method
						lostMethods.add(originalMethod);
					}
				} else if (patchedMethod != null) {//Just the modified has the method
					gainedMethods.add(patchedMethod);
				} else {//Neither have the method?!
					throw new IllegalStateException("Unable to find " + methodName + " in either " + className + " versions");
				}
			}

			commonMethods.sort(Comparator.comparingInt(method -> !"<clinit>".equals(method.node.name) ? patched.indexOf(method.node) : "com/mojang/blaze3d/platform/GLX".equals(className) ? patched.size() : -1));
			lostMethods.sort(Comparator.comparingInt(original::indexOf));
			gainedMethods.sort(Comparator.comparingInt(patched::indexOf));
		}


		//Make sure at least one method contains lambdas, and there are both lost and gained methods which probably are a lambda 
		if (commonMethods.stream().noneMatch(method -> !method.equal && method.hasLambdas()) || lostMethods.isEmpty() || gainedMethods.isEmpty()) return true;

		List<MethodNode> gainedLambdas = gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")).collect(Collectors.toList());
		if (gainedLambdas.isEmpty()) return true; //Nothing looks like a lambda


		Map<String, MethodNode> possibleLambdas = gainedLambdas.stream().collect(Collectors.toMap(method -> method.name.concat(method.desc), Function.identity())); //The collection of lambdas we're looking to fix, any others are irrelevant from the point of view that they're probably fine
		Map<String, MethodNode> nameToLosses = lostMethods.stream().collect(Collectors.toMap(method -> method.name.concat(method.desc), Function.identity()));

		for (int i = 0; i < commonMethods.size(); i++) {//Indexed for loop as each added fix will add to commonMethods
			MethodComparison method = commonMethods.get(i);

			if (method.effectivelyEqual) resolveCloseMethod(className, commonMethods, lostMethods, gainedMethods, method, nameToLosses, possibleLambdas);
		}


		for (int i = 0; i < commonMethods.size(); i++) {
			MethodComparison method = commonMethods.get(i);
			if (method.effectivelyEqual) continue; //Already handled this method

			List<Lambda> originalLambdas = method.getOriginalLambads();
			List<Lambda> patchedLambdas = method.getPatchedLambads();

			out: if (originalLambdas.size() == patchedLambdas.size()) {
				for (Iterator<Lambda> itOriginal = originalLambdas.iterator(), itPatched = patchedLambdas.iterator(); itOriginal.hasNext() && itPatched.hasNext();) {
					Lambda originalLambda = itOriginal.next();
					Lambda patchedLambda = itPatched.next();

					//Check if the lambdas are acting as the same method implementation
					if (!Objects.equals(originalLambda.method, patchedLambda.method)) break out;
				}

				pairUp(className, commonMethods, lostMethods, gainedMethods, originalLambdas, patchedLambdas, nameToLosses, possibleLambdas, () -> {
					for (int j = commonMethods.size() - 1; j < commonMethods.size(); j++) {
						MethodComparison innerMethod = commonMethods.get(j);

						if (innerMethod.effectivelyEqual) resolveCloseMethod(className, commonMethods, lostMethods, gainedMethods, innerMethod, nameToLosses, possibleLambdas);
					}
				});

				continue; //Matched all the lambdas up for method
			}

			Collector<Lambda, ?, Map<String, Map<String, List<Lambda>>>> lambdaCategorisation = Collectors.groupingBy(lambda -> lambda.desc, Collectors.groupingBy(lambda -> lambda.method));
			Map<String, Map<String, List<Lambda>>> descToOriginalLambda = originalLambdas.stream().collect(lambdaCategorisation);
			Map<String, Map<String, List<Lambda>>> descToPatchedLambda = patchedLambdas.stream().collect(lambdaCategorisation);

			Set<String> commonDescs = Sets.intersection(descToOriginalLambda.keySet(), descToPatchedLambda.keySet()); //Unique descriptions that are found in both the lost methods and gained lambdas
			if (!commonDescs.isEmpty()) {
				int fixedLambdas = 0;

				for (String desc : commonDescs) {
					Map<String, List<Lambda>> typeToOriginalLambda = descToOriginalLambda.get(desc);
					Map<String, List<Lambda>> typeToPatchedLambda = descToPatchedLambda.get(desc);

					for (String type : Sets.intersection(typeToOriginalLambda.keySet(), typeToPatchedLambda.keySet())) {
						List<Lambda> matchedOriginalLambdas = typeToOriginalLambda.get(type);
						List<Lambda> matchedPatchedLambdas = typeToPatchedLambda.get(type);

						if (matchedOriginalLambdas.size() == matchedPatchedLambdas.size()) {//Presume if the size is more than one they're in the same order
							fixedLambdas += matchedOriginalLambdas.size();

							pairUp(className, commonMethods, lostMethods, gainedMethods, matchedOriginalLambdas, matchedPatchedLambdas, nameToLosses, possibleLambdas, () -> {
								for (int j = commonMethods.size() - 1; j < commonMethods.size(); j++) {
									MethodComparison innerMethod = commonMethods.get(j);

									if (innerMethod.effectivelyEqual) resolveCloseMethod(className, commonMethods, lostMethods, gainedMethods, innerMethod, nameToLosses, possibleLambdas);
								}
							});
						}
					}
				}

				if (fixedLambdas == originalLambdas.size()) return true; //Caught all the lambdas
			}
		}

		return possibleLambdas.isEmpty(); //All the lambda-like methods which could be matched up if possibleLambdas is empty
	}

	private void resolveCloseMethod(String className, List<MethodComparison> commonMethods, List<MethodNode> lostMethods, List<MethodNode> gainedMethods,
			MethodComparison method, Map<String, MethodNode> nameToLosses, Map<String, MethodNode> possibleLambdas) {
		assert method.effectivelyEqual;

		if (!method.equal) {
			if (method.getOriginalLambads().size() != method.getPatchedLambads().size()) {
				throw new IllegalStateException("Bytecode in " + className + '#' + method.node.name + method.node.desc + " appeared unchanged but lambda count changed?");
			}

			pairUp(className, commonMethods, lostMethods, gainedMethods, method.getOriginalLambads(), method.getPatchedLambads(), nameToLosses, possibleLambdas, Runnables.doNothing());
		}
	}

	private void pairUp(String className, List<MethodComparison> commonMethods, List<MethodNode> lostMethods, List<MethodNode> gainedMethods,
			List<Lambda> originalLambdas, List<Lambda> patchedLambdas, Map<String, MethodNode> nameToLosses, Map<String, MethodNode> possibleLambdas, Runnable onPair) {
      for (Iterator<Lambda> itOriginal = originalLambdas.iterator(), itPatched = patchedLambdas.iterator(); itOriginal.hasNext() && itPatched.hasNext();) {
			Lambda lost = itOriginal.next();
			Lambda gained = itPatched.next();

			if (!className.equals(lost.owner)) return;
			assert className.equals(gained.owner);

			MethodNode lostMethod = nameToLosses.remove(lost.getName());
			MethodNode gainedMethod = possibleLambdas.remove(gained.getName());

			if (lostMethod == null) {
				if (gainedMethod == null) {
					assert Objects.equals(lost.getFullName(), gained.getFullName());
					return;
				} else {
					throw new IllegalStateException("Couldn't find original method for lambda: " + lost.getFullName());
				}
			} else if (gainedMethod == null) {
				throw new IllegalStateException("Couldn't find patched method for lambda: " + gained.getFullName());
			}

			if (addFix(className, commonMethods, gainedMethod, lostMethod)) {
				lostMethods.remove(lostMethod);
				gainedMethods.remove(gainedMethod);
				onPair.run();
			}
		}
	}

	private boolean addFix(String className, List<MethodComparison> commonMethods, MethodNode from, MethodNode to) {
		if (!from.desc.equals(to.desc)) {
			System.err.println("Description changed remapping lambda handle: " + className + '#' + from.name + from.desc + " => " + className + '#' + to.name + to.desc);
			return false; //Don't add the fix if it is wrong
		}

		fixes.put(new Member(className, from.name, from.desc), to.name);

		from.name = to.name; //Apply the rename to the actual method node too
		commonMethods.add(new MethodComparison(to, from));
		return true;
	}

	@Override
	public void load(MappingAcceptor out) {
		fixes.forEach(out::acceptMethod);
	}
}
