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
import com.google.enterprise.adaptor.Metadata;

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

import org.junit.*;
import org.junit.rules.ExpectedException;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;
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

  @Test
  public void testConstructor() {
    new SharePointAdaptor();
  }

  @Test
  public void testNullSiteDataFactory() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(null, new UnsupportedHttpClient());
  }

  @Test
  public void testNullHttpClient() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(new UnsupportedSiteDataFactory(), null);
  }

  @Test
  public void testInitDestroy() throws IOException {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedHttpClient());
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
        new UnsupportedHttpClient());
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
        new UnsupportedHttpClient());
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
    final String getContentContentDatabase
        = "<ContentDatabase>"
        + "<Metadata ChangeId=\"1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727"
        +   "056594000000;603\""
        + " ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "<Sites>"
        + "<Site URL=\"http://localhost:1\""
        + " ID=\"{bb3bb2dd-6ea7-471b-a361-6fb67988755c}\" />"
        + "<Site URL=\"http://localhost:1/sites/SiteCollection\""
        + " ID=\"{5cbcd3b1-fca9-48b2-92db-3b5de26f837d}\" />"
        + "</Sites></ContentDatabase>";
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
        new UnsupportedHttpClient());
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
    assertEquals(new Acl.Builder()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList("GDC-PSL\\Administrator",
            "GDC-PSL\\spuser1", "NT AUTHORITY\\LOCAL SERVICE")).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentSiteCollection() throws IOException {
    final String getContentSiteCollection
        = "<Site>"
        + "<Metadata URL=\"http://localhost:1\""
        + " ID=\"{bb3bb2dd-6ea7-471b-a361-6fb67988755c}\""
        + " LastModified=\"2012-06-25 22:29:58Z\" PortalURL=\"\""
        + " UserProfileGUID=\"\""
        + " RootWebId=\"{b2ea1067-3a54-4ab7-a459-c8ec864b97eb}\""
        + " ChangeId=\"1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;634762601982930"
        +   "000;726\" />"
        + "<Groups><Group>"
        + "<Group ID=\"3\" Name=\"chinese1 Owners\""
        + " Description=\"Use this group to give people full control permission"
        +   "s to the SharePoint site: chinese1\" OwnerID=\"3\""
        + " OwnerIsUser=\"False\" />"
        + "<Users>"
        + "<User ID=\"1\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " Name=\"GDC-PSL\\administrator\""
        + " LoginName=\"GDC-PSL\\administrator\" Email=\"\" Notes=\"\""
        + " IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "</Users></Group><Group>"
        + "<Group ID=\"5\" Name=\"chinese1 Members\""
        + " Description=\"Use this group to give people contribute permissions "
        +   "to the SharePoint site: chinese1\" OwnerID=\"3\""
        + " OwnerIsUser=\"False\" />"
        + "<Users>"
        + "<User ID=\"6\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-1132\""
        + " Name=\"spuser2\" LoginName=\"GDC-PSL\\spuser2\" Email=\"\""
        + " Notes=\"\" IsSiteAdmin=\"False\" IsDomainGroup=\"False\" />"
        + "<User ID=\"7\" Sid=\"S-1-5-32-545\" Name=\"BUILTIN\\users\""
        + " LoginName=\"BUILTIN\\users\" Email=\"\" Notes=\"\""
        + " IsSiteAdmin=\"False\" IsDomainGroup=\"True\" />"
        + "<User ID=\"9\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-1134\""
        + " Name=\"spuser4\" LoginName=\"GDC-PSL\\spuser4\" Email=\"\""
        + " Notes=\"\" IsSiteAdmin=\"False\" IsDomainGroup=\"False\" />"
        + "</Users></Group><Group>"
        + "<Group ID=\"4\" Name=\"chinese1 Visitors\""
        + " Description=\"Use this group to give people read permissions to the"
        + " SharePoint site: chinese1\" OwnerID=\"3\" OwnerIsUser=\"False\" />"
        + "<Users /></Group></Groups>"
        + "<Web>"
        + "<Metadata URL=\"http://localhost:1\""
        + " LastModified=\"2012-06-25 22:29:58Z\""
        + " Created=\"2011-10-14 18:59:25Z\""
        + " ID=\"{b2ea1067-3a54-4ab7-a459-c8ec864b97eb}\" Title=\"chinese1\""
        + " Description=\"\" Author=\"GDC-PSL\\administrator\""
        + " Language=\"1033\" CRC=\"1158260233\" NoIndex=\"False\""
        + " DefaultHomePage=\"\" ExternalSecurity=\"False\""
        + " ScopeID=\"{01abac8c-66c8-4fed-829c-8dd02bbf40dd}\""
        + " AllowAnonymousAccess=\"False\" AnonymousViewListItems=\"False\""
        + " AnonymousPermMask=\"0\" />"
        + "<Users>"
        + "<User ID=\"1\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " Name=\"GDC-PSL\\administrator\""
        + " LoginName=\"GDC-PSL\\administrator\" Email=\"\" Notes=\"\""
        + " IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "<User ID=\"2\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-1130\""
        + " Name=\"spuser1\" LoginName=\"GDC-PSL\\spuser1\" Email=\"\""
        + " Notes=\"\" IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "<User ID=\"6\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-1132\""
        + " Name=\"spuser2\" LoginName=\"GDC-PSL\\spuser2\" Email=\"\""
        + " Notes=\"\" IsSiteAdmin=\"False\" IsDomainGroup=\"False\" />"
        + "</Users>"
        + "<ACL><permissions>"
        + "<permission memberid='2' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "<permission memberid='6' mask='206292717568' />"
        + "</permissions></ACL>"
        + "<Webs>"
        + "<Web URL=\"http://localhost:1/somesite\""
        + " ID=\"{ee63e7d0-da23-4553-9f14-359f1cc1bf1c}\""
        + " LastModified=\"2012-06-25 22:29:58Z\" />"
        + "</Webs><Lists>"
        + "<List ID=\"{133fcb96-7e9b-46c9-b5f3-09770a35ad8a}\""
        + " LastModified=\"2012-06-01 22:00:07Z\""
        + " DefaultViewUrl=\"/Lists/Announcements/AllItems.aspx\" />"
        + "<List ID=\"{648f6636-3d90-4565-86b9-2dd7611fc855}\""
        + " LastModified=\"2012-06-01 22:39:22Z\""
        + " DefaultViewUrl=\"/Shared Documents/Forms/AllItems.aspx\" />"
        + "</Lists>"
        + "<FPFolder><Folders>"
        + "<Folder URL=\"Lists\" ID=\"{c2c6cfcc-439e-4372-8a7a-87bec657eebf}\""
        + " LastModified=\"2012-06-25 22:29:58Z\" />"
        + "</Folders><Files>"
        + "<File URL=\"default.aspx\""
        + " ID=\"{1bdad8a3-376d-448c-b9c3-de91a6152687}\""
        + " LastModified=\"2012-06-25 22:29:58Z\" />"
        + "</Files></FPFolder></Web></Site>";
    final String getContentSite
        = "<Web>"
        + "<Metadata URL=\"http://localhost:1\""
        + " LastModified=\"2012-05-15 19:07:39Z\""
        + " Created=\"2011-10-14 18:59:25Z\""
        + " ID=\"{b2ea1067-3a54-4ab7-a459-c8ec864b97eb}\""
        + " Title=\"chinese1\" Description=\"\""
        + " Author=\"GDC-PSL\\administrator\" Language=\"1033\""
        + " CRC=\"558566148\" NoIndex=\"False\" DefaultHomePage=\"\""
        + " ExternalSecurity=\"False\""
        + " ScopeID=\"{01abac8c-66c8-4fed-829c-8dd02bbf40dd}\""
        + " AllowAnonymousAccess=\"False\" AnonymousViewListItems=\"False\""
        + " AnonymousPermMask=\"0\" />"
        + "<Users>"
        + "<User ID=\"1\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " Name=\"GDC-PSL\\administrator\""
        + " LoginName=\"GDC-PSL\\administrator\" Email=\"\" Notes=\"\""
        + " IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "<User ID=\"2\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-1130\""
        + " Name=\"spuser1\" LoginName=\"GDC-PSL\\spuser1\" Email=\"\""
        + " Notes=\"\" IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "</Users>"
        + "<ACL><permissions>"
        + "<permission memberid='2' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions>"
        + "</ACL>"
        + "<Webs>"
        + "<Web URL=\"http://localhost:1/somesite\""
        + " ID=\"{ee63e7d0-da23-4553-9f14-359f1cc1bf1c}\""
        + " LastModified=\"2012-05-15 19:07:39Z\" />"
        + "</Webs><Lists>"
        + "<List ID=\"{133fcb96-7e9b-46c9-b5f3-09770a35ad8a}\""
        + " LastModified=\"2012-05-15 18:21:38Z\""
        + " DefaultViewUrl=\"/Lists/Announcements/AllItems.aspx\" />"
        + "<List ID=\"{648f6636-3d90-4565-86b9-2dd7611fc855}\""
        + " LastModified=\"2012-05-15 19:07:40Z\""
        + " DefaultViewUrl=\"/Shared Documents/Forms/AllItems.aspx\" />"
        + "</Lists>"
        + "<FPFolder><Folders>"
        + "<Folder URL=\"Lists\" ID=\"{c2c6cfcc-439e-4372-8a7a-87bec657eebf}\""
        + " LastModified=\"2012-05-15 19:07:39Z\" />"
        + "</Folders><Files>"
        + "<File URL=\"default.aspx\""
        + " ID=\"{1bdad8a3-376d-448c-b9c3-de91a6152687}\""
        + " LastModified=\"2012-05-15 19:07:39Z\" />"
        + "</Files></FPFolder></Web>";
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
    }, new UnsupportedHttpClient());
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
        + "<ul><li><a href=\"../somesite\">"
        + "http://localhost:1/somesite</a></li></ul>"
        + "<p>Lists</p>"
        + "<ul><li><a href=\"../Lists/Announcements/AllItems.aspx\">"
        + "/Lists/Announcements/AllItems.aspx</a></li>"
        + "<li><a href=\"../Shared%20Documents/Forms/AllItems.aspx\">"
        + "/Shared Documents/Forms/AllItems.aspx</a>"
        + "</li></ul>"
        + "<p>Folders</p>"
        + "<ul><li><a href=\"SiteCollection/Lists\">Lists</a></li></ul>"
        + "<p>List Items</p>"
        + "<ul><li><a href=\"SiteCollection/default.aspx\">"
        + "default.aspx</a></li></ul>"
        + "</body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId(""))
        .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT)
        .setPermitGroups(Arrays.asList("chinese1 Members", "chinese1 Owners",
            "chinese1 Visitors"))
        .setPermitUsers(Arrays.asList("GDC-PSL\\spuser1")).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentList() throws IOException {
    final String getContentListResponse
        = "<List>"
        + "<Metadata ID=\"{6f33949a-b3ff-4b0c-ba99-93cb518ac2c0}\""
        + " LastModified=\"2012-05-04 21:24:32Z\" Title=\"Custom List\""
        + " DefaultTitle=\"True\" Description=\"\" BaseType=\"GenericList\""
        + " BaseTemplate=\"GenericList\""
        + " DefaultViewUrl=\"/sites/SiteCollection/Lists/Custom List/AllItems.a"
        +   "spx\""
        + " DefaultViewItemUrl=\"/sites/SiteCollection/Lists/Custom List/DispFo"
        +   "rm.aspx\""
        + " RootFolder=\"Lists/Custom List\" Author=\"System Account\""
        + " ItemCount=\"7\" ReadSecurity=\"1\" AllowAnonymousAccess=\"False\""
        + " AnonymousViewListItems=\"False\" AnonymousPermMask=\"0\""
        + " CRC=\"1334405648\" NoIndex=\"False\""
        + " ScopeID=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\" />"
        + "<ACL><permissions>"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></ACL>"
        + "<Views>"
        + "<View URL=\"Lists/Custom List/AllItems.aspx\""
        + " ID=\"{18b67349-78bd-49a2-ba1a-cdbc048adf0b}\" Title=\"All Items\""
        + " />"
        + "</Views>"
        + "<Schema>"
        + "<Field Name=\"Title\" Title=\"Title\" Type=\"Text\" />"
        + "<Field Name=\"Additional_x0020_Info\" Title=\"Additional Info\""
        + " Type=\"Text\" />"
        + "<Field Name=\"ContentType\" Title=\"Content Type\" Type=\"Choice\""
        + " />"
        + "<Field Name=\"ID\" Title=\"ID\" Type=\"Counter\" />"
        + "<Field Name=\"Modified\" Title=\"Modified\" Type=\"DateTime\" />"
        + "<Field Name=\"Created\" Title=\"Created\" Type=\"DateTime\" />"
        + "<Field Name=\"Author\" Title=\"Created By\" Type=\"User\" />"
        + "<Field Name=\"Editor\" Title=\"Modified By\" Type=\"User\" />"
        + "<Field Name=\"_UIVersionString\" Title=\"Version\" Type=\"Text\" />"
        + "<Field Name=\"Attachments\" Title=\"Attachments\""
        + " Type=\"Attachments\" />"
        + "<Field Name=\"Edit\" Title=\"Edit\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitleNoMenu\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitle\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"DocIcon\" Title=\"Type\" Type=\"Computed\" />"
        + "</Schema></List>";
    final String getContentFolderResponse
        = "<Folder><Metadata>"
        + "<scope id=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\">"
        + "<permissions>"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions>"
        + "</scope>"
        + "</Metadata>"
        + "<xml xmlns:s='uuid:BDC6E3F0-6DA3-11d1-A2A3-00AA00C14882'"
        + " xmlns:dt='uuid:C2F41010-65B3-11d1-A29F-00AA00C14882'"
        + " xmlns:rs='urn:schemas-microsoft-com:rowset'"
        + " xmlns:z='#RowsetSchema'>"
        + "<s:Schema id='RowsetSchema'>"
        + "<s:ElementType name='row' content='eltOnly' rs:CommandTimeout='30'>"
        + "<s:AttributeType name='ows_ContentTypeId' rs:name='Content Type ID'"
        + " rs:number='1'>"
        + "<s:datatype dt:type='int' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Title' rs:name='Title' rs:number='2'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationComments'"
        + " rs:name='Approver Comments' rs:number='3'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_File_x0020_Type' rs:name='File Type'"
        + " rs:number='4'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Additional_x0020_Info'"
        + " rs:name='Additional Info' rs:number='5'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ContentType' rs:name='Content Type'"
        + " rs:number='6'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ID' rs:name='ID' rs:number='7'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Modified' rs:name='Modified'"
        + " rs:number='8'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created' rs:name='Created' rs:number='9'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Author' rs:name='Created By'"
        + " rs:number='10'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Editor' rs:name='Modified By'"
        + " rs:number='11'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__HasCopyDestinations'"
        + " rs:name='Has Copy Destinations' rs:number='12'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__CopySource' rs:name='Copy Source'"
        + " rs:number='13'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_owshiddenversion'"
        + " rs:name='owshiddenversion' rs:number='14'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowVersion'"
        + " rs:name='Workflow Version' rs:number='15'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersion' rs:name='UI Version'"
        + " rs:number='16'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersionString' rs:name='Version'"
        + " rs:number='17'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Attachments' rs:name='Attachments'"
        + " rs:number='18'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationStatus'"
        + " rs:name='Approval Status' rs:number='19'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitleNoMenu' rs:name='Title'"
        + " rs:number='20'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitle' rs:name='Title'"
        + " rs:number='21'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_SelectTitle' rs:name='Select'"
        + " rs:number='22'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_InstanceID' rs:name='Instance ID'"
        + " rs:number='23'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Order' rs:name='Order' rs:number='24'>"
        + "<s:datatype dt:type='float' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_GUID' rs:name='GUID' rs:number='25'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowInstanceID'"
        + " rs:name='Workflow Instance ID' rs:number='26'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileRef' rs:name='URL Path'"
        + " rs:number='27'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileDirRef' rs:name='Path'"
        + " rs:number='28'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Last_x0020_Modified' rs:name='Modified'"
        + " rs:number='29'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created_x0020_Date' rs:name='Created'"
        + " rs:number='30'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FSObjType' rs:name='Item Type'"
        + " rs:number='31'>"
        + "<s:datatype dt:type='ui1' dt:lookup='true' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_PermMask'"
        + " rs:name='Effective Permissions Mask' rs:number='32'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileLeafRef' rs:name='Name'"
        + " rs:number='33'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_UniqueId' rs:name='Unique Id'"
        + " rs:number='34'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ProgId' rs:name='ProgId' rs:number='35'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ScopeId' rs:name='ScopeId'"
        + " rs:number='36'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_HTML_x0020_File_x0020_Type'"
        + " rs:name='HTML File Type' rs:number='37'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableStart'"
        + " rs:name='Edit Menu Table Start' rs:number='38'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableEnd'"
        + " rs:name='Edit Menu Table End' rs:number='39'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilenameNoMenu' rs:name='Name'"
        + " rs:number='40'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilename' rs:name='Name'"
        + " rs:number='41'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_DocIcon' rs:name='Type' rs:number='42'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ServerUrl' rs:name='Server Relative URL'"
        + " rs:number='43'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_EncodedAbsUrl'"
        + " rs:name='Encoded Absolute URL' rs:number='44'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_BaseName' rs:name='File Name'"
        + " rs:number='45'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_MetaInfo' rs:name='Property Bag'"
        + " rs:number='46'>"
        + "<s:datatype dt:type='int' dt:lookup='true' dt:maxLength='2147483646'"
        + " />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__Level' rs:name='Level' rs:number='47'>"
        + "<s:datatype dt:type='ui1' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__IsCurrentVersion'"
        + " rs:name='Is Current Version' rs:number='48'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "</s:ElementType></s:Schema><scopes>"
        + "<scope id='{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}' >"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "<scope id='{d3a69dbf-b1ee-4b8d-ad30-5f64b661bf41}' >"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "</scope>"
        + "</scopes>"
        + "<rs:data ItemCount=\"4\">"
        + "<z:row ows_ContentTypeId='0x0100442459C9B5E59C4F9CFDC789A220FC92'"
        + " ows_Title='Outside Folder' ows_ContentType='Item' ows_ID='3'"
        + " ows_Modified='2012-05-01T22:14:17Z'"
        + " ows_Created='2012-05-01T22:14:17Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='1'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='0'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='Outside Folder'"
        + " ows_LinkTitle='Outside Folder' ows_SelectTitle='3'"
        + " ows_Order='300.000000000000'"
        + " ows_GUID='{10E17D90-375F-47A5-94EE-6E75A3EF0E2D}'"
        + " ows_FileRef='3;#sites/SiteCollection/Lists/Custom List/3_.000'"
        + " ows_FileDirRef='3;#sites/SiteCollection/Lists/Custom List'"
        + " ows_Last_x0020_Modified='3;#2012-05-01T22:14:17Z'"
        + " ows_Created_x0020_Date='3;#2012-05-01T22:14:17Z'"
        + " ows_FSObjType='3;#0' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='3;#3_.000'"
        + " ows_UniqueId='3;#{FD87F56D-DBE1-4EB1-8379-0B83082615E0}'"
        + " ows_ProgId='3;#'"
        + " ows_ScopeId='3;#{F9CB02B3-7F29-4CAC-804F-BA6E14F1EB39}'"
        + " ows__EditMenuTableStart='3_.000' ows__EditMenuTableEnd='3'"
        + " ows_LinkFilenameNoMenu='3_.000' ows_LinkFilename='3_.000'"
        + " ows_ServerUrl='/sites/SiteCollection/Lists/Custom List/3_.000'"
        + " ows_EncodedAbsUrl='http://w2k2r2-sp07-2.gdc-psl.net:2900/sites/Site"
        +   "Collection/Lists/Custom%20List/3_.000' ows_BaseName='3_'"
        + " ows_MetaInfo='3;#' ows__Level='1' ows__IsCurrentVersion='1'"
        + " ows_ServerRedirected='0'/>"
        + "<z:row ows_ContentTypeId='0x01200077DD29735CE61148A73F540231F24430'"
        + " ows_Title='Test Folder' ows_ContentType='Folder' ows_ID='1'"
        + " ows_Modified='2012-05-01T22:13:47Z'"
        + " ows_Created='2012-05-01T22:13:47Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='1'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='0'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='Test Folder'"
        + " ows_LinkTitle='Test Folder' ows_SelectTitle='1'"
        + " ows_Order='100.000000000000'"
        + " ows_GUID='{C099F4ED-6E96-4A00-B94A-EE443061EE49}'"
        + " ows_FileRef='1;#sites/SiteCollection/Lists/Custom List/Test Folder'"
        + " ows_FileDirRef='1;#sites/SiteCollection/Lists/Custom List'"
        + " ows_Last_x0020_Modified='1;#2012-05-02T21:13:17Z'"
        + " ows_Created_x0020_Date='1;#2012-05-01T22:13:47Z'"
        + " ows_FSObjType='1;#1' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='1;#Test Folder'"
        + " ows_UniqueId='1;#{CE33B6B7-9F5E-4224-8D77-9C42E6290FE6}'"
        + " ows_ProgId='1;#'"
        + " ows_ScopeId='1;#{D3A69DBF-B1EE-4B8D-AD30-5F64B661BF41}'"
        + " ows__EditMenuTableStart='Test Folder' ows__EditMenuTableEnd='1'"
        + " ows_LinkFilenameNoMenu='Test Folder' ows_LinkFilename='Test Folder'"
        + " ows_ServerUrl='/sites/SiteCollection/Lists/Custom List/Test Folder'"
        + " ows_EncodedAbsUrl='http://w2k2r2-sp07-2.gdc-psl.net:2900/sites/Site"
        +   "Collection/Lists/Custom%20List/Test%20Folder'"
        + " ows_BaseName='Test Folder' ows_MetaInfo='1;#' ows__Level='1'"
        + " ows__IsCurrentVersion='1' ows_ServerRedirected='0'/>"
        + "</rs:data>"
        + "</xml></Folder>";
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
        new UnsupportedHttpClient());
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "AllItems.aspx"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection",
          siteData, Callables.returning(memberIdMapping))
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
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"))
        .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT)
        .setPermitGroups(Arrays.asList("SiteCollection Members",
            "SiteCollection Owners", "SiteCollection Visitors")).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentAttachment() throws IOException {
    final String site = "http://localhost:1";
    final String attachmentId = site + "/Lists/Custom List/Attachments/2/104600"
        + "0.pdf";
    final String listId = site + "/Lists/Custom List/AllItems.aspx";
    final String listGuid = "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}";
    final String getContentListItemAttachments
        = "<Item Count=\"1\">"
        + "<Attachment URL=\"http://localhost:1/Lists/Custom List/Attachments/2"
        +     "/1046000.pdf\" />"
        + "</Item>";
    final String getContentListItemResponse
        = "<Item>"
        + "<Metadata>"
        + "<scope id=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\"><permissions>"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></scope>"
        + "</Metadata>"
        + "<xml xmlns:s='uuid:BDC6E3F0-6DA3-11d1-A2A3-00AA00C14882'"
        + " xmlns:dt='uuid:C2F41010-65B3-11d1-A29F-00AA00C14882'"
        + " xmlns:rs='urn:schemas-microsoft-com:rowset'"
        + " xmlns:z='#RowsetSchema'>"
        + "<s:Schema id='RowsetSchema'>"
        + "<s:ElementType name='row' content='eltOnly' rs:CommandTimeout='30'>"
        + "<s:AttributeType name='ows_ContentTypeId' rs:name='Content Type ID'"
        + " rs:number='1'>"
        + "<s:datatype dt:type='int' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Title' rs:name='Title' rs:number='2'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationComments'"
        + " rs:name='Approver Comments' rs:number='3'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_File_x0020_Type' rs:name='File Type'"
        + " rs:number='4'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Additional_x0020_Info'"
        + " rs:name='Additional Info' rs:number='5'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ContentType' rs:name='Content Type'"
        + " rs:number='6'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ID' rs:name='ID' rs:number='7'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Modified' rs:name='Modified'"
        + " rs:number='8'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created' rs:name='Created' rs:number='9'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Author' rs:name='Created By'"
        + " rs:number='10'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Editor' rs:name='Modified By'"
        + " rs:number='11'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__HasCopyDestinations'"
        + " rs:name='Has Copy Destinations' rs:number='12'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__CopySource' rs:name='Copy Source'"
        + " rs:number='13'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_owshiddenversion'"
        + " rs:name='owshiddenversion' rs:number='14'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowVersion'"
        + " rs:name='Workflow Version' rs:number='15'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersion' rs:name='UI Version'"
        + " rs:number='16'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersionString' rs:name='Version'"
        + " rs:number='17'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Attachments' rs:name='Attachments'"
        + " rs:number='18'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationStatus'"
        + " rs:name='Approval Status' rs:number='19'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitleNoMenu' rs:name='Title'"
        + " rs:number='20'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitle' rs:name='Title'"
        + " rs:number='21'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_SelectTitle' rs:name='Select'"
        + " rs:number='22'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_InstanceID' rs:name='Instance ID'"
        + " rs:number='23'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Order' rs:name='Order' rs:number='24'>"
        + "<s:datatype dt:type='float' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_GUID' rs:name='GUID' rs:number='25'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowInstanceID'"
        + " rs:name='Workflow Instance ID' rs:number='26'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileRef' rs:name='URL Path'"
        + " rs:number='27'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileDirRef' rs:name='Path'"
        + " rs:number='28'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Last_x0020_Modified' rs:name='Modified'"
        + " rs:number='29'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created_x0020_Date' rs:name='Created'"
        + " rs:number='30'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FSObjType' rs:name='Item Type'"
        + " rs:number='31'>"
        + "<s:datatype dt:type='ui1' dt:lookup='true' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_PermMask'"
        + " rs:name='Effective Permissions Mask' rs:number='32'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileLeafRef' rs:name='Name'"
        + " rs:number='33'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_UniqueId' rs:name='Unique Id'"
        + " rs:number='34'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ProgId' rs:name='ProgId' rs:number='35'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ScopeId' rs:name='ScopeId'"
        + " rs:number='36'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_HTML_x0020_File_x0020_Type'"
        + " rs:name='HTML File Type' rs:number='37'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableStart'"
        + " rs:name='Edit Menu Table Start' rs:number='38'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableEnd'"
        + " rs:name='Edit Menu Table End' rs:number='39'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilenameNoMenu' rs:name='Name'"
        + " rs:number='40'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilename' rs:name='Name'"
        + " rs:number='41'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_DocIcon' rs:name='Type' rs:number='42'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ServerUrl' rs:name='Server Relative URL'"
        + " rs:number='43'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_EncodedAbsUrl'"
        + " rs:name='Encoded Absolute URL' rs:number='44'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_BaseName' rs:name='File Name'"
        + " rs:number='45'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_MetaInfo' rs:name='Property Bag'"
        + " rs:number='46'>"
        + "<s:datatype dt:type='int' dt:lookup='true' dt:maxLength='2147483646'"
        + " />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__Level' rs:name='Level' rs:number='47'>"
        + "<s:datatype dt:type='ui1' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__IsCurrentVersion'"
        + " rs:name='Is Current Version' rs:number='48'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "</s:ElementType></s:Schema><scopes>"
        + "<scope id='{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}' >"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "<scope id='{2e29615c-59e7-493b-b08a-3642949cc069}' >"
        + "<permission memberid='1' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "</scopes>"
        + "<rs:data ItemCount=\"1\">"
        + "<z:row ows_ContentTypeId='0x0100442459C9B5E59C4F9CFDC789A220FC92'"
        + " ows_Title='Inside Folder' ows_ContentType='Item' ows_ID='2'"
        + " ows_Modified='2012-05-04T21:24:32Z'"
        + " ows_Created='2012-05-01T22:14:06Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='4'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='1'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='Inside Folder'"
        + " ows_LinkTitle='Inside Folder' ows_SelectTitle='2'"
        + " ows_Order='200.000000000000'"
        + " ows_GUID='{2C5BEF60-18FA-42CA-B472-7B5E1EC405A5}'"
        + " ows_FileRef='2;#Lists/Custom List/Test Folder/2_.000'"
        + " ows_FileDirRef='2;#Lists/Custom List/Test Folder'"
        + " ows_Last_x0020_Modified='2;#2012-05-01T22:14:06Z'"
        + " ows_Created_x0020_Date='2;#2012-05-01T22:14:06Z'"
        + " ows_FSObjType='2;#0' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='2;#2_.000'"
        + " ows_UniqueId='2;#{E7156244-AC2F-4402-AA74-7A365726CD02}'"
        + " ows_ProgId='2;#'"
        + " ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'"
        + " ows__EditMenuTableStart='2_.000' ows__EditMenuTableEnd='2'"
        + " ows_LinkFilenameNoMenu='2_.000' ows_LinkFilename='2_.000'"
        + " ows_ServerUrl='/Lists/Custom List/Test Folder/2_.000'"
        + " ows_EncodedAbsUrl='http://localhost:1/Lists/Custom%20List/Test%20Fo"
        +   "lder/2_.000'"
        + " ows_BaseName='2_' ows_MetaInfo='2;#' ows__Level='1'"
        + " ows__IsCurrentVersion='1' ows_ServerRedirected='0'/>"
        + "</rs:data>"
        + "</xml></Item>";
    class ListItemAttachmentsSiteData extends UnsupportedSiteData {
      @Override
      public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
          Holder<String> strSite, Holder<String> strWeb) {
        assertEquals(attachmentId, strUrl);
        setValue(getSiteAndWebResult, 0L);
        setValue(strSite, site);
        setValue(strWeb, site);
      }

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
        } else {
          fail("Unexpected object type: " + objectType);
          throw new AssertionError();
        }
      }
    }
    final String goldenContents = "attachment contents";
    final String goldenContentType = "fake/type";
    adaptor = new SharePointAdaptor(
        new SingleSiteDataFactory(new ListItemAttachmentsSiteData(),
          "http://localhost:1/_vti_bin/SiteData.asmx"),
        new HttpClient() {
      @Override
      public FileInfo issueGetRequest(URL url) {
        assertEquals(
          "http://localhost:1/Lists/Custom%20List/Attachments/2/1046000.pdf",
          url.toString());
        InputStream contents = new ByteArrayInputStream(
            goldenContents.getBytes(charset));
        List<String> headers = Arrays.asList("not-the-Content-Type", "early",
            "conTent-TypE", goldenContentType, "Content-Type", "late");
        return new FileInfo.Builder(contents).setHeaders(headers).build();
      }
    });
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId(attachmentId));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    assertEquals(goldenContents, responseString);
    assertEquals(goldenContentType, response.getContentType());
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId(
            "http://localhost:1/Lists/Custom List/Test Folder/2_.000"))
        .build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentListItem() throws IOException {
    final String getContentListResponse
        = "<List>"
        + "<Metadata ID=\"{6f33949a-b3ff-4b0c-ba99-93cb518ac2c0}\""
        + " LastModified=\"2012-05-04 21:24:32Z\" Title=\"Custom List\""
        + " DefaultTitle=\"True\" Description=\"\" BaseType=\"GenericList\""
        + " BaseTemplate=\"GenericList\""
        + " DefaultViewUrl=\"/sites/SiteCollection/Lists/Custom List/AllItems.a"
        +   "spx\""
        + " DefaultViewItemUrl=\"/sites/SiteCollection/Lists/Custom List/DispFo"
        +   "rm.aspx\""
        + " RootFolder=\"Lists/Custom List\" Author=\"System Account\""
        + " ItemCount=\"7\" ReadSecurity=\"1\" AllowAnonymousAccess=\"False\""
        + " AnonymousViewListItems=\"False\" AnonymousPermMask=\"0\""
        + " CRC=\"1334405648\" NoIndex=\"False\""
        + " ScopeID=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\" />"
        + "<ACL><permissions>"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></ACL>"
        + "<Views>"
        + "<View URL=\"Lists/Custom List/AllItems.aspx\""
        + " ID=\"{18b67349-78bd-49a2-ba1a-cdbc048adf0b}\" Title=\"All Items\""
        + " />"
        + "</Views><Schema>"
        + "<Field Name=\"Title\" Title=\"Title\" Type=\"Text\" />"
        + "<Field Name=\"Additional_x0020_Info\" Title=\"Additional Info\""
        + " Type=\"Text\" />"
        + "<Field Name=\"ContentType\" Title=\"Content Type\" Type=\"Choice\""
        + " />"
        + "<Field Name=\"ID\" Title=\"ID\" Type=\"Counter\" />"
        + "<Field Name=\"Modified\" Title=\"Modified\" Type=\"DateTime\" />"
        + "<Field Name=\"Created\" Title=\"Created\" Type=\"DateTime\" />"
        + "<Field Name=\"Author\" Title=\"Created By\" Type=\"User\" />"
        + "<Field Name=\"Editor\" Title=\"Modified By\" Type=\"User\" />"
        + "<Field Name=\"_UIVersionString\" Title=\"Version\" Type=\"Text\" />"
        + "<Field Name=\"Attachments\" Title=\"Attachments\""
        + " Type=\"Attachments\" />"
        + "<Field Name=\"Edit\" Title=\"Edit\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitleNoMenu\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitle\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"DocIcon\" Title=\"Type\" Type=\"Computed\" />"
        + "</Schema></List>";
    final String getContentListItemResponse
        = "<Item>"
        + "<Metadata>"
        + "<scope id=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\"><permissions>"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></scope>"
        + "</Metadata>"
        + "<xml xmlns:s='uuid:BDC6E3F0-6DA3-11d1-A2A3-00AA00C14882'"
        + " xmlns:dt='uuid:C2F41010-65B3-11d1-A29F-00AA00C14882'"
        + " xmlns:rs='urn:schemas-microsoft-com:rowset'"
        + " xmlns:z='#RowsetSchema'>"
        + "<s:Schema id='RowsetSchema'>"
        + "<s:ElementType name='row' content='eltOnly' rs:CommandTimeout='30'>"
        + "<s:AttributeType name='ows_ContentTypeId' rs:name='Content Type ID'"
        + " rs:number='1'>"
        + "<s:datatype dt:type='int' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Title' rs:name='Title' rs:number='2'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationComments'"
        + " rs:name='Approver Comments' rs:number='3'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_File_x0020_Type' rs:name='File Type'"
        + " rs:number='4'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Additional_x0020_Info'"
        + " rs:name='Additional Info' rs:number='5'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ContentType' rs:name='Content Type'"
        + " rs:number='6'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ID' rs:name='ID' rs:number='7'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Modified' rs:name='Modified'"
        + " rs:number='8'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created' rs:name='Created' rs:number='9'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Author' rs:name='Created By'"
        + " rs:number='10'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Editor' rs:name='Modified By'"
        + " rs:number='11'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__HasCopyDestinations'"
        + " rs:name='Has Copy Destinations' rs:number='12'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__CopySource' rs:name='Copy Source'"
        + " rs:number='13'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_owshiddenversion'"
        + " rs:name='owshiddenversion' rs:number='14'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowVersion'"
        + " rs:name='Workflow Version' rs:number='15'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersion' rs:name='UI Version'"
        + " rs:number='16'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersionString' rs:name='Version'"
        + " rs:number='17'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Attachments' rs:name='Attachments'"
        + " rs:number='18'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationStatus'"
        + " rs:name='Approval Status' rs:number='19'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitleNoMenu' rs:name='Title'"
        + " rs:number='20'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitle' rs:name='Title'"
        + " rs:number='21'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_SelectTitle' rs:name='Select'"
        + " rs:number='22'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_InstanceID' rs:name='Instance ID'"
        + " rs:number='23'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Order' rs:name='Order' rs:number='24'>"
        + "<s:datatype dt:type='float' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_GUID' rs:name='GUID' rs:number='25'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowInstanceID'"
        + " rs:name='Workflow Instance ID' rs:number='26'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileRef' rs:name='URL Path'"
        + " rs:number='27'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileDirRef' rs:name='Path'"
        + " rs:number='28'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Last_x0020_Modified' rs:name='Modified'"
        + " rs:number='29'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created_x0020_Date' rs:name='Created'"
        + " rs:number='30'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FSObjType' rs:name='Item Type'"
        + " rs:number='31'>"
        + "<s:datatype dt:type='ui1' dt:lookup='true' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_PermMask'"
        + " rs:name='Effective Permissions Mask' rs:number='32'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileLeafRef' rs:name='Name'"
        + " rs:number='33'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_UniqueId' rs:name='Unique Id'"
        + " rs:number='34'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ProgId' rs:name='ProgId' rs:number='35'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ScopeId' rs:name='ScopeId'"
        + " rs:number='36'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_HTML_x0020_File_x0020_Type'"
        + " rs:name='HTML File Type' rs:number='37'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableStart'"
        + " rs:name='Edit Menu Table Start' rs:number='38'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableEnd'"
        + " rs:name='Edit Menu Table End' rs:number='39'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilenameNoMenu' rs:name='Name'"
        + " rs:number='40'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilename' rs:name='Name'"
        + " rs:number='41'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_DocIcon' rs:name='Type' rs:number='42'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ServerUrl' rs:name='Server Relative URL'"
        + " rs:number='43'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_EncodedAbsUrl'"
        + " rs:name='Encoded Absolute URL' rs:number='44'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_BaseName' rs:name='File Name'"
        + " rs:number='45'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_MetaInfo' rs:name='Property Bag'"
        + " rs:number='46'>"
        + "<s:datatype dt:type='int' dt:lookup='true' dt:maxLength='2147483646'"
        + " />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__Level' rs:name='Level' rs:number='47'>"
        + "<s:datatype dt:type='ui1' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__IsCurrentVersion'"
        + " rs:name='Is Current Version' rs:number='48'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "</s:ElementType></s:Schema><scopes>"
        + "<scope id='{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}' >"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "<scope id='{2e29615c-59e7-493b-b08a-3642949cc069}' >"
        + "<permission memberid='1' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "</scopes>"
        + "<rs:data ItemCount=\"1\">"
        + "<z:row ows_ContentTypeId='0x0100442459C9B5E59C4F9CFDC789A220FC92'"
        + " ows_Title='Inside Folder' ows_ContentType='Item' ows_ID='2'"
        + " ows_Modified='2012-05-04T21:24:32Z'"
        + " ows_Created='2012-05-01T22:14:06Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='4'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='1'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='Inside Folder'"
        + " ows_LinkTitle='Inside Folder' ows_SelectTitle='2'"
        + " ows_Order='200.000000000000'"
        + " ows_GUID='{2C5BEF60-18FA-42CA-B472-7B5E1EC405A5}'"
        + " ows_FileRef='2;#sites/SiteCollection/Lists/Custom List/Test Folder/"
        +   "2_.000'"
        + " ows_FileDirRef='2;#sites/SiteCollection/Lists/Custom List/Test Fold"
        +   "er'"
        + " ows_Last_x0020_Modified='2;#2012-05-01T22:14:06Z'"
        + " ows_Created_x0020_Date='2;#2012-05-01T22:14:06Z'"
        + " ows_FSObjType='2;#0' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='2;#2_.000'"
        + " ows_UniqueId='2;#{E7156244-AC2F-4402-AA74-7A365726CD02}'"
        + " ows_ProgId='2;#'"
        + " ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'"
        + " ows__EditMenuTableStart='2_.000' ows__EditMenuTableEnd='2'"
        + " ows_LinkFilenameNoMenu='2_.000' ows_LinkFilename='2_.000'"
        + " ows_ServerUrl='/sites/SiteCollection/Lists/Custom List/Test Folder/"
        +   "2_.000'"
        + " ows_EncodedAbsUrl='http://localhost:1/sites/SiteCollection/Lists/Cu"
        +   "stom%20List/Test%20Folder/2_.000'"
        + " ows_BaseName='2_' ows_MetaInfo='2;#' ows__Level='1'"
        + " ows__IsCurrentVersion='1' ows_ServerRedirected='0'/>"
        + "</rs:data>"
        + "</xml></Item>";
    final String getContentListItemAttachmentsResponse
        = "<Item Count=\"1\">"
        + "<Attachment URL=\"http://localhost:1/sites/SiteCollection/Lists/Cust"
        + "om List/Attachments/2/1046000.pdf\" />"
        + "</Item>";
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
        new UnsupportedHttpClient());
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection",
          siteData, Callables.returning(memberIdMapping))
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
        .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT)
        .setPermitGroups(Arrays.asList("SiteCollection Members",
            "SiteCollection Owners", "SiteCollection Visitors"))
        .setPermitUsers(Arrays.asList("GDC-PSL\\administrator")).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentFolder() throws IOException {
    final String getContentListItemResponse
        = "<Item><Metadata>"
        + "<scope id=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\">"
        + "<permissions><permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></scope>"
        + "</Metadata>"
        + "<xml xmlns:s='uuid:BDC6E3F0-6DA3-11d1-A2A3-00AA00C14882'"
        + " xmlns:dt='uuid:C2F41010-65B3-11d1-A29F-00AA00C14882'"
        + " xmlns:rs='urn:schemas-microsoft-com:rowset'"
        + " xmlns:z='#RowsetSchema'>"
        + "<s:Schema id='RowsetSchema'>"
        + "<s:ElementType name='row' content='eltOnly' rs:CommandTimeout='30'>"
        + "<s:AttributeType name='ows_ContentTypeId' rs:name='Content Type ID'"
        + " rs:number='1'>"
        + "<s:datatype dt:type='int' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Title' rs:name='Title' rs:number='2'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationComments'"
        + " rs:name='Approver Comments' rs:number='3'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_File_x0020_Type' rs:name='File Type'"
        + " rs:number='4'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Additional_x0020_Info'"
        + " rs:name='Additional Info' rs:number='5'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ContentType' rs:name='Content Type'"
        + " rs:number='6'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ID' rs:name='ID' rs:number='7'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Modified' rs:name='Modified'"
        + " rs:number='8'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created' rs:name='Created' rs:number='9'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Author' rs:name='Created By'"
        + " rs:number='10'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Editor' rs:name='Modified By'"
        + " rs:number='11'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__HasCopyDestinations'"
        + " rs:name='Has Copy Destinations' rs:number='12'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__CopySource' rs:name='Copy Source'"
        + " rs:number='13'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_owshiddenversion'"
        + " rs:name='owshiddenversion' rs:number='14'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowVersion'"
        + " rs:name='Workflow Version' rs:number='15'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersion' rs:name='UI Version'"
        + " rs:number='16'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersionString' rs:name='Version'"
        + " rs:number='17'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Attachments' rs:name='Attachments'"
        + " rs:number='18'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationStatus'"
        + " rs:name='Approval Status' rs:number='19'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitleNoMenu' rs:name='Title'"
        + " rs:number='20'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitle' rs:name='Title'"
        + " rs:number='21'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_SelectTitle' rs:name='Select'"
        + " rs:number='22'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_InstanceID' rs:name='Instance ID'"
        + " rs:number='23'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Order' rs:name='Order' rs:number='24'>"
        + "<s:datatype dt:type='float' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_GUID' rs:name='GUID' rs:number='25'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowInstanceID'"
        + " rs:name='Workflow Instance ID' rs:number='26'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileRef' rs:name='URL Path'"
        + " rs:number='27'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileDirRef' rs:name='Path'"
        + " rs:number='28'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Last_x0020_Modified' rs:name='Modified'"
        + " rs:number='29'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created_x0020_Date' rs:name='Created'"
        + " rs:number='30'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FSObjType' rs:name='Item Type'"
        + " rs:number='31'>"
        + "<s:datatype dt:type='ui1' dt:lookup='true' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_PermMask'"
        + " rs:name='Effective Permissions Mask' rs:number='32'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileLeafRef' rs:name='Name'"
        + " rs:number='33'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_UniqueId' rs:name='Unique Id'"
        + " rs:number='34'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ProgId' rs:name='ProgId' rs:number='35'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ScopeId' rs:name='ScopeId'"
        + " rs:number='36'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_HTML_x0020_File_x0020_Type'"
        + " rs:name='HTML File Type' rs:number='37'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableStart'"
        + " rs:name='Edit Menu Table Start' rs:number='38'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableEnd'"
        + " rs:name='Edit Menu Table End' rs:number='39'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilenameNoMenu' rs:name='Name'"
        + " rs:number='40'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilename' rs:name='Name'"
        + " rs:number='41'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_DocIcon' rs:name='Type' rs:number='42'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ServerUrl' rs:name='Server Relative URL'"
        + " rs:number='43'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_EncodedAbsUrl'"
        + " rs:name='Encoded Absolute URL' rs:number='44'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_BaseName' rs:name='File Name'"
        + " rs:number='45'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_MetaInfo' rs:name='Property Bag'"
        + " rs:number='46'>"
        + "<s:datatype dt:type='int' dt:lookup='true' dt:maxLength='2147483646'"
        + " />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__Level' rs:name='Level' rs:number='47'>"
        + "<s:datatype dt:type='ui1' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__IsCurrentVersion'"
        + " rs:name='Is Current Version' rs:number='48'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "</s:ElementType></s:Schema><scopes>"
        + "<scope id='{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}' >"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "<scope id='{2e29615c-59e7-493b-b08a-3642949cc069}' >"
        + "<permission memberid='1' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "</scopes>"
        + "<rs:data ItemCount=\"1\">"
        + "<z:row"
        + " ows_ContentTypeId='0x01200077DD29735CE61148A73F540231F24430'"
        + " ows_Title='Test Folder' ows_ContentType='Folder' ows_ID='1'"
        + " ows_Modified='2012-05-01T22:13:47Z'"
        + " ows_Created='2012-05-01T22:13:47Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='1'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='0'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='Test Folder'"
        + " ows_LinkTitle='Test Folder' ows_SelectTitle='1'"
        + " ows_Order='100.000000000000'"
        + " ows_GUID='{C099F4ED-6E96-4A00-B94A-EE443061EE49}'"
        + " ows_FileRef='1;#sites/SiteCollection/Lists/Custom List/Test Folder'"
        + " ows_FileDirRef='1;#sites/SiteCollection/Lists/Custom List'"
        + " ows_Last_x0020_Modified='1;#2012-05-02T21:13:17Z'"
        + " ows_Created_x0020_Date='1;#2012-05-01T22:13:47Z'"
        + " ows_FSObjType='1;#1' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='1;#Test Folder'"
        + " ows_UniqueId='1;#{CE33B6B7-9F5E-4224-8D77-9C42E6290FE6}'"
        + " ows_ProgId='1;#'"
        + " ows_ScopeId='1;#{2E29615C-59E7-493B-B08A-3642949CC069}'"
        + " ows__EditMenuTableStart='Test Folder' ows__EditMenuTableEnd='1'"
        + " ows_LinkFilenameNoMenu='Test Folder' ows_LinkFilename='Test Folder'"
        + " ows_ServerUrl='/sites/SiteCollection/Lists/Custom List/Test Folder'"
        + " ows_EncodedAbsUrl='http://localhost:1/sites/SiteCollection/Lists/Cu"
        +   "stom%20List/Test%20Folder'"
        + " ows_BaseName='Test Folder' ows_MetaInfo='1;#' ows__Level='1'"
        + " ows__IsCurrentVersion='1' ows_ServerRedirected='0'/>"
        + "</rs:data>"
        + "</xml></Item>";
    final String getContentListResponse
        = "<List>"
        + "<Metadata ID=\"{6f33949a-b3ff-4b0c-ba99-93cb518ac2c0}\""
        + " LastModified=\"2012-05-04 21:24:32Z\" Title=\"Custom List\""
        + " DefaultTitle=\"True\" Description=\"\" BaseType=\"GenericList\""
        + " BaseTemplate=\"GenericList\""
        + " DefaultViewUrl=\"/sites/SiteCollection/Lists/Custom List/AllItems.a"
        +   "spx\""
        + " DefaultViewItemUrl=\"/sites/SiteCollection/Lists/Custom List/DispFo"
        +   "rm.aspx\""
        + " RootFolder=\"Lists/Custom List\" Author=\"System Account\""
        + " ItemCount=\"7\" ReadSecurity=\"1\" AllowAnonymousAccess=\"False\""
        + " AnonymousViewListItems=\"False\" AnonymousPermMask=\"0\""
        + " CRC=\"1334405648\" NoIndex=\"False\""
        + " ScopeID=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\" />"
        + "<ACL><permissions>"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></ACL>"
        + "<Views>"
        + "<View URL=\"Lists/Custom List/AllItems.aspx\""
        + " ID=\"{18b67349-78bd-49a2-ba1a-cdbc048adf0b}\" Title=\"All Items\""
        + " />"
        + "</Views>"
        + "<Schema>"
        + "<Field Name=\"Title\" Title=\"Title\" Type=\"Text\" />"
        + "<Field Name=\"Additional_x0020_Info\" Title=\"Additional Info\""
        + " Type=\"Text\" />"
        + "<Field Name=\"ContentType\" Title=\"Content Type\" Type=\"Choice\""
        + " />"
        + "<Field Name=\"ID\" Title=\"ID\" Type=\"Counter\" />"
        + "<Field Name=\"Modified\" Title=\"Modified\" Type=\"DateTime\" />"
        + "<Field Name=\"Created\" Title=\"Created\" Type=\"DateTime\" />"
        + "<Field Name=\"Author\" Title=\"Created By\" Type=\"User\" />"
        + "<Field Name=\"Editor\" Title=\"Modified By\" Type=\"User\" />"
        + "<Field Name=\"_UIVersionString\" Title=\"Version\" Type=\"Text\" />"
        + "<Field Name=\"Attachments\" Title=\"Attachments\""
        + " Type=\"Attachments\" />"
        + "<Field Name=\"Edit\" Title=\"Edit\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitleNoMenu\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitle\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"DocIcon\" Title=\"Type\" Type=\"Computed\" />"
        + "</Schema></List>";
    final String getContentFolderResponse
        = "<Folder><Metadata>"
        + "<scope id=\"{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}\">"
        + "<permissions><permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></scope>"
        + "</Metadata>"
        + "<xml xmlns:s='uuid:BDC6E3F0-6DA3-11d1-A2A3-00AA00C14882'"
        + " xmlns:dt='uuid:C2F41010-65B3-11d1-A29F-00AA00C14882'"
        + " xmlns:rs='urn:schemas-microsoft-com:rowset'"
        + " xmlns:z='#RowsetSchema'>"
        + "<s:Schema id='RowsetSchema'>"
        + "<s:ElementType name='row' content='eltOnly' rs:CommandTimeout='30'>"
        + "<s:AttributeType name='ows_ContentTypeId' rs:name='Content Type ID'"
        + " rs:number='1'>"
        + "<s:datatype dt:type='int' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Title' rs:name='Title' rs:number='2'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationComments'"
        + " rs:name='Approver Comments' rs:number='3'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_File_x0020_Type' rs:name='File Type'"
        + " rs:number='4'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Additional_x0020_Info'"
        + " rs:name='Additional Info' rs:number='5'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ContentType' rs:name='Content Type'"
        + " rs:number='6'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ID' rs:name='ID' rs:number='7'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Modified' rs:name='Modified'"
        + " rs:number='8'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created' rs:name='Created' rs:number='9'>"
        + "<s:datatype dt:type='datetime' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Author' rs:name='Created By'"
        + " rs:number='10'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Editor' rs:name='Modified By'"
        + " rs:number='11'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__HasCopyDestinations'"
        + " rs:name='Has Copy Destinations' rs:number='12'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__CopySource' rs:name='Copy Source'"
        + " rs:number='13'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_owshiddenversion'"
        + " rs:name='owshiddenversion' rs:number='14'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowVersion'"
        + " rs:name='Workflow Version' rs:number='15'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersion' rs:name='UI Version'"
        + " rs:number='16'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__UIVersionString' rs:name='Version'"
        + " rs:number='17'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Attachments' rs:name='Attachments'"
        + " rs:number='18'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__ModerationStatus'"
        + " rs:name='Approval Status' rs:number='19'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitleNoMenu' rs:name='Title'"
        + " rs:number='20'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkTitle' rs:name='Title'"
        + " rs:number='21'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_SelectTitle' rs:name='Select'"
        + " rs:number='22'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_InstanceID' rs:name='Instance ID'"
        + " rs:number='23'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Order' rs:name='Order' rs:number='24'>"
        + "<s:datatype dt:type='float' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_GUID' rs:name='GUID' rs:number='25'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_WorkflowInstanceID'"
        + " rs:name='Workflow Instance ID' rs:number='26'>"
        + "<s:datatype dt:type='string' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileRef' rs:name='URL Path'"
        + " rs:number='27'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileDirRef' rs:name='Path'"
        + " rs:number='28'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Last_x0020_Modified' rs:name='Modified'"
        + " rs:number='29'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_Created_x0020_Date' rs:name='Created'"
        + " rs:number='30'>"
        + "<s:datatype dt:type='datetime' dt:lookup='true' dt:maxLength='8' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FSObjType' rs:name='Item Type'"
        + " rs:number='31'>"
        + "<s:datatype dt:type='ui1' dt:lookup='true' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_PermMask'"
        + " rs:name='Effective Permissions Mask' rs:number='32'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_FileLeafRef' rs:name='Name'"
        + " rs:number='33'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_UniqueId' rs:name='Unique Id'"
        + " rs:number='34'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ProgId' rs:name='ProgId' rs:number='35'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ScopeId' rs:name='ScopeId'"
        + " rs:number='36'>"
        + "<s:datatype dt:type='string' dt:lookup='true' dt:maxLength='38' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_HTML_x0020_File_x0020_Type'"
        + " rs:name='HTML File Type' rs:number='37'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableStart'"
        + " rs:name='Edit Menu Table Start' rs:number='38'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__EditMenuTableEnd'"
        + " rs:name='Edit Menu Table End' rs:number='39'>"
        + "<s:datatype dt:type='i4' dt:maxLength='4' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilenameNoMenu' rs:name='Name'"
        + " rs:number='40'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_LinkFilename' rs:name='Name'"
        + " rs:number='41'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_DocIcon' rs:name='Type' rs:number='42'>"
        + "<s:datatype dt:type='string' dt:maxLength='512' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_ServerUrl' rs:name='Server Relative URL'"
        + " rs:number='43'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_EncodedAbsUrl'"
        + " rs:name='Encoded Absolute URL' rs:number='44'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_BaseName' rs:name='File Name'"
        + " rs:number='45'>"
        + "<s:datatype dt:type='string' dt:maxLength='1073741823' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows_MetaInfo' rs:name='Property Bag'"
        + " rs:number='46'>"
        + "<s:datatype dt:type='int' dt:lookup='true' dt:maxLength='2147483646'"
        + " />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__Level' rs:name='Level' rs:number='47'>"
        + "<s:datatype dt:type='ui1' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "<s:AttributeType name='ows__IsCurrentVersion'"
        + " rs:name='Is Current Version' rs:number='48'>"
        + "<s:datatype dt:type='boolean' dt:maxLength='1' />"
        + "</s:AttributeType>"
        + "</s:ElementType></s:Schema><scopes>"
        + "<scope id='{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}' >"
        + "<permission memberid='1' mask='206292717568' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "<scope id='{2e29615c-59e7-493b-b08a-3642949cc069}' >"
        + "<permission memberid='1' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</scope>"
        + "</scopes>"
        + "<rs:data ItemCount=\"2\">"
        + "<z:row"
        + " ows_ContentTypeId='0x0100442459C9B5E59C4F9CFDC789A220FC92'"
        + " ows_Title='Inside Folder' ows_ContentType='Item' ows_ID='2'"
        + " ows_Modified='2012-05-04T21:24:32Z'"
        + " ows_Created='2012-05-01T22:14:06Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='4'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='1'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='Inside Folder'"
        + " ows_LinkTitle='Inside Folder' ows_SelectTitle='2'"
        + " ows_Order='200.000000000000'"
        + " ows_GUID='{2C5BEF60-18FA-42CA-B472-7B5E1EC405A5}'"
        + " ows_FileRef='2;#sites/SiteCollection/Lists/Custom List/Test Folder/"
        +   "2_.000'"
        + " ows_FileDirRef='2;#sites/SiteCollection/Lists/Custom List/Test Fold"
        +   "er'"
        + " ows_Last_x0020_Modified='2;#2012-05-01T22:14:06Z'"
        + " ows_Created_x0020_Date='2;#2012-05-01T22:14:06Z'"
        + " ows_FSObjType='2;#0' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='2;#2_.000'"
        + " ows_UniqueId='2;#{E7156244-AC2F-4402-AA74-7A365726CD02}'"
        + " ows_ProgId='2;#'"
        + " ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'"
        + " ows__EditMenuTableStart='2_.000' ows__EditMenuTableEnd='2'"
        + " ows_LinkFilenameNoMenu='2_.000' ows_LinkFilename='2_.000'"
        + " ows_ServerUrl='/sites/SiteCollection/Lists/Custom List/Test Folder/"
        + "2_.000'"
        + " ows_EncodedAbsUrl='http://localhost:1/sites/SiteCollection/Lists/Cu"
        + "stom%20List/Test%20Folder/2_.000'"
        + " ows_BaseName='2_' ows_MetaInfo='2;#' ows__Level='1'"
        + " ows__IsCurrentVersion='1' ows_ServerRedirected='0'/>"
        + "<z:row"
        + " ows_ContentTypeId='0x01200077DD29735CE61148A73F540231F24430'"
        + " ows_Title='testing' ows_ContentType='Folder' ows_ID='5'"
        + " ows_Modified='2012-05-02T21:13:16Z'"
        + " ows_Created='2012-05-02T21:13:16Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='1'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='0'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='testing'"
        + " ows_LinkTitle='testing' ows_SelectTitle='5'"
        + " ows_Order='500.000000000000'"
        + " ows_GUID='{D803788D-7A3A-4222-AC66-BFD261412A28}'"
        + " ows_FileRef='5;#sites/SiteCollection/Lists/Custom List/Test Folder/"
        +   "testing'"
        + " ows_FileDirRef='5;#sites/SiteCollection/Lists/Custom List/Test Fold"
        +   "er'"
        + " ows_Last_x0020_Modified='5;#2012-05-04T17:49:23Z'"
        + " ows_Created_x0020_Date='5;#2012-05-02T21:13:17Z'"
        + " ows_FSObjType='5;#1' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='5;#testing'"
        + " ows_UniqueId='5;#{C2590C9A-C4E0-4411-BBB2-03ABC60AB073}'"
        + " ows_ProgId='5;#'"
        + " ows_ScopeId='5;#{2E29615C-59E7-493B-B08A-3642949CC069}'"
        + " ows__EditMenuTableStart='testing' ows__EditMenuTableEnd='5'"
        + " ows_LinkFilenameNoMenu='testing' ows_LinkFilename='testing'"
        + " ows_ServerUrl='/sites/SiteCollection/Lists/Custom List/Test Folder/"
        +   "testing'"
        + " ows_EncodedAbsUrl='http://localhost:1/sites/SiteCollection/Lists/Cu"
        +   "stom%20List/Test%20Folder/testing'"
        + " ows_BaseName='testing' ows_MetaInfo='5;#' ows__Level='1'"
        + " ows__IsCurrentVersion='1' ows_ServerRedirected='0'/>"
        + "</rs:data>"
        + "</xml></Folder>";
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
        new UnsupportedHttpClient());
    adaptor.init(new MockAdaptorContext(config, null));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GetContentsRequest request = new GetContentsRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder"));
    GetContentsResponse response = new GetContentsResponse(baos);
    adaptor.new SiteDataClient("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection",
          siteData, Callables.returning(memberIdMapping))
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
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection/"
            + "Lists/Custom List/AllItems.aspx"))
        .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT)
        .setPermitGroups(Arrays.asList("SiteCollection Members",
            "SiteCollection Owners", "SiteCollection Visitors"))
        .setPermitUsers(Arrays.asList("GDC-PSL\\administrator")).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocIds() throws IOException, InterruptedException {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedHttpClient());
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
        new UnsupportedHttpClient());
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
  public void testModifiedGetDocIdsClient() throws IOException,
      InterruptedException {
    final String getChangesContentDatabase
        = "<SPContentDatabase Change=\"Unchanged\" ItemCount=\"1\">"
        + "<ContentDatabase>"
        + "<Metadata ChangeId=\"1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727"
        +   "056594000000;603\""
        + " ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
        + "</ContentDatabase>"
        + "<SPSite Change=\"Unchanged\" ItemCount=\"1\">"
        + "<Messages><Message>1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;63472702"
        + "8976500000;600 Microsoft.SharePoint.SPChangeItem Add Done </Message>"
        + "</Messages>"
        + "<Site>"
        + "<Metadata URL=\"http://localhost:1\""
        + " ID=\"{bb3bb2dd-6ea7-471b-a361-6fb67988755c}\""
        + " LastModified=\"2012-05-15 19:07:39Z\" PortalURL=\"\""
        + " UserProfileGUID=\"\""
        + " RootWebId=\"{b2ea1067-3a54-4ab7-a459-c8ec864b97eb}\""
        + " ChangeId=\"1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000"
        +   "000;603\" />"
        + "<Groups><Group><Group ID=\"3\" Name=\"chinese1 Owners\""
        + " Description=\"Use this group to give people full control permission"
        +   "s to the SharePoint site: chinese1\" OwnerID=\"3\""
        + " OwnerIsUser=\"False\" />"
        + "<Users>"
        + "<User ID=\"1\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " Name=\"GDC-PSL\\administrator\""
        + " LoginName=\"GDC-PSL\\administrator\" Email=\"\" Notes=\"\""
        + " IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "<User ID=\"2\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-1130\""
        + " Name=\"spuser1\" LoginName=\"GDC-PSL\\spuser1\" Email=\"\""
        + " Notes=\"\" IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "</Users></Group>"
        + "<Group><Group ID=\"4\" Name=\"chinese1 Visitors\""
        + " Description=\"Use this group to give people read permissions to the"
        +   " SharePoint site: chinese1\""
        + " OwnerID=\"3\" OwnerIsUser=\"False\" />"
        + "<Users /></Group>"
        + "<Group><Group ID=\"5\" Name=\"chinese1 Members\""
        + " Description=\"Use this group to give people contribute permissions "
        +   "to the SharePoint site: chinese1\""
        + " OwnerID=\"3\" OwnerIsUser=\"False\" />"
        + "<Users /></Group></Groups>"
        + "</Site>"
        + "<SPWeb Change=\"Unchanged\" ItemCount=\"1\">"
        + "<Web><Metadata URL=\"http://localhost:1\""
        + " LastModified=\"2012-05-15 19:07:39Z\""
        + " Created=\"2011-10-14 18:59:25Z\""
        + " ID=\"{b2ea1067-3a54-4ab7-a459-c8ec864b97eb}\" Title=\"chinese1\""
        + " Description=\"\" Author=\"GDC-PSL\\administrator\""
        + " Language=\"1033\" CRC=\"558566148\" NoIndex=\"False\""
        + " DefaultHomePage=\"default.aspx\" ExternalSecurity=\"False\""
        + " ScopeID=\"{01abac8c-66c8-4fed-829c-8dd02bbf40dd}\""
        + " AllowAnonymousAccess=\"False\" AnonymousViewListItems=\"False\""
        + " AnonymousPermMask=\"0\" />"
        + "<Users><User ID=\"1\""
        + " Sid=\"S-1-5-21-736914693-3137354690-2813686979-500\""
        + " Name=\"GDC-PSL\\administrator\""
        + " LoginName=\"GDC-PSL\\administrator\" Email=\"\" Notes=\"\""
        + " IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "<User ID=\"2\" Sid=\"S-1-5-21-736914693-3137354690-2813686979-1130\""
        + " Name=\"spuser1\" LoginName=\"GDC-PSL\\spuser1\" Email=\"\""
        + " Notes=\"\" IsSiteAdmin=\"True\" IsDomainGroup=\"False\" />"
        + "</Users>"
        + "<ACL><permissions>"
        + "<permission memberid='2' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></ACL>"
        + "<Webs /><Lists />"
        + "</Web>"
        + "<SPList Change=\"Unchanged\" ItemCount=\"1\">"
        + "<List><Metadata ID=\"{133fcb96-7e9b-46c9-b5f3-09770a35ad8a}\""
        + " LastModified=\"2012-05-15 18:21:38Z\" Title=\"Announcements\""
        + " DefaultTitle=\"True\""
        + " Description=\"Use the Announcements list to post messages on the ho"
        +   "me page of your site.\""
        + " BaseType=\"GenericList\" BaseTemplate=\"Announcements\""
        + " DefaultViewUrl=\"/Lists/Announcements/AllItems.aspx\""
        + " DefaultViewItemUrl=\"/Lists/Announcements/DispForm.aspx\""
        + " RootFolder=\"Lists/Announcements\" Author=\"System Account\""
        + " ItemCount=\"2\" ReadSecurity=\"1\" AllowAnonymousAccess=\"False\""
        + " AnonymousViewListItems=\"False\" AnonymousPermMask=\"0\""
        + " CRC=\"751515778\" NoIndex=\"False\""
        + " ScopeID=\"{01abac8c-66c8-4fed-829c-8dd02bbf40dd}\" />"
        + "<ACL><permissions>"
        + "<permission memberid='2' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></ACL>"
        + "<Views><View URL=\"Lists/Announcements/AllItems.aspx\""
        + " ID=\"{54f41f88-ddf6-404c-bdf9-a104eabcd1a9}\" Title=\"All items\""
        + " /></Views>"
        + "<Schema>"
        + "<Field Name=\"ID\" Title=\"ID\" Type=\"Counter\" />"
        + "<Field Name=\"ContentType\" Title=\"Content Type\" Type=\"Text\" />"
        + "<Field Name=\"Title\" Title=\"Title\" Type=\"Text\" />"
        + "<Field Name=\"Modified\" Title=\"Modified\" Type=\"DateTime\" />"
        + "<Field Name=\"Created\" Title=\"Created\" Type=\"DateTime\" />"
        + "<Field Name=\"Author\" Title=\"Created By\" Type=\"User\" />"
        + "<Field Name=\"Editor\" Title=\"Modified By\" Type=\"User\" />"
        + "<Field Name=\"_UIVersionString\" Title=\"Version\" Type=\"Text\" />"
        + "<Field Name=\"Attachments\" Title=\"Attachments\""
        + " Type=\"Attachments\" />"
        + "<Field Name=\"Edit\" Title=\"Edit\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitleNoMenu\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"LinkTitle\" Title=\"Title\" Type=\"Computed\" />"
        + "<Field Name=\"DocIcon\" Title=\"Type\" Type=\"Computed\" />"
        + "<Field Name=\"Body\" Title=\"Body\" Type=\"Note\" />"
        + "<Field Name=\"Expires\" Title=\"Expires\" Type=\"DateTime\" />"
        + "</Schema> </List>"
        + "<SPListItem Change=\"Add\" ItemCount=\"0\" UpdateSecurity=\"False\""
        + " Id=\"{5085be94-b5c1-45c8-a047-d0f03344fe31}\""
        + " ParentId=\"{133fcb96-7e9b-46c9-b5f3-09770a35ad8a}_\""
        + " InternalUrl=\"/siteurl=/siteid={bb3bb2dd-6ea7-471b-a361-6fb67988755"
        +   "c}/weburl=/webid={b2ea1067-3a54-4ab7-a459-c8ec864b97eb}/listid={13"
        +   "3fcb96-7e9b-46c9-b5f3-09770a35ad8a}/folderurl=/itemid=2\""
        + " DisplayUrl=\"/Lists/Announcements/DispForm.aspx?ID=2\""
        + " ServerUrl=\"http://localhost:1\" CRC=\"0\" Url=\"2_.000\">"
        + "<ListItem>"
        + "<z:row xmlns:z='#RowsetSchema' ows_ID='2'"
        + " ows_ContentTypeId='0x010400FDF586FAF309684984C89E7FB272808F'"
        + " ows_ContentType='Announcement' ows_Title='Test Announcement'"
        + " ows_Modified='2012-05-15T18:21:38Z'"
        + " ows_Created='2012-05-15T18:21:38Z'"
        + " ows_Author='1073741823;#System Account'"
        + " ows_Editor='1073741823;#System Account' ows_owshiddenversion='1'"
        + " ows_WorkflowVersion='1' ows__UIVersion='512'"
        + " ows__UIVersionString='1.0' ows_Attachments='0'"
        + " ows__ModerationStatus='0' ows_LinkTitleNoMenu='Test Announcement'"
        + " ows_LinkTitle='Test Announcement' ows_SelectTitle='2'"
        + " ows_Order='200.000000000000'"
        + " ows_GUID='{986A1401-9DE7-4CD5-8CC0-9BBC64D5F146}'"
        + " ows_FileRef='2;#Lists/Announcements/2_.000'"
        + " ows_FileDirRef='2;#Lists/Announcements'"
        + " ows_Last_x0020_Modified='2;#2012-05-15T18:21:38Z'"
        + " ows_Created_x0020_Date='2;#2012-05-15T18:21:38Z'"
        + " ows_FSObjType='2;#0' ows_PermMask='0x7fffffffffffffff'"
        + " ows_FileLeafRef='2;#2_.000'"
        + " ows_UniqueId='2;#{5085BE94-B5C1-45C8-A047-D0F03344FE31}'"
        + " ows_ProgId='2;#'"
        + " ows_ScopeId='2;#{01ABAC8C-66C8-4FED-829C-8DD02BBF40DD}'"
        + " ows__EditMenuTableStart='2_.000' ows__EditMenuTableEnd='2'"
        + " ows_LinkFilenameNoMenu='2_.000' ows_LinkFilename='2_.000'"
        + " ows_ServerUrl='/Lists/Announcements/2_.000'"
        + " ows_EncodedAbsUrl='http://localhost:1/Lists/Announcements/2_.000'"
        + " ows_BaseName='2_' ows_MetaInfo='2;#' ows__Level='1'"
        + " ows__IsCurrentVersion='1'"
        + " ows_Body='This is the body of the announcement.'"
        + " ows_ServerRedirected='0'/>"
        + "<permissions>"
        + "<permission memberid='2' mask='9223372036854775807' />"
        + "<permission memberid='3' mask='9223372036854775807' />"
        + "<permission memberid='4' mask='756052856929' />"
        + "<permission memberid='5' mask='1856436900591' />"
        + "</permissions></ListItem></SPListItem>"
        + "</SPList>"
        + "</SPWeb>"
        + "</SPSite>"
        + "</SPContentDatabase>";
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedHttpClient());
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1/sites/SiteCollection",
        "http://localhost:1/sites/SiteCollection", new UnsupportedSiteData(),
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
        new UnsupportedHttpClient());
    adaptor.init(new MockAdaptorContext(config, null));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1", "http://localhost:1",
        new UnsupportedSiteData(), new UnsupportedCallable<MemberIdMapping>());
    String xml = "<broken";
    thrown.expect(IOException.class);
    client.jaxbParse(xml, SPContentDatabase.class);
  }

  @Test
  public void testValidationError() throws Exception {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedHttpClient());
    adaptor.init(new MockAdaptorContext(config, null));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1", "http://localhost:1",
        new UnsupportedSiteData(), new UnsupportedCallable<MemberIdMapping>());
    // Lacks required child element.
    String xml = "<SPContentDatabase"
        + " xmlns='http://schemas.microsoft.com/sharepoint/soap/'/>";
    thrown.expect(IOException.class);
    client.jaxbParse(xml, SPContentDatabase.class);
  }

  @Test
  public void testParseUnknownXml() throws Exception {
    adaptor = new SharePointAdaptor(new UnsupportedSiteDataFactory(),
        new UnsupportedHttpClient());
    adaptor.init(new MockAdaptorContext(config, null));
    SharePointAdaptor.SiteDataClient client = adaptor.new SiteDataClient(
        "http://localhost:1", "http://localhost:1",
        new UnsupportedSiteData(), new UnsupportedCallable<MemberIdMapping>());
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

  private static class UnsupportedSiteDataFactory implements SiteDataFactory {
    @Override
    public SiteDataSoap newSiteData(String endpoint) {
      throw new UnsupportedOperationException();
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
}
