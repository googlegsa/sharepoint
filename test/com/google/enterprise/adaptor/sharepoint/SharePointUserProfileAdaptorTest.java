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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocRequest;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.InvalidConfigurationException;

import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.SamlHandshakeManager;
import com.google.enterprise.adaptor.sharepoint.SharePointUserProfileAdaptor.UserProfileServiceClient;
import com.google.enterprise.adaptor.sharepoint.SharePointUserProfileAdaptor.UserProfileServiceFactory;
import com.google.enterprise.adaptor.sharepoint.SharePointUserProfileAdaptor.UserProfileServiceWS;
import com.google.enterprise.adaptor.testing.RecordingDocIdPusher;
import com.google.enterprise.adaptor.testing.RecordingResponse;
import com.google.enterprise.adaptor.testing.RecordingResponse.State;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationMode;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;
import com.microsoft.schemas.sharepoint.soap.authentication.LoginResult;

import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.ArrayOfUserProfileChangeData;
import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.UserProfileChangeData;
import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.UserProfileChangeDataContainer;
import com.microsoft.webservices.sharepointportalserver.userprofilechangeservice.UserProfileChangeQuery;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ArrayOfContactData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ArrayOfPropertyData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ArrayOfValueData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ContactData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.GetUserProfileByIndexResult;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.Privacy;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.PropertyData;
import com.microsoft.webservices.sharepointportalserver.userprofileservice.ValueData;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.w3c.dom.DOMImplementation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.ws.Binding;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.EndpointReference;

import javax.xml.ws.WebServiceException;

/** Test cases for {@link SharePointUserProfileAdaptor}. */
public class SharePointUserProfileAdaptorTest {

  private Config config;
  private SharePointUserProfileAdaptor adaptor;
  private final Charset charset = Charset.forName("UTF-8");
  private AuthenticationClientFactory authenticationFactory 
      = new MockAuthenticationClientFactoryForms();
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setup() {
    config = new Config();
    new SharePointUserProfileAdaptor().initConfig(config);
    config.overrideKey("sharepoint.server", "http://sharepoint.example.com");
    config.overrideKey("sharepoint.username", "adminUser");
    config.overrideKey("sharepoint.password", "password");
    config.overrideKey("profile.setacl", "true");
  }

  private void populateProfileProperties (
      ArrayOfPropertyData userProfileProperties,
      String property, String[] values) {
    populateProfileProperties(
        userProfileProperties, property, values, Privacy.PUBLIC);
  }

  private void populateProfileProperties (
      ArrayOfPropertyData userProfileProperties,
      String property, String[] values, Privacy privacy) {
    ArrayOfValueData valueData = new ArrayOfValueData();
    if (values != null) {
      for (String v : values) {
        ValueData valueToAdd = new ValueData();
        valueToAdd.setValue(v);
        valueData.getValueData().add(valueToAdd);
      }
    }

    PropertyData propertyData = new PropertyData();
    propertyData.setPrivacy(privacy);
    propertyData.setName(property);
    propertyData.setValues(valueData);
    userProfileProperties.getPropertyData().add(propertyData);
  }

  @Test
  public void testDestroy() {
    SharePointUserProfileAdaptor adaptor = new SharePointUserProfileAdaptor();
    adaptor.destroy();
  }
  
