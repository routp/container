package com.routp.container.config;

import static io.helidon.config.PollingStrategies.regular;
import static io.helidon.config.PollingStrategies.watch;
import static java.time.Duration.ofSeconds;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import com.routp.container.config.handler.ChangeHandler;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.PollingStrategies;
import io.helidon.config.spi.AbstractConfigSource;
import io.helidon.config.spi.ConfigSource;


/**
 * A thread-safe singleton that builds one {@link Config} object from one or more specified config
 * source file or directories. This requires one-time initialization using the builder
 * {@link Builder#build()} before accessing the config object or config properties.
 * <p>
 * Example of Builder: <br>
 * DynamicConfig.builder().includeSysEnvProps().useCustomExecutor().sources(file1.properties, file2.props).build(); <br>
 * DynamicConfig.builder().useCustomExecutor().strategy(Strategy.POLL).frequency(PollFrequency.HIGH).sources(file1
 * .properties, file2.props).build();
 * <br>
 * </p>
 * The internal built {@link Config} object remains private to this class.
 *
 * @author prarout
 * @since 1.0.0
 */
public final class DynamicConfig {
    private static final Logger logger = Logger.getLogger(DynamicConfig.class.getName());

    private static volatile DynamicConfig dynamicConfig;
    private static final Object syncLock = new Object();

    private boolean includeSysEnvProps;
    private boolean useCustomExecutor;
    private boolean runAsDaemon;
    private Strategy strategy;
    private PollFrequency pollFrequency;
    private Set<String> configFileSystemSet;
    private Set<Class<? extends ChangeHandler>> changeHandlers;

    private ScheduledExecutorService configWatchExecutor;
    private Config config;


    /**
     * Can not be instantiated
     */
    private DynamicConfig(boolean includeSysEnvProps, boolean useCustomExecutor, boolean runAsDaemon,
                          Strategy strategy, PollFrequency pollFrequency, Set<String> configFileSystemSet,
                          ScheduledExecutorService configWatchExecutor,
                          Set<Class<? extends ChangeHandler>> changeHandlers, Config config) {
        this.includeSysEnvProps = includeSysEnvProps;
        this.useCustomExecutor = useCustomExecutor;
        this.runAsDaemon = runAsDaemon;
        this.strategy = strategy;
        this.pollFrequency = pollFrequency;
        this.configFileSystemSet = configFileSystemSet;
        this.configWatchExecutor = configWatchExecutor;
        this.changeHandlers = changeHandlers;
        this.config = config;

    }

    /**
     * Creates an instance of {@link DynamicConfig.Builder}
     *
     * @return {@link DynamicConfig.Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean includeSysEnvProps;
        private boolean useCustomExecutor;
        private boolean runAsDaemon;
        private String[] sources;
        private Strategy strategy;
        private PollFrequency pollFrequency;
        private Set<Class<? extends ChangeHandler>> changeHandlers = new HashSet<>();

        /**
         * Includes system and environment properties to {@link Config} object.
         *
         * @return current {@link DynamicConfig.Builder} instance
         */
        public Builder includeSysEnvProps() {
            this.includeSysEnvProps = true;
            return this;
        }

        /**
         * Creates an custom executor service which is used to watch filesystem changes on. An initialized
         * {@link DynamicConfig} can be terminated only when the custom executor service is used.
         *
         * @return current {@link DynamicConfig.Builder} instance
         */
        public Builder useCustomExecutor() {
            this.useCustomExecutor = true;
            return this;
        }

        /**
         * The shutdown hook is added when threads pool is custom executor and runs as daemon process providing auto
         * shuts down in case of JVM's normal exit (System.exit()) or interrupted exit(crtl+c). Default value
         * {@code false}.
         *
         * @return current {@link DynamicConfig.Builder} instance
         */
        public Builder runAsDaemon() {
            this.runAsDaemon = true;
            return this;
        }

        /**
         * Adds one or more config source files or directories. If no value provided for sources and other
         * parameters are not set then an empty {@link Config} object will be created. A config property
         * defined in Configuration sources found earlier in the list are considered to have a higher priority than
         * the latter ones. i.e. Config property with same name in a source config file take precedence over other
         * source config file having same config property according to the order they are added into the list.
         *
         * @param sources array of config source file and directory paths
         * @return current {@link DynamicConfig.Builder} instance
         */
        public Builder sources(String... sources) {
            this.sources = sources;
            return this;
        }

