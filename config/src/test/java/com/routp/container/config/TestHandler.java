package com.routp.container.config;

import java.util.Map;

import com.routp.container.config.handler.ChangeHandler;

/**
 * Test handler implementation of {@link ChangeHandler}
 *
 * @author prarout
 * @since 1.0.0
 */
public class TestHandler implements ChangeHandler {
    @Override
    public void execute(Map<String, String> configMap) {
        System.out.println("Invocation of handler " + this.getClass().getName() + " on change event. New config: " + configMap);
        if (configMap.containsKey("log.debug.enable") && Boolean.parseBoolean(configMap.get("log.debug.enable"))) {
            System.out.println("Debug enable request received with logging level " + configMap.get("log.level"));
        }
    }
}
