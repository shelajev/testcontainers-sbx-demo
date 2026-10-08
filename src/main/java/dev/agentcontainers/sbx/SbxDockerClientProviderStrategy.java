package dev.agentcontainers.sbx;

import com.github.dockerjava.api.DockerClient;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;

/**
 * Discovered through Testcontainers' ServiceLoader SPI. Creating the provider does not start sbx.
 * One mountless sandbox is created for the selected provider and removed when the JVM exits.
 */
public final class SbxDockerClientProviderStrategy extends DockerClientProviderStrategy {
    private SandboxRuntime runtime;
    private DockerClient client;

    @Override
    public String getDescription() {
        return "Docker Sandboxes (sbx)";
    }

    @Override
    protected boolean isApplicable() {
        String os = System.getProperty("os.name");
        return os.equals("Linux") || os.equals("Mac OS X");
    }

    @Override
    protected int getPriority() {
        return 200;
    }

    @Override
    protected boolean isPersistable() {
        return false;
    }

    @Override
    public synchronized TransportConfig getTransportConfig() {
        if (runtime == null) runtime = SandboxRuntime.open(SbxConfiguration.load());
        runtime.checkAlive();
        return TransportConfig.builder().dockerHost(java.net.URI.create("unix://" + runtime.socket())).build();
    }

    @Override
    public synchronized DockerClient getDockerClient() {
        if (client == null) {
            getTransportConfig();
            client = PublishingDockerClient.wrap(super.getDockerClient(), container -> {
                runtime.checkAlive();
                runtime.publish(SandboxPorts.required(container));
            });
        }
        return client;
    }

    @Override
    protected boolean test() {
        boolean usable = false;
        try {
            usable = super.test();
            if (usable) getDockerClient().infoCmd().exec();
            return usable;
        } catch (RuntimeException e) {
            usable = false;
            throw e;
        } finally {
            if (!usable && runtime != null) runtime.close();
        }
    }

    @Override
    public String getDockerHostIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public String getRemoteDockerUnixSocketPath() {
        return SandboxRuntime.GUEST_SOCKET;
    }

    @Override
    public boolean allowUserOverrides() {
        return false;
    }
}
