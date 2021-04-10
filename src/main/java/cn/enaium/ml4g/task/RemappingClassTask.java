package cn.enaium.ml4g.task;

import cn.enaium.ml4g.util.GameUtil;
import cn.enaium.ml4g.util.MappingUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.ASM9;

/**
 * @author Enaium
 */
public class RemappingClassTask extends Task {
    @TaskAction
    public void remapping() {
        try {
            JsonObject jsonObject = new JsonObject();
            JsonObject mappings = new JsonObject();
            File classes = new File(getProject().getBuildDir(), "classes");
            MappingUtil.analyzeJar(GameUtil.getClientCleanFile(extension));
            for (File file : FileUtils.listFiles(classes.getAbsoluteFile(), new String[]{"class"}, true)) {
                MappingUtil.initMapping(GameUtil.getClientMappingFile(extension));
                MappingUtil.putRemap(false);
                byte[] bytes = FileUtils.readFileToByteArray(file);
                FileUtils.writeByteArrayToFile(file, MappingUtil.accept(bytes));
                if (extension.mixinRefMap != null) {
                    MixinScannerVisitor mixinScannerVisitor = new MixinScannerVisitor();
                    new ClassReader(bytes).accept(mixinScannerVisitor, 0);
                    for (String mixin : mixinScannerVisitor.getMixins()) {
                        JsonObject mapping = new JsonObject();

                        for (String method : mixinScannerVisitor.getMethods()) {
                            mapping.addProperty(method, getMethodObf(mixin, method));
                        }

                        for (String mixinTarget : mixinScannerVisitor.getTargets()) {
                            if (!mixinTarget.contains("field:")) {
                                String targetClass = MappingUtil.classCleanToObfMap.get(mixinTarget.substring(1, mixinTarget.indexOf(";")));

                                if (targetClass == null) {
                                    continue;
                                }

                                String targetMethod = getMethodObf(targetClass, mixinTarget.substring(mixinTarget.indexOf(";") + 1));
                                if (targetMethod == null) {
                                    continue;
                                }
                                mapping.addProperty(mixinTarget, targetMethod);
                            } else {
                                String left = mixinTarget.split("field:")[0];
                                String right = mixinTarget.split("field:")[1];
                                String targetClass = MappingUtil.classCleanToObfMap.get(left.substring(1, left.indexOf(";")));
                                String targetField = MappingUtil.classCleanToObfMap.get(right.substring(1, right.indexOf(";")));

                                if (targetClass == null || targetField == null) {
                                    continue;
                                }

                                mapping.addProperty(mixinTarget, "L" + targetClass + ";field:L" + targetField + ";");
                            }
                        }

                        for (Map.Entry<String, String> entry : mixinScannerVisitor.getAccessors().entrySet()) {

                            String fieldName = MappingUtil.fieldCleanToObfMap.get(mixin + "/" + entry.getValue());

                            if (fieldName == null) {
                                continue;
                            }

                            if (entry.getKey().contains(";")) {
                                String ret = entry.getKey().substring(entry.getKey().lastIndexOf(")") + 1);
                                ret = ret.substring(1, ret.lastIndexOf(";"));
                                ret = MappingUtil.classCleanToObfMap.get(ret);
                                if (ret == null) {
                                    continue;
                                }
                                mapping.addProperty(entry.getValue(), fieldName.split("/")[1] + ":L" + ret + ";");
                            } else {
                                mapping.addProperty(entry.getValue(), entry.getKey());
                            }
                        }
                        mappings.add(mixinScannerVisitor.className, mapping);
                    }

                }
            }

            if (extension.mixinRefMap != null) {
                jsonObject.add("mappings", mappings);

                JavaPluginConvention java = (JavaPluginConvention) getProject().getConvention().getPlugins().get("java");
                File resourceDir = new File(getProject().getBuildDir(), "resources");
                for (SourceSet sourceSet : java.getSourceSets()) {
                    if (!resourceDir.exists()) {
                        resourceDir.mkdir();
                    }
                    File dir = new File(resourceDir, sourceSet.getName());
                    if (dir.exists()) {
                        FileUtils.write(new File(dir, extension.mixinRefMap), new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException e) {
            getProject().getLogger().lifecycle(e.getMessage(), e);
        }
    }

    private String getMethodObf(String klass, String method) {
        String methodName = method.substring(0, method.indexOf("("));
        String methodDescriptor = method.substring(method.indexOf("("));
        String methodObf = MappingUtil.methodCleanToObfMap.get(klass + "/" + methodName + " " + methodDescriptor);
        if (methodObf == null) {
            return null;
        }
        methodObf = "L" + methodObf.split(" ")[0].replace("/", ";") + methodObf.split(" ")[1];
        return methodObf;
    }

    private static class MixinScannerVisitor extends ClassVisitor {

        private AnnotationNode mixin = null;
        private final List<AnnotationNode> methodList = new ArrayList<>();
        private final HashMap<String, AnnotationNode> accessorList = new HashMap<>();

        String className;

        MixinScannerVisitor() {
            super(ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                return mixin = new AnnotationNode(descriptor);
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String methodDescriptor, String signature, String[] exceptions) {
            return new MethodVisitor(api, super.visitMethod(access, name, methodDescriptor, signature, exceptions)) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                        AnnotationNode inject = new AnnotationNode(descriptor);
                        methodList.add(inject);
                        return inject;
                    }

                    if (descriptor.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                        AnnotationNode accessor = new AnnotationNode(descriptor);
                        accessorList.put(methodDescriptor, accessor);
                        return accessor;
                    }
                    return super.visitAnnotation(descriptor, visible);
                }
            };
        }

        List<String> getMethods() {

            if (methodList.isEmpty()) {
                return new ArrayList<>();
            }

            List<String> methods = new ArrayList<>();

            for (AnnotationNode annotationNode : methodList) {
                List<String> privateMethod = getAnnotationValue(annotationNode, "method");
                if (privateMethod != null) {
                    methods.addAll(privateMethod);
                }
            }

            return methods;
        }

        List<String> getTargets() {

            if (methodList.isEmpty()) {
                return new ArrayList<>();
            }

            List<String> targets = new ArrayList<>();

            for (AnnotationNode annotationNode : methodList) {
                List<AnnotationNode> at = getAnnotationValue(annotationNode, "at");

                if (at == null) {
                    return new ArrayList<>();
                }

                for (AnnotationNode node : at) {
                    String privateTarget = getAnnotationValue(node, "target");

                    if (privateTarget != null) {
                        targets.add(privateTarget);
                    }
                }
            }

            return targets;
        }


        HashMap<String, String> getAccessors() {
            if (accessorList.isEmpty()) {
                return new HashMap<>();
            }

            HashMap<String, String> accessors = new HashMap<>();

            for (Map.Entry<String, AnnotationNode> entry : accessorList.entrySet()) {
                accessors.put(entry.getKey(), getAnnotationValue(entry.getValue(), "value"));
            }

            return accessors;
        }

        List<String> getMixins() {
            if (mixin == null) {
                return new ArrayList<>();
            }

            List<String> mixins = new ArrayList<>();
            List<Type> publicTargets = getAnnotationValue(mixin, "value");

            if (publicTargets != null) {
                for (Type type : publicTargets) {
                    mixins.add(type.getClassName().replace(".", "/"));
                }
            }

            return mixins;
        }

        @SuppressWarnings("unchecked")
        private <T> T getAnnotationValue(AnnotationNode annotationNode, String key) {
            boolean getNextValue = false;

            if (annotationNode.values == null) {
                return null;
            }

            for (Object value : annotationNode.values) {
                if (getNextValue) {
                    return (T) value;
                }
                if (value.equals(key)) {
                    getNextValue = true;
                }
            }

            return null;
        }
    }
}