        /**
         * After initialization this class keep watching or polling depending on the strategy {@link Strategy} for
         * the change events on the registered config source files and reload the config object automatically.
         *
         * @param strategy {@link Strategy}
         * @return current {@link DynamicConfig.Builder} instance
         */
        public Builder strategy(Strategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * The frequency in seconds the source files will be polled when the {@link Strategy} is set to {@code Strategy
         * .PollFrequency}
         *
         * @param pollFrequency {@link PollFrequency}
         * @return current {@link DynamicConfig.Builder} instance
         */
        public Builder frequency(PollFrequency pollFrequency) {
            this.pollFrequency = pollFrequency;
            return this;
        }

        /**
         * Adds one {@link ChangeHandler} implementation class that will be called when there is a change event
         *
         * @param configHandler {@link ChangeHandler}
         * @return current {@link DynamicConfig.Builder} instance
         */
        public Builder handler(Class<? extends ChangeHandler> configHandler) {
            changeHandlers.add(configHandler);
            return this;
        }

        /**
         * Adds a list of {@link ChangeHandler} implementation classes those will be called when there is a change event
         *
         * @param handlers list of config handlers {@link ChangeHandler}
         * @return current {@link DynamicConfig.Builder} instance
         */
        @SafeVarargs
        public final Builder handlers(Class<? extends ChangeHandler>... handlers) {
            Collections.addAll(changeHandlers, handlers);
            return this;
        }

        /**
         * Builds the {@link DynamicConfig}.
         */
        public void build() {
            DynamicConfig.initialize(includeSysEnvProps, useCustomExecutor, runAsDaemon, strategy, pollFrequency,
                    changeHandlers, sources);
        }
    }

