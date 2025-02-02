package com.imaginarycode.minecraft.redisbungee.events;


import java.util.UUID;

/**
 * This event is sent when a player disconnects. RedisBungee sends the event only when
 * the proxy the player has been connected to is different than the local proxy.
 * <p>
 * This event corresponds to {@link com.velocitypowered.api.event.connection.DisconnectEvent}, and is fired
 * asynchronously.
 *
 * @since 0.3.4
 */
public class PlayerLeftNetworkEvent {
    private final UUID uuid;

    public PlayerLeftNetworkEvent(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }
}
