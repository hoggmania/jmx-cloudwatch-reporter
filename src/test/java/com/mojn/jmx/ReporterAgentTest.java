package com.mojn.jmx;

import com.codahale.metrics.ConsoleReporter;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Created by johannes on 4/4/14.
 */
public class ReporterAgentTest {

    private ReporterAgent reporterAgent;

    @Before
    public void setUp() throws Exception {
        reporterAgent = new ReporterAgent();
    }

    @Test
    @Ignore("ad-hoc")
    public void testRegister() throws Exception {
        // reporterAgent.allowedBeans= new ImmutableMap.Builder<String, String>().put("java.lang:type=Memory.HeapMemoryUsage","sssswww").build();
        String file = this.getClass().getResource("/test2.json").getFile();

        reporterAgent.readConfig(file);
        reporterAgent.initLogger();
        reporterAgent.registerPlatformBeans();

        reporterAgent.addBeanNotificationListener();
        ConsoleReporter reporter= ConsoleReporter.forRegistry(reporterAgent.metricRegistry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        reporter.start(1, TimeUnit.SECONDS);
        while(true) {
            // eat some memory
            String s = "";
            for (int i = 0; i < 100000; i++) {
                 s+="asdasdasd";
            }
        }
    }

    @Test
    public void testSimpleLogger() throws Exception {
        String file = this.getClass().getResource("/test2.json").getFile();
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel","debug");
        reporterAgent.readConfig(file);
        reporterAgent.initLogger();
        assertEquals("debug", System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
    }


    @Test
    public void testParse() throws Exception {
        String file = this.getClass().getResource("/test2.json").getFile();
        reporterAgent.readConfig(file);
        try {
            reporterAgent.readConfig(new File(file).getParent());
            fail("no exception");
        } catch (IOException e) {

        }
        reporterAgent.readConfig(file);
        assertEquals( 300, reporterAgent.settings.reportInterval );

    }

}