    /**
     * Initializes the dynamic config.
     *
     * @param includeSysEnvProps {@code true} includes system and environment variables to the config otherwise not
     * @param useCustomExecutor  {@code true} an custom executor service will be used to watch filesystem changes
     * @param runAsDaemon        {@code true} runs as a daemon process
     * @param strategy           strategy type WATCH or POLL. Default is WATCH
     * @param pollFrequency      poll frequency is strategy type is POLL. No effect of this value if strategy is WATCH
     * @param sources            array of config source file and directory paths
     */
    private static synchronized void initialize(boolean includeSysEnvProps,
                                                boolean useCustomExecutor,
                                                boolean runAsDaemon,
                                                Strategy strategy,
                                                PollFrequency pollFrequency,
                                                Set<Class<? extends ChangeHandler>> changeHandlers,
                                                String... sources) {
        if (dynamicConfig != null) {
            logger.info("Dynamic config is already initialized.");
            return;
        }

        // Start config initialization
        ScheduledExecutorService fswExecutor = null;
        try {
            // Add config source files provided to the set to remove duplicates if any.
            final Set<String> configFileSystemSet = new LinkedHashSet<>();
            if (ArrayUtils.isNotEmpty(sources)) {
                Collections.addAll(configFileSystemSet, sources);
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Source config files: " + configFileSystemSet);
            }

            final Strategy finalStrategy = strategy != null ? strategy : Strategy.WATCH;
            final PollFrequency finalPollFrequency = pollFrequency != null ? pollFrequency : PollFrequency.MEDIUM;

            // PollingStrategies::watch creates one executor registered file to be watched for file events.
            if (useCustomExecutor) {
                final BasicThreadFactory fileWatcherFactory = new BasicThreadFactory.Builder().namingPattern(
                        "DynamicConfigFileWatcher" + "-%d").daemon(runAsDaemon).build();
                fswExecutor =
                        Executors.newScheduledThreadPool(configFileSystemSet.size(), fileWatcherFactory);

                // Only for daemon mode - auto shutdown hook registration for thread pool for normal exit or
                // interrupt shutdown like crtl+c
                if (runAsDaemon) {
                    final ScheduledExecutorService configWatchExecutor = fswExecutor;
                    Runtime.getRuntime().addShutdownHook(new Thread(() ->
                            shutdownExecutorService(configWatchExecutor, 1000, TimeUnit.MILLISECONDS)));
                }
            }

            // List of sources to be used to build config and to be watched for change events
            final List<Supplier<ConfigSource>> configSources = new ArrayList<>();
            for (String configFile : configFileSystemSet) {
                Path cfgPath = Paths.get(configFile);
                AbstractConfigSource.Builder<? extends AbstractConfigSource.Builder<?, Path>, Path> configSourceBuilder;
                if (Files.isDirectory(cfgPath)) {
                    configSourceBuilder = ConfigSources.directory(configFile);
                } else {
                    configSourceBuilder = ConfigSources.file(configFile);
                }
                if (useCustomExecutor) {
                    if (Strategy.WATCH == finalStrategy) {
                        configSourceBuilder.pollingStrategy(
                                watch(cfgPath).executor(fswExecutor));
                    } else {
                        configSourceBuilder.pollingStrategy(
                                regular(ofSeconds(finalPollFrequency.getDuration())).executor(fswExecutor));
                    }

                } else {
                    if (Strategy.WATCH == finalStrategy) {
                        configSourceBuilder.pollingStrategy(PollingStrategies::watch);
                    } else {
                        configSourceBuilder.pollingStrategy(regular(ofSeconds(finalPollFrequency.getDuration())));
                    }
                }
                configSources.add(configSourceBuilder);
            }

            // Build the config object
            final Config.Builder configBuilder = Config.builder().sources(configSources).disableCaching();
            if (!includeSysEnvProps) {
                configBuilder.disableEnvironmentVariablesSource().disableSystemPropertiesSource();
            }

            Config config = configBuilder.build();
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("Dynamic config map at initialization: " + config.asMap());
            }

            config.onChange(DynamicConfig::onChange);

            // Create the final dynamic config object
            dynamicConfig = new DynamicConfig(includeSysEnvProps, useCustomExecutor, runAsDaemon, finalStrategy,
                    finalPollFrequency, configFileSystemSet, fswExecutor, changeHandlers, config);

        } catch (Exception e) {
            // Any dynamic exception during initialization, reset the values and throw the exception
            shutdownExecutorService(fswExecutor, 1000, TimeUnit.MILLISECONDS);
            throw e;
        }
    }

    /**
     * Terminates {@link DynamicConfig} and resets initialization parameters only if custom executor was used to watch
     * filesystem changes. After this operation {@link DynamicConfig} can be re-initialized in the same JVM instance.
     */
    public static synchronized void terminate() {
        checkInitialization();
        if (dynamicConfig.useCustomExecutor) {
            if (dynamicConfig.config != null) {
                shutdownExecutorService(dynamicConfig.configWatchExecutor, 1000,
                        TimeUnit.MILLISECONDS);
            }
            dynamicConfig = null;
            logger.info("Dynamic config was terminated.");
        } else {
            throw new UnsupportedOperationException("Dynamic config termination is not allowed when" +
                    " useCustomExecutor is set to false.");
        }
    }


    /**
     * Callback method to do action when there is an event triggered such as modify on any registered config source
     * files. This method must return {@code true} to continue listening the change events.
     *
     * @param changedConfig reloaded {@link Config} object
     */
    private static void onChange(Config changedConfig) {
        logger.info("Change event received on specified config source files.");
        if (logger.isLoggable(Level.FINER)) {
            logger.finer("Modified config: " + changedConfig.asMap());
        }
        checkInitialization();
        try {
            synchronized (syncLock) {
                dynamicConfig.config = changedConfig;
                invokeHandlers();
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Config object reload failed on change event", e);
        }
    }

    /**
     * Executes the registered handlers on every change event.
     */
    private static void invokeHandlers() {
        for (Class<? extends ChangeHandler> handler : dynamicConfig.changeHandlers) {
            if (handler != null) {
                ChangeHandler configHandler;
                try {
                    configHandler = handler.newInstance();
                    configHandler.execute(getConfigAsMap());
                } catch (InstantiationException | IllegalAccessException e) {
                    logger.log(Level.SEVERE,
                            "Handler " + handler.getName() + " instantiation failed. " + e.getMessage(), e);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Handler " + handler.getName() + " execution failed. " + e.getMessage(),
                            e);
                }
            }
        }
    }

    /**
     * Returns a copy of existing {@link Config} object in a Map
     *
     * @return {@link Map} object of {@link Config}
     */
    public static Map<String, String> getConfigAsMap() {
        checkInitialization();
        if (dynamicConfig.config != null) {
            final Map<String, String> configMap = new HashMap<>(dynamicConfig.config.asMap().get());
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("Dynamic config map: " + configMap);
            }
            if (!configMap.isEmpty()) {
                return Collections.unmodifiableMap(configMap);
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Returns string value of the specified key, null if key is not found or value of key is null. A convenient method
     * for the caller where the caller can cast the value to the desired type.
     *
     * @param key key name in the config
     * @return value of the specified key
     */
    public static String getValue(final String key) {
        checkInitialization();
        return dynamicConfig.getProperty(key, String.class);
    }

    /**
     * Returns {@link Boolean} value of the specified key, null if key is not found or value of key is null
     *
     * @param key key name in the config
     * @return value of the specified key
     */
    public static Boolean getBooleanValue(final String key) {
        checkInitialization();
        return dynamicConfig.getProperty(key, Boolean.class);
    }

    /**
     * Returns {@link Integer} value of the specified key, null if key is not found or value of key is null
     *
     * @param key key name in the config
     * @return value of the specified key
     */
    public static Integer getIntValue(final String key) {
        checkInitialization();
        return dynamicConfig.getProperty(key, Integer.class);
    }

    /**
     * Returns {@link Double} value of the specified key, null if key is not found or value of key is null
     *
     * @param key key name in the config
     * @return value of the specified key
     */
    public static Double getDoubleValue(final String key) {
        checkInitialization();
        return dynamicConfig.getProperty(key, Double.class);
    }

    /**
     * Returns {@link Long} value of the specified key, null if key is not found or value of key is null
     *
     * @param key key name in the config
     * @return value of the specified key
     */
    public static Long getLongValue(final String key) {
        checkInitialization();
        return dynamicConfig.getProperty(key, Long.class);
    }

    /**
     * Returns value of the specified key, returns default value if the key not found or key's value is null. If the
     * value is available in config it casts the value to type of default value before returning
     *
     * @param key        key name in the config
     * @param defaultVal default value to be returned if the key not found or key's value is null
     * @param valType    the type of value
     * @param <T>        class type
     * @return value or default value for the specified key
     */
    public static <T> T getConfigValueOrDefault(final String key, Object defaultVal, Class<T> valType) {
        if (valType != null && valType == defaultVal.getClass()) {
            T configValue = null;
            try {
                checkInitialization();
                configValue = dynamicConfig.getProperty(key, valType);
            } catch (Exception e) {
                if (logger.isLoggable(Level.FINER)) {
                    logger.log(Level.FINER, e.getMessage(), e);
                }
            }
            return configValue != null ? configValue : valType.cast(defaultVal);
        } else {
            throw new UnsupportedOperationException("Mismatch in type of default value and specified type");
        }
    }

    /**
     * Returns string value of the specified key, returns default value if the key not found or key's value is null.
     * A convenient method for the caller where the caller can cast the value to the desired type.
     *
     * @param key        key name in the config
     * @param defaultVal default value to be returned if the key not found or key's value is null
     * @return value or default value for the specified key
     */
    public static String getConfigValueOrDefault(final String key, String defaultVal) {
        String configValue = null;
        try {
            checkInitialization();
            configValue = dynamicConfig.getProperty(key, String.class);
        } catch (Exception e) {
            if (logger.isLoggable(Level.FINER)) {
                logger.log(Level.FINER, e.getMessage(), e);
            }
        }
        return StringUtils.isNotBlank(configValue) ? configValue : defaultVal;

    }

    /**
     * Returns value of the specified property, null if property is not found or value of key is null
     *
     * @param key  key name in the config
     * @param type type of value
     * @param <T>  class type
     * @return value of the specified key
     */
    private <T> T getProperty(final String key, Class<T> type) {

        T value = null;
        if (StringUtils.isNotBlank(key)) {
            Config propConfig;
            if (config != null && (propConfig = config.get(key)).exists()) {
                value = propConfig.as(type).isPresent() ? propConfig.as(type).get() : null;
            }
        }
        if (logger.isLoggable(Level.FINER)) {
            logger.finer("Config property key: " + key + ", value: " + value);
        }
        return value;
    }

    /**
     * Checks if {@link DynamicConfig} is initialized. If not it throws {@link IllegalStateException}
     */
    private static void checkInitialization() {
        if (dynamicConfig == null) {
            throw new IllegalStateException("Dynamic config is not initialized.");
        }
    }

    /**
     * Returns {@link DynamicConfig} initialization details if initialization was successful
     *
     * @return initialization details
     */

    public static String getInitDetails() {
        checkInitialization();
        final String frequency = Strategy.WATCH == dynamicConfig.strategy ? "N/A" :
                dynamicConfig.pollFrequency.getDuration() + " seconds";
        return ("includeSysEnvProps: " + dynamicConfig.includeSysEnvProps +
                ", " +
                "useCustomExecutor: " + dynamicConfig.useCustomExecutor +
                ", " +
                "runAsDaemon: " + dynamicConfig.runAsDaemon +
                ", " +
                "Strategy: " + dynamicConfig.strategy +
                ", " +
                "pollingFrequency: " + frequency +
                ", " +
                "configFileSources: " + dynamicConfig.configFileSystemSet);
    }

    /**
     * Shuts down an executor service thread pool gracefully
     *
     * @param executorService the executor service thread pool to be shut down
     * @param timeout         maximum time to wait for termination before calling {@link ExecutorService#shutdownNow()}
     * @param timeUnit        {@link TimeUnit} type for timeout value
     */
    private static void shutdownExecutorService(ExecutorService executorService, long timeout, TimeUnit timeUnit) {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                // if executor is not terminated after specified timeout, invoke shutdown now.
                if (!executorService.awaitTermination(timeout, timeUnit)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info(executorService.toString());
        }
    }
}