package org.meridor.perspective.shell.commands;

import org.meridor.perspective.beans.*;
import org.meridor.perspective.beans.Image;
import org.meridor.perspective.shell.query.*;
import org.meridor.perspective.shell.repository.ImagesRepository;
import org.meridor.perspective.shell.repository.InstancesRepository;
import org.meridor.perspective.shell.repository.ProjectsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.meridor.perspective.shell.repository.impl.TextUtils.joinLines;

@Component
public class ShowCommands extends BaseCommands {
    
    @Autowired
    private ProjectsRepository projectsRepository;
    
    @Autowired
    private InstancesRepository instancesRepository;
    
    @Autowired
    private ImagesRepository imagesRepository;
    
    @Autowired
    private EntityFormatter entityFormatter;
    
    @Autowired
    private QueryProvider queryProvider;
    
    @CliCommand(value = "show projects", help = "Show available projects")
    public void showProjects(
            @CliOption(key = "name", help = "Project name") String name,
            @CliOption(key = "cloud", help = "Cloud types") String cloud
    ) {
        ShowProjectsQuery showProjectsQuery = getShowProjectsQuery(cloud, name);
        validateExecuteShowResult(
                showProjectsQuery,
                new String[]{"Name", "Cloud"},
                q -> {
                    List<Project> projects = projectsRepository.showProjects(q);
                    return projects.stream()
                            .map(p -> new String[]{p.getName(), p.getCloudType().value()})
                            .collect(Collectors.toList());
                }
        );
    }
    
    @CliCommand(value = "show flavors", help = "Show available flavors")
    public void showFlavors(
            @CliOption(key = "name", help = "Flavor name") String name,
            @CliOption(key = "projectName", help = "Project name") String projectName,
            @CliOption(key = "cloud", help = "Cloud type") String cloud
    ) {
        ShowFlavorsQuery showFlavorsQuery = queryProvider.get(ShowFlavorsQuery.class)
                .withNames(name)
                .withProjects(projectName)
                .withClouds(cloud);
        validateExecuteShowResult(
                showFlavorsQuery,
                new String[]{"Name", "Project", "VCPUs", "RAM", "Root disk", "Ephemeral disk"},
                q -> {
                    Map<Project, List<Flavor>> flavorsMap = projectsRepository.showFlavors(q);
                    return flavorsMap.keySet().stream()
                            .flatMap(p -> {
                                List<Flavor> flavors = flavorsMap.get(p);
                                return flavors.stream().map(f -> new String[]{
                                        f.getName(), p.getName(),
                                        String.valueOf(f.getVcpus()), String.valueOf(f.getRam()),
                                        String.valueOf(f.getRootDisk()), String.valueOf(f.getEphemeralDisk())
                                });
                            })
                            .collect(Collectors.toList());
                }
        );
    }
    
    @CliCommand(value = "show networks", help = "Show available networks")
    public void showNetworks(
            @CliOption(key = "name", help = "Network name") String name,
            @CliOption(key = "projectName", help = "Project name") String projectName,
            @CliOption(key = "cloud", help = "Cloud type") String cloud
    ) {
        ShowNetworksQuery showNetworksQuery = queryProvider.get(ShowNetworksQuery.class).withNames(name)
                .withProjects(projectName)
                .withClouds(cloud);
        validateExecuteShowResult(
                showNetworksQuery,
                new String[]{"Name", "Project", "Subnets", "State", "Is Shared"},
                q -> {
                    Map<Project, List<Network>> networksMap = projectsRepository.showNetworks(q);
                    return networksMap.keySet().stream()
                            .flatMap(p -> {
                                List<Network> networks = networksMap.get(p);
                                return networks.stream().map(n -> new String[]{
                                        n.getName(), p.getName(),
                                        joinLines(
                                                n.getSubnets().stream()
                                                .map(s -> String.format(
                                                        "%s/%s",
                                                        s.getCidr().getAddress(),
                                                        String.valueOf(s.getCidr().getPrefixSize()))
                                                )
                                                .sorted((c1, c2) -> Comparator.<String>naturalOrder().compare(c1, c2))
                                                .collect(Collectors.toSet())
                                        ),
                                        n.getState(), String.valueOf(n.isIsShared())
                                });
                            })
                            .collect(Collectors.toList());
                }
        );
    }
    
