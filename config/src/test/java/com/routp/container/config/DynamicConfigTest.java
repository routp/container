package com.routp.container.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Unit test class for {@link DynamicConfig}
 *
 * @author prarout
 * @since 1.0.0
 */
@TestMethodOrder(OrderAnnotation.class)
public class DynamicConfigTest {

    private static final String testServiceFilePath =
            System.getProperty("user.dir") + File.separator + "testCfg.properties";

    @BeforeAll
    public static void initialize() throws Exception {
        preInitializationCheck();

        // Source file does not exist
        try {
            DynamicConfig.builder().sources(System.getProperty("user.dir") + File.separator + "abc.properties").build();
        } catch (Exception e) {
            assertTrue(e instanceof io.helidon.config.ConfigException);
        }

        // Default value without initialization
        assertEquals("default", DynamicConfig.getConfigValueOrDefault("test.default", "default"));
        assertEquals(30, DynamicConfig.getConfigValueOrDefault("test.sleep", 30, Integer.class));

        // Successful initialization - With custom executor which allows termination.
        preInitializationCheck();
        setupTestConfigFile();
        TimeUnit.MILLISECONDS.sleep(1000);
        DynamicConfig.builder().useCustomExecutor().sources(testServiceFilePath).includeSysEnvProps().build();
        System.out.println("Config created:" + DynamicConfig.getConfigAsMap());
        String initDetails = DynamicConfig.getInitDetails();
        assertNotNull(initDetails);
        System.out.println("Initialization Details with custom executor: " + initDetails);
        // Default value with initialization - Not yet defined
        assertEquals("default", DynamicConfig.getConfigValueOrDefault("test.default", "default"));
        assertEquals(30, DynamicConfig.getConfigValueOrDefault("test.sleep", 30, Integer.class));
        DynamicConfig.terminate();
        deleteTestConfigFile();

        // Successful initialization - With custom executor with polling which allows termination.
        preInitializationCheck();
        setupTestConfigFile();
        TimeUnit.MILLISECONDS.sleep(1000);
        DynamicConfig.builder().useCustomExecutor().includeSysEnvProps().sources(testServiceFilePath).strategy(Strategy.POLL)
                .frequency(PollFrequency.HIGH).build();
        initDetails = DynamicConfig.getInitDetails();
        assertNotNull(initDetails);
        System.out.println("Initialization Details with custom executor with polling: " + initDetails);
        System.out.println();
        DynamicConfig.terminate();
        deleteTestConfigFile();

        // Successful Initialization - With default executor and event handler invocation
        preInitializationCheck();
        setupTestConfigFile();
        TimeUnit.MILLISECONDS.sleep(1000);
        DynamicConfig.builder().sources(testServiceFilePath).handler(TestHandler.class).build();
        initDetails = DynamicConfig.getInitDetails();
        assertNotNull(initDetails);
        System.out.println("Initialization Details with default executor:: " + initDetails);
        // Default value with initialization - Not yet defined
        assertEquals("V2", DynamicConfig.getConfigValueOrDefault("test.version", "V1"));
        assertEquals(10, DynamicConfig.getConfigValueOrDefault("test.timeout", 30, Integer.class));
        try {
            DynamicConfig.terminate();
        } catch (Exception e) {
            assertTrue(e instanceof UnsupportedOperationException);
        }

        // New attempt of initialization after a successful initialization - No impact.
        DynamicConfig.builder().useCustomExecutor().includeSysEnvProps().runAsDaemon().sources(testServiceFilePath).build();
        assertEquals(initDetails, DynamicConfig.getInitDetails());
        DynamicConfig.builder().sources(testServiceFilePath).build();
        assertEquals(initDetails, DynamicConfig.getInitDetails());
    }

    private static void preInitializationCheck() {
        // Without initialization - Get initialization details throws IllegalStateException
        try {
            DynamicConfig.getInitDetails();
        } catch (Exception e) {
            assertTrue(e instanceof IllegalStateException);
        }

        // Without initialization - Get config map throws IllegalStateException
        try {
            DynamicConfig.getConfigAsMap();
        } catch (Exception e) {
            assertTrue(e instanceof IllegalStateException);
        }

        // Without initialization - terminate throws IllegalStateException
        try {
            DynamicConfig.terminate();
        } catch (Exception e) {
            assertTrue(e instanceof IllegalStateException);
        }
    }

    private static void setupTestConfigFile() throws IOException {
        Properties properties = new Properties();
        properties.put("test.server.url", "http://abc.xyz");
        properties.put("test.account", "demo");
        properties.put("test.version", "V2");
        properties.put("test.timeout", "10");
        try (OutputStream propertiesFileWriter = new FileOutputStream(testServiceFilePath)) {
            properties.store(propertiesFileWriter, "#Dynamic config test config properties");
        }
    }

    /**
     * Creates a test properties in user directory
     *
     * @throws IOException exception raised in creating properties file
     */
    private static void modifyTestConfigFile() throws IOException {
        Properties properties = new Properties();
        properties.put("test.account", "sales");
        properties.put("log.debug.enable", "true");
        properties.put("log.level", "DEBUG");
        properties.put("test.version", "V3");
        properties.put("test.timeout", "15");
        properties.put("UID", "2147483649");
        properties.put("percentage", "85.5");
        properties.put("isSupported", "false");

        try (OutputStream propertiesFileWriter = new FileOutputStream(testServiceFilePath)) {
            properties.store(propertiesFileWriter, "#Dynamic config test config properties");
        }
    }


    /**
     * Get value of properties after a successful initialization
     */
    @Test
    @Order(1)
    public void testGetConfig() {
        System.out.println("Original config: " + Objects.requireNonNull(DynamicConfig.getConfigAsMap()));
        assertEquals("V2", DynamicConfig.getValue("test.version"));
        assertEquals("http://abc.xyz", DynamicConfig.getValue("test.server.url"));
        assertEquals("demo", DynamicConfig.getValue("test.account"));
    }

    /**
     * Test change on the source file post initialization
     */
    @Test
    @Order(2)
    public void testConfigChange() {
        boolean isException = false;
        try {
            modifyTestConfigFile();
            // Only required for MacOS. Java does not have a native file watcher implementation for MacOS,
            // it’s using a fallback based on polling.
            final String os = System.getProperty("os.name").trim();
            if (os.toUpperCase().startsWith("MAC")) {
                TimeUnit.MILLISECONDS.sleep(15000);
            }
        } catch (Exception e) {
            isException = true;
        }
        System.out.println("Modified config: " + Objects.requireNonNull(DynamicConfig.getConfigAsMap()));
        assertFalse(isException);

        assertEquals("sales", DynamicConfig.getValue("test.account"));
        assertEquals(15, (int) DynamicConfig.getIntValue("test.timeout"));
        assertEquals(2147483649L, DynamicConfig.getLongValue("UID"));
        assertEquals(85.5, DynamicConfig.getDoubleValue("percentage"));
        assertFalse(DynamicConfig.getBooleanValue("isSupported"));
        assertNull(DynamicConfig.getValue("test.server.url"));
    }


    private static void deleteTestConfigFile() throws Exception {
        Files.deleteIfExists(Paths.get(testServiceFilePath));
    }

    @AfterAll
    public static void tearDown() throws Exception {
        deleteTestConfigFile();
    }
}