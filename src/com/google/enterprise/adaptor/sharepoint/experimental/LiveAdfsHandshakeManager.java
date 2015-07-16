// Copyright 2015 Google Inc. All Rights Reserved.
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


package com.google.enterprise.adaptor.sharepoint.experimental;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.HttpPostClient;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.HttpPostClientImpl;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.PostResponseInfo;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.SamlHandshakeManager;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
// TODO: Move this class under examples directory once this implementation 
// is finalized and validated in field.
/**
 * SamlHandshakeManager implementation to support ADFS 2.0 + 
 * Live authentication to request ADFS authentication token and 
 * extract authentication cookie from Live authentication.
 */
public class LiveAdfsHandshakeManager implements SamlHandshakeManager {
  private static final Logger log
      = Logger.getLogger(LiveAdfsHandshakeManager.class.getName());
  
  private static final String DEFAULT_LOGIN = "/_layouts/Authenticate.aspx";
  private static final String DEFAULT_TRUST = "/_trust";
  
  protected final String login;
  protected final String username;
  protected final String password;
  protected final String sharePointUrl;
  protected final String stsendpoint;
  protected final String stsrealm;
  protected final HttpPostClient httpClient;
  protected final String trustLocation;
  private static final String reqXML
      = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
      + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
      + "xmlns:a=\"http://www.w3.org/2005/08/addressing\" "
      + "xmlns:u=\"http://docs.oasis-open.org/wss/2004/01/"
      + "oasis-200401-wss-wssecurity-utility-1.0.xsd\"><s:Header>"
      + "<a:Action s:mustUnderstand=\"1\">"
      + "http://schemas.xmlsoap.org/ws/2005/02/trust/RST/Issue</a:Action>"
      + "<a:ReplyTo><a:Address>"
      + "http://www.w3.org/2005/08/addressing/anonymous</a:Address>"
      + "</a:ReplyTo><a:To s:mustUnderstand=\"1\">"
      + "%s</a:To>" // stsendpont
      + "<o:Security s:mustUnderstand=\"1\" "
      + "xmlns:o=\"http://docs.oasis-open.org/wss/2004/01/"
      + "oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
      + "<o:UsernameToken><o:Username>%s</o:Username>" //username
      + "<o:Password>%s</o:Password></o:UsernameToken>" //password
      + "</o:Security></s:Header><s:Body>"
      + "<t:RequestSecurityToken "
      + "xmlns:t=\"http://schemas.xmlsoap.org/ws/2005/02/trust\">"
      + "<wsp:AppliesTo xmlns:wsp=\""
      + "http://schemas.xmlsoap.org/ws/2004/09/policy\">"
      + "<a:EndpointReference><a:Address>%s</a:Address>" //stsrealm
      + "</a:EndpointReference></wsp:AppliesTo>"
      + "<t:KeyType>http://schemas.xmlsoap.org/ws/2005/05/identity/NoProofKey"
      + "</t:KeyType>"
      + "<t:RequestType>http://schemas.xmlsoap.org/ws/2005/02/trust/Issue"
      + "</t:RequestType>"
      + "<t:TokenType>urn:oasis:names:tc:SAML:1.0:assertion</t:TokenType>"
      + "</t:RequestSecurityToken></s:Body></s:Envelope>";

  @VisibleForTesting
  LiveAdfsHandshakeManager(String sharePointUrl, String username,
      String password, String stsendpoint, String stsrealm, String login,
      String trustLocation, HttpPostClient httpClient) {
    this.sharePointUrl = sharePointUrl;
    this.username = username;
    this.password = password;
    this.stsendpoint = stsendpoint;
    this.stsrealm = stsrealm;
    this.login = login;
    this.trustLocation = trustLocation;
    this.httpClient = httpClient;
  }

  public static LiveAdfsHandshakeManager getInstance(
      Map<String, String> config) {
    String username = config.get("sharepoint.username");    
    String password = config.get("sharepoint.password");
    String stsendpoint = config.get("sharepoint.sts.endpoint");
    String stsrealm = config.get("sharepoint.sts.realm");
    String sharePointUrl = config.get("sharepoint.server");
    String login = config.containsKey("sharepoint.sts.login") 
        ? config.get("sharepoint.sts.login") : sharePointUrl + DEFAULT_LOGIN;
    String trustLocation = config.containsKey("sharepoint.sts.trustLocation")
        ? config.get("sharepoint.sts.trustLocation")
            : sharePointUrl + DEFAULT_TRUST;
    return new LiveAdfsHandshakeManager(sharePointUrl, username, password,
        stsendpoint, stsrealm, login, trustLocation, new HttpPostClientImpl());
  }
  @Override
  public String requestToken() throws IOException {
    String saml = generateSamlRequest();    
    URL u = new URL(stsendpoint);
    Map<String, String> requestHeaders = new HashMap<String, String>();
    requestHeaders.put("SOAPAction", stsendpoint);
    requestHeaders.put("Content-Type",
        "application/soap+xml; charset=utf-8");
    PostResponseInfo postResponse
        = httpClient.issuePostRequest(u, requestHeaders, saml);
    String result = postResponse.getPostContents();
    return extractToken(result);
  }

  @Override
  public String getAuthenticationCookie(String token) throws IOException {    
    URL u = new URL(trustLocation);
    String param = "wctx=MEST=0&LoginOptions=2&wa=wsignin1.0&wp=MBI"
        + "&wreply=" + URLEncoder.encode(login,"UTF-8")
        + "&wresult=" + URLEncoder.encode(token, "UTF-8");
    log.log(Level.FINER, "Step 1A: Making HTTP request @ {0} with data {1}",
        new Object[] {trustLocation, param});

    Map<String, String> requestHeaders = new HashMap<String, String>();
    requestHeaders.put("SOAPAction", stsendpoint);
    PostResponseInfo postResponse
        = httpClient.issuePostRequest(u, requestHeaders, param);
    String location = postResponse.getPostResponseHeaderField("Location");
    log.log(Level.FINER, "Step 1B: Extracted redirect location {0}", location);
    String loginCookie = postResponse.getPostResponseHeaderField("Set-Cookie");
    URL defaultUrl = new URL(login);
    requestHeaders.clear();
    requestHeaders.put("Cookie", loginCookie);
    String data = location.substring(location.indexOf("t="));
    log.log(Level.FINER, "Step 2A: Making HTTP request @ {0} with data {1}",
        new Object[] {login, data});
    postResponse = httpClient.issuePostRequest(
        defaultUrl, requestHeaders, data);
    location = postResponse.getPostResponseHeaderField("Location");
    log.log(Level.FINER, "Step 2B: Extracted redirect location {0}", location);
    log.log(Level.FINER, "Step 3A: Making HTTP request @ {0}", location);        
    postResponse = httpClient.issuePostRequest(
        new URL(location), requestHeaders, "");    
    String loginToken = postResponse.getPostContents().substring(
        postResponse.getPostContents().indexOf("value=\"")+ 7,
        postResponse.getPostContents().lastIndexOf("\""));
    log.log(Level.FINER, "Step 3B: Extracted login token {0}", loginToken);
    log.log(Level.FINER, "Step 4A: Making HTTP request @ {0} with data {1}",
        new Object[] {defaultUrl, "t="+loginToken});
    postResponse = httpClient.issuePostRequest(
        defaultUrl, requestHeaders, "t="+loginToken);
    String cookie = postResponse.getPostResponseHeaderField("Set-Cookie");
    log.log(Level.FINER, "Step 4B: Extracted login cookie {0}", cookie);
    return cookie;
  }

  private String generateSamlRequest() {
    return String.format(reqXML, escapeCdata(stsendpoint),
        escapeCdata(username), escapeCdata(password), escapeCdata(stsrealm));   
  }

  @VisibleForTesting
  String extractToken(String tokenResponse) throws IOException {
    if (tokenResponse == null) {
      throw new IOException("tokenResponse is null");
    }
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      DocumentBuilder db = dbf.newDocumentBuilder();
      Document document 
          = db.parse(new InputSource(new StringReader(tokenResponse)));
      NodeList nodes
          = document.getElementsByTagNameNS(
              "http://schemas.xmlsoap.org/ws/2005/02/trust",
              "RequestSecurityTokenResponse");
      if (nodes.getLength() == 0) {
        log.log(Level.WARNING,
            "ADFS token not available in response {0}", tokenResponse);
        throw new IOException("ADFS token not available in response");
      }
      Node responseToken = nodes.item(0);
      String token = getOuterXml(responseToken);
      log.log(Level.FINER, "ADFS Authentication Token {0}", token);
      return token;
    } catch (ParserConfigurationException ex) {
      throw new IOException("Error parsing result", ex);      
    } catch (SAXException ex) {
      throw new IOException("Error parsing result", ex);
    }
  }

  private String getOuterXml(Node node) throws IOException {
    try {
      Transformer transformer 
          = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty("omit-xml-declaration", "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(node), new StreamResult(writer));
      return writer.toString();
    } catch (TransformerConfigurationException ex) {
      throw new IOException(ex);
    } catch (TransformerException ex) {
      throw new IOException(ex);
    }
  }
  
  @VisibleForTesting
  String escapeCdata(String input) {
    if (Strings.isNullOrEmpty(input)) {
      return "";
    }  
    return "<![CDATA[" + input.replace("]]>", "]]]]><![CDATA[>") + "]]>";
  }
}
