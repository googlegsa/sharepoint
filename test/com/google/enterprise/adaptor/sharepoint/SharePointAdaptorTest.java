// Copyright 2012 Google Inc. All Rights Reserved.
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

import static com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.FileInfo;
import static com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.HttpClient;
import static com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.SiteDataFactory;
import static org.junit.Assert.*;

import com.google.common.util.concurrent.Callables;
import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.IOHelper;
import com.google.enterprise.adaptor.Metadata;
import com.google.enterprise.adaptor.UserPrincipal;
import com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.SiteUserIdMappingCallable;
import com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.UserGroupFactory;

import com.microsoft.schemas.sharepoint.soap.ArrayOfSFPUrl;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSList;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSListWithTime;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSProperty;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSWebWithTime;
import com.microsoft.schemas.sharepoint.soap.ArrayOfString;
import com.microsoft.schemas.sharepoint.soap.ObjectType;
import com.microsoft.schemas.sharepoint.soap.SListMetadata;
import com.microsoft.schemas.sharepoint.soap.SPContentDatabase;
import com.microsoft.schemas.sharepoint.soap.SSiteMetadata;
import com.microsoft.schemas.sharepoint.soap.SWebMetadata;
import com.microsoft.schemas.sharepoint.soap.SiteDataSoap;
import com.microsoft.schemas.sharepoint.soap.directory.AddUserCollectionToGroup;
import com.microsoft.schemas.sharepoint.soap.directory.AddUserCollectionToRole;
import com.microsoft.schemas.sharepoint.soap.directory.EmailsInputType;
import com.microsoft.schemas.sharepoint.soap.directory.GetAllUserCollectionFromWebResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetCurrentUserInfoResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetGroupCollectionFromRoleResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetGroupCollectionFromSiteResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetGroupCollectionFromUserResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetGroupCollectionFromWebResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetGroupCollectionResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetGroupInfoResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetRoleCollectionFromGroupResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetRoleCollectionFromUserResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetRoleCollectionFromWebResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetRoleCollectionResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetRolesAndPermissionsForCurrentUserResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetRolesAndPermissionsForSiteResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollection;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromGroupResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromRoleResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromSiteResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromWebResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserInfoResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserLoginFromEmailResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GroupsInputType;
import com.microsoft.schemas.sharepoint.soap.directory.PrincipalType;
import com.microsoft.schemas.sharepoint.soap.directory.RemoveUserCollectionFromGroup;
import com.microsoft.schemas.sharepoint.soap.directory.RemoveUserCollectionFromRole;
import com.microsoft.schemas.sharepoint.soap.directory.RemoveUserCollectionFromSite;
import com.microsoft.schemas.sharepoint.soap.directory.RoleOutputType;
import com.microsoft.schemas.sharepoint.soap.directory.RolesInputType;
import com.microsoft.schemas.sharepoint.soap.directory.TrueFalseType;
import com.microsoft.schemas.sharepoint.soap.directory.User;
import com.microsoft.schemas.sharepoint.soap.directory.UserGroupSoap;
import com.microsoft.schemas.sharepoint.soap.directory.Users;

import org.junit.*;
import org.junit.rules.ExpectedException;

import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceException;

/**
 * Test cases for {@link SharePointAdaptor}.
 */
public class SharePointAdaptorTest {
  private final Charset charset = Charset.forName("UTF-8");
  private Config config;
  private SharePointAdaptor adaptor;
  private Executor executor = new UnsupportedExecutor();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  /**
   * JAXBContext is expensive to create and is created as part of the class'
   * initialization. Do this in a separately so that the timing for this
   * initalization does not count toward the first real test run. It looks like
   * a bug when a faster test takes longer, just because it ran first.
   */
  @BeforeClass
  public static void initJaxbContext() {
    SharePointAdaptor.init();
  }

  @Before
  public void setup() {
    config = new Config();
    new SharePointAdaptor().initConfig(config);
    config.overrideKey("sharepoint.server", "http://localhost:1");
    config.overrideKey("sharepoint.username", "fakeuser");
    config.overrideKey("sharepoint.password", "fakepass");
  }

  @After
  public void teardown() {
    if (adaptor != null) {
      adaptor.destroy();
    }
  }

  public List<UserPrincipal> users(String... names) {
    List<UserPrincipal> users = new ArrayList<UserPrincipal>();
    for (String name : names) {
      users.add(new UserPrincipal(name));
    }
    return users;
  }

  public List<GroupPrincipal> groups(String... names) {
    List<GroupPrincipal> groups = new ArrayList<GroupPrincipal>();
    for (String name : names) {
      groups.add(new GroupPrincipal(name));
    }
    return groups;
  }
  
  public User createUserGroupUser(long id, String loginName, String sid, 
      String name, String email, boolean isDomainGroup, boolean isSiteAdmin) {
    User u = new User();
    u.setID(id);
    u.setLoginName(loginName);
    u.setSid(sid);
    u.setName(name);
    u.setEmail(email);
    u.setIsDomainGroup(
        isDomainGroup ? TrueFalseType.TRUE : TrueFalseType.FALSE);
    u.setIsSiteAdmin(
        isSiteAdmin ? TrueFalseType.TRUE : TrueFalseType.FALSE);
    return u;        
  }

  @Test
  public void testConstructor() {
    new SharePointAdaptor();
  }

  @Test
  public void testNullSiteDataFactory() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(null, new UnsupportedUserGroupFactory(),
        new UnsupportedHttpClient(), executor);
  }
  
  @Test
  public void testNullUserGroupFactory() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(new UnsupportedSiteDataFactory(), null,
        new UnsupportedHttpClient(), executor);
  }

  @Test
  public void testNullHttpClient() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), null, executor);
  }
  
  @Test
  public void testNullExecutor() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(), null);
  }

  @Test
  public void testInitDestroy() throws IOException {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(),
        new UnsupportedHttpClient(), executor);
    adaptor.init(new MockAdaptorContext(config, null));
    adaptor.destroy();
    adaptor = null;
  }

  @Test
  public void testGetDocContentWrongServer() throws IOException {
    class WrongServerSiteData extends UnsupportedSiteData {
      @Override
      public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
          Holder<String> strSite, Holder<String> strWeb) {
        assertEquals("http://wronghost:1/", strUrl);

        setValue(getSiteAndWebResult, 1L);
        setValue(strSite, null);
        setValue(strWeb, null);
      }
    }

    adaptor = new SharePointAdaptor(
        new SingleSiteDataFactory(new WrongServerSiteData(),
          "http://localhost:1/_vti_bin/SiteData.asmx"),
        new UnsupportedUserGroupFactory(),
        new UnsupportedHttpClient(), executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://wronghost:1/"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(request, response);
    assertTrue(response.isNotFound());
  }

  @Test
  public void testGetDocContentWrongPage() throws IOException {
    final String wrongPage = "http://localhost:1/wrongPage";
    class WrongPageSiteData extends UnsupportedSiteData {
      @Override
      public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
          Holder<String> strSite, Holder<String> strWeb) {
        assertEquals(wrongPage, strUrl);

        setValue(getSiteAndWebResult, 0L);
        setValue(strSite, "http://localhost:1");
        setValue(strWeb, "http://localhost:1");
      }

      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        assertEquals(wrongPage, strURL);

        setValue(getURLSegmentsResult, false);
        setValue(strWebID, null);
        setValue(strBucketID, null);
        setValue(strListID, null);
        setValue(strItemID, null);
      }
    }

    adaptor = new SharePointAdaptor(
        new SingleSiteDataFactory(new WrongPageSiteData(),
          "http://localhost:1/_vti_bin/SiteData.asmx"),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(new DocId(wrongPage));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(request, response);
    assertTrue(response.isNotFound());
  }

  @Test
  public void testGetDocContentVirtualServer() throws IOException {
    final String getContentVirtualServer
        = loadTestString("vs.xml");
    final String getContentContentDatabase
        = loadTestString("cd.xml");
    class VirtualServerSiteData extends UnsupportedSiteData {
      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.VIRTUAL_SERVER.equals(objectType)) {
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          setValue(getContentResult, getContentVirtualServer);
        } else if (ObjectType.CONTENT_DATABASE.equals(objectType)) {
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}", objectId);
          setValue(getContentResult, getContentContentDatabase);
        } else {
          fail("Unknown object type: " + objectType);
          throw new AssertionError();
        }
      }
    }

    adaptor = new SharePointAdaptor(
        new SingleSiteDataFactory(new VirtualServerSiteData(),
            "http://localhost:1/_vti_bin/SiteData.asmx"),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(new GetContentsRequest(new DocId("")), response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>Virtual Server http://localhost:1/</title></head>"
        + "<body><h1>Virtual Server http://localhost:1/</h1>"
        + "<p>Sites</p><ul>"
        // These are relative URLs to DocIds that are URLs, and thus the "./"
        // prefix is correct.
        + "<li><a href=\"./http://localhost:1\">localhost:1</a></li>"
        + "<li><a href=\"./http://localhost:1/sites/SiteCollection\">"
        + "SiteCollection</a></li>"
        + "</ul></body></html>";
    assertEquals(golden, responseString);
    String[] permit = new String[] {"GDC-PSL\\Administrator",
        "GDC-PSL\\spuser1", "NT AUTHORITY\\LOCAL SERVICE"};
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(users(permit)).setPermitGroups(groups(permit)).build(),
        response.getAcl());
    assertNull(response.getDisplayUrl());
  }

  @Test
  public void testGetDocContentSiteCollection() throws IOException {
    final String getContentSiteCollection
        = loadTestString("sites-SiteCollection-sc.xml");
    final String getContentSite
        = loadTestString("sites-SiteCollection-s.xml");
    class SiteCollectionSiteData extends UnsupportedSiteData {
      private final String endpoint;

      public SiteCollectionSiteData(String endpoint) {
        this.endpoint = endpoint;
      }

      @Override
      public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
          Holder<String> strSite, Holder<String> strWeb) {
        assertEquals(endpoint, "http://localhost:1/_vti_bin/SiteData.asmx");
        assertEquals("http://localhost:1/sites/SiteCollection", strUrl);
        setValue(getSiteAndWebResult, 0L);
        setValue(strSite, "http://localhost:1/sites/SiteCollection");
        setValue(strWeb, "http://localhost:1/sites/SiteCollection");
      }

      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        assertEquals(endpoint,
            "http://localhost:1/sites/SiteCollection/_vti_bin/SiteData.asmx");
        assertEquals("http://localhost:1/sites/SiteCollection", strURL);

        setValue(getURLSegmentsResult, true);
        setValue(strWebID, null);
        setValue(strBucketID, null);
        setValue(strListID, null);
        setValue(strItemID, null);
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        if (objectType.equals(ObjectType.SITE)) {
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals(null, objectId);
          setValue(lastItemIdOnPage, null);
          setValue(getContentResult, getContentSite);
        } else if (objectType.equals(ObjectType.SITE_COLLECTION)) {
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals(null, objectId);
          setValue(lastItemIdOnPage, null);
          setValue(getContentResult, getContentSiteCollection);
        } else {
          fail("Unexpected objectType: " + objectType);
        }
      }
    }

    adaptor = new SharePointAdaptor(new SiteDataFactory() {
          @Override
          public SiteDataSoap newSiteData(String endpoint) {
            return new SiteCollectionSiteData(endpoint);
          }
        }, new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>Site chinese1</title></head>"
        + "<body><h1>Site chinese1</h1>"
        + "<p>Sites</p>"
        + "<ul><li><a href=\"SiteCollection/somesite\">"
        + "http://localhost:1/sites/SiteCollection/somesite</a></li></ul>"
        + "<p>Lists</p>"
        + "<ul><li><a href=\"SiteCollection/Lists/Announcements/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Lists/Announcements/AllItems.aspx</a></li>"
        + "<li><a href=\"SiteCollection/Shared%20Documents/Forms/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Shared Documents/Forms/AllItems.aspx</a>"
        + "</li></ul>"
        + "<p>Folders</p>"
        + "<ul><li><a href=\"SiteCollection/Lists\">Lists</a></li></ul>"
        + "<p>List Items</p>"
        + "<ul><li><a href=\"SiteCollection/default.aspx\">"
        + "default.aspx</a></li></ul>"
        + "</body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId(""))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(groups("chinese1 Members", "chinese1 Owners",
            "chinese1 Visitors"))
        .setPermitUsers(users("GDC-PSL\\spuser1")).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection"),
        response.getDisplayUrl());
  }

  @Test
  public void testGetDocContentList() throws IOException {
    final String getContentListResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-l.xml");
    final String getContentSite
        = loadTestString("sites-SiteCollection-s.xml");
    final String getContentFolderResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-f.xml");
    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        assertEquals("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/AllItems.aspx", strURL);
        setValue(getURLSegmentsResult, true);
        setValue(strWebID, null);
        setValue(strBucketID, null);
        setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
        setValue(strItemID, null);
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.LIST.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          assertEquals(null, folderUrl);
          assertEquals(null, itemId);
          setValue(getContentResult, getContentListResponse);
        } else if (ObjectType.SITE.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals(null, objectId);
          assertEquals(null, folderUrl);
          assertEquals(null, itemId);
          setValue(getContentResult, getContentSite);
        } else if (ObjectType.FOLDER.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          assertEquals("", folderUrl);
          assertEquals(null, itemId);
          assertEquals(null, lastItemIdOnPage.value);
          setValue(getContentResult, getContentFolderResponse);
          setValue(lastItemIdOnPage, null);
        } else {
          fail("Unexpected object type: " + objectType);
          throw new AssertionError();
        }
      }
    };
    final MemberIdMapping memberIdMapping;
    {
      Map<Integer, String> users = new HashMap<Integer, String>();
      Map<Integer, String> groups = new HashMap<Integer, String>();
      users.put(1, "GDC-PSL\\administrator");
      groups.put(3, "SiteCollection Owners");
      groups.put(4, "SiteCollection Visitors");
      groups.put(5, "SiteCollection Members");
      memberIdMapping = new MemberIdMapping(users, groups);
    }

    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "AllItems.aspx"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), Callables.returning(memberIdMapping),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>List Custom List</title></head>"
        + "<body><h1>List Custom List</h1>"
        + "<p>List Items</p>"
        + "<ul>"
        + "<li><a href=\"3_.000\">Outside Folder</a></li>"
        + "<li><a href=\"Test%20Folder\">Test Folder</a></li>"
        + "</ul></body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId(""))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(groups("SiteCollection Members",
            "SiteCollection Owners", "SiteCollection Visitors")).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/AllItems.aspx"), response.getDisplayUrl());
  }

  @Test
  public void testGetDocContentAttachment() throws IOException {
    final String site = "http://localhost:1/sites/SiteCollection";
    final String attachmentId = site + "/Lists/Custom List/Attachments/2/104600"
        + "0.pdf";
    final String listId = site + "/Lists/Custom List/AllItems.aspx";
    final String listGuid = "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}";
    final String getContentListItemAttachments
        = loadTestString("sites-SiteCollection-Lists-CustomList-2-a.xml");
    final String getContentListItemResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-2-li.xml");
    final String getContentListResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-l.xml");
    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        assertEquals(listId, strURL);

        setValue(getURLSegmentsResult, true);
        setValue(strWebID, null);
        setValue(strBucketID, null);
        setValue(strListID, listGuid);
        setValue(strItemID, null);
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        if (ObjectType.LIST_ITEM_ATTACHMENTS.equals(objectType)) {
          assertEquals(listGuid, objectId);
          assertEquals("", folderUrl);
          assertEquals("2", itemId);

          setValue(lastItemIdOnPage, null);
          setValue(getContentResult, getContentListItemAttachments);
        } else if (ObjectType.LIST_ITEM.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals(listGuid, objectId);
          assertEquals("", folderUrl);
          assertEquals("2", itemId);

          setValue(lastItemIdOnPage, null);
          setValue(getContentResult, getContentListItemResponse);
        } else if (objectType.equals(ObjectType.LIST)) {
          assertEquals(false, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals(listGuid, objectId);
          setValue(getContentResult, getContentListResponse);
        } else {
          fail("Unexpected object type: " + objectType);
          throw new AssertionError();
        }
      }
    };
    final String goldenContents = "attachment contents";
    final String goldenContentType = "fake/type";
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new HttpClient() {
      @Override
      public FileInfo issueGetRequest(URL url) {
        assertEquals(
          "http://localhost:1/sites/SiteCollection/Lists/Custom%20List/"
            + "Attachments/2/1046000.pdf",
          url.toString());
        InputStream contents = new ByteArrayInputStream(
            goldenContents.getBytes(charset));
        List<String> headers = Arrays.asList("not-the-Content-Type", "early",
            "conTent-TypE", goldenContentType, "Content-Type", "late");
        return new FileInfo.Builder(contents).setHeaders(headers).build();
      }
    }, executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId(attachmentId));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(),
          new UnsupportedCallable<MemberIdMapping>(),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    assertEquals(goldenContents, responseString);
    assertEquals(goldenContentType, response.getContentType());
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId(
          "http://localhost:1/sites/SiteCollection/Lists/Custom List/"
            + "Test Folder/2_.000"))
        .build(),
        response.getAcl());
    assertEquals(URI.create(
          "http://localhost:1/sites/SiteCollection/Lists/Custom%20List/"
            + "Attachments/2/1046000.pdf"),
        response.getDisplayUrl());
  }

  @Test
  public void testGetDocContentListItem() throws IOException {
    final String getContentListResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-l.xml");
    final String getContentListItemResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-2-li.xml");
    final String getContentFolderResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-1-li.xml");
    final String getContentListItemAttachmentsResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-2-a.xml");
    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        if (("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder/2_.000").equals(strURL)) {
          setValue(getURLSegmentsResult, true);
          setValue(strWebID, null);
          setValue(strBucketID, null);
          setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
          setValue(strItemID, "2");
        } else if (("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder").equals(strURL)) {
          setValue(getURLSegmentsResult, true);
          setValue(strWebID, null);
          setValue(strBucketID, null);
          setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
          setValue(strItemID, "1");
        } else {
          fail("Unexpected strUrl: " + strURL);
        }
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.LIST_ITEM.equals(objectType)) {
          if ("1".equals(itemId)) {
            assertEquals(false, securityOnly);
            assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
            setValue(getContentResult, getContentFolderResponse);
          } else if ("2".equals(itemId)) {
            assertEquals(false, securityOnly);
            assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
            setValue(getContentResult, getContentListItemResponse);
          }
        } else if (ObjectType.LIST_ITEM_ATTACHMENTS.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          assertEquals("2", itemId);
          setValue(getContentResult, getContentListItemAttachmentsResponse);
        } else if (objectType.equals(ObjectType.LIST)) {
          assertEquals(false, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          setValue(getContentResult, getContentListResponse);
        } else {
          fail("Unexpected object type: " + objectType);
          throw new AssertionError();
        }
      }
    };
    final MemberIdMapping memberIdMapping;
    {
      Map<Integer, String> users = new HashMap<Integer, String>();
      Map<Integer, String> groups = new HashMap<Integer, String>();
      users.put(1, "GDC-PSL\\administrator");
      groups.put(3, "SiteCollection Owners");
      groups.put(4, "SiteCollection Visitors");
      groups.put(5, "SiteCollection Members");
      memberIdMapping = new MemberIdMapping(users, groups);
    }

    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), Callables.returning(memberIdMapping),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>List Item Inside Folder</title></head>"
        + "<body><h1>List Item Inside Folder</h1>"
        + "<p>Attachments</p><ul>"
        + "<li><a href=\"../Attachments/2/1046000.pdf\">1046000.pdf</a></li>"
        + "</ul></body></html>";
    final Metadata goldenMetadata;
    {
      Metadata meta = new Metadata();
      meta.add("Attachments", "1");
      meta.add("Author", "System Account");
      meta.add("BaseName", "2_");
      meta.add("ContentType", "Item");
      meta.add("ContentTypeId", "0x0100442459C9B5E59C4F9CFDC789A220FC92");
      meta.add("Created", "2012-05-01T22:14:06Z");
      meta.add("Created_x0020_Date", "2012-05-01T22:14:06Z");
      meta.add("Editor", "System Account");
      meta.add("EncodedAbsUrl", "http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/Test%20Folder/2_.000");
      meta.add("FSObjType", "0");
      meta.add("FileDirRef",
          "sites/SiteCollection/Lists/Custom List/Test Folder");
      meta.add("FileLeafRef", "2_.000");
      meta.add("FileRef",
          "sites/SiteCollection/Lists/Custom List/Test Folder/2_.000");
      meta.add("GUID", "{2C5BEF60-18FA-42CA-B472-7B5E1EC405A5}");
      meta.add("ID", "2");
      meta.add("Last_x0020_Modified", "2012-05-01T22:14:06Z");
      meta.add("LinkFilename", "2_.000");
      meta.add("LinkFilenameNoMenu", "2_.000");
      meta.add("LinkTitle", "Inside Folder");
      meta.add("LinkTitleNoMenu", "Inside Folder");
      meta.add("Modified", "2012-05-04T21:24:32Z");
      meta.add("Order", "200.000000000000");
      meta.add("PermMask", "0x7fffffffffffffff");
      meta.add("ScopeId", "{2E29615C-59E7-493B-B08A-3642949CC069}");
      meta.add("SelectTitle", "2");
      meta.add("ServerRedirected", "0");
      meta.add("ServerUrl",
          "/sites/SiteCollection/Lists/Custom List/Test Folder/2_.000");
      meta.add("Title", "Inside Folder");
      meta.add("UniqueId", "{E7156244-AC2F-4402-AA74-7A365726CD02}");
      meta.add("WorkflowVersion", "1");
      meta.add("_EditMenuTableEnd", "2");
      meta.add("_EditMenuTableStart", "2_.000");
      meta.add("_IsCurrentVersion", "1");
      meta.add("_Level", "1");
      meta.add("_ModerationStatus", "0");
      meta.add("_UIVersion", "512");
      meta.add("_UIVersionString", "1.0");
      meta.add("owshiddenversion", "4");
      goldenMetadata = meta.unmodifiableView();
    }
    assertEquals(golden, responseString);
    assertEquals(goldenMetadata, response.getMetadata());
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection/"
            + "Lists/Custom List/Test Folder"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/DispForm.aspx?ID=2"),
        response.getDisplayUrl());
  }

  @Test
  public void testGetDocContentListItemAnonymousAccess() throws IOException {
    final String getContentListResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-l.xml")
        .replace(
            "AllowAnonymousAccess=\"False\"", "AllowAnonymousAccess=\"True\"")
        .replace("AnonymousViewListItems=\"False\"",
            "AnonymousViewListItems=\"True\"")
        .replace(
            "AnonymousPermMask=\"0\"", "AnonymousPermMask=\"68719546465\"");
    final String getContentSite = loadTestString("sites-SiteCollection-s.xml")
        .replace("AnonymousPermMask=\"0\"", "AnonymousPermMask=\"65536\"");

    final String getContentListItemResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-2-li.xml")
        .replace("ows_Attachments='1'", "ows_Attachments='0'")
        .replace("ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'",
             "ows_ScopeId='2;#{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}'");

    final String getContentVirtualServer = loadTestString("vs.xml");

    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        if (("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder/2_.000").equals(strURL)) {
          setValue(getURLSegmentsResult, true);
          setValue(strWebID, null);
          setValue(strBucketID, null);
          setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
          setValue(strItemID, "2");
        } else if (("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder").equals(strURL)) {
          setValue(getURLSegmentsResult, true);
          setValue(strWebID, null);
          setValue(strBucketID, null);
          setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
          setValue(strItemID, "1");
        } else {
          fail("Unexpected strUrl: " + strURL);
        }
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.LIST_ITEM.equals(objectType)) {
         if ("2".equals(itemId)) {
            assertEquals(false, securityOnly);
            assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
            setValue(getContentResult, getContentListItemResponse);
          } else {
            fail("Unexpected item id: " + itemId);
          }
        } else if (objectType.equals(ObjectType.LIST)) {
          assertEquals(false, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          setValue(getContentResult, getContentListResponse);
        } else if (objectType.equals(ObjectType.SITE)) {
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals(null, objectId);
          setValue(lastItemIdOnPage, null);
          setValue(getContentResult, getContentSite);
        } else if (ObjectType.VIRTUAL_SERVER.equals(objectType)) {
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          setValue(getContentResult, getContentVirtualServer);
        } else {
          fail("Unexpected object type: " + objectType);
        }
      }
    };
    final MemberIdMapping memberIdMapping;
    {
      Map<Integer, String> users = new HashMap<Integer, String>();
      Map<Integer, String> groups = new HashMap<Integer, String>();
      users.put(1, "GDC-PSL\\administrator");
      groups.put(3, "SiteCollection Owners");
      groups.put(4, "SiteCollection Visitors");
      groups.put(5, "SiteCollection Members");
      memberIdMapping = new MemberIdMapping(users, groups);
    }

    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), Callables.returning(memberIdMapping),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    assertNull(response.getAcl());
  }

  @Test
  public void testGetDocContentListItemWithReadSecurity() throws IOException {
    final String getContentListResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-l.xml")
        .replace("ReadSecurity=\"1\"", "ReadSecurity=\"2\"");
    final String getContentListItemResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-2-li.xml")
        .replace("ows_Attachments='1'", "ows_Attachments='0'");

    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        if (("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder/2_.000").equals(strURL)) {
          setValue(getURLSegmentsResult, true);
          setValue(strWebID, null);
          setValue(strBucketID, null);
          setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
          setValue(strItemID, "2");
        } else if (("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder").equals(strURL)) {
          setValue(getURLSegmentsResult, true);
          setValue(strWebID, null);
          setValue(strBucketID, null);
          setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
          setValue(strItemID, "1");
        } else {
          fail("Unexpected strUrl: " + strURL);
        }
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.LIST_ITEM.equals(objectType)) {
          if ("2".equals(itemId)) {
            assertEquals(false, securityOnly);
            assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
            setValue(getContentResult, getContentListItemResponse);
          }
        } else if (objectType.equals(ObjectType.LIST)) {
          assertEquals(false, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          setValue(getContentResult, getContentListResponse);
        } else {
          fail("Unexpected object type: " + objectType);
          throw new AssertionError();
        }
      }
    };
    final MemberIdMapping memberIdMapping;
    {
      Map<Integer, String> users = new HashMap<Integer, String>();
      Map<Integer, String> groups = new HashMap<Integer, String>();
      users.put(1, "GDC-PSL\\administrator");
      groups.put(3, "SiteCollection Owners");
      groups.put(4, "SiteCollection Visitors");
      groups.put(5, "SiteCollection Members");
      memberIdMapping = new MemberIdMapping(users, groups);
    }

    Users users = new Users();
    users.getUser().add(createUserGroupUser(1, "GDC-PSL\\administrator",
        "S-1-5-21-7369146", "Administrator", "admin@domain.com", false, true));
    users.getUser().add(createUserGroupUser(7, "GDC-PSL\\User1",
        "S-1-5-21-736911", "User1", "User1@domain.com", false, false));
    users.getUser().add(createUserGroupUser(9, "GDC-PSL\\User11",
        "S-1-5-21-7369132", "User11", "User11@domain.com", false, false));
    users.getUser().add(createUserGroupUser(1073741823, "System.Account",
        "S-1-5-21-7369343", "System Account", "System.Account@domain.com",
        false, true));

    MockUserGroupFactory mockUserGroupFactory
        = new MockUserGroupFactory(users);

    adaptor = new SharePointAdaptor(new SiteDataFactory() {
      @Override
      public SiteDataSoap newSiteData(String endpoint) {
        return new UnsupportedSiteData();
      }
    },
    mockUserGroupFactory, new UnsupportedHttpClient(),
    new Executor() {
      @Override
      public void execute(Runnable command) {
        command.run();
      }
    });
    final AccumulatingDocIdPusher docIdPusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, null) {
      @Override
      public DocIdPusher getDocIdPusher() {
        return docIdPusher;
      }
    });
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
            + "Test Folder/2_.000"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          mockUserGroupFactory.newUserGroup(
              "http://localhost:1/sites/SiteCollection"),
          Callables.returning(memberIdMapping),
          adaptor.new SiteUserIdMappingCallable(
              "http://localhost:1/sites/SiteCollection"))
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>List Item Inside Folder</title></head>"
        + "<body><h1>List Item Inside Folder</h1>"
        + "</body></html>";

    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"
            + "/Lists/Custom List/Test Folder/2_.000_READ_SECURITY"))
        .setPermitUsers(users("GDC-PSL\\administrator"))
        .setPermitGroups(groups("SiteCollection Owners",
            "SiteCollection Members", "SiteCollection Visitors"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
    assertEquals(Collections.singletonList(Collections.singletonMap(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
            + "Test Folder/2_.000_READ_SECURITY"),
        new Acl.Builder()
            .setEverythingCaseInsensitive()
            .setPermitUsers(users("GDC-PSL\\administrator", "System.Account"))
            .setPermitGroups(groups("SiteCollection Owners"))
            .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT)
            .setInheritFrom(new DocId(""))
            .build())),
        docIdPusher.getNamedResources());
  }

  public void testGetDocContentListItemScopeSameAsParent() throws IOException {
    final String getContentListResponse
        = loadTestString("tapasnay-Lists-Announcements-l.xml");
    final String getContentListItemResponse
        = loadTestString("tapasnay-Lists-Announcements-1-li.xml");
    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        assertEquals("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder/2_.000", strURL);
        setValue(getURLSegmentsResult, true);
        setValue(strWebID, null);
        setValue(strBucketID, null);
        setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
        setValue(strItemID, "2");
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.LIST_ITEM.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          assertEquals("2", itemId);
          setValue(getContentResult, getContentListItemResponse);
        } else if (objectType.equals(ObjectType.LIST)) {
          assertEquals(false, retrieveChildItems);
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          setValue(getContentResult, getContentListResponse);
        } else {
          fail("Unexpected object type: " + objectType);
          throw new AssertionError();
        }
      }
    };
    final MemberIdMapping memberIdMapping;
    {
      Map<Integer, String> users = new HashMap<Integer, String>();
      Map<Integer, String> groups = new HashMap<Integer, String>();
      users.put(1, "SOMEHOST\\administrator");
      memberIdMapping = new MemberIdMapping(users, groups);
    }

    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
        "http://localhost:1/sites/SiteCollection",
        siteData, new UnsupportedUserGroupSoap(),
        Callables.returning(memberIdMapping),
        new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>List Item Get Started with Microsoft SharePoint "
        +   "Foundation!</title></head>"
        + "<body><h1>List Item Get Started with Microsoft SharePoint "
        +   "Foundation!</h1>"
        + "</body></html>";
    final Metadata goldenMetadata;
    {
      Metadata meta = new Metadata();
      meta.add("Attachments", "0");
      meta.add("Author", "GDC-PSL\\administrator");
      meta.add("BaseName", "1_");
      meta.add("Body", "Microsoft SharePoint Foundation helps you to be more "
          + "effective by connecting people, information, and documents. For "
          + "information on getting started, see Help.");
      meta.add("ContentType", "Announcement");
      meta.add("ContentTypeId", "0x0104007B3DA16495E1404895F5E01885B11519");
      meta.add("Created", "2011-09-07T06:49:13Z");
      meta.add("Created_x0020_Date", "2011-09-07T06:49:13Z");
      meta.add("Editor", "GDC-PSL\\administrator");
      meta.add("EncodedAbsUrl", "http://localhost/tapasnay/Lists/Announcements/"
          + "1_.000");
      meta.add("Expires", "2011-09-07T06:49:09Z");
      meta.add("FSObjType", "0");
      meta.add("FileDirRef", "tapasnay/Lists/Announcements");
      meta.add("FileLeafRef", "1_.000");
      meta.add("FileRef", "tapasnay/Lists/Announcements/1_.000");
      meta.add("FolderChildCount", "0");
      meta.add("GUID", "{AAFA6C7F-B734-4981-BCCA-8EFF00A701CB}");
      meta.add("ID", "1");
      meta.add("ItemChildCount", "0");
      meta.add("Last_x0020_Modified", "2011-09-07T06:49:13Z");
      meta.add("LinkFilename", "1_.000");
      meta.add("LinkFilename2", "1_.000");
      meta.add("LinkFilenameNoMenu", "1_.000");
      meta.add("LinkTitle",
          "Get Started with Microsoft SharePoint Foundation!");
      meta.add("LinkTitle2",
          "Get Started with Microsoft SharePoint Foundation!");
      meta.add("LinkTitleNoMenu",
          "Get Started with Microsoft SharePoint Foundation!");
      meta.add("Modified", "2011-09-07T06:49:13Z");
      meta.add("Order", "100.000000000000");
      meta.add("PermMask", "0x7fffffffffffffff");
      meta.add("ScopeId", "{1D857DC3-DD22-4326-95F0-01B27D6DA6D6}");
      meta.add("SelectTitle", "1");
      meta.add("ServerRedirected", "0");
      meta.add("ServerUrl", "/tapasnay/Lists/Announcements/1_.000");
      meta.add("SortBehavior", "0");
      meta.add("Title", "Get Started with Microsoft SharePoint Foundation!");
      meta.add("UniqueId", "{08CCA823-9ECF-4F91-8642-510C568230A9}");
      meta.add("WorkflowVersion", "1");
      meta.add("_EditMenuTableEnd", "1");
      meta.add("_EditMenuTableStart", "1_.000");
      meta.add("_EditMenuTableStart2", "1");
      meta.add("_IsCurrentVersion", "1");
      meta.add("_Level", "1");
      meta.add("_ModerationStatus", "0");
      meta.add("_UIVersion", "512");
      meta.add("_UIVersionString", "1.0");
      meta.add("owshiddenversion", "1");
      goldenMetadata = meta.unmodifiableView();
    }
    assertEquals(golden, responseString);
    assertEquals(goldenMetadata, response.getMetadata());
    // It looks odd that nobody can access the document since there are no
    // groups and users, but the policy permits GDC-PSL\administrator. Thus, the
    // policy's PARENT_OVERRIDE behavior is important.
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId(
            "http://localhost:1/tapasnay/Lists/Announcements"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentFolder() throws IOException {
    final String getContentListItemResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-1-li.xml");
    final String getContentListResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-l.xml");
    final String getContentFolderResponse
        = loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml");
    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getURLSegments(String strURL,
          Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
          Holder<String> strBucketID, Holder<String> strListID,
          Holder<String> strItemID) {
        assertEquals("http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder", strURL);
        setValue(getURLSegmentsResult, true);
        setValue(strWebID, null);
        setValue(strBucketID, null);
        setValue(strListID, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
        setValue(strItemID, "1");
      }

      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.LIST_ITEM.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          assertEquals("1", itemId);
          setValue(getContentResult, getContentListItemResponse);
        } else if (ObjectType.LIST.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          assertEquals(null, itemId);
          setValue(getContentResult, getContentListResponse);
        } else if (ObjectType.FOLDER.equals(objectType)) {
          assertEquals(false, securityOnly);
          assertEquals("{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", objectId);
          assertEquals(null, itemId);
          assertEquals("Test Folder", folderUrl);
          setValue(getContentResult, getContentFolderResponse);
          setValue(lastItemIdOnPage, null);
        } else {
          fail("Unexpected object type: " + objectType);
          throw new AssertionError();
        }
      }
    };
    final MemberIdMapping memberIdMapping;
    {
      Map<Integer, String> users = new HashMap<Integer, String>();
      Map<Integer, String> groups = new HashMap<Integer, String>();
      users.put(1, "GDC-PSL\\administrator");
      groups.put(3, "SiteCollection Owners");
      groups.put(4, "SiteCollection Visitors");
      groups.put(5, "SiteCollection Members");
      memberIdMapping = new MemberIdMapping(users, groups);
    }

    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection",
          siteData, new UnsupportedUserGroupSoap(),
        Callables.returning(memberIdMapping),
        new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>Folder Test Folder</title></head>"
        + "<body><h1>Folder Test Folder</h1>"
        + "<p>List Items</p>"
        + "<ul>"
        + "<li><a href=\"Test%20Folder/2_.000\">Inside Folder</a></li>"
        + "<li><a href=\"Test%20Folder/testing\">testing</a></li>"
        + "</ul></body></html>";
    final Metadata goldenMetadata;
    {
      Metadata meta = new Metadata();
      meta.add("Attachments", "0");
      meta.add("Author", "System Account");
      meta.add("BaseName", "Test Folder");
      meta.add("ContentType", "Folder");
      meta.add("ContentTypeId", "0x01200077DD29735CE61148A73F540231F24430");
      meta.add("Created", "2012-05-01T22:13:47Z");
      meta.add("Created_x0020_Date", "2012-05-01T22:13:47Z");
      meta.add("Editor", "System Account");
      meta.add("EncodedAbsUrl", "http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/Test%20Folder");
      meta.add("FSObjType", "1");
      meta.add("FileDirRef", "sites/SiteCollection/Lists/Custom List");
      meta.add("FileLeafRef", "Test Folder");
      meta.add("FileRef", "sites/SiteCollection/Lists/Custom List/Test Folder");
      meta.add("GUID", "{C099F4ED-6E96-4A00-B94A-EE443061EE49}");
      meta.add("ID", "1");
      meta.add("Last_x0020_Modified", "2012-05-02T21:13:17Z");
      meta.add("LinkFilename", "Test Folder");
      meta.add("LinkFilenameNoMenu", "Test Folder");
      meta.add("LinkTitle", "Test Folder");
      meta.add("LinkTitleNoMenu", "Test Folder");
      meta.add("Modified", "2012-05-01T22:13:47Z");
      meta.add("Order", "100.000000000000");
      meta.add("PermMask", "0x7fffffffffffffff");
      meta.add("ScopeId", "{2E29615C-59E7-493B-B08A-3642949CC069}");
      meta.add("SelectTitle", "1");
      meta.add("ServerRedirected", "0");
      meta.add("ServerUrl",
          "/sites/SiteCollection/Lists/Custom List/Test Folder");
      meta.add("Title", "Test Folder");
      meta.add("UniqueId", "{CE33B6B7-9F5E-4224-8D77-9C42E6290FE6}");
      meta.add("WorkflowVersion", "1");
      meta.add("_EditMenuTableEnd", "1");
      meta.add("_EditMenuTableStart", "Test Folder");
      meta.add("_IsCurrentVersion", "1");
      meta.add("_Level", "1");
      meta.add("_ModerationStatus", "0");
      meta.add("_UIVersion", "512");
      meta.add("_UIVersionString", "1.0");
      meta.add("owshiddenversion", "1");
      goldenMetadata = meta.unmodifiableView();
    }
    assertEquals(golden, responseString);
    assertEquals(goldenMetadata, response.getMetadata());
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId(""))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(groups("SiteCollection Members",
            "SiteCollection Owners", "SiteCollection Visitors"))
        .setPermitUsers(users("GDC-PSL\\administrator")).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/AllItems.aspx?RootFolder=/sites/SiteCollection/"
          + "Lists/Custom%20List/Test%20Folder"),
        response.getDisplayUrl());
  }

  @Test
  public void testGetDocIds() throws IOException, InterruptedException {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(0, pusher.getRecords().size());
    adaptor.getDocIds(pusher);
    assertEquals(1, pusher.getRecords().size());
    assertEquals(new DocIdPusher.Record.Builder(new DocId("")).build(),
        pusher.getRecords().get(0));
  }

  @Test
  public void testModifiedGetDocIds() throws IOException, InterruptedException {
    final String getContentVirtualServer
        = "<VirtualServer>"
        + "<Metadata URL=\"http://localhost:1/\" />"
        + "<ContentDatabases>"
        + "<ContentDatabase ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "<ContentDatabase ID=\"{3ac1e3b3-2326-7341-4afe-16751eafbc51}\" />"
        + "</ContentDatabases>"
        + "<Policies AnonymousGrantMask=\"0\" AnonymousDenyMask=\"0\">"
        + "<PolicyUser LoginName=\"NT AUTHORITY\\LOCAL SERVICE\""
        + " Sid=\"S-1-5-19\" GrantMask=\"4611686224789442657\" DenyMask=\"0\"/>"
        + "<PolicyUser LoginName=\"GDC-PSL\\spuser1\""
        + " Sid=\"S-1-5-21-736914693-3137354690-2813686979-1130\""
        + " GrantMask=\"4611686224789442657\" DenyMask=\"0\"/>"
        + "<PolicyUser LoginName=\"GDC-PSL\\Administrator\""
        + " Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " GrantMask=\"9223372036854775807\" DenyMask=\"0\"/>"
        + "</Policies></VirtualServer>";
    final String getContentVirtualServer2
        = "<VirtualServer>"
        + "<Metadata URL=\"http://localhost:1/\" />"
        + "<ContentDatabases>"
        + "<ContentDatabase ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "</ContentDatabases>"
        + "<Policies AnonymousGrantMask=\"0\" AnonymousDenyMask=\"0\">"
        + "<PolicyUser LoginName=\"NT AUTHORITY\\LOCAL SERVICE\""
        + " Sid=\"S-1-5-19\" GrantMask=\"4611686224789442657\" DenyMask=\"0\"/>"
        + "<PolicyUser LoginName=\"GDC-PSL\\spuser1\""
        + " Sid=\"S-1-5-21-736914693-3137354690-2813686979-1130\""
        + " GrantMask=\"4611686224789442657\" DenyMask=\"0\"/>"
        + "<PolicyUser LoginName=\"GDC-PSL\\Administrator\""
        + " Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " GrantMask=\"9223372036854775807\" DenyMask=\"0\"/>"
        + "</Policies></VirtualServer>";
    final String getContentContentDatabase4fb
        = "<ContentDatabase>"
        + "<Metadata ChangeId=\"1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727"
        +   "056594000000;603\""
        + " ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "</ContentDatabase>";
    final String getContentContentDatabase3ac
        = "<ContentDatabase>"
        + "<Metadata ChangeId=\"1;0;3ac1e3b3-2326-7341-4afe-16751eafbc51;634882"
        +   "028739000000;224\""
        + " ID=\"{3ac1e3b3-2326-7341-4afe-16751eafbc51}\" />"
        + "</ContentDatabase>";
    final String getChangesContentDatabase4fb
        = "<SPContentDatabase Change=\"Unchanged\" ItemCount=\"0\">"
        + "<ContentDatabase>"
        + "<Metadata ChangeId=\"1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727"
        +   "056594000000;603\""
        + " ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "</ContentDatabase></SPContentDatabase>";
    final AtomicLong atomicState = new AtomicLong();
    final AtomicLong atomicNumberGetChangesCalls = new AtomicLong(0);
    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        long state = atomicState.get();
        if (state == 0) {
          throw new WebServiceException("fake IO error");
        } else if (state == 1) {
          setValue(lastItemIdOnPage, null);
          if (ObjectType.VIRTUAL_SERVER.equals(objectType)) {
            assertEquals(true, retrieveChildItems);
            assertEquals(false, securityOnly);
            setValue(getContentResult, getContentVirtualServer);
          } else if (ObjectType.CONTENT_DATABASE.equals(objectType)) {
            if ("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}".equals(objectId)) {
              setValue(getContentResult, getContentContentDatabase4fb);
            } else if ("{3ac1e3b3-2326-7341-4afe-16751eafbc51}"
                .equals(objectId)) {
              setValue(getContentResult, getContentContentDatabase3ac);
            } else {
              throw new AssertionError();
            }
            assertEquals(false, retrieveChildItems);
            assertEquals(false, securityOnly);
          } else {
            throw new AssertionError();
          }
        } else if (state == 2) {
          assertEquals(ObjectType.VIRTUAL_SERVER, objectType);
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          setValue(lastItemIdOnPage, null);
          setValue(getContentResult, getContentVirtualServer2);
        } else {
          throw new AssertionError();
        }
      }

      @Override
      public void getChanges(ObjectType objectType, String contentDatabaseId,
          Holder<String> lastChangeId, Holder<String> currentChangeId,
          Integer timeout, Holder<String> getChangesResult,
          Holder<Boolean> moreChanges) {
        long state = atomicState.get();
        if (state == 0) {
          throw new AssertionError();
        } else if (state == 2) {
          atomicNumberGetChangesCalls.getAndIncrement();
          assertEquals(ObjectType.CONTENT_DATABASE, objectType);
          assertEquals("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}",
              contentDatabaseId);
          assertEquals(
              "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
              lastChangeId.value);
          String newLastChangeId = "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;63"
              + "4727056594000000;603";
          setValue(lastChangeId, newLastChangeId);
          setValue(currentChangeId, newLastChangeId);
          setValue(getChangesResult, getChangesContentDatabase4fb);
          setValue(moreChanges, false);
        } else {
          throw new AssertionError();
        }
      }
    };
    SiteDataFactory siteDataFactory = new SingleSiteDataFactory(siteData,
          "http://localhost:1/_vti_bin/SiteData.asmx");
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    // Error getting content databases, so content databases remains unchanged
    // (empty).
    atomicState.set(0);
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals(0, atomicNumberGetChangesCalls.get());

    // Find new content databases and get their current change id.
    atomicState.set(1);
    adaptor.getModifiedDocIds(pusher);
    assertEquals(1, pusher.getRecords().size());
    assertEquals(new DocIdPusher.Record.Builder(new DocId(""))
        .setCrawlImmediately(true).build(),
        pusher.getRecords().get(0));
    assertEquals(0, atomicNumberGetChangesCalls.get());
    pusher.reset();

    // Discover one content database disappeared; get changes for other content
    // database.
    atomicState.set(2);
    adaptor.getModifiedDocIds(pusher);
    assertEquals(1, pusher.getRecords().size());
    assertEquals(new DocIdPusher.Record.Builder(new DocId(""))
        .setCrawlImmediately(true).build(),
        pusher.getRecords().get(0));
    assertEquals(1, atomicNumberGetChangesCalls.get());
  }

  @Test
  public void testModifiedGetDocIdsSP2010() throws IOException,
         InterruptedException {
    final String getContentVirtualServer
        = "<VirtualServer>"
        + "<Metadata ID=\"{3a125232-0c27-495f-8c92-65ad85b5a17c}\""
        + " Version=\"14.0.4762.1000\" URL=\"http://localhost:1/\""
        + " URLZone=\"Default\" URLIsHostHeader=\"False\" />"
        + "<ContentDatabases>"
        + "<ContentDatabase ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "</ContentDatabases>"
        + "<Policies AnonymousGrantMask=\"0\" AnonymousDenyMask=\"0\">"
        + "<PolicyUser LoginName=\"NT AUTHORITY\\LOCAL SERVICE\""
        + " Sid=\"S-1-5-19\" GrantMask=\"4611686224789442657\" DenyMask=\"0\"/>"
        + "<PolicyUser LoginName=\"GDC-PSL\\spuser1\""
        + " Sid=\"S-1-5-21-736914693-3137354690-2813686979-1130\""
        + " GrantMask=\"4611686224789442657\" DenyMask=\"0\"/>"
        + "<PolicyUser LoginName=\"GDC-PSL\\Administrator\""
        + " Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " GrantMask=\"9223372036854775807\" DenyMask=\"0\"/>"
        + "</Policies></VirtualServer>";
    final String getContentContentDatabase4fb
        = "<ContentDatabase>"
        + "<Metadata ChangeId=\"1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727"
        +   "056594000000;603\""
        + " ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "</ContentDatabase>";
    final String getChangesContentDatabase4fb
        = "<SPContentDatabase Change=\"Unchanged\" ItemCount=\"0\">"
        + "<ContentDatabase>"
        + "<Metadata ChangeId=\"1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf9;634727"
        +   "056595000000;604\""
        + " ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "</ContentDatabase></SPContentDatabase>";
    final AtomicLong atomicNumberGetChangesCalls = new AtomicLong(0);
    SiteDataSoap siteData = new UnsupportedSiteData() {
      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        setValue(lastItemIdOnPage, null);
        if (ObjectType.VIRTUAL_SERVER.equals(objectType)) {
          assertEquals(true, retrieveChildItems);
          assertEquals(false, securityOnly);
          setValue(getContentResult, getContentVirtualServer);
        } else if (ObjectType.CONTENT_DATABASE.equals(objectType)) {
          assertEquals("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}", objectId);
          assertEquals(false, retrieveChildItems);
          assertEquals(false, securityOnly);
          setValue(getContentResult, getContentContentDatabase4fb);
        } else {
          throw new AssertionError();
        }
      }

      @Override
      public void getChanges(ObjectType objectType, String contentDatabaseId,
          Holder<String> lastChangeId, Holder<String> currentChangeId,
          Integer timeout, Holder<String> getChangesResult,
          Holder<Boolean> moreChanges) {
        atomicNumberGetChangesCalls.getAndIncrement();
        // The timeout in SP 2010 is not a timeout and should always be at least
        // 60. Otherwise, you will always get zero results.
        assertTrue(timeout >= 60);
        assertEquals(ObjectType.CONTENT_DATABASE, objectType);
        assertEquals("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}",
            contentDatabaseId);
        assertEquals(
            "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
            lastChangeId.value);
        setValue(currentChangeId, "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf9;634"
            + "727056595000000;604");
        setValue(lastChangeId, currentChangeId.value);
        setValue(getChangesResult, getChangesContentDatabase4fb);
        setValue(moreChanges, false);
      }
    };
    SiteDataFactory siteDataFactory = new SingleSiteDataFactory(siteData,
          "http://localhost:1/_vti_bin/SiteData.asmx");
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    // Initialize changeIds.
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, atomicNumberGetChangesCalls.get());

    // Check for changes. This should not go into an infinite loop.
    adaptor.getModifiedDocIds(pusher);
    assertEquals(1, atomicNumberGetChangesCalls.get());
  }

  @Test
  public void testModifiedGetDocIdsClient() throws IOException,
      InterruptedException {
    final String getChangesContentDatabase
        = loadTestString("testModifiedGetDocIdsClient.changes-cd.xml");
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1/sites/SiteCollection",
        "http://localhost:1/sites/SiteCollection", new UnsupportedSiteData(),
        new UnsupportedUserGroupSoap(),
        new UnsupportedCallable<MemberIdMapping>(),
        new UnsupportedCallable<MemberIdMapping>());

    SPContentDatabase result
        = parseChanges(client, getChangesContentDatabase);

    client.getModifiedDocIds(result, pusher);
    assertEquals(1, pusher.getRecords().size());
    assertEquals(new DocIdPusher.Record.Builder(new DocId(
          "http://localhost:1/Lists/Announcements/2_.000"))
        .setCrawlImmediately(true).build(), pusher.getRecords().get(0));
  }

  @Test
  public void testParseError() throws Exception {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(), new UnsupportedHttpClient(),
        executor);
    adaptor.init(new MockAdaptorContext(config, null));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1", "http://localhost:1",
        new UnsupportedSiteData(), new UnsupportedUserGroupSoap(),
        new UnsupportedCallable<MemberIdMapping>(),
        new UnsupportedCallable<MemberIdMapping>());
    String xml = "<broken";
    thrown.expect(IOException.class);
    client.jaxbParse(xml, SPContentDatabase.class);
  }

  @Test
  public void testValidationError() throws Exception {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(),
        new UnsupportedHttpClient(), executor);
    adaptor.init(new MockAdaptorContext(config, null));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1", "http://localhost:1",
        new UnsupportedSiteData(), new UnsupportedUserGroupSoap(),
        new UnsupportedCallable<MemberIdMapping>(),
        new UnsupportedCallable<MemberIdMapping>());
    // Lacks required child element.
    String xml = "<SPContentDatabase"
        + " xmlns='http://schemas.microsoft.com/sharepoint/soap/'/>";
    thrown.expect(IOException.class);
    client.jaxbParse(xml, SPContentDatabase.class);
  }

  @Test
  public void testDisabledValidation() throws Exception {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(),
        new UnsupportedHttpClient(), executor);
    config.overrideKey("sharepoint.xmlValidation", "false");
    adaptor.init(new MockAdaptorContext(config, null));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1", "http://localhost:1",
        new UnsupportedSiteData(), new UnsupportedUserGroupSoap(),
        new UnsupportedCallable<MemberIdMapping>(),
        new UnsupportedCallable<MemberIdMapping>());
    // Lacks required child element.
    String xml = "<SPContentDatabase"
        + " xmlns='http://schemas.microsoft.com/sharepoint/soap/'/>";
    assertNotNull(client.jaxbParse(xml, SPContentDatabase.class));
  }


  @Test
  public void testParseUnknownXml() throws Exception {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedUserGroupFactory(),
        new UnsupportedHttpClient(), executor);
    adaptor.init(new MockAdaptorContext(config, null));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1", "http://localhost:1",
        new UnsupportedSiteData(), new UnsupportedUserGroupSoap(),
        new UnsupportedCallable<MemberIdMapping>(),
        new UnsupportedCallable<MemberIdMapping>());
    // Valid XML, but not any class that we know about.
    String xml = "<html/>";
    thrown.expect(IOException.class);
    client.jaxbParse(xml, SPContentDatabase.class);
  }

  @Test
  public void testFileInfoGetFirstHeaderWithNameMissing() {
    FileInfo fi = new FileInfo.Builder(new ByteArrayInputStream(new byte[0]))
        .setHeaders(Arrays.asList("Some-Header", "somevalue")).build();
    assertEquals("somevalue", fi.getFirstHeaderWithName("some-heaDer"));
    assertNull(fi.getFirstHeaderWithName("Missing-Header"));
  }

  @Test
  public void testFileInfoNullContents() {
    thrown.expect(NullPointerException.class);
    new FileInfo.Builder(null);
  }

  @Test
  public void testFileInfoNullHeaders() {
    FileInfo.Builder builder
        = new FileInfo.Builder(new ByteArrayInputStream(new byte[0]));
    thrown.expect(NullPointerException.class);
    builder.setHeaders(null);
  }

  @Test
  public void testFileInfoOddHeadersLength() {
    FileInfo.Builder builder
        = new FileInfo.Builder(new ByteArrayInputStream(new byte[0]));
    thrown.expect(IllegalArgumentException.class);
    builder.setHeaders(Arrays.asList("odd-length"));
  }

  private <T> void setValue(Holder<T> holder, T value) {
    if (holder != null) {
      holder.value = value;
    }
  }

  private SPContentDatabase parseChanges(
      SharePointAdaptor.SiteDataClient client, String xml) throws IOException {
    String xmlns = "http://schemas.microsoft.com/sharepoint/soap/";
    xml = xml.replace("<SPContentDatabase ",
        "<SPContentDatabase xmlns='" + xmlns + "' ");
    return client.jaxbParse(xml, SPContentDatabase.class);
  }

  private String loadTestString(String testString) throws IOException {
    return loadResourceAsString("spresponses/" + testString);
  }

  private String loadResourceAsString(String resource) throws IOException {
    return IOHelper.readInputStreamToString(
        getClass().getResourceAsStream(resource), Charset.forName("UTF-8"));
  }

  private static class UnsupportedSiteDataFactory implements SiteDataFactory {
    @Override
    public SiteDataSoap newSiteData(String endpoint) {
      throw new UnsupportedOperationException();
    }
  }

  private static class UnsupportedUserGroupFactory
      implements UserGroupFactory {
    @Override
    public UserGroupSoap newUserGroup(String endpoint) {
      return new UnsupportedUserGroupSoap();
    }
  }

  private static class MockUserGroupFactory implements UserGroupFactory {
    final Users users;
    public MockUserGroupFactory(Users users) {
      this.users = users;
    }

    @Override
    public UserGroupSoap newUserGroup(String endpoint) {
      return new MockUserGroupSoap(users);
    }
  }
  private static class SingleSiteDataFactory implements SiteDataFactory {
    private final SiteDataSoap siteData;
    private final String expectedEndpoint;

    public SingleSiteDataFactory(SiteDataSoap siteData,
        String expectedEndpoint) {
      this.siteData = siteData;
      this.expectedEndpoint = expectedEndpoint;
    }

    @Override
    public SiteDataSoap newSiteData(String endpoint) {
      assertEquals(expectedEndpoint, endpoint);
      return siteData;
    }
  }

  private static class UnsupportedHttpClient implements HttpClient {
    @Override
    public FileInfo issueGetRequest(URL url) {
      throw new UnsupportedOperationException();
    }
  }

  private static class MockUserGroupSoap extends UnsupportedUserGroupSoap {
    final Users users;    
    public MockUserGroupSoap(Users users) {
      this.users = users;      
    }
    
    @Override
    public GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult 
        getUserCollectionFromSite() {
      GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult result 
          = new GetUserCollectionFromSiteResponse
              .GetUserCollectionFromSiteResult();
      GetUserCollectionFromSiteResponse
          .GetUserCollectionFromSiteResult.GetUserCollectionFromSite siteUsers 
          = new GetUserCollectionFromSiteResponse
              .GetUserCollectionFromSiteResult.GetUserCollectionFromSite();   
      siteUsers.setUsers(users);
      result.setGetUserCollectionFromSite(siteUsers);
      return result;      
    }
  }
  
  private static class UnsupportedUserGroupSoap implements UserGroupSoap {
    @Override
    public GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult 
        getUserCollectionFromSite() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetUserCollectionFromWebResponse.GetUserCollectionFromWebResult 
        getUserCollectionFromWeb() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetAllUserCollectionFromWebResponse.GetAllUserCollectionFromWebResult 
        getAllUserCollectionFromWeb() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetUserCollectionFromGroupResponse.GetUserCollectionFromGroupResult 
        getUserCollectionFromGroup(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetUserCollectionFromRoleResponse.GetUserCollectionFromRoleResult 
        getUserCollectionFromRole(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetUserCollectionResponse.GetUserCollectionResult 
        getUserCollection(GetUserCollection.UserLoginNamesXml ulnx) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetUserInfoResponse.GetUserInfoResult 
        getUserInfo(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetCurrentUserInfoResponse.GetCurrentUserInfoResult 
        getCurrentUserInfo() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addUserToGroup(String string, String string1, 
        String string2, String string3, String string4) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addUserCollectionToGroup(String string,
        AddUserCollectionToGroup.UsersInfoXml uix) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addUserToRole(String string, String string1,
        String string2, String string3, String string4) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addUserCollectionToRole(String string,
        AddUserCollectionToRole.UsersInfoXml uix) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void updateUserInfo(String string, String string1,
        String string2, String string3) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeUserFromSite(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeUserCollectionFromSite(
        RemoveUserCollectionFromSite.UserLoginNamesXml ulnx) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeUserFromWeb(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeUserFromGroup(String string, String string1) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeUserCollectionFromGroup(String string,
        RemoveUserCollectionFromGroup.UserLoginNamesXml ulnx) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeUserFromRole(String string, String string1) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeUserCollectionFromRole(String string,
        RemoveUserCollectionFromRole.UserLoginNamesXml ulnx) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetGroupCollectionFromSiteResponse.GetGroupCollectionFromSiteResult
        getGroupCollectionFromSite() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetGroupCollectionFromWebResponse.GetGroupCollectionFromWebResult
        getGroupCollectionFromWeb() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetGroupCollectionFromRoleResponse.GetGroupCollectionFromRoleResult
        getGroupCollectionFromRole(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetGroupCollectionFromUserResponse.GetGroupCollectionFromUserResult
        getGroupCollectionFromUser(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetGroupCollectionResponse.GetGroupCollectionResult
        getGroupCollection(GroupsInputType git) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetGroupInfoResponse.GetGroupInfoResult
        getGroupInfo(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addGroup(String string, String string1, PrincipalType pt,
        String string2, String string3) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addGroupToRole(String string, String string1) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void updateGroupInfo(String string, String string1,
        String string2, PrincipalType pt, String string3) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeGroup(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeGroupFromRole(String string, String string1) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetRoleCollectionFromWebResponse.GetRoleCollectionFromWebResult
        getRoleCollectionFromWeb() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetRoleCollectionFromGroupResponse.GetRoleCollectionFromGroupResult
        getRoleCollectionFromGroup(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetRoleCollectionFromUserResponse.GetRoleCollectionFromUserResult
        getRoleCollectionFromUser(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetRoleCollectionResponse.GetRoleCollectionResult 
        getRoleCollection(RolesInputType rit) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public RoleOutputType getRoleInfo(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addRole(String string, String string1, int i) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void addRoleDef(String string, String string1, BigInteger bi) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void updateRoleInfo(String string, String string1,
        String string2, int i) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void updateRoleDefInfo(String string, String string1, 
        String string2, BigInteger bi) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public void removeRole(String string) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetUserLoginFromEmailResponse.GetUserLoginFromEmailResult 
        getUserLoginFromEmail(EmailsInputType eit) {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetRolesAndPermissionsForCurrentUserResponse
        .GetRolesAndPermissionsForCurrentUserResult 
        getRolesAndPermissionsForCurrentUser() {
      throw new UnsupportedOperationException(); 
    }

    @Override
    public GetRolesAndPermissionsForSiteResponse
        .GetRolesAndPermissionsForSiteResult 
        getRolesAndPermissionsForSite() {
      throw new UnsupportedOperationException(); 
    }    
  }

  /**
   * Throw UnsupportedOperationException for all calls.
   */
  private static class UnsupportedSiteData implements SiteDataSoap {
    @Override
    public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
        Holder<String> strSite, Holder<String> strWeb) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getSite(Holder<Long> getSiteResult,
        Holder<SSiteMetadata> sSiteMetadata, Holder<ArrayOfSWebWithTime> vWebs,
        Holder<String> strUsers, Holder<String> strGroups,
        Holder<ArrayOfString> vGroups) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getWeb(Holder<Long> getWebResult,
        Holder<SWebMetadata> sWebMetadata, Holder<ArrayOfSWebWithTime> vWebs,
        Holder<ArrayOfSListWithTime> vLists, Holder<ArrayOfSFPUrl> vFPUrls,
        Holder<String> strRoles, Holder<ArrayOfString> vRolesUsers,
        Holder<ArrayOfString> vRolesGroups) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getList(String strListName, Holder<Long> getListResult,
        Holder<SListMetadata> sListMetadata,
        Holder<ArrayOfSProperty> vProperties) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getListItems(String strListName, String strQuery,
        String strViewFields, long uRowLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void enumerateFolder(String strFolderUrl,
        Holder<Long> enumerateFolderResult, Holder<ArrayOfSFPUrl> vUrls) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getAttachments(String strListName, String strItemId,
        Holder<Long> getAttachmentsResult, Holder<ArrayOfString> vAttachments) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getURLSegments(String strURL,
        Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
        Holder<String> strBucketID, Holder<String> strListID,
        Holder<String> strItemID) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getListCollection(Holder<Long> getListCollectionResult,
        Holder<ArrayOfSList> vLists) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getContent(ObjectType objectType, String objectId,
        String folderUrl, String itemId, boolean retrieveChildItems,
        boolean securityOnly, Holder<String> lastItemIdOnPage,
        Holder<String> getContentResult) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getSiteUrl(String url, Holder<Long> getSiteUrlResult,
        Holder<String> siteUrl, Holder<String> siteId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getChanges(ObjectType objectType, String contentDatabaseId,
        Holder<String> lastChangeId, Holder<String> currentChangeId,
        Integer timeout, Holder<String> getChangesResult,
        Holder<Boolean> moreChanges) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getChangesEx(int version, String xmlInput) {
      throw new UnsupportedOperationException();
    }
  }

  private static class UnsupportedCallable<V> implements Callable<V> {
    @Override
    public V call() {
      throw new UnsupportedOperationException();
    }
  }
  
  private static class UnsupportedExecutor implements Executor {
    @Override
    public void execute(Runnable command) {
      throw new UnsupportedOperationException();
    }
  }
}
