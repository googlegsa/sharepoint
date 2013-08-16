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
import com.google.enterprise.adaptor.PollingIncrementalAdaptor;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;

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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.ws.EndpointReference;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.wsaddressing.W3CEndpointReferenceBuilder;

/**
 * An adaptor for obtaining user profile information from SharePoint.
 * @author tvartak
 *
 */
public class SharePointUserProfileAdaptor extends AbstractAdaptor
    implements PollingIncrementalAdaptor {
  private static final Map<String, String> SP_GSA_PROPERTY_MAPPINGS;

  private static final Charset encoding = Charset.forName("UTF-8");

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
  private NtlmAuthenticator ntlmAuthenticator;
  private final UserProfileServiceFactory userProfileServiceFactory;

  private String userProfileChangeToken;
  private boolean setAcl = true;
  private UserProfileServiceClient userProfileServiceClient;

  public static void main(String[] args) {
    AbstractAdaptor.main(new SharePointUserProfileAdaptor(), args);
  }

  public SharePointUserProfileAdaptor() {
    this(new UserProfileServiceFactoryImpl());
  }

  @VisibleForTesting
  SharePointUserProfileAdaptor(
      UserProfileServiceFactory userProfileServiceFactory) {
    if (userProfileServiceFactory == null) {
      throw new NullPointerException();
    }
    this.userProfileServiceFactory = userProfileServiceFactory;
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
    config.addKey("sharepoint.username", null);
    config.addKey("sharepoint.password", null);
    config.addKey("profile.setacl", "true");
  }

  @Override
  public void init(AdaptorContext context) throws IOException {
    Config config = context.getConfig();

    virtualServer = config.getValue("sharepoint.server");
    if (virtualServer.endsWith("/")) {
      virtualServer = virtualServer.substring(0, virtualServer.length() - 1);
    }
    String username = config.getValue("sharepoint.username");
    String password = context.getSensitiveValueDecoder().decodeValue(
        config.getValue("sharepoint.password"));
    setAcl = Boolean.parseBoolean(config.getValue("profile.setacl"));

    log.log(Level.CONFIG, "virtualServer: {0}", virtualServer);
    log.log(Level.CONFIG, "Username: {0}", username);
    log.log(Level.CONFIG, "setAcl: {0}", setAcl);

    ntlmAuthenticator = new NtlmAuthenticator(username, password);
    // Unfortunately, this is a JVM-wide modification.
    Authenticator.setDefault(ntlmAuthenticator);
    log.log(Level.FINEST, "Initializing User profile Service Client for {0}",
        virtualServer + USER_PROFILE_SERVICE_ENDPOINT);
    userProfileServiceClient = new UserProfileServiceClient(
        userProfileServiceFactory.newUserProfileService(
            virtualServer + USER_PROFILE_SERVICE_ENDPOINT,
            virtualServer + USER_PROFILE_CHANGE_SERVICE_ENDPOINT));
    userProfileChangeToken =
        userProfileServiceClient.userProfileServiceWS.getCurrentChangeToken();
  }

  @Override
  public void destroy() {
    Authenticator.setDefault(null);
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
        String endpointChangeService);
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
        String endpointChangeService) {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(endpoint).build();
      EndpointReference endpointChangeRef = new W3CEndpointReferenceBuilder()
          .address(endpointChangeService).build();
      return new SharePointUserProfileServiceWS(userProfileServiceSoap.
          getPort(endpointRef, UserProfileServiceSoap.class),
          userProfileChangeServiceSoap.getPort(endpointChangeRef,
              UserProfileChangeServiceSoap.class));
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
        List<String> values = readUserProfilePropertyValues(prop);
        for (String v : values) {
          response.addMetadata(propertyName, v);
        }
      }
      if (setAcl) {
        List<GroupPrincipal> permitGroups = new ArrayList<GroupPrincipal>();
        permitGroups.add(
            new GroupPrincipal("NT AUTHORITY\\Authenticated Users"));
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
