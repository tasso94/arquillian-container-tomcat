/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.tomcat.managed_5_5;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.tomcat.AdditionalJavaOptionsParser;
import org.jboss.arquillian.container.tomcat.CommonTomcatManager;
import org.jboss.arquillian.container.tomcat.ProtocolMetadataParser;
import org.jboss.arquillian.container.tomcat.Validate;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

/**
 * <p>
 * Arquillian {@link DeployableContainer} implementation for an Managed Tomcat server; responsible for both lifecycle and deployment operations.
 * </p>
 *
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 * @author <a href="mailto:jhuska@redhat.com">Juraj Huska</a>
 * @author <a href="mailto:ozizka@redhat.com">Ondrej Zizka</a>
 * @version $Revision: $
 */
public class TomcatManagedContainer implements DeployableContainer<TomcatManagedConfiguration>
{
   private static final Logger log = Logger.getLogger(TomcatManagedContainer.class.getName());
   private static final String JAVA_FROM_CURRENT_VM = 
      System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

   /**
    * Tomcat container configuration
    */
   private TomcatManagedConfiguration configuration;

   private CommonTomcatManager<TomcatManagedConfiguration> manager;

   private Thread shutdownThread;

   private Process startupProcess;

   @Override
   public Class<TomcatManagedConfiguration> getConfigurationClass()
   {
      return TomcatManagedConfiguration.class;
   }

   @Override
   public ProtocolDescription getDefaultProtocol()
   {
      return new ProtocolDescription("Servlet 2.5");
   }

   @Override
   public void setup(TomcatManagedConfiguration configuration)
   {
      this.configuration = configuration;
      this.manager = new CommonTomcatManager<TomcatManagedConfiguration>(configuration);
   }

   @Override
   public void start() throws LifecycleException
   {

      try
      {
         final String CATALINA_HOME = configuration.getCatalinaHome();
            final String CATALINA_BASE = configuration.getCatalinaBase();
         final String ADDITIONAL_JAVA_OPTS = configuration.getJavaVmArguments();

            final String absoluteCatalinaHomePath = new File(CATALINA_HOME).getAbsolutePath();
            final String absoluteCatalinaBasePath = new File(CATALINA_BASE).getAbsolutePath();

         // construct a command to execute
         List<String> cmd = new ArrayList<String>();

         cmd.add(JAVA_FROM_CURRENT_VM);

            cmd.add("-Djava.util.logging.config.file=" + absoluteCatalinaBasePath + "/conf/" + configuration.getLoggingProperties());
         cmd.add("-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager");

         cmd.add("-Dcom.sun.management.jmxremote.port=" + configuration.getJmxPort());
         cmd.add("-Dcom.sun.management.jmxremote.ssl=false");
         cmd.add("-Dcom.sun.management.jmxremote.authenticate=false");

         cmd.addAll(AdditionalJavaOptionsParser.parse(ADDITIONAL_JAVA_OPTS));

         String CLASS_PATH = absoluteCatalinaHomePath + "/bin/bootstrap.jar" + System.getProperty("path.separator");
         CLASS_PATH += absoluteCatalinaHomePath + "/bin/tomcat-juli.jar";

         cmd.add("-classpath");
         cmd.add(CLASS_PATH);
         cmd.add("-Djava.endorsed.dirs=" + absoluteCatalinaHomePath + "/endorsed");
         cmd.add("-Dcatalina.base=" + absoluteCatalinaBasePath);
         cmd.add("-Dcatalina.home=" + absoluteCatalinaHomePath);
         cmd.add("-Djava.io.tmpdir=" + absoluteCatalinaBasePath + "/temp");
         cmd.add("org.apache.catalina.startup.Bootstrap");
         cmd.add("-config");
         cmd.add(absoluteCatalinaBasePath + "/conf/" + configuration.getServerConfig());
         cmd.add("start");

         // execute command
         ProcessBuilder startupProcessBuilder = new ProcessBuilder(cmd);
         startupProcessBuilder.redirectErrorStream(true);
         startupProcessBuilder.directory(new File(configuration.getCatalinaHome() + "/bin"));
         log.info("Starting Tomcat with: " + cmd.toString());
         startupProcess = startupProcessBuilder.start();
         new Thread(new ConsoleConsumer(configuration.isOutputToConsole())).start();
         final Process proc = startupProcess;

         shutdownThread = new Thread(new Runnable()
         {
            @Override
            public void run()
            {
               if (proc != null)
               {
                  proc.destroy();
                  try
                  {
                     proc.waitFor();
                  }
                  catch (InterruptedException e)
                  {
                     throw new RuntimeException(e);
                  }
               }
            }
         });
         Runtime.getRuntime().addShutdownHook(shutdownThread);

         long startupTimeout = configuration.getStartupTimeoutInSeconds();
         long timeout = startupTimeout * 1000;
         boolean serverAvailable = false;
         while (timeout > 0 && serverAvailable == false)
         {
            serverAvailable = manager.isRunning();
            if (!serverAvailable)
            {
               Thread.sleep(100);
               timeout -= 100;
            }
         }
         if (!serverAvailable)
         {
            destroystartupProcess();
            throw new TimeoutException(String.format("Managed server was not started within [%d] s", startupTimeout));
         }

      }
      catch (Exception ex)
      {

         throw new LifecycleException("Could not start container", ex);
      }

   }

