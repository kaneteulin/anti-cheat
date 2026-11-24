package com.github.kaneteulin.anticheat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * 플레이어가 보내는 패킷 수(패킷 과다 검사)와 클라이언트 틱 간격(타이머 검사)을 센다. 패킷은 그대로 통과시킨다.
 */
final class PacketCounter extends ChannelDuplexHandler {

    private static final String NAME = "anticheat_packet_counter";

    private final PlayerData data;

    private PacketCounter(PlayerData data) {
        this.data = data;
    }

    static void inject(Player player, PlayerData data) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(NAME) == null && channel.pipeline().get("packet_handler") != null) {
                channel.pipeline().addBefore("packet_handler", NAME, new PacketCounter(data));
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        data.packets.incrementAndGet();
        if (msg instanceof ServerboundClientTickEndPacket) {
            data.timer.onClientTick(System.nanoTime());
        }
        super.channelRead(ctx, msg);
    }
}
