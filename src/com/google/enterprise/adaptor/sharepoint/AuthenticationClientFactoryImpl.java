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

import com.google.common.base.Strings;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.SamlHandshakeManager;

import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.ws.EndpointReference;
import javax.xml.ws.Service;
import javax.xml.ws.wsaddressing.W3CEndpointReferenceBuilder;

/**
 * Authentication Factory implementation to return appropriate
 * authentication client for FormsAuthenticationHandler implementation.
 */
public class AuthenticationClientFactoryImpl 
    implements AuthenticationClientFactory {
  /** SharePoint's namespace. */
  private static final String XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";

  private static final Logger log
      = Logger.getLogger(AuthenticationClientFactoryImpl.class.getName());

  private final Service authenticationService;
    
    public AuthenticationClientFactoryImpl() {
      this.authenticationService = Service.create(
          AuthenticationSoap.class.getResource("Authentication.wsdl"),
          new QName(XMLNS, "Authentication"));
    }

    private static String handleEncoding(String endpoint) {
      // Handle Unicode. Java does not properly encode the POST path.
      return URI.create(endpoint).toASCIIString();
    }

    private static URI spUrlToUri(String url) throws IOException {
      // Because SP is silly, the path of the URI is unencoded, but the rest of
      // the URI is correct. Thus, we split up the path from the host, and then
      // turn them into URIs separately, and then turn everything into a
      // properly-escaped string.
      String[] parts = url.split("/", 4);
      if (parts.length < 3) {
        throw new IllegalArgumentException("Too few '/'s: " + url);
      }
      String host = parts[0] + "/" + parts[1] + "/" + parts[2];
      // Host must be properly-encoded already.
      URI hostUri = URI.create(host);
      if (parts.length == 3) {
        // There was no path.
        return hostUri;
      }
      URI pathUri;
      try {
        pathUri = new URI(null, null, "/" + parts[3], null);
      } catch (URISyntaxException ex) {
        throw new IOException(ex);
      }
      return hostUri.resolve(pathUri);
    }

    @Override
    public AuthenticationSoap newSharePointFormsAuthentication(
        String virtualServer, String username, String password)
        throws IOException {
      String authenticationEndPoint = spUrlToUri(virtualServer
          + "/_vti_bin/Authentication.asmx").toString();
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(handleEncoding(authenticationEndPoint)).build();       
          authenticationService.getPort(endpointRef, AuthenticationSoap.class);
      return 
          authenticationService.getPort(endpointRef, AuthenticationSoap.class);
    }

    @Override
    public SamlHandshakeManager newAdfsAuthentication(String virtualServer,
        String username, String password, String stsendpoint, String stsrealm,
      String login, String trustlocation) throws IOException {      
      AdfsHandshakeManager.Builder manager 
          = new AdfsHandshakeManager.Builder(virtualServer, username,
              password, stsendpoint, stsrealm);
      if (!Strings.isNullOrEmpty(login)) {
        log.log(Level.CONFIG,
            "Using non default login value for ADFS [{0}]", login);
        manager.setLoginUrl(login);
      }
      if (!Strings.isNullOrEmpty(trustlocation)) {
        log.log(Level.CONFIG, "Using non default trust location for ADFS [{0}]",
            trustlocation);
        manager.setTrustLocation(trustlocation);
      }
      return manager.build();
    }

    @Override
    public SamlHandshakeManager newLiveAuthentication(String virtualServer,
        String username, String password) throws IOException {   
      return new LiveAuthenticationHandshakeManager.Builder(
              virtualServer, username, password).build();
    }
}
