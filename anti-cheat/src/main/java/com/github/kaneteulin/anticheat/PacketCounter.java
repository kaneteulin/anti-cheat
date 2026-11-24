package com.github.kaneteulin.anticheat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 플레이어 연결에 끼워 넣는 처리기.
 * <ul>
 *     <li>받는 패킷: 개수(패킷 과다 검사), 클라이언트 틱 간격(타이머 검사)</li>
 *     <li>보내는 패킷: 다른 플레이어의 정확한 체력과 흡수 체력을 숨김 (체력 표시 치트 대응)</li>
 * </ul>
 */
final class PacketCounter extends ChannelDuplexHandler {

    private static final String NAME = "anticheat_packet_counter";

    /** 접속 중인 플레이어의 엔티티 번호 (체력을 숨길 대상) */
    static final Set<Integer> PLAYER_IDS = ConcurrentHashMap.newKeySet();
    /** 체력 숨기기 사용 여부 */
    static volatile boolean hideHealth = true;

    private static final float SHOWN_HEALTH = 20.0f;
    private static final int HEALTH_ID = net.minecraft.world.entity.LivingEntity.DATA_HEALTH_ID.id();
    private static final EntityDataAccessor<Float> ABSORPTION = absorptionAccessor();

    private final PlayerData data;
    private final int selfId;

    private PacketCounter(PlayerData data, int selfId) {
        this.data = data;
        this.selfId = selfId;
    }

    static void inject(Player player, PlayerData data) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        int selfId = player.getEntityId();
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(NAME) == null && channel.pipeline().get("packet_handler") != null) {
                channel.pipeline().addBefore("packet_handler", NAME, new PacketCounter(data, selfId));
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

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (hideHealth) {
            msg = filter(msg);
        }
        super.write(ctx, msg, promise);
    }

    @SuppressWarnings("unchecked")
    private Object filter(Object msg) {
        if (msg instanceof ClientboundSetEntityDataPacket packet) {
            return hideHealth(packet);
        }
        if (msg instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                Packet<? super ClientGamePacketListener> replaced = sub instanceof ClientboundSetEntityDataPacket p ? hideHealth(p) : sub;
                changed |= replaced != sub;
                packets.add(replaced);
            }
            return changed ? new ClientboundBundlePacket(packets) : msg;
        }
        return msg;
    }

    /** 다른 플레이어의 체력을 고정값으로, 흡수 체력을 0 으로 (0 이하는 죽는 모습을 위해 그대로) */
    private ClientboundSetEntityDataPacket hideHealth(ClientboundSetEntityDataPacket packet) {
        if (packet.id() == selfId || !PLAYER_IDS.contains(packet.id())) {
            return packet;
        }
        List<SynchedEntityData.DataValue<?>> values = packet.packedItems();
        List<SynchedEntityData.DataValue<?>> result = null;

        for (int i = 0; i < values.size(); i++) {
            SynchedEntityData.DataValue<?> value = values.get(i);
            SynchedEntityData.DataValue<?> replaced = value;
            if (value.id() == HEALTH_ID && value.value() instanceof Float health && health > 0) {
                replaced = SynchedEntityData.DataValue.create(net.minecraft.world.entity.LivingEntity.DATA_HEALTH_ID, SHOWN_HEALTH);
            } else if (ABSORPTION != null && value.id() == ABSORPTION.id() && value.value() instanceof Float absorption && absorption > 0) {
                replaced = SynchedEntityData.DataValue.create(ABSORPTION, 0.0f);
            }
            if (replaced != value) {
                if (result == null) {
                    result = new ArrayList<>(values);
                }
                result.set(i, replaced);
            }
        }
        return result == null ? packet : new ClientboundSetEntityDataPacket(packet.id(), result);
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float> absorptionAccessor() {
        try {
            Field field = net.minecraft.world.entity.player.Player.class.getDeclaredField("DATA_PLAYER_ABSORPTION_ID");
            field.setAccessible(true);
            return (EntityDataAccessor<Float>) field.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
