package dev.taclight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 契约注册表**顺序**契约(2026-09-26 task-64)。
// 全序口径(与 AllContracts.java 顶部注释必须一致):
//   键1 = 契约【简单类名】按【序数(UTF-16 code unit)升序】(String.compareTo;禁文化敏感排序)
//   键2 = 并列时按【全限定类名】序数升序
// 形态纪律:一契约一行 main(args);新增只插整行/整块,永不改既有行。
public final class ContractOrderContract {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final String REGISTRY = "src/test/java/dev/taclight/AllContracts.java";

    public static void main(String[] args) throws Exception {
        String src = Files.readString(Path.of(REGISTRY), StandardCharsets.UTF_8);
        List<String> regs = new ArrayList<>();
        for (String line : src.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            if (!t.endsWith(".main(args);")) continue;
            String name = t.substring(0, t.length() - ".main(args);".length()).trim();
            if (name.isEmpty() || name.contains(" ") || name.contains("(")) continue;
            regs.add(name);
        }
        check(!regs.isEmpty(), "解析出契约注册行(实际 " + regs.size() + " 行)");
        Set<String> simples = new HashSet<>();
        for (String r : regs) simples.add(simple(r));
        check(simples.size() == regs.size(),
                "[一契约一行·名字唯一] 简单类名无重复(" + regs.size() + " 行 / 唯一 " + simples.size() + " 个)");
        List<String> bad = new ArrayList<>();
        for (int i = 1; i < regs.size(); i++) {
            if (cmp(regs.get(i - 1), regs.get(i)) >= 0) bad.add(regs.get(i - 1) + " > " + regs.get(i));
        }
        check(bad.isEmpty(), "[★交换即必红] 严格序数全序(简单类名 asc,并列按全限定名 asc);违反=" + bad);
        boolean headers = true;
        for (String s : simples) {
            if (!src.contains("System.out.println(\"== " + s + " ==\");")) headers = false;
        }
        check(headers, "每个注册项都有对应的 '== <简单类名> ==' 表头行");
        check(src.contains("新条目必须插入正确位置"), "顶部注释写明全序规则与'只插整行/整块'纪律");
        if (!FAILURES.isEmpty()) throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        System.out.println("ContractOrderContract: ALL PASS (" + checks + " checks)");
    }

    static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    static int cmp(String a, String b) {
        int c = simple(a).compareTo(simple(b));
        return c != 0 ? c : a.compareTo(b);
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (cond) System.out.println("PASS: " + what);
        else { FAILURES.add(what); System.out.println("FAIL: " + what); }
    }

    private ContractOrderContract() {}
}