/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Copyright 2002, 2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.arquillian.container.tomcat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;

/**
 * Based on AbstractCatalinaTask, abstract base class for Ant tasks that interact with the <em>Manager</em> web application for
 * dynamically deploying and undeploying applications.
 *
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 * @author Craig R. McClanahan
 *
 */
public class CommonTomcatManager<C extends CommonTomcatConfiguration>
{
   private static Logger log = Logger.getLogger(CommonTomcatManager.class.getName());

   // encoding of manager web app
   protected static final String MANAGER_CHARSET = "utf-8";

   private C configuration;

   /**
    * Creates a Tomcat manager abstraction
    *
    * @param configuration the configuration
    */
   public CommonTomcatManager(C configuration)
   {
      this.configuration = configuration;
   }

   public void deploy(String name, URL content) throws IOException, DeploymentException
   {
      final String contentType = "application/octet-stream";
      Validate.notNullOrEmpty(name, "Name must not be null or empty");
      Validate.notNull(content, "Content to be deployed must not be null");

      URLConnection conn = content.openConnection();
      int contentLength = conn.getContentLength();
      InputStream stream = new BufferedInputStream(conn.getInputStream());

      // Building URL
      StringBuilder command = new StringBuilder(getDeployCommand());
      try
      {
         command.append(URLEncoder.encode(name, configuration.getUrlCharset()));
      }
      catch (UnsupportedEncodingException e)
      {
         throw new DeploymentException("Unable to construct path for Tomcat manager", e);
      }

      execute(command.toString(), stream, contentType, contentLength);
   }

   public void undeploy(String name) throws IOException, DeploymentException
   {
      Validate.notNullOrEmpty(name, "Undeployed name must not be null or empty");

      // Building URL
      StringBuilder command = new StringBuilder(getUndeployCommand());
      try
      {
         command.append(URLEncoder.encode(name, configuration.getUrlCharset()));
      }
      catch (UnsupportedEncodingException e)
      {
         throw new DeploymentException("Unable to construct path for Tomcat manager", e);
      }

      execute(command.toString(), null, null, -1);
   }

   public void list() throws IOException
   {
      execute(getListCommand(), null, null, -1);
   }

   public boolean isRunning()
   {
      try
      {
         list();
         return true;
      }
      catch (IOException e)
      {
         return false;
      }
   }

   public String normalizeArchiveName(String name)
   {
      Validate.notNull(name, "Archive name must not be empty");

      if ("ROOT.war".equals(name))
      {
         return "";
      }

      if (name.indexOf('.') != -1)
      {
         return name.substring(0, name.lastIndexOf("."));
      }

      return name;
   }

   protected String getDeployCommand()
   {
      return "/deploy?path=";
   }

   protected String getUndeployCommand()
   {
      return "/undeploy?path=";
   }

   protected String getListCommand()
   {
      return "/list";
   }

   /**
    * Execute the specified command, based on the configured properties. The input stream will be closed upon completion of
    * this task, whether it was executed successfully or not.
    *
    * @param command Command to be executed
    * @param istream InputStream to include in an HTTP PUT, if any
    * @param contentType Content type to specify for the input, if any
    * @param contentLength Content length to specify for the input, if any
    * @throws IOException
    * @throws MalformedURLException
    * @throws DeploymentException
    */
   protected void execute(String command, InputStream istream, String contentType, int contentLength)
         throws IOException
   {

      URLConnection conn = null;
      try
      {
         // Create a connection for this command
         conn = (new URL(configuration.getManagerUrl() + command)).openConnection();
         HttpURLConnection hconn = (HttpURLConnection) conn;

         // Set up standard connection characteristics
         hconn.setAllowUserInteraction(false);
         hconn.setDoInput(true);
         hconn.setUseCaches(false);
         if (istream != null)
         {
            hconn.setDoOutput(true);
            hconn.setRequestMethod("PUT");
            if (contentType != null)
            {
               hconn.setRequestProperty("Content-Type", contentType);
            }
            if (contentLength >= 0)
            {
               hconn.setRequestProperty("Content-Length", "" + contentLength);

               hconn.setFixedLengthStreamingMode(contentLength);
            }
         }
         else
         {
            hconn.setDoOutput(false);
            hconn.setRequestMethod("GET");
         }
         hconn.setRequestProperty("User-Agent", "Arquillian-Tomcat-Manager-Util/1.0");
         // add authorization header if password is provided
         if (configuration.getUser() != null && configuration.getUser().length() != 0)
         {
            hconn.setRequestProperty("Authorization", constructHttpBasicAuthHeader());
         }
         hconn.setRequestProperty("Accept", "text/plain");

         // Establish the connection with the server
         hconn.connect();

         // Send the request data (if any)
         if (istream != null)
         {
            BufferedOutputStream ostream = new BufferedOutputStream(hconn.getOutputStream(), 1024);
            IOUtil.copy(istream, ostream);
            ostream.flush();
            ostream.close();
            istream.close();
         }

         processResponse(command, hconn);
      }
      finally
      {
         IOUtil.closeQuietly(istream);
      }
   }

   protected void processResponse(String command, HttpURLConnection hconn) throws IOException
   {
      int httpResponseCode = hconn.getResponseCode();
      // Supposes that <= 199 is not bad, but is it? See http://en.wikipedia.org/wiki/List_of_HTTP_status_codes
      if (httpResponseCode >= 400 && httpResponseCode < 500)
      {
         throw new ConfigurationException(
               "Unable to connect to Tomcat manager. "
                     + "The server command ("
                     + command
                     + ") failed with responseCode ("
                     + httpResponseCode
                     + ") and responseMessage ("
                     + hconn.getResponseMessage()
                     + ").\n\n"
                     + "Please make sure that you provided correct credentials to an user which is able to access Tomcat manager application.\n"
                     + "These credentials can be specified in the Arquillian container configuration as \"user\" and \"pass\" properties.\n"
                     + "The user must have appripriate role specified in tomcat-users.xml file.\n");
      }
      else if (httpResponseCode >= 300)
      {
         throw new IllegalStateException("The server command (" + command + ") failed with responseCode ("
               + httpResponseCode + ") and responseMessage (" + hconn.getResponseMessage() + ").");
      }
      BufferedReader reader = null;
      try
      {
         // Process the response message
         reader = new BufferedReader(new InputStreamReader(hconn.getInputStream(), MANAGER_CHARSET));
         String line = reader.readLine();
         String contentError = null;
         if (line != null && !line.startsWith("OK -"))
         {
            contentError = line;
         }
         while (line != null)
         {
            if (log.isLoggable(Level.FINE))
            {
               log.fine(line);
            }
            line = reader.readLine();
         }
         if (contentError != null)
         {
            throw new RuntimeException("The server command (" + command + ") failed with content (" + contentError
                  + ").");
         }
      }
      finally
      {
         IOUtil.closeQuietly(reader);
      }
   }

   protected String constructHttpBasicAuthHeader()
   {
      // Set up an authorization header with our credentials
      String credentials = configuration.getUser() + ":" + configuration.getPass();
      // Encodes the user:password pair as a sequence of ISO-8859-1 bytes.
      // We'll return the Base64 encoded form of this ISO-8859-1 byte sequence.
      try
      {
         return "Basic " + Base64Coder.encodeString_IOS_8859_1(credentials);
      }
      catch (UnsupportedEncodingException e)
      {
         throw new RuntimeException(e);
      }
   }

}
