// Copyright 2013 Google Inc. All Rights Reserved.
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
import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.PollingIncrementalLister;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.SamlHandshakeManager;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;

import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.ArrayOfUserProfileChangeData;
import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.UserProfileChangeData;
import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.UserProfileChangeDataContainer;
import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.UserProfileChangeQuery;
import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.UserProfileChangeServiceSoap;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ArrayOfContactData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ArrayOfPropertyData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ContactData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.GetUserProfileByIndexResult;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.Privacy;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.PropertyData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.UserProfileServiceSoap;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ValueData;

import org.w3c.dom.DOMException;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.EndpointReference;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.wsaddressing.W3CEndpointReferenceBuilder;

/**
 * An adaptor for obtaining user profile information from SharePoint.
 * @author tvartak
 *
 */
public class SharePointUserProfileAdaptor extends AbstractAdaptor
    implements PollingIncrementalLister {
  private static final Map<String, String> SP_GSA_PROPERTY_MAPPINGS;

  private static final Charset encoding = Charset.forName("UTF-8");
  
  /** SharePoint's namespace. */
  private static final String AUTH_XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";

  private static final String XMLNS =
      "http://microsoft.com/webservices/SharePointPortalServer/UserProfileService";
  private static final String XMLNS_CHANGE =
      "http://microsoft.com/webservices/SharePointPortalServer/UserProfileChangeService";
  public static final String PROFILE_ACCOUNTNAME_PROPERTY = "AccountName";

  private static final String USER_PROFILE_SERVICE_ENDPOINT =
      "/_vti_bin/UserProfileService.asmx";
  private static final String USER_PROFILE_CHANGE_SERVICE_ENDPOINT =
      "/_vti_bin/UserProfileChangeService.asmx";
  // Social ID prefix required for Expert Search
  public static final String SOCIAL_ID_PREFIX = "social:expert:";

  public static final String CONTACT_ELEMENT = "gsa:contact";
  public static final String CONTACTS_ROOT_ELEMENT = "gsa:Contacts";
  public static final String GSA_NAMESPACE 
      = "http://www.google.com/schemas/gsa";
  public static final String PROFILE_PREFERRED_NAME_PROPERTY = "PreferredName";

  public static final String GSA_PROPNAME_COLLEAGUES =
      "google_social_user_colleagues";

  // Mapping for SharePoint user profile properties to
  // GSA Expert Search properties
  static {
    Map<String, String> map = new HashMap<String, String>();
    map.put("SPS-Skills", "google_social_user_skills");
    map.put("SPS-PastProjects", "google_social_user_pastprojects");
    map.put(PROFILE_ACCOUNTNAME_PROPERTY, "google_social_user_accountname");
    map.put(PROFILE_PREFERRED_NAME_PROPERTY,
        "google_social_user_preferredname");
    SP_GSA_PROPERTY_MAPPINGS = Collections.unmodifiableMap(map);
  }

  private static final Logger log =
      Logger.getLogger(SharePointUserProfileAdaptor.class.getName());

  private String virtualServer;
  private String mySiteHost;
  private NtlmAuthenticator ntlmAuthenticator;
  private final UserProfileServiceFactory userProfileServiceFactory;
  private final AuthenticationClientFactory authenticationClientFactory;

  private String userProfileChangeToken;
  private boolean setAcl = true;
  private String namespace;
  private UserProfileServiceClient userProfileServiceClient;
  private ScheduledThreadPoolExecutor scheduledExecutor 
      = new ScheduledThreadPoolExecutor(1);

  private FormsAuthenticationHandler authenticationHandler;

  public static void main(String[] args) {
    AbstractAdaptor.main(new SharePointUserProfileAdaptor(), args);
  }

  public SharePointUserProfileAdaptor() {
    this(new UserProfileServiceFactoryImpl(),
        new AuthenticationClientFactoryImpl());
  }

  @VisibleForTesting
  SharePointUserProfileAdaptor(
      UserProfileServiceFactory userProfileServiceFactory,
      AuthenticationClientFactory authenticationClientFactory) {
    if (userProfileServiceFactory == null 
        || authenticationClientFactory == null) {
      throw new NullPointerException();
    }
    this.userProfileServiceFactory = userProfileServiceFactory;
    this.authenticationClientFactory = authenticationClientFactory;
  }

  @VisibleForTesting
  void setUserProfileChangeToken (String changeToken) {
    userProfileChangeToken = changeToken;
  }

  @VisibleForTesting
  String getUserProfileChangeToken() {
    return userProfileChangeToken;
  }


  @Override
  public void initConfig(Config config) {
    config.addKey("sharepoint.server", null);
    boolean onWindows = System.getProperty("os.name").contains("Windows");
    // When running on Windows, Windows Authentication can log us in.
    config.addKey("sharepoint.username", onWindows ? "" : null);
    config.addKey("sharepoint.password", onWindows ? "" : null);
    config.addKey("profile.setacl", "true");
    config.addKey("adaptor.namespace", "Default");
    config.addKey("profile.mysitehost", "");
    // When running against ADFS authentication, set this to ADFS endpoint.
    config.addKey("sharepoint.sts.endpoint", "");
    // When running against ADFS authentication, set this to realm value.
    // Normally realm value is either http://sharepointurl/_trust or
    // urn:sharepointenv:com format. You can use 
    // Get-SPTrustedIdentityTokenIssuer to get "DefaultProviderRealm" value
    config.addKey("sharepoint.sts.realm", "");
    // You can override default value of http://sharepointurl/_trust by 
    // specifying this property.
    config.addKey("sharepoint.sts.trustLocation", "");
    // You can override default value of 
    // http://sharepointurl/_layouts/Authenticate.aspx by specifying this 
    // property.
    config.addKey("sharepoint.sts.login", "");
    // Set this to true when using Live authentication.
    config.addKey("sharepoint.useLiveAuthentication", "false");
  }

  @Override
  public void init(AdaptorContext context) throws IOException {
    context.setPollingIncrementalLister(this);
    Config config = context.getConfig();

    virtualServer = config.getValue("sharepoint.server");
    if (virtualServer.endsWith("/")) {
      virtualServer = virtualServer.substring(0, virtualServer.length() - 1);
    }
    String username = config.getValue("sharepoint.username");
    String password = context.getSensitiveValueDecoder().decodeValue(
        config.getValue("sharepoint.password"));
    setAcl = Boolean.parseBoolean(config.getValue("profile.setacl"));
    namespace = config.getValue("adaptor.namespace");
    String stsendpoint = config.getValue("sharepoint.sts.endpoint");
    String stsrealm = config.getValue("sharepoint.sts.realm");
    boolean useLiveAuthentication = Boolean.parseBoolean(
        config.getValue("sharepoint.useLiveAuthentication"));

    log.log(Level.CONFIG, "virtualServer: {0}", virtualServer);
    log.log(Level.CONFIG, "Username: {0}", username);
    log.log(Level.CONFIG, "setAcl: {0}", setAcl);
    log.log(Level.CONFIG, "Namespace: {0}", namespace);
    log.log(Level.CONFIG, "STS Endpoint: {0}", stsendpoint);
    log.log(Level.CONFIG, "STS Realm: {0}", stsrealm);
    log.log(Level.CONFIG, "Use Live Authentication: {0}",
        useLiveAuthentication);
    
    mySiteHost = config.getValue("profile.mysitehost");
    log.log(Level.CONFIG, "mySiteHost: {0}", mySiteHost);
    if (mySiteHost.isEmpty()) {
      log.log(Level.WARNING, "My site host is not specified."
          + " Using virtual server url as My site host.");
      mySiteHost = virtualServer;
    }
    
    if (mySiteHost.endsWith("/")) {
      mySiteHost = mySiteHost.substring(0, mySiteHost.length() - 1);
    }

    ntlmAuthenticator = new NtlmAuthenticator(username, password);
    // Unfortunately, this is a JVM-wide modification.
    Authenticator.setDefault(ntlmAuthenticator);
     
    if (useLiveAuthentication)  {
      SamlHandshakeManager manager = authenticationClientFactory
          .newLiveAuthentication(virtualServer, username, password);
      authenticationHandler = new SamlAuthenticationHandler.Builder(username,
          password, scheduledExecutor, manager).build();     
    } else if (!"".equals(stsendpoint) && !"".equals(stsrealm)) {
      SamlHandshakeManager manager = authenticationClientFactory
          .newAdfsAuthentication(virtualServer, username, password, stsendpoint,
              stsrealm, config.getValue("sharepoint.sts.login"),
              config.getValue("sharepoint.sts.trustLocation"));
      authenticationHandler = new SamlAuthenticationHandler.Builder(username,
          password, scheduledExecutor, manager).build();            
    } else {    
      AuthenticationSoap authenticationSoap = authenticationClientFactory
          .newSharePointFormsAuthentication(virtualServer, username, password);
      authenticationHandler = new SharePointFormsAuthenticationHandler
          .Builder(username, password, scheduledExecutor, authenticationSoap)
          .build();
    }
    authenticationHandler.start();
    log.log(Level.FINEST, "Initializing User profile Service Client for {0}",
        virtualServer + USER_PROFILE_SERVICE_ENDPOINT);
    userProfileServiceClient = new UserProfileServiceClient(
        userProfileServiceFactory.newUserProfileService(
            virtualServer + USER_PROFILE_SERVICE_ENDPOINT,
            virtualServer + USER_PROFILE_CHANGE_SERVICE_ENDPOINT,
            authenticationHandler.getAuthenticationCookies()));
    userProfileChangeToken =
        userProfileServiceClient.userProfileServiceWS.getCurrentChangeToken();
  }

  @Override
  public void destroy() {
    Authenticator.setDefault(null);
    scheduledExecutor.shutdown();
    try {     
      scheduledExecutor.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    scheduledExecutor.shutdownNow();
  }

  @Override
  public void getDocContent(
      Request request, Response response) throws IOException {
    userProfileServiceClient.getDocContent(request, response);
  }

  @Override
  public void getDocIds(DocIdPusher pusher) throws IOException,
      InterruptedException {
    userProfileServiceClient.getDocIds(pusher);
  }

  @Override
  public void getModifiedDocIds(DocIdPusher pusher)
      throws InterruptedException, IOException {
    userProfileChangeToken = userProfileServiceClient.getModifiedDocIds(pusher,
        userProfileChangeToken);
    log.log(Level.FINE, "getModifiedDocIds returned change token: {0}",
        userProfileChangeToken);
  }

  private static class NtlmAuthenticator extends Authenticator {
    private final String username;
    private final char[] password;

    public NtlmAuthenticator(String username, String password) {
      this.username = username;
      this.password = password.toCharArray();
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
      return new PasswordAuthentication(username, password);
    }

  }

  @VisibleForTesting
  interface UserProfileServiceFactory {
    public UserProfileServiceWS newUserProfileService(String endpoint,
        String endpointChangeService, List<String> cookies);
  }

  private static class UserProfileServiceFactoryImpl
      implements UserProfileServiceFactory {
    private final Service userProfileServiceSoap;
    private final Service userProfileChangeServiceSoap;

    public UserProfileServiceFactoryImpl() {
      URL urlUserProfileService =
          UserProfileServiceSoap.class.getResource("UserProfileService.wsdl");
      QName qname = new QName(XMLNS, "UserProfileService");
      this.userProfileServiceSoap = Service.create(
          urlUserProfileService, qname);
      URL urlUserProfileChangeService =
          UserProfileChangeServiceSoap.class.getResource(
              "UserProfileChangeService.wsdl");
      QName qnameChange = new QName(XMLNS_CHANGE, "UserProfileChangeService");
      this.userProfileChangeServiceSoap = Service.create(
          urlUserProfileChangeService, qnameChange);
    }

    @Override
    public UserProfileServiceWS newUserProfileService(String endpoint,
        String endpointChangeService, List<String> cookies) {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(endpoint).build();
      EndpointReference endpointChangeRef = new W3CEndpointReferenceBuilder()
          .address(endpointChangeService).build();
      UserProfileServiceSoap inUserProfileServiceSoap 
          = userProfileServiceSoap.getPort(
              endpointRef, UserProfileServiceSoap.class);       
      UserProfileChangeServiceSoap inUserProfileChangeServiceSoap 
          = userProfileChangeServiceSoap.getPort(
              endpointChangeRef, UserProfileChangeServiceSoap.class);
      // JAX-WS RT 2.1.4 doesn't handle headers correctly and always assumes the
      // list contains precisely one entry, so we work around it here.
      if (!cookies.isEmpty()) {
        addFormsAuthenticationCookies(
            (BindingProvider) inUserProfileServiceSoap, cookies);
        addFormsAuthenticationCookies(
            (BindingProvider) inUserProfileChangeServiceSoap, cookies);
      }
      return new SharePointUserProfileServiceWS(inUserProfileServiceSoap,
          inUserProfileChangeServiceSoap);
    }
    
    private void addFormsAuthenticationCookies(BindingProvider port, 
        List<String> cookies) {
      if (cookies.isEmpty()) {
        disableFormsAuthentication(port);
      }
      port.getRequestContext().put(MessageContext.HTTP_REQUEST_HEADERS,
          Collections.singletonMap("Cookie", cookies));
    }
    
    private void disableFormsAuthentication(BindingProvider port) {
      port.getRequestContext().put(MessageContext.HTTP_REQUEST_HEADERS, 
          Collections.singletonMap(
            "X-FORMS_BASED_AUTH_ACCEPTED", Collections.singletonList("f")));
    }
  }

  @VisibleForTesting
  static interface UserProfileServiceWS {

    public GetUserProfileByIndexResult getUserProfileByIndex(int index)
        throws WebServiceException;

    public ArrayOfPropertyData getUserProfileByName(String userName)
        throws WebServiceException;

    public ArrayOfContactData getUserColleagues(String key)
        throws WebServiceException;

    public String getCurrentChangeToken() throws WebServiceException;

    public UserProfileChangeDataContainer getUserProfileChanges(
        String lastChangeToken,  UserProfileChangeQuery changeQuery)
            throws WebServiceException;
  }

  // SharePoint implementation for User Profile Service
  // and User Profile Change Service
  private static class SharePointUserProfileServiceWS
      implements UserProfileServiceWS {
    private final UserProfileServiceSoap userProfileServiceSoap;
    private final UserProfileChangeServiceSoap userProfileChangeServiceSoap;

    public SharePointUserProfileServiceWS(
        UserProfileServiceSoap userProfileServiceSoap,
        UserProfileChangeServiceSoap userProfileChangeServiceSoap) {
      this.userProfileServiceSoap = LoggingWSHandler.create(
          UserProfileServiceSoap.class, userProfileServiceSoap);
      this.userProfileChangeServiceSoap = LoggingWSHandler.create(
          UserProfileChangeServiceSoap.class, userProfileChangeServiceSoap);
    }


    @Override
    public GetUserProfileByIndexResult getUserProfileByIndex(int index)
        throws WebServiceException {
      return userProfileServiceSoap.getUserProfileByIndex(index);
    }

    @Override
    public ArrayOfContactData getUserColleagues(String key)
        throws WebServiceException {
      return userProfileServiceSoap.getUserColleagues(key);
    }

    @Override
    public ArrayOfPropertyData getUserProfileByName(String userName)
        throws WebServiceException {
      return userProfileServiceSoap.getUserProfileByName(userName);
    }

    @Override
    public String getCurrentChangeToken() throws WebServiceException {
      try {
        return userProfileChangeServiceSoap.getCurrentChangeToken();
      } catch (Exception ex) {
        log.log(Level.WARNING,
            "Error fetching change token from SharePoint. Returning null.", ex);
        return null;
      }
    }

    @Override
    public UserProfileChangeDataContainer getUserProfileChanges(
        String lastChangeToken,  UserProfileChangeQuery changeQuery) {
      return userProfileChangeServiceSoap.getChanges(
          lastChangeToken, changeQuery);
    }
  }

  @VisibleForTesting
  class UserProfileServiceClient {

    private final UserProfileServiceWS userProfileServiceWS;
    private DOMImplementation domImpl;
    private DOMImplementationLS ls;

    public UserProfileServiceClient(
        UserProfileServiceWS userProfileServiceWS) {
      this.userProfileServiceWS = userProfileServiceWS;
      try {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        this.domImpl = db.getDOMImplementation();
        ls = (DOMImplementationLS) domImpl;
      } catch (ParserConfigurationException pce) {
        log.log(Level.WARNING,
            "Colleagues information will be missing as " +
            "Parser Configuration Exception creating DOMImplementation", pce);
      }
    }

    public void getDocIds(DocIdPusher pusher)
        throws RemoteException, InterruptedException {
      int index = -1;
      List<DocId> profilesToPush = new ArrayList<DocId>();
      do {
        GetUserProfileByIndexResult nextProfile;
        try {
          nextProfile = userProfileServiceWS.getUserProfileByIndex(index);
        } catch (WebServiceException e) {
          log.log(Level.WARNING,
              "Error fetching user profile at index {0}", index);
          log.log(Level.WARNING,
              "Exception for getUserProfileByIndex : ", e);
          // Flushing available docids
          pusher.pushDocIds(profilesToPush);
          throw e;
        }
        if (nextProfile == null) {
          break;
        }
        index = Integer.parseInt(nextProfile.getNextValue());
        log.log(Level.FINEST, "Next Index is {0}", index);
        ArrayOfPropertyData profileProperties = nextProfile.getUserProfile();
        String userAccountName = getUserProfilePropertySingleValue(
            profileProperties, PROFILE_ACCOUNTNAME_PROPERTY);
        if (!Strings.isNullOrEmpty(userAccountName)) {
          profilesToPush.add(new DocId(SOCIAL_ID_PREFIX + userAccountName));
          log.log(Level.FINEST, "Adding Doc ID {0}",
              SOCIAL_ID_PREFIX + userAccountName);
        }
        if (profilesToPush.size() == 500) {
          pusher.pushDocIds(profilesToPush);
          profilesToPush.clear();
        }
      } while (index != -1); // For last profile next value will be -1
      pusher.pushDocIds(profilesToPush);
    }

    public void getDocContent(
        Request request, Response response) throws IOException {
      DocId id = request.getDocId();
      String uniqueId = id.getUniqueId();
      if (!uniqueId.startsWith(SOCIAL_ID_PREFIX)) {
        log.log(Level.WARNING, "Invalid DocID {0}", uniqueId);
        response.respondNotFound();
        return;
      }

      String userName = uniqueId.substring(SOCIAL_ID_PREFIX.length());
      log.log(Level.FINEST, "Fetching user profile for {0}", userName);
      ArrayOfPropertyData userProfileProperties = null;
      try {
        userProfileProperties =
            userProfileServiceWS.getUserProfileByName(userName);
      } catch (WebServiceException e) {
        log.log(Level.WARNING,
            "Error getting User profile {0}", e.getMessage());
        // SharePoint 2010 : could not be found
        // MOSS 2007 : User Not Found:
        if (e.getMessage() == null ||
            (!e.getMessage().contains("could not be found") &&
            !e.getMessage().contains("User Not Found:"))) {
          log.log(Level.WARNING,
              "Error getting User profile for {0}", userName);
          throw new IOException(e);
        }
      }
      if (userProfileProperties == null) {
        log.log(Level.WARNING, "User profile not available for {0}",
            userName);
        response.respondNotFound();
        return;
      }

      List<PropertyData> properties = userProfileProperties.getPropertyData();
      for (PropertyData prop : properties) {
        String propertyName = getGSAPropertyMapping(prop.getName());
        if (prop.getPrivacy() != Privacy.PUBLIC) {
          log.log(Level.FINE, "Excluding non public property {0}",
              propertyName);
          continue;
        }
        // Skipping UserProfile_GUID field.
        if ("UserProfile_GUID".equals(propertyName)) {
          continue;
        }
        List<String> values = readUserProfilePropertyValues(prop);
        for (String v : values) {
          response.addMetadata(propertyName, v);
        }
      }
      if (setAcl) {
        List<GroupPrincipal> permitGroups = new ArrayList<GroupPrincipal>();
        permitGroups.add(
            new GroupPrincipal("NT AUTHORITY\\Authenticated Users", namespace));
        response.setAcl(new Acl.Builder().setEverythingCaseInsensitive()
            .setInheritanceType(Acl.InheritanceType.LEAF_NODE)
            .setPermitGroups(permitGroups).build());
      }

      // domImpl is required for Colleagues data processing
      if (this.domImpl != null) {
        ArrayOfContactData colleagues =
            userProfileServiceWS.getUserColleagues(userName);
        String colleaguesXml = serializeColleagues(colleagues);
        if (colleaguesXml != null) {
          response.addMetadata(GSA_PROPNAME_COLLEAGUES, colleaguesXml);
        }
      }

      String displayUrl = mySiteHost + "/person.aspx?accountname=" 
          + URLEncoder.encode(userName, "UTF-8");
      response.setDisplayUrl(URI.create(displayUrl));
      
      String userProfileTitle = getUserProfilePropertySingleValue(
          userProfileProperties, PROFILE_PREFERRED_NAME_PROPERTY);
      if (userProfileTitle == null) {
        userProfileTitle = userName;
      }
      OutputStream os = response.getOutputStream();
      os.write(MessageFormat.format("<html><head><title>{0}</title></head>"
          + "<body><h1>{0}</h1></body></html>", 
          escapeContent(userProfileTitle)).getBytes(encoding));
    }

    public String getModifiedDocIds(DocIdPusher pusher, String lastChangeToken)
        throws InterruptedException, IOException {
      log.log(Level.FINE, "Last Change Token available with Adaptor [{0}]",
          lastChangeToken);
      String changeTokenOnSharePoint = userProfileServiceWS.getCurrentChangeToken();
      if (Strings.isNullOrEmpty(lastChangeToken)) {
        // Since last token is empty returning current change token
        // from SharePoint for processing future updates.
        return changeTokenOnSharePoint;
      }
      String changeTokenToUse = lastChangeToken;
      Set<DocIdPusher.Record> profilesToPush =
          new HashSet<DocIdPusher.Record>();
      UserProfileChangeQuery changeQuery = new UserProfileChangeQuery();
      changeQuery.setDelete(true);
      changeQuery.setAdd(true);
      changeQuery.setUserProfile(true);
      changeQuery.setUpdate(true);
      changeQuery.setUpdateMetadata(true);
      changeQuery.setSingleValueProperty(true);
      changeQuery.setMultiValueProperty(true);
      changeQuery.setColleague(true);
      while (true) {
        log.log(Level.FINE, "Getting changes with change token [{0}]",
            changeTokenToUse);
        UserProfileChangeDataContainer changeContainer = null;
        try {
          changeContainer = userProfileServiceWS.getUserProfileChanges(
              changeTokenToUse, changeQuery);
        } catch (WebServiceException e) {
          log.log(Level.WARNING,
              "Error Getting changes with change token [{0}]",
              changeTokenToUse);
          log.log(Level.WARNING, "Exception getUserProfileChanges : ", e);
          return changeTokenOnSharePoint;
        }
        if (changeContainer == null) {
          log.log(Level.WARNING,
              "Recevived null change container with change token [{0}]",
              changeTokenToUse);
          return changeTokenOnSharePoint;
        }
        ArrayOfUserProfileChangeData changeData = changeContainer.getChanges();
        String changeTokenFromResult = changeContainer.getChangeToken();
        if (changeData == null ||
            changeData.getUserProfileChangeData().isEmpty()) {
          log.log(Level.FINE, "No profile changes with change token [{0}]",
              changeTokenToUse);
          return changeTokenOnSharePoint;
        }
        List<UserProfileChangeData> changes =
            changeData.getUserProfileChangeData();

        for (UserProfileChangeData change : changes) {
          String userAccountName  = change.getUserAccountName();
          log.log(Level.FINE, "Processing change for user [{0}]",
              userAccountName);
          profilesToPush.add(new DocIdPusher.Record.Builder(
              new DocId(SOCIAL_ID_PREFIX + userAccountName))
              .setCrawlImmediately(true).build());
        }
        pusher.pushRecords(profilesToPush);
        profilesToPush.clear();
        changeTokenToUse = changeTokenFromResult;
        log.log(Level.FINE, "Next change token for query [{0}]",
            changeTokenToUse);
        if (Strings.isNullOrEmpty(changeTokenToUse)) {
          return changeTokenOnSharePoint;
        }
      }
    }

    private String getGSAPropertyMapping(String spPropertyName) {
      return SP_GSA_PROPERTY_MAPPINGS.containsKey(spPropertyName) ?
          SP_GSA_PROPERTY_MAPPINGS.get(spPropertyName) :
            normalizeSPPropertyNameForGSA(spPropertyName);
    }
    
    private String escapeContent(String raw) {
      return raw.replace("&", "&amp;").replace("<", "&lt;");
    }

    /**
     * Normalize propertynames so that they become queryable in GSA.
     * Replacing '-'with '_'.
     * If there are other quirky restrictions we need to add them here.
     */
    private String normalizeSPPropertyNameForGSA(String name) {
      return name.replace('-', '_');
    }

    private List<String> getUserProfilePropertyValues(
        ArrayOfPropertyData profileProperties, String propertyName) {
      if (profileProperties == null) {
        return null;
      }
      for (PropertyData property : profileProperties.getPropertyData()) {
        if (propertyName.equalsIgnoreCase(property.getName())) {
          return readUserProfilePropertyValues(property);
        }
      }
      return null;
    }

    private List<String> readUserProfilePropertyValues(
        PropertyData property) {
      List<String> values = new ArrayList<String>();
      if (property.getValues() != null) {
        for (ValueData value : property.getValues().getValueData()) {
          values.add(value.getValue().toString());
        }
      }
      return values;
    }

    private String getUserProfilePropertySingleValue(
        ArrayOfPropertyData profileProperties, String propertyName) {
      List<String> values =
          getUserProfilePropertyValues(profileProperties, propertyName);
      if (values == null || values.isEmpty()) {
        return null;
      } else {
        return values.get(0);
      }
    }

    @VisibleForTesting
    String serializeColleagues(ArrayOfContactData colleaguesData) {
      if (colleaguesData == null) {
        return null;
      }
      List<ContactData> colleagues = colleaguesData.getContactData();
      if (colleagues == null || colleagues.isEmpty()) {
        return null;
      }

      if (domImpl == null) {
        // Return null as domImpl is not available.
        log.log(Level.WARNING, "Returing null as DOMImplemenatation is null");
        return null;
      }

      Document colleaguesDocument;
      try {
        colleaguesDocument = domImpl.createDocument(
            GSA_NAMESPACE, CONTACTS_ROOT_ELEMENT, null);
      } catch (DOMException de) {
        log.log(Level.WARNING, "DOM Exception processing user colleagues",
            de);
        return null;
      }

      // Create the root element
      for (ContactData oneColleague : colleagues) {
        // For each Colleague object create element and attach it to root
        if (oneColleague.getPrivacy() == Privacy.PUBLIC) {
          Element colleagueElem = createColleagueElement(
              colleaguesDocument, oneColleague);
          if (colleagueElem != null) {
            colleaguesDocument.getDocumentElement().appendChild(colleagueElem);
          }

        }
      }

      if (!colleaguesDocument.getDocumentElement().hasChildNodes()) {
        return null;
      }

      LSSerializer lss = ls.createLSSerializer();
      LSOutput lso = ls.createLSOutput();
      StringWriter writer = new StringWriter();
      lso.setCharacterStream(writer);
      lss.write(colleaguesDocument, lso);
      String result = writer.toString();
      return result;
    }

    @VisibleForTesting
    Element createColleagueElement(Document colleaguesDocument,
        ContactData oneColleague) {
      Element ele = colleaguesDocument.createElementNS(GSA_NAMESPACE, CONTACT_ELEMENT);
      String accountName = oneColleague.getAccountName();
      if (accountName == null) {
        return null;
      }
      setColleagueAttribute(ele, "gsa:accountname", accountName);
      setColleagueAttribute(ele, "gsa:name", oneColleague.getName());
      setColleagueAttribute(ele, "gsa:email", oneColleague.getEmail());
      setColleagueAttribute(ele, "gsa:url", oneColleague.getUrl());
      setColleagueAttribute(ele, "gsa:title", oneColleague.getTitle());
      setColleagueAttribute(ele, "gsa:group", oneColleague.getGroup());
      setColleagueAttribute(ele, "gsa:isinworkinggroup",
          oneColleague.isIsInWorkGroup() ? "true" : "false");
      return ele;
    }

    @VisibleForTesting
    void setColleagueAttribute(Element e, String atrbName, String atrbValue) {
      atrbValue = atrbValue == null ? "" : atrbValue;
      try {
        e.setAttributeNS(GSA_NAMESPACE, atrbName, URLEncoder.encode(atrbValue, "UTF-8"));
      } catch (UnsupportedEncodingException uee) {
        log.log(Level.WARNING, "Error encoding value",
            uee);
      }
    }
  }
}
