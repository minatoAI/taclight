package dev.taclight.tacz;

/**
 * TaCZ 能力探测(软依赖)。本类不引用任何 TaCZ 类,永远安全。
 */
public final class TaczCompat {
    private static Boolean presentCache;

    public static boolean present() {
        if (presentCache == null) {
            try {
                Class.forName("com.tacz.guns.api.item.IGun");
                presentCache = true;
            } catch (ClassNotFoundException e) {
                presentCache = false;
            }
        }
        return presentCache;
    }

    private TaczCompat() {}
}
