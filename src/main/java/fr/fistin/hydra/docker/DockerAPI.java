package fr.fistin.hydra.docker;

import fr.fistin.hydra.Hydra;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class DockerAPI {

    private final Map<String, DockerCommand> dockerCommands;

    private final Hydra hydra;

    public DockerAPI(Hydra hydra) {
        this.hydra = hydra;
        this.dockerCommands = new HashMap<>();
    }

    public void callCommand(String name) {
        this.hydra.getScheduler().runTaskAsynchronously(() -> {
            final DockerCommand command = this.getDockerCommandByName(name);

            this.hydra.getLogger().log(Level.INFO, String.format("Calling command %s.", command.getName()));
            command.getTask().run();
        });
    }

    public void unload() {
        this.hydra.getLogger().log(Level.INFO, "Unloading docker commands...");
        this.dockerCommands.clear();
    }

    public DockerCommand getDockerCommandByName(String name) {
        return this.dockerCommands.getOrDefault(name, new DockerCommand("unknown", () -> this.hydra.getLogger().log(Level.WARNING, "Unknown command !")));
    }

    public Map<String, DockerCommand> getDockerCommands() {
        return this.dockerCommands;
    }
}