   @Override
   public void stop() throws LifecycleException
   {

      if (shutdownThread != null)
      {
         Runtime.getRuntime().removeShutdownHook(shutdownThread);
         shutdownThread = null;
      }
      try
      {
         if (startupProcess != null)
         {
            startupProcess.destroy();
            startupProcess.waitFor();
            startupProcess = null;
         }
      }
      catch (Exception e)
      {
         throw new LifecycleException("Could not stop container", e);
      }
   }

   /**
    * Deploys to remote Tomcat using it's /manager web-app's org.apache.catalina.manager.ManagerServlet.
    *
    * @param archive
    * @return
    * @throws DeploymentException
    */
   @Override
   public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException
   {
      Validate.notNull(archive, "Archive must not be null");

      String archiveName = manager.normalizeArchiveName(archive.getName());
      URL archiveURL = ShrinkWrapUtil.toURL(archive);
      try
      {
         manager.deploy("/" + archiveName, archiveURL);
      }
      catch (IOException e)
      {
         throw new DeploymentException("Unable to deploy an archive " + archive.getName(), e);
      }

      ProtocolMetadataParser<TomcatManagedConfiguration> parser = new ProtocolMetadataParser<TomcatManagedConfiguration>(configuration);
      return parser.retrieveContextServletInfo(archiveName);
   }

   @Override
   public void undeploy(Archive<?> archive) throws DeploymentException
   {
      Validate.notNull(archive, "Archive must not be null");

      String archiveName = manager.normalizeArchiveName(archive.getName());
      try
      {
         manager.undeploy("/" + archiveName);
      }
      catch (IOException e)
      {
         throw new DeploymentException("Unable to undeploy an archive " + archive.getName(), e);
      }
   }

   @Override
   public void deploy(Descriptor descriptor) throws DeploymentException
   {

      throw new UnsupportedOperationException("Not implemented");

   }

   @Override
   public void undeploy(Descriptor descriptor) throws DeploymentException
   {

      throw new UnsupportedOperationException("Not implemented");

   }

   /**
    * Runnable that consumes the output of the startupProcess. If nothing consumes the output the AS will hang on some
    * platforms
    *
    * @author Stuart Douglas
    */
   private class ConsoleConsumer implements Runnable
   {

      private final boolean writeOutput;

      ConsoleConsumer(boolean writeOutput)
      {
         this.writeOutput = writeOutput;
      }

      @Override
      public void run()
      {
         final InputStream stream = startupProcess.getInputStream();
         final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
         String line = null;
         try
         {
            while ((line = reader.readLine()) != null)
            {
               if (writeOutput)
               {
                  System.out.println(line);
               }
            }
         }
         catch (IOException e)
         {
         }
      }

   }

   private int destroystartupProcess()
   {
      if (startupProcess == null)
         return 0;
      startupProcess.destroy();
      try
      {
         return startupProcess.waitFor();
      }
      catch (InterruptedException e)
      {
         throw new RuntimeException(e);
      }
   }
}
