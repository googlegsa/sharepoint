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

import com.google.enterprise.adaptor.sharepoint.FormsAuthenticationHandlerTest.MockScheduledExecutor;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.HttpPostClient;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.PostResponseInfo;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;


public class LiveAuthenticationHandshakeManagerTest {
  
  private static final String LIVE_AUTHENTICATION_RESPONSE 
      = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
      + "<S:Envelope xmlns:S=\"http://www.w3.org/2003/05/soap-envelope\" "
      + "xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-"
      + "wssecurity-secext-1.0.xsd\" "
      + "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-"
      + "wss-wssecurity-utility-1.0.xsd\" "
      + "xmlns:wsa=\"http://www.w3.org/2005/08/addressing\">"
      + "<S:Header><wsa:Action "
      + "xmlns:S=\"http://www.w3.org/2003/05/soap-envelope\" "
      + "xmlns:wsa=\"http://www.w3.org/2005/08/addressing\" "
      + "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-"
      + "wss-wssecurity-utility-1.0.xsd\" wsu:Id=\"Action\" "
      + "S:mustUnderstand=\"1\">"
      + "http://schemas.xmlsoap.org/ws/2005/02/trust/RSTR/Issue"
      + "</wsa:Action><wsa:To "
      + "xmlns:S=\"http://www.w3.org/2003/05/soap-envelope\" "
      + "xmlns:wsa=\"http://www.w3.org/2005/08/addressing\" "
      + "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-"
      + "wss-wssecurity-utility-1.0.xsd\" "
      + "wsu:Id=\"To\" S:mustUnderstand=\"1\">"
      + "http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous"
      + "</wsa:To><wsse:Security S:mustUnderstand=\"1\">"
      + "<wsu:Timestamp xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/"
      + "oasis-200401-wss-wssecurity-utility-1.0.xsd\" wsu:Id=\"TS\">"
      + "<wsu:Created>2014-03-27T20:56:38Z</wsu:Created><wsu:Expires>"
      + "2014-03-27T21:01:38Z</wsu:Expires></wsu:Timestamp></wsse:Security>"
      + "</S:Header><S:Body><wst:RequestSecurityTokenResponse "
      + "xmlns:S=\"http://www.w3.org/2003/05/soap-envelope\" "
      + "xmlns:wst=\"http://schemas.xmlsoap.org/ws/2005/02/trust\" "
      + "xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-"
      + "wss-wssecurity-secext-1.0.xsd\" "
      + "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-"
      + "wss-wssecurity-utility-1.0.xsd\" "
      + "xmlns:saml=\"urn:oasis:names:tc:SAML:1.0:assertion\" "
      + "xmlns:wsp=\"http://schemas.xmlsoap.org/ws/2004/09/policy\" "
      + "xmlns:psf=\"http://schemas.microsoft.com/Passport"
      + "/SoapServices/SOAPFault\"><wst:TokenType>urn:passport:compact"
      + "</wst:TokenType><wsp:AppliesTo "
      + "xmlns:wsa=\"http://www.w3.org/2005/08/addressing\">"
      + "<wsa:EndpointReference><wsa:Address>"
      + "https://sharepoint.example.com"
      + "</wsa:Address></wsa:EndpointReference>"
      + "</wsp:AppliesTo><wst:Lifetime><wsu:Created>2014-03-27T20:56:38Z"
      + "</wsu:Created><wsu:Expires>2014-03-28T20:56:38Z</wsu:Expires>"
      + "</wst:Lifetime><wst:RequestedSecurityToken>"
      + "<wsse:BinarySecurityToken Id=\"Compact0\">"
      + "t=This is live authentication token to extract"
      + "</wsse:BinarySecurityToken></wst:RequestedSecurityToken>"
      + "<wst:RequestedAttachedReference><wsse:SecurityTokenReference>"
      + "<wsse:Reference URI=\"euzZqFurd7rgUGVjTUnCah09kbA=\">"
      + "</wsse:Reference></wsse:SecurityTokenReference>"
      + "</wst:RequestedAttachedReference><wst:RequestedUnattachedReference>"
      + "<wsse:SecurityTokenReference><wsse:Reference "
      + "URI=\"euzZqFurd7rgUGVjTUnCah09kbA=\"></wsse:Reference>"
      + "</wsse:SecurityTokenReference></wst:RequestedUnattachedReference>"
      + "</wst:RequestSecurityTokenResponse></S:Body></S:Envelope>";
  
  @Rule
  public ExpectedException thrown = ExpectedException.none();  
  
