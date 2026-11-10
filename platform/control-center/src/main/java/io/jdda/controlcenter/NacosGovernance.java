package io.jdda.controlcenter;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jdda.governance.nacos.enabled", havingValue = "true")
class NacosGovernance {
    private static final Logger LOG = LoggerFactory.getLogger(NacosGovernance.class);
    private static final String PREFIX = "jdda.governance.nacos.";
    private final GovernancePolicy policy;
    private final Environment environment;
    private ConfigService config;
    private ScheduledExecutorService refreshes;

    NacosGovernance(GovernancePolicy policy, Environment environment) {
        this.policy = policy;
        this.environment = environment;
    }

    @PostConstruct
    void start() {
        String address = environment.getRequiredProperty(PREFIX + "server-addr");
        String dataId = environment.getRequiredProperty(PREFIX + "data-id");
        String group = environment.getRequiredProperty(PREFIX + "group");
        if (address.isBlank() || dataId.isBlank() || group.isBlank())
            throw new IllegalArgumentException("Set the Nacos server address, data ID and group.");
        var properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, address);
        setIfPresent(properties, PropertyKeyConst.NAMESPACE, "namespace");
        setIfPresent(properties, PropertyKeyConst.USERNAME, "username");
        setIfPresent(properties, PropertyKeyConst.PASSWORD, "password");

        try {
            config = NacosFactory.createConfigService(properties);
            String initial = config.getConfig(dataId, group, 5000);
            policy.apply(initial, "nacos");
            refreshes = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jdda-nacos").factory());
            refreshes.scheduleWithFixedDelay(() -> refresh(dataId, group), 5, 5, TimeUnit.SECONDS);
            LOG.info("Loaded JDDA governance from Nacos");
        } catch (NacosException e) {
            close();
            throw new IllegalStateException("Unable to load JDDA governance from Nacos. Error code " + e.getErrCode() + ".");
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void setIfPresent(Properties properties, String key, String name) {
        String value = environment.getProperty(PREFIX + name, "");
        if (!value.isBlank()) properties.setProperty(key, value);
    }

    private void refresh(String dataId, String group) {
        try {
            long previous = policy.snapshot().revision();
            policy.apply(config.getConfig(dataId, group, 2000), "nacos");
            long current = policy.snapshot().revision();
            if (current != previous) LOG.info("Applied JDDA governance revision {}", current);
        } catch (NacosException unavailable) {
            LOG.warn("Nacos governance read failed. Kept revision {}", policy.snapshot().revision());
        } catch (RuntimeException invalid) {
            LOG.warn("Rejected invalid Nacos governance update. Kept revision {}", policy.snapshot().revision());
        }
    }

    @PreDestroy
    void close() {
        if (refreshes != null) refreshes.shutdownNow();
        if (config == null) return;
        try {
            config.shutDown();
        } catch (NacosException e) {
            LOG.warn("Could not close Nacos governance client. Error code {}", e.getErrCode());
        }
    }
}
