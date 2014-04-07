package com.mojn.jmx;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient;
import com.blacklocus.metrics.CloudWatchReporter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.JmxAttributeGauge;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.relation.MBeanServerNotificationFilter;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ReporterAgent implements NotificationListener {

    protected MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
    protected MetricRegistry metricRegistry = new MetricRegistry();
    protected Settings settings;
    protected String metricsSuffix = "";
    Logger log = null;

    static class Settings {
        public String awsSecretKey = "";
        public String awsAccessKey = "";
        public String instanceId;
        public String endPoint = "monitoring.eu-west-1.amazonaws.com";
        // hand parsed json to support Composite Beans
        public Map<ObjectName, ObjectNode> beans;
        public String cloudWatchNamespace = "jmx";
        public long reportInterval = TimeUnit.MINUTES.toSeconds(1);
        public boolean logIgnoredAttributes = false;
        public Map<String,String> simpleLogger = Collections.emptyMap();
    }


    ObjectMapper jsonMapper = new ObjectMapper();

    public void init(String agentArguments) throws IOException {
        String configFile = agentArguments != null ? agentArguments : "jmx-cloudwatch.json";
        readConfig(configFile);
        initLogger();
        initAllowedBeans();

        AWSCredentials awsCredentials = new BasicAWSCredentials(settings.awsAccessKey, settings.awsSecretKey);
        //set up listeners for jmx events, so we get notifications for all new beans registered (premain runs "pre main"
        addBeanNotificationListener();
        registerPlatformBeans();
        initReporter(awsCredentials);
    }

    protected void initLogger() {
        Map<String,String> existingLoggerProps = null;
        if (settings.simpleLogger.size()>0) {
            existingLoggerProps = new HashMap<String,String>(settings.simpleLogger.size());
            for (Map.Entry<Object, Object> sysProp : System.getProperties().entrySet()) {
                if (sysProp.getKey().toString().startsWith("org.slf4j.simpleLogger.")) {
                    existingLoggerProps.put(sysProp.getKey().toString(), sysProp.getValue().toString());
                }
            }
            for (Map.Entry<String, String> entry: settings.simpleLogger.entrySet()) {
                System.setProperty("org.slf4j.simpleLogger." + entry.getKey(), entry.getValue());
            }
        }
        log = LoggerFactory.getLogger(ReporterAgent.class);
        if (existingLoggerProps!=null ) {
            System.getProperties().putAll(existingLoggerProps);
        }

    }


    protected void registerPlatformBeans() {
        // list all domains :
        Set<String> platformDomains = new HashSet<String>(Arrays.asList(mBeanServer.getDomains()));
        for (Map.Entry<ObjectName, ObjectNode> beanEntry : settings.beans.entrySet()) {
            // check if mBean is registered
            MBeanInfo mBeanInfo = null;
            ObjectName objectName = beanEntry.getKey();
            if (!platformDomains.contains(objectName.getDomain())) {
                continue;
            }
            try {
                mBeanInfo = mBeanServer.getMBeanInfo(objectName);
            } catch (Exception e) {
                log.warn("Exception while looking up mbean name {} - {}",objectName.toString(),e.getMessage());
                continue;
            }

            handleAttributes(mBeanInfo, objectName, beanEntry.getValue());
        }
        if (settings.logIgnoredAttributes) {
            Set<ObjectName> objectNames = mBeanServer.queryNames(null, null);
            for (ObjectName objectName : objectNames) {
                ObjectNode jsonNodes = settings.beans.get(objectName);
                if (jsonNodes==null) {
                    try {
                        logAllAttributes(objectName, mBeanServer.getMBeanInfo(objectName));
                    } catch (JMException e) {
                        log.warn("",e);
                    }
                }
            }
        }
    }

    protected void handleAttributes(MBeanInfo mBeanInfo, ObjectName objectName, ObjectNode attributesToMonitor) {
        for (MBeanAttributeInfo attr : mBeanInfo.getAttributes()) {
            // check if we are interested in this attribute
            String attrName = attr.getName();
            JsonNode jsonNode = attributesToMonitor.get(attrName);
            if (jsonNode != null) {
                if (!attr.isReadable()) {
                    log.warn("cannot monitor unreadable attribute {}@{}", objectName.toString(), attrName);
                    continue;
                }
                if (jsonNode.isTextual()) {
                    String monitorName = jsonNode.asText() + metricsSuffix;
                    metricRegistry.register(monitorName, new JmxAttributeGauge(mBeanServer, objectName, attrName));
                    log.info("attribute {}@{} registered as {}", objectName , attrName, monitorName);
                } else if (jsonNode.isObject()) {
                    if (!CompositeData.class.getName().equals(attr.getType())) {
                        log.warn("attribute {}@{} is not CompositeData", objectName, attr);
                        continue;
                    }
                    try {
                        CompositeData attribute = (CompositeData) mBeanServer.getAttribute(objectName, attrName);
                        handleComposite(objectName, attrName, attribute, (ObjectNode) jsonNode);
                    } catch (JMException e) {
                        log.warn("unable to get composite attributes {}@{}",objectName,attrName,e);
                    }

                }
            } else if (settings.logIgnoredAttributes) {
                log.info("{}@{} ignored",objectName,attr.getName());
            }
        }

    }

    class CompositeGauge implements Gauge<Object> {
        private final ObjectName objectName;
        private final String attrName;
        private final String compositeAttr;

        CompositeGauge(ObjectName objectName, String attrName, String compositeAttr) {
            this.objectName = objectName;
            this.attrName = attrName;
            this.compositeAttr = compositeAttr;
        }

        @Override
        public Object getValue() {
            Object value = null;
            try {
                CompositeData compositeData = (CompositeData) mBeanServer.getAttribute(objectName, attrName);
                value = compositeData.get(compositeAttr);
            } catch (Exception e) {
                log.warn("error while getting {}@{}>{}", objectName, attrName, compositeAttr, e);
            }
            return value;
        }
    }

    protected void handleComposite(final ObjectName objectName, final String attributeName, CompositeData composite, ObjectNode jsonNode) {
        for (Iterator<String> iterator = jsonNode.fieldNames(); iterator.hasNext(); ) {
            String compositeAttr = iterator.next();
            try {
                Object o = composite.get(compositeAttr);
                // register handler based on Type
                String monitorName = jsonNode.get(compositeAttr).textValue() + metricsSuffix;
                metricRegistry.register(monitorName, new CompositeGauge(objectName, attributeName, compositeAttr));
                log.info("mBean {}@{}>{} registered as {}", objectName.toString(), attributeName, compositeAttr, monitorName);
            } catch (Exception e) {
                log.warn("Attribute {} not part of CompositeData {}@{}", compositeAttr, objectName.toString(), attributeName);
            }
        }
    }

    protected void initReporter(AWSCredentials awsCredentials) {
        final AmazonCloudWatchAsyncClient cloudWatchClient = new AmazonCloudWatchAsyncClient(awsCredentials);
        cloudWatchClient.setEndpoint(settings.endPoint);
        //start cloudwatch reporting
        new CloudWatchReporter(
                metricRegistry,
                settings.cloudWatchNamespace,
                cloudWatchClient
        ).start(settings.reportInterval, TimeUnit.SECONDS);
    }

    protected void addBeanNotificationListener() throws IOException {
        MBeanServerNotificationFilter filter = new MBeanServerNotificationFilter();
        filter.enableAllObjectNames();
        try {
            mBeanServer.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, this, filter, null);
        } catch (InstanceNotFoundException e) {
            throw new IOException(e);
        }
    }

    protected void initAllowedBeans() {
        metricsSuffix = settings.instanceId != null ? " instance=" + settings.instanceId + "*" : "";
    }

    protected void readConfig(String configFile) throws IOException {
        File file = new File(configFile);
        if (!file.isFile()) {
            throw new IOException(String.format("%s not found", file.getAbsolutePath()));
        }
        settings = jsonMapper.readValue(file, Settings.class);
    }




    @Override
    public void handleNotification(Notification notification, Object handback) {
        try {
            MBeanServerNotification mbs = (MBeanServerNotification) notification;
            if (MBeanServerNotification.REGISTRATION_NOTIFICATION.equals(mbs.getType())) {
                ObjectName objectName = ((MBeanServerNotification) notification).getMBeanName();
                ObjectNode jsonNodes = settings.beans.get(objectName);
                MBeanInfo mBeanInfo = mBeanServer.getMBeanInfo(objectName);
                if (jsonNodes != null) {
                    handleAttributes(mBeanInfo, objectName, jsonNodes);
                } else if (settings.logIgnoredAttributes) {
                    logAllAttributes(objectName,mBeanInfo);
                }
            }
        } catch (Exception exc) {
            log.error("error while registering bean:", exc);
        }
    }

    protected void logAllAttributes(ObjectName objectName, MBeanInfo mBeanInfo) {
        for (MBeanAttributeInfo mBeanAttributeInfo : mBeanInfo.getAttributes()) {
            log.info("{}@{} ignored",objectName,mBeanAttributeInfo.getName());
        }
    }

}

