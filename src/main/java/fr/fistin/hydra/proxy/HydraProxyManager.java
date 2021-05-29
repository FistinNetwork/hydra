package fr.fistin.hydra.proxy;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.*;
import fr.fistin.hydra.Hydra;
import fr.fistin.hydra.docker.container.DockerContainer;
import fr.fistin.hydra.docker.image.DockerImage;
import fr.fistin.hydra.util.References;
import fr.fistin.hydraconnector.protocol.channel.HydraChannel;
import fr.fistin.hydraconnector.protocol.packet.event.ProxyStartedPacket;
import fr.fistin.hydraconnector.protocol.packet.event.ProxyStoppedPacket;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class HydraProxyManager {

    private final DockerImage minecraftProxyImage = new DockerImage("itzg/bungeecord", "latest");

    private final Map<String, HydraProxy> proxies;

    private final Hydra hydra;

    public HydraProxyManager(Hydra hydra) {
        this.hydra = hydra;
        this.proxies = new HashMap<>();
    }

    public void pullMinecraftProxyImage() {
        this.hydra.getImageManager().pullImage(this.minecraftProxyImage);
    }

    public void startProxy(HydraProxy proxy) {
        final DockerContainer container = new DockerContainer(proxy.toString(), this.minecraftProxyImage);
        container.setHostname(proxy.toString());
        container.setEnvs(proxy.getOptions().getEnvs());
        container.setPublishedPort(25565);

        proxy.setContainer(this.hydra.getContainerManager().runContainer(container));
        proxy.setStartedTime(System.currentTimeMillis());

        this.proxies.put(proxy.toString(), proxy);

        try {
            proxy.setProxyIp(InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        final Ports.Binding[] bindings = this.hydra.getContainerManager().inspectContainer(container).getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(25565));
        final int port = Integer.parseInt(bindings[0].getHostPortSpec());

        proxy.setProxyPort(port);

        this.hydra.getHydraConnector().getConnectionManager().sendPacket(HydraChannel.EVENT, new ProxyStartedPacket(proxy.toString()));
    }

    public boolean stopProxy(HydraProxy proxy) {
        if (!proxy.getContainer().isEmpty()) {
            try {
                this.hydra.getContainerManager().stopContainer(proxy.getContainer());

                proxy.setCurrentState(ProxyState.SHUTDOWN);

                this.hydra.getHydraConnector().getConnectionManager().sendPacket(HydraChannel.EVENT, new ProxyStoppedPacket(proxy.toString()));

                this.proxies.remove(proxy.toString());

                if (!this.hydra.isStopping()) this.hydra.getScheduler().schedule(() -> this.checkIfProxyHasShutdown(proxy), 1, 0, TimeUnit.MINUTES);

                return true;
            } catch (Exception e) {
                this.hydra.getLogger().log(Level.WARNING, String.format("Le proxy: %s ne s'est pas stoppé !", proxy));
            }
        }
        return false;
    }

    public void stopAllProxies() {
        this.hydra.getLogger().log(Level.INFO, "Stopping all proxies currently running...");

        for (Map.Entry<String, HydraProxy> entry : this.proxies.entrySet()) {
            this.hydra.getScheduler().runTaskAsynchronously(() -> this.stopProxy(entry.getValue()));
        }
    }

    public void checkIfProxyHasShutdown(HydraProxy proxy) {
        final List<Container> containers = this.hydra.getDocker().getDockerClient().listContainersCmd().exec();
        for (Container container : containers) {
            if (proxy.getContainer().equals(container.getId())) {
                this.hydra.getLogger().log(Level.SEVERE,  String.format("Le proxy: %s ne s'est pas stoppé !", proxy));
                this.hydra.getLogger().log(Level.INFO,  String.format("Tentative de kill sur le proxy: %s", proxy));
                this.hydra.getDocker().getDockerClient().killContainerCmd(proxy.getContainer()).exec();
            }
        }
        if (!this.hydra.isStopping()) this.removeProxy(proxy);
    }

    public void checkIfAllProxiesHaveShutdown() {
        this.hydra.getLogger().log(Level.INFO, "Checking if all proxies have shutdown...");

        for (Map.Entry<String, HydraProxy> entry : this.proxies.entrySet()) {
            this.checkIfProxyHasShutdown(entry.getValue());
        }
        this.proxies.clear();
    }

    public void checkStatus(HydraProxy proxy) {
        if (!proxy.getContainer().isEmpty()) {
            final InspectContainerResponse.ContainerState containerState = this.hydra.getDocker().getDockerClient()
                    .inspectContainerCmd(proxy.getContainer())
                    .exec()
                    .getState();

            switch (containerState.getHealth().getStatus()) {
                case "starting":
                    proxy.setCurrentState(ProxyState.STARTING);
                    break;
                case "healthy":
                    proxy.setCurrentState(ProxyState.READY);
                    break;
                default:
                    proxy.setCurrentState(ProxyState.IDLE);
            }
        }
    }

    public void addProxy(HydraProxy proxy) {
        this.proxies.put(proxy.toString(), proxy);
    }

    public void removeProxy(HydraProxy proxy) {
        this.proxies.remove(proxy.toString());
    }

    public Map<String, HydraProxy> getProxies() {
        return this.proxies;
    }
}
