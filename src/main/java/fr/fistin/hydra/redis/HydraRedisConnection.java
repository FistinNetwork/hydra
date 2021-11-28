package fr.fistin.hydra.redis;

import fr.fistin.hydra.util.References;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Project: Hydra
 * Created by AstFaster
 * on 25/10/2021 at 09:51
 */
public class HydraRedisConnection {

    private JedisPool jedisPool;

    private Thread reconnectTask;

    private boolean connected;

    private final String redisHost;
    private final int redisPort;
    private final String redisPass;

    public HydraRedisConnection() {
        this.redisHost = References.STACK_NAME + "_" + System.getenv("REDIS_HOST");
        this.redisPort = Integer.parseInt(System.getenv("REDIS_PORT"));
        this.redisPass = System.getenv("REDIS_PASS");
    }

    public boolean connect() {
        final JedisPoolConfig config = new JedisPoolConfig();

        config.setJmxEnabled(false);
        config.setMaxTotal(-1);

        if (this.redisPass != null && !this.redisPass.isEmpty()) {
            this.jedisPool = new JedisPool(config, this.redisHost, this.redisPort, 0, this.redisPass);
        } else {
            this.jedisPool = new JedisPool(config, this.redisHost, this.redisPort, 0);
        }

        try {
            this.getJedis().close();

            this.connected = true;

            System.out.println(References.NAME + " is now connected with Redis database.");

            this.startReconnectTask();

            return true;
        } catch (Exception e) {
            System.err.println("Couldn't connect to Redis database !");
            return false;
        }
    }

    private void startReconnectTask() {
        this.reconnectTask = new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            while (!Thread.interrupted()) {
                this.reconnect();

                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        this.reconnectTask.start();
    }

    private void reconnect() {
        try {
            this.getJedis().close();
        } catch (Exception e) {
            System.err.println("Encountered exception in Redis reconnection task. Error:" + e.getMessage());
            System.err.println("Error in Redis database connection ! Trying to reconnect...");

            this.connected = false;

            this.connect();
        }
    }

    public void disconnect() {
        System.out.println("Disconnecting " + References.NAME + " from Redis database...");

        if (this.reconnectTask != null && this.reconnectTask.isAlive()) {
            this.reconnectTask.interrupt();
        }

        this.connected = false;

        this.jedisPool.close();
    }

    public JedisPool getJedisPool() {
        return this.jedisPool;
    }

    public Jedis getJedis() {
        return this.jedisPool.getResource();
    }

    public boolean isConnected() {
        return this.connected;
    }

}