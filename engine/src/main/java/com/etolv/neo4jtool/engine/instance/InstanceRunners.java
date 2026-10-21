package com.etolv.neo4jtool.engine.instance;

public final class InstanceRunners {

    private InstanceRunners() {
    }

    public static InstanceRunner create(InstanceSettings settings) {
        return switch (settings.runner()) {
            case DOCKER -> new DockerRunner(settings);
            case LOCAL -> new LocalRunner(settings);
        };
    }
}
