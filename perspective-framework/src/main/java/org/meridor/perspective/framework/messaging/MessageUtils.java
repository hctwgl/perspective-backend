package org.meridor.perspective.framework.messaging;

import org.meridor.perspective.config.CloudType;
import org.meridor.perspective.framework.messaging.impl.MessageImpl;

public class MessageUtils {

    public static Message message(CloudType cloudType, Object payload) {
        return new MessageImpl(cloudType, payload);
    }

    public static String getRealQueueName(String queueName, CloudType cloudType) {
        return String.format("%s_%s", cloudType.value(), queueName);
    }

}