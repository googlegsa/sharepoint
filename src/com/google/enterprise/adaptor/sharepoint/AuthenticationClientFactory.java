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


import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.SamlHandshakeManager;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;

import java.io.IOException;
import java.util.Map;

/**
 * Authentication Factory to return appropriate authentication client for
 * FormsAuthenticationHandler implementation.
 */
public interface AuthenticationClientFactory {  
  public AuthenticationSoap newSharePointFormsAuthentication(
      String virtualServer, String username, String password)
      throws IOException;

  public SamlHandshakeManager newAdfsAuthentication(String virtualServer,
      String username, String password, String stsendpoint, String stsrealm,
      String login, String trustlocation) throws IOException;

  public SamlHandshakeManager newLiveAuthentication(String virtualServer,
      String username, String password) throws IOException;
  
  public SamlHandshakeManager newCustomSamlAuthentication(
      String factoryMethodName, Map<String, String> config) throws IOException;
}
