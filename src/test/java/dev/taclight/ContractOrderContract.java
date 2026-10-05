package dev.taclight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

        // ★ 2026-09-28 reviewfix(横扫同类判据病的头号发现 F1):上面**全部**断言都只在
        //   "**解析到的**注册项"这个集合内部成立 —— 顺序、唯一、表头,一个不少地自洽。
        //   ⇒ 把某个契约的注册块(它的 `== X ==` 表头行 + `X.main(args);` 行)**整块删掉**:
        //      剩余项仍严格有序、仍唯一、仍都有表头 ⇒ 本契约**全绿**,而那个契约**从此不再运行**,
        //      `AllContracts` 也照旧打印 "ALL PASS" —— **一个发布包边界契约可以静默消失**。
        //   这正是本项目最恨的那类病:判据只保证"不该有的没出现",不保证"该有的都在"。
        //   (与 HudCommandContract ⑥ 同族:`REVIEW-U1.md` 的 U1-1 修的是那一条,本条是"同类还有谁"。)
        //   ⇒ 补一条**双向集合相等**正控:磁盘 `**/*Contract.java` 的简单类名集合 == 注册项简单类名集合。
        //   反事实(可机械复现):注释掉 AllContracts 里任一契约的两行 ⇒ 本条必红并**点名**是哪个契约。
        Path testRoot = Path.of("src/test/java/dev/taclight");
        check(Files.isDirectory(testRoot), "[正控·注册表不缩水] 能枚举契约源码目录: " + testRoot);
        if (Files.isDirectory(testRoot)) {
            List<String> onDisk = new ArrayList<>();
            try (var walk = Files.walk(testRoot)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Contract.java"))
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        onDisk.add(fn.substring(0, fn.length() - ".java".length()));
                    });
            }
            Set<String> diskSet = new HashSet<>(onDisk);
            List<String> notRegistered = new ArrayList<>();   // 盘上有、注册表没有 ⇒ 该契约不会跑
            for (String d : diskSet) if (!simples.contains(d)) notRegistered.add(d);
            List<String> ghost = new ArrayList<>();           // 注册表有、盘上没有 ⇒ 名字打错了
            for (String s : simples) if (!diskSet.contains(s)) ghost.add(s);
            Collections.sort(notRegistered);
            Collections.sort(ghost);
            check(notRegistered.isEmpty() && ghost.isEmpty(),
                    "[正控·注册表不缩水] 磁盘 *Contract.java 集合 == 注册项集合(盘上未注册=" + notRegistered
                            + " / 注册了但盘上没有=" + ghost + "; 盘上 " + diskSet.size() + " 个 / 注册 " + simples.size() + " 个)");
        }
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