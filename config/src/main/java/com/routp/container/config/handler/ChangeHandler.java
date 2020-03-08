package com.routp.container.config.handler;

import java.util.Map;

/**
 * Handler interface
 *
 * @author prarout
 * @since 1.0.0
 */
@FunctionalInterface
public interface ChangeHandler {
    void execute(Map<String, String> configMap);

}
