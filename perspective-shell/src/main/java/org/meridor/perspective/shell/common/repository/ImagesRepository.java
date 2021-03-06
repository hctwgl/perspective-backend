package org.meridor.perspective.shell.common.repository;

import org.meridor.perspective.beans.Image;
import org.meridor.perspective.shell.common.request.FindImagesRequest;
import org.meridor.perspective.shell.common.result.FindImagesResult;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ImagesRepository {
    
    List<FindImagesResult> findImages(FindImagesRequest findImagesRequest);

    Set<String> addImages(List<Image> images);

    Set<String> deleteImages(Collection<String> imageIds);
    
}
