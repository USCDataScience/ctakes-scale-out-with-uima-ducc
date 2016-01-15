/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.shangridocs.services.tika;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.transport.http.HTTPConduit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.InterruptedException;

@Path("/tika")
public class TikaCtakesResource {

  public static final Logger LOG = Logger.getLogger(TikaCtakesResource.class
      .getName());

  public static final long DEFAULT_TIMEOUT = 1000000L;

  private URL tikaCtakesURL;

  private URL tikaURL;

  private static final String TIKA_URL_PROPERTY = "org.shangridocs.tika.url";

  private static final String CTAKES_URL_PROPERTY = "org.shangridocs.tika.ctakes.url";

  private static final String DUCC_JOB_SUBMIT_CLASSPATH = "org.apache.uima.ducc.cli.DuccJobSubmit";

  public TikaCtakesResource(@Context ServletContext sc)
      throws MalformedURLException {
    tikaURL = new URL(sc.getInitParameter(TIKA_URL_PROPERTY));
    tikaCtakesURL = new URL(sc.getInitParameter(CTAKES_URL_PROPERTY));
  }

  @GET
  @Path("/status")
  @Produces("text/html")
  public Response status() {
    return Response
        .ok("<h1>This is Tika cTAKES Resource: running correctly</h1><h2>Tika Proxy: /rmeta</h2><p>"
            + tikaURL.toString()
            + "</p><h2>cTAKES Proxy: /ctakes</h2><p>"
            + tikaCtakesURL.toString()
            + "</p> <h2>Tika Form Proxy: /rmeta/form</h3><p>"
            + tikaURL.toString() + "</p>").build();
  }

  @PUT
  @Consumes("multipart/form-data")
  @Produces({ "text/csv", "application/json" })
  @Path("/rmeta/form")
  public Response forwardTikaMultiPart(Attachment att,
      @HeaderParam("Content-Disposition") String contentDisposition) {
    return forwardProxy(att.getObject(InputStream.class), tikaURL.toString(),
        contentDisposition);
  }

  @PUT
  @Path("/rmeta")
  @Produces("application/json")
  public Response forwardTika(InputStream is,
      @HeaderParam("Content-Disposition") String contentDisposition) {
    return forwardProxy(is, tikaURL.toString(), contentDisposition);
  }

  @PUT
  @Path("/ctakes")
  public Response forwardCtakes(InputStream is,
      @HeaderParam("Content-Disposition") String contentDisposition) {
    String shangridocsHome = System.getenv("SHANGRIDOCS_HOME");
    LOG.info("SHANGRIDOCS_HOME: " + shangridocsHome);
    // Create output file from Tika
    String tikaOutputFile = shangridocsHome + "/shangridocs-services/src/main/resources/buffer/output.txt";
    createTikaOutput(is, tikaOutputFile);

    //Submit ctakes job to ducc with the tika output file as input
    String jobFile = shangridocsHome + "/shangridocs-services/src/main/resources/ctakes.job";
    String json = submitDuccJob(jobFile, tikaOutputFile);
    
    LOG.info("Response received: " + json);
    return Response.ok(json, MediaType.APPLICATION_JSON).build();
  }

  private void createTikaOutput(InputStream is, String outputFile) {
    try {
      OutputStream os = new FileOutputStream(new File(outputFile));
      int read = 0;
      byte[] bytes = new byte[1024];
      while ((read = is.read(bytes)) != -1) {
        os.write(bytes, 0, read);
      }
      os.close();     
      LOG.info("Tika output is created here: " + outputFile); 
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String submitDuccJob(String jobFile, String outputFile) {
    String output = "";
    try {
      String duccHome = System.getenv("DUCC_HOME");
      Process process = null;
      try {
        // Submit DUCC job  
        ProcessBuilder duccPb = new ProcessBuilder(new String[] {"java", "-cp", duccHome + "/lib/uima-ducc-cli.jar", 
           DUCC_JOB_SUBMIT_CLASSPATH, "-f", jobFile});
        process = duccPb.start();

        LOG.info("DUCC_HOME: " + duccHome);
        LOG.info("Submitted job file: " + jobFile);

        BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        // Read the output from the command
        LOG.info("Standard output of ducc_submit: ");
        String str = null;
        while ((str = stdInput.readLine()) != null) {
            System.out.println(str);
        }
         
        // Read any errors from the attempted command
        LOG.info("Error of ducc_subimt (if any): ");
        while ((str = stdError.readLine()) != null) {
            System.out.println(str);
        }

        process.waitFor(); 

        // Convert the format to the desired one in Shangridocs
        String ctakesHome = System.getenv("CTAKES_HOME");
        String shangridocsHome = System.getenv("SHANGRIDOCS_HOME");
        String handlerHome = shangridocsHome + "/shangridocs-services/src/main/resources/CTAKESContentHandler";
        ProcessBuilder handlerPb = new ProcessBuilder(new String[] {"java", "-cp", 
          handlerHome + "/lib/*:" + handlerHome + "/config:" + ctakesHome + "/lib/*", 
          "CTAKESContentToMetadataHandler", "-i", outputFile + ".xml", "-o", outputFile});
        process = handlerPb.start();

        // Read any errors from the attempted command
        while ((str = stdError.readLine()) != null) {
            System.out.println(str);
        }

        process.waitFor();
        
      } catch (InterruptedException e) {
          e.printStackTrace();
      } 

      // read the output from DUCC
      InputStream inputStream = new FileInputStream(outputFile);
      BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      output = sb.toString();

    } catch (IOException e) {
      e.printStackTrace();
    }
    return output;
  }

  private Response forwardProxy(InputStream is, String url,
      String contentDisposition) {
    LOG.info("PUTTING document [" + contentDisposition + "] to Tika at :["
        + url + "]");
    WebClient client = WebClient.create(url).accept("application/json")
        .header("Content-Disposition", contentDisposition);
    HTTPConduit conduit = WebClient.getConfig(client).getHttpConduit();
    conduit.getClient().setConnectionTimeout(DEFAULT_TIMEOUT);
    conduit.getClient().setReceiveTimeout(DEFAULT_TIMEOUT);
    Response response = client.put(is);
    String json = response.readEntity(String.class);
    LOG.info("Response received: " + json);
    return Response.ok(json, MediaType.APPLICATION_JSON).build();
  }

}