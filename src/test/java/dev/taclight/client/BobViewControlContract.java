package dev.taclight.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure command contract for the reversible view-bobbing debug switch. */
public final class BobViewControlContract {
    private static int passed;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("FAIL: " + message);
        passed++;
        System.out.println("PASS: " + message);
    }

    public static void main(String[] args) {
        AtomicBoolean value = new AtomicBoolean(true);
        List<Boolean> writes = new ArrayList<>();

        check(dev.taclight.devonly.BobViewControl.configure("", value::get, next -> {
            value.set(next);
            writes.add(next);
        }).equals("on"), "空参查询当前 bob=on");
        check(writes.isEmpty(), "status 查询不写选项");
        check(dev.taclight.devonly.BobViewControl.configure("off", value::get, next -> {
            value.set(next);
            writes.add(next);
        }).equals("off") && !value.get(), "off 关闭并回显");
        check(dev.taclight.devonly.BobViewControl.configure("status", value::get, next -> {
            value.set(next);
            writes.add(next);
        }).equals("off"), "status 只读回显 off");
        check(dev.taclight.devonly.BobViewControl.configure("on", value::get, next -> {
            value.set(next);
            writes.add(next);
        }).equals("on") && value.get(), "on 恢复并回显");
        check(writes.equals(List.of(false, true)), "只对 on/off 各写一次");
        check(dev.taclight.devonly.BobViewControl.configure("toggle", value::get, value::set).contains("用法"),
                "非法参数失败且不扩展未批准语义");

        System.out.println("BobViewControlContract: ALL PASS (" + passed + " checks)");
    }
}
