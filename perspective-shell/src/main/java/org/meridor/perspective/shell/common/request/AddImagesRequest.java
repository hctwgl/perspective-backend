package org.meridor.perspective.shell.common.request;

import org.meridor.perspective.beans.Image;
import org.meridor.perspective.beans.MetadataMap;
import org.meridor.perspective.shell.common.misc.DateUtils;
import org.meridor.perspective.shell.common.repository.InstancesRepository;
import org.meridor.perspective.shell.common.repository.impl.Placeholder;
import org.meridor.perspective.shell.common.result.FindInstancesResult;
import org.meridor.perspective.shell.common.validator.annotation.Name;
import org.meridor.perspective.shell.common.validator.annotation.Required;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.meridor.perspective.events.EventFactory.now;
import static org.meridor.perspective.shell.common.repository.impl.Placeholder.DATE;
import static org.meridor.perspective.shell.common.repository.impl.Placeholder.NAME;
import static org.meridor.perspective.shell.common.repository.impl.TextUtils.getPlaceholder;
import static org.meridor.perspective.shell.common.repository.impl.TextUtils.replacePlaceholders;
import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

@Component
@Scope(SCOPE_PROTOTYPE)
public class AddImagesRequest implements Request<List<Image>> {

    @Name("instances")
    @Required
    private String instanceNames;
    
    @Name("name")
    @Required
    private String imageName = String.format("%s-%s", getPlaceholder(NAME), getPlaceholder(DATE));

    private final InstancesRepository instancesRepository;

    private final RequestProvider requestProvider;

    private final DateUtils dateUtils;

    @Autowired
    public AddImagesRequest(InstancesRepository instancesRepository, RequestProvider requestProvider, DateUtils dateUtils) {
        this.instancesRepository = instancesRepository;
        this.requestProvider = requestProvider;
        this.dateUtils = dateUtils;
    }

    public AddImagesRequest withInstanceNames(String instanceNames) {
        this.instanceNames = instanceNames;
        return this;
    }
    
    public AddImagesRequest withName(String name) {
        this.imageName = name;
        return this;
    }

    @Override
    public List<Image> getPayload() {
        List<Image> images = new ArrayList<>();
        List<FindInstancesResult> matchingInstances = instancesRepository
                .findInstances(requestProvider.get(FindInstancesRequest.class).withNames(instanceNames));
        matchingInstances.forEach(i -> images.add(createImage(imageName, i)));
        return images;
    }
    
    private Image createImage(String imageName, FindInstancesResult instance) {
        Image image = new Image();
        String finalImageName = (imageName != null) ?
                replacePlaceholders(imageName, new HashMap<Placeholder, String>() {
                    {
                        put(NAME, instance.getName());
                        put(DATE, dateUtils.formatDate(now()));
                    }
                }) :
                String.format("%s-image", instance.getName());
        image.setName(finalImageName);
        image.setCloudId(instance.getCloudId());
        image.setCloudType(instance.getCloudType());
        List<String> projectIds = Collections.singletonList(instance.getProjectId());
        image.setProjectIds(projectIds);
        image.setInstanceId(instance.getId());
        MetadataMap metadataMap = new MetadataMap();
        image.setMetadata(metadataMap);
        return image;
    }

}
