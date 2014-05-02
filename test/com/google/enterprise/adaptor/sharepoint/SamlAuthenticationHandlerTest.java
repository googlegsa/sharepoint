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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.enterprise.adaptor.sharepoint.FormsAuthenticationHandlerTest.UnsupportedScheduledExecutor;
import com.google.enterprise.adaptor.sharepoint.FormsAuthenticationHandlerTest.MockScheduledExecutor;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.SamlHandshakeManager;

import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;


public class SamlAuthenticationHandlerTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();
  
  @Test
  public void testBuilder() {
    new SamlAuthenticationHandler.Builder("username", "password",
        new UnsupportedScheduledExecutor(),
        new UnsupportedSamlHandshakeManager()).build();
  }
  
  @Test
  public void testNullSamlClient() {
    thrown.expect(NullPointerException.class);
    new SamlAuthenticationHandler.Builder("username", "password",
         new UnsupportedScheduledExecutor(), null).build();
  }
  
  @Test
  public void testIsFormsAutentication() throws IOException {
    assertTrue(new SamlAuthenticationHandler.Builder("username", "password",
        new UnsupportedScheduledExecutor(),
        new UnsupportedSamlHandshakeManager()).build().isFormsAuthentication());    
  }
  
  @Test
  public void testNullToken() throws IOException {
    SamlAuthenticationHandler handler = new SamlAuthenticationHandler.Builder(
        "username", "password", new UnsupportedScheduledExecutor(),
        new MockSamlHandshakeManager(null, null) {
          @Override public String getAuthenticationCookie(String token) {
            throw new UnsupportedOperationException();
          }      
        }).build();
    
    assertTrue(handler.isFormsAuthentication());
    thrown.expect(IOException.class);
    AuthenticationResult result = handler.authenticate();    
  }
  
  @Test
  public void testSAMLAutentication() throws IOException {
    SamlAuthenticationHandler handler 
        = new SamlAuthenticationHandler.Builder("username", "password", 
            new MockScheduledExecutor(),
            new MockSamlHandshakeManager("token", "AuthenticationCookie"))
            .build();    
    assertTrue(handler.isFormsAuthentication());
    AuthenticationResult result = handler.authenticate();
    assertNotNull(result);
    assertEquals("AuthenticationCookie", result.getCookie());
    assertEquals(600, result.getCookieTimeOut());
    assertEquals("NO_ERROR", result.getErrorCode());    
  }
  
  private static class UnsupportedSamlHandshakeManager 
      implements SamlHandshakeManager {
    @Override
    public String requestToken() throws IOException {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public String getAuthenticationCookie(String token) throws IOException {
      throw new UnsupportedOperationException();
    }    
  }
  
  private static class MockSamlHandshakeManager 
      extends UnsupportedSamlHandshakeManager {
    private String token;
    private String cookie;
    MockSamlHandshakeManager(String token, String cookie) {
      this.token = token;
      this.cookie = cookie;      
    }
    
    @Override
    public String requestToken() {
      return token;
    }
    
    @Override
    public String getAuthenticationCookie(String token) throws IOException {
      return cookie;
    }
  }  
}
