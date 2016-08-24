package org.meridor.perspective.framework.storage;

import org.meridor.perspective.beans.Project;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface ProjectsAware {

    Optional<Project> getProject(String projectId);

    Collection<Project> getProjects();
    
    Collection<Project> getProjects(Set<String> ids);

    void saveProject(Project project);

    void addProjectListener(EntityListener<Project> listener);
    
}