  @Test
  public void testAdaptorInitWithLive() throws Exception {
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor = new SharePointUserProfileAdaptor(
        new MockUserProfileServiceFactoryImpl(null), authenticationFactory);
    config.overrideKey("sharepoint.useLiveAuthentication", "true");
    config.overrideKey("sharepoint.usernamet", "");
    config.overrideKey("sharepoint.password", "");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithCustomSaml() throws Exception {
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor = new SharePointUserProfileAdaptor(
        new MockUserProfileServiceFactoryImpl(null),
        new AuthenticationClientFactoryImpl());
    String factoryMethod = MockCustomSamlHandshakeManager.class
        .getName() + ".getInstance";
    config.overrideKey("sharepoint.customSamlManager", factoryMethod);
    config.overrideKey("gsa.version", "7.4.0-0");
    config.overrideKey("test.token", "test token");
    config.overrideKey("test.cookie", "test cookie");
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }

  @Test
  public void testBlankCredentialsOnWindows() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").contains("Windows"));
    Config adaptorConfig = new Config();
    new SharePointUserProfileAdaptor().initConfig(adaptorConfig);
    adaptorConfig.overrideKey(
        "sharepoint.server", "http://sharepoint.example.com");
    assertEquals(adaptorConfig.getValue("sharepoint.username"), "");
    assertEquals(adaptorConfig.getValue("sharepoint.password"), "");
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(adaptorConfig, pusher));
  }

  @Test
  public void testGetDocIds() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    ArrayOfPropertyData profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user1"});
    serviceFactory.addUserProfileToCollection(1, 2, "user1", profile, null);

    profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user2"});
    serviceFactory.addUserProfileToCollection(2, 4, "user2", profile, null);

    profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user3"});
    serviceFactory.addUserProfileToCollection(4, 5, "user3", profile, null);

    // Last record should be discarded by Adaptor
    // since profile properties are null
    serviceFactory.addUserProfileToCollection(5, 6, "user4", null, null);

    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(0, pusher.getRecords().size());
    adaptor.getDocIds(pusher);
    assertEquals(3, pusher.getRecords().size());
  }

  @Test
  public void testGetDocIdsWithParseException() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    ArrayOfPropertyData profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user1"});
    serviceFactory.addUserProfileToCollection(1, 2, "user1", profile, null);

    profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user2"});
    serviceFactory.addUserProfileToCollection(2, 4, "user2", profile, null);

    WebServiceException toThrow = new WebServiceException(
        new XMLStreamException("ParseError"));
    serviceFactory.addExceptionForIndex(4, toThrow);

    profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user5"});
    serviceFactory.addUserProfileToCollection(5, 6, "user5", profile, null);

    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(0, pusher.getRecords().size());
    adaptor.getDocIds(pusher);
    assertEquals(3, pusher.getRecords().size());
  }

  @Test
  public void testGetDocIdsWithException() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    ArrayOfPropertyData profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user1"});
    serviceFactory.addUserProfileToCollection(1, 2, "user1", profile, null);

    profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user2"});
    serviceFactory.addUserProfileToCollection(2, 4, "user2", profile, null);

    WebServiceException toThrow = new WebServiceException(
        new Exception("non parse exception"));
    serviceFactory.addExceptionForIndex(4, toThrow);

    profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user5"});
    serviceFactory.addUserProfileToCollection(5, 6, "user5", profile, null);

    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(0, pusher.getRecords().size());
    thrown.expect(WebServiceException.class);
    adaptor.getDocIds(pusher);
  }

  @Test
  public void testGetDocIdsNoProfiles() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(0, pusher.getRecords().size());
    adaptor.getDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
  }

  @Test
  public void testGetDocContent() throws IOException, Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    ArrayOfPropertyData profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"domain\\user1"});
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_PREFERRED_NAME_PROPERTY,
        new String[] {"First & Last"});
    String[] skills =
        new String[] {"Java", "SharePoint", "C++", "Design"};
    populateProfileProperties(profile, "SPS-Skills", skills);
    populateProfileProperties(profile, "SP Single Value Property",
        new String[] {"Value1"});
    populateProfileProperties(profile, "SP Multi Value Property",
        new String[] {"Value1", "Value2", "Value3"});
    populateProfileProperties(profile, "Empty Property", null);
    populateProfileProperties(profile, "Private Property",
        new String[] {"Private Value"}, Privacy.PRIVATE);
    populateProfileProperties(profile, "UserProfile_GUID", 
        new String[] {"{guid}"});
    ArrayOfContactData colleaguesData = new ArrayOfContactData();
    ContactData cPublic = new ContactData();

    cPublic.setPrivacy(Privacy.PUBLIC);
    cPublic.setAccountName("mydomain\\public");
    cPublic.setEmail("publicuser@example.com");
    cPublic.setGroup("public group");
    cPublic.setIsInWorkGroup(true);
    cPublic.setUrl("http:\\\\www.example.com");
    cPublic.setName("Public Colleague");
    colleaguesData.getContactData().add(cPublic);

    ContactData cPrivate = new ContactData();
    cPrivate.setPrivacy(Privacy.PRIVATE);
    cPrivate.setAccountName("mydomain\\Private");
    cPrivate.setEmail("Privateuser@example.com");
    cPrivate.setGroup("Private group");
    cPrivate.setIsInWorkGroup(true);
    cPrivate.setUrl("http:\\\\www.example.com");
    cPrivate.setName("Private Colleague");
    colleaguesData.getContactData().add(cPrivate);

    serviceFactory.addUserProfileToCollection(1, 2, "domain\\user1",
        profile, colleaguesData);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory
        ,authenticationFactory);
    config.overrideKey("adaptor.namespace", "ns1");

    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId(SharePointUserProfileAdaptor.SOCIAL_ID_PREFIX 
          + "domain\\user1"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(request, response);
    
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<html><head><title>First &amp; Last"
        + "</title></head><body><h1>First &amp; Last</h1></body></html>";
    assertEquals(golden, responseString);

    assertFalse(response.getState().equals(State.NOT_FOUND));
    assertEquals("domain\\user1", response.getMetadata().getOneValue(
        "google_social_user_accountname"));
    assertEquals("Value1", response.getMetadata().getOneValue(
        "SP Single Value Property"));
    assertEquals(4, response.getMetadata().getAllValues(
        "google_social_user_skills").size());
    assertEquals(3, response.getMetadata().getAllValues(
        "SP Multi Value Property").size());
    assertFalse(response.getMetadata().getKeys().contains("Empty Property"));
    assertFalse(response.getMetadata().getKeys().contains("Private Property"));
    assertFalse(response.getMetadata().getKeys().contains("UserProfile_GUID"));

    // Colleagues Verification
    String xml = response.getMetadata().getOneValue(
        SharePointUserProfileAdaptor.GSA_PROPNAME_COLLEAGUES);
    assertNotNull(xml);

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    org.w3c.dom.Document doc = builder.parse(
        new ByteArrayInputStream(xml.getBytes()));
    assertTrue(doc.hasChildNodes());
    assertEquals(SharePointUserProfileAdaptor.CONTACTS_ROOT_ELEMENT,
        doc.getFirstChild().getNodeName());
    assertEquals(1, doc.getDocumentElement().getChildNodes().getLength());
    org.w3c.dom.Node pubContact = doc.getDocumentElement().getFirstChild();
    assertEquals(SharePointUserProfileAdaptor.CONTACT_ELEMENT,
        pubContact.getNodeName());
    String email = pubContact.getAttributes().getNamedItem("gsa:email").getNodeValue();
    assertEquals("publicuser@example.com", URLDecoder.decode(email, "UTF-8"));
    String url = pubContact.getAttributes().getNamedItem("gsa:url").getNodeValue();
    assertEquals("http:\\\\www.example.com", URLDecoder.decode(url, "UTF-8"));
    String group = pubContact.getAttributes().getNamedItem("gsa:group").getNodeValue();
    assertEquals("public group", URLDecoder.decode(group, "UTF-8"));
    String name = pubContact.getAttributes().getNamedItem("gsa:name").getNodeValue();
    assertEquals("Public Colleague", URLDecoder.decode(name, "UTF-8"));
    String isInWorkGroup
        = pubContact.getAttributes().getNamedItem("gsa:isinworkinggroup").getNodeValue();
    assertEquals("true", URLDecoder.decode(isInWorkGroup, "UTF-8"));
    
    assertEquals(URI.create("http://sharepoint.example.com/person.aspx?"
        + "accountname=domain%5Cuser1"), response.getDisplayUrl());



    //ACL Verification
    List<GroupPrincipal> groups = new ArrayList<GroupPrincipal>();
    groups.add(new GroupPrincipal("NT AUTHORITY\\Authenticated Users", "ns1"));
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.LEAF_NODE)
        .setPermitGroups(groups).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentNotFound() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);

    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    
    DocRequest request = new DocRequest(
        new DocId(SharePointUserProfileAdaptor.SOCIAL_ID_PREFIX + "user1"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }

  @Test
  public void testGetDocContentInvalidDocId() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    ArrayOfPropertyData profile = new ArrayOfPropertyData();
    populateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user1"});
    serviceFactory.addUserProfileToCollection(1, 2, "user1", profile, null);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);

    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    DocRequest request = new DocRequest(
        new DocId("user1"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }

  @Test
  public void testGetModifiedDocIdsWithEmptyChangeToken() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "change token on mock repository");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken(null);
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals("change token on mock repository",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsWithNoChange() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "same current token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken("same current token");
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals("same current token",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsDiffrentTokenNoChange() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "new token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken("old token");
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals("new token",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsWithChange() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "new token");
    //Test will use Pagination size of 3
    //batch 1 - user1,user2
    serviceFactory.addChangeLogForUser("user1");
    serviceFactory.addChangeLogForUser("user2");
    serviceFactory.addChangeLogForUser("user2");
    // batch 2 - user4, user3, user5
    serviceFactory.addChangeLogForUser("user4");
    serviceFactory.addChangeLogForUser("user3");
    serviceFactory.addChangeLogForUser("user5");
    //batch 3 -user3, user4
    serviceFactory.addChangeLogForUser("user3");
    serviceFactory.addChangeLogForUser("user4");
    serviceFactory.addChangeLogForUser("user4");
    //batch 4 -user6
    serviceFactory.addChangeLogForUser("user6");
    serviceFactory.addChangeLogForUser("user6");
    serviceFactory.addChangeLogForUser("user6");
    //batch 5 -user6
    serviceFactory.addChangeLogForUser("user6");

    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken("old token");
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(9, pusher.getRecords().size());
    assertEquals("new token",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsInvalidToken()throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "sp token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken("invalid");
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals("sp token",
        adaptor.getUserProfileChangeToken());
  }
  @Test
  public void testColleaguesDataAllPrivate() {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "sp token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    UserProfileServiceClient client = adaptor.new UserProfileServiceClient(null);

    ArrayOfContactData colleaguesData = new ArrayOfContactData();
    ContactData cPublic = new ContactData();

    cPublic.setPrivacy(Privacy.PRIVATE);
    cPublic.setAccountName("mydomain\\public");
    cPublic.setEmail("publicuser@example.com");
    cPublic.setGroup("public group");
    cPublic.setIsInWorkGroup(true);
    cPublic.setUrl("http:\\\\www.example.com");
    cPublic.setName("Public Colleague");
    colleaguesData.getContactData().add(cPublic);

    ContactData cPrivate = new ContactData();
    cPrivate.setPrivacy(Privacy.PRIVATE);
    cPrivate.setAccountName("mydomain\\Private");
    cPrivate.setEmail("Privateuser@example.com");
    cPrivate.setGroup("Private group");
    cPrivate.setIsInWorkGroup(true);
    cPrivate.setUrl("http:\\\\www.example.com");
    cPrivate.setName("Private Colleague");
    colleaguesData.getContactData().add(cPrivate);
    String xml = client.serializeColleagues(colleaguesData);
    assertNull(xml);
  }
  @Test
  public void testCreateColleagueElement() throws Exception {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "sp token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory,
        authenticationFactory);
    UserProfileServiceClient client =
        adaptor.new UserProfileServiceClient(null);
    // Get an instance of factory
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    org.w3c.dom.Document dom;
    DOMImplementation domImpl;
    DocumentBuilder db = dbf.newDocumentBuilder();
    domImpl = db.getDOMImplementation();
    // Create an instance of DOM
    dom = domImpl.createDocument(
       SharePointUserProfileAdaptor.GSA_NAMESPACE,
       SharePointUserProfileAdaptor.CONTACTS_ROOT_ELEMENT, null);
    ContactData cPublic = new ContactData();
    cPublic.setPrivacy(Privacy.PRIVATE);
    cPublic.setAccountName("mydomain\\public");
    cPublic.setEmail("publicuser@example.com");
    cPublic.setGroup("public group");
    cPublic.setIsInWorkGroup(false);
    cPublic.setUrl("http:\\\\www.example.com");
    cPublic.setName("Public Colleague");
    org.w3c.dom.Element e = client.createColleagueElement(dom, cPublic);
    assertNotNull(e);
    assertEquals(SharePointUserProfileAdaptor.CONTACT_ELEMENT, e.getNodeName());
    assertEquals("false", e.getAttribute("gsa:isinworkinggroup"));
  }

  private static class MockUserProfileServiceWS
      implements UserProfileServiceWS {
    Map<Integer, GetUserProfileByIndexResult> userProfileCollectionByIndex =
        new HashMap<Integer, GetUserProfileByIndexResult>();
    Map<String, GetUserProfileByIndexResult> userProfileCollectionByName =
        new HashMap<String, GetUserProfileByIndexResult>();


    String newChangeToken;
    List<UserProfileChangeData> changes;
    Map<Integer, WebServiceException> exceptionsToThrow;

    public MockUserProfileServiceWS(String changeToken) {
      this.newChangeToken = changeToken;
      changes = new ArrayList<UserProfileChangeData>();
      exceptionsToThrow = new HashMap<Integer, WebServiceException>();
    }

    @Override
    public GetUserProfileByIndexResult getUserProfileByIndex(int index)
        throws WebServiceException {
      if (exceptionsToThrow.containsKey(index)) {
        throw exceptionsToThrow.get(index);
      }
      Integer[] indexArray =
          userProfileCollectionByIndex.keySet().toArray(new Integer[0]);
      Arrays.sort(indexArray);
      for (int i : indexArray) {
        if (index <= i) {
          // This is to mock user profile web service/
          // User profile web service will send user profile at next
          // available index when profile is not available
          // at exact requested index.
          return userProfileCollectionByIndex.get(i);
        }
      }
      return null;
    }

    @Override
    public ArrayOfPropertyData getUserProfileByName(String userName)
        throws WebServiceException {
      if (userProfileCollectionByName.containsKey(userName)) {
        System.out.println("Returning profile for : " + userName);
        return userProfileCollectionByName.get(userName).getUserProfile();
      } else {
        throw new WebServiceException("A user with the account name "
            + userName + " could not be found. ---> User Not Found: "
            + "Could not load profile data from the database.");
      }
    }


    @Override
    public ArrayOfContactData getUserColleagues(String key)
        throws WebServiceException {
      if (userProfileCollectionByName.containsKey(key)) {
        return userProfileCollectionByName.get(key).getColleagues();
      } else {
        return null;
      }
    }

    @Override
    public String getCurrentChangeToken() throws WebServiceException {
      // TODO(tvartak) Auto-generated method stub
      return newChangeToken;
    }

    @Override
    public UserProfileChangeDataContainer getUserProfileChanges(
        String lastChangeToken, UserProfileChangeQuery changeQuery)
            throws WebServiceException {
      if (lastChangeToken == "invalid") {
        throw new WebServiceException("Invalid change token");
      }
      UserProfileChangeDataContainer changeContainer =
          new UserProfileChangeDataContainer();
      changeContainer.setChanges(new ArrayOfUserProfileChangeData());

      if (newChangeToken.equals(lastChangeToken)) {
        changeContainer.setChangeToken(newChangeToken);
        return changeContainer;
      }
      int batchCount = 0;
      while (!changes.isEmpty()) {
        changeContainer.getChanges().getUserProfileChangeData().add(
            changes.remove(0));
        batchCount++;
        // Pagination with page size of 3
        if (batchCount == 3) {
          changeContainer.setChangeToken("paged");
          return changeContainer;
        }
      }
      changeContainer.setChangeToken(newChangeToken);
      return changeContainer;
    }

    public void addUserProfileToCollection(int index, int nextIndex,
        String userAccountName, ArrayOfPropertyData profileProperties,
        ArrayOfContactData colleagues) {
      GetUserProfileByIndexResult userProfile =
          new GetUserProfileByIndexResult();
      userProfile.setUserProfile(profileProperties);
      userProfile.setNextValue(Integer.toString(nextIndex));
      userProfile.setColleagues(colleagues);
      userProfileCollectionByIndex.put(index, userProfile);
      userProfileCollectionByName.put(userAccountName, userProfile);
    }

    public void addChangeLogForUser(String userName) {
      UserProfileChangeData change = new UserProfileChangeData();
      change.setUserAccountName(userName);
      changes.add(change);
    }

    public void addExceptionForIndex(int index, WebServiceException toThrow) {
      exceptionsToThrow.put(index, toThrow);
    }
  }

  private static class MockUserProfileServiceFactoryImpl
      implements UserProfileServiceFactory {
    MockUserProfileServiceWS proxy;

    public MockUserProfileServiceFactoryImpl(String changeTokenOnRepository) {
      proxy =
          new MockUserProfileServiceWS(changeTokenOnRepository);
    }
    @Override
    public UserProfileServiceWS newUserProfileService(String endpoint,
        String endpointChangeService,
        Map<String, List<String>> requestHeaders) {
      return proxy;
    }

    public void addUserProfileToCollection (int index, int nextIndex,
        String userAccountName, ArrayOfPropertyData profileProperties,
        ArrayOfContactData colleagues) {
      proxy.addUserProfileToCollection(index, nextIndex,
          userAccountName, profileProperties, colleagues);
    }

    public void addExceptionForIndex(int index, WebServiceException toThrow) {
      proxy.addExceptionForIndex(index, toThrow);
    }

    public void addChangeLogForUser(String userName) {
      proxy.addChangeLogForUser(userName);
    }
  }
  
  private static class MockAuthenticationSoap
      implements AuthenticationSoap, BindingProvider {
    private final Map<String, Object> requestContext 
        = new HashMap<String, Object>();
    @Override
    public LoginResult login(String string, String string1) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AuthenticationMode mode() {
      return AuthenticationMode.WINDOWS;
    }    

    @Override
    public Map<String, Object> getRequestContext() {
      return requestContext;
    }

    @Override
    public Map<String, Object> getResponseContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Binding getBinding() {
      throw new UnsupportedOperationException();
    }

    @Override
    public EndpointReference getEndpointReference() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends EndpointReference> T getEndpointReference(
        Class<T> clazz) {
      throw new UnsupportedOperationException();
    }
  }
  
  private static class UnsupportedAuthenticationClientFactory 
      implements AuthenticationClientFactory {

    @Override
    public AuthenticationSoap newSharePointFormsAuthentication(
        String virtualServer, String username, String password)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public SamlHandshakeManager newAdfsAuthentication(String virtualServer,
        String username, String password, String stsendpoint, String stsrelam,
      String login, String trustlocation) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public SamlHandshakeManager newLiveAuthentication(String virtualServer,
        String username, String password) throws IOException {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public SamlHandshakeManager newCustomSamlAuthentication(
        String factoryMethodName, Map<String, String> config)
        throws IOException {
      throw new UnsupportedOperationException();
    }
    
  }
  
  private static class MockAuthenticationClientFactoryForms 
      extends UnsupportedAuthenticationClientFactory {
    @Override
    public AuthenticationSoap newSharePointFormsAuthentication(
        String virtualServer, String username, String password)
        throws IOException {
      return new MockAuthenticationSoap();
    }    
  }
  
  private static class MockCustomSamlHandshakeManager
      implements SamlHandshakeManager {
    private String token;
    private String cookie;
    private MockCustomSamlHandshakeManager(String token, String cookie) {
      this.token = token;
      this.cookie = cookie;      
    }
    
    @Override
    public String requestToken() throws IOException {
      return token;
    }
    
    @Override
    public String getAuthenticationCookie(String token) throws IOException {
      return cookie;
    }
    
    public static SamlHandshakeManager getInstance(Map<String, String> config) {
      return new MockCustomSamlHandshakeManager(
          config.get("test.token"), config.get("test.cookie"));
    }
    
    public static String getStringIntance(Map<String, String> config) {
      return "wrong object";
    }
  }  
}