    @CliCommand(value = "show keypairs", help = "Show available keypairs")
    public void showKeypairs(
            @CliOption(key = "name", help = "Keypair name") String name,
            @CliOption(key = "projectName", help = "Project name") String projectName,
            @CliOption(key = "cloud", help = "Cloud type") String cloud
    ) {
        ShowKeypairsQuery showKeypairsQuery = queryProvider.get(ShowKeypairsQuery.class).withNames(name)
                .withProjects(projectName)
                .withClouds(cloud);
        validateExecuteShowResult(
                showKeypairsQuery,
                new String[]{"Name", "Fingerprint", "Project"},
                q -> {
                    Map<Project, List<Keypair>> keypairsMap = projectsRepository.showKeypairs(q);
                    return keypairsMap.keySet().stream()
                            .flatMap(p -> {
                                List<Keypair> keypairs = keypairsMap.get(p);
                                return keypairs.stream().map(k -> new String[]{
                                        k.getName(), k.getFingerprint(), p.getName()
                                });
                            })
                            .collect(Collectors.toList());
                }
        );
    }
    
    @CliCommand(value = "show instances", help = "Show instances")
    public void showInstances(
            @CliOption(key = "id", help = "Instance id") String id,
            @CliOption(key = "name", help = "Instance name") String name,
            @CliOption(key = "flavor", help = "Flavor name") String flavor,
            @CliOption(key = "image", help = "Image name") String image,
            @CliOption(key = "state", help = "Instance state") String state,
            @CliOption(key = "cloud", help = "Cloud type") String cloud,
            @CliOption(key = "projects", help = "Project names") String projects
    ) {
        ShowInstancesQuery showInstancesQuery = queryProvider.get(ShowInstancesQuery.class)
                .withIds(id)
                .withNames(name)
                .withFlavors(flavor)
                .withImages(image)
                .withStates(state)
                .withClouds(cloud)
                .withProjectNames(projects);
        validateExecuteShowResult(
                showInstancesQuery,
                new String[]{"Name", "Project", "Image", "Flavor", "Network", "State", "Last modified"},
                q -> {
                    List<Instance> instances = instancesRepository.showInstances(q);
                    return entityFormatter.formatInstances(instances, cloud);
                }
        );
    }
    
    @CliCommand(value = "show images", help = "Show images")
    public void showImages(
            @CliOption(key = "id", help = "Image id") String id,
            @CliOption(key = "name", help = "Image name") String name,
            @CliOption(key = "state", help = "Image state") String state,
            @CliOption(key = "cloud", help = "Cloud type") String cloud
    ) {
        ShowImagesQuery showImagesQuery = queryProvider.get(ShowImagesQuery.class)
                .withIds(id)
                .withNames(name)
                .withStates(state)
                .withCloudNames(cloud);
        validateExecuteShowResult(
                showImagesQuery,
                new String[]{"Name", "Projects", "State", "Last modified"},
                q -> {
                    List<Image> images = imagesRepository.showImages(q);
                    return entityFormatter.formatImages(images);
                }
        );
    }
    
    @CliCommand(value = "show console", help = "Show console for instances")
    public void showConsole(
            @CliOption(key = "", mandatory = true, help = "Comma separated instances names or patterns to match against instance name") String names
    ) {
        if (!Desktop.isDesktopSupported()) {
            warn("This operation is not supported on your platform.");
        }
        ShowInstancesQuery showInstancesQuery = queryProvider.get(ShowInstancesQuery.class)
                .withNames(names);
        validateExecuteShowResult(
                showInstancesQuery,
                q -> {
                    List<Instance> instances = instancesRepository.showInstances(q);
                    if (instances.isEmpty()) {
                        error(String.format("Instances not found: %s", names));
                    }
                    instances.forEach(i -> {
                        if (!i.getMetadata().containsKey(MetadataKey.CONSOLE_URL)) {
                            warn(String.format("Matched instance \"%s\" but it didn't contain console information.", i.getName()));
                            return;
                        }
                        String consoleUriString = i.getMetadata().get(MetadataKey.CONSOLE_URL);
                        try {
                            URI consoleUri = new URI(consoleUriString);
                            ok(String.format("Opening console for instance \"%s\"...", i.getName()));
                            Desktop.getDesktop().browse(consoleUri);
                        } catch (URISyntaxException e) {
                            warn(String.format("Instance \"%s\" contains wrong console URL: %s.", i.getName(), consoleUriString));
                        } catch (Exception e) {
                            error(String.format("Failed to open console for instance \"%s\". Either default browser is not set or it failed to open console at: %s", i.getName(), consoleUriString));
                        }
                    });
                }
        );
    }

    private ShowProjectsQuery getShowProjectsQuery(String clouds, String projects) {
        return queryProvider.get(ShowProjectsQuery.class)
                .withClouds(clouds)
                .withNames(projects);
    }
}
