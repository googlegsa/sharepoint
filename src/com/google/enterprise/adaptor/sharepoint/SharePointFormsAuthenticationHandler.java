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

import static com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.getAdaptorUser;

import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationMode;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;
import com.microsoft.schemas.sharepoint.soap.authentication.LoginErrorCode;
import com.microsoft.schemas.sharepoint.soap.authentication.LoginResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.handler.MessageContext;

/**
 * AuthenticationHandler implementation for SharePoint forms authentication
 * using Authentication.asmx web service.
*/
public class SharePointFormsAuthenticationHandler 
    extends FormsAuthenticationHandler {
  private static final Logger log
      = Logger.getLogger(SharePointFormsAuthenticationHandler.class.getName());
  // Default time out for forms authentication with .NET is 30 mins
  private static final int DEFAULT_COOKIE_TIMEOUT_SECONDS = 30 * 60;
  private final AuthenticationSoap authenticationClient;
  private AuthenticationMode authenticationMode;

  private SharePointFormsAuthenticationHandler(String username, String password,
      ScheduledExecutorService executor,
      AuthenticationSoap authenticationClient) {
    super(username, password, executor);
    this.authenticationClient = authenticationClient;
  }
  
  public static class Builder {
    private final String username;
    private final String password;
    private final ScheduledExecutorService executor;
    private final AuthenticationSoap authenticationClient;
    public Builder(String username, String password,
        ScheduledExecutorService executor,
        AuthenticationSoap authenticationClient) {
      if (username == null || password == null || executor == null
          || authenticationClient == null) {
        throw new NullPointerException();        
      }
      this.username = username;
      this.password = password;
      this.executor = executor;
      this.authenticationClient = authenticationClient;      
    }
    
    public SharePointFormsAuthenticationHandler build() {
      SharePointFormsAuthenticationHandler authenticationHandler
          = new SharePointFormsAuthenticationHandler(
              username, password, executor, authenticationClient);
      return authenticationHandler;
    }    
  }

  @Override
  public AuthenticationResult authenticate() throws IOException {
    if (!isFormsAuthentication()) {
      return new AuthenticationResult(null, DEFAULT_COOKIE_TIMEOUT_SECONDS,
          LoginErrorCode.NOT_IN_FORMS_AUTHENTICATION_MODE.toString());
    }
    LoginResult result;
    try {
      result = authenticationClient.login(username, password);
    } catch (WebServiceException ex) {
      log.log(Level.WARNING,
          "Forms authentication failed.", ex);
      log.log(Level.INFO, "Possible SharePoint environment configured to use "
          + "claims based windows integrated authentication. "
          + "Adaptor will fallback to use windows integrated authentication "
          + "using username \"{0}\"", getAdaptorUser(""));
      return new AuthenticationResult(null, DEFAULT_COOKIE_TIMEOUT_SECONDS,
          LoginErrorCode.NOT_IN_FORMS_AUTHENTICATION_MODE.toString());
    }

    log.log(Level.FINE,
        "Login Cookie Expiration in = {0}", result.getTimeoutSeconds());
    if (result.getErrorCode() != LoginErrorCode.NO_ERROR) {
      log.log(Level.WARNING, "Forms authentication failed with error code {0}. "
          + "Possible SharePoint environment with multiple claims providers. "
          + "Adaptor will fallback to use windows integrated authentication "
          + "using username \"{1}\"",
          new Object[] {result.getErrorCode(), getAdaptorUser("")});
      return new AuthenticationResult(null, DEFAULT_COOKIE_TIMEOUT_SECONDS,
          result.getErrorCode().toString());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> responseHeaders
        = (Map<String, Object>) ((BindingProvider) authenticationClient)
        .getResponseContext().get(MessageContext.HTTP_RESPONSE_HEADERS);
    log.log(Level.FINEST, "Response headers: {0}", responseHeaders);
    if(!responseHeaders.containsKey("Set-cookie")) {
      throw new IOException("Unable to extract authentication cookie.");
    }
    
    @SuppressWarnings("unchecked")
    List<String> cookies = (List<String>) responseHeaders.get("Set-cookie");
    if (cookies == null || cookies.isEmpty()) {
      throw new IOException("Unable to extract authentication cookie.");
    }
    
    int cookieTimeout;
    // On SP2007 result.getTimeoutSeconds() can be null
    if (result.getTimeoutSeconds() == null) {
      log.log(Level.FINE,
          "Login cookie timeout is null. Using default cookie timeout.");
      cookieTimeout = DEFAULT_COOKIE_TIMEOUT_SECONDS;
    } else {
      cookieTimeout = result.getTimeoutSeconds() > 0 
          ? result.getTimeoutSeconds() : DEFAULT_COOKIE_TIMEOUT_SECONDS;
    }
    
    log.log(Level.FINE,
        "Login Cookie Expiration in = {0} seconds", cookieTimeout);
 
    return new AuthenticationResult(cookies.get(0), cookieTimeout,
        result.getErrorCode().toString());
  }

  public boolean isFormsAuthentication() throws IOException {
    if (authenticationMode == null) {
      // Cache authentication mode value to avoid repetitive web service 
      // calls to get authentication mode.     
      setAuthenticationMode(authenticationClient.mode());
      log.log(Level.FINE, "Authentication Mode {0}", authenticationMode);
    }
    return authenticationMode == AuthenticationMode.FORMS;
  }
  
  private synchronized void setAuthenticationMode(AuthenticationMode mode) {
    authenticationMode = mode;
  }
}
