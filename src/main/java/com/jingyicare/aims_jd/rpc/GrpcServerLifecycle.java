package com.jingyicare.aims_jd.rpc;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {
    public GrpcServerLifecycle(
        List<BindableService> services,
        @Value("${aims.jd.rpc.port:9090}") int port,
        @Value("${aims.jd.rpc.enabled:true}") boolean enabled
    ) {
        this.services = services == null ? List.of() : List.copyOf(services);
        this.port = port;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("AIMS JD gRPC server disabled");
            return;
        }
        if (running) {
            return;
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Invalid gRPC port: " + port);
        }
        if (services.isEmpty()) {
            throw new IllegalStateException("No gRPC services registered");
        }

        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port);
            for (BindableService service : services) {
                builder.addService(service);
            }
            server = builder.build().start();
            running = true;
            log.info("AIMS JD gRPC server started: port={} services={}", port, services.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start AIMS JD gRPC server on port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        Server current = server;
        server = null;
        running = false;
        if (current == null) {
            return;
        }

        current.shutdown();
        try {
            if (!current.awaitTermination(10, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
        log.info("AIMS JD gRPC server stopped");
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private final List<BindableService> services;
    private final int port;
    private final boolean enabled;
    private volatile boolean running;
    private Server server;
}
