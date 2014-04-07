package com.mojn.jmx;

import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Created by johannes on 4/3/14.
 */
public class Agent {
    public static void premain(String agentArgument) {
        Thread thread = Thread.currentThread();
        ClassLoader contextClassLoader = thread.getContextClassLoader();
        try {
            URL resource = Agent.class.getResource("ReporterAgent.class");
            URL url = new URL(resource.getFile().replaceAll("!.*$",""));
            System.err.println(url.toExternalForm());
            ParentLastURLClassLoader parentLastURLClassLoader = new ParentLastURLClassLoader(new URL[]{url});
            Class<?> clazz = parentLastURLClassLoader.loadClass("com.mojn.jmx.ReporterAgent",false);
            clazz.getMethod("init",String.class).invoke(clazz.newInstance(), agentArgument);
        } catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            thread.setContextClassLoader(contextClassLoader);
        }
    }
}