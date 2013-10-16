// Copyright 2012 Google Inc. All Rights Reserved.
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
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationMode;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;
import com.microsoft.schemas.sharepoint.soap.authentication.LoginErrorCode;
import com.microsoft.schemas.sharepoint.soap.authentication.LoginResult;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.ws.BindingProvider;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.handler.MessageContext;


/**
 * Helper class to handle forms authentication.
 */
class FormsAuthenticationHandler {
  /** SharePoint's namespace. */
  private static final String XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";
  
  private static final Logger log
      = Logger.getLogger(FormsAuthenticationHandler.class.getName());
  // Default time out for forms authentication with .NET is 30 mins
  private static final long DEFAULT_COOKIE_TIMEOUT_SECONDS = 30 * 60;

  private final String userName;
  private final String password;
  private final ScheduledExecutorService executor;
  private final Runnable refreshRunnable = new RefreshRunnable();  
  private final List<String> authenticationCookiesList 
      = new CopyOnWriteArrayList<String>();
  private AuthenticationMode authenticationMode;
  private final AuthenticationSoap authenticationClient;
  
  @VisibleForTesting    
  FormsAuthenticationHandler(String userName, String password,
      ScheduledExecutorService executor,
      AuthenticationSoap authenticationClient) {
    if (userName == null || password == null || executor == null || 
        authenticationClient == null) {
      throw new NullPointerException();
    }
    this.userName = userName;
    this.password = password;   
    this.executor = executor;
    this.authenticationClient = authenticationClient;
  }  

  public List<String> getAuthenticationCookies() {
    return Collections.unmodifiableList(authenticationCookiesList);
  }
  
  public boolean isFormsAuthentication() {
    return authenticationMode == AuthenticationMode.FORMS;
  }
  
  private void refreshCookies() throws IOException {
    log.log(Level.FINE, "AuthenticationMode = {0}", authenticationMode);
    if (authenticationMode != AuthenticationMode.FORMS) {
      return;
    }
    
    LoginResult result;
    try {
      result = authenticationClient.login(userName, password);
    } catch (WebServiceException ex) {
      log.log(Level.WARNING,
          "Possible SP2013 environment with windows authentication", ex);
      authenticationMode = AuthenticationMode.WINDOWS;
      return;
    }
    log.log(Level.FINE, 
        "Login Cookie Expiration in = {0}", result.getTimeoutSeconds());
    if (result.getErrorCode() != LoginErrorCode.NO_ERROR) {
      log.log(Level.WARNING, "Forms authentication failed with authentication "
          + "web service with Error Code {0}. Possible SharePoint environment "
          + "with multiple claims provider. Adaptor might have been configured "
          + "to use windows authentication.", result.getErrorCode());
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> responseHeaders
        = (Map<String, Object>) ((BindingProvider) authenticationClient)
        .getResponseContext().get(MessageContext.HTTP_RESPONSE_HEADERS);
    log.log(Level.FINEST, "Response headers: {0}", responseHeaders);
    @SuppressWarnings("unchecked")
    String cookies = ((List<String>) responseHeaders.get("Set-cookie")).get(0);
    if (authenticationCookiesList.isEmpty()) {
      authenticationCookiesList.add(cookies);
    } else {
      authenticationCookiesList.set(0, cookies);
    }
    long cookieTimeOut = (result.getTimeoutSeconds() == null) ? 
        DEFAULT_COOKIE_TIMEOUT_SECONDS : result.getTimeoutSeconds();

    long rerunAfter = (cookieTimeOut + 1) / 2;
    executor.schedule(refreshRunnable, rerunAfter, TimeUnit.SECONDS);
    log.log(Level.FINEST,
        "Authentication Cookie is {0}", authenticationCookiesList);   
 }
 
  public void start() throws IOException {
    authenticationMode = authenticationClient.mode();
    refreshCookies();
  }

  private class RefreshRunnable implements Runnable {
    @Override
    public void run() {
      try {
        refreshCookies();
      } catch(IOException ex) {
        log.log(Level.WARNING, 
            "Error refreshing forms authentication cookies", ex);        
        executor.schedule(this, 5, TimeUnit.MINUTES);
      }
    }
  }
}
