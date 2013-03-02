package com.google.enterprise.adaptor.sharepoint;

import static org.junit.Assert.*;


import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;

import com.google.enterprise.adaptor.sharepoint.SharePointUserProfileAdaptor.UserProfileServiceClient;
import com.google.enterprise.adaptor.sharepoint.SharePointUserProfileAdaptor.UserProfileServiceFactory;
import com.google.enterprise.adaptor.sharepoint.SharePointUserProfileAdaptor.UserProfileServiceWS;

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

import org.junit.*;

import org.w3c.dom.DOMImplementation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import javax.xml.ws.WebServiceException;


public class SharePointUserProfileAdaptorTest {

  private Config config;
  private SharePointUserProfileAdaptor adaptor;

@Before
  public void setup() {
    config = new Config();
    new SharePointUserProfileAdaptor().initConfig(config);
    config.overrideKey("sharepoint.server", "http://sharepoint.example.com");
    config.overrideKey("sharepoint.username", "adminUser");
    config.overrideKey("sharepoint.password", "password");
    config.overrideKey("profile.setacl", "true");
  }

  private void poulateProfileProperties (
      ArrayOfPropertyData userProfileProperties,
      String property, String[] values) {
    poulateProfileProperties(
        userProfileProperties, property, values, Privacy.PUBLIC);
  }

  private void poulateProfileProperties (
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
  public void testGetDocIds() throws IOException, InterruptedException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    ArrayOfPropertyData profile = new ArrayOfPropertyData();
    poulateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user1"});
    serviceFactory.AddUserProfileToCollection(1, 2, "user1", profile, null);

    profile = new ArrayOfPropertyData();
    poulateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user2"});
    serviceFactory.AddUserProfileToCollection(2, 4, "user2", profile, null);

    profile = new ArrayOfPropertyData();
    poulateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user3"});
    serviceFactory.AddUserProfileToCollection(4, 5, "user3", profile, null);

    // Last record should be discarded by Adaptor
    // since profile properties are null
    serviceFactory.AddUserProfileToCollection(5, 6, "user4", null, null);

    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(0, pusher.getRecords().size());
    adaptor.getDocIds(pusher);
    assertEquals(3, pusher.getRecords().size());
  }

  @Test
  public void testGetDocIdsNoProfiles()
      throws IOException, InterruptedException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
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
    poulateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user1"});
    String[] skills =
        new String[] {"Java", "SharePoint", "C++", "Design"};
    poulateProfileProperties(profile, "SPS-Skills", skills);
    poulateProfileProperties(profile, "SP Single Value Property",
        new String[] {"Value1"});
    poulateProfileProperties(profile, "SP Multi Value Property",
        new String[] {"Value1", "Value2", "Value3"});
    poulateProfileProperties(profile, "Empty Property", null);
    poulateProfileProperties(profile, "Private Property",
        new String[] {"Private Value"}, Privacy.PRIVATE);
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