  @Test
  public void testConstructor() {
    new LiveAuthenticationHandshakeManager.Builder(
        "http://sharepointurl", "username", "password")
        .build();    
  }
  
  @Test
  public void testNullUsername() {
    thrown.expect(NullPointerException.class);
    new LiveAuthenticationHandshakeManager.Builder(
        "http://endpoint", null, "password").build();
  }
  
  @Test
  public void testNullPassword() {
    thrown.expect(NullPointerException.class);
    new LiveAuthenticationHandshakeManager.Builder(
        "http://endpoint", "username", null).build();
  }
  
  @Test
  public void testNullSharePointUrl() {
    thrown.expect(NullPointerException.class);
    new LiveAuthenticationHandshakeManager.Builder(
        null, "username", "password").build();
  }
  
  @Test
  public void testExtractToken() throws IOException {
    LiveAuthenticationHandshakeManager manager 
        = new LiveAuthenticationHandshakeManager.Builder(
            "https://sharepoint.example.com", "username", "password").build();    
    
    assertEquals("t=This is live authentication token to extract",
        manager.extractToken(LIVE_AUTHENTICATION_RESPONSE));
  }  
  
  @Test
  public void testExtractTokenWithInvalidInput() throws IOException {
    LiveAuthenticationHandshakeManager manager 
        = new LiveAuthenticationHandshakeManager.Builder(
            "https://sharepoint.example.com", "username", "password").build();
    
    String tokenResponse = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
        + "<S:Envelope xmlns:S=\"http://www.w3.org/2003/05/soap-envelope\">"       
        + "<data>Something went wrong this is invalid</data>"
        + "</S:Envelope>";
    
    thrown.expect(IOException.class);
    String extractedToken = manager.extractToken(tokenResponse);   
  }
  
  @Test
  public void testExtractTokenWithNullInput() throws IOException {
    LiveAuthenticationHandshakeManager manager 
        = new LiveAuthenticationHandshakeManager.Builder(
            "https://sharepoint.example.com", "username", "password").build();
    thrown.expect(IOException.class);
    String extractedToken = manager.extractToken(null);   
  }
  
  @Test
  public void testAuthenticateInSamlHandlerWithLive() throws IOException{
    MockHttpPostClient postClient = new MockHttpPostClient();    
    LiveAuthenticationHandshakeManager manager 
        = new LiveAuthenticationHandshakeManager.Builder(
        "https://sharepoint.example.com", "username@domain", "password&123",
        postClient).build();
    URL tokenRequest = new URL(
        "https://login.microsoftonline.com/extSTS.srf");   
    postClient.responseMap.put(tokenRequest,
        new PostResponseInfo(LIVE_AUTHENTICATION_RESPONSE, null));    
    URL submitToken = new URL(
        "https://sharepoint.example.com/_forms/default.aspx?wa=wsignin1.0");    
    Map<String, List<String>> responseHeaders 
        = new HashMap<String, List<String>>();
    responseHeaders.put("some-header", Arrays.asList("some value"));
    responseHeaders.put("Set-Cookie",
        Arrays.asList("FedAuth=AutheCookie", "rfta=rftaValue"));
    
    postClient.responseMap.put(submitToken,
        new PostResponseInfo(null, responseHeaders));
    
    SamlAuthenticationHandler authenticationHandler 
        = new SamlAuthenticationHandler.Builder("username@domain",
            "password&123", new MockScheduledExecutor(), manager).build();
    AuthenticationResult result = authenticationHandler.authenticate();
    
    assertEquals("FedAuth=AutheCookie;rfta=rftaValue;", result.getCookie());
    assertEquals("NO_ERROR", result.getErrorCode());
    assertEquals(600, result.getCookieTimeOut());    
  }
  
  
  private static class MockHttpPostClient implements HttpPostClient {

    private Map<URL, SamlAuthenticationHandler.PostResponseInfo> responseMap;
    private Map<URL, String> receivedRequestBodyMap;
    public MockHttpPostClient() {
      responseMap = new HashMap<URL, PostResponseInfo>();
      receivedRequestBodyMap = new HashMap<URL, String>();      
    }
    
    @Override    
    public PostResponseInfo issuePostRequest(
        URL url, Map<String, String> connectionProperties, String requestBody)
        throws IOException {
      if (!responseMap.containsKey(url)) {
        throw new UnsupportedOperationException(
            "Unexpected Http Post for URL " + url);
      }
      // log incoming request body
      receivedRequestBodyMap.put(url, requestBody);
      return responseMap.get(url);
    }
  }  
}
