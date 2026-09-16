package dev.taclight.client;

import net.minecraft.world.entity.Entity;

/**
 * 当前正在渲染的实体(2026-09-02 里程碑②):LivingEntityRenderEntityMixin 在
 * LivingEntityRenderer.render 前后设置/清除,供 BeamRendererMixin 在第三人称
 * 激光渲染时归属枪口捕获的实体 id。渲染线程单线程读写,ThreadLocal 防御外部复用。
 */
public final class RenderedEntityTracker {
    private static final ThreadLocal<Entity> CURRENT = new ThreadLocal<>();

    private RenderedEntityTracker() {}

    public static void push(Entity entity) {
        CURRENT.set(entity);
    }

    public static void pop() {
        CURRENT.remove();
    }

    /** null = 不在实体渲染栈内(如 GUI/掉落物展示),调用方应放弃归属。 */
    public static Entity current() {
        return CURRENT.get();
    }
}