    serviceFactory.AddUserProfileToCollection(1, 2, "user1", profile, colleaguesData);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);

    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId(SharePointUserProfileAdaptor.SOCIAL_ID_PREFIX + "user1"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(request, response);

    assertFalse(response.isNotFound());
    assertEquals("user1", response.getMetadata().getOneValue(
        "google_social_user_accountname"));
    assertEquals("Value1", response.getMetadata().getOneValue(
        "SP Single Value Property"));
    assertEquals(4, response.getMetadata().getAllValues(
        "google_social_user_skills").size());
    assertEquals(3, response.getMetadata().getAllValues(
        "SP Multi Value Property").size());
    assertFalse(response.getMetadata().getKeys().contains("Empty Property"));
    assertFalse(response.getMetadata().getKeys().contains("Private Property"));

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
    String isInWorkGroup = pubContact.getAttributes().getNamedItem("gsa:isinworkinggroup").getNodeValue();
    assertEquals("true", URLDecoder.decode(isInWorkGroup, "UTF-8"));



    //ACL Verification
    assertNotNull(response.getAcl());
    assertNotNull(response.getAcl().getPermitGroups());
    assertEquals(1, response.getAcl().getPermitGroups().size());
    assertTrue(response.getAcl().getPermitGroups().contains(
        "NT AUTHORITY\\Authenticated Users"));
    assertTrue(response.getAcl().getPermitUsers().isEmpty());
    assertTrue(response.getAcl().getDenyGroups().isEmpty());
    assertTrue(response.getAcl().getDenyUsers().isEmpty());
  }

  @Test
  public void testGetDocContentNotFound() throws IOException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);

    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId( SharePointUserProfileAdaptor.SOCIAL_ID_PREFIX + "user1"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(request, response);
    assertTrue(response.isNotFound());
  }

  @Test
  public void testGetDocContentInvalidDocId() throws IOException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(null);
    ArrayOfPropertyData profile = new ArrayOfPropertyData();
    poulateProfileProperties(profile,
        SharePointUserProfileAdaptor.PROFILE_ACCOUNTNAME_PROPERTY,
        new String[] {"user1"});
    serviceFactory.AddUserProfileToCollection(1, 2, "user1", profile, null);
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);

    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("user1"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(request, response);
    assertTrue(response.isNotFound());
  }

  @Test
  public void testGetModifiedDocIdsWithEmptyChangeToken()
      throws InterruptedException, IOException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "change token on mock repository");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken(null);
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals("change token on mock repository",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsWithNoChange()
      throws InterruptedException, IOException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "same current token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken("same current token");
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals("same current token",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsDiffrentTokenNoChange()
      throws InterruptedException, IOException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "new token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken("old token");
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals("new token",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsWithChange()
      throws InterruptedException, IOException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "new token");
    //Test will use Pagination size of 3
    //batch 1 - user1,user2
    serviceFactory.AddChangeLogForUser("user1");
    serviceFactory.AddChangeLogForUser("user2");
    serviceFactory.AddChangeLogForUser("user2");
    // batch 2 - user4, user3, user5
    serviceFactory.AddChangeLogForUser("user4");
    serviceFactory.AddChangeLogForUser("user3");
    serviceFactory.AddChangeLogForUser("user5");
    //batch 3 -user3, user4
    serviceFactory.AddChangeLogForUser("user3");
    serviceFactory.AddChangeLogForUser("user4");
    serviceFactory.AddChangeLogForUser("user4");
    //batch 4 -user6
    serviceFactory.AddChangeLogForUser("user6");
    serviceFactory.AddChangeLogForUser("user6");
    serviceFactory.AddChangeLogForUser("user6");
    //batch 5 -user6
    serviceFactory.AddChangeLogForUser("user6");

    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.setUserProfileChangeToken("old token");
    assertEquals(0, pusher.getRecords().size());
    adaptor.getModifiedDocIds(pusher);
    assertEquals(9, pusher.getRecords().size());
    assertEquals("new token",
        adaptor.getUserProfileChangeToken());
  }

  @Test
  public void testGetModifiedDocIdsInvalidToken()
      throws InterruptedException, IOException {
    MockUserProfileServiceFactoryImpl serviceFactory =
        new MockUserProfileServiceFactoryImpl(
            "sp token");
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
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
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
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
    adaptor = new SharePointUserProfileAdaptor(serviceFactory);
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

    public MockUserProfileServiceWS(String changeToken) {
      this.newChangeToken = changeToken;
      changes = new ArrayList<UserProfileChangeData>();
    }

    @Override
    public GetUserProfileByIndexResult getUserProfileByIndex(int index)
        throws WebServiceException {
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
      // TODO Auto-generated method stub
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

    public void AddUserProfileToCollection(int index, int nextIndex,
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

    public void AddChangeLogForUser(String userName) {
      UserProfileChangeData change = new UserProfileChangeData();
      change.setUserAccountName(userName);
      changes.add(change);
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
        String endpointChangeService) {
      return proxy;
    }

    public void AddUserProfileToCollection (int index, int nextIndex,
        String userAccountName, ArrayOfPropertyData profileProperties,
        ArrayOfContactData colleagues) {
      proxy.AddUserProfileToCollection(index, nextIndex,
          userAccountName, profileProperties, colleagues);
    }

    public void AddChangeLogForUser(String userName) {
      proxy.AddChangeLogForUser(userName);
    }
  }
}
