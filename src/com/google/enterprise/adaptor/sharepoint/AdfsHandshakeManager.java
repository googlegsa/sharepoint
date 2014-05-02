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

/**
 * SamlHandshakeManager implementation to support ADFS 2.0 
 * to request ADFS authentication token and extract authentication cookie.
 */
public class AdfsHandshakeManager implements SamlHandshakeManager {
  private static final Logger log
      = Logger.getLogger(AdfsHandshakeManager.class.getName());
  
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
  AdfsHandshakeManager(String sharePointUrl, String username,
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

  public static class Builder {
    private final String username;
    private final String password;
    private final String sharePointUrl;
    private final String stsendpoint;
    private final String stsrealm;
    private final HttpPostClient httpClient;
    private String login;
    private String trustLocation;    

    public Builder(String sharePointUrl, String username,
      String password, String stsendpoint, String stsrealm) {
      this(sharePointUrl, username, password, stsendpoint, stsrealm,
          new HttpPostClientImpl());
    }

    @VisibleForTesting
    Builder(String sharePointUrl, String username,
      String password, String stsendpoint, String stsrealm,
      HttpPostClient httpClient) {
      if (sharePointUrl == null || username == null || password == null
          || stsendpoint == null || httpClient == null || stsrealm == null) {
        throw new NullPointerException();
      }
      this.sharePointUrl = sharePointUrl;
      this.username = username;
      this.password = password;
      this.stsendpoint = stsendpoint;
      this.stsrealm = stsrealm;
      this.httpClient = httpClient;
      this.login = sharePointUrl + DEFAULT_LOGIN;
      this.trustLocation = sharePointUrl + DEFAULT_TRUST;
    }

    public Builder setLoginUrl(String login) {      
      this.login = login;
      return this;
    }

    public Builder setTrustLocation(String trustLocation) {      
      this.trustLocation = trustLocation;
      return this;
    }

    public AdfsHandshakeManager build() {
      if (Strings.isNullOrEmpty(trustLocation) 
          || Strings.isNullOrEmpty(login)) {
        throw new NullPointerException();
      }
      return new AdfsHandshakeManager(sharePointUrl, username,
          password, stsendpoint, stsrealm, login, trustLocation, httpClient);
    }
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
    String param = "wa=wsignin1.0"
        + "&wctx=" + URLEncoder.encode(login,"UTF-8")
        + "&wresult=" + URLEncoder.encode(token, "UTF-8");

    Map<String, String> requestHeaders = new HashMap<String, String>();
    requestHeaders.put("SOAPAction", stsendpoint);
    PostResponseInfo postResponse
        = httpClient.issuePostRequest(u, requestHeaders, param);
    String cookie = postResponse.getPostResponseHeaderField("Set-Cookie");
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
