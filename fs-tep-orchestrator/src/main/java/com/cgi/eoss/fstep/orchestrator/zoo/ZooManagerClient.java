package com.cgi.eoss.fstep.orchestrator.zoo;

import com.cgi.eoss.fstep.model.FstepService;
import com.cgi.eoss.fstep.rpc.WpsServiceDescriptor;
import com.cgi.eoss.fstep.rpc.WpsServiceDescriptors;
import com.cgi.eoss.fstep.rpc.ZooManagerServiceGrpc;
import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p>gRPC client for ZOO management services.</p>
 */
@Component
public class ZooManagerClient {

    private final DiscoveryClient discoveryClient;
    private final String zooManagerServiceId;

    @Autowired
    public ZooManagerClient(DiscoveryClient discoveryClient,
                            @Value("${fstep.orchestrator.zooManager.eurekaServiceId:fs-tep zoo manager}") String zooManagerServiceId) {
        this.discoveryClient = discoveryClient;
        this.zooManagerServiceId = zooManagerServiceId;
    }

    /**
     * <p>Ask the ZOO Manager component to update the active of WPS services from the given collection.</p>
     */
    public void updateActiveZooServices(List<FstepService> services) {
        ServiceInstance zooManager = Iterables.getOnlyElement(discoveryClient.getInstances(zooManagerServiceId));

        ManagedChannel managedChannel = ManagedChannelBuilder.forAddress(zooManager.getHost(), Integer.parseInt(zooManager.getMetadata().get("grpcPort")))
                .usePlaintext(true)
                .build();

        ZooManagerServiceGrpc.ZooManagerServiceBlockingStub client = ZooManagerServiceGrpc.newBlockingStub(managedChannel);

        WpsServiceDescriptors.Builder argsBuilder = WpsServiceDescriptors.newBuilder();
        services.stream().map(this::convertToRpcFstepService).forEach(argsBuilder::addServices);

        client.updateActiveZooServices(argsBuilder.build());
    }

    private WpsServiceDescriptor convertToRpcFstepService(FstepService fstepService) {
        return WpsServiceDescriptor.newBuilder()
                .setName(fstepService.getName())
                .setContent(ByteString.copyFromUtf8(fstepService.getServiceDescriptor().toYaml()))
                .build();
    }

}
