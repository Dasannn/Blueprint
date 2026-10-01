package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.PresentationConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Bukkit cannot read the current title/action bar. Observe outbound packets to avoid
 * clearing another plugin's newer overlay. Reflection follows the existing packet fake
 * renderer: no new dependency, and an unsupported bridge skips before delivery.
 */
final class PrivateScreen {
    private final ScreenOwnership ownership = new ScreenOwnership();
    private final Object pipeline;
    private final Object channel;
    private final Object connection;
    private final Method send;
    private final Object clear;
    private final List<Object> packets;
    private final String handlerName = "socialblueprint-screen-" + UUID.randomUUID();
    private final Class<?> pipelineType;
    private final Class<?> channelType;

    PrivateScreen(Player player, Component text, PresentationConfig.Flash config) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = handle.getClass().getField("connection").get(handle);
        connection = listener.getClass().getField("connection").get(listener);
        channel = connection.getClass().getField("channel").get(connection);
        channelType = Class.forName("io.netty.channel.Channel");
        pipelineType = Class.forName("io.netty.channel.ChannelPipeline");
        pipeline = channelType.getMethod("pipeline").invoke(channel);
        send = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        Class<?> componentType = Class.forName("net.minecraft.network.chat.Component");
        Method convert = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                .getMethod("asVanilla", Component.class);
        boolean actionBar = config.channel().equals("action-bar");
        String packetName = actionBar ? "ClientboundSetActionBarTextPacket" : "ClientboundSetTitleTextPacket";
        Object line = packet(packetName).getConstructor(componentType).newInstance(convert.invoke(null, text));
        if (actionBar) {
            packets = List.of(line);
            clear = packet(packetName).getConstructor(componentType).newInstance(convert.invoke(null, Component.empty()));
        } else {
            Object times = packet("ClientboundSetTitlesAnimationPacket").getConstructor(int.class, int.class, int.class)
                    .newInstance(config.fadeInTicks(), config.durationTicks(), config.fadeOutTicks());
            Object subtitle = packet("ClientboundSetSubtitleTextPacket").getConstructor(componentType)
                    .newInstance(convert.invoke(null, Component.empty()));
            packets = List.of(times, subtitle, line);
            clear = packet("ClientboundClearTitlesPacket").getConstructor(boolean.class).newInstance(false);
        }
        Class<?> outbound = Class.forName("io.netty.channel.ChannelOutboundHandler");
        Class<?> contextType = Class.forName("io.netty.channel.ChannelHandlerContext");
        Object observer = Proxy.newProxyInstance(outbound.getClassLoader(), new Class<?>[]{outbound}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> handlerName;
            };
            if (method.getName().equals("handlerAdded") || method.getName().equals("handlerRemoved")) return null;
            if (method.getName().equals("write")) {
                Object outgoing = args[1];
                boolean ours = packets.stream().anyMatch(p -> p == outgoing);
                String name = outgoing.getClass().getSimpleName();
                boolean replaces = isReplacement(outgoing, actionBar);
                if (ours) ownership.claimed();
                else if (replaces) ownership.replaced();
                else if (name.equals("ClientboundBundlePacket")) {
                    for (Object child : (Iterable<?>) outgoing.getClass().getMethod("subPackets").invoke(outgoing))
                        if (isReplacement(child, actionBar)) ownership.replaced();
                }
            }
            Class<?>[] types = Arrays.copyOfRange(method.getParameterTypes(), 1, method.getParameterCount());
            Object[] forwarded = Arrays.copyOfRange(args, 1, args.length);
            String name = method.getName().equals("exceptionCaught") ? "fireExceptionCaught" : method.getName();
            contextType.getMethod(name, types).invoke(args[0], forwarded);
            return null;
        });
        pipelineType.getMethod("addLast", String.class, Class.forName("io.netty.channel.ChannelHandler"))
                .invoke(pipeline, handlerName, observer);
    }

    void show() throws ReflectiveOperationException {
        for (Object packet : packets) send.invoke(connection, packet);
    }

    void restore() {
        if (!ownership.ended().compareAndSet(false, true)) return;
        try {
            // The event loop serializes ownership checks with external outbound writes.
            // Only packet objects/flags cross this boundary; no Bukkit access here.
            Executor loop = (Executor) channelType.getMethod("eventLoop").invoke(channel);
            loop.execute(() -> {
                try {
                    if (ownership.mayClear()) channelType.getMethod("writeAndFlush", Object.class).invoke(channel, clear);
                } catch (ReflectiveOperationException ignored) {
                    // A closed connection needs no client restoration.
                } finally {
                    try { pipelineType.getMethod("remove", String.class).invoke(pipeline, handlerName); }
                    catch (ReflectiveOperationException ignored) {}
                }
            });
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    private static Class<?> packet(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft.network.protocol.game." + name);
    }
    private static boolean isReplacement(Object outgoing, boolean actionBar) {
        String name = outgoing.getClass().getSimpleName();
        return actionBar ? name.equals("ClientboundSetActionBarTextPacket") || overlaySystemChat(outgoing)
                : name.equals("ClientboundSetTitleTextPacket") || name.equals("ClientboundSetSubtitleTextPacket")
                  || name.equals("ClientboundSetTitlesAnimationPacket") || name.equals("ClientboundClearTitlesPacket");
    }
    private static boolean overlaySystemChat(Object packet) {
        if (!packet.getClass().getSimpleName().equals("ClientboundSystemChatPacket")) return false;
        try { return (boolean) packet.getClass().getMethod("overlay").invoke(packet); }
        catch (ReflectiveOperationException ignored) { return true; } // relinquish rather than erase another feature
    }
}
