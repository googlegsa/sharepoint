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
import static com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.SoapFactory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Callables;
import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.DocRequest;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.IOHelper;
import com.google.enterprise.adaptor.InvalidConfigurationException;
import com.google.enterprise.adaptor.Metadata;
import com.google.enterprise.adaptor.Principal;
import com.google.enterprise.adaptor.StartupException;
import com.google.enterprise.adaptor.UserPrincipal;
import com.google.enterprise.adaptor.sharepoint.ActiveDirectoryClient.ADServer;
import com.google.enterprise.adaptor.sharepoint.SamlAuthenticationHandler.SamlHandshakeManager;
import com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.FileInfo;
import com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.SharePointUrl;
import com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.SiteUserIdMappingCallable;
import com.google.enterprise.adaptor.sharepoint.SharePointAdaptor.SoapFactory;
import com.google.enterprise.adaptor.testing.RecordingDocIdPusher;
import com.google.enterprise.adaptor.testing.RecordingResponse;
import com.google.enterprise.adaptor.testing.RecordingResponse.State;
import com.google.enterprise.adaptor.testing.UnsupportedDocIdPusher;

import com.microsoft.schemas.sharepoint.soap.ItemData;
import com.microsoft.schemas.sharepoint.soap.ObjectType;
import com.microsoft.schemas.sharepoint.soap.SPContentDatabase;
import com.microsoft.schemas.sharepoint.soap.SiteDataSoap;
import com.microsoft.schemas.sharepoint.soap.VirtualServer;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationMode;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;
import com.microsoft.schemas.sharepoint.soap.authentication.LoginResult;
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
import com.microsoft.schemas.sharepoint.soap.people.ArrayOfPrincipalInfo;
import com.microsoft.schemas.sharepoint.soap.people.ArrayOfString;
import com.microsoft.schemas.sharepoint.soap.people.PeopleSoap;
import com.microsoft.schemas.sharepoint.soap.people.PrincipalInfo;
import com.microsoft.schemas.sharepoint.soap.people.SPPrincipalType;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.ws.Binding;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.EndpointReference;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.handler.MessageContext;

/**
 * Test cases for {@link SharePointAdaptor}.
 */
