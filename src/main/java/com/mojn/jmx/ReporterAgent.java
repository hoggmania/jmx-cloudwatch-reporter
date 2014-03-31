package com.mojn.jmx;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerNotification;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.relation.MBeanServerNotificationFilter;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient;
import com.blacklocus.metrics.CloudWatchReporter;
import com.codahale.metrics.JmxAttributeGauge;
import com.codahale.metrics.MetricRegistry;

public class ReporterAgent {

	public static void premain(String agentArgument, Instrumentation instrumentation){
		
		Object propertyPath = System.getProperties().get("aws.credentials.file"); 
		Object beanPath = System.getProperties().get("beans.file");
		Object endPoint	= System.getProperties().get("aws.cloudwatch.endpoint");
		Object instanceId = System.getProperties().get("aws.instance");
		
		
		//read path for credentials file to s3
		//and also read path to list of jmx beans to do transfer for
		if(  propertyPath != null && beanPath != null && endPoint != null && instanceId != null ){
			
			BufferedReader beanList = null;

			//suffix so we get counters splitted per instance
			String suffix = " instance="+instanceId+"*";
			
			try {
			
				//credential should be specified as described here http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/PropertiesCredentials.html
				AWSCredentials awsCredentials = new PropertiesCredentials(new File(propertyPath.toString()));
				
				//read list of jmx beans to monitor
				beanList = new BufferedReader( new InputStreamReader( new FileInputStream(new File(beanPath.toString())) ) );
				
				//reads list of allowed beans
				final Map<String,String> allowedBeans = new HashMap<String,String>();
				String beanName = beanList.readLine();

				while( beanName != null ){
					//split into allowed name, cloudwatch name
					//note that name should contain at least a space, as the cloud watch reporter otherwise will fail in
					String[] beanNameSplitted = beanName.split("\t");
					allowedBeans.put(beanNameSplitted[0],(beanNameSplitted.length > 1 ? beanNameSplitted[1] : beanNameSplitted[0])+suffix ) ;
					beanName = beanList.readLine();
				}
				
				final MetricRegistry metricRegistry = new MetricRegistry();
				final MBeanServer beanServer = ManagementFactory.getPlatformMBeanServer();

				
			    final AmazonCloudWatchAsyncClient cloudWatchClient = new AmazonCloudWatchAsyncClient(awsCredentials);
			    cloudWatchClient.setEndpoint(endPoint.toString());
			    //cloudWatchClient.setEndpoint("monitoring.eu-west-1.amazonaws.com");
				
				//listener
				NotificationListener listener = new NotificationListener() {

					@Override
					public void handleNotification(Notification notification,Object handback) {
						
						try {
							MBeanServerNotification mbs = (MBeanServerNotification) notification;
							
							
							if(MBeanServerNotification.REGISTRATION_NOTIFICATION.equals(mbs.getType())) {
	
								MBeanInfo beanInfo = beanServer.getMBeanInfo(mbs.getMBeanName());
									for( MBeanAttributeInfo attribute : beanInfo.getAttributes() ){
										if( attribute.isReadable() ){

											String counterName = allowedBeans.get( mbs.getMBeanName().toString() +"."+attribute.getName() ); 
											
											if( counterName != null ){

												metricRegistry.register(counterName, new JmxAttributeGauge(mbs.getMBeanName(), attribute.getName()));
	
												System.out.println("MBean Registered [" + mbs.getMBeanName() +"."+attribute.getName()+ "] as " + counterName);
										
											} else {
												
												System.out.println( "MBean [" + mbs.getMBeanName().toString() + "."+attribute.getName()+"] was not registered as attribute wasn't in the white list" );
											}
											
											
										} else {
											System.out.println( "MBean [" + mbs.getMBeanName().toString() + "]."+attribute.getName()+" was not registered as attribute wasn't readable" );
										}
									}
								
						    }
						} catch (  Exception exc ){
							System.err.println("error while registering bean:"+exc.getMessage());
						}
						
					}
					
				};
				
				//set up listeners for jmx events, so we get notifications for all new beans that are registered
				MBeanServerNotificationFilter filter = new MBeanServerNotificationFilter();
			    filter.enableAllObjectNames();				
				beanServer.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, listener, filter, null);

			    //start cloudwatch reporting
			    new CloudWatchReporter(
					metricRegistry,
			        "voldemort-metrics",
			        cloudWatchClient
			    ).start(10, TimeUnit.SECONDS);
				
				
			} catch ( Exception exc ){
				System.err.println("Got error while setting up bean listeners:" + exc.getMessage());
			} finally {
				if( beanList != null ) {
					try {
						beanList.close();
					} catch ( Exception exc ){
						System.err.println("Got error while closing reader:" + exc.getMessage());
					}
				}
					
			}
			
		} else {
			if( propertyPath == null ){
				System.out.println("No path was found for aws credentials file, please start jvm with -Daws.credentials.file={path to file}");
			}
			if( beanPath == null ){
				System.out.println("No path was found for bean list, please start jvm with -Dbeans.file={path to file}");
			}
			if( endPoint == null ){
				System.out.println("No endpoint found for cloudwatch client, please start jvm with -Daws.cloudwatch.endpoint={endpoint}");
			}
			if( instanceId == null ){
				System.out.println("No instance id found for cloudwatch client, please start jvm with -Daws.instance={instance id}");
			}
			
		}
		
		
		
		
	}
	
}

