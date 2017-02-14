package org.meridor.perspective.googlecloud;

import org.meridor.perspective.beans.Instance;
import org.meridor.perspective.config.OperationType;
import org.springframework.stereotype.Component;

import java.util.function.BiFunction;

import static org.meridor.perspective.config.OperationType.HARD_REBOOT_INSTANCE;

@Component
public class HardRebootInstanceOperation extends BaseInstanceOperation {

    @Override
    protected BiFunction<Api, Instance, Boolean> getAction() {
        return (api, instance) -> api.hardRebootInstance(instance.getRealId());
    }

    @Override
    protected String getSuccessMessage(Instance instance) {
        return String.format("Hard rebooted instance %s (%s)", instance.getName(), instance.getId());
    }

    @Override
    protected String getErrorMessage(Instance instance) {
        return String.format("Failed to hard reboot instance %s (%s)", instance.getName(), instance.getId());
    }

    @Override
    public OperationType[] getTypes() {
        return new OperationType[]{HARD_REBOOT_INSTANCE};
    }

}
