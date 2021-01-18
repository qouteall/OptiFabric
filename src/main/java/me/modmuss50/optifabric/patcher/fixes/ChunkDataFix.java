package me.modmuss50.optifabric.patcher.fixes;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import me.modmuss50.optifabric.util.RemappingUtils;

public class ChunkDataFix implements ClassFixer {
	//Newer OptiFine versions change the type of a private field which Indigo uses

	@Override
	public void fix(ClassNode optifine, ClassNode minecraft) {
		String nonEmptyLayers = RemappingUtils.mapFieldName("class_846$class_849", "field_4450", "Ljava/util/Set;");

		for (FieldNode field : optifine.fields) {
			if (nonEmptyLayers.equals(field.name) && "Ljava/util/Set;".equals(field.desc)) {
				return;
			}
		}

		//Strictly speaking it does have a signature too, but it has no bearing on the outcome to bother making it
		optifine.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, nonEmptyLayers, "Ljava/util/Set;", null, null));
	}
}