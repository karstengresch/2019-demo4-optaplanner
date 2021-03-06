package com.redhat.demo.optaplanner.upstream;

import java.io.IOException;
import java.util.Properties;

import com.google.common.base.Charsets;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.marshall.StringMarshaller;

public class HotRodClientConfiguration {

    private static final String HOTROD_PROPERTIES_FILE = "hotrod-client.properties";

    public static ConfigurationBuilder get() {
        ConfigurationBuilder builder = new ConfigurationBuilder();
        Properties props = new Properties();
        try {
            props.load(HotRodClientConfiguration.class.getClassLoader().getResourceAsStream(HOTROD_PROPERTIES_FILE));
        } catch (IOException e) {
            throw new RuntimeException("Could not load infinispan.properties file.", e);
        }

        builder.addServer()
                .host(props.getProperty("infinispan.client.hotrod.endpoint"))
                .port(Integer.parseInt(props.getProperty("infinispan.client.hotrod.port")))
                .marshaller(new StringMarshaller(Charsets.UTF_8));
        return builder;
    }
}
