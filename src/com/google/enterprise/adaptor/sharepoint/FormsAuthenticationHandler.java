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
import com.google.common.base.Strings;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Helper class to handle forms authentication.
 */
abstract class FormsAuthenticationHandler {
  /** SharePoint's namespace. */
  private static final String XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";
  
  private static final Logger log
      = Logger.getLogger(FormsAuthenticationHandler.class.getName());
  // Default time out for forms authentication with .NET is 30 mins
  private static final long DEFAULT_COOKIE_TIMEOUT_SECONDS = 30 * 60;

  protected final String username;
  protected final String password;
  private final ScheduledExecutorService executor;
  private final Runnable refreshRunnable = new RefreshRunnable();  
  private final List<String> authenticationCookiesList 
      = new CopyOnWriteArrayList<String>();  
  private boolean isFormsAuthentication = false;

  @VisibleForTesting    
  FormsAuthenticationHandler(String username, String password,
      ScheduledExecutorService executor) {
    if (username == null || password == null || executor == null) {
      throw new NullPointerException();
    }
    this.username = username;
    this.password = password;
    this.executor = executor;   
  }

  public List<String> getAuthenticationCookies() {
    return Collections.unmodifiableList(authenticationCookiesList);
  }
  // TODO : Remove isFormAuthentication.
  abstract boolean isFormsAuthentication() throws IOException;
  
  abstract AuthenticationResult authenticate() throws IOException;
  
  private void refreshCookies() throws IOException {
    
    if ("".equals(username) || "".equals(password)) {
      log.log(Level.FINE, 
          "Empty username / password. Using authentication mode as Windows");
       isFormsAuthentication = false;
       return;
    }

    if (!isFormsAuthentication) {
      return;
    }

    log.log(Level.FINE, "About to refresh authentication cookie.");
    AuthenticationResult result = authenticate();
    log.log(Level.FINE, "Authentication Result {0}", result.getErrorCode());

    String cookie = result.getCookie();
    if (Strings.isNullOrEmpty(cookie)) {
      log.log(Level.INFO, "Authentication cookie is null or empty."
          + " Adaptor will use Windows authentication.");
      return;
    }
    if (authenticationCookiesList.isEmpty()) {
      authenticationCookiesList.add(cookie);
    } else {
      authenticationCookiesList.set(0, cookie);
    }
    long cookieTimeOut = result.getCookieTimeOut();

    long rerunAfter = (cookieTimeOut + 1) / 2;
    executor.schedule(refreshRunnable, rerunAfter, TimeUnit.SECONDS);
    log.log(Level.FINEST,
        "Authentication Cookie is {0}", authenticationCookiesList);   
 }
 
  public void start() throws IOException {
    if ("".equals(username) || "".equals(password)) {
      log.log(Level.FINE, "Empty username or password. Using windows"
          + " integrated authentication.");
       isFormsAuthentication = false;
       return;
    }
    isFormsAuthentication = isFormsAuthentication();
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