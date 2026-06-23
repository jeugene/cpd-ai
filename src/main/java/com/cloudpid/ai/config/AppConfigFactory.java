package com.cloudpid.ai.config;

import org.aeonbits.owner.ConfigFactory;

public final class AppConfigFactory {

    private static final AppConfig INSTANCE = ConfigFactory.create(AppConfig.class);

    private AppConfigFactory() {}

    public static AppConfig get() {
        return INSTANCE;
    }
}
