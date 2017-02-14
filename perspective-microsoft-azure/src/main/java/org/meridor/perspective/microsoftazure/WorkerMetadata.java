package org.meridor.perspective.microsoftazure;

import org.meridor.perspective.config.CloudType;
import org.meridor.perspective.worker.misc.impl.AbstractWorkerMetadata;
import org.springframework.stereotype.Component;

@Component
public class WorkerMetadata extends AbstractWorkerMetadata {
    @Override
    public CloudType getCloudType() {
        return CloudType.MICROSOFT_AZURE;
    }
}
