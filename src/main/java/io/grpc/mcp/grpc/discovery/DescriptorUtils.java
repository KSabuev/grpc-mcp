package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FileDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public final class DescriptorUtils {

    private static final int FILE_MSG_TYPE = FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER;
    private static final int MSG_FIELD = DescriptorProto.FIELD_FIELD_NUMBER;
    private static final int MSG_NESTED_TYPE = DescriptorProto.NESTED_TYPE_FIELD_NUMBER;
    private static final int MSG_ENUM_TYPE = DescriptorProto.ENUM_TYPE_FIELD_NUMBER;
    private static final int ENUM_VALUE = EnumDescriptorProto.VALUE_FIELD_NUMBER;

    private DescriptorUtils() {}

    private static String pkgPrefix(FileDescriptorProto fdp) {
        String pkg = fdp.getPackage();
        return pkg.isEmpty() ? "" : pkg + ".";
    }

    public record DiscoveredServices(List<String> serviceNames, List<MethodMeta> methods) {}

    record CommentMaps(
            Map<String, String> methodComments,
            Map<String, String> serviceComments,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments,
            Map<String, String> messageComments
    ) {
        static CommentMaps extract(List<FileDescriptorProto> protoList) {
            Map<String, String> methodComments = new HashMap<>();
            Map<String, String> serviceComments = new HashMap<>();
            Map<String, String> fieldComments = new HashMap<>();
            Map<String, String> enumValueComments = new HashMap<>();
            Map<String, String> messageComments = new HashMap<>();

            for (FileDescriptorProto fdp : protoList) {
                SourceCodeInfo sci = fdp.getSourceCodeInfo();
                Map<List<Integer>, SourceCodeInfo.Location> locationIndex = buildLocationIndex(sci);
                String prefix = pkgPrefix(fdp);

                extractMethodComments(fdp, prefix, locationIndex, methodComments);
                extractServiceComments(fdp, prefix, locationIndex, serviceComments);
                collectFieldAndEnumComments(fdp, prefix, locationIndex, fieldComments, enumValueComments);
                collectMessageComments(fdp, prefix, locationIndex, messageComments);
            }
            return new CommentMaps(methodComments, serviceComments, fieldComments, enumValueComments, messageComments);
        }
    }

    private static Map<List<Integer>, SourceCodeInfo.Location> buildLocationIndex(SourceCodeInfo sci) {
        Map<List<Integer>, SourceCodeInfo.Location> index = new HashMap<>();
        for (SourceCodeInfo.Location loc : sci.getLocationList()) {
            index.put(loc.getPathList(), loc);
        }
        return index;
    }

    private static String findComment(Map<List<Integer>, SourceCodeInfo.Location> locationIndex, List<Integer> targetPath) {
        SourceCodeInfo.Location loc = locationIndex.get(targetPath);
        if (loc == null) return null;

        String comment = loc.getLeadingComments();
        if (!comment.isBlank()) {
            return comment.trim();
        }
        comment = loc.getTrailingComments();
        if (!comment.isBlank()) {
            return comment.trim();
        }
        return null;
    }

    static void extractMethodComments(FileDescriptorProto fdp, SourceCodeInfo sci, Map<String, String> methodComments) {
        extractMethodComments(fdp, pkgPrefix(fdp), buildLocationIndex(sci), methodComments);
    }

    static void extractServiceComments(FileDescriptorProto fdp, SourceCodeInfo sci, Map<String, String> serviceComments) {
        extractServiceComments(fdp, pkgPrefix(fdp), buildLocationIndex(sci), serviceComments);
    }

    static void collectMessageComments(FileDescriptorProto fdp, SourceCodeInfo sci, Map<String, String> messageComments) {
        collectMessageComments(fdp, pkgPrefix(fdp), buildLocationIndex(sci), messageComments);
    }

    static void collectFieldAndEnumComments(
            FileDescriptorProto fdp,
            SourceCodeInfo sci,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        collectFieldAndEnumComments(fdp, pkgPrefix(fdp), buildLocationIndex(sci), fieldComments, enumValueComments);
    }

    public static DiscoveredServices collectServices(List<FileDescriptorProto> protoList) {
        List<FileDescriptor> fileDescriptors = buildFileDescriptors(new ArrayList<>(protoList));
        CommentMaps comments = CommentMaps.extract(protoList);
        return buildMethodMetas(fileDescriptors, comments);
    }

    private static DiscoveredServices buildMethodMetas(List<FileDescriptor> fileDescriptors, CommentMaps comments) {
        Set<String> serviceNames = new LinkedHashSet<>();
        List<MethodMeta> result = new ArrayList<>();

        for (FileDescriptor fd : fileDescriptors) {
            for (Descriptors.ServiceDescriptor svcDesc : fd.getServices()) {
                String svcFullName = svcDesc.getFullName();
                serviceNames.add(svcFullName);
                String serviceComment = comments.serviceComments().get(svcFullName);

                for (Descriptors.MethodDescriptor methodDesc : svcDesc.getMethods()) {
                    result.add(buildMethodMeta(methodDesc, svcFullName, serviceComment, comments));
                }
            }
        }

        return new DiscoveredServices(List.copyOf(serviceNames), result);
    }

    private static MethodMeta buildMethodMeta(
            Descriptors.MethodDescriptor methodDesc,
            String svcFullName,
            String serviceComment,
            CommentMaps comments
    ) {
        String methodKey = svcFullName + "/" + methodDesc.getName();
        String inputMsgFullName = methodDesc.getInputType().getFullName();

        Map<String, String> inputFieldComments = new HashMap<>();
        for (Descriptors.FieldDescriptor f : methodDesc.getInputType().getFields()) {
            String key = inputMsgFullName + "." + f.getName();
            String comment = comments.fieldComments().get(key);
            if (comment != null) {
                inputFieldComments.put(f.getName(), comment);
            }
        }

        return MethodMeta.builder()
                .serviceName(svcFullName)
                .methodName(methodDesc.getName())
                .fullMethodName(methodKey)
                .inputDescriptor(methodDesc.getInputType())
                .outputDescriptor(methodDesc.getOutputType())
                .methodDescription(comments.methodComments().get(methodKey))
                .serviceDescription(serviceComment)
                .inputMessageDescription(comments.messageComments().get(inputMsgFullName))
                .fieldComments(inputFieldComments)
                .enumValueComments(collectEnumValueComments(methodDesc.getInputType(), comments.enumValueComments()))
                .serverStreaming(methodDesc.isServerStreaming())
                .clientStreaming(methodDesc.isClientStreaming())
                .build();
    }

    static List<FileDescriptor> buildFileDescriptors(List<FileDescriptorProto> protoList) {
        Map<String, FileDescriptorProto> protoMap = new LinkedHashMap<>();
        for (FileDescriptorProto p : protoList) {
            protoMap.put(p.getName(), p);
        }

        Map<String, FileDescriptor> resolved = new HashMap<>();
        List<FileDescriptor> result = new ArrayList<>();

        boolean changed = true;
        while (changed && !protoMap.isEmpty()) {
            changed = false;
            Iterator<Map.Entry<String, FileDescriptorProto>> it = protoMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, FileDescriptorProto> entry = it.next();
                FileDescriptorProto proto = entry.getValue();
                boolean depsResolved = true;
                List<FileDescriptor> deps = new ArrayList<>();
                for (String depName : proto.getDependencyList()) {
                    if (resolved.containsKey(depName)) {
                        deps.add(resolved.get(depName));
                    } else if (protoMap.containsKey(depName)) {
                        depsResolved = false;
                        break;
                    } else {
                        log.debug("Unknown dependency {} for {}, skipping", depName, proto.getName());
                    }
                }
                if (depsResolved) {
                    try {
                        FileDescriptor fd = FileDescriptor.buildFrom(proto, deps.toArray(new FileDescriptor[0]));
                        resolved.put(proto.getName(), fd);
                        result.add(fd);
                        it.remove();
                        changed = true;
                    } catch (Descriptors.DescriptorValidationException e) {
                        log.error("Failed to build FileDescriptor for {}: {}", proto.getName(), e.getMessage(), e);
                        it.remove();
                    }
                }
            }
        }
        return result;
    }

    private static void extractMethodComments(FileDescriptorProto fdp, String prefix,
                                               Map<List<Integer>, SourceCodeInfo.Location> locationIndex,
                                               Map<String, String> methodComments) {
        for (int si = 0; si < fdp.getServiceCount(); si++) {
            var svcProto = fdp.getService(si);
            String svcFullName = prefix + svcProto.getName();

            for (int mi = 0; mi < svcProto.getMethodCount(); mi++) {
                String methodName = svcProto.getMethod(mi).getName();
                String key = svcFullName + "/" + methodName;

                List<Integer> targetPath = Arrays.asList(
                        FileDescriptorProto.SERVICE_FIELD_NUMBER, si,
                        DescriptorProtos.ServiceDescriptorProto.METHOD_FIELD_NUMBER, mi
                );

                String comment = findComment(locationIndex, targetPath);
                if (comment != null) {
                    methodComments.put(key, comment);
                }
            }
        }
    }

    private static void extractServiceComments(
            FileDescriptorProto fdp,
            String prefix,
            Map<List<Integer>, SourceCodeInfo.Location> locationIndex,
            Map<String, String> serviceComments
    ) {
        for (int si = 0; si < fdp.getServiceCount(); si++) {
            var svcProto = fdp.getService(si);
            String svcFullName = prefix + svcProto.getName();

            List<Integer> targetPath = Arrays.asList(FileDescriptorProto.SERVICE_FIELD_NUMBER, si);

            String comment = findComment(locationIndex, targetPath);
            if (comment != null) {
                serviceComments.put(svcFullName, comment);
            }
        }
    }

    private static void collectMessageComments(
            FileDescriptorProto fdp,
            String prefix,
            Map<List<Integer>, SourceCodeInfo.Location> locationIndex,
            Map<String, String> messageComments
    ) {
        for (int mi = 0; mi < fdp.getMessageTypeCount(); mi++) {
            var msg = fdp.getMessageType(mi);
            String msgName = prefix + msg.getName();
            List<Integer> targetPath = Arrays.asList(FILE_MSG_TYPE, mi);
            String comment = findComment(locationIndex, targetPath);
            if (comment != null) {
                messageComments.put(msgName, comment);
            }
            collectNestedMessageComments(locationIndex, messageComments, msgName, targetPath, msg);
        }
    }

    private static void collectNestedMessageComments(
            Map<List<Integer>, SourceCodeInfo.Location> locationIndex,
            Map<String, String> messageComments,
            String parentName,
            List<Integer> parentPath, DescriptorProto msg
    ) {
        for (int ni = 0; ni < msg.getNestedTypeCount(); ni++) {
            var nested = msg.getNestedType(ni);
            String nestedName = parentName + "." + nested.getName();
            List<Integer> nestedPath = new ArrayList<>(parentPath);
            nestedPath.add(MSG_NESTED_TYPE);
            nestedPath.add(ni);

            String comment = findComment(locationIndex, nestedPath);
            if (comment != null) {
                messageComments.put(nestedName, comment);
            }
            collectNestedMessageComments(locationIndex, messageComments, nestedName, nestedPath, nested);
        }
    }

    private static void collectFieldAndEnumComments(
            FileDescriptorProto fdp,
            String prefix,
            Map<List<Integer>, SourceCodeInfo.Location> locationIndex,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        for (int mi = 0; mi < fdp.getMessageTypeCount(); mi++) {
            DescriptorProto msg = fdp.getMessageType(mi);
            collectFromMessage(prefix, msg.getName(), Arrays.asList(FILE_MSG_TYPE, mi),
                    msg, locationIndex, fieldComments, enumValueComments);
        }
    }

    static Map<String, String> collectEnumValueComments(
            Descriptors.Descriptor msg,
            Map<String, String> allEnumValueComments
    ) {
        Map<String, String> result = new HashMap<>();
        collectEnumValueComments(msg, allEnumValueComments, result, "", new HashSet<>());
        return result;
    }

    private static void collectEnumValueComments(
            Descriptors.Descriptor msg,
            Map<String, String> allEnumValueComments,
            Map<String, String> result, String prefix,
            Set<String> visited
    ) {
        String fullName = msg.getFullName();
        if (!visited.add(fullName)) return;

        String msgPrefix = prefix.isEmpty() ? fullName : prefix;
        for (Descriptors.FieldDescriptor field : msg.getFields()) {
            if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM) {
                String enumTypeName = field.getEnumType().getFullName();
                for (Descriptors.EnumValueDescriptor val : field.getEnumType().getValues()) {
                    String key = enumTypeName + "." + val.getName();
                    if (allEnumValueComments.containsKey(key)) {
                        result.put(key, allEnumValueComments.get(key));
                    }
                }
            }
            if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                collectEnumValueComments(field.getMessageType(), allEnumValueComments, result, msgPrefix, visited);
            }
        }
    }

    private static void collectFromMessage(
            String pkgPrefix,
            String msgName,
            List<Integer> msgPrefix,
            DescriptorProto msg,
            Map<List<Integer>, SourceCodeInfo.Location> locationIndex,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        String fullMsgName = pkgPrefix + msgName;

        for (int fi = 0; fi < msg.getFieldCount(); fi++) {
            List<Integer> fieldPath = new ArrayList<>(msgPrefix);
            fieldPath.add(MSG_FIELD);
            fieldPath.add(fi);

            String comment = findComment(locationIndex, fieldPath);
            if (comment != null) {
                fieldComments.put(fullMsgName + "." + msg.getField(fi).getName(), comment);
            }
        }

        collectEnumComment(locationIndex, msg, msgPrefix, fullMsgName, enumValueComments);

        for (int ni = 0; ni < msg.getNestedTypeCount(); ni++) {
            List<Integer> nestedPrefix = new ArrayList<>(msgPrefix);
            nestedPrefix.add(MSG_NESTED_TYPE);
            nestedPrefix.add(ni);

            collectFromMessage(pkgPrefix, msgName + "." + msg.getNestedType(ni).getName(),
                    nestedPrefix, msg.getNestedType(ni), locationIndex, fieldComments, enumValueComments);
        }
    }

    private static void collectEnumComment(
            Map<List<Integer>, SourceCodeInfo.Location> locationIndex,
            DescriptorProto msg,
            List<Integer> msgPrefix, String fullMsgName,
            Map<String, String> enumValueComments
    ) {
        for (int ei = 0; ei < msg.getEnumTypeCount(); ei++) {
            var enumType = msg.getEnumType(ei);
            for (int vi = 0; vi < enumType.getValueCount(); vi++) {
                List<Integer> enumPath = new ArrayList<>(msgPrefix);
                enumPath.add(MSG_ENUM_TYPE);
                enumPath.add(ei);
                enumPath.add(ENUM_VALUE);
                enumPath.add(vi);

                String comment = findComment(locationIndex, enumPath);
                if (comment != null) {
                    enumValueComments.put(fullMsgName + "." + enumType.getName() + "." + enumType.getValue(vi).getName(), comment);
                }
            }
        }
    }
}
