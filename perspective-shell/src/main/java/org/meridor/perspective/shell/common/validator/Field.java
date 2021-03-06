package org.meridor.perspective.shell.common.validator;

public enum Field {

    PROJECTS,
    CLOUDS,
    KEYPAIR_NAMES,
    NETWORK_NAMES,
    FLAVOR_NAMES,
    INSTANCE_STATES,
    INSTANCE_NAMES,
    IMAGE_NAMES,
    IMAGE_STATES;

    public static boolean contains(String name) {
        for (Field c : values()) {
            if (c.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
    
}
