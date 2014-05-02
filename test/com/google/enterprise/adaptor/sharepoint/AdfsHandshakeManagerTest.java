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
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;


public class AdfsHandshakeManagerTest {
  
  @Rule
  public ExpectedException thrown = ExpectedException.none();
  
  private static class UnsupportedHttpPostClient implements HttpPostClient {
    @Override
    public SamlAuthenticationHandler.PostResponseInfo issuePostRequest(
        URL url, Map<String, String> connectionProperties, String requestBody)
        throws IOException {
      throw new UnsupportedOperationException();
    }    
  }
  
  private static class MockHttpPostClient implements HttpPostClient {

    private Map<URL, PostResponseInfo> responseMap;
    private Map<URL, String> receivedRequestBodyMap;
    public MockHttpPostClient() {
      responseMap = new HashMap<URL, PostResponseInfo>();
      receivedRequestBodyMap = new HashMap<URL, String>();      
    }
    
    @Override
    public SamlAuthenticationHandler.PostResponseInfo issuePostRequest(
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

  @Test
  public void testConstructor() {
    new AdfsHandshakeManager.Builder(
        "http://endpoint", "username", "password", "https://sts", "realm")
        .build();    
  }

  @Test
  public void testNullUsername() {
    thrown.expect(NullPointerException.class);
    new AdfsHandshakeManager.Builder(
        "http://endpoint", null, "password", "https://sts", "realm").build();
  }

  @Test
  public void testNullPassword() {
    thrown.expect(NullPointerException.class);
    new AdfsHandshakeManager.Builder(
        "http://endpoint", "username", null, "https://sts", "realm").build();
  }

  @Test
  public void testNullEndpoint() {
    thrown.expect(NullPointerException.class);
    new AdfsHandshakeManager.Builder(
        null, "username", "password","https://sts", "realm").build();
  }

  @Test
  public void testNullSts() {
    thrown.expect(NullPointerException.class);
    new AdfsHandshakeManager.Builder(
        "http://endpoint", "username", "password", null, "realm").build();
  }

  @Test
  public void testNullRealm() {
    thrown.expect(NullPointerException.class);
    new AdfsHandshakeManager.Builder(
        "http://endpoint", "username", "password", "http://sts", null).build();
  }

  @Test
  public void testRequestToken() throws IOException{
    MockHttpPostClient postClient = new MockHttpPostClient();
    AdfsHandshakeManager manager = new AdfsHandshakeManager.Builder(
        "https://sharepoint.intranet.com", "username@domain", "pass]]>word&123", 
        "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed",
        "urn:realm:sharepoint", postClient).build();
    URL tokenRequest = new URL(
        "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed");
    String tokenResponse = "<s:Envelope "
        + "xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">"
        + "<s:Header>Some header</s:Header>"
        + "<t:RequestSecurityTokenResponse "
        + "xmlns:t=\"http://schemas.xmlsoap.org/ws/2005/02/trust\">"
        + "This is requested token"
        + "</t:RequestSecurityTokenResponse>"        
        + "</s:Envelope>";
    postClient.responseMap.put(tokenRequest,
        new PostResponseInfo(tokenResponse, null));    
    assertEquals("<t:RequestSecurityTokenResponse "
        + "xmlns:t=\"http://schemas.xmlsoap.org/ws/2005/02/trust\">"
        + "This is requested token"
        + "</t:RequestSecurityTokenResponse>", manager.requestToken());

    String expectedRequestBody 
        = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
        + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
        + "xmlns:a=\"http://www.w3.org/2005/08/addressing\" "
        + "xmlns:u=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-"
        + "wssecurity-utility-1.0.xsd\"><s:Header><a:Action "
        + "s:mustUnderstand=\"1\">"
        + "http://schemas.xmlsoap.org/ws/2005/02/trust/RST/Issue</a:Action>"
        + "<a:ReplyTo><a:Address>http://www.w3.org/2005/08/addressing/anonymous"
        + "</a:Address></a:ReplyTo><a:To s:mustUnderstand=\"1\"><![CDATA["
        + "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed]]>"
        + "</a:To><o:Security s:mustUnderstand=\"1\" "
        + "xmlns:o=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-"
        + "wssecurity-secext-1.0.xsd\"><o:UsernameToken>"
        + "<o:Username><![CDATA[username@domain]]></o:Username>"
        + "<o:Password><![CDATA[pass]]]]><![CDATA[>word&123]]>"
        + "</o:Password></o:UsernameToken></o:Security></s:Header>"
        + "<s:Body><t:RequestSecurityToken "
        + "xmlns:t=\"http://schemas.xmlsoap.org/ws/2005/02/trust\">"
        + "<wsp:AppliesTo "
        + "xmlns:wsp=\"http://schemas.xmlsoap.org/ws/2004/09/policy\">"
        + "<a:EndpointReference><a:Address>"
        + "<![CDATA[urn:realm:sharepoint]]></a:Address>"
        + "</a:EndpointReference></wsp:AppliesTo><t:KeyType>"
        + "http://schemas.xmlsoap.org/ws/2005/05/identity/NoProofKey"
        + "</t:KeyType><t:RequestType>"
        + "http://schemas.xmlsoap.org/ws/2005/02/trust/Issue"
        + "</t:RequestType><t:TokenType>urn:oasis:names:tc:SAML:1.0:assertion"
        + "</t:TokenType></t:RequestSecurityToken></s:Body></s:Envelope>";

    assertEquals(expectedRequestBody,
        postClient.receivedRequestBodyMap.get(tokenRequest));
    
  }

  @Test
  public void testNullRequestToken() throws IOException{    
    MockHttpPostClient postClient = new MockHttpPostClient();    
    AdfsHandshakeManager manager = new AdfsHandshakeManager.Builder(
        "https://sharepoint.intranet.com", "username@domain", "password&123", 
        "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed",
        "urn:realm:sharepoint", postClient).build();
    URL tokenRequest = new URL(
        "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed");
    postClient.responseMap.put(tokenRequest,
        new PostResponseInfo("<data>some invalid content</data>", null));
    thrown.expect(IOException.class);
    String token = manager.requestToken();    
  }

  @Test
  public void testGetAuthenticationCookie() throws IOException{
    MockHttpPostClient postClient = new MockHttpPostClient();    
    AdfsHandshakeManager manager = new AdfsHandshakeManager.Builder(
        "https://sharepoint.intranet.com", "username@domain", "password&123", 
        "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed",
        "urn:realm:sharepoint", postClient).build();   

    URL submitToken = new URL("https://sharepoint.intranet.com/_trust");

    Map<String, List<String>> responseHeaders 
        = new HashMap<String, List<String>>();
    responseHeaders.put("some-header", Arrays.asList("some value"));
    responseHeaders.put("Set-Cookie", Arrays.asList("FedAuth=AutheCookie"));

    postClient.responseMap.put(submitToken,
        new PostResponseInfo("submit token response", responseHeaders));
    String cookie = manager.getAuthenticationCookie(
        "<t:RequestSecurityTokenResponse "
        + "xmlns:t=\"http://schemas.xmlsoap.org/ws/2005/02/trust\">"
        + "This is requested token"
        + "</t:RequestSecurityTokenResponse>");

    assertEquals("FedAuth=AutheCookie;", cookie);

    String expectedSubmitTokenRequest = "wa=wsignin1.0&wctx=" 
      + URLEncoder.encode("https://sharepoint.intranet.com/_layouts/"
          + "Authenticate.aspx","UTF-8")
      + "&wresult=" + URLEncoder.encode("<t:RequestSecurityTokenResponse "
          + "xmlns:t=\"http://schemas.xmlsoap.org/ws/2005/02/trust\">"
          + "This is requested token"
          + "</t:RequestSecurityTokenResponse>", "UTF-8");  
    assertEquals(expectedSubmitTokenRequest,
        postClient.receivedRequestBodyMap.get(submitToken));
  }

  @Test
  public void testAuthenticateInSamlHandlerWithADFS() throws IOException{
    MockHttpPostClient postClient = new MockHttpPostClient();
    String username = "username@domain";
    String password = "password&123";
    AdfsHandshakeManager manager = new AdfsHandshakeManager.Builder(
        "https://sharepoint.intranet.com", username, password, 
        "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed",
        "urn:realm:sharepoint", postClient).build();
    URL tokenRequest = new URL(
        "https://sts.dmain.com/adfs/services/trust/2005/usernamemixed");
    String tokenResponse = "<s:Envelope "
        + "xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">"
        + "<s:Header>Some header</s:Header>"
        + "<t:RequestSecurityTokenResponse "
        + "xmlns:t=\"http://schemas.xmlsoap.org/ws/2005/02/trust\">"
        + "This is requested token"
        + "</t:RequestSecurityTokenResponse>"        
        + "</s:Envelope>";
    postClient.responseMap.put(tokenRequest,
        new PostResponseInfo(tokenResponse, null));    
    URL submitToken = new URL("https://sharepoint.intranet.com/_trust");    
    Map<String, List<String>> responseHeaders 
        = new HashMap<String, List<String>>();
    responseHeaders.put("some-header", Arrays.asList("some value"));
    responseHeaders.put("Set-Cookie", Arrays.asList("FedAuth=AutheCookie"));

    postClient.responseMap.put(submitToken,
        new PostResponseInfo(null, responseHeaders));

    SamlAuthenticationHandler authenticationHandler 
        = new SamlAuthenticationHandler.Builder(username, password,
            new MockScheduledExecutor(), manager).build();
    AuthenticationResult result = authenticationHandler.authenticate();

    assertEquals("FedAuth=AutheCookie;", result.getCookie());
    assertEquals("NO_ERROR", result.getErrorCode());
    assertEquals(600, result.getCookieTimeOut());    
  }

  @Test
  public void testEscapeCdata() {

    AdfsHandshakeManager manager = new AdfsHandshakeManager.Builder(
        "http://endpoint", "username", "password", "https://sts", "realm")
        .build();

     assertEquals("<![CDATA[This is simple]]>",
         manager.escapeCdata("This is simple"));

     assertEquals(
         "<![CDATA[This is simple]]]]><![CDATA[>with additional text]]>",
         manager.escapeCdata("This is simple]]>with additional text"));

     assertEquals(
         "<![CDATA[This is > & simple]]]]><![CDATA[>]]>",
         manager.escapeCdata("This is > & simple]]>"));

     assertEquals(
         "<![CDATA[]]]]><![CDATA[>]]>",
         manager.escapeCdata("]]>"));

     assertEquals("<![CDATA[<![CDATA[This is simple]]]]><![CDATA[>]]>",
         manager.escapeCdata("<![CDATA[This is simple]]>"));    

     assertEquals("<![CDATA[This is simple]]]]>"
         + "<![CDATA[>with multiple]]]]><![CDATA[>]]>",
         manager.escapeCdata("This is simple]]>with multiple]]>"));
  }
}
