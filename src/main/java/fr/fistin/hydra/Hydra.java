package fr.fistin.hydra;

import fr.fistin.hydra.api.HydraAPI;
import fr.fistin.hydra.api.protocol.HydraChannel;
import fr.fistin.hydra.api.protocol.HydraConnection;
import fr.fistin.hydra.api.protocol.environment.HydraEnvironment;
import fr.fistin.hydra.configuration.HydraConfiguration;
import fr.fistin.hydra.configuration.nested.HydraRedisConfiguration;
import fr.fistin.hydra.docker.Docker;
import fr.fistin.hydra.proxy.HydraProxyManager;
import fr.fistin.hydra.receiver.HydraQueryReceiver;
import fr.fistin.hydra.redis.HydraRedisConnection;
import fr.fistin.hydra.server.HydraServerManager;
import fr.fistin.hydra.util.References;
import fr.fistin.hydra.util.logger.HydraLogger;
import fr.fistin.hydra.util.logger.HydraLoggingOutputStream;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jline.console.ConsoleReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class Hydra {

    /** Logger */
    private ConsoleReader consoleReader;
    private HydraLogger logger;

    /** Security */
    private PrivateKey privateKey;
    private PublicKey publicKey;

    /** Docker */
    private Docker docker;

    /** Redis */
    private HydraRedisConnection redisConnection;

    /** Hydra */
    private HydraConfiguration configuration;
    private HydraAPI api;
    private HydraEnvironment environment;
    private HydraProxyManager proxyManager;
    private HydraServerManager serverManager;

    /** State */
    private boolean running = false;

    public void start() {
        HydraLogger.printHeaderMessage();

        this.setupConsoleReader();
        this.setupLogger();

        System.out.println("Starting " + References.NAME + "...");

        this.configuration = HydraConfiguration.load();

        this.loadKeys();
        this.createEnvironment();

        this.docker = new Docker();
        this.redisConnection = new HydraRedisConnection(this.configuration.getRedisConfiguration());

        if (!this.redisConnection.connect()) {
            System.exit(-1);
        }

        this.api = new HydraAPI.Builder(HydraAPI.Type.SERVER)
                .withLogger(this.logger)
                .withLogHeader("API")
                .withPrivateKey(this.privateKey)
                .withPublicKey(this.publicKey)
                .withJedisPool(this.redisConnection.getJedisPool())
                .build();
        this.api.start();
        this.proxyManager = new HydraProxyManager(this);
        this.serverManager = new HydraServerManager(this);

        this.registerReceivers();

        this.proxyManager.startProxy();

        this.running = true;

        this.api.getExecutorService().schedule(() -> {
            final String[] types = new String[]{"lobby", "rtf", "tnttag", "wr"};

            for (String type : types) {
                this.serverManager.startServer(type);
            }
        }, 15, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public void shutdown() {
        if (this.running) {
            System.out.println("Stopping " + References.NAME + "...");

            this.running = false;

            if (this.redisConnection != null && this.redisConnection.isConnected()) {
                this.api.stop("Stopping " + References.NAME + " application");
                this.redisConnection.disconnect();
            }

            System.out.println(References.NAME + " is now down. See you soon!");
        }
    }

    private void setupConsoleReader() {
        try {
            this.consoleReader = new ConsoleReader();
            this.consoleReader.setExpandEvents(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupLogger() {
        try {
            if (!Files.exists(References.LOG_FOLDER)) {
                Files.createDirectory(References.LOG_FOLDER);
            }

            this.logger = new HydraLogger(this, References.NAME, References.LOG_FILE.toString());

            System.setErr(new PrintStream(new HydraLoggingOutputStream(this.logger, Level.SEVERE), true));
            System.setOut(new PrintStream(new HydraLoggingOutputStream(this.logger, Level.INFO), true));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadKeys() {
        final SignatureAlgorithm algorithm = SignatureAlgorithm.RS256;

        try {
            final KeyFactory keyFactory = KeyFactory.getInstance(algorithm.getFamilyName());

            this.privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(References.PRIVATE_KEY_FILE)));

            System.out.println("Private key read from file.");
            System.out.println("Generating public key from private one...");

            final RSAPrivateCrtKey rsaPrivateCrtKey = (RSAPrivateCrtKey) this.privateKey;
            final RSAPublicKeySpec keySpec = new RSAPublicKeySpec(rsaPrivateCrtKey.getModulus(), rsaPrivateCrtKey.getPublicExponent());

            this.publicKey = keyFactory.generatePublic(keySpec);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            System.out.println("Generating key pair...");

            final KeyPair keyPair = Keys.keyPairFor(algorithm);

            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();

            try {
                System.out.println("Writing private key in file...");

                Files.write(References.PRIVATE_KEY_FILE, this.privateKey.getEncoded());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void createEnvironment() {
        System.out.println("Creating environment...");

        final HydraRedisConfiguration config = this.configuration.getRedisConfiguration();

        this.environment = new HydraEnvironment(config.getRedisHost(), config.getRedisPort(), config.getRedisPassword(), this.publicKey);
    }

    private void registerReceivers() {
        final HydraConnection connection = this.api.getConnection();

        connection.registerReceiver(HydraChannel.QUERY, new HydraQueryReceiver(this));
    }

    public ConsoleReader getConsoleReader() {
        return this.consoleReader;
    }

    public HydraLogger getLogger() {
        return this.logger;
    }

    public HydraConfiguration getConfiguration() {
        return this.configuration;
    }

    public Docker getDocker() {
        return this.docker;
    }

    public HydraRedisConnection getRedisConnection() {
        return this.redisConnection;
    }

    public PublicKey getPublicKey() {
        return this.publicKey;
    }

    public HydraAPI getAPI() {
        return this.api;
    }

    public HydraEnvironment getEnvironment() {
        return this.environment;
    }

    public HydraProxyManager getProxyManager() {
        return this.proxyManager;
    }

    public HydraServerManager getServerManager() {
        return this.serverManager;
    }

    public boolean isRunning() {
        return this.running;
    }

}