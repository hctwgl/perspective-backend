package org.meridor.perspective.openstack;

import org.meridor.perspective.config.Cloud;
import org.meridor.perspective.worker.operation.OperationUtils;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.types.Facing;
import org.openstack4j.api.types.ServiceType;
import org.openstack4j.core.transport.Config;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.*;
import org.openstack4j.model.compute.actions.RebuildOptions;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.identity.v3.Endpoint;
import org.openstack4j.model.identity.v3.Service;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.Network;
import org.openstack4j.openstack.OSFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Component
public class ApiProviderImpl implements ApiProvider {

    private final OperationUtils operationUtils;

    @Autowired
    public ApiProviderImpl(OperationUtils operationUtils) {
        this.operationUtils = operationUtils;
    }

    @Override
    public Api getApi(Cloud cloud, String region) {
        return region != null ? new ApiImpl(cloud, region) : new ApiImpl(cloud);
    }

    private Api getApi(Cloud cloud) {
        return getApi(cloud, null);
    }

    @Override
    public void forEachRegion(Cloud cloud, BiConsumer<String, Api> action) {
        Api api = getApi(cloud);
        Set<String> computeRegions = api.listComputeRegions();
        computeRegions.forEach(cr -> action.accept(cr, new ApiImpl(cloud, cr)));
    }

    private class ApiImpl implements Api {

        //TODO: probably implement API pooling

        private final OSClient.OSClientV3 api;

        private ApiImpl(Cloud cloud) {
            this(cloud, null);
        }

        private ApiImpl(Cloud cloud, String region) {
            this.api = createApi(cloud, region);
        }

        private OSClient.OSClientV3 createApi(Cloud cloud, String region) {
            return operationUtils.fromProjectAndUserName(cloud, (projectName, userName) -> {
                OSClient.OSClientV3 api = OSFactory.builderV3()
                        .withConfig(getConnectionSettings())
                        .endpoint(cloud.getEndpoint())
                        .credentials(userName, cloud.getCredential(), Identifier.byId("default"))
                        .scopeToProject(Identifier.byName(projectName), Identifier.byName("default"))
                        .authenticate();
                return region != null ? api.useRegion(region) : api;
            });
        }

        private Config getConnectionSettings() {
            return Config.newConfig()
                    .withConnectionTimeout(10000)
                    .withReadTimeout(30000);
        }

        @Override
        public Set<String> listComputeRegions() {
            return getRegions(ServiceType.COMPUTE);
        }

        private Set<String> getRegions(ServiceType serviceType) {

            Optional<? extends Service> serviceCandidate = api.getToken().getCatalog().stream()
                    .filter(s -> s.getType().equals(serviceType.getType()))
                    .findFirst();

            return serviceCandidate.isPresent() ?
                    serviceCandidate.get().getEndpoints().stream()
                            .filter(e -> e.getIface() == Facing.PUBLIC)
                            .map(Endpoint::getRegion)
                            .collect(Collectors.toSet()) :
                    Collections.emptySet();
        }

        @Override
        public List<? extends Flavor> listFlavors() {
            return api.compute().flavors().list();
        }

        @Override
        public List<? extends Network> listNetworks() {
            return api.networking().network().list();
        }

        @Override
        public List<? extends AvailabilityZone> listAvailabilityZones() {
            return api.compute().zones().list();
        }

        @Override
        public String addKeypair(Keypair keypair) {
            return api.compute().keypairs().create(
                    keypair.getName(),
                    keypair.getPublicKey()
            ).getPrivateKey();
        }

        @Override
        public List<? extends Keypair> listKeypairs() {
            return api.compute().keypairs().list();
        }

        @Override
        public AbsoluteLimit getQuota() {
            return api.compute().quotaSets().limits().getAbsolute();
        }

        @Override
        public String addInstance(ServerCreate serverConfig) {
            return api.compute().servers().boot(serverConfig).getId();
        }

        @Override
        public boolean deleteInstance(String instanceId) {
            return api.compute().servers().delete(instanceId).isSuccess();
        }

        @Override
        public boolean startInstance(String instanceId) {
            return api.compute().servers().action(instanceId, Action.START).isSuccess();
        }

        @Override
        public boolean shutdownInstance(String instanceId) {
            return api.compute().servers().action(instanceId, Action.STOP).isSuccess();
        }

        @Override
        public boolean rebootInstance(String instanceId) {
            return api.compute().servers().reboot(instanceId, RebootType.SOFT).isSuccess();
        }

        @Override
        public boolean hardRebootInstance(String instanceId) {
            return api.compute().servers().reboot(instanceId, RebootType.HARD).isSuccess();
        }

        @Override
        public boolean resizeInstance(String instanceId, String flavorId) {
            return api.compute().servers().resize(instanceId, flavorId).isSuccess();
        }

        @Override
        public boolean confirmInstanceResize(String instanceId) {
            return api.compute().servers().confirmResize(instanceId).isSuccess();
        }

        @Override
        public boolean revertInstanceResize(String instanceId) {
            return api.compute().servers().revertResize(instanceId).isSuccess();
        }

        @Override
        public boolean renameInstance(String instanceId, String newName) {
            Server updatedServer = api.compute().servers().update(
                    instanceId,
                    ServerUpdateOptions.create().name(newName)
            );
            return updatedServer.getName().equals(newName);
        }

        @Override
        public boolean rebuildInstance(String instanceId, String imageId) {
            return api.compute().servers().rebuild(
                    instanceId,
                    RebuildOptions.create().image(imageId)
            ).isSuccess();
        }

        @Override
        public boolean pauseInstance(String instanceId) {
            return api.compute().servers().action(instanceId, Action.PAUSE).isSuccess();
        }

        @Override
        public boolean unpauseInstance(String instanceId) {
            return api.compute().servers().action(instanceId, Action.UNPAUSE).isSuccess();
        }

        @Override
        public boolean suspendInstance(String instanceId) {
            return api.compute().servers().action(instanceId, Action.SUSPEND).isSuccess();
        }

        @Override
        public boolean resumeInstance(String instanceId) {
            return api.compute().servers().action(instanceId, Action.RESUME).isSuccess();
        }

        @Override
        public List<? extends Server> listInstances() {
            return api.compute().servers().list(true);
        }

        @Override
        public Optional<Server> getInstanceById(String instanceId) {
            return Optional.ofNullable(api.compute().servers().get(instanceId));
        }

        @Override
        public String getInstanceConsoleUrl(String instanceId, String consoleType) {
            return api.compute().servers().getVNCConsole(instanceId, VNCConsole.Type.value(consoleType)).getURL();
        }

        @Override
        public String addImage(String instanceId, String imageName) {
            return api.compute().servers().createSnapshot(instanceId, imageName);
        }

        @Override
        public boolean deleteImage(String imageId) {
            return api.compute().images().delete(imageId).isSuccess();
        }

        @Override
        public List<? extends org.openstack4j.model.image.Image> listImages() {
            return api.images().listAll();
        }

        @Override
        public Optional<Image> getImageById(String imageId) {
            return Optional.ofNullable(api.images().get(imageId));
        }

    }

}
