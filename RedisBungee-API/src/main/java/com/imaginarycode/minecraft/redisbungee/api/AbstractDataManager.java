package com.imaginarycode.minecraft.redisbungee.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imaginarycode.minecraft.redisbungee.api.tasks.RedisTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;

import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * This class manages all the data that RedisBungee fetches from Redis, along with updates to that data.
 *
 * @since 0.3.3
 */
public abstract class AbstractDataManager<P, PL, PD, PS> {
    private final RedisBungeePlugin<P> plugin;
    private final Cache<UUID, String> serverCache = createCache();
    private final Cache<UUID, String> proxyCache = createCache();
    private final Cache<UUID, InetAddress> ipCache = createCache();
    private final Cache<UUID, Long> lastOnlineCache = createCache();
    private final Gson gson = new Gson();

    public AbstractDataManager(RedisBungeePlugin<P> plugin) {
        this.plugin = plugin;
    }

    private static <K, V> Cache<K, V> createCache() {
        // TODO: Allow customization via cache specification, ala ServerListPlus
        return CacheBuilder.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    private final JsonParser parser = new JsonParser();

    public String getServer(final UUID uuid) {
        P player = plugin.getPlayer(uuid);

        if (player != null)
            return plugin.isPlayerOnAServer(player) ? plugin.getPlayerServerName(player) : null;

        try {
            return serverCache.get(uuid, new RedisTask<String>(plugin.getApi()) {
                @Override
                public String jedisTask(Jedis jedis) {
                    return Objects.requireNonNull(jedis.hget("player:" + uuid, "server"), "user not found");

                }

                @Override
                public String clusterJedisTask(JedisCluster jedisCluster) {
                    return Objects.requireNonNull(jedisCluster.hget("player:" + uuid, "server"), "user not found");

                }
            });
        } catch (ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
                return null; // HACK
            plugin.logFatal("Unable to get server");
            throw new RuntimeException("Unable to get server for " + uuid, e);
        }
    }


    public String getProxy(final UUID uuid) {
        P player = plugin.getPlayer(uuid);

        if (player != null)
            return plugin.getConfiguration().getProxyId();

        try {
            return proxyCache.get(uuid, new RedisTask<String>(plugin.getApi()) {
                @Override
                public String jedisTask(Jedis jedis) {
                    return Objects.requireNonNull(jedis.hget("player:" + uuid, "proxy"), "user not found");
                }

                @Override
                public String clusterJedisTask(JedisCluster jedisCluster) {
                    return Objects.requireNonNull(jedisCluster.hget("player:" + uuid, "proxy"), "user not found");
                }
            });
        } catch (ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
                return null; // HACK
            plugin.logFatal("Unable to get proxy");
            throw new RuntimeException("Unable to get proxy for " + uuid, e);
        }
    }

    public InetAddress getIp(final UUID uuid) {
        P player = plugin.getPlayer(uuid);

        if (player != null)
            return plugin.getPlayerIp(player);

        try {
            return ipCache.get(uuid, new RedisTask<InetAddress>(plugin.getApi()) {
                @Override
                public InetAddress jedisTask(Jedis jedis) {
                    String result = jedis.hget("player:" + uuid, "ip");
                    if (result == null)
                        throw new NullPointerException("user not found");
                    return InetAddresses.forString(result);
                }

                @Override
                public InetAddress clusterJedisTask(JedisCluster jedisCluster) {
                    String result = jedisCluster.hget("player:" + uuid, "ip");
                    if (result == null)
                        throw new NullPointerException("user not found");
                    return InetAddresses.forString(result);
                }
            });
        } catch (ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
                return null; // HACK
            plugin.logFatal("Unable to get IP");
            throw new RuntimeException("Unable to get IP for " + uuid, e);
        }
    }

    public long getLastOnline(final UUID uuid) {
        P player = plugin.getPlayer(uuid);

        if (player != null)
            return 0;

        try {
            return lastOnlineCache.get(uuid, new RedisTask<Long>(plugin.getApi()) {
                @Override
                public Long jedisTask(Jedis jedis) {
                    String result = jedis.hget("player:" + uuid, "online");
                    return result == null ? -1 : Long.parseLong(result);
                }

                @Override
                public Long clusterJedisTask(JedisCluster jedisCluster) {
                    String result = jedisCluster.hget("player:" + uuid, "online");
                    return result == null ? -1 : Long.parseLong(result);
                }
            });
        } catch (ExecutionException e) {
            plugin.logFatal("Unable to get last time online");
            throw new RuntimeException("Unable to get last time online for " + uuid, e);
        }
    }

    protected void invalidate(UUID uuid) {
        ipCache.invalidate(uuid);
        lastOnlineCache.invalidate(uuid);
        serverCache.invalidate(uuid);
        proxyCache.invalidate(uuid);
    }

    // Invalidate all entries related to this player, since they now lie. (call invalidate(uuid))
    public abstract void onPostLogin(PL event);

    // Invalidate all entries related to this player, since they now lie. (call invalidate(uuid))
    public abstract void onPlayerDisconnect(PD event);

    public abstract void onPubSubMessage(PS event);

    protected void handlePubSubMessage(String channel, String message) {
        if (!channel.equals("redisbungee-data"))
            return;

        // Partially deserialize the message so we can look at the action
        JsonObject jsonObject = parser.parse(message).getAsJsonObject();

        String source = jsonObject.get("source").getAsString();

        if (source.equals(plugin.getConfiguration().getProxyId()))
            return;

        DataManagerMessage.Action action = DataManagerMessage.Action.valueOf(jsonObject.get("action").getAsString());

        switch (action) {
            case JOIN:
                final DataManagerMessage<LoginPayload> message1 = gson.fromJson(jsonObject, new TypeToken<DataManagerMessage<LoginPayload>>() {
                }.getType());
                proxyCache.put(message1.getTarget(), message1.getSource());
                lastOnlineCache.put(message1.getTarget(), (long) 0);
                ipCache.put(message1.getTarget(), message1.getPayload().getAddress());
                plugin.executeAsync(new Runnable() {
                    @Override
                    public void run() {
                        Object event;
                        try {
                            event = plugin.getNetworkJoinEventClass().getDeclaredConstructor(UUID.class).newInstance(message1.getTarget());
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                                 NoSuchMethodException e) {
                            throw new RuntimeException("unable to dispatch an network join event", e);
                        }
                        plugin.callEvent(event);

                    }
                });
                break;
            case LEAVE:
                final DataManagerMessage<LogoutPayload> message2 = gson.fromJson(jsonObject, new TypeToken<DataManagerMessage<LogoutPayload>>() {
                }.getType());
                invalidate(message2.getTarget());
                lastOnlineCache.put(message2.getTarget(), message2.getPayload().getTimestamp());
                plugin.executeAsync(new Runnable() {
                    @Override
                    public void run() {
                        Object event;
                        try {
                            event = plugin.getNetworkQuitEventClass().getDeclaredConstructor(UUID.class).newInstance(message2.getTarget());
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                                 NoSuchMethodException e) {
                            throw new RuntimeException("unable to dispatch an network quit event", e);
                        }
                        plugin.callEvent(event);
                    }
                });
                break;
            case SERVER_CHANGE:
                final DataManagerMessage<ServerChangePayload> message3 = gson.fromJson(jsonObject, new TypeToken<DataManagerMessage<ServerChangePayload>>() {
                }.getType());
                serverCache.put(message3.getTarget(), message3.getPayload().getServer());
                plugin.executeAsync(new Runnable() {
                    @Override
                    public void run() {
                        Object event;
                        try {
                            event = plugin.getServerChangeEventClass().getDeclaredConstructor(UUID.class, String.class, String.class).newInstance(message3.getTarget(), ((ServerChangePayload) message3.getPayload()).getOldServer(), ((ServerChangePayload) message3.getPayload()).getServer());
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                                 NoSuchMethodException e) {
                            throw new RuntimeException("unable to dispatch an server change event", e);
                        }
                        plugin.callEvent(event);
                    }
                });
                break;
        }
    }

    public static class DataManagerMessage<T> {
        private final UUID target;
        private final String source;
        private final Action action; // for future use!
        private final T payload;

        public DataManagerMessage(UUID target, String source, Action action, T payload) {
            this.target = target;
            this.source = source;
            this.action = action;
            this.payload = payload;
        }

        public UUID getTarget() {
            return target;
        }

        public String getSource() {
            return source;
        }

        public Action getAction() {
            return action;
        }

        public T getPayload() {
            return payload;
        }

        public enum Action {
            JOIN,
            LEAVE,
            SERVER_CHANGE
        }
    }

    public static class LoginPayload {
        private final InetAddress address;

        public LoginPayload(InetAddress address) {
            this.address = address;
        }

        public InetAddress getAddress() {
            return address;
        }
    }

    public static class ServerChangePayload {
        private final String server;
        private final String oldServer;

        public ServerChangePayload(String server, String oldServer) {
            this.server = server;
            this.oldServer = oldServer;
        }

        public String getServer() {
            return server;
        }

        public String getOldServer() {
            return oldServer;
        }
    }


    public static class LogoutPayload {
        private final long timestamp;

        public LogoutPayload(long timestamp) {
            this.timestamp = timestamp;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}