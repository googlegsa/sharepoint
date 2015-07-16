// Copyright 2014 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.sharepoint;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.enterprise.adaptor.IOHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FormsAuthenticationHandler for SAML based authentication.
 */
public class SamlAuthenticationHandler extends FormsAuthenticationHandler {

  private static final Logger log
      = Logger.getLogger(SamlAuthenticationHandler.class.getName());
  private static final int DEFAULT_COOKIE_TIMEOUT_SECONDS = 600;
  private static final Charset CHARSET = Charset.forName("UTF-8");

  private final SamlHandshakeManager samlClient;

  private SamlAuthenticationHandler(String username, String password,
      ScheduledExecutorService executor, SamlHandshakeManager samlClient) {
    super(username, password, executor);
    this.samlClient = samlClient;
  }
  
  public static class Builder {
    private final String username;
    private final String password;
    private final ScheduledExecutorService executor;
    private final SamlHandshakeManager samlClient;
    public Builder(String username, String password,
        ScheduledExecutorService executor, SamlHandshakeManager samlClient) {
      if (username == null || password == null || executor == null
          || samlClient == null) {
        throw new NullPointerException();        
      }
      this.username = username;
      this.password = password;
      this.executor = executor;
      this.samlClient = samlClient;      
    }
    
    public SamlAuthenticationHandler build() {
      SamlAuthenticationHandler authenticationHandler
          = new SamlAuthenticationHandler(username, password, executor,
              samlClient);      
      return authenticationHandler;
    }
    
  }

  @Override
  public AuthenticationResult authenticate() throws IOException {
    String token = samlClient.requestToken();
    if (Strings.isNullOrEmpty(token)) {
      throw new IOException("Invalid SAML token");
    }    
    String cookie = samlClient.getAuthenticationCookie(token);
    log.log(Level.FINER, "Authentication Cookie {0}", cookie);
    return new AuthenticationResult(cookie,
        DEFAULT_COOKIE_TIMEOUT_SECONDS, "NO_ERROR");
  }

  @Override
  public boolean isFormsAuthentication() throws IOException {
    return true;
  }

  @VisibleForTesting
  public interface SamlHandshakeManager {
    public String requestToken() throws IOException;
    public String getAuthenticationCookie(String token) throws IOException;
  }

  @VisibleForTesting
  public interface HttpPostClient {
    public PostResponseInfo issuePostRequest(URL url,
        Map<String, String> connectionProperties, String requestBody)
        throws IOException;
  }

  @VisibleForTesting
  public static class HttpPostClientImpl implements HttpPostClient{
    @Override
    public PostResponseInfo issuePostRequest(URL url,
        Map<String, String> connectionProperties, String requestBody)
        throws IOException {

      // Handle Unicode. Java does not properly encode the GET.
      try {
        url = new URL(url.toURI().toASCIIString());
      } catch (URISyntaxException ex) {
        throw new IOException(ex);
      }

      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      try {
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestMethod("POST");
        connection.setInstanceFollowRedirects(false);

        for(String key : connectionProperties.keySet()) {
          connection.addRequestProperty(key, connectionProperties.get(key));
        }

        if (!connectionProperties.containsKey("Content-Length")) {
          connection.addRequestProperty("Content-Length",
              Integer.toString(requestBody.length()));
        }

        OutputStream out = connection.getOutputStream();
        Writer wout = new OutputStreamWriter(out);
        wout.write(requestBody);
        wout.flush();
        wout.close();
        InputStream in = connection.getInputStream();
        String result = IOHelper.readInputStreamToString(in, CHARSET);
        return new PostResponseInfo(result, connection.getHeaderFields());
      } finally {
        InputStream inputStream = connection.getResponseCode() >= 400
            ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream != null) {
          inputStream.close();
        }
      }
    }
  }

  @VisibleForTesting
  public static class PostResponseInfo {
    /** Non-null contents. */
    private final String contents;
    /** Non-null headers. */
    private final Map<String, List<String>> headers;

    PostResponseInfo(
        String contents, Map<String, List<String>> headers) {
      this.contents = contents;
      this.headers  = (headers == null)
          ? new HashMap<String, List<String>>() 
          : new HashMap<String, List<String>>(headers);
    }

    public String getPostContents() {
      return contents;
    }

    public Map<String, List<String>> getPostResponseHeaders() {
      return Collections.unmodifiableMap(headers);
    }

    public String getPostResponseHeaderField(String header) {
      if (headers == null || !headers.containsKey(header)) {
        return null;
      }
      if (headers.get(header) == null || headers.get(header).isEmpty()) {
        return null;
      }
      StringBuilder sbValues = new StringBuilder();
      for(String value : headers.get(header)) {
        if ("".equals(value)) {
          continue;
        }
        sbValues.append(value);
        sbValues.append(";");
      }
      return sbValues.toString();
    }
  }
}