public class SharePointAdaptorTest {
  private static final String AUTH_ENDPOINT
      = "http://localhost:1/_vti_bin/Authentication.asmx";
  private static final String VS_ENDPOINT
      = "http://localhost:1/_vti_bin/SiteData.asmx";
  private static final ContentExchange VS_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.VIRTUAL_SERVER, null, null, null,
          true, false, null, loadTestString("vs.xml"));
  private static final ContentExchange CD_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.CONTENT_DATABASE,
          "{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}", null, null, true, false,
          null, loadTestString("cd.xml"));
  private static final String SITES_SITECOLLECTION_ENDPOINT
      = "http://localhost:1/sites/SiteCollection/_vti_bin/SiteData.asmx";
  private static final SiteAndWebExchange SITES_SITECOLLECTION_SAW_EXCHANGE
      = new SiteAndWebExchange("http://localhost:1/sites/SiteCollection", 0,
          "http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection");
  private static final SiteAndWebExchange ROOT_SITE_SAW_EXCHANGE
      = new SiteAndWebExchange("http://localhost:1", 0, "http://localhost:1",
          "http://localhost:1");
  private static final URLSegmentsExchange SITES_SITECOLLECTION_URLSEG_EXCHANGE
      = new URLSegmentsExchange("http://localhost:1/sites/SiteCollection",
          true, null, null, null, null);
  private static final ContentExchange SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.SITE, null, null, null, true, false,
          null, loadTestString("sites-SiteCollection-s.xml"));
  private static final ContentExchange SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.SITE_COLLECTION, null, null, null,
          true, false, null, loadTestString("sites-SiteCollection-sc.xml"));
  private static final URLSegmentsExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_URLSEG_EXCHANGE
      = new URLSegmentsExchange(
          "http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/AllItems.aspx",
          true, null, null, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", null);
  private static final ContentExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.LIST,
          "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", null, null, false, false,
          null, loadTestString("sites-SiteCollection-Lists-CustomList-l.xml"));
  private static final ContentExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_F_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.FOLDER,
          "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "", null, true, false,
          null, loadTestString("sites-SiteCollection-Lists-CustomList-f.xml"));
  private static final URLSegmentsExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_URLSEG_EXCHANGE
      = new URLSegmentsExchange(
          "http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder",
          true, null, null, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "1");
  private static final URLSegmentsExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_URLSEG_EXCHANGE
      = new URLSegmentsExchange(
          "http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/Test Folder/2_.000",
          true, null, null, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "2");
  private static final ContentExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_LI_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.LIST_ITEM,
          "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "", "1", false, false,
          null,
          loadTestString("sites-SiteCollection-Lists-CustomList-1-li.xml"));
  private static final ContentExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_F_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.FOLDER,
          "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "Test Folder", null,
          true, false, null,
          loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml"));
  private static final ContentExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.LIST_ITEM,
          "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "", "2", false, false,
          null,
          loadTestString("sites-SiteCollection-Lists-CustomList-2-li.xml"));
  private static final ContentExchange
      SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_A_CONTENT_EXCHANGE
      = new ContentExchange(ObjectType.LIST_ITEM_ATTACHMENTS,
          "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "", "2", true, false,
          null,
          loadTestString("sites-SiteCollection-Lists-CustomList-2-a.xml"));
  private static final String DEFAULT_NAMESPACE = "Default";
  private static final String SITES_SITECOLLECTION_NAMESPACE
      = "Default_http://localhost:1/sites/SiteCollection";
  private static final UserPrincipal NT_AUTHORITY_LOCAL_SERVICE
      = new UserPrincipal("NT AUTHORITY\\LOCAL SERVICE", DEFAULT_NAMESPACE);
  private static final GroupPrincipal NT_AUTHORITY_AUTHENTICATED_USERS
      = new GroupPrincipal("NT AUTHORITY\\authenticated users",
          DEFAULT_NAMESPACE);
  private static final UserPrincipal GDC_PSL_ADMINISTRATOR
      = new UserPrincipal("GDC-PSL\\administrator", DEFAULT_NAMESPACE);
  private static final UserPrincipal GDC_PSL_SPUSER1
      = new UserPrincipal("GDC-PSL\\spuser1", DEFAULT_NAMESPACE);
  private static final GroupPrincipal SITES_SITECOLLECTION_OWNERS
      = new GroupPrincipal("chinese1 Owners",
          SITES_SITECOLLECTION_NAMESPACE);
  private static final GroupPrincipal SITES_SITECOLLECTION_VISITORS
      = new GroupPrincipal("chinese1 Visitors",
          SITES_SITECOLLECTION_NAMESPACE);
  private static final GroupPrincipal SITES_SITECOLLECTION_MEMBERS
      = new GroupPrincipal("chinese1 Members",
          SITES_SITECOLLECTION_NAMESPACE);
  private static final GroupPrincipal SITES_SITECOLLECTION_REVIEWERS
      = new GroupPrincipal("chinese1 Reviewers",
          SITES_SITECOLLECTION_NAMESPACE);
  private static final MemberIdMapping SITES_SITECOLLECTION_MEMBER_MAPPING
      = new MemberIdMappingBuilder()
      .put(1, GDC_PSL_ADMINISTRATOR)
      .put(3, SITES_SITECOLLECTION_OWNERS)
      .put(4, SITES_SITECOLLECTION_VISITORS)
      .put(5, SITES_SITECOLLECTION_MEMBERS)
      .put(6, SITES_SITECOLLECTION_REVIEWERS)
      .build();

  private final Charset charset = Charset.forName("UTF-8");
  private Config config;
  private SharePointAdaptor adaptor;
  private DocIdPusher pusher = new UnsupportedDocIdPusher();
  private Callable<ExecutorService> executorFactory
      = new Callable<ExecutorService>() {
        @Override
        public ExecutorService call() {
          return new CallerRunsExecutor();
        }
      };
  private final MockSoapFactory initableSoapFactory
      = MockSoapFactory.blank()
      .endpoint(VS_ENDPOINT, MockSiteData.blank()
          .register(VS_CONTENT_EXCHANGE)
          .register(CD_CONTENT_EXCHANGE)
          .register(ROOT_SITE_SAW_EXCHANGE));

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
  public void testSiteDataFactoryImpl() throws IOException {
    SharePointAdaptor.SoapFactoryImpl sdfi
        = new SharePointAdaptor.SoapFactoryImpl();
    assertNotNull(
        sdfi.newSiteData("http://localhost:1/_vti_bin/SiteData.asmx"));
  }

  @Test
  public void testConstructor() {
    new SharePointAdaptor();
  }

  @Test
  public void testDestroy() {
    SharePointAdaptor adaptor = new SharePointAdaptor();
    adaptor.destroy();
  }

  @Test
  public void testNullSoapFactory() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(null, new UnsupportedHttpClient(), executorFactory,
        new UnsupportedAuthenticationClientFactory(),
        new UnsupportedActiveDirectoryClientFactory());
  }

  @Test
  public void testNullHttpClient() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(MockSoapFactory.blank(), null, executorFactory,
        new UnsupportedAuthenticationClientFactory(),
        new UnsupportedActiveDirectoryClientFactory());
  }

  @Test
  public void testNullExecutorFactory() {
    thrown.expect(NullPointerException.class);
    new SharePointAdaptor(MockSoapFactory.blank(), new UnsupportedHttpClient(),
        null, new UnsupportedAuthenticationClientFactory(),
        new UnsupportedActiveDirectoryClientFactory());
  }

  @Test
  public void testInitDestroy() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testInitFailedForMissingRootCollection() throws Exception {
    MockSoapFactory rootMissingSoapFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(new SiteAndWebExchange(
                "http://localhost:1", 10L, null, null)));
    adaptor = new SharePointAdaptor(rootMissingSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    thrown.expect(StartupException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
  }
  
  @Test
  public void testAdaptorWithSocketTimeoutConfiguration() throws Exception {
    Map<String, Object> goldenRequestContext;
    Map<String, Object> goldenRequestContextAuth;
    {
      Map<String, Object> tmp = new HashMap<String, Object>();
      tmp.put("com.sun.xml.internal.ws.connect.timeout", 20000);
      tmp.put("com.sun.xml.internal.ws.request.timeout", 180000);
      tmp.put("com.sun.xml.ws.connect.timeout", 20000);
      tmp.put("com.sun.xml.ws.request.timeout", 180000);
      goldenRequestContextAuth 
          = Collections.unmodifiableMap(new HashMap<String, Object>(tmp));
      // Disabling forms authentication
      tmp.put(MessageContext.HTTP_REQUEST_HEADERS, 
        Collections.singletonMap("X-FORMS_BASED_AUTH_ACCEPTED",
          Collections.singletonList("f")));
      goldenRequestContext = Collections.unmodifiableMap(tmp);
    }

    MockSiteData siteDataSoap = new MockSiteData()
        .register(VS_CONTENT_EXCHANGE)
        .register(CD_CONTENT_EXCHANGE)
        .register(ROOT_SITE_SAW_EXCHANGE);
    MockPeopleSoap peopleSoap = new MockPeopleSoap();
    MockUserGroupSoap userGroupSoap = new MockUserGroupSoap(null);
    final MockAuthenticationSoap authenticationSoap 
        = new MockAuthenticationSoap();
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, siteDataSoap)
        .endpoint("http://localhost:1/_vti_bin/People.asmx", peopleSoap)
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx", userGroupSoap);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms() {
          @Override
          public AuthenticationSoap newSharePointFormsAuthentication(
              String virtualServer, String username, String password)
              throws IOException {
            return authenticationSoap;
          }
        }, new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("adaptor.docHeaderTimeoutSecs", "20");    
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(goldenRequestContext, siteDataSoap.getRequestContext());
    assertEquals(goldenRequestContext, peopleSoap.getRequestContext());
    assertEquals(goldenRequestContext, userGroupSoap.getRequestContext());
    assertEquals(goldenRequestContextAuth,
        authenticationSoap.getRequestContext());
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testAdaptorWithUserAgentConfiguration() throws Exception {
    Map<String, Object> goldenRequestContext;
    Map<String, Object> goldenRequestContextAuth;
    {
      Map<String, Object> tmp = new HashMap<String, Object>();
      tmp.put("com.sun.xml.internal.ws.connect.timeout", 30000);
      tmp.put("com.sun.xml.internal.ws.request.timeout", 180000);
      tmp.put("com.sun.xml.ws.connect.timeout", 30000);
      tmp.put("com.sun.xml.ws.request.timeout", 180000);
      tmp.put(MessageContext.HTTP_REQUEST_HEADERS, Collections.singletonMap(
          "User-Agent", Collections.singletonList("GSASharePointAdaptor")));
      goldenRequestContextAuth 
          = Collections.unmodifiableMap(new HashMap<String, Object>(tmp));
      // Disabling forms authentication and add user agent
      Map<String, List<String>> headers = new HashMap<String, List<String>>();
      headers.put("X-FORMS_BASED_AUTH_ACCEPTED",
          Collections.singletonList("f"));
      headers.put("User-Agent",
          Collections.singletonList("GSASharePointAdaptor"));
      tmp.put(MessageContext.HTTP_REQUEST_HEADERS,
          Collections.unmodifiableMap(headers));
      goldenRequestContext = Collections.unmodifiableMap(tmp);
    }

    MockSiteData siteDataSoap = new MockSiteData()
        .register(VS_CONTENT_EXCHANGE)
        .register(CD_CONTENT_EXCHANGE)
        .register(ROOT_SITE_SAW_EXCHANGE);
    MockPeopleSoap peopleSoap = new MockPeopleSoap();
    MockUserGroupSoap userGroupSoap = new MockUserGroupSoap(null);
    final MockAuthenticationSoap authenticationSoap 
        = new MockAuthenticationSoap();
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, siteDataSoap)
        .endpoint("http://localhost:1/_vti_bin/People.asmx", peopleSoap)
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx", userGroupSoap);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms() {
          @Override
          public AuthenticationSoap newSharePointFormsAuthentication(
              String virtualServer, String username, String password)
              throws IOException {
            return authenticationSoap;
          }
        }, new UnsupportedActiveDirectoryClientFactory());    
    config.overrideKey("adaptor.userAgent", "GSASharePointAdaptor");
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(goldenRequestContext, siteDataSoap.getRequestContext());
    assertEquals(goldenRequestContext, peopleSoap.getRequestContext());
    assertEquals(goldenRequestContext, userGroupSoap.getRequestContext());
    assertEquals(goldenRequestContextAuth,
        authenticationSoap.getRequestContext());
    adaptor.destroy();
    adaptor = null;
  }

  @Test
  public void testAdaptorInitWithAdfs() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryAdfs(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.sts.endpoint", "https://stsendpoint");
    config.overrideKey("sharepoint.sts.realm", "urn:sharepoint:com");
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testCheckFullReadPermissionForAdaptorUser() throws Exception {
    MockSiteData siteDataSoap = new MockSiteData()
        .register(VS_CONTENT_EXCHANGE)
        .register(CD_CONTENT_EXCHANGE)
        .register(ROOT_SITE_SAW_EXCHANGE);
    VirtualServer vs = new SiteDataClient(siteDataSoap, true)
        .getContentVirtualServer();

    assertEquals(1, SharePointAdaptor.checkFullReadPermissionForAdaptorUser(vs,
        "GDC-PSL\\spuser1"));
    assertEquals(0, SharePointAdaptor.checkFullReadPermissionForAdaptorUser(vs,
        "GDC-PSL\\administrator"));
    assertEquals(-1, SharePointAdaptor.checkFullReadPermissionForAdaptorUser(vs,
        "Some Fake User"));
  }
  
  @Test
  public void testAdaptorInitwithMalformedSharePointUrl() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server", "malformed-url");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }

  @Test
  public void testAdaptorInitAdfsUnknownHostException() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    final MockSamlHandshakeManager samlManager 
        = new MockSamlHandshakeManager("token", "cookie") {
          @Override
          public String requestToken() throws IOException{
            throw new UnknownHostException("stsendpoint");
          }
    };
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryAdfs() {
          @Override
          public SamlHandshakeManager newAdfsAuthentication(
              String virtualServer, String username, String password,
              String stsendpoint, String stsrelam, String login,
              String trustlocation) throws IOException {
            return samlManager;
          } 
        }, new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.sts.endpoint", "https://stsendpoint");
    config.overrideKey("sharepoint.sts.realm", "urn:sharepoint:com");
    thrown.expect(IOException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));   
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitAdfsWithBlankUsername() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.sts.endpoint", "https://stsendpoint");
    config.overrideKey("sharepoint.sts.realm", "urn:sharepoint:com");
    config.overrideKey("sharepoint.usernamet", "");
    config.overrideKey("sharepoint.password", "");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitLiveWithBlankUsername() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.useLiveAuthentication", "true");
    config.overrideKey("sharepoint.usernamet", "");
    config.overrideKey("sharepoint.password", "");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithMissingRelam() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.sts.endpoint", "https://stsendpoint");   
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithLive() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryLive(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.useLiveAuthentication", "true");   
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithInvalidCustomSamlManager() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new AuthenticationClientFactoryImpl(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey(
        "sharepoint.customSamlManager", "com.invalid.class.method");
    config.overrideKey("gsa.version", "7.4.0-0");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithInvalidCustomSamlManagerMethod()
      throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new AuthenticationClientFactoryImpl(),
        new UnsupportedActiveDirectoryClientFactory());
    String factoryMethod = MockCustomSamlHandshakeManager.class
        .getName() + ".getInstanceWrong";
    config.overrideKey("sharepoint.customSamlManager", factoryMethod);
    config.overrideKey("gsa.version", "7.4.0-0");
    config.overrideKey("test.token", "test token");
    config.overrideKey("test.cookie", "test cookie");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithInvalidCustomSamlManagerIntance()
      throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new AuthenticationClientFactoryImpl(),
        new UnsupportedActiveDirectoryClientFactory());
    String factoryMethod = MockCustomSamlHandshakeManager.class
        .getName() + ".getStringIntance";
    config.overrideKey("sharepoint.customSamlManager", factoryMethod);
    config.overrideKey("gsa.version", "7.4.0-0");
    config.overrideKey("test.token", "test token");
    config.overrideKey("test.cookie", "test cookie");
    thrown.expect(ClassCastException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithCustomSamlManager() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx",
            new MockPeopleSoap())
        .endpoint("http://localhost:1/_vti_bin/UserGroup.asmx",
            new MockUserGroupSoap(null));
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new AuthenticationClientFactoryImpl(),
        new UnsupportedActiveDirectoryClientFactory());
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
  public void testInitDestroyInitDestroy() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());    
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.destroy();
    adaptor = null;
  }

  @Test
  public void testTrailingSlashInit() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server", "http://localhost:1/");
    adaptor.init(new MockAdaptorContext(config, pusher));
  }

  @Test
  public void testSpUrlToUriPassthrough() throws Exception {
    assertEquals("http://somehost:1/path/file",
        SharePointAdaptor.spUrlToUri("http://somehost:1/path/file").toString());
  }

  @Test
  public void testSpUrlToUriSpace() throws Exception {
    assertEquals("http://somehost/A%20space",
        SharePointAdaptor.spUrlToUri("http://somehost/A space").toString());
  }

  @Test
  public void testSpUrlToUriPassthroughNoPath() throws Exception {
    assertEquals("https://somehost",
        SharePointAdaptor.spUrlToUri("https://somehost").toString());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSpUrlToUriNoSceme() throws Exception {
    SharePointAdaptor.spUrlToUri("http:/");
  }
  
  @Test
  public void testAdaptorInitWithInvalidMaxRedirects() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("adaptor.maxRedirectsToFollow", "invalid");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithNegativeMaxRedirects() throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("adaptor.maxRedirectsToFollow", "-2");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }
  
  @Test
  public void testAdaptorInitWithValidMaxRedirectsNoBrowserLeniency()
      throws Exception {
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("adaptor.maxRedirectsToFollow", "20");
    config.overrideKey("adaptor.lenientUrlRulesAndCustomRedirect", "false");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }

  @Test
  public void testMetadataDecoding() {
    assertEquals("NothingSpecial",
        SharePointAdaptor.decodeMetadataName("NothingSpecial"));
    assertEquals("_x020__x00020__0020__x0020",
        SharePointAdaptor.decodeMetadataName("_x020__x00020__0020__x0020"));
    assertEquals("Simple Space",
        SharePointAdaptor.decodeMetadataName("Simple_x0020_Space"));
    assertEquals("Multiple \u0394Replacements\u2ee8$",
        SharePointAdaptor.decodeMetadataName(
            "Multiple_x0020__x0394_Replacements_x2ee8__x0024_"));
  }

  @Test
  public void testStripHtml() {
    assertEquals("<testing@example.com>",
        SharePointAdaptor.stripHtml("<testing@example.com>"));
    assertEquals("some text",
        SharePointAdaptor.stripHtml("<div><b>some</b> text</div>"));
    assertEquals("a 0 ",
        SharePointAdaptor.stripHtml("<br><a href=\"test's\" hover=none "
          + "\nz='fo\" '>a &#0048;</a> "));
    // The space isn't a space, but a no-break space.
    // The \u0000 is is simply to make sure we don't break. It is actually
    // invalid input, so we don't care too much how we resolve it.
    assertEquals(" &&<>\"'0\u2014\u0000$",
        SharePointAdaptor.stripHtml(
            "&nbsp;&&amp;&lt;&gt;&quot;&apos;&#0048;&#8212;&abcde;"
            + "&#9999999999;&#65536;&#0036;"));
  }

  @Test
  public void testGetDocContentWrongServer() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()        
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(new SiteAndWebExchange(
                "http://wronghost:1/", 1, null, null)));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://wronghost:1/"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }

  @Test
  public void testGetDocContentWrongPage() throws Exception {
    final String wrongPage = "http://localhost:1/wrongPage";
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(new SiteAndWebExchange(
                wrongPage, 0, "http://localhost:1", "http://localhost:1"))
            .register(new URLSegmentsExchange(
                wrongPage, false, null, null, null, null)));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(new DocId(wrongPage));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }
  
  @Test
  public void testGetDocContentForNotIncludedDocumentPaths() throws Exception {    
    final String getChangesSiteCollection726 =
        loadTestString("testModifiedGetDocIdsClient.changes-sc.xml");
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE)
            .register(new SiteAndWebExchange(
                "http://localhost:1/sites/other", 0, 
                "http://localhost:1/sites/other", 
                "http://localhost:1/sites/other")))
        .endpoint("http://localhost:1/sites/other/_vti_bin/SiteData.asmx",
            MockSiteData.blank())        
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE)
            .register(new ChangesExchange(ObjectType.SITE_COLLECTION,
                    "{bb3bb2dd-6ea7-471b-a361-6fb67988755c}",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    null,
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    600, getChangesSiteCollection726, false)));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server",
        "http://localhost:1/sites/SiteCollection");
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest requestOtherSC = new DocRequest(
        new DocId("http://localhost:1/sites/other"));
    RecordingResponse responseOtherSC = new RecordingResponse();
    adaptor.getDocContent(requestOtherSC, responseOtherSC);
    assertEquals(State.NOT_FOUND, responseOtherSC.getState());
    
    DocRequest requestRoot = new DocRequest(new DocId(""));
    RecordingResponse responseRoot = new RecordingResponse();
    adaptor.getDocContent(requestRoot, responseRoot);
    assertEquals(State.NOT_FOUND, responseRoot.getState());
    
  }

  @Test
  public void testGetDocContentVirtualServer() throws Exception {
    MockPeopleSoap mockPeople = new MockPeopleSoap();    
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE", 
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\Administrator", "dministrator", 
        SPPrincipalType.USER);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(new DocRequest(new DocId("")), response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>http://localhost:1/</title></head>"
        + "<body><h1><!--googleoff: index-->Virtual Server"
        +   "<!--googleon: index--> http://localhost:1/</h1>"
        + "<p><!--googleoff: index-->Sites<!--googleon: index--></p><ul>"
        // These are relative URLs to DocIds that are URLs, and thus the "./"
        // prefix is correct.
        + "<li><a href=\"./http://localhost:1\">localhost:1</a></li>"
        + "<li><a href=\"./http://localhost:1/sites/SiteCollection\">"
        + "SiteCollection</a></li>"
        + "</ul></body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR, GDC_PSL_SPUSER1,
            NT_AUTHORITY_LOCAL_SERVICE)).build(), response.getAcl());
    assertNull(response.getDisplayUrl());
  }
  
  @Test
  public void testGetDocContentVirtualServerWithTrailingSlashSC()
      throws Exception {
    MockPeopleSoap mockPeople = new MockPeopleSoap();    
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE", 
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult(
        "GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult(
        "GDC-PSL\\Administrator", "dministrator", SPPrincipalType.USER);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE.
                replaceInContent("http://localhost:1/sites/SiteCollection",
                    "http://localhost:1/sites/SiteCollection/"))
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(new DocRequest(new DocId("")), response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>http://localhost:1/</title></head>"
        + "<body><h1><!--googleoff: index-->Virtual Server"
        +   "<!--googleon: index--> http://localhost:1/</h1>"
        + "<p><!--googleoff: index-->Sites<!--googleon: index--></p><ul>"
        // These are relative URLs to DocIds that are URLs, and thus the "./"
        // prefix is correct.
        + "<li><a href=\"./http://localhost:1\">localhost:1</a></li>"
        + "<li><a href=\"./http://localhost:1/sites/SiteCollection\">"
        + "SiteCollection</a></li>"
        + "</ul></body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR, GDC_PSL_SPUSER1,
            NT_AUTHORITY_LOCAL_SERVICE)).build(), response.getAcl());
    assertNull(response.getDisplayUrl());
  }

 @Test
  public void testGetDocContentWithMultipleSC() throws Exception {
    MockPeopleSoap mockPeople = new MockPeopleSoap();
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE",
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\Administrator", "administrator",
        SPPrincipalType.USER);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE)
            .register(new SiteAndWebExchange(
                "http://localhost:1/sites/SiteCollection/web", 0,
                "http://localhost:1/sites/SiteCollection",
                "http://localhost:1/sites/SiteCollection/web"))
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE
                .replaceInContent("</Sites>", "<Site "
                    + "URL=\"http://localhost:1/sites/SiteCollectionOneMore\" "
                    + "ID=\"{5cbcd3b1-fca9-48b2-92db-OneMore}\" />"
                    + "</Sites>"))
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);
    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server", "http://localhost:1/");
    config.overrideKey("sharepoint.siteCollectionsToInclude",
        "http://localhost:1,http://localhost:1/sites/SiteCollectionOneMore,");
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(new DocRequest(new DocId("")), response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>http://localhost:1/</title></head>"
        + "<body><h1><!--googleoff: index-->Virtual Server"
        +   "<!--googleon: index--> http://localhost:1/</h1>"
        + "<p><!--googleoff: index-->Sites<!--googleon: index--></p><ul>"
        // These are relative URLs to DocIds that are URLs, and thus the "./"
        // prefix is correct.
        + "<li><a href=\"./http://localhost:1\">localhost:1</a></li>"
        + "<li><a href=\"./http://localhost:1/sites/SiteCollectionOneMore\">"
        + "SiteCollectionOneMore</a></li>"
        + "</ul></body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR, GDC_PSL_SPUSER1,
            NT_AUTHORITY_LOCAL_SERVICE)).build(), response.getAcl());
    assertNull(response.getDisplayUrl());
    // Request to fetch doc content from excluded site collection
    DocRequest requestOtherSC = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/web"));
    RecordingResponse responseOtherSC = new RecordingResponse();
    adaptor.getDocContent(requestOtherSC, responseOtherSC);
    assertEquals(State.NOT_FOUND, responseOtherSC.getState());
  }

  @Test
  public void testGetDocContentMultipleSCInvalidServerURL() throws Exception {
    MockPeopleSoap mockPeople = new MockPeopleSoap();
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE",
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\Administrator", "administrator",
        SPPrincipalType.USER);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE
                .replaceInContent("</Sites>", "<Site "
                    + "URL=\"http://localhost:1/sites/SiteCollectionOneMore\" "
                    + "ID=\"{5cbcd3b1-fca9-48b2-92db-OneMore}\" />"
                    + "</Sites>"))
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server",
        "http://localhost:1/sites/siteCollections");
    config.overrideKey("sharepoint.siteCollectionsToInclude",
        "http://localhost:1,http://localhost:1/sites/SiteCollectionOneMore,");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }

  @Test
  public void testGetDocContentMultipleSCWithSCOnlyFlag() throws Exception {
    MockPeopleSoap mockPeople = new MockPeopleSoap();
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE",
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\Administrator", "administrator",
        SPPrincipalType.USER);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE)
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE
                .replaceInContent("</Sites>", "<Site "
                    + "URL=\"http://localhost:1/sites/SiteCollectionOneMore\" "
                    + "ID=\"{5cbcd3b1-fca9-48b2-92db-OneMore}\" />"
                    + "</Sites>"))
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.siteCollectionOnly", "true");
    config.overrideKey("sharepoint.siteCollectionsToInclude",
        "http://localhost:1/sites/SiteCollectionOneMore,");
    thrown.expect(InvalidConfigurationException.class);
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor = null;
  }

  @Test
  public void testGetDocContentMultipleSCExcludeRootSC() throws Exception {
    MockPeopleSoap mockPeople = new MockPeopleSoap();
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE",
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\Administrator", "administrator",
        SPPrincipalType.USER);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE)
            .register(new SiteAndWebExchange(
                "http://localhost:1/web", 0,
                "http://localhost:1",
                "http://localhost:1/web"))
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE
                .replaceInContent("</Sites>", "<Site "
                    + "URL=\"http://localhost:1/sites/SiteCollectionOneMore\" "
                    + "ID=\"{5cbcd3b1-fca9-48b2-92db-OneMore}\" />"
                    + "</Sites>"))
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server", "http://localhost:1/");
    config.overrideKey("sharepoint.siteCollectionsToInclude",
        "http://localhost:1/sites/SiteCollectionOneMore,");
    adaptor.init(new MockAdaptorContext(config, pusher));

    RecordingResponse response = new RecordingResponse();
    DocRequest requestOtherSC = new DocRequest(
        new DocId("http://localhost:1/web"));
    RecordingResponse responseOtherSC = new RecordingResponse();
    adaptor.getDocContent(requestOtherSC, responseOtherSC);
    assertEquals(State.NOT_FOUND, responseOtherSC.getState());
  }

  @Test
  public void testGetDocContentVirtualServerContentDBError()
      throws Exception {
    MockPeopleSoap mockPeople = new MockPeopleSoap();    
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE", 
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\Administrator", "dministrator", 
        SPPrincipalType.USER);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE.
                replaceInContent("</ContentDatabases>",
                    "<ContentDatabase ID=\"{error content db}\" />"
                    + "</ContentDatabases>"))
            .register(CD_CONTENT_EXCHANGE)
            .register(new ContentExchange(
                ObjectType.CONTENT_DATABASE, "{error content db}", null, null,
                true, false, null, "error", false,
                new WebServiceException("Content database not available")))
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,        
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(new DocRequest(new DocId("")), response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>http://localhost:1/</title></head>"
        + "<body><h1><!--googleoff: index-->Virtual Server"
        +   "<!--googleon: index--> http://localhost:1/</h1>"
        + "<p><!--googleoff: index-->Sites<!--googleon: index--></p><ul>"
        // These are relative URLs to DocIds that are URLs, and thus the "./"
        // prefix is correct.
        + "<li><a href=\"./http://localhost:1\">localhost:1</a></li>"
        + "<li><a href=\"./http://localhost:1/sites/SiteCollection\">"
        + "SiteCollection</a></li>"
        + "</ul></body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR, GDC_PSL_SPUSER1,
            NT_AUTHORITY_LOCAL_SERVICE)).build(), response.getAcl());
    assertNull(response.getDisplayUrl());
  }
  
  @Test
  public void testPolicyAclsWithClaims() throws Exception {
    String claimsPolicyUsers = "<PolicyUser "
        + "LoginName=\"i:0#.w|GSA-CONNECTORS\\Administrator\" "
        + "BinaryIdentifier=\"i:0).w|s-1-5-21-3993744865-352142399"
        + "7-1479072767-500\" Sid=\"\" BinaryIdentifierType=\"UserKey\" "
        + "GrantMask=\"9223372036854775807\" DenyMask=\"0\" />"
        + "<PolicyUser "
        + "LoginName=\"c:0+.w|s-1-5-21-3993744865-3521423997-1479072767-513\" "
        + "BinaryIdentifier=\"c:0+.w|s-1-5-21-3993744865-3521423997"
        + "-1479072767-513\" Sid=\"\" BinaryIdentifierType=\"UserKey\" "
        + "GrantMask=\"4611686224789442657\" "
        + "DenyMask=\"0\" />"
        + "<PolicyUser "
        + "LoginName=\"i:0e.t|adfsv2|spuat.adaptor@gsa-connectors.com\" "
        + "BinaryIdentifier=\"i:0e.t|adfsv2|spuat.adaptor@gsa-connectors.com\" "
        + "Sid=\"\" BinaryIdentifierType=\"UserKey\" "
        + "GrantMask=\"4611686224789442657\" DenyMask=\"0\" />"
        + "<PolicyUser "
        + "LoginName=\"c:0-.t|adfsv2|grouplevel1@gsa-connectors.com\" "
        + "BinaryIdentifier=\"c:0-.t|adfsv2|grouplevel1@gsa-connectors.com\" "
        + "Sid=\"\" BinaryIdentifierType=\"UserKey\" "
        + "GrantMask=\"4611686224789442657\" DenyMask=\"0\" />"
        + "<PolicyUser "
        + "LoginName=\"c:0?.t|adfsv2|group1.nameid@gsa-connectors.com\" "
        + "BinaryIdentifier=\"c:0?.t|adfsv2|group1.nameid@gsa-connectors.com\" "
        + "Sid=\"\" BinaryIdentifierType=\"UserKey\" "
        + "GrantMask=\"4611686224789442657\" DenyMask=\"0\" />"      
        + "<PolicyUser "
        + "LoginName=\"i:0?.t|adfsv2|spuat.adaptor.nameid@gsa-connectors.com\" "
        + "BinaryIdentifier=\"i:0e.t|adfsv2|spuat.adaptor.nameid@gsa-"
        + "connectors.com\" Sid=\"\" BinaryIdentifierType=\"UserKey\" "
        + "GrantMask=\"4611686224789442657\" DenyMask=\"0\" />"
        + "<PolicyUser "
        + "LoginName=\"i:0aa.t|adfsv2|invalidclaim\" "
        + "BinaryIdentifier=\"i:0aa.t|adfsv2|invalidclaim\" "
        + "Sid=\"\" BinaryIdentifierType=\"UserKey\" "
        + "GrantMask=\"4611686224789442657\" DenyMask=\"0\" />"
        + "</Policies>";
    MockPeopleSoap mockPeople = new MockPeopleSoap();
    mockPeople.addToResult("i:0#.w|GSA-CONNECTORS\\Administrator",
        "Administrator", SPPrincipalType.USER);
    mockPeople.addToResult(
        "c:0+.w|s-1-5-21-3993744865-3521423997-1479072767-513",
        "GSA-CONNECTORS\\domain users", SPPrincipalType.SECURITY_GROUP);
    mockPeople.addToResult("NT AUTHORITY\\LOCAL SERVICE", 
        "NT AUTHORITY\\LOCAL SERVICE", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\spuser1", "spuser1", SPPrincipalType.USER);
    mockPeople.addToResult("GDC-PSL\\Administrator", "dministrator", 
        SPPrincipalType.USER);
    mockPeople.addToResult("i:0e.t|adfsv2|spuat.adaptor@gsa-connectors.com",
        "spuat.adaptor@gsa-connectors.com", SPPrincipalType.USER);
    mockPeople.addToResult("c:0-.t|adfsv2|grouplevel1@gsa-connectors.com",
        "grouplevel1@gsa-connectors.com", SPPrincipalType.SECURITY_GROUP);
    mockPeople.addToResult(
        "i:0?.t|adfsv2|spuat.adaptor.nameid@gsa-connectors.com",
        "spuat.adaptor.nameid@gsa-connectors.com", SPPrincipalType.USER);
    mockPeople.addToResult("c:0?.t|adfsv2|group1.nameid@gsa-connectors.com",
        "group1.nameid@gsa-connectors.com", SPPrincipalType.SECURITY_GROUP);
    mockPeople.addToResult("i:0aa.t|adfsv2|invalidclaim",
        "invalidclaim", SPPrincipalType.USER);
    
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE
              .replaceInContent("</Policies>", claimsPolicyUsers))
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint("http://localhost:1/_vti_bin/People.asmx", mockPeople);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory, 
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(new DocRequest(new DocId("")), response);       
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR, GDC_PSL_SPUSER1,
            NT_AUTHORITY_LOCAL_SERVICE, new UserPrincipal(
                "GSA-CONNECTORS\\Administrator", DEFAULT_NAMESPACE),
            new UserPrincipal("spuat.adaptor@gsa-connectors.com",
                DEFAULT_NAMESPACE),
            new UserPrincipal("spuat.adaptor.nameid@gsa-connectors.com",
                DEFAULT_NAMESPACE)))
        .setPermitGroups(Arrays.asList(new GroupPrincipal(
            "GSA-CONNECTORS\\Domain Users", DEFAULT_NAMESPACE),
            new GroupPrincipal("grouplevel1@gsa-connectors.com",
                DEFAULT_NAMESPACE),
            new GroupPrincipal("group1.nameid@gsa-connectors.com",
                DEFAULT_NAMESPACE)))
        .build(),
        response.getAcl());
    assertNull(response.getDisplayUrl());
  }

  @Test
  public void testGetDocContentSiteCollection() throws Exception {    
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }
    
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>chinese1</title></head>"
        + "<body><h1><!--googleoff: index-->Site<!--googleon: index-->"
        +   " chinese1</h1>"
        + "<p><!--googleoff: index-->Sites<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/somesite\">"
        + "http://localhost:1/sites/SiteCollection/somesite</a></li></ul>"
        + "<p><!--googleoff: index-->Lists<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/Lists/Announcements/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Lists/Announcements/AllItems.aspx</a></li>"
        + "<li><a href=\"SiteCollection/Shared%20Documents/Forms/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Shared Documents/Forms/AllItems.aspx</a>"
        + "</li></ul>"
        + "<p><!--googleoff: index-->Folders<!--googleon: index--></p>"
        + "<ul></ul>"
        + "<p><!--googleoff: index-->List Items<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/default.aspx\">"
        + "default.aspx</a></li></ul>"
        + "</body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(
            SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS,
            SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1)).build(),
        response.getAcl());
    assertEquals(Collections.singletonMap("admin", new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setPermitUsers(Arrays.asList(new UserPrincipal("GDC-PSL\\spuser1"),
            new UserPrincipal("GDC-PSL\\administrator")))
        .setInheritFrom(new DocId(""))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .build()), response.getNamedResources());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection"),
        response.getDisplayUrl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }
  
  @Test
  public void testGetDocContentSCVerifyGroupPush() throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups1;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_REVIEWERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE)));
      goldenGroups1 = Collections.unmodifiableMap(tmp);
    }
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups2;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups2 = Collections.unmodifiableMap(tmp);
    }

    ContentExchange scContentExchange1 = new ContentExchange(
        ObjectType.SITE_COLLECTION, null, null, null, true, false, null,
        loadTestString("sites-SiteCollection-sc-useonce.xml"), true);
    ContentExchange scContentExchange2 = new ContentExchange(
        ObjectType.SITE_COLLECTION, null, null, null, true, false, null,
        loadTestString("sites-SiteCollection-sc.xml"), false);

    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(scContentExchange1)
            .register(scContentExchange2));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());

    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response1 = new RecordingResponse();
    // First call returns 3 groups(Owners, Members with 1 user and Reviewers
    // with 1 user
    adaptor.getDocContent(request, response1);
    assertEquals(goldenGroups1, pusher.getGroupDefinitions());

    RecordingResponse response2 = new RecordingResponse();
    // Second call returns 3 groups(Owners, Members with 2 users and Visitors)
    adaptor.getDocContent(request, response2);
    assertEquals(goldenGroups2, pusher.getGroupDefinitions());
  }

  public void testGetDocContentSiteCollectionWithSidlookup() throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }

    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE));
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>chinese1</title></head>"
        + "<body><h1><!--googleoff: index-->Site<!--googleon: index-->"
        +   " chinese1</h1>"
        + "<p><!--googleoff: index-->Sites<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/somesite\">"
        + "http://localhost:1/sites/SiteCollection/somesite</a></li></ul>"
        + "<p><!--googleoff: index-->Lists<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/Lists/Announcements/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Lists/Announcements/AllItems.aspx</a></li>"
        + "<li><a href=\"SiteCollection/Shared%20Documents/Forms/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Shared Documents/Forms/AllItems.aspx</a>"
        + "</li></ul>"
        + "<p><!--googleoff: index-->Folders<!--googleon: index--></p>"
        + "<ul></ul>"
        + "<p><!--googleoff: index-->List Items<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/default.aspx\">"
        + "default.aspx</a></li></ul>"
        + "</body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(
            SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS,
            SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1)).build(),
        response.getAcl());
    assertEquals(Collections.singletonMap("admin", new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setPermitUsers(Arrays.asList(new UserPrincipal("GDC-PSL\\spuser1"),
            new UserPrincipal("GDC-PSL\\administrator")))
        .setInheritFrom(new DocId(""))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .build()), response.getNamedResources());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection"),
        response.getDisplayUrl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }
  
  @Test
  public void testGetDocContentSiteCollectionOnly() throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }
    
    final String getChangesSiteCollection726 =
        loadTestString("testModifiedGetDocIdsClient.changes-sc.xml");
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE)
            .register(new ChangesExchange(ObjectType.SITE_COLLECTION,
                    "{bb3bb2dd-6ea7-471b-a361-6fb67988755c}",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    null,
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    600, getChangesSiteCollection726, false)));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server",
        "http://localhost:1/sites/SiteCollection");
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>chinese1</title></head>"
        + "<body><h1><!--googleoff: index-->Site<!--googleon: index-->"
        +   " chinese1</h1>"
        + "<p><!--googleoff: index-->Sites<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/somesite\">"
        + "http://localhost:1/sites/SiteCollection/somesite</a></li></ul>"
        + "<p><!--googleoff: index-->Lists<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/Lists/Announcements/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Lists/Announcements/AllItems.aspx</a></li>"
        + "<li><a href=\"SiteCollection/Shared%20Documents/Forms/"
        +   "AllItems.aspx\">"
        + "/sites/SiteCollection/Shared Documents/Forms/AllItems.aspx</a>"
        + "</li></ul>"
        + "<p><!--googleoff: index-->Folders<!--googleon: index--></p>"
        + "<ul></ul>"
        + "<p><!--googleoff: index-->List Items<!--googleon: index--></p>"
        + "<ul><li><a href=\"SiteCollection/default.aspx\">"
        + "default.aspx</a></li></ul>"
        + "</body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(
            SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS,
            SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1)).build(),
        response.getAcl());
    assertEquals(Collections.singletonMap("admin", new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(new UserPrincipal("GDC-PSL\\spuser1"),
            new UserPrincipal("GDC-PSL\\administrator")))       
        .build()), response.getNamedResources());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection"),
        response.getDisplayUrl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }

  @Test
  public void testGetDocContentSubSiteUniquePermissions() throws Exception {
    String subSiteUrl = "http://localhost:1/sites/SiteCollection/SubSite";
    Users users = new Users();
    users.getUser().add(createUserGroupUser(1, "GDC-PSL\\administrator",
        "S-1-5-21-7369146", "Administrator", "admin@domain.com", false, true));
    users.getUser().add(createUserGroupUser(7, "GDC-PSL\\User1",
        "S-1-5-21-736911", "User1", "User1@domain.com", false, false));
    users.getUser().add(createUserGroupUser(500, "GDC-PSL\\User500",
        "S-1-5-21-7369500", "User500", "User11@domain.com", false, false));
    users.getUser().add(createUserGroupUser(300, "GDC-PSL\\Group300",
        "S-1-5-21-7369300", "Group300", "Group300@domain.com", true, false));
    users.getUser().add(createUserGroupUser(1073741823, "System.Account",
        "S-1-5-21-7369343", "System Account", "System.Account@domain.com",
        false, true));

    MockUserGroupSoap mockUserGroupSoap = new MockUserGroupSoap(users);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE)
            .register(new SiteAndWebExchange(subSiteUrl, 0,
                "http://localhost:1/sites/SiteCollection", subSiteUrl)))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE))
        .endpoint(subSiteUrl + "/_vti_bin/SiteData.asmx", MockSiteData.blank()
                .register(new URLSegmentsExchange(
                    subSiteUrl, true, "WebId", null, null, null))
                .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
                    .replaceInContent("/SiteCollection",
                        "/SiteCollection/SubSite")
                    .replaceInContent(
                        "ScopeID=\"{01abac8c-66c8-4fed-829c-8dd02bbf40dd}\"",
                        "ScopeID=\"{O7ac581ea-fdd1-4b0d-a5de-fc1b69e57a8d}\"")
                    .replaceInContent(
                        "<permission memberid='4' mask='756052856929' />",
                        "<permission memberid='4' mask='0' />")
                    .replaceInContent("</permissions>",
                        "<permission memberid='500' mask='756052856929' />"
                        + "<permission memberid='300' mask='756052856929' />"
                        + "</permissions>"))
                .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE))
        .endpoint("http://localhost:1/sites/SiteCollection/"
            + "_vti_bin/UserGroup.asmx", mockUserGroupSoap);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/SubSite"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
   
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(
            SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS,
            new GroupPrincipal("GDC-PSL\\Group300", DEFAULT_NAMESPACE)))
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1, 
            new UserPrincipal("GDC-PSL\\User500", DEFAULT_NAMESPACE))).build(),
        response.getAcl());
  }

  @Test
  public void testGetDocContentSubSiteUniquePermissionsInvalidUser()
      throws Exception {
    String subSiteUrl = "http://localhost:1/sites/SiteCollection/SubSite";

    MockUserGroupSoapException mockUserGroupSoapException =
        new MockUserGroupSoapException();
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE)
            .register(new SiteAndWebExchange(subSiteUrl, 0,
                "http://localhost:1/sites/SiteCollection", subSiteUrl)))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE))
        .endpoint(subSiteUrl + "/_vti_bin/SiteData.asmx", MockSiteData.blank()
                .register(new URLSegmentsExchange(
                    subSiteUrl, true, "WebId", null, null, null))
                .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
                    .replaceInContent("/SiteCollection",
                        "/SiteCollection/SubSite")
                    .replaceInContent(
                        "ScopeID=\"{01abac8c-66c8-4fed-829c-8dd02bbf40dd}\"",
                        "ScopeID=\"{O7ac581ea-fdd1-4b0d-a5de-fc1b69e57a8d}\"")
                    .replaceInContent(
                        "<permission memberid='4' mask='756052856929' />",
                        "<permission memberid='4' mask='0' />")
                    .replaceInContent("</permissions>",
                        "<permission memberid='500' mask='756052856929' />"
                        + "<permission memberid='300' mask='756052856929' />"
                        + "</permissions>"))
                .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE))
        .endpoint("http://localhost:1/sites/SiteCollection/"
            + "_vti_bin/UserGroup.asmx", mockUserGroupSoapException);

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/SubSite"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);

    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(
            SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS))
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1)).build(),
        response.getAcl());
    assertEquals(2,
        mockUserGroupSoapException.atomicNumberGetSiteUserMappingCalls.get());
  }

  @Test
  public void testGetDocContentSCInvalidUserWithOutOfDateMemberCache()
      throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser100", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }

    ReferenceSiteData siteData = new ReferenceSiteData();
    MockUserGroupSoapException mockUserGroupSoapException
        = new MockUserGroupSoapException();
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, siteData)
        .endpoint("http://localhost:1/sites/SiteCollection/"
            + "_vti_bin/UserGroup.asmx", mockUserGroupSoapException);
    SiteDataSoap siteDataState1 = MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE);
    SiteDataSoap siteDataState2 = MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
              .replaceInContent(" memberid='2'", " memberid='100'"))
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE
              // Purposefully leave ID=2 alone. The 6 and spuser2 here is simply
              // an otherwise-unused entry.
              .replaceInContent("<User ID=\"6\"", "<User ID=\"100\"")
              .replaceInContent("spuser2", "spuser100"));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    // This populates the cache, but otherwise doesn't test anything new.
    siteData.setSiteDataSoap(siteDataState1);
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1)).build(),
        response.getAcl());

    // Were we able to pick up the new user in the ACLs?
    siteData.setSiteDataSoap(siteDataState2);
    response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser100", DEFAULT_NAMESPACE)))
        .build(),
        response.getAcl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
    assertEquals(1,
        mockUserGroupSoapException.atomicNumberGetSiteUserMappingCalls.get());
  }

  @Test
  public void testGetDocContentSiteCollectionWithOutOfDateMemberCache()
      throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser100", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }

    ReferenceSiteData siteData = new ReferenceSiteData();
    Users users = new Users();
    MockUserGroupSoap mockUserGroupSoap = new MockUserGroupSoap(users);
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, siteData)
        .endpoint("http://localhost:1/sites/SiteCollection/"
            + "_vti_bin/UserGroup.asmx", mockUserGroupSoap);
    SiteDataSoap siteDataState1 = MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE);
    SiteDataSoap siteDataState2 = MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
              .replaceInContent(" memberid='2'", " memberid='100'"))
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE
              // Purposefully leave ID=2 alone. The 6 and spuser2 here is simply
              // an otherwise-unused entry.
              .replaceInContent("<User ID=\"6\"", "<User ID=\"100\"")
              .replaceInContent("spuser2", "spuser100"));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    // This populates the cache, but otherwise doesn't test anything new.
    siteData.setSiteDataSoap(siteDataState1);
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1)).build(),
        response.getAcl());

    // Were we able to pick up the new user in the ACLs?
    siteData.setSiteDataSoap(siteDataState2);
    response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser100", DEFAULT_NAMESPACE)))
        .build(),
        response.getAcl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }

  @Test
  public void testGetDocContentSiteCollectionWithAdGroup() throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          new GroupPrincipal("GDC-PSL\\administrator", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.<Principal>asList(
            new GroupPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new GroupPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }
    
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
              .replaceInContent("LoginName=\"GDC-PSL\\spuser1\"",
                "LoginName=\"GDC-PSL\\group\"")
              .replaceInContent("IsDomainGroup=\"False\"",
                "IsDomainGroup=\"True\""))
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE
              .replaceInContent("LoginName=\"GDC-PSL\\spuser1\"",
                "LoginName=\"GDC-PSL\\group\"")
              .replaceInContent("IsDomainGroup=\"False\"",
                "IsDomainGroup=\"True\"")));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS,
            new GroupPrincipal("GDC-PSL\\group", DEFAULT_NAMESPACE))).build(),
        response.getAcl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }

  @Test
  public void testGetDocContentSiteCollectionWithClaims() throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }

    String permissions = "<permission memberid='11' mask='756052856929' />"
        + "<permission memberid='12' mask='756052856929' />"
        + "<permission memberid='13' mask='756052856929' />"
        + "<permission memberid='14' mask='756052856929' />"
        + "<permission memberid='15' mask='756052856929' />"        
        + "<permission memberid='19' mask='756052856929' /></permissions>";
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
              .replaceInContent("</permissions>", permissions))
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE
              .replaceInContent("</permissions>", permissions)));

    
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1,
            new UserPrincipal("GSA-CONNECTORS\\User1", DEFAULT_NAMESPACE),
            new UserPrincipal("membershipprovider:user2007",
              DEFAULT_NAMESPACE)))
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS,
            new GroupPrincipal("GSA-CONNECTORS\\domain users",
              DEFAULT_NAMESPACE),
            new GroupPrincipal("Everyone", DEFAULT_NAMESPACE),
            NT_AUTHORITY_AUTHENTICATED_USERS,
            new GroupPrincipal("roleprovider:super", DEFAULT_NAMESPACE)))
        .build(),
        response.getAcl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }
    
  @Test
  public void testGetDocContentSiteCollectionWithClaimsSidLookup()
      throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }

    String permissions = "<permission memberid='11' mask='756052856929' />"
        + "<permission memberid='12' mask='756052856929' />"
        + "<permission memberid='13' mask='756052856929' />"
        + "<permission memberid='14' mask='756052856929' />"
        + "<permission memberid='15' mask='756052856929' />"        
        + "<permission memberid='19' mask='756052856929' /></permissions>";
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(VS_CONTENT_EXCHANGE)
            .register(CD_CONTENT_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
            .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
                .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
                .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
                    .replaceInContent("</permissions>", permissions))
                    .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE
                        .replaceInContent("</permissions>", permissions)));
    
    Map<String, String> sidLookup = new HashMap<String, String>();
    sidLookup.put(
        "s-1-5-21-3993744865-3521423997-1479072767", "gsa-connectors");
    sidLookup.put(
        "s-1-5-21-3993744865-3521423997-1479072767-513", "domain-users");
    MockActiveDirectoryClientFactory adClientFactory =
        new MockActiveDirectoryClientFactory(
            new MockADServer(Collections.unmodifiableMap(sidLookup)));
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        adClientFactory);
    config.overrideKey("sidLookup.host", "adhost");
    config.overrideKey("sidLookup.username", "aduser");
    config.overrideKey("sidLookup.password", "password");
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(new Acl.Builder()
    .setEverythingCaseInsensitive()
    .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
        "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitUsers(Arrays.asList(GDC_PSL_SPUSER1,
            new UserPrincipal("GSA-CONNECTORS\\User1", DEFAULT_NAMESPACE),
            new UserPrincipal("membershipprovider:user2007",
                DEFAULT_NAMESPACE)))
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS,
            new GroupPrincipal("GSA-CONNECTORS\\domain-users",
                DEFAULT_NAMESPACE),
            new GroupPrincipal("Everyone", DEFAULT_NAMESPACE),
            NT_AUTHORITY_AUTHENTICATED_USERS,
            new GroupPrincipal("roleprovider:super", DEFAULT_NAMESPACE)))
        .build(),response.getAcl());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }

  public void testGetDocContentSiteCollectionNoIndex() throws Exception {
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE)
            .register(ROOT_SITE_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
              .replaceInContent("NoIndex=\"False\"", "NoIndex=\"True\"")));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection"));
    RecordingResponse response = new RecordingResponse();
    adaptor.getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }

  @Test
  public void testGetDocContentList() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_F_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE);

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "AllItems.aspx"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>Custom List</title></head>"
        + "<body><h1><!--googleoff: index-->List<!--googleon: index-->"
        +   " Custom List</h1>"
        + "<p><!--googleoff: index-->List Items<!--googleon: index--></p>"
        + "<ul>"
        + "<li><a href=\"3_.000\">Outside Folder</a></li>"
        + "<li><a href=\"Test%20Folder\">Test Folder</a></li>"
        + "</ul></body></html>";
    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId(
            "http://localhost:1/sites/SiteCollection/Lists/Custom List"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .build(),
        response.getAcl());
    // Verify named resource for List Root Folder
    assertEquals(Collections.singletonMap(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List"),
        new Acl.Builder()
          .setEverythingCaseInsensitive()
          .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
              "admin")
          .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
          .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
              SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS))
          .build()),
        pusher.getNamedResources());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/AllItems.aspx"), response.getDisplayUrl());
    assertEquals(new Date(1336166672000L), response.getLastModified());
  }

  @Test
  public void testGetDocContentListNoIndex() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE
          .replaceInContent("NoIndex=\"False\"", "NoIndex=\"True\""));

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "AllItems.aspx"));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          new UnsupportedCallable<MemberIdMapping>(),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }

  @Test
  public void testGetDocContentListNonDefaultView() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)       
        .register(new URLSegmentsExchange(
          "http://localhost:1/sites/SiteCollection/Lists/Custom List"
          + "/NonDefault.aspx", false, null, null, null, null))
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE);
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "NonDefault.aspx"));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          new UnsupportedCallable<MemberIdMapping>(),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }
  
  @Test
  public void testGetDocContentListEmptyDefaultView() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE
            .replaceInContent("DefaultViewUrl=\"/sites/SiteCollection/Lists/"
            + "Custom List/AllItems.aspx\"", "DefaultViewUrl=\"/\""))        
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_F_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(new URLSegmentsExchange(
              "http://localhost:1/sites/SiteCollection/Lists/Custom List",
               true, null, null, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}",
               null));
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List"));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    // Verify display URL for List document
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List"), response.getDisplayUrl());    
  }
  

  @Test
  public void testGetDocContentAttachment() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE);
    final String site = "http://localhost:1/sites/SiteCollection";
    final String attachmentId = site + "/Lists/Custom List/Attachments/2/104600"
        + "0.pdf";

    final String goldenContents = "attachment contents";
    final String goldenContentType = "fake/type";
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new HttpClient() {
      @Override
      public FileInfo issueGetRequest(URL url,
          List<String> authenticationCookies, String adaptorUserAgent,
          int maxRedirectsToFollow, boolean performBrowserLeniency) {
        assertEquals(
          "http://localhost:1/sites/SiteCollection/Lists/Custom%20List/"
            + "Attachments/2/1046000.pdf",
          url.toString());
        InputStream contents = new ByteArrayInputStream(
            goldenContents.getBytes(charset));
        List<String> headers = Arrays.asList("not-the-Content-Type", "early",
            "conTent-TypE", goldenContentType, "Content-Type", "late",
            "Last-Modified", "Tue, 01 May 2012 22:14:41 GMT");
        return new FileInfo.Builder(contents).setHeaders(headers).build();
      }

      @Override
      public String getRedirectLocation(URL url,
          List<String> authenticationCookies, String adaptorUserAgent)
          throws IOException {
        assertEquals(
            "http://localhost:1/sites/SiteCollection/Lists/Custom%20List",
            url.toString());

        return "http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/AllItems.aspx";
      }

      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        throw new UnsupportedOperationException();
      }
    }, executorFactory, new MockAuthenticationClientFactoryForms(),
    new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId(attachmentId));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
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
    assertEquals(new Date(1335910481000L), response.getLastModified());
  }
  
  @Test
  public void testGetDocContentAttachmentDeletedParent() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
            .replaceInContent("data ItemCount=\"1\"", "data ItemCount=\"0\""))
        .register(new URLSegmentsExchange(
            "http://localhost:1/sites/SiteCollection/Lists/Custom List"
                + "/Attachments/2/1046000.pdf", false, null, null, null, null));

    final String site = "http://localhost:1/sites/SiteCollection";
    final String attachmentId = site 
        + "/Lists/Custom List/Attachments/2/1046000.pdf";
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new HttpClient() {
      @Override
      public FileInfo issueGetRequest(URL url,
          List<String> authenticationCookies, String adaptorUserAgent,
          int maxRedirectsToFollow, boolean performBrowserLeniency) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getRedirectLocation(URL url,
          List<String> authenticationCookies, String adaptorUserAgent)
          throws IOException {
        assertEquals(
            "http://localhost:1/sites/SiteCollection/Lists/Custom%20List",
            url.toString());

        return "http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/AllItems.aspx";
      }

      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        throw new UnsupportedOperationException();
      }
    }, executorFactory, new MockAuthenticationClientFactoryForms(),
    new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId(attachmentId));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          new UnsupportedCallable<MemberIdMapping>(),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    assertEquals(State.NOT_FOUND, response.getState());
  }

  @Test
  public void testGetDocContentAttachmentSpecialMimeType() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE);
    final String site = "http://localhost:1/sites/SiteCollection";
    final String attachmentId = site + "/Lists/Custom List/Attachments/2/104600"
        + "0.pdf";

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new HttpClient() {
      @Override
      public FileInfo issueGetRequest(URL url,
          List<String> authenticationCookies, String adaptorUserAgent,
          int maxRedirectsToFollow, boolean performBrowserLeniency) {
        InputStream contents = new ByteArrayInputStream(new byte[0]);
        List<String> headers = Arrays.asList(
            "Content-Type", "application/vnd.ms-excel.12");
        return new FileInfo.Builder(contents).setHeaders(headers).build();
      }

      @Override
      public String getRedirectLocation(URL url,
          List<String> authenticationCookies, String adaptorUserAgent)
          throws IOException {
        assertEquals(
            "http://localhost:1/sites/SiteCollection/Lists/Custom%20List",
            url.toString());
        
        return "http://localhost:1/sites/SiteCollection/Lists/Custom List"
            + "/AllItems.aspx";
      }

      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        throw new UnsupportedOperationException();
      }
    }, executorFactory, new MockAuthenticationClientFactoryForms(),
    new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId(attachmentId));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          new UnsupportedCallable<MemberIdMapping>(),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    assertEquals(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        response.getContentType());
  }

  @Test
  public void testGetDocContentListItem() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_LI_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_A_CONTENT_EXCHANGE);

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"), new Date(1336166662000L));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>Inside Folder</title></head>"
        + "<body><h1><!--googleoff: index-->List Item<!--googleon: index-->"
        +   " Inside Folder</h1>"
        + "<p><!--googleoff: index-->Attachments<!--googleon: index--></p><ul>"
        + "<li><a href=\"../Attachments/2/1046000.pdf\">1046000.pdf</a></li>"
        + "</ul>"
        + "<!--googleoff: index--><table style='border: none'>"
        + "<tr><td>Attachments</td><td>1</td></tr>"
        + "<tr><td>Author</td><td>System Account</td></tr>"
        + "<tr><td>BaseName</td><td>2_</td></tr>"
        + "<tr><td>ContentType</td><td>Item</td></tr>"
        + "<tr><td>ContentTypeId</td>"
        +   "<td>0x0100442459C9B5E59C4F9CFDC789A220FC92</td></tr>"
        + "<tr><td>Created</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>Created Date</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>Editor</td><td>System Account</td></tr>"
        + "<tr><td>EncodedAbsUrl</td>"
        +   "<td>http://localhost:1/sites/SiteCollection/Lists/Custom%20List/"
        +   "Test%20Folder/2_.000</td></tr>"
        + "<tr><td>FSObjType</td><td>0</td></tr>"
        + "<tr><td>FileDirRef</td>"
        + "<td>sites/SiteCollection/Lists/Custom List/Test Folder</td></tr>"
        + "<tr><td>FileLeafRef</td><td>2_.000</td></tr>"
        + "<tr><td>FileRef</td>"
        +   "<td>sites/SiteCollection/Lists/Custom List/Test Folder/2_.000</td>"
        +   "</tr>"
        + "<tr><td>GUID</td>"
        +   "<td>{2C5BEF60-18FA-42CA-B472-7B5E1EC405A5}</td></tr>"
        + "<tr><td>ID</td><td>2</td></tr>"
        + "<tr><td>Last Modified</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>LinkFilename</td><td>2_.000</td></tr>"
        + "<tr><td>LinkFilenameNoMenu</td><td>2_.000</td></tr>"
        + "<tr><td>LinkTitle</td><td>Inside Folder</td></tr>"
        + "<tr><td>LinkTitleNoMenu</td><td>Inside Folder</td></tr>"
        + "<tr><td>Modified</td><td>2012-05-04T21:24:32Z</td></tr>"
        + "<tr><td>Order</td><td>200.000000000000</td></tr>"
        + "<tr><td>PermMask</td><td>0x7fffffffffffffff</td></tr>"
        + "<tr><td>ScopeId</td>"
        +   "<td>{2E29615C-59E7-493B-B08A-3642949CC069}</td></tr>"
        + "<tr><td>SelectTitle</td><td>2</td></tr>"
        + "<tr><td>ServerRedirected</td><td>0</td></tr>"
        + "<tr><td>ServerUrl</td>"
        +   "<td>/sites/SiteCollection/Lists/Custom List/Test Folder/2_.000"
        +   "</td></tr>"
        + "<tr><td>Title</td><td>Inside Folder</td></tr>"
        + "<tr><td>UniqueId</td>"
        +   "<td>{E7156244-AC2F-4402-AA74-7A365726CD02}</td></tr>"
        + "<tr><td>WorkflowVersion</td><td>1</td></tr>"
        + "<tr><td>_EditMenuTableEnd</td><td>2</td></tr>"
        + "<tr><td>_EditMenuTableStart</td><td>2_.000</td></tr>"
        + "<tr><td>_IsCurrentVersion</td><td>1</td></tr>"
        + "<tr><td>_Level</td><td>1</td></tr>"
        + "<tr><td>_ModerationStatus</td><td>0</td></tr>"
        + "<tr><td>_UIVersion</td><td>512</td></tr>"
        + "<tr><td>_UIVersionString</td><td>1.0</td></tr>"
        + "<tr><td>owshiddenversion</td><td>4</td></tr>"
        + "</table><!--googleon: index-->"
        + "</body></html>";
    final Metadata goldenMetadata;
    {
      Metadata meta = new Metadata();
      meta.add("Attachments", "1");
      meta.add("Author", "System Account");
      meta.add("BaseName", "2_");
      meta.add("ContentType", "Item");
      meta.add("ContentTypeId", "0x0100442459C9B5E59C4F9CFDC789A220FC92");
      meta.add("Created", "2012-05-01T22:14:06Z");
      meta.add("Created Date", "2012-05-01T22:14:06Z");
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
      meta.add("Last Modified", "2012-05-01T22:14:06Z");
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
      meta.add("sharepoint:parentwebtitle", "chinese1");
      meta.add("sharepoint:listguid", "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
      meta.add("google:objecttype", "ListItem");
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
    assertEquals(new Date(1336166672000L), response.getLastModified());
  }

  @Test
  public void testGetDocContentListItemMessage() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_LI_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
            .replaceInContent("ows_Title='Inside Folder'",
                "ows_DiscussionTitle='Discussion Subject'")
            .replaceInContent("ows_ContentType='Item'",
                "ows_ContentType='Message'")
            .replaceInContent("ows_Attachments='1'",
                "ows_Attachments='0'"))
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_A_CONTENT_EXCHANGE);

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"), new Date(1336166662000L));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>Discussion Subject</title></head>"
        + "<body><h1><!--googleoff: index-->List Item<!--googleon: index-->"
        +   " Discussion Subject</h1>"
        + "<!--googleoff: index--><table style='border: none'>"
        + "<tr><td>Attachments</td><td>0</td></tr>"
        + "<tr><td>Author</td><td>System Account</td></tr>"
        + "<tr><td>BaseName</td><td>2_</td></tr>"
        + "<tr><td>ContentType</td><td>Message</td></tr>"
        + "<tr><td>ContentTypeId</td>"
        +   "<td>0x0100442459C9B5E59C4F9CFDC789A220FC92</td></tr>"
        + "<tr><td>Created</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>Created Date</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>DiscussionTitle</td><td>Discussion Subject</td></tr>"
        + "<tr><td>Editor</td><td>System Account</td></tr>"
        + "<tr><td>EncodedAbsUrl</td>"
        +   "<td>http://localhost:1/sites/SiteCollection/Lists/Custom%20List/"
        +   "Test%20Folder/2_.000</td></tr>"
        + "<tr><td>FSObjType</td><td>0</td></tr>"
        + "<tr><td>FileDirRef</td>"
        +   "<td>sites/SiteCollection/Lists/Custom List/Test Folder</td></tr>"
        + "<tr><td>FileLeafRef</td><td>2_.000</td></tr>"
        + "<tr><td>FileRef</td>"
        +   "<td>sites/SiteCollection/Lists/Custom List/Test Folder/2_.000</td>"
        +   "</tr>"
        + "<tr><td>GUID</td>"
        +   "<td>{2C5BEF60-18FA-42CA-B472-7B5E1EC405A5}</td></tr>"
        + "<tr><td>ID</td><td>2</td></tr>"
        + "<tr><td>Last Modified</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>LinkFilename</td><td>2_.000</td></tr>"
        + "<tr><td>LinkFilenameNoMenu</td><td>2_.000</td></tr>"
        + "<tr><td>LinkTitle</td><td>Inside Folder</td></tr>"
        + "<tr><td>LinkTitleNoMenu</td><td>Inside Folder</td></tr>"
        + "<tr><td>Modified</td><td>2012-05-04T21:24:32Z</td></tr>"
        + "<tr><td>Order</td><td>200.000000000000</td></tr>"
        + "<tr><td>PermMask</td><td>0x7fffffffffffffff</td></tr>"
        + "<tr><td>ScopeId</td>"
        +   "<td>{2E29615C-59E7-493B-B08A-3642949CC069}</td></tr>"
        + "<tr><td>SelectTitle</td><td>2</td></tr>"
        + "<tr><td>ServerRedirected</td><td>0</td></tr>"
        + "<tr><td>ServerUrl</td>"
        +   "<td>/sites/SiteCollection/Lists/Custom List/Test Folder/2_.000"
        +   "</td></tr>"
        + "<tr><td>UniqueId</td>"
        +   "<td>{E7156244-AC2F-4402-AA74-7A365726CD02}</td></tr>"
        + "<tr><td>WorkflowVersion</td><td>1</td></tr>"
        + "<tr><td>_EditMenuTableEnd</td><td>2</td></tr>"
        + "<tr><td>_EditMenuTableStart</td><td>2_.000</td></tr>"
        + "<tr><td>_IsCurrentVersion</td><td>1</td></tr>"
        + "<tr><td>_Level</td><td>1</td></tr>"
        + "<tr><td>_ModerationStatus</td><td>0</td></tr>"
        + "<tr><td>_UIVersion</td><td>512</td></tr>"
        + "<tr><td>_UIVersionString</td><td>1.0</td></tr>"
        + "<tr><td>owshiddenversion</td><td>4</td></tr>"
        + "</table><!--googleon: index-->"
        + "</body></html>";
    final Metadata goldenMetadata;
    {
      Metadata meta = new Metadata();
      meta.add("Attachments", "0");
      meta.add("Author", "System Account");
      meta.add("BaseName", "2_");
      meta.add("ContentType", "Message");
      meta.add("ContentTypeId", "0x0100442459C9B5E59C4F9CFDC789A220FC92");
      meta.add("DiscussionTitle", "Discussion Subject");
      meta.add("Created", "2012-05-01T22:14:06Z");
      meta.add("Created Date", "2012-05-01T22:14:06Z");
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
      meta.add("Last Modified", "2012-05-01T22:14:06Z");
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
      meta.add("sharepoint:parentwebtitle", "chinese1");
      meta.add("sharepoint:listguid", "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
      meta.add("google:objecttype", "ListItem");
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
          + "Custom%20List/DispForm.aspx?ID=2&Source=/sites/"
        + "SiteCollection/Lists/Custom%20List/Test%20Folder"),
        response.getDisplayUrl());
    assertEquals(new Date(1336166672000L), response.getLastModified());
  }

  @Test
  public void testGetDocContentListItemWithNoContent() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_LI_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_A_CONTENT_EXCHANGE);

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream() {      
      @Override
      public void write(byte[] b) throws IOException {       
        throw new UnsupportedOperationException("No response expected");
      } 
      @Override
      public synchronized void write(byte[] b, int off, int len) {
        throw new UnsupportedOperationException("No response expected");
      }      
    };
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"), new Date(1336166672000L));    
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);   
    final Metadata goldenMetadata;
    {
      Metadata meta = new Metadata();
      meta.add("Attachments", "1");
      meta.add("Author", "System Account");
      meta.add("BaseName", "2_");
      meta.add("ContentType", "Item");
      meta.add("ContentTypeId", "0x0100442459C9B5E59C4F9CFDC789A220FC92");
      meta.add("Created", "2012-05-01T22:14:06Z");
      meta.add("Created Date", "2012-05-01T22:14:06Z");
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
      meta.add("Last Modified", "2012-05-01T22:14:06Z");
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
      meta.add("sharepoint:parentwebtitle", "chinese1");
      meta.add("sharepoint:listguid", "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
      meta.add("google:objecttype", "ListItem");
      goldenMetadata = meta.unmodifiableView();
    }   
    assertEquals(goldenMetadata, response.getMetadata());
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection/"
            + "Lists/Custom List/Test Folder"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/DispForm.aspx?ID=2"),
        response.getDisplayUrl());
    assertEquals(new Date(1336166672000L), response.getLastModified());
    assertEquals(State.NO_CONTENT, response.getState());
  }

  @Test
  public void testGetDocContentListItemWithListAsParent() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
            .replaceInContent("ows_Attachments='1'", "ows_Attachments='0'")
            .replaceInContent("Inside Folder", "Under List")
            .replaceInContent("/Test Folder", "")
            .replaceInContent("/Test%20Folder", "")
            .replaceInContent(
              "ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'",
              "ows_ScopeId='2;#{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}'"))
        .register(new URLSegmentsExchange(
            "http://localhost:1/sites/SiteCollection/Lists/Custom List/2_.000",
          true, null, null, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "2"));
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List"
          + "/2_.000"));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    // just verify ACLs here
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection/"
            + "Lists/Custom List"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/DispForm.aspx?ID=2"),
        response.getDisplayUrl());
  }
  
  @Test
  public void testGetDocContentMsgFile() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
            .replaceInContent("2_.000", "outlookFile.msg")
            .replaceInContent("ows_ContentTypeId='0x0100",
                "ows_ContentTypeId='0x0101")
            .replaceInContent("ows_Attachments='1'", "ows_Attachments='0'")
            .replaceInContent("Inside Folder", "Under List")
            .replaceInContent("/Test Folder", "")
            .replaceInContent("/Test%20Folder", "")
            .replaceInContent(
              "ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'",
              "ows_ScopeId='2;#{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}'"))
        .register(new URLSegmentsExchange("http://localhost:1/sites/"
            + "SiteCollection/Lists/Custom List/outlookFile.msg",
          true, null, null, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "2"));
    final String goldenContents = "msg contents";
    final String goldenContentType = "application/octet-stream";
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new HttpClient() {
          @Override
          public FileInfo issueGetRequest(URL url,
              List<String> authenticationCookies, String adaptorUserAgent,
              int maxRedirectsToFollow, boolean performBrowserLeniency) {
            assertEquals("http://localhost:1/sites/SiteCollection/Lists/"
                + "Custom%20List/outlookFile.msg", url.toString());
            InputStream contents = new ByteArrayInputStream(
                goldenContents.getBytes(charset));
            List<String> headers = Arrays.asList("not-the-Content-Type",
                "early", "conTent-TypE", goldenContentType, "Content-Type",
                "late", "Last-Modified", "Tue, 01 May 2012 22:14:41 GMT");
            return new FileInfo.Builder(contents).setHeaders(headers).build();
          }

          @Override
          public String getRedirectLocation(URL url,
              List<String> authenticationCookies, String adaptorUserAgent)
                  throws IOException {
            throw new UnsupportedOperationException();        
          }

          @Override
          public HttpURLConnection getHttpURLConnection(URL url)
              throws IOException {
            throw new UnsupportedOperationException();
          }
        }, executorFactory, new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List"
          + "/outlookFile.msg"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);    
    String responseString = new String(baos.toByteArray(), charset);
    assertEquals(goldenContents, responseString);
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection/"
            + "Lists/Custom List"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/outlookFile.msg"),
        response.getDisplayUrl());
    assertEquals("application/vnd.ms-outlook", response.getContentType());
  }

  @Test
  public void testGetDocContentInList() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
            .replaceInContent("2_.000", "cs.pdf")
            .replaceInContent("Inside Folder", "Under List")
            .replaceInContent("/Test Folder", "")
            .replaceInContent("/Test%20Folder", "")
            .replaceInContent(
              "ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'",
              "ows_ScopeId='2;#{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}'"))
        .register(new URLSegmentsExchange("http://localhost:1/sites/"
            + "SiteCollection/Lists/Custom List/cs.pdf",
          true, null, null, "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}", "2"));
    final String goldenContents = "pdf contents";
    final String goldenContentType = "application/pdf";
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new HttpClient() {
          @Override
          public FileInfo issueGetRequest(URL url,
              List<String> authenticationCookies, String adaptorUserAgent,
              int maxRedirectsToFollow, boolean performBrowserLeniency) {
            assertEquals("http://localhost:1/sites/SiteCollection/Lists/"
                + "Custom%20List/cs.pdf", url.toString());
            InputStream contents = new ByteArrayInputStream(
                goldenContents.getBytes(charset));
            List<String> headers = Arrays.asList("not-the-Content-Type",
                "early", "conTent-TypE", goldenContentType, "Content-Type",
                "late", "Last-Modified", "Tue, 01 May 2012 22:14:41 GMT");
            return new FileInfo.Builder(contents).setHeaders(headers).build();
          }

          @Override
          public String getRedirectLocation(URL url,
              List<String> authenticationCookies, String adaptorUserAgent)
                  throws IOException {
            throw new UnsupportedOperationException();
          }

          @Override
          public HttpURLConnection getHttpURLConnection(URL url)
              throws IOException {
            throw new UnsupportedOperationException();
          }
        }, executorFactory, new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List"
          + "/cs.pdf"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    assertEquals(goldenContents, responseString);
    assertEquals(new Acl.Builder()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection/"
            + "Lists/Custom List"))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/cs.pdf"),
        response.getDisplayUrl());
    assertEquals("application/pdf", response.getContentType());
  }

  @Test
  public void testGetDocContentListItemAnonymousAccess() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_URLSEG_EXCHANGE)
        // TODO(ejona): This access of VS doesn't look right, because it should
        // happen on a siteData for VS_ENDPOINT.
        .register(VS_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE
            .replaceInContent("AnonymousPermMask=\"0\"",
              "AnonymousPermMask=\"65536\""))
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE
            .replaceInContent("AllowAnonymousAccess=\"False\"",
              "AllowAnonymousAccess=\"True\"")
            .replaceInContent("AnonymousViewListItems=\"False\"",
              "AnonymousViewListItems=\"True\"")
            .replaceInContent("AnonymousPermMask=\"0\"",
              "AnonymousPermMask=\"68719546465\""))
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
            .replaceInContent("ows_Attachments='1'", "ows_Attachments='0'")
            .replaceInContent(
                "ows_ScopeId='2;#{2E29615C-59E7-493B-B08A-3642949CC069}'",
                "ows_ScopeId='2;#{f9cb02b3-7f29-4cac-804f-ba6e14f1eb39}'"));

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder/2_.000"));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    assertNull(response.getAcl());
  }

  @Test
  public void testGetDocContentListItemWithReadSecurity() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE
            .replaceInContent("ReadSecurity=\"1\"", "ReadSecurity=\"2\""))
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_2_LI_CONTENT_EXCHANGE
            .replaceInContent("ows_Attachments='1'", "ows_Attachments='0'"));

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

    MockUserGroupSoap mockUserGroupSoap = new MockUserGroupSoap(users);

    adaptor = new SharePointAdaptor(
        initableSoapFactory
          .endpoint(
              "http://localhost:1/sites/SiteCollection/_vti_bin/UserGroup.asmx",
              mockUserGroupSoap)
          .endpoint(SITES_SITECOLLECTION_ENDPOINT, new UnsupportedSiteData()),
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
            + "Test Folder/2_.000"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection", siteData,
          mockUserGroupSoap, new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          adaptor.new SiteUserIdMappingCallable(
              "http://localhost:1/sites/SiteCollection"))
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>Inside Folder</title></head>"
        + "<body><h1><!--googleoff: index-->List Item<!--googleon: index-->"
        +   " Inside Folder</h1>"
        + "<!--googleoff: index--><table style='border: none'>"
        + "<tr><td>Attachments</td><td>0</td></tr>"
        + "<tr><td>Author</td><td>System Account</td></tr>"
        + "<tr><td>BaseName</td><td>2_</td></tr>"
        + "<tr><td>ContentType</td><td>Item</td></tr>"
        + "<tr><td>ContentTypeId</td>"
        +   "<td>0x0100442459C9B5E59C4F9CFDC789A220FC92</td></tr>"
        + "<tr><td>Created</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>Created Date</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>Editor</td><td>System Account</td></tr>"
        + "<tr><td>EncodedAbsUrl</td>"
        +   "<td>http://localhost:1/sites/SiteCollection/Lists/Custom%20List/"
        +   "Test%20Folder/2_.000</td></tr>"
        + "<tr><td>FSObjType</td><td>0</td></tr>"
        + "<tr><td>FileDirRef</td>"
        + "<td>sites/SiteCollection/Lists/Custom List/Test Folder</td></tr>"
        + "<tr><td>FileLeafRef</td><td>2_.000</td></tr>"
        + "<tr><td>FileRef</td>"
        +   "<td>sites/SiteCollection/Lists/Custom List/Test Folder/2_.000</td>"
        +   "</tr>"
        + "<tr><td>GUID</td>"
        +   "<td>{2C5BEF60-18FA-42CA-B472-7B5E1EC405A5}</td></tr>"
        + "<tr><td>ID</td><td>2</td></tr>"
        + "<tr><td>Last Modified</td><td>2012-05-01T22:14:06Z</td></tr>"
        + "<tr><td>LinkFilename</td><td>2_.000</td></tr>"
        + "<tr><td>LinkFilenameNoMenu</td><td>2_.000</td></tr>"
        + "<tr><td>LinkTitle</td><td>Inside Folder</td></tr>"
        + "<tr><td>LinkTitleNoMenu</td><td>Inside Folder</td></tr>"
        + "<tr><td>Modified</td><td>2012-05-04T21:24:32Z</td></tr>"
        + "<tr><td>Order</td><td>200.000000000000</td></tr>"
        + "<tr><td>PermMask</td><td>0x7fffffffffffffff</td></tr>"
        + "<tr><td>ScopeId</td>"
        +   "<td>{2E29615C-59E7-493B-B08A-3642949CC069}</td></tr>"
        + "<tr><td>SelectTitle</td><td>2</td></tr>"
        + "<tr><td>ServerRedirected</td><td>0</td></tr>"
        + "<tr><td>ServerUrl</td>"
        +   "<td>/sites/SiteCollection/Lists/Custom List/Test Folder/2_.000"
        +   "</td></tr>"
        + "<tr><td>Title</td><td>Inside Folder</td></tr>"
        + "<tr><td>UniqueId</td>"
        +   "<td>{E7156244-AC2F-4402-AA74-7A365726CD02}</td></tr>"
        + "<tr><td>WorkflowVersion</td><td>1</td></tr>"
        + "<tr><td>_EditMenuTableEnd</td><td>2</td></tr>"
        + "<tr><td>_EditMenuTableStart</td><td>2_.000</td></tr>"
        + "<tr><td>_IsCurrentVersion</td><td>1</td></tr>"
        + "<tr><td>_Level</td><td>1</td></tr>"
        + "<tr><td>_ModerationStatus</td><td>0</td></tr>"
        + "<tr><td>_UIVersion</td><td>512</td></tr>"
        + "<tr><td>_UIVersionString</td><td>1.0</td></tr>"
        + "<tr><td>owshiddenversion</td><td>4</td></tr>"
        + "</table><!--googleon: index-->"
        + "</body></html>";

    assertEquals(golden, responseString);
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"
            + "/Lists/Custom List/Test Folder/2_.000"), "readSecurity")
        .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR))
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_OWNERS,
            SITES_SITECOLLECTION_MEMBERS, SITES_SITECOLLECTION_VISITORS))
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES).build(),
        response.getAcl());
    assertEquals(Collections.singletonMap(
        "readSecurity",
        new Acl.Builder()
            .setEverythingCaseInsensitive()
            .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR,
                new UserPrincipal("System.Account", DEFAULT_NAMESPACE)))
            .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_OWNERS))
            .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT)
            .setInheritFrom(
                new DocId("http://localhost:1/sites/SiteCollection"), "admin")
            .build()),
        response.getNamedResources());
  }

  @Test
  public void testGetDocContentFolder() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_LI_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_F_CONTENT_EXCHANGE);

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder"));
    RecordingResponse response = new RecordingResponse(baos);
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection",
          siteData, new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);
    String responseString = new String(baos.toByteArray(), charset);
    final String golden
        = "<!DOCTYPE html>\n"
        + "<html><head><title>Test Folder</title></head>"
        + "<body><h1><!--googleoff: index-->Folder<!--googleon: index-->"
        +   " Test Folder</h1>"
        + "<p><!--googleoff: index-->List Items<!--googleon: index--></p>"
        + "<ul>"
        + "<li><a href=\"Test%20Folder/2_.000\">Inside Folder</a></li>"
        + "<li><a href=\"Test%20Folder/testing\">testing</a></li>"
        + "</ul>"
        + "<!--googleoff: index--><table style='border: none'>"
        + "<tr><td>Attachments</td><td>0</td></tr>"
        + "<tr><td>Author</td><td>System Account</td></tr>"
        + "<tr><td>BaseName</td><td>Test Folder</td></tr>"
        + "<tr><td>ContentType</td><td>Folder</td></tr>"
        + "<tr><td>ContentTypeId</td>"
        +   "<td>0x01200077DD29735CE61148A73F540231F24430</td></tr>"
        + "<tr><td>Created</td><td>2012-05-01T22:13:47Z</td></tr>"
        + "<tr><td>Created Date</td><td>2012-05-01T22:13:47Z</td></tr>"
        + "<tr><td>Editor</td><td>System Account</td></tr>"
        + "<tr><td>EncodedAbsUrl</td>"
        + "<td>http://localhost:1/sites/SiteCollection/Lists/Custom%20List/"
        +   "Test%20Folder</td></tr>"
        + "<tr><td>FSObjType</td><td>1</td></tr>"
        + "<tr><td>FileDirRef</td><td>sites/SiteCollection/Lists/Custom List"
        +   "</td></tr>"
        + "<tr><td>FileLeafRef</td><td>Test Folder</td></tr>"
        + "<tr><td>FileRef</td><td>sites/SiteCollection/Lists/Custom List/"
        +   "Test Folder</td></tr>"
        + "<tr><td>GUID</td><td>{C099F4ED-6E96-4A00-B94A-EE443061EE49}</td>"
        +   "</tr>"
        + "<tr><td>ID</td><td>1</td></tr>"
        + "<tr><td>Last Modified</td><td>2012-05-02T21:13:17Z</td></tr>"
        + "<tr><td>LinkFilename</td><td>Test Folder</td></tr>"
        + "<tr><td>LinkFilenameNoMenu</td><td>Test Folder</td></tr>"
        + "<tr><td>LinkTitle</td><td>Test Folder</td></tr>"
        + "<tr><td>LinkTitleNoMenu</td><td>Test Folder</td></tr>"
        + "<tr><td>Modified</td><td>2012-05-01T22:13:47Z</td></tr>"
        + "<tr><td>Order</td><td>100.000000000000</td></tr>"
        + "<tr><td>PermMask</td><td>0x7fffffffffffffff</td></tr>"
        + "<tr><td>ScopeId</td><td>{2E29615C-59E7-493B-B08A-3642949CC069}</td>"
        +   "</tr>"
        + "<tr><td>SelectTitle</td><td>1</td></tr>"
        + "<tr><td>ServerRedirected</td><td>0</td></tr>"
        + "<tr><td>ServerUrl</td><td>/sites/SiteCollection/Lists/Custom List/"
        +   "Test Folder</td></tr>"
        + "<tr><td>Title</td><td>Test Folder</td></tr>"
        + "<tr><td>UniqueId</td><td>{CE33B6B7-9F5E-4224-8D77-9C42E6290FE6}</td>"
        +   "</tr>"
        + "<tr><td>WorkflowVersion</td><td>1</td></tr>"
        + "<tr><td>_EditMenuTableEnd</td><td>1</td></tr>"
        + "<tr><td>_EditMenuTableStart</td><td>Test Folder</td></tr>"
        + "<tr><td>_IsCurrentVersion</td><td>1</td></tr>"
        + "<tr><td>_Level</td><td>1</td></tr>"
        + "<tr><td>_ModerationStatus</td><td>0</td></tr>"
        + "<tr><td>_UIVersion</td><td>512</td></tr>"
        + "<tr><td>_UIVersionString</td><td>1.0</td></tr>"
        + "<tr><td>owshiddenversion</td><td>1</td></tr>"
        + "</table><!--googleon: index-->"
        + "</body></html>";
    final Metadata goldenMetadata;
    {
      Metadata meta = new Metadata();
      meta.add("Attachments", "0");
      meta.add("Author", "System Account");
      meta.add("BaseName", "Test Folder");
      meta.add("ContentType", "Folder");
      meta.add("ContentTypeId", "0x01200077DD29735CE61148A73F540231F24430");
      meta.add("Created", "2012-05-01T22:13:47Z");
      meta.add("Created Date", "2012-05-01T22:13:47Z");
      meta.add("Editor", "System Account");
      meta.add("EncodedAbsUrl", "http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/Test%20Folder");
      meta.add("FSObjType", "1");
      meta.add("FileDirRef", "sites/SiteCollection/Lists/Custom List");
      meta.add("FileLeafRef", "Test Folder");
      meta.add("FileRef", "sites/SiteCollection/Lists/Custom List/Test Folder");
      meta.add("GUID", "{C099F4ED-6E96-4A00-B94A-EE443061EE49}");
      meta.add("ID", "1");
      meta.add("Last Modified", "2012-05-02T21:13:17Z");
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
      meta.add("sharepoint:parentwebtitle", "chinese1");
      meta.add("sharepoint:listguid", "{6F33949A-B3FF-4B0C-BA99-93CB518AC2C0}");
      meta.add("google:objecttype", "Folder");
      goldenMetadata = meta.unmodifiableView();
    }
    assertEquals(golden, responseString);
    assertEquals(goldenMetadata, response.getMetadata());
    assertEquals(new Acl.Builder()
        .setEverythingCaseInsensitive()
        .setInheritFrom(new DocId("http://localhost:1/sites/SiteCollection"),
          "admin")
        .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
        .setPermitGroups(Arrays.asList(SITES_SITECOLLECTION_MEMBERS,
            SITES_SITECOLLECTION_OWNERS, SITES_SITECOLLECTION_VISITORS))
        .setPermitUsers(Arrays.asList(GDC_PSL_ADMINISTRATOR)).build(),
        response.getAcl());
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List/AllItems.aspx?RootFolder=/sites/SiteCollection/"
          + "Lists/Custom%20List/Test%20Folder"),
        response.getDisplayUrl());
  }
  
  @Test
  public void testGetDocContentFolderEmptyDefaultView() throws Exception {
    SiteDataSoap siteData = MockSiteData.blank()
        .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_URLSEG_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_L_CONTENT_EXCHANGE
            .replaceInContent("DefaultViewUrl=\"/sites/SiteCollection/Lists/"
            + "Custom List/AllItems.aspx\"", "DefaultViewUrl=\"/\""))
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_LI_CONTENT_EXCHANGE)
        .register(SITES_SITECOLLECTION_LISTS_CUSTOMLIST_1_F_CONTENT_EXCHANGE);

    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    DocRequest request = new DocRequest(
        new DocId("http://localhost:1/sites/SiteCollection/Lists/Custom List/"
          + "Test Folder"));
    RecordingResponse response = new RecordingResponse();
    adaptor.new SiteAdaptor("http://localhost:1/sites/SiteCollection",
          "http://localhost:1/sites/SiteCollection",
          siteData, new UnsupportedUserGroupSoap(), new UnsupportedPeopleSoap(),
          Callables.returning(SITES_SITECOLLECTION_MEMBER_MAPPING),
          new UnsupportedCallable<MemberIdMapping>())
        .getDocContent(request, response);    
    assertEquals(URI.create("http://localhost:1/sites/SiteCollection/Lists/"
          + "Custom%20List?RootFolder=/sites/SiteCollection/"
          + "Lists/Custom%20List/Test%20Folder"),response.getDisplayUrl());
  }
  
  @Test
  public void testGetDocIds() throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }

    // Force a full batch of 2 and a final batch of 1.
    config.overrideKey("feed.maxUrls", "2");
    adaptor = new SharePointAdaptor(MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
          .register(VS_CONTENT_EXCHANGE)
          .register(CD_CONTENT_EXCHANGE
            .replaceInContent("<Site URL=\"http://localhost:1\"\n"
              + " ID=\"{bb3bb2dd-6ea7-471b-a361-6fb67988755c}\" />", ""))
          .register(ROOT_SITE_SAW_EXCHANGE)
          .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
          .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE)),
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    assertEquals(0, pusher.getRecords().size());
    assertEquals(0, pusher.getGroupDefinitions().size());
    adaptor.getDocIds(pusher);
    assertEquals(
        Arrays.asList(new DocIdPusher.Record.Builder(new DocId("")).build()),
        pusher.getRecords());
    assertEquals(goldenGroups, pusher.getGroupDefinitions(
        "SITEID-bb3bb2dd-6ea7-471b-a361-6fb67988755c"));
  }
  
  @Test
  public void testGetDocIdsSiteCollectionOnly() throws Exception {
    final Map<GroupPrincipal, Collection<Principal>> goldenGroups;
    {
      Map<GroupPrincipal, Collection<Principal>> tmp
          = new TreeMap<GroupPrincipal, Collection<Principal>>();
      tmp.put(SITES_SITECOLLECTION_OWNERS, Arrays.<Principal>asList(
          GDC_PSL_ADMINISTRATOR));
      tmp.put(SITES_SITECOLLECTION_MEMBERS, Arrays.asList(
            new UserPrincipal("GDC-PSL\\spuser2", DEFAULT_NAMESPACE),
            new GroupPrincipal("BUILTIN\\users", DEFAULT_NAMESPACE),
            new UserPrincipal("GDC-PSL\\spuser4", DEFAULT_NAMESPACE)));
      tmp.put(SITES_SITECOLLECTION_VISITORS, Arrays.<Principal>asList());
      goldenGroups = Collections.unmodifiableMap(tmp);
    }

    final String getChangesSiteCollection726 =
        loadTestString("testModifiedGetDocIdsClient.changes-sc.xml");
    final ContentExchange getContentSiteCollection  =
        new ContentExchange(ObjectType.SITE_COLLECTION, null, null, null,
            true, false, null, loadTestString("sites-SiteCollection-sc.xml")
                .replace("http://localhost:1/sites/SiteCollection",
                    "http://localhost:1/sites/sitecollection"));
    SoapFactory siteDataFactory = MockSoapFactory.blank()        
        .endpoint(SITES_SITECOLLECTION_ENDPOINT.replace("SiteCollection",
            "sitecollection"), MockSiteData.blank()
            .register(ROOT_SITE_SAW_EXCHANGE)            
            .register(getContentSiteCollection)
            .register(new ChangesExchange(ObjectType.SITE_COLLECTION,
                    "{bb3bb2dd-6ea7-471b-a361-6fb67988755c}",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    null,
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    600, getChangesSiteCollection726, false)))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE));

    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server",
        "http://localhost:1/sites/sitecollection");
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    adaptor.getDocIds(pusher);
    assertEquals(
        Arrays.asList(new DocIdPusher.Record.Builder(
            new DocId("http://localhost:1/sites/SiteCollection")).build()),
            pusher.getRecords());
    assertEquals(goldenGroups, pusher.getGroupDefinitions());
  }

  @Test
  public void testModifiedGetDocIds() throws Exception {
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
    final ReferenceSiteData siteData = new ReferenceSiteData();
    SiteDataSoap state0 = MockSiteData.blank()
        .register(VS_CONTENT_EXCHANGE)
        .register(CD_CONTENT_EXCHANGE)
        .register(ROOT_SITE_SAW_EXCHANGE);
    SiteDataSoap state1 = new UnsupportedSiteData() {
      @Override
      public void getContent(ObjectType objectType, String objectId,
          String folderUrl, String itemId, boolean retrieveChildItems,
          boolean securityOnly, Holder<String> lastItemIdOnPage,
          Holder<String> getContentResult) {
        throw new WebServiceException("fake IO error");
      }
    };
    SiteDataSoap state2 = MockSiteData.blank()
        .register(VS_CONTENT_EXCHANGE.replaceInContent(
          "<ContentDatabase ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />",
          "<ContentDatabase ID=\"{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}\" />"
           + "<ContentDatabase ID=\"{3ac1e3b3-2326-7341-4afe-16751eafbc51}\" />"
          ))
        .register(new ContentExchange(ObjectType.CONTENT_DATABASE,
              "{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}", null, null, false,
              false, null, getContentContentDatabase4fb))
        .register(new ContentExchange(ObjectType.CONTENT_DATABASE,
              "{3ac1e3b3-2326-7341-4afe-16751eafbc51}", null, null, false,
              false, null, getContentContentDatabase3ac));
    SiteDataSoap state3 = MockSiteData.blank()
        .register(VS_CONTENT_EXCHANGE)
        .register(new ChangesExchange(ObjectType.CONTENT_DATABASE,
              "{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}",
              "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
              "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
              null,
              "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
              15, getChangesContentDatabase4fb, false));
    final AtomicLong atomicNumberGetChangesCalls = new AtomicLong(0);
    SiteDataSoap countingSiteData = new DelegatingSiteData() {
      @Override
      protected SiteDataSoap delegate() {
        return siteData;
      }

      @Override
      public void getChanges(ObjectType objectType,
          String contentDatabaseId, Holder<String> lastChangeId,
          Holder<String> currentChangeId, Integer timeout,
          Holder<String> getChangesResult, Holder<Boolean> moreChanges) {
        atomicNumberGetChangesCalls.getAndIncrement();
        super.getChanges(objectType, contentDatabaseId, lastChangeId,
            currentChangeId, timeout, getChangesResult, moreChanges);
      }
    };
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, countingSiteData);
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    siteData.setSiteDataSoap(state0);
    adaptor.init(new MockAdaptorContext(config, pusher));

    // Error getting content databases, so content databases remains unchanged
    // (empty).
    siteData.setSiteDataSoap(state1);
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertEquals(0, atomicNumberGetChangesCalls.get());

    // Find new content databases and get their current change id.
    siteData.setSiteDataSoap(state2);
    adaptor.getModifiedDocIds(pusher);
    assertEquals(1, pusher.getRecords().size());
    assertEquals(new DocIdPusher.Record.Builder(new DocId(""))
        .setCrawlImmediately(true).build(),
        pusher.getRecords().get(0));
    assertEquals(0, atomicNumberGetChangesCalls.get());
    pusher.reset();

    // Discover one content database disappeared; get changes for other content
    // database.
    siteData.setSiteDataSoap(state3);
    adaptor.getModifiedDocIds(pusher);
    assertEquals(1, pusher.getRecords().size());
    assertEquals(new DocIdPusher.Record.Builder(new DocId(""))
        .setCrawlImmediately(true).build(),
        pusher.getRecords().get(0));
    assertEquals(1, atomicNumberGetChangesCalls.get());
  }

  @Test
  public void testModifiedGetDocIdsSP2010() throws Exception {
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
    // SP 2010 provides more metadata than 2007.
    ContentExchange vsContentExchange = VS_CONTENT_EXCHANGE.replaceInContent(
        "<Metadata URL=\"http://localhost:1/\" />",
        "<Metadata ID=\"{3a125232-0c27-495f-8c92-65ad85b5a17c}\""
          + " Version=\"14.0.4762.1000\" URL=\"http://localhost:1/\""
          + " URLZone=\"Default\" URLIsHostHeader=\"False\" />");
    final AtomicLong atomicNumberGetChangesCalls = new AtomicLong(0);
    final SiteDataSoap siteData = MockSiteData.blank()
        .register(vsContentExchange)
        .register(CD_CONTENT_EXCHANGE)
        .register(ROOT_SITE_SAW_EXCHANGE)
        .register(new ContentExchange(ObjectType.CONTENT_DATABASE,
              "{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}", null, null, false,
              false, null, getContentContentDatabase4fb))
        // The timeout in SP 2010 is not a timeout and should always be at least
        // 60 to get a result.
        .register(new ChangesExchange(ObjectType.CONTENT_DATABASE,
              "{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}",
              "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
              "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
              null,
              "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634727056594000000;603",
              600, getChangesContentDatabase4fb, false));
    SiteDataSoap countingSiteData = new DelegatingSiteData() {
      @Override
      protected SiteDataSoap delegate() {
        return siteData;
      }

      @Override
      public void getChanges(ObjectType objectType,
          String contentDatabaseId, Holder<String> lastChangeId,
          Holder<String> currentChangeId, Integer timeout,
          Holder<String> getChangesResult, Holder<Boolean> moreChanges) {
        atomicNumberGetChangesCalls.getAndIncrement();
        super.getChanges(objectType, contentDatabaseId, lastChangeId,
            currentChangeId, timeout, getChangesResult, moreChanges);
      }
    };
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, countingSiteData);
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));

    // Initialize changeIds.
    adaptor.getModifiedDocIds(pusher);
    assertEquals(0, atomicNumberGetChangesCalls.get());

    // Check for changes. This should not go into an infinite loop.
    adaptor.getModifiedDocIds(pusher);
    assertEquals(1, atomicNumberGetChangesCalls.get());
  }

  @Test
  public void testModifiedGetDocIdsClient() throws Exception {
    final String getChangesContentDatabase
        = loadTestString("testModifiedGetDocIdsClient.changes-cd.xml");
    adaptor = new SharePointAdaptor(initableSoapFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    adaptor.init(new MockAdaptorContext(config, pusher));
    SPContentDatabase result = parseChanges(getChangesContentDatabase);
    List<DocId> docIds = new ArrayList<DocId>();
    Map<GroupPrincipal, Collection<Principal>> groupDefs
        = new HashMap<GroupPrincipal, Collection<Principal>>();
    Set<String> updatedSiteSecurity = new HashSet<String>();
    adaptor.getModifiedDocIdsContentDatabase(
        result, docIds, updatedSiteSecurity);    
    assertEquals(Arrays.asList(
          new DocId("http://localhost:1/Lists/Announcements/2_.000")),
        docIds);
    assertEquals(Collections.emptyMap(), groupDefs);
  }
  
  @Test
  public void testModifiedGetDocIdsSiteCollection() throws Exception {
    final String getChangesSiteCollection726
        = loadTestString("testModifiedGetDocIdsClient.changes-sc.xml");
    final String getChangesSiteCollection728 = "<SPSite Change=\"Unchanged\" "
        + "ItemCount=\"0\"><Messages /></SPSite>";
    SoapFactory siteDataFactory = MockSoapFactory.blank()
        .endpoint(VS_ENDPOINT, MockSiteData.blank()
            .register(SITES_SITECOLLECTION_SAW_EXCHANGE))
        .endpoint(SITES_SITECOLLECTION_ENDPOINT, MockSiteData.blank()
            .register(ROOT_SITE_SAW_EXCHANGE)
            .register(SITES_SITECOLLECTION_URLSEG_EXCHANGE)
            .register(SITES_SITECOLLECTION_S_CONTENT_EXCHANGE)
            .register(SITES_SITECOLLECTION_SC_CONTENT_EXCHANGE)
            .register(new ChangesExchange(ObjectType.SITE_COLLECTION,
                    "{bb3bb2dd-6ea7-471b-a361-6fb67988755c}",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;726",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;728",
                    null,
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;728",
                    600, getChangesSiteCollection726, false))
            .register(new ChangesExchange(ObjectType.SITE_COLLECTION,
                    "{bb3bb2dd-6ea7-471b-a361-6fb67988755c}",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;728",
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;728",
                    null,
                    "1;1;bb3bb2dd-6ea7-471b-a361-6fb67988755c;"
                        + "634762601982930000;728",
                    600, getChangesSiteCollection728, false)));
    adaptor = new SharePointAdaptor(siteDataFactory,
        new UnsupportedHttpClient(), executorFactory,
        new MockAuthenticationClientFactoryForms(),
        new UnsupportedActiveDirectoryClientFactory());
    config.overrideKey("sharepoint.server",
        "http://localhost:1/sites/SiteCollection");
    config.overrideKey("sharepoint.siteCollectionOnly", "true");
    RecordingDocIdPusher pusher = new RecordingDocIdPusher();
    adaptor.init(new MockAdaptorContext(config, pusher));
    //First call to get Modified DocIds with change Id 726
    adaptor.getModifiedDocIdsSiteCollection(pusher);
    assertEquals(1, pusher.getRecords().size());
    assertEquals(new DocIdPusher.Record.Builder(
            new DocId("http://localhost:1/sites/SiteCollection/"
                + "Lists/Announcements/2_.000"))
            .setCrawlImmediately(true).build(), pusher.getRecords().get(0));
    assertTrue(pusher.getGroupDefinitions().isEmpty());
    
    
    //Next call to get Modified DocIds with change Id 728
    pusher = new RecordingDocIdPusher();
    adaptor.getModifiedDocIdsSiteCollection(pusher);
    assertEquals(0, pusher.getRecords().size());
    assertTrue(pusher.getGroupDefinitions().isEmpty());
  }

  @Test
  public void testParseError() throws Exception {
    SiteDataClient client = new SiteDataClient(
        new UnsupportedSiteData(), false);
    String xml = "<broken";
    thrown.expect(IOException.class);
    client.jaxbParse(xml, SPContentDatabase.class);
  }

  @Test
  public void testValidationError() throws Exception {
    SiteDataClient client = new SiteDataClient(
        new UnsupportedSiteData(), true);
    // Lacks required child element.
    String xml = "<SPContentDatabase"
        + " xmlns='http://schemas.microsoft.com/sharepoint/soap/'/>";
    thrown.expect(IOException.class);
    client.jaxbParse(xml, SPContentDatabase.class);
  }

  @Test
  public void testDisabledValidation() throws Exception {
    SiteDataClient client = new SiteDataClient(
        new UnsupportedSiteData(), false);
    // Lacks required child element.
    String xml = "<SPContentDatabase"
        + " xmlns='http://schemas.microsoft.com/sharepoint/soap/'/>";
    assertNotNull(client.jaxbParse(xml, SPContentDatabase.class));
  }

  @Test
  public void testChar31Stripping() throws Exception {
    SiteDataClient client = new SiteDataClient(
        new UnsupportedSiteData(), true);
    String xml = loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml")
        .replace("<Folder>",
            "<Folder xmlns='http://schemas.microsoft.com/sharepoint/soap/'>")
        .replace("MetaInfo='2;#'", "MetaInfo='2;#&#31;'");
    assertNotNull(client.jaxbParse(xml, ItemData.class));
  }

  @Test
  public void testUnusedCharCodeStripping() throws Exception {
    SiteDataClient client = new SiteDataClient(
        new UnsupportedSiteData(), true);
    String xml = loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml")
        .replace("<Folder>",
            "<Folder xmlns='http://schemas.microsoft.com/sharepoint/soap/'>")
        .replace("MetaInfo='2;#'", "MetaInfo='2;#&#00;'");
    assertNotNull(client.jaxbParse(xml, ItemData.class));
    
    xml = loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml")
        .replace("<Folder>",
            "<Folder xmlns='http://schemas.microsoft.com/sharepoint/soap/'>")
        .replace("MetaInfo='2;#'", "MetaInfo='2;#&#1;'");
    assertNotNull(client.jaxbParse(xml, ItemData.class));
    
    xml = loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml")
        .replace("<Folder>",
            "<Folder xmlns='http://schemas.microsoft.com/sharepoint/soap/'>")
        .replace("MetaInfo='2;#'", "MetaInfo='2;#&#11;'");
    assertNotNull(client.jaxbParse(xml, ItemData.class));
    
    xml = loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml")
        .replace("<Folder>",
            "<Folder xmlns='http://schemas.microsoft.com/sharepoint/soap/'>")
        .replace("MetaInfo='2;#'", "MetaInfo='2;#&#21;'");
    assertNotNull(client.jaxbParse(xml, ItemData.class));
    
    xml = loadTestString("sites-SiteCollection-Lists-CustomList-1-f.xml")
        .replace("<Folder>",
            "<Folder xmlns='http://schemas.microsoft.com/sharepoint/soap/'>")
        .replace("MetaInfo='2;#'", "MetaInfo='2;#&#128;'");
    assertNotNull(client.jaxbParse(xml, ItemData.class));
  }

  @Test
  public void testParseUnknownXml() throws Exception {
    SiteDataClient client = new SiteDataClient(
        new UnsupportedSiteData(), true);
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
  
  @Test
  public void testIssueGetRequestWithMoreThanMaxRedirect() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl(){
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        return new MockHttpURLConnection(url, HttpURLConnection.HTTP_MOVED_TEMP,
            "http://localshost:8080/default.aspx?q={Some Value}", null);
      }
    };
    thrown.expect(IOException.class);
    client.issueGetRequest(new URL("http://localshost:8080/default.aspx"),
        new ArrayList<String>(), "", 10, true);
  }  

  @Test
  public void testIssueGetRequestWithHttpUnauthorized() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl() {
      @Override
      public HttpURLConnection getHttpURLConnection(URL url) {
        return new MockHttpURLConnection(url,
            HttpURLConnection.HTTP_UNAUTHORIZED,
            "http://localshost:8080/default.aspx?q={Some Value}", null);
      }
    };
    thrown.expect(IOException.class);
    client.issueGetRequest(new URL("http://localshost:8080/default.aspx"),
        new ArrayList<String>(), "", 10, true);
  }

  @Test
  public void testIssueGetRequestWithSameAsMaxRedirect() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl(){
      int requestCount = 0;
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        requestCount++;
        if (requestCount <= 10) {
         return new MockHttpURLConnection(url,
             HttpURLConnection.HTTP_MOVED_TEMP,
             "http://localshost:8080/default.aspx?q={Some Value}", null);
        } else {
          return new MockHttpURLConnection(url, HttpURLConnection.HTTP_OK, null,
              "Golden Content");
        }
      }
    };    
    FileInfo output =
        client.issueGetRequest(new URL("http://localshost:8080/default.aspx"),
            new ArrayList<String>(), "", 10, true);
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    IOHelper.copyStream(output.getContents(), content);
    assertEquals("Golden Content", content.toString());
    output.getContents().close();
  }
  
  @Test
  public void testIssueGetRequestWithHttpOk() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl(){
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        return new MockHttpURLConnection(url, HttpURLConnection.HTTP_OK, null,
            "Golden Content");
      }
    };    
    FileInfo output =
        client.issueGetRequest(new URL("http://localshost:8080/default.aspx"),
            new ArrayList<String>(), "", 10, true);
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    IOHelper.copyStream(output.getContents(), content);
    assertEquals("Golden Content", content.toString());
    output.getContents().close();
  }
  
  @Test
  public void testIssueGetRequestWithZeroRedirectsAllowed() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl(){
      int requestCount = 0;
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        requestCount++;
        System.out.println(requestCount);
        if (requestCount == 1) {
         return new MockHttpURLConnection(url,
             HttpURLConnection.HTTP_MOVED_TEMP,
             "http://localshost:8080/default.aspx?q={Some Value}", null);
        } else {
          throw new UnsupportedOperationException("With 0 redirects allowed "
              + "only one call to getHttpUrlConnection is expected.");
        }
      }
    };
    thrown.expect(IOException.class);
    client.issueGetRequest(new URL("http://localshost:8080/default.aspx"),
        new ArrayList<String>(), "", 0, true);
  }

  @Test
  public void testIssueGetRequestWithRedirect() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl(){
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        String urlToServe = url.toString();
        if ("http://localshost:8080/default.aspx"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url,
              HttpURLConnection.HTTP_MOVED_TEMP,
              "http://localshost:8080/Redirect1.aspx", null);
        } else if ("http://localshost:8080/Redirect1.aspx"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url,
              HttpURLConnection.HTTP_MOVED_PERM,
              "http://localshost:8080/Final.aspx?q={data}", null);
        } else if ("http://localshost:8080/Final.aspx?q=%7Bdata%7D"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url, HttpURLConnection.HTTP_OK, null,
              "Golden Content");
        }
        throw new UnsupportedOperationException();
      }
    };
    FileInfo output =
        client.issueGetRequest(new URL("http://localshost:8080/default.aspx"),
            new ArrayList<String>(), "", 20, true);
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    IOHelper.copyStream(output.getContents(), content);
    assertEquals("Golden Content", content.toString());
    output.getContents().close();
  }

  @Test
  public void testIssueGetRequestWithRelativeRedirect() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl() {
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        String urlToServe = url.toString();
        if ("http://localhost:8080/default.aspx"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url,
              HttpURLConnection.HTTP_MOVED_TEMP,
              "/Redirect1.aspx", null);
        } else if ("http://localhost:8080/Redirect1.aspx"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url,
              HttpURLConnection.HTTP_MOVED_PERM,
              "/test/Final Document.aspx?q={data}", null);
        } else if (
            "http://localhost:8080/test/Final%20Document.aspx?q=%7Bdata%7D"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url, HttpURLConnection.HTTP_OK, null,
              "Golden Content");
        }
        throw new UnsupportedOperationException();
      }
    };
    FileInfo output
        = client.issueGetRequest(new URL("http://localhost:8080/default.aspx"),
            new ArrayList<String>(), "", 20, true);
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    IOHelper.copyStream(output.getContents(), content);
    assertEquals("Golden Content", content.toString());
    output.getContents().close();
  }

  @Test
  public void testIssueGetRequestWithSecuredUrlRedirect() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl() {
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        String urlToServe = url.toString();
        if ("https://localhost:8080/default.aspx"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url,
              HttpURLConnection.HTTP_MOVED_PERM,
              "/Redirect1.aspx", null);
        } else if ("https://localhost:8080/Redirect1.aspx"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url,
              HttpURLConnection.HTTP_MOVED_PERM,
              "/Final Document.aspx?q={data}", null);
        } else if ("https://localhost:8080/Final%20Document.aspx?q=%7Bdata%7D"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url, HttpURLConnection.HTTP_OK, null,
              "Golden Content");
        }
        throw new UnsupportedOperationException();
      }
    };
    FileInfo output
        = client.issueGetRequest(new URL("https://localhost:8080/default.aspx"),
            new ArrayList<String>(), "", 20, true);
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    IOHelper.copyStream(output.getContents(), content);
    assertEquals("Golden Content", content.toString());
    output.getContents().close();
  }

  @Test
  public void testIssueGetRequestWithNonRootRelative() throws Exception {
    HttpClient client = new SharePointAdaptor.HttpClientImpl() {
      @Override
      public HttpURLConnection getHttpURLConnection(URL url)
          throws IOException {
        String urlToServe = url.toString();
        if ("http://localhost:8080/default.aspx"
            .equalsIgnoreCase(urlToServe)) {
          return new MockHttpURLConnection(url,
              HttpURLConnection.HTTP_MOVED_TEMP,
              "test/Final Document.aspx?q={data}", null);
        } else {
          throw new UnsupportedOperationException(
              "Unexpected redirect location");
        }
      }
    };
    thrown.expect(IOException.class);
    client.issueGetRequest(new URL("http://localhost:8080/default.aspx"),
        new ArrayList<String>(), "", 20, true);
  }

  @Test
  public void testEncodeSharePointUrl() throws Exception {
    //Just host
    assertEquals("http://intranet.example.com",
        SharePointAdaptor.encodeSharePointUrl(
            "http://intranet.example.com", true).toString());
    // No query params
    assertEquals("http://intranet.example.com/team%20site/page.aspx",
        SharePointAdaptor.encodeSharePointUrl(
            "http://intranet.example.com/team site/page.aspx", true)
        .toString());
    // Query params needs encoding
    assertEquals("https://localshost:80/team%20site/Final.aspx?q=%7Bdata%7D",
        SharePointAdaptor.encodeSharePointUrl(
            "https://localshost:80/team site/Final.aspx?q={data}", true)
        .toString());
    // Host with query params
    assertEquals("https://localshost:8080/?q=%7Bdata%20more%7D",
        SharePointAdaptor.encodeSharePointUrl(
            "https://localshost:8080?q={data more}", true).toString());
  }

  @Test
  public void testSharePointUrlNullInputUrl() {
    SharePointAdaptor adaptor = new SharePointAdaptor();
    thrown.expect(NullPointerException.class);
    adaptor.new SharePointUrl(null, "", "");
  }
  
  @Test
  public void testSharePointUrlConstructor() {
    SharePointAdaptor adaptor = new SharePointAdaptor();
    adaptor.new SharePointUrl("http://sharepoint.intranet.com", "", "");
  }
  
  @Test
  public void testSharePointUrlConstructorWithSpaceInUrl() {
    SharePointAdaptor adaptor = new SharePointAdaptor();
    adaptor.new SharePointUrl(
        "http://sharepoint.intranet.com/sites/new site collection", "", "");
  }
  
  @Test
  public void testSharePointUrlMalformedInput() {
    SharePointAdaptor adaptor = new SharePointAdaptor();
    thrown.expect(InvalidConfigurationException.class);
    adaptor.new SharePointUrl("malformed.sharepoint.com", "", "");
  }
  
  @Test
  public void testSharePointUrlAndRootUrl() {
    SharePointAdaptor adaptor = new SharePointAdaptor();
    SharePointUrl sharePointUrl = adaptor.new SharePointUrl(
        "http://localhost:1000/sites/collection/", "", "");
    assertEquals("http://localhost:1000/sites/collection",
        sharePointUrl.getSharePointUrl());
    assertEquals("http://localhost:1000", sharePointUrl.getVirtualServerUrl());
  }  
  
  @Test
  public void testSharePointUrlIsSiteCollectionUrl() {
    SharePointAdaptor adaptor = new SharePointAdaptor();
    SharePointUrl sharePointUrl = adaptor.new SharePointUrl(
        "http://localhost:1000/sites/collection/", "", "");
    assertTrue(sharePointUrl.isSiteCollectionUrl());
    
    SharePointUrl virtualServer 
        = adaptor.new SharePointUrl("http://localhost:1000/", "", "");
    assertFalse(virtualServer.isSiteCollectionUrl());
    
    SharePointUrl sharePointUrlWithMode = adaptor.new SharePointUrl(
        "http://localhost:1000/sites/collection/", "false", "");
    assertFalse(sharePointUrlWithMode.isSiteCollectionUrl());
    
    SharePointUrl virtualServerWithMode 
        = adaptor.new SharePointUrl("http://localhost:1000/", "true", "");
    assertTrue(virtualServerWithMode.isSiteCollectionUrl());

    thrown.expect(InvalidConfigurationException.class);
    SharePointUrl sharePointUrlWithSCWrongVirtualServerUrl
        = adaptor.new SharePointUrl("http://localhost:1000/sites/other", "true",
            "http://localhost:1000/sites/collection/");
  }

  private static <T> void setValue(Holder<T> holder, T value) {
    if (holder != null) {
      holder.value = value;
    }
  }

  private SPContentDatabase parseChanges(String xml) throws IOException {
    SiteDataClient client = new SiteDataClient(new UnsupportedSiteData(), true);
    String xmlns = "http://schemas.microsoft.com/sharepoint/soap/";
    xml = xml.replace("<SPContentDatabase ",
        "<SPContentDatabase xmlns='" + xmlns + "' ");
    return client.jaxbParse(xml, SPContentDatabase.class);
  }

  private static String loadTestString(String testString) {
    try {
      return loadResourceAsString("spresponses/" + testString);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static String loadResourceAsString(String resource)
      throws IOException {
    return IOHelper.readInputStreamToString(SharePointAdaptorTest.class
        .getResourceAsStream(resource), Charset.forName("UTF-8"));
  }

  private static class UnsupportedHttpClient implements HttpClient {
    @Override
    public FileInfo issueGetRequest(URL url,
        List<String> authenticationCookies, String adaptorUserAgent,
        int maxRedirectsToFollow, boolean performBrowserLeniency) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getRedirectLocation(URL url,
        List<String> authenticationCookies, String adaptorUserAgent)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public HttpURLConnection getHttpURLConnection(URL url) throws IOException {
      throw new UnsupportedOperationException();
    }
  }
  
  private abstract static class DelegatingPeopleSoap implements PeopleSoap {
    protected abstract PeopleSoap delegate();

    @Override
    public boolean isClaimsMode() {
      return delegate().isClaimsMode();
    } 

    @Override
    public ArrayOfPrincipalInfo resolvePrincipals(
        ArrayOfString aos, SPPrincipalType sppt, boolean bln) {
      return delegate().resolvePrincipals(aos, sppt, bln);
    }

    @Override
    public ArrayOfPrincipalInfo searchPrincipals(
        String string, int i, SPPrincipalType sppt) {
      return delegate().searchPrincipals(string, i, sppt);
    }
  }
  
  private static class UnsupportedPeopleSoap extends DelegatingPeopleSoap
      implements BindingProvider {
    private final String endpoint;
    private final Map<String, Object> requestContext
        = new HashMap<String, Object>();

    public UnsupportedPeopleSoap() {
      this(null);
    }

    public UnsupportedPeopleSoap(String endpoint) {
      this.endpoint = endpoint;
    }

    @Override
    protected PeopleSoap delegate() {
      if (endpoint == null) {
        throw new UnsupportedOperationException();
      } else {
        throw new UnsupportedOperationException("Endpoint: " + endpoint);
      }
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
  
  private static class MockPeopleSoap extends UnsupportedPeopleSoap {
    private final ArrayOfPrincipalInfo result;
    private Map<String, Object> requestContext = new HashMap<String, Object>();
   
    public MockPeopleSoap() {
      this.result = new ArrayOfPrincipalInfo();
    }
    
    @Override
    public ArrayOfPrincipalInfo resolvePrincipals(
        ArrayOfString aos, SPPrincipalType sppt, boolean bln) {      
      return result;     
    }
    
    public void addToResult(String accountName, String dispalyName, 
        SPPrincipalType type) {
      PrincipalInfo p = new PrincipalInfo();
      p.setAccountName(accountName);
      p.setDisplayName(dispalyName);
      p.setIsResolved(true);
      p.setPrincipalType(type);
      result.getPrincipalInfo().add(p);      
    }

    @Override
    public Map<String, Object> getRequestContext() {
      return requestContext;
    }    
  }

  private static class MockUserGroupSoap extends UnsupportedUserGroupSoap {
    final Users users;
    private Map<String, Object> requestContext = new HashMap<String, Object>();
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
        
    @Override
    public Map<String, Object> getRequestContext() {
      return requestContext;
    }   
  }
  
  private static class MockUserGroupSoapException
      extends UnsupportedUserGroupSoap {
    private Map<String, Object> requestContext = new HashMap<String, Object>();
    // counter for site user mapping call
    private AtomicLong atomicNumberGetSiteUserMappingCalls = new AtomicLong(0);

    @Override
    public GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult
        getUserCollectionFromSite() {
          atomicNumberGetSiteUserMappingCalls.incrementAndGet();
          throw new WebServiceException("Mock SOAP error");
    }

    @Override
    public Map<String, Object> getRequestContext() {
      return requestContext;
    }
  }

  private static class UnsupportedUserGroupSoap
      extends DelegatingUserGroupSoap  implements BindingProvider {
    private final String endpoint;
    private final Map<String, Object> requestContext
        = new HashMap<String, Object>();

    public UnsupportedUserGroupSoap() {
      this(null);
    }

    public UnsupportedUserGroupSoap(String endpoint) {
      this.endpoint = endpoint;
    }

    @Override
    protected UserGroupSoap delegate() {
      if (endpoint == null) {
        throw new UnsupportedOperationException();
      } else {
        throw new UnsupportedOperationException("Endpoint: " + endpoint);
      }
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
    public <T extends EndpointReference> T 
        getEndpointReference(Class<T> clazz) {
      throw new UnsupportedOperationException();
    }
  }

  private abstract static class DelegatingUserGroupSoap
      implements UserGroupSoap {
    protected abstract UserGroupSoap delegate();

    @Override
    public GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult
        getUserCollectionFromSite() {
      return delegate().getUserCollectionFromSite();
    }

    @Override
    public GetUserCollectionFromWebResponse.GetUserCollectionFromWebResult
        getUserCollectionFromWeb() {
      return delegate().getUserCollectionFromWeb();
    }

    @Override
    public GetAllUserCollectionFromWebResponse.GetAllUserCollectionFromWebResult
        getAllUserCollectionFromWeb() {
      return delegate().getAllUserCollectionFromWeb();
    }

    @Override
    public GetUserCollectionFromGroupResponse.GetUserCollectionFromGroupResult
        getUserCollectionFromGroup(String string) {
      return delegate().getUserCollectionFromGroup(string);
    }

    @Override
    public GetUserCollectionFromRoleResponse.GetUserCollectionFromRoleResult
        getUserCollectionFromRole(String string) {
      return delegate().getUserCollectionFromRole(string);
    }

    @Override
    public GetUserCollectionResponse.GetUserCollectionResult
        getUserCollection(GetUserCollection.UserLoginNamesXml ulnx) {
      return delegate().getUserCollection(ulnx);
    }

    @Override
    public GetUserInfoResponse.GetUserInfoResult getUserInfo(String string) {
      return delegate().getUserInfo(string);
    }

    @Override
    public GetCurrentUserInfoResponse.GetCurrentUserInfoResult
        getCurrentUserInfo() {
      return delegate().getCurrentUserInfo();
    }

    @Override
    public void addUserToGroup(String string, String string1,
        String string2, String string3, String string4) {
      delegate().addUserToGroup(string, string1, string2, string3, string4);
    }

    @Override
    public void addUserCollectionToGroup(String string,
        AddUserCollectionToGroup.UsersInfoXml uix) {
      delegate().addUserCollectionToGroup(string, uix);
    }

    @Override
    public void addUserToRole(String string, String string1,
        String string2, String string3, String string4) {
      delegate().addUserToRole(string, string1, string2, string3, string4);
    }

    @Override
    public void addUserCollectionToRole(String string,
        AddUserCollectionToRole.UsersInfoXml uix) {
      delegate().addUserCollectionToRole(string, uix);
    }

    @Override
    public void updateUserInfo(String string, String string1,
        String string2, String string3) {
      delegate().updateUserInfo(string, string1, string2, string3);
    }

    @Override
    public void removeUserFromSite(String string) {
      delegate().removeUserFromSite(string);
    }

    @Override
    public void removeUserCollectionFromSite(
        RemoveUserCollectionFromSite.UserLoginNamesXml ulnx) {
      delegate().removeUserCollectionFromSite(ulnx);
    }

    @Override
    public void removeUserFromWeb(String string) {
      delegate().removeUserFromWeb(string);
    }

    @Override
    public void removeUserFromGroup(String string, String string1) {
      delegate().removeUserFromGroup(string, string1);
    }

    @Override
    public void removeUserCollectionFromGroup(String string,
        RemoveUserCollectionFromGroup.UserLoginNamesXml ulnx) {
      delegate().removeUserCollectionFromGroup(string, ulnx);
    }

    @Override
    public void removeUserFromRole(String string, String string1) {
      delegate().removeUserFromRole(string, string1);
    }

    @Override
    public void removeUserCollectionFromRole(String string,
        RemoveUserCollectionFromRole.UserLoginNamesXml ulnx) {
      delegate().removeUserCollectionFromRole(string, ulnx);
    }

    @Override
    public GetGroupCollectionFromSiteResponse.GetGroupCollectionFromSiteResult
        getGroupCollectionFromSite() {
      return delegate().getGroupCollectionFromSite();
    }

    @Override
    public GetGroupCollectionFromWebResponse.GetGroupCollectionFromWebResult
        getGroupCollectionFromWeb() {
      return delegate().getGroupCollectionFromWeb();
    }

    @Override
    public GetGroupCollectionFromRoleResponse.GetGroupCollectionFromRoleResult
        getGroupCollectionFromRole(String string) {
      return delegate().getGroupCollectionFromRole(string);
    }

    @Override
    public GetGroupCollectionFromUserResponse.GetGroupCollectionFromUserResult
        getGroupCollectionFromUser(String string) {
      return delegate().getGroupCollectionFromUser(string);
    }

    @Override
    public GetGroupCollectionResponse.GetGroupCollectionResult
        getGroupCollection(GroupsInputType git) {
      return delegate().getGroupCollection(git);
    }

    @Override
    public GetGroupInfoResponse.GetGroupInfoResult getGroupInfo(String string) {
      return delegate().getGroupInfo(string);
    }

    @Override
    public void addGroup(String string, String string1, PrincipalType pt,
        String string2, String string3) {
      delegate().addGroup(string, string1, pt, string2, string3);
    }

    @Override
    public void addGroupToRole(String string, String string1) {
      delegate().addGroupToRole(string, string1);
    }

    @Override
    public void updateGroupInfo(String string, String string1,
        String string2, PrincipalType pt, String string3) {
      delegate().updateGroupInfo(string, string1, string2, pt, string3);
    }

    @Override
    public void removeGroup(String string) {
      delegate().removeGroup(string);
    }

    @Override
    public void removeGroupFromRole(String string, String string1) {
      delegate().removeGroupFromRole(string, string1);
    }

    @Override
    public GetRoleCollectionFromWebResponse.GetRoleCollectionFromWebResult
        getRoleCollectionFromWeb() {
      return delegate().getRoleCollectionFromWeb();
    }

    @Override
    public GetRoleCollectionFromGroupResponse.GetRoleCollectionFromGroupResult
        getRoleCollectionFromGroup(String string) {
      return delegate().getRoleCollectionFromGroup(string);
    }

    @Override
    public GetRoleCollectionFromUserResponse.GetRoleCollectionFromUserResult
        getRoleCollectionFromUser(String string) {
      return delegate().getRoleCollectionFromUser(string);
    }

    @Override
    public GetRoleCollectionResponse.GetRoleCollectionResult
        getRoleCollection(RolesInputType rit) {
      return delegate().getRoleCollection(rit);
    }

    @Override
    public RoleOutputType getRoleInfo(String string) {
      return delegate().getRoleInfo(string);
    }

    @Override
    public void addRole(String string, String string1, int i) {
      delegate().addRole(string, string1, i);
    }

    @Override
    public void addRoleDef(String string, String string1, BigInteger bi) {
      delegate().addRoleDef(string, string1, bi);
    }

    @Override
    public void updateRoleInfo(String string, String string1,
        String string2, int i) {
      delegate().updateRoleInfo(string, string1, string2, i);
    }

    @Override
    public void updateRoleDefInfo(String string, String string1,
        String string2, BigInteger bi) {
      delegate().updateRoleDefInfo(string, string1, string2, bi);
    }

    @Override
    public void removeRole(String string) {
      delegate().removeRole(string);
    }

    @Override
    public GetUserLoginFromEmailResponse.GetUserLoginFromEmailResult
        getUserLoginFromEmail(EmailsInputType eit) {
      return delegate().getUserLoginFromEmail(eit);
    }

    @Override
    public GetRolesAndPermissionsForCurrentUserResponse
        .GetRolesAndPermissionsForCurrentUserResult
        getRolesAndPermissionsForCurrentUser() {
      return delegate().getRolesAndPermissionsForCurrentUser();
    }

    @Override
    public GetRolesAndPermissionsForSiteResponse
        .GetRolesAndPermissionsForSiteResult getRolesAndPermissionsForSite() {
      return delegate().getRolesAndPermissionsForSite();
    }
  }
  
  private static class MockAuthenticationSoap extends 
      UnsupportedAuthenticationSoap {
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
  }
  
  private static class UnsupportedAuthenticationSoap 
      extends DelegatingAuthenticationSoap {
    private final String endpoint;

    public UnsupportedAuthenticationSoap() {
      this(null);
    }

    public UnsupportedAuthenticationSoap(String endpoint) {
      this.endpoint = endpoint;
    }

    @Override
    protected AuthenticationSoap delegate() {
      if (endpoint == null) {
        throw new UnsupportedOperationException();
      } else {
        throw new UnsupportedOperationException("Endpoint: " + endpoint);
      }
    }

    @Override
    public Map<String, Object> getRequestContext() {
      throw new UnsupportedOperationException();
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


  private abstract static class DelegatingAuthenticationSoap 
      implements AuthenticationSoap, BindingProvider {
    protected abstract AuthenticationSoap delegate();

    @Override
    public LoginResult login(String username, String password) {
      return delegate().login(username, password);
    }

    @Override
    public AuthenticationMode mode() {
      return delegate().mode();
    }    
    
    @Override
    public Map<String, Object> getRequestContext() {
      return ((BindingProvider) delegate()).getRequestContext();
    }

    @Override
    public Map<String, Object> getResponseContext() {
      return ((BindingProvider) delegate()).getResponseContext();
    }

    @Override
    public Binding getBinding() {
      return ((BindingProvider) delegate()).getBinding();
    }

    @Override
    public EndpointReference getEndpointReference() {
      return ((BindingProvider) delegate()).getEndpointReference();
    }

    @Override
    public <T extends EndpointReference> T getEndpointReference(
        Class<T> clazz) {
      return ((BindingProvider) delegate()).getEndpointReference(clazz);
    }
  }

  /**
   * Throw UnsupportedOperationException for all calls.
   */
  private static class UnsupportedSiteData extends DelegatingSiteData
      implements BindingProvider {
    private final Map<String, Object> requestContext
        = new HashMap<String, Object>();
    @Override
    protected SiteDataSoap delegate() {
      throw new UnsupportedOperationException();
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
    public <T extends EndpointReference> T 
        getEndpointReference(Class<T> clazz) {
      throw new UnsupportedOperationException();
    }
  }

  private static class UnsupportedCallable<V> implements Callable<V> {
    @Override
    public V call() {
      throw new UnsupportedOperationException();
    }
  }

  private static class MockSoapFactory implements SoapFactory {
    private final String expectedEndpoint;
    private final SiteDataSoap siteData;
    private final UserGroupSoap userGroup;  
    private final PeopleSoap people;
    private final MockSoapFactory chain;

    private MockSoapFactory(String expectedEndpoint, SiteDataSoap siteData,
        UserGroupSoap userGroup, PeopleSoap people, MockSoapFactory chain) {
      this.expectedEndpoint = expectedEndpoint;
      this.siteData = siteData;
      this.userGroup = userGroup;
      this.people = people;   
      this.chain = chain;
    }

    public static MockSoapFactory blank() {
      return new MockSoapFactory(null, null, null, null, null);
    }

    public MockSoapFactory endpoint(String expectedEndpoint,
        SiteDataSoap siteData) {
      return new MockSoapFactory(
          expectedEndpoint, siteData, null, null, this);
    }

    public MockSoapFactory endpoint(String expectedEndpoint,
        UserGroupSoap userGroup) {
      return new MockSoapFactory(
          expectedEndpoint, null, userGroup, null, this);
    }
    
    public MockSoapFactory endpoint(String expectedEndpoint,
        PeopleSoap people) {
      return new MockSoapFactory(
          expectedEndpoint, null, null, people, this);
    }    

    @Override
    public SiteDataSoap newSiteData(String endpoint) {
      if (chain == null) {
        fail("Could not find endpoint " + endpoint);
      }
      if (expectedEndpoint.equals(endpoint) && siteData != null) {
        return siteData;
      }
      return chain.newSiteData(endpoint);
    }

    @Override
    public UserGroupSoap newUserGroup(String endpoint) {
      if (chain == null) {
        // UserGroupSoaps are commonly created but rarely used, so we go ahead
        // and just provide an instance instead of forcing all users of the mock
        // to populate trash instances.
        return new UnsupportedUserGroupSoap(endpoint);
      }
      if (expectedEndpoint.equals(endpoint) && userGroup != null) {
        return userGroup;
      }
      return chain.newUserGroup(endpoint);
    }

    @Override
    public PeopleSoap newPeople(String endpoint) {
      if (chain == null) {
        return new UnsupportedPeopleSoap(endpoint);
      }
      if (expectedEndpoint.equals(endpoint) && people != null) {
        return people;
      }
      return chain.newPeople(endpoint);
    }
  }

  private static class ReferenceSiteData extends DelegatingSiteData {
    private volatile SiteDataSoap siteData = new UnsupportedSiteData();   

    @Override
    protected SiteDataSoap delegate() {
      return siteData;
    }

    public void setSiteDataSoap(SiteDataSoap siteData) {
      if (siteData == null) {
        throw new NullPointerException();
      }
      this.siteData = siteData;
    }
  }

  private static class MockSiteData extends UnsupportedSiteData {
    private final List<URLSegmentsExchange> urlSegmentsList;
    private final List<ContentExchange> contentList;
    private final List<ChangesExchange> changesList;
    private final List<SiteAndWebExchange> siteAndWebList;

    private MockSiteData() {
      this.urlSegmentsList = Collections.emptyList();
      this.contentList = Collections.emptyList();
      this.changesList = Collections.emptyList();
      this.siteAndWebList = Collections.emptyList();
    }

    private MockSiteData(List<URLSegmentsExchange> urlSegmentsList,
        List<ContentExchange> contentList, List<ChangesExchange> changesList,
        List<SiteAndWebExchange> siteAndWebList) {
      this.urlSegmentsList = urlSegmentsList;
      this.contentList = contentList;
      this.changesList = changesList;
      this.siteAndWebList = siteAndWebList;
    }

    @Override
    public void getURLSegments(String strURL,
        Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
        Holder<String> strBucketID, Holder<String> strListID,
        Holder<String> strItemID) {
      for (URLSegmentsExchange ex : urlSegmentsList) {
        if (!ex.strURL.equals(strURL)) {
          continue;
        }
        setValue(getURLSegmentsResult, ex.getURLSegmentsResult);
        setValue(strWebID, ex.strWebID);
        setValue(strBucketID, ex.strBucketID);
        setValue(strListID, ex.strListID);
        setValue(strItemID, ex.strItemID);
        return;
      }
      fail("Could not find " + strURL);
    }

    @Override
    public void getContent(ObjectType objectType, String objectId,
        String folderUrl, String itemId, boolean retrieveChildItems,
        boolean securityOnly, Holder<String> lastItemIdOnPage,
        Holder<String> getContentResult) {
      for (ContentExchange ex : contentList) {
        if (!ex.objectType.equals(objectType)
            || !Objects.equal(ex.objectId, objectId)
            || !Objects.equal(ex.folderUrl, folderUrl)
            || !Objects.equal(ex.itemId, itemId)
            || ex.retrieveChildItems != retrieveChildItems
            || ex.securityOnly != securityOnly
            || ex.responseUsed.get()) {
          continue;
        }
        if (ex.exceptionToThrow != null) {
          ex.responseUsed.set(ex.useOnce);
          throw ex.exceptionToThrow;
        }
        ex.responseUsed.set(ex.useOnce);
        setValue(lastItemIdOnPage, ex.lastItemIdOnPage);
        setValue(getContentResult, ex.getContentResult);
        return;
      }
      fail("Could not find " + objectType + ", " + objectId + ", " + folderUrl
          + ", " + itemId + ", " + retrieveChildItems + ", " + securityOnly);
    }

    @Override
    public void getChanges(ObjectType objectType, String contentDatabaseId,
        Holder<String> lastChangeId, Holder<String> currentChangeId,
        Integer timeout, Holder<String> getChangesResult,
        Holder<Boolean> moreChanges) {
      for (ChangesExchange ex : changesList) {
        if (!ex.objectType.equals(objectType)
            || !Objects.equal(ex.contentDatabaseId, contentDatabaseId)
            || !Objects.equal(ex.lastChangeIdIn, lastChangeId.value)
            || !Objects.equal(ex.currentChangeIdIn, currentChangeId.value)
            || !Objects.equal(ex.timeout, timeout)) {
          continue;
        }
        setValue(lastChangeId, ex.lastChangeIdOut);
        setValue(currentChangeId, ex.currentChangeIdOut);
        setValue(getChangesResult, ex.getChangesResult);
        setValue(moreChanges, ex.moreChanges);
        return;
      }
      fail("Could not find " + objectType + ", " + contentDatabaseId + ", "
          + lastChangeId.value + ", " + currentChangeId.value + ", " + timeout);
    }

    @Override
    public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
        Holder<String> strSite, Holder<String> strWeb) {
      for (SiteAndWebExchange ex : siteAndWebList) {
        if (!ex.strUrl.equals(strUrl)) {
          continue;
        }
        setValue(getSiteAndWebResult, ex.getSiteAndWebResult);
        setValue(strSite, ex.strSite);
        setValue(strWeb, ex.strWeb);
        return;
      }
      fail("Could not find " + strUrl);
    }

    public static MockSiteData blank() {
      return new MockSiteData();
    }

    public MockSiteData register(URLSegmentsExchange use) {
      return new MockSiteData(addToList(urlSegmentsList, use),
          contentList, changesList, siteAndWebList);
    }

    public MockSiteData register(ContentExchange ce) {
      return new MockSiteData(urlSegmentsList, addToList(contentList, ce),
          changesList, siteAndWebList);
    }

    public MockSiteData register(ChangesExchange ce) {
      return new MockSiteData(urlSegmentsList, contentList,
          addToList(changesList, ce), siteAndWebList);
    }

    public MockSiteData register(SiteAndWebExchange sawe) {
      return new MockSiteData(urlSegmentsList, contentList, changesList,
          addToList(siteAndWebList, sawe));
    }

    /** Creates a new list that has the item appended. */
    private <T> List<T> addToList(List<T> existingList, T item) {
      List<T> l = new ArrayList<T>(existingList);
      l.add(item);
      return Collections.unmodifiableList(l);
    }    
  }

  private static class URLSegmentsExchange {
    public final String strURL;
    public final boolean getURLSegmentsResult;
    public final String strWebID;
    public final String strBucketID;
    public final String strListID;
    public final String strItemID;

    public URLSegmentsExchange(String strURL, boolean getURLSegmentsResult,
        String strWebID, String strBucketID, String strListID,
        String strItemID) {
      this.strURL = strURL;
      this.getURLSegmentsResult = getURLSegmentsResult;
      this.strWebID = strWebID;
      this.strBucketID = strBucketID;
      this.strListID = strListID;
      this.strItemID = strItemID;
    }
  }

  private static class ContentExchange {
    public final ObjectType objectType;
    public final String objectId;
    public final String folderUrl;
    public final String itemId;
    public final boolean retrieveChildItems;
    public final boolean securityOnly;
    public final String lastItemIdOnPage;
    public final String getContentResult;
    public final WebServiceException exceptionToThrow;
    /**
    * Signals that ContentExchange will be called only once when useOnce is true
    * Subsequent calls to MockSiteData.getContent will look for another
    * registered ContentExchange.
    */
    public final boolean useOnce;
    final AtomicBoolean responseUsed = new AtomicBoolean(false);

    public ContentExchange(ObjectType objectType, String objectId,
        String folderUrl, String itemId, boolean retrieveChildItems,
        boolean securityOnly, String lastItemIdOnPage,
        String getContentResult) {
      this(objectType, objectId, folderUrl, itemId, retrieveChildItems,
          securityOnly, lastItemIdOnPage, getContentResult, false);
    }
    
    public ContentExchange(ObjectType objectType, String objectId,
        String folderUrl, String itemId, boolean retrieveChildItems,
        boolean securityOnly, String lastItemIdOnPage,
        String getContentResult, boolean useOnce) {
      this(objectType, objectId, folderUrl, itemId, retrieveChildItems,
          securityOnly, lastItemIdOnPage, getContentResult, useOnce, null);
    }

    public ContentExchange(ObjectType objectType, String objectId,
        String folderUrl, String itemId, boolean retrieveChildItems,
        boolean securityOnly, String lastItemIdOnPage,
        String getContentResult, boolean useOnce,
        WebServiceException exceptionToThrow) {
      this.objectType = objectType;
      this.objectId = objectId;
      this.folderUrl = folderUrl;
      this.itemId = itemId;
      this.retrieveChildItems = retrieveChildItems;
      this.securityOnly = securityOnly;
      this.lastItemIdOnPage = lastItemIdOnPage;
      this.getContentResult = getContentResult;
      this.exceptionToThrow = exceptionToThrow;
      this.useOnce = useOnce;
    }

    public ContentExchange replaceInContent(String match, String replacement) {
      String result = getContentResult.replace(match, replacement);
      if (getContentResult.equals(result)) {
        fail("Replacement had not effect");
      }
      return new ContentExchange(objectType, objectId, folderUrl, itemId,
          retrieveChildItems, securityOnly, lastItemIdOnPage, result);
    }
  }

  private static class ChangesExchange {
    public final ObjectType objectType;
    public final String contentDatabaseId;
    public final String lastChangeIdIn;
    public final String lastChangeIdOut;
    public final String currentChangeIdIn;
    public final String currentChangeIdOut;
    public final Integer timeout;
    public final String getChangesResult;
    public final boolean moreChanges;

    public ChangesExchange(ObjectType objectType, String contentDatabaseId,
        String lastChangeIdIn, String lastChangeIdOut, String currentChangeIdIn,
        String currentChangeIdOut, Integer timeout, String getChangesResult,
        boolean moreChanges) {
      this.objectType = objectType;
      this.contentDatabaseId = contentDatabaseId;
      this.lastChangeIdIn = lastChangeIdIn;
      this.lastChangeIdOut = lastChangeIdOut;
      this.currentChangeIdIn = currentChangeIdIn;
      this.currentChangeIdOut = currentChangeIdOut;
      this.timeout = timeout;
      this.getChangesResult = getChangesResult;
      this.moreChanges = moreChanges;
    }
  }

  private static class SiteAndWebExchange {
    public final String strUrl;
    public final long getSiteAndWebResult;
    public final String strSite;
    public final String strWeb;

    public SiteAndWebExchange(String strUrl, long getSiteAndWebResult,
        String strSite, String strWeb) {
      this.strUrl = strUrl;
      this.getSiteAndWebResult = getSiteAndWebResult;
      this.strSite = strSite;
      this.strWeb = strWeb;
    }
  }

  private static class MemberIdMappingBuilder {
    private final Map<Integer, Principal> map
        = new HashMap<Integer, Principal>();

    public MemberIdMapping build() {
      return new MemberIdMapping(map);
    }

    public MemberIdMappingBuilder put(Integer i, Principal p) {
      map.put(i, p);
      return this;
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
      return null;
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
  
  private static class MockAuthenticationClientFactoryAdfs 
      extends UnsupportedAuthenticationClientFactory {
    @Override
    public SamlHandshakeManager newAdfsAuthentication(
        String virtualServer, String username, String password,
        String stsendpoint, String stsrelam, String login,
        String trustlocation) throws IOException {
      return new MockSamlHandshakeManager("Token", "rtf=authenticationCookie;");
    }
  }
  
  private static class MockAuthenticationClientFactoryLive
      extends UnsupportedAuthenticationClientFactory {
    @Override
    public SamlHandshakeManager newLiveAuthentication(
        String virtualServer, String username, String password)
        throws IOException {
      return new MockSamlHandshakeManager("Token", "rtf=authenticationCookie;");
    }
  }
  
  private static class MockSamlHandshakeManager 
      implements SamlHandshakeManager {
    private String token;
    private String cookie;
    MockSamlHandshakeManager(String token, String cookie) {
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
  
  
  private static class MockHttpURLConnection extends HttpURLConnection {
    private final int responseCodeToReturn;
    private final String redirectLocation;
    private final String content;
    
    public MockHttpURLConnection(URL url, int responseCodeToReturn,
        String redirectLocation, String content) {
      super(url);
      this.responseCodeToReturn = responseCodeToReturn;
      this.redirectLocation = redirectLocation;
      this.content = content;      
    }
    @Override
    public void disconnect() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean usingProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void connect() throws IOException {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public int getResponseCode() throws IOException {
      return responseCodeToReturn;
    }
    
    @Override
    public String getHeaderField(String name) {
      if ("Location".equals(name)) {
        return redirectLocation;
      }
      return null;
    }
    
    @Override
    public InputStream getInputStream() throws IOException {
      if (responseCodeToReturn >= HttpURLConnection.HTTP_BAD_REQUEST) {
        return null;
      }
      if (content == null) {
        return new ByteArrayInputStream("".getBytes());
      }
      return new ByteArrayInputStream(content.getBytes());
    }

    @Override
    public InputStream getErrorStream() {
      if (responseCodeToReturn < HttpURLConnection.HTTP_BAD_REQUEST
          || content == null) {
        return null;
      }
      return new ByteArrayInputStream("".getBytes());
    }
  }
  
  private static class UnsupportedActiveDirectoryClientFactory
      implements ActiveDirectoryClientFactory {
    @Override
    public ActiveDirectoryClient newActiveDirectoryClient(String host,
        int port, String username, String password, String method)
        throws IOException {
      throw new UnsupportedOperationException();      
    }    
  }
  
  private static class MockActiveDirectoryClientFactory
      extends UnsupportedActiveDirectoryClientFactory {
    private ADServer adServer;
    @Override
    public ActiveDirectoryClient newActiveDirectoryClient(String host,
        int port, String username, String password, String method)
        throws IOException{
      return new ActiveDirectoryClient(adServer);     
    }
    
    public MockActiveDirectoryClientFactory(ADServer adServer) {
      this.adServer = adServer;
    }
  }
  
  private static class MockADServer implements ADServer {
    Map<String, String> lookup;
    
    public MockADServer(Map<String, String> lookup) {
      this.lookup = lookup;
    }
    
    @Override
    public String getUserAccountBySid(String sid) throws IOException {      
      return lookup.get(sid);
    }
    
    @Override
    public void start() throws IOException {
    }    
  }    
}
