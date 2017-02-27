package org.meridor.perspective.shell.interactive.commands;

import org.meridor.perspective.shell.common.repository.ImagesRepository;
import org.meridor.perspective.shell.common.repository.InstancesRepository;
import org.meridor.perspective.shell.common.request.AddImagesRequest;
import org.meridor.perspective.shell.common.request.AddInstancesRequest;
import org.meridor.perspective.shell.common.request.RequestProvider;
import org.meridor.perspective.shell.interactive.CommandExecutor;
import org.meridor.perspective.shell.interactive.wizard.images.AddImagesWizard;
import org.meridor.perspective.shell.interactive.wizard.instances.add.AddInstancesWizard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;

@Component
public class AddCommands extends BaseCommands {
    
    @Autowired
    private InstancesRepository instancesRepository;
    
    @Autowired
    private ImagesRepository imagesRepository;
    
    @Autowired
    private AddInstancesWizard addInstancesWizard;
    
    @Autowired
    private AddImagesWizard addImagesWizard;

    @Autowired
    private RequestProvider requestProvider;
    
    @Autowired
    private EntityFormatter entityFormatter;
    
    @Autowired
    private CommandExecutor commandExecutor;

    @CliCommand(value = "add instances", help = "Add one or more instances to project")
    public void addInstance(
            @CliOption(key = "name", help = "Instance name") String name,
            @CliOption(key = "project", help = "Name of the project to launch instance in") String project,
            @CliOption(key = "flavor", help = "Instance flavor") String flavor,
            @CliOption(key = "image", help = "Instance image") String image,
            @CliOption(key = "network", help = "Name of the network to use") String network,
            @CliOption(key = "keypair", help = "Name of the keypair to use") String keypair,
            @CliOption(key = "range", help = "A range of numbers to launch instances with") String range,
            @CliOption(key = "count", help = "How many instances to launch") Integer count,
            @CliOption(key = "options", help = "Various instance options") String options
    ) {
        if (name != null) {
            AddInstancesRequest addInstancesRequest = requestProvider.get(AddInstancesRequest.class)
                    .withName(name)
                    .withProject(project)
                    .withFlavor(flavor)
                    .withImage(image)
                    .withNetwork(network)
                    .withKeypair(keypair)
                    .withOptions(options);
            
            if ( (count == null) && (range == null) ) {
                count = 1;
            }
            
            addInstancesRequest = (count != null) ?
                    addInstancesRequest.withCount(count) :
                    addInstancesRequest.withRange(range);
            
            validateConfirmExecuteShowStatus(
                    addInstancesRequest,
                    instances -> String.format("Going to add %d instances:", instances.size()),
                    instances -> new String[]{"Name", "Project", "Image", "Flavor", "More"},
                    instances -> entityFormatter.formatNewInstances(instances),
                    instancesRepository::addInstances
            );
        } else if (addInstancesWizard.runSteps()) {
            commandExecutor.execute(addInstancesWizard.getCommand());
        }
        
    }
    
    @CliCommand(value = "add images", help = "Add one or more images to project")
    public void addImage(
            @CliOption(key = "instances", help = "Comma separated instance names or patterns to match against instance name") String instanceNames,
            @CliOption(key = "", help = "Comma separated instance names or patterns to match against instance name") String inlineInstanceNames,
            @CliOption(key = "name", help = "Image name") String imageName
    ) {
        if (inlineInstanceNames != null || instanceNames != null) {
            AddImagesRequest addImagesRequest = requestProvider.get(AddImagesRequest.class);
            addImagesRequest = (inlineInstanceNames != null) ?
                    addImagesRequest.withInstanceNames(inlineInstanceNames) :
                    addImagesRequest.withInstanceNames(instanceNames);
            if (imageName != null) {
                addImagesRequest = addImagesRequest.withName(imageName);
            }
            validateConfirmExecuteShowStatus(
                    addImagesRequest,
                    images -> String.format("Going to add %d images.", images.size()),
                    images -> new String[]{"Name", "Instance", "Project"},
                    images -> entityFormatter.formatNewImages(images),
                    imagesRepository::addImages
            );
        } else if (addImagesWizard.runSteps()) {
            commandExecutor.execute(addImagesWizard.getCommand());
        }
    }
    
}
