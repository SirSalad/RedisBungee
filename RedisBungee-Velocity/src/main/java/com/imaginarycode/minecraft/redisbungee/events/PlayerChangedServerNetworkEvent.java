package com.imaginarycode.minecraft.redisbungee.events;


import java.util.UUID;

/**
 * This event is sent when a player connects to a new server. RedisBungee sends the event only when
 * the proxy the player has been connected to is different than the local proxy.
 * <p>
 * This event corresponds to {@link com.velocitypowered.api.event.player.ServerConnectedEvent}, and is fired
 * asynchronously.
 *
 * @since 0.3.4
 */
public class PlayerChangedServerNetworkEvent {
    private final UUID uuid;
    private final String previousServer;
    private final String server;

    public PlayerChangedServerNetworkEvent(UUID uuid, String previousServer, String server) {
        this.uuid = uuid;
        this.previousServer = previousServer;
        this.server = server;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getServer() {
        return server;
    }

    public String getPreviousServer() {
        return previousServer;
    }
}
