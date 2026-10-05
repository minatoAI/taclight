package dev.taclight.devonly.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 光影包补丁工具(dev/build 期,JavaExec 运行):
 * 读取 pack 目录 + patch.json 描述,生成"原包未被修改"的派生包。
 * 规则:所有锚点在原文件中必须唯一;写入 marker 保证幂等;输出文件若已含 marker 则视为已打补丁。
 * 用法:PackPatcherTool <packDir> <patchJson> <outDir>
 */
public final class PackPatcherTool {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: PackPatcherTool <packDir> <patchJson> <outDir>");
            System.exit(2);
        }
        Path packDir = Paths.get(args[0]);
        Path patchJson = Paths.get(args[1]);
        Path outDir = Paths.get(args[2]);

        JsonObject spec;
        try (var reader = Files.newBufferedReader(patchJson)) {
            spec = GSON.fromJson(reader, JsonObject.class);
        }
        String marker = spec.get("marker").getAsString();
        System.out.println("[PackPatcher] pack=" + packDir + " marker=" + marker);

        // 1) 整体复制原包到输出
        deleteRecursively(outDir);
        Files.createDirectories(outDir);
        try (var stream = Files.walk(packDir)) {
            stream.forEach(src -> {
                try {
                    Path rel = packDir.relativize(src);
                    Path dst = outDir.resolve(rel);
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        System.out.println("[PackPatcher] copied base pack");

        // 2) addFiles(新增 include 等)
        JsonArray addFiles = spec.getAsJsonArray("addFiles");
        for (JsonElement e : addFiles) {
            JsonObject o = e.getAsJsonObject();
            String dstPath = o.get("path").getAsString();
            String resource = o.get("resource").getAsString();
            Path dst = outDir.resolve(dstPath);
            Files.createDirectories(dst.getParent());
            byte[] content = loadResource(resource);
            Files.write(dst, content);
            System.out.println("[PackPatcher] added " + dstPath + " (" + content.length + "B)");
        }

        // 3) patchFiles
        JsonArray patchFiles = spec.getAsJsonArray("patchFiles");
        for (JsonElement e : patchFiles) {
            JsonObject o = e.getAsJsonObject();
            String file = o.get("file").getAsString();
            String op = o.get("op").getAsString();
            String anchor = o.get("anchor").getAsString();
            String content = o.get("content").getAsString();
            Path path = outDir.resolve(file);
            String text = Files.readString(path, StandardCharsets.UTF_8);

            if (text.contains(content)) {
                System.out.println("[PackPatcher] skip (content already present): " + file);
                continue;
            }
            int idx = indexOfUnique(text, anchor, file);
            String before, after;
            switch (op) {
                case "replace" -> { before = text.substring(0, idx) + content + text.substring(idx + anchor.length()); }
                case "insertAfter" -> { before = text.substring(0, idx + anchor.length()) + content + text.substring(idx + anchor.length()); }
                case "insertBefore" -> { before = text.substring(0, idx) + content + text.substring(idx); }
                default -> throw new IllegalArgumentException("unknown op: " + op);
            }
            Files.writeString(path, before, StandardCharsets.UTF_8);
            System.out.println("[PackPatcher] patched " + file + " (" + op + ") sha512=" + sha512(path));
        }
        System.out.println("[PackPatcher] DONE -> " + outDir);
    }

    private static int indexOfUnique(String text, String anchor, String file) {
        int first = text.indexOf(anchor);
        if (first < 0) throw new IllegalStateException("anchor not found in " + file + ": " + anchor);
        if (text.indexOf(anchor, first + 1) >= 0) {
            throw new IllegalStateException("anchor NOT unique in " + file + ": " + anchor);
        }
        return first;
    }

    private static byte[] loadResource(String resource) throws IOException {
        try (InputStream in = PackPatcherTool.class.getResourceAsStream("/" + resource)) {
            if (in != null) return in.readAllBytes();
        }
        Path fs = Paths.get("src", "main", "resources", resource);
        if (Files.exists(fs)) return Files.readAllBytes(fs);
        throw new IOException("resource not found: " + resource);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String sha512(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] d = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
