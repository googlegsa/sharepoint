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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdEncoder;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.IOHelper;
import com.google.enterprise.adaptor.PollingIncrementalLister;
import com.google.enterprise.adaptor.Principal;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.UserPrincipal;
import com.google.enterprise.adaptor.sharepoint.RareModificationCache.CachedList;
import com.google.enterprise.adaptor.sharepoint.RareModificationCache.CachedVirtualServer;
import com.google.enterprise.adaptor.sharepoint.RareModificationCache.CachedWeb;
import com.google.enterprise.adaptor.sharepoint.SiteDataClient.CursorPaginator;
import com.google.enterprise.adaptor.sharepoint.SiteDataClient.Paginator;
import com.google.enterprise.adaptor.sharepoint.SiteDataClient.XmlProcessingException;

import com.microsoft.schemas.sharepoint.soap.ContentDatabase;
import com.microsoft.schemas.sharepoint.soap.ContentDatabases;
import com.microsoft.schemas.sharepoint.soap.Files;
import com.microsoft.schemas.sharepoint.soap.FolderData;
import com.microsoft.schemas.sharepoint.soap.Folders;
import com.microsoft.schemas.sharepoint.soap.GroupMembership;
import com.microsoft.schemas.sharepoint.soap.Item;
import com.microsoft.schemas.sharepoint.soap.ItemData;
import com.microsoft.schemas.sharepoint.soap.Lists;
import com.microsoft.schemas.sharepoint.soap.ObjectType;
import com.microsoft.schemas.sharepoint.soap.Permission;
import com.microsoft.schemas.sharepoint.soap.PolicyUser;
import com.microsoft.schemas.sharepoint.soap.SPContentDatabase;
import com.microsoft.schemas.sharepoint.soap.SPList;
import com.microsoft.schemas.sharepoint.soap.SPListItem;
import com.microsoft.schemas.sharepoint.soap.SPSite;
import com.microsoft.schemas.sharepoint.soap.SPWeb;
import com.microsoft.schemas.sharepoint.soap.Scopes;
import com.microsoft.schemas.sharepoint.soap.Site;
import com.microsoft.schemas.sharepoint.soap.SiteDataSoap;
import com.microsoft.schemas.sharepoint.soap.Sites;
import com.microsoft.schemas.sharepoint.soap.TrueFalseType;
import com.microsoft.schemas.sharepoint.soap.UserDescription;
import com.microsoft.schemas.sharepoint.soap.VirtualServer;
import com.microsoft.schemas.sharepoint.soap.Web;
import com.microsoft.schemas.sharepoint.soap.Webs;
import com.microsoft.schemas.sharepoint.soap.Xml;
import com.microsoft.schemas.sharepoint.soap.authentication.AuthenticationSoap;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromSiteResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult;
import com.microsoft.schemas.sharepoint.soap.directory.User;
import com.microsoft.schemas.sharepoint.soap.directory.UserGroupSoap;
import com.microsoft.schemas.sharepoint.soap.people.ArrayOfPrincipalInfo;
import com.microsoft.schemas.sharepoint.soap.people.ArrayOfString;
import com.microsoft.schemas.sharepoint.soap.people.PeopleSoap;
import com.microsoft.schemas.sharepoint.soap.people.PrincipalInfo;
import com.microsoft.schemas.sharepoint.soap.people.SPPrincipalType;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.EndpointReference;
import javax.xml.ws.Holder;
import javax.xml.ws.Service;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.wsaddressing.W3CEndpointReferenceBuilder;

/**
 * SharePoint Adaptor for the GSA.
 */
public class SharePointAdaptor extends AbstractAdaptor
    implements PollingIncrementalLister {
  /** Charset used in generated HTML responses. */
  private static final Charset CHARSET = Charset.forName("UTF-8");
  private static final String XMLNS_DIRECTORY
      = "http://schemas.microsoft.com/sharepoint/soap/directory/";
  
  /** SharePoint's namespace. */
  private static final String XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";

  /**
   * The data element within a self-describing XML blob. See
   * http://msdn.microsoft.com/en-us/library/windows/desktop/ms675943.aspx .
   */
  private static final QName DATA_ELEMENT
      = new QName("urn:schemas-microsoft-com:rowset", "data");
  /**
   * The row element within a self-describing XML blob. See
   * http://msdn.microsoft.com/en-us/library/windows/desktop/ms675943.aspx .
   */
  private static final QName ROW_ELEMENT = new QName("#RowsetSchema", "row");
  /**
   * Row attribute guaranteed to be in ListItem responses. See
   * http://msdn.microsoft.com/en-us/library/dd929205.aspx . Provides ability to
   * distinguish between folders and other list items.
   */
  private static final String OWS_FSOBJTYPE_ATTRIBUTE = "ows_FSObjType";
  private static final String OWS_AUTHOR_ATTRIBUTE = "ows_Author";
  /** Row attribute that contains the title of the List Item. */
  private static final String OWS_TITLE_ATTRIBUTE = "ows_Title";
  /**
   * Row attribute that contains a URL-like string identifying the object.
   * Sometimes this can be modified (by turning spaces into %20 and the like) to
   * access the object. In general, this in the string we provide to SP to
   * resolve information about the object.
   */
  private static final String OWS_SERVERURL_ATTRIBUTE = "ows_ServerUrl";
  /**
   * Row attribute that contains a hierarchial hex number that describes the
   * type of object. See http://msdn.microsoft.com/en-us/library/aa543822.aspx
   * for more information about content type IDs.
   */
  private static final String OWS_CONTENTTYPEID_ATTRIBUTE = "ows_ContentTypeId";
  /**
   * Row attribute guaranteed to be in ListItem responses. See
   * http://msdn.microsoft.com/en-us/library/dd929205.aspx . Provides scope id
   * used for permissions. Note that the casing is different than documented;
   * this is simply because of a documentation bug.
   */
  private static final String OWS_SCOPEID_ATTRIBUTE = "ows_ScopeId";
  private static final String OWS_FILEDIRREF_ATTRIBUTE = "ows_FileDirRef";
  /**
   * As described at http://msdn.microsoft.com/en-us/library/aa543822.aspx .
   */
  private static final String CONTENTTYPEID_DOCUMENT_PREFIX = "0x0101";
  /** Provides the number of attachments the list item has. */
  private static final String OWS_ATTACHMENTS_ATTRIBUTE = "ows_Attachments";
  /** The last time metadata or content was modified. */
  private static final String OWS_LAST_MODIFIED_ATTRIBUTE
      = "ows_Last_x0020_Modified";
  /**
   * Matches a SP-encoded value that contains one or more values. See {@link
   * SiteAdaptor.addMetadata}.
   */
  private static final Pattern ALTERNATIVE_VALUE_PATTERN
      = Pattern.compile("^\\d+;#");
  /**
   * As defined at http://msdn.microsoft.com/en-us/library/ee394878.aspx .
   */
  private static final long VIEW_LIST_ITEMS_MASK = 0x0000000000000001;
  /**
   * As defined at http://msdn.microsoft.com/en-us/library/ee394878.aspx .
   */
  private static final long OPEN_MASK = 0x0000000000010000;
  /**
   * As defined at http://msdn.microsoft.com/en-us/library/ee394878.aspx .
   */
  private static final long VIEW_PAGES_MASK = 0x0000000000020000;
  /**
   * As defined at http://msdn.microsoft.com/en-us/library/ee394878.aspx .
   */
  private static final long MANAGE_LIST_MASK = 0x0000000000000800;

  static final long LIST_ITEM_MASK
      = OPEN_MASK | VIEW_PAGES_MASK | VIEW_LIST_ITEMS_MASK;
  private static final long READ_SECURITY_LIST_ITEM_MASK
      = OPEN_MASK | VIEW_PAGES_MASK | VIEW_LIST_ITEMS_MASK | MANAGE_LIST_MASK;

  private static final int LIST_READ_SECURITY_ENABLED = 2;

  private static final String IDENTITY_CLAIMS_PREFIX = "i:0";

  private static final String OTHER_CLAIMS_PREFIX = "c:0";
  
  private static final String METADATA_OBJECT_TYPE = "google:objecttype";
  private static final String METADATA_PARENT_WEB_TITLE 
      = "sharepoint:parentwebtitle";
  private static final String METADATA_LIST_GUID = "sharepoint:listguid";

  private static final Pattern METADATA_ESCAPE_PATTERN
      = Pattern.compile("_x([0-9a-f]{4})_");

  private static final String SITE_COLLECTION_ADMIN_FRAGMENT = "admin";

  private static final Logger log
      = Logger.getLogger(SharePointAdaptor.class.getName());

  /**
   * Map from Site or Web URL to SiteAdaptor object used to communicate with
   * that Site/Web.
   */
  private final ConcurrentMap<String, SiteAdaptor> siteAdaptors
      = new ConcurrentSkipListMap<String, SiteAdaptor>();
  private final DocId virtualServerDocId = new DocId("");
  private AdaptorContext context;
  /**
   * The URL of the top-level Virtual Server that we use to bootstrap our
   * SP instance knowledge.
   */
  private String virtualServer;
  /**
   * Cache that provides immutable {@link MemberIdMapping} instances for the
   * provided site URL key. Since {@code MemberIdMapping} is immutable, updating
   * the cache creates new mapping instances that replace the previous value.
   */
  private LoadingCache<String, MemberIdMapping> memberIdsCache
      = CacheBuilder.newBuilder()
        .refreshAfterWrite(30, TimeUnit.MINUTES)
        .expireAfterWrite(45, TimeUnit.MINUTES)
        .build(new MemberIdsCacheLoader());
  private LoadingCache<String, MemberIdMapping> siteUserCache
      = CacheBuilder.newBuilder()
        .refreshAfterWrite(30, TimeUnit.MINUTES)
        .expireAfterWrite(45, TimeUnit.MINUTES)
        .build(new SiteUserCacheLoader());
  private RareModificationCache rareModCache;
  /** Map from Content Database GUID to last known Change Token for that DB. */
  private final ConcurrentSkipListMap<String, String> contentDatabaseChangeId
      = new ConcurrentSkipListMap<String, String>();
  private final SoapFactory soapFactory;
  /** Client for initiating raw HTTP connections. */
  private final HttpClient httpClient;
  private final Callable<ExecutorService> executorFactory;
  private ExecutorService executor;
  private boolean xmlValidation;
  private long maxIndexableSize;
  
  private ScheduledThreadPoolExecutor scheduledExecutor 
      = new ScheduledThreadPoolExecutor(1);
  private String defaultNamespace;
  /** Authenticator instance that authenticates with SP. */
  /**
   * Cached value of whether we are talking to a SP 2007 server or not. This
   * value is used in case of error in certain situations.
   */
  private boolean isSp2007;
  private NtlmAuthenticator ntlmAuthenticator;
  /**
   * Lock for refreshing MemberIdMapping. We use a unique lock because it is
   * held while waiting on I/O.
   */
  private final Object refreshMemberIdMappingLock = new Object();
  
  private FormsAuthenticationHandler authenticationHandler;
  private static final TimeZone gmt = TimeZone.getTimeZone("GMT");
  /** RFC 822 date format, as updated by RFC 1123. */
  private final ThreadLocal<DateFormat> dateFormatRfc1123
      = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
          DateFormat df = new SimpleDateFormat(
              "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
          df.setTimeZone(gmt);
          return df;
        }
      };
  private final ThreadLocal<DateFormat> modifiedDateFormat
      = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
          DateFormat df = new SimpleDateFormat(
              "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);
          df.setTimeZone(gmt);
          return df;
        }
      };

  public SharePointAdaptor() {
    this(new SoapFactoryImpl(), new HttpClientImpl(),
        new CachedThreadPoolFactory());
  }

  @VisibleForTesting
  SharePointAdaptor(SoapFactory soapFactory, HttpClient httpClient,
      Callable<ExecutorService> executorFactory) {
    if (soapFactory == null || httpClient == null || executorFactory == null) {
      throw new NullPointerException();
    }
    this.soapFactory = soapFactory;
    this.httpClient = httpClient;
    this.executorFactory = executorFactory;
  }

  /**
   * Method to cause static initialization of the class. Mainly useful to tests
   * so that the cost of initializing the class does not count toward the first
   * test case run.
   */
  @VisibleForTesting
  static void init() {}

  @Override
  public void initConfig(Config config) {
    boolean onWindows = System.getProperty("os.name").contains("Windows");
    config.addKey("sharepoint.server", null);
    // When running on Windows, Windows Authentication can log us in.
    config.addKey("sharepoint.username", onWindows ? "" : null);
    config.addKey("sharepoint.password", onWindows ? "" : null);
    // On any particular SharePoint instance, we expect that at least some
    // responses will not pass xml validation. We keep the option around to
    // allow us to improve the schema itself, but also allow enable users to
    // enable checking as a form of debugging.
    config.addKey("sharepoint.xmlValidation", "false");
    // 2 MB. We need to know how much of the generated HTML the GSA will index,
    // because the GSA won't see links outside of that content.
    config.addKey("sharepoint.maxIndexableSize", "2097152");
    config.addKey("adaptor.namespace", "Default");
  }

  @Override
  public void init(AdaptorContext context) throws Exception {
    this.context = context;
    context.setPollingIncrementalLister(this);
    Config config = context.getConfig();
    virtualServer = config.getValue("sharepoint.server");
    String username = config.getValue("sharepoint.username");
    String password = context.getSensitiveValueDecoder().decodeValue(
        config.getValue("sharepoint.password"));
    xmlValidation = Boolean.parseBoolean(
        config.getValue("sharepoint.xmlValidation"));
    maxIndexableSize = Integer.parseInt(
        config.getValue("sharepoint.maxIndexableSize"));
    defaultNamespace = config.getValue("adaptor.namespace");

    log.log(Level.CONFIG, "VirtualServer: {0}", virtualServer);
    log.log(Level.CONFIG, "Username: {0}", username);
    log.log(Level.CONFIG, "Password: {0}", password);
    log.log(Level.CONFIG, "Default Namespace: {0}", defaultNamespace);

    ntlmAuthenticator = new NtlmAuthenticator(username, password);
    // Unfortunately, this is a JVM-wide modification.
    Authenticator.setDefault(ntlmAuthenticator);
    URL virtualServerUrl = new URL(virtualServer);
    ntlmAuthenticator.addPermitForHost(virtualServerUrl);
    String authenticationEndPoint = spUrlToUri(
        virtualServer + "/_vti_bin/Authentication.asmx").toString();
    authenticationHandler = new FormsAuthenticationHandler(username,
        password, scheduledExecutor,
        soapFactory.newAuthentication(authenticationEndPoint));
    authenticationHandler.start();
    executor = executorFactory.call();
    try {
      SiteAdaptor vsAdaptor = getSiteAdaptor(virtualServer, virtualServer);
      SiteDataClient virtualServerSiteDataClient =
          vsAdaptor.getSiteDataClient();
      rareModCache
          = new RareModificationCache(virtualServerSiteDataClient, executor);

      // Test out configuration.
      VirtualServer vs = virtualServerSiteDataClient.getContentVirtualServer();
      String version = vs.getMetadata().getVersion();
      log.log(Level.INFO, "SharePoint Version : {0}", version);
      // Version is missing for SP 2007 (but its version is 12).
      // Version for SP2010 is 14. Version for SP2013 is 15.
      isSp2007 = (version == null);
      log.log(Level.FINE, "isSP2007 : {0}", isSp2007);
      
      // Loop through all host-named site collections and add them to
      // whitelist for authenticator.
      for (ContentDatabases.ContentDatabase cdcd : 
          vs.getContentDatabases().getContentDatabase()) {
        ContentDatabase cd;
        try {
          cd = virtualServerSiteDataClient.getContentContentDatabase(
              cdcd.getID(), true);
        } catch (IOException ex) {
          log.log(Level.WARNING, "Failed to get sites for database {0}",
              cdcd.getID());
          continue;
        }
        if (cd.getSites() == null) {
          continue;
        }
        for (Sites.Site siteListing : cd.getSites().getSite()) {
          String siteString
              = vsAdaptor.encodeDocId(siteListing.getURL()).getUniqueId();
          ntlmAuthenticator.addPermitForHost(spUrlToUri(siteString).toURL());
        }
      }      
    } catch (Exception e) {
      // Don't leak the executor.
      destroy();
      throw e;
    }
  }

  @Override
  public void destroy() {
    executor.shutdown();
    scheduledExecutor.shutdown();
    try {
      executor.awaitTermination(10, TimeUnit.SECONDS);
      scheduledExecutor.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    
    executor.shutdownNow();
    scheduledExecutor.shutdownNow();
    executor = null;
    scheduledExecutor = null;
    rareModCache = null;
    Authenticator.setDefault(null);
    ntlmAuthenticator = null;
  }

  @Override
  public void getDocContent(Request request, Response response)
      throws IOException {
    log.entering("SharePointAdaptor", "getDocContent",
        new Object[] {request, response});
    DocId id = request.getDocId();
     if (id.equals(virtualServerDocId)) {
      SiteAdaptor virtualServerSiteAdaptor
          = getSiteAdaptor(virtualServer, virtualServer);
      virtualServerSiteAdaptor.getVirtualServerDocContent(request, response);
    } else {
      SiteAdaptor rootSiteAdaptor
          = getRootAdaptorForUrl(spUrlToUri(id.getUniqueId()));
      if (rootSiteAdaptor == null) {
        log.log(Level.FINE, "responding not found");
        response.respondNotFound();
        log.exiting("SharePointAdaptor", "getDocContent");
        return;
      }
      SiteAdaptor siteAdaptor
          = rootSiteAdaptor.getAdaptorForUrl(id.getUniqueId());
      if (siteAdaptor == null) {
        log.log(Level.FINE, "responding not found");
        response.respondNotFound();
        log.exiting("SharePointAdaptor", "getDocContent");
        return;
      }
      siteAdaptor.getDocContent(request, response);
    }
    log.exiting("SharePointAdaptor", "getDocContent");
  }

  @Override
  public void getDocIds(DocIdPusher pusher) throws InterruptedException,
      IOException {
    log.entering("SharePointAdaptor", "getDocIds", pusher);
    pusher.pushDocIds(Arrays.asList(virtualServerDocId));

    SiteAdaptor vsAdaptor = getSiteAdaptor(virtualServer, virtualServer);
    SiteDataClient vsClient = vsAdaptor.getSiteDataClient();
    VirtualServer vs = vsClient.getContentVirtualServer();
    Map<GroupPrincipal, Collection<Principal>> defs
        = new HashMap<GroupPrincipal, Collection<Principal>>();
    for (ContentDatabases.ContentDatabase cdcd
        : vs.getContentDatabases().getContentDatabase()) {
      ContentDatabase cd;
      try {
        cd = vsClient.getContentContentDatabase(cdcd.getID(), true);
      } catch (IOException ex) {
        log.log(Level.WARNING, "Failed to get local groups for database {0}",
            cdcd.getID());
        continue;
      }
      if (cd.getSites() == null) {
        continue;
      }
      for (Sites.Site siteListing : cd.getSites().getSite()) {
        String siteString
            = vsAdaptor.encodeDocId(siteListing.getURL()).getUniqueId();
        ntlmAuthenticator.addPermitForHost(spUrlToUri(siteString).toURL());           
        SiteAdaptor siteAdaptor = getSiteAdaptor(siteString, siteString);
        Site site;
        try {
          site = siteAdaptor.getSiteDataClient().getContentSite();
        } catch (IOException ex) {
          log.log(Level.WARNING, "Failed to get local groups for site {0}",
              siteString);
          continue;
        }
        defs.putAll(siteAdaptor.computeMembersForGroups(site.getGroups()));
      }
    }
    pusher.pushGroupDefinitions(defs, false);
    log.exiting("SharePointAdaptor", "getDocIds");
  }

  @Override
  public void getModifiedDocIds(DocIdPusher pusher)
      throws InterruptedException {
    log.entering("SharePointAdaptor", "getModifiedDocIds", pusher);
    SiteAdaptor siteAdaptor;
    try {
      siteAdaptor = getSiteAdaptor(virtualServer, virtualServer);
    } catch (IOException ex) {
      // The call should never fail, and it is the only IOException-throwing
      // call that we can't recover from. Handling it this way allows us to
      // remove IOException from the signature and ensure that we handle the
      // exception gracefully throughout this method.
      throw new RuntimeException(ex);
    }
    SiteDataClient client = siteAdaptor.getSiteDataClient();
    VirtualServer vs = null;
    try {
      vs = client.getContentVirtualServer();
    } catch (IOException ex) {
      log.log(Level.WARNING, "Could not retrieve list of content databases",
          ex);
    }
    Set<String> discoveredContentDatabases;
    if (vs == null) {
      // Retrieving list of databases failed, but we can continue without it.
      discoveredContentDatabases
        = new HashSet<String>(contentDatabaseChangeId.keySet());
    } else {
      discoveredContentDatabases = new HashSet<String>();
      if (vs.getContentDatabases() != null) {
        for (ContentDatabases.ContentDatabase cd
            : vs.getContentDatabases().getContentDatabase()) {
          discoveredContentDatabases.add(cd.getID());
        }
      }
    }
    Set<String> knownContentDatabases
        = new HashSet<String>(contentDatabaseChangeId.keySet());
    Set<String> removedContentDatabases
        = new HashSet<String>(knownContentDatabases);
    removedContentDatabases.removeAll(discoveredContentDatabases);
    Set<String> newContentDatabases
        = new HashSet<String>(discoveredContentDatabases);
    newContentDatabases.removeAll(knownContentDatabases);
    Set<String> updatedContentDatabases
        = new HashSet<String>(knownContentDatabases);
    updatedContentDatabases.retainAll(discoveredContentDatabases);
    if (!removedContentDatabases.isEmpty()
        || !newContentDatabases.isEmpty()) {
      DocIdPusher.Record record
          = new DocIdPusher.Record.Builder(virtualServerDocId)
          .setCrawlImmediately(true).build();
      pusher.pushRecords(Collections.singleton(record));
    }
    for (String contentDatabase : removedContentDatabases) {
      contentDatabaseChangeId.remove(contentDatabase);
    }
    for (String contentDatabase : newContentDatabases) {
      ContentDatabase cd;
      try {
        cd = client.getContentContentDatabase(contentDatabase, false);
      } catch (IOException ex) {
        log.log(Level.WARNING, "Could not retrieve change id for content "
            + "database: " + contentDatabase, ex);
        // Continue processing. Hope that next time works better.
        continue;
      }
      String changeId = cd.getMetadata().getChangeId();
      contentDatabaseChangeId.put(contentDatabase, changeId);
    }
    for (String contentDatabase : updatedContentDatabases) {
      String changeId = contentDatabaseChangeId.get(contentDatabase);
      if (changeId == null) {
        // The item was removed from contentDatabaseChangeId, so apparently
        // this database is gone.
        continue;
      }
      CursorPaginator<SPContentDatabase, String> changesPaginator
          = client.getChangesContentDatabase(contentDatabase, changeId,
              isSp2007);
      Set<DocId> docIds = new HashSet<DocId>();
      Set<String> updatedSiteSecurity = new HashSet<String>();
      try {
        while (true) {
          try {
            SPContentDatabase changes = changesPaginator.next();
            if (changes == null) {
              break;
            }
            getModifiedDocIdsContentDatabase(changes, docIds,
                updatedSiteSecurity);
          } catch (XmlProcessingException ex) {
            log.log(Level.WARNING, "Error parsing changes from content "
                + "database: " + contentDatabase, ex);
            // The cursor is guaranteed to be advanced past the position that
            // failed parsing, so we just ignore the failure and continue
            // looping.
          }
          contentDatabaseChangeId.put(contentDatabase,
              changesPaginator.getCursor());
        }
      } catch (IOException ex) {
        log.log(Level.WARNING, "Error getting changes from content database: "
            + contentDatabase, ex);
        // Continue processing. Hope that next time works better.
      }
      List<DocIdPusher.Record> records
          = new ArrayList<DocIdPusher.Record>(docIds.size());
      DocIdPusher.Record.Builder builder
          = new DocIdPusher.Record.Builder(new DocId("to-be-replaced-name"))
          .setCrawlImmediately(true);
      for (DocId docId : docIds) {
        records.add(builder.setDocId(docId).build());
      }
      pusher.pushRecords(records);
      if (updatedSiteSecurity.isEmpty()) {
        continue;
      }
      Map<GroupPrincipal, Collection<Principal>> groupDefs
          = new HashMap<GroupPrincipal, Collection<Principal>>();
      for (String siteUrl : updatedSiteSecurity) {
        Site site;
        try {
          site = getSiteAdaptor(siteUrl, siteUrl).getSiteDataClient()
              .getContentSite();
        } catch (IOException ex) {
          log.log(Level.WARNING, "Failed to get local groups for site {0}",
              siteUrl);
          continue;
        }
        groupDefs.putAll(siteAdaptor.computeMembersForGroups(site.getGroups()));
      }
      pusher.pushGroupDefinitions(groupDefs, false);
    }
    log.exiting("SharePointAdaptor", "getModifiedDocIds", pusher);
  }

  @VisibleForTesting
  void getModifiedDocIdsContentDatabase(SPContentDatabase changes,
      Collection<DocId> docIds,
      Collection<String> updatedSiteSecurity) throws IOException {
    log.entering("SharePointAdaptor", "getModifiedDocIdsContentDatabase",
        new Object[] {changes, docIds});
    if (!"Unchanged".equals(changes.getChange())) {
      docIds.add(virtualServerDocId);
    }
    for (SPSite site : changes.getSPSite()) {       
      getModifiedDocIdsSite(site, docIds, updatedSiteSecurity);
    }
    log.exiting("SharePointAdaptor", "getModifiedDocIdsContentDatabase");
  }

  private void getModifiedDocIdsSite(SPSite changes, Collection<DocId> docIds,
      Collection<String> updatedSiteSecurity) throws IOException {
    log.entering("SharePointAdaptor", "getModifiedDocIdsSite",
        new Object[] {changes, docIds});
    if (isModified(changes.getChange())) {
      String siteUrl = changes.getServerUrl() + changes.getDisplayUrl();
      if (siteUrl.endsWith("/")) {
        siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
      }
      docIds.add(new DocId(siteUrl));
      // Add modified site to whitelist for authenticator as this might be new
      // host name site collection.
      ntlmAuthenticator.addPermitForHost(spUrlToUri(siteUrl).toURL());
      if ("UpdateSecurity".equals(changes.getChange())) {
        updatedSiteSecurity.add(siteUrl);
      }
    }
    for (SPWeb web : changes.getSPWeb()) {
      getModifiedDocIdsWeb(web, docIds);
    }
    log.exiting("SharePointAdaptor", "getModifiedDocIdsSite");
  }

  private void getModifiedDocIdsWeb(SPWeb changes, Collection<DocId> docIds) {
    log.entering("SharePointAdaptor", "getModifiedDocIdsWeb",
        new Object[] {changes, docIds});
    if (isModified(changes.getChange())) {
      String webUrl = changes.getServerUrl() + changes.getDisplayUrl();
      if (webUrl.endsWith("/")) {
        webUrl = webUrl.substring(0, webUrl.length() - 1);
      }
      docIds.add(new DocId(webUrl));
    }
    
    for (Object choice : changes.getSPFolderOrSPListOrSPFile()) {      
      if (choice instanceof SPList) {
        getModifiedDocIdsList((SPList) choice, docIds);
      }
    }
    log.exiting("SharePointAdaptor", "getModifiedDocIdsWeb");
  }

  private void getModifiedDocIdsList(SPList changes,
      Collection<DocId> docIds) {
    log.entering("SharePointAdaptor", "getModifiedDocIdsList",
        new Object[] {changes, docIds});
    if (isModified(changes.getChange())) {
      String listUrl = changes.getServerUrl() + changes.getDisplayUrl();
      docIds.add(new DocId(listUrl));
    }
    for (Object choice : changes.getSPViewOrSPListItem()) {
      // Ignore view change detection.

      if (choice instanceof SPListItem) {
        getModifiedDocIdsListItem((SPListItem) choice, docIds);
      }
    }
    log.exiting("SharePointAdaptor", "getModifiedDocIdsList");
  }

  private void getModifiedDocIdsListItem(SPListItem changes,
      Collection<DocId> docIds) {
    log.entering("SharePointAdaptor", "getModifiedDocIdsListItem",
        new Object[] {changes, docIds});
    if (isModified(changes.getChange())) {
      Object oData = changes.getListItem().getAny();
      if (!(oData instanceof Element)) {
        log.log(Level.WARNING, "Unexpected object type for data: {0}",
            oData.getClass());
      } else {
        Element data = (Element) oData;
        String serverUrl = data.getAttribute(OWS_SERVERURL_ATTRIBUTE);        
        if (serverUrl == null) {
          log.log(Level.WARNING, "Could not find server url attribute for "
              + "list item {0}", changes.getId());
        } else {
          String url = changes.getServerUrl() + serverUrl;
          docIds.add(new DocId(url));
        }
      }
    }
    log.exiting("SharePointAdaptor", "getModifiedDocIdsListItem");
  }

  private boolean isModified(String change) {
    return !"Unchanged".equals(change) && !"Delete".equals(change);
  }

  private SiteAdaptor getSiteAdaptor(String site, String web)
      throws IOException {
    if (web.endsWith("/")) {
      // Always end without a '/' for a canonical form.
      web = web.substring(0, web.length() - 1);
    }
    SiteAdaptor siteAdaptor = siteAdaptors.get(web);
    if (siteAdaptor == null) {
      if (site.endsWith("/")) {
        // Always end without a '/' for a canonical form.
        site = site.substring(0, site.length() - 1);
      }
      ntlmAuthenticator.addPermitForHost(new URL(web));
      String endpoint = spUrlToUri(web + "/_vti_bin/SiteData.asmx").toString();
      SiteDataSoap siteDataSoap = soapFactory.newSiteData(endpoint);
      
      String endpointUserGroup = spUrlToUri(site + "/_vti_bin/UserGroup.asmx")
          .toString();
      UserGroupSoap userGroupSoap = soapFactory.newUserGroup(endpointUserGroup);
      String endpointPeople = spUrlToUri(site + "/_vti_bin/People.asmx")
          .toString();
      PeopleSoap peopleSoap = soapFactory.newPeople(endpointPeople);
      // JAX-WS RT 2.1.4 doesn't handle headers correctly and always assumes the
      // list contains precisely one entry, so we work around it here.
      if (authenticationHandler.isFormsAuthentication()) {
        addFormsAuthenticationCookies((BindingProvider) siteDataSoap);
        addFormsAuthenticationCookies((BindingProvider) userGroupSoap);
        addFormsAuthenticationCookies((BindingProvider) peopleSoap);
      }
      siteAdaptor = new SiteAdaptor(site, web, siteDataSoap, userGroupSoap,
          peopleSoap, new MemberIdMappingCallable(site),
          new SiteUserIdMappingCallable(site));
      siteAdaptors.putIfAbsent(web, siteAdaptor);
      siteAdaptor = siteAdaptors.get(web);
    }
    return siteAdaptor;
  }
  
  private void addFormsAuthenticationCookies(BindingProvider port) {
    if (authenticationHandler.getAuthenticationCookies().isEmpty()) {
      // JAX-WS RT 2.1.4 doesn't handle headers correctly and always assumes the
      // list contains precisely one entry, so we work around it here.
      disableFormsAuthentication(port);    
      return;
    }
    port.getRequestContext().put(MessageContext.HTTP_REQUEST_HEADERS,
        Collections.singletonMap("Cookie", 
            authenticationHandler.getAuthenticationCookies()));
  }
  
  private void disableFormsAuthentication(BindingProvider port) {
    port.getRequestContext().put(MessageContext.HTTP_REQUEST_HEADERS, 
        Collections.singletonMap("X-FORMS_BASED_AUTH_ACCEPTED", 
          Collections.singletonList("f")));
  }

  static URI spUrlToUri(String url) throws IOException {
    // Because SP is silly, the path of the URI is unencoded, but the rest of
    // the URI is correct. Thus, we split up the path from the host, and then
    // turn them into URIs separately, and then turn everything into a
    // properly-escaped string.
    String[] parts = url.split("/", 4);
    if (parts.length < 3) {
      throw new IllegalArgumentException("Too few '/'s: " + url);
    }
    String host = parts[0] + "/" + parts[1] + "/" + parts[2];
    // Host must be properly-encoded already.
    URI hostUri = URI.create(host);
    if (parts.length == 3) {
      // There was no path.
      return hostUri;
    }
    URI pathUri;
    try {
      pathUri = new URI(null, null, "/" + parts[3], null);
    } catch (URISyntaxException ex) {
      throw new IOException(ex);
    }
    return hostUri.resolve(pathUri);
  }

  /**
   * SharePoint encodes special characters as _x????_ where the ? are hex
   * digits. Each such encoding is a UTF-16 character. For example, _x0020_ is
   * space and _xFFE5_ is the fullwidth yen sign.
   */
  @VisibleForTesting
  static String decodeMetadataName(String name) {
    Matcher m = METADATA_ESCAPE_PATTERN.matcher(name);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      char c = (char) Integer.parseInt(m.group(1), 16);
      m.appendReplacement(sb, "" + c);
    }
    m.appendTail(sb);
    return sb.toString();
  }

  public static void main(String[] args) {
    AbstractAdaptor.main(new SharePointAdaptor(), args);
  }

  private SiteAdaptor getRootAdaptorForUrl(URI uri) throws IOException {
    if (!ntlmAuthenticator.isPermittedHost(uri.toURL())) {
      log.log(Level.WARNING, "URL {0} not white listed", uri);
      return null;
    }
    String rootUrl;
    try {
       rootUrl = getRootUrl(uri);
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
    return getSiteAdaptor(rootUrl, rootUrl);
  }
  
  private String getRootUrl(URI uri) throws URISyntaxException {
    return new URI(
        uri.getScheme(), uri.getAuthority(), null, null, null).toString();
  }

  @VisibleForTesting
  class SiteAdaptor {
    private final SiteDataClient siteDataClient;
    private final UserGroupSoap userGroup;
    private final PeopleSoap people;
    private final String siteUrl;
    private final String webUrl;
    private final DocId siteDocId;
    /**
     * Callable for accessing an up-to-date instance of {@link MemberIdMapping}.
     * Using a callable instead of accessing {@link #memberIdsCache} directly as
     * this allows mocking out the cache during testing.
     */
    private final Callable<MemberIdMapping> memberIdMappingCallable;
    private final Callable<MemberIdMapping> siteUserIdMappingCallable;

    public SiteAdaptor(String site, String web, SiteDataSoap siteDataSoap,
        UserGroupSoap userGroupSoap, PeopleSoap people,
        Callable<MemberIdMapping> memberIdMappingCallable,
        Callable<MemberIdMapping> siteUserIdMappingCallable) {
      log.entering("SiteAdaptor", "SiteAdaptor",
          new Object[] {site, web, siteDataSoap});
      if (site.endsWith("/")) {
        throw new AssertionError();
      }
      if (web.endsWith("/")) {
        throw new AssertionError();
      }
      if (memberIdMappingCallable == null) {
        throw new NullPointerException();
      }
      this.siteUrl = site;
      this.siteDocId = new DocId(site);
      this.webUrl = web;
      this.userGroup = userGroupSoap;
      this.people = people;
      this.siteDataClient = new SiteDataClient(siteDataSoap, xmlValidation);
      this.memberIdMappingCallable = memberIdMappingCallable;
      this.siteUserIdMappingCallable = siteUserIdMappingCallable;
      log.exiting("SiteAdaptor", "SiteAdaptor");
    }

    private MemberIdMapping getMemberIdMapping() throws IOException {
      try {
        return memberIdMappingCallable.call();
      } catch (IOException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new IOException(ex);
      }
    }

    /**
     * Provide a more recent MemberIdMapping than {@code mapping}, because the
     * mapping is known to be out-of-date.
     */
    private MemberIdMapping refreshMemberIdMapping(MemberIdMapping mapping)
        throws IOException {
      // Synchronize callers to prevent a rush of invalidations due to multiple
      // callers noticing that the map was out of date at the same time.
      synchronized (refreshMemberIdMappingLock) {
        // NOTE: This may block on I/O, so we must be wary of what locks are
        // held.
        MemberIdMapping maybeNewMapping = getMemberIdMapping();
        if (mapping != maybeNewMapping) {
          // The map has already been refreshed.
          return maybeNewMapping;
        }
        memberIdsCache.invalidate(siteUrl);
      }
      return getMemberIdMapping();
    }

     private MemberIdMapping getSiteUserMapping() throws IOException {
      try {
        return siteUserIdMappingCallable.call();
      } catch (IOException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new IOException(ex);
      }
    }

    public void getDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteAdaptor", "getDocContent",
          new Object[] {request, response});
      String url = request.getDocId().getUniqueId();
      if (getAttachmentDocContent(request, response)) {
        // Success, it was an attachment.
        log.exiting("SiteAdaptor", "getDocContent");
        return;
      }

      Holder<String> listId = new Holder<String>();
      Holder<String> itemId = new Holder<String>();
      // No need to retrieve webId, since it isn't populated when you contact a
      // web's SiteData.asmx page instead of its parent site's.
      boolean result = siteDataClient.getUrlSegments(
          request.getDocId().getUniqueId(), listId, itemId);
      if (!result) {
        // It may still be an aspx page.
        if (request.getDocId().getUniqueId().toLowerCase(Locale.ENGLISH)
            .endsWith(".aspx")) {
          getAspxDocContent(request, response);
        } else {
          log.log(Level.FINE, "responding not found");
          response.respondNotFound();
        }
        log.exiting("SiteAdaptor", "getDocContent");
        return;
      }
      if (itemId.value != null) {
        getListItemDocContent(request, response, listId.value, itemId.value);
      } else if (listId.value != null) {
        getListDocContent(request, response, listId.value);
      } else {
        // Assume it is a top-level site.
        getSiteDocContent(request, response);
      }
      log.exiting("SiteAdaptor", "getDocContent");
    }

    private DocId encodeDocId(String url) {
      log.entering("SiteAdaptor", "encodeDocId", url);
      if (url.toLowerCase().startsWith("https://")
          || url.toLowerCase().startsWith("http://")) {
        // Leave as-is.
      } else if (!url.startsWith("/")) {
        url = webUrl + "/" + url;
      } else {
        // Rip off everthing after the third slash (including the slash).
        // Get http://example.com from http://example.com/some/folder.
        String[] parts = webUrl.split("/", 4);
        url = parts[0] + "//" + parts[2] + url;
      }
      DocId docId = new DocId(url);
      log.exiting("SiteAdaptor", "encodeDocId", docId);
      return docId;
    }

    private URI docIdToUri(DocId docId) throws IOException {
      return spUrlToUri(docId.getUniqueId());
    }

    /**
     * Handles converting from relative paths to fully qualified URIs and
     * dealing with SharePoint's lack of encoding paths (spaces in SP are kept
     * as spaces in URLs, instead of becoming %20).
     */
    private URI sharePointUrlToUri(String path) throws IOException {
      return docIdToUri(encodeDocId(path));
    }

    private void getVirtualServerDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteAdaptor", "getVirtualServerDocContent",
          new Object[] {request, response});
      VirtualServer vs = siteDataClient.getContentVirtualServer();

      final long necessaryPermissionMask = LIST_ITEM_MASK;
      List<Principal> permits = new ArrayList<Principal>();
      List<Principal> denies = new ArrayList<Principal>();

      // A PolicyUser is either a user or group, but we aren't provided with
      // which. We make a web service call to determine which. When using claims
      // is enabled, we actually do know the type, but we need additional
      // information to produce a clear ACL. As such, we blindly get more info
      // for all the PolicyUsers at once in a single batch.
      Map<String, PrincipalInfo> resolvedPolicyUsers;
      {
        List<String> policyUsers = new ArrayList<String>();
        for (PolicyUser policyUser : vs.getPolicies().getPolicyUser()) {
          policyUsers.add(policyUser.getLoginName());
        }
        resolvedPolicyUsers = resolvePrincipals(policyUsers);
      }

      for (PolicyUser policyUser : vs.getPolicies().getPolicyUser()) {
        String loginName = policyUser.getLoginName();
        PrincipalInfo p = resolvedPolicyUsers.get(loginName);
        if (p == null || !p.isIsResolved()) {
          log.log(Level.WARNING, 
              "Unable to resolve Policy User = {0}", loginName);
          continue;
        }
        // TODO(ejona): special case NT AUTHORITY\LOCAL SERVICE.
        if (p.getPrincipalType() != SPPrincipalType.SECURITY_GROUP
            && p.getPrincipalType() != SPPrincipalType.USER) {
          log.log(Level.WARNING, "Principal {0} is an unexpected type: {1}",
              new Object[] {p.getAccountName(), p.getPrincipalType()});
          continue;
        }
        boolean isGroup
            = p.getPrincipalType() == SPPrincipalType.SECURITY_GROUP;
        String accountName = decodeClaim(p.getAccountName(), p.getDisplayName(),
            isGroup);
        if (accountName == null) {
          log.log(Level.WARNING, 
              "Unable to decode claim. Skipping policy user {0}", loginName);
          continue;
        }
        log.log(Level.FINER, "Policy User accountName = {0}", accountName);
        Principal principal;
        if (isGroup) {
          principal = new GroupPrincipal(accountName, defaultNamespace);
        } else {
          principal = new UserPrincipal(accountName, defaultNamespace);
        }
        long grant = policyUser.getGrantMask().longValue();
        if ((necessaryPermissionMask & grant) == necessaryPermissionMask) {
          permits.add(principal);
        }
        long deny = policyUser.getDenyMask().longValue();
        // If at least one necessary bit is masked, then deny user.
        if ((necessaryPermissionMask & deny) != 0) {
          denies.add(principal);
        }
      }
      response.setAcl(new Acl.Builder()
          .setEverythingCaseInsensitive()
          .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
          .setPermits(permits).setDenies(denies).build());
      
      response.addMetadata(METADATA_OBJECT_TYPE,
          ObjectType.VIRTUAL_SERVER.value());

      HtmlResponseWriter writer = createHtmlResponseWriter(response);
      writer.start(request.getDocId(), ObjectType.VIRTUAL_SERVER,
          vs.getMetadata().getURL());

      writer.startSection(ObjectType.SITE);
      DocIdEncoder encoder = context.getDocIdEncoder();
      for (ContentDatabases.ContentDatabase cdcd
          : vs.getContentDatabases().getContentDatabase()) {
        ContentDatabase cd
            = siteDataClient.getContentContentDatabase(cdcd.getID(), true);
        if (cd.getSites() != null) {
          for (Sites.Site site : cd.getSites().getSite()) {
            writer.addLink(encodeDocId(site.getURL()), null);
          }
        }
      }
      writer.finish();
      log.exiting("SiteAdaptor", "getVirtualServerDocContent");
    }

    /**
     * Returns the url of the parent of the web. The parent url is not the same
     * as the siteUrl, since there may be multiple levels of webs. It is an
     * error to call this method when there is no parent, which is the case iff
     * {@link #isWebSiteCollection} is {@code true}.
     */
    private String getWebParentUrl() {
      if (isWebSiteCollection()) {
        throw new IllegalStateException();
      }
      int slashIndex = webUrl.lastIndexOf("/");
      return webUrl.substring(0, slashIndex);
    }

    /** Returns true if webUrl is a site collection. */
    private boolean isWebSiteCollection() {
      return siteUrl.equals(webUrl);
    }

    /**
     * Returns {@code true} if the current web should not be indexed. This
     * method may issue a request for the web content for all parent webs, so it
     * is expensive, although it uses cached responses to reduce cost.
     */
    private boolean isWebNoIndex(CachedWeb w) throws IOException {
      if ("True".equals(w.noIndex)) {
        return true;
      }
      if (isWebSiteCollection()) {
        return false;
      }
      SiteAdaptor siteAdaptor = getSiteAdaptor(siteUrl, getWebParentUrl());
      return siteAdaptor.isWebNoIndex(
          rareModCache.getWeb(siteAdaptor.siteDataClient));
    }

    private void getSiteDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteAdaptor", "getSiteDocContent",
          new Object[] {request, response});
      Web w = siteDataClient.getContentWeb();

      if (isWebNoIndex(new CachedWeb(w))) {
        log.fine("Document marked for NoIndex");
        response.respondNotFound();
        log.exiting("SiteAdaptor", "getSiteDocContent");
        return;
      }

      if (webUrl.endsWith("/")) {
        throw new AssertionError();
      }

      if (isWebSiteCollection()) {
        Collection<Principal> admins = new LinkedList<Principal>();
        for (UserDescription user : w.getUsers().getUser()) {
          if (user.getIsSiteAdmin() != TrueFalseType.TRUE) {
            continue;
          }
          Principal principal = userDescriptionToPrincipal(user);
          if (principal == null) {
            log.log(Level.WARNING,
                "Unable to determine login name. Skipping admin user with ID "
                + "{0}", user.getID());
            continue;
          }
          admins.add(principal);
        }
        response.putNamedResource(SITE_COLLECTION_ADMIN_FRAGMENT,
            new Acl.Builder()
              .setEverythingCaseInsensitive()
              .setPermits(admins)
              .setInheritFrom(virtualServerDocId)
              .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
              .build());
      }

      boolean allowAnonymousAccess
          = isAllowAnonymousReadForWeb(new CachedWeb(w))
          // Check if anonymous access is denied by web application policy
          && !isDenyAnonymousAccessOnVirtualServer(
              rareModCache.getVirtualServer());

      if (!allowAnonymousAccess) {
        final boolean includePermissions;
        if (isWebSiteCollection()) {
          includePermissions = true;
        } else {
          SiteAdaptor parentSiteAdaptor
              = getSiteAdaptor(siteUrl, getWebParentUrl());
          Web parentW = parentSiteAdaptor.siteDataClient.getContentWeb();
          String parentScopeId
              = parentW.getMetadata().getScopeID().toLowerCase(Locale.ENGLISH);
          String scopeId
              = w.getMetadata().getScopeID().toLowerCase(Locale.ENGLISH);
          includePermissions = !scopeId.equals(parentScopeId);
        }
        Acl.Builder acl;
        if (includePermissions) {
          List<Permission> permissions
              = w.getACL().getPermissions().getPermission();
          acl = generateAcl(permissions, LIST_ITEM_MASK)
              .setInheritFrom(siteDocId, SITE_COLLECTION_ADMIN_FRAGMENT);
        } else {
          acl = new Acl.Builder().setInheritFrom(new DocId(getWebParentUrl()));
        }
        response.setAcl(acl
            .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
            .build());
      }
      
      response.addMetadata(METADATA_OBJECT_TYPE, ObjectType.SITE.value());
      response.addMetadata(METADATA_PARENT_WEB_TITLE,
          w.getMetadata().getTitle());      

      response.setDisplayUrl(spUrlToUri(w.getMetadata().getURL()));
      HtmlResponseWriter writer = createHtmlResponseWriter(response);
      writer.start(request.getDocId(), ObjectType.SITE,
          w.getMetadata().getTitle());

      DocIdEncoder encoder = context.getDocIdEncoder();
      if (w.getWebs() != null) {
        writer.startSection(ObjectType.SITE);
        for (Webs.Web web : w.getWebs().getWeb()) {
          writer.addLink(encodeDocId(web.getURL()), web.getURL());
        }
      }
      if (w.getLists() != null) {
        writer.startSection(ObjectType.LIST);
        for (Lists.List list : w.getLists().getList()) {
          writer.addLink(encodeDocId(list.getDefaultViewUrl()),
              list.getDefaultViewUrl());
        }
      }
      if (w.getFPFolder() != null) {
        FolderData f = w.getFPFolder();
        if (!f.getFolders().isEmpty()) {
          writer.startSection(ObjectType.FOLDER);
          for (Folders folders : f.getFolders()) {
            if (folders.getFolder() != null) {
              for (Folders.Folder folder : folders.getFolder()) {
                // Lists is always present in the listing but never exists.
                if ("Lists".equals(folder.getURL())) {
                  continue;
                }
                writer.addLink(encodeDocId(folder.getURL()), null);
              }
            }
          }
        }
        if (!f.getFiles().isEmpty()) {
          writer.startSection(ObjectType.LIST_ITEM);
          for (Files files : f.getFiles()) {
            if (files.getFile() != null) {
              for (Files.File file : files.getFile()) {
                writer.addLink(encodeDocId(file.getURL()), null);
              }
            }
          }
        }
      }
      writer.finish();
      log.exiting("SiteAdaptor", "getSiteDocContent");
    }

    private void getListDocContent(Request request, Response response,
        String id) throws IOException {
      log.entering("SiteAdaptor", "getListDocContent",
          new Object[] {request, response, id});
      com.microsoft.schemas.sharepoint.soap.List l
          = siteDataClient.getContentList(id);
      Web w = siteDataClient.getContentWeb();

      if (TrueFalseType.TRUE.equals(l.getMetadata().getNoIndex())
          || isWebNoIndex(new CachedWeb(w))) {
        log.fine("Document marked for NoIndex");
        response.respondNotFound();
        log.exiting("SiteAdaptor", "getListDocContent");
        return;
      }

      boolean allowAnonymousAccess
          = isAllowAnonymousReadForList(new CachedList(l))
          && isAllowAnonymousPeekForWeb(new CachedWeb(w))
          && !isDenyAnonymousAccessOnVirtualServer(
              rareModCache.getVirtualServer());

      if (!allowAnonymousAccess) {
        String scopeId
            = l.getMetadata().getScopeID().toLowerCase(Locale.ENGLISH);
        String webScopeId
            = w.getMetadata().getScopeID().toLowerCase(Locale.ENGLISH);

        Acl.Builder acl;
        if (scopeId.equals(webScopeId)) {
          acl = new Acl.Builder().setInheritFrom(new DocId(webUrl));
        } else {
          List<Permission> permissions
              = l.getACL().getPermissions().getPermission();
          acl = generateAcl(permissions, LIST_ITEM_MASK)
              .setInheritFrom(siteDocId, SITE_COLLECTION_ADMIN_FRAGMENT);
        }
        response.setAcl(acl
            .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
            .build());
      }
      
      response.addMetadata(METADATA_OBJECT_TYPE,
          ObjectType.LIST.value());
      response.addMetadata(METADATA_PARENT_WEB_TITLE,
          w.getMetadata().getTitle());
      response.addMetadata(METADATA_LIST_GUID, l.getMetadata().getID());

      response.setDisplayUrl(sharePointUrlToUri(
          l.getMetadata().getDefaultViewUrl()));
      HtmlResponseWriter writer = createHtmlResponseWriter(response);
      writer.start(request.getDocId(), ObjectType.LIST,
          l.getMetadata().getTitle());
      processFolder(id, "", writer);
      writer.finish();
      log.exiting("SiteAdaptor", "getListDocContent");
    }

    /**
     * {@code writer} should already have had {@link HtmlResponseWriter#start}
     * called.
     */
    private void processFolder(String listGuid, String folderPath,
        HtmlResponseWriter writer) throws IOException {
      log.entering("SiteAdaptor", "processFolder",
          new Object[] {listGuid, folderPath, writer});
      Paginator<ItemData> folderPaginator
          = siteDataClient.getContentFolderChildren(listGuid, folderPath);
      writer.startSection(ObjectType.LIST_ITEM);
      ItemData folder;
      while ((folder = folderPaginator.next()) != null) {
        Xml xml = folder.getXml();

        Element data = getFirstChildWithName(xml, DATA_ELEMENT);
        for (Element row : getChildrenWithName(data, ROW_ELEMENT)) {
          String rowUrl = row.getAttribute(OWS_SERVERURL_ATTRIBUTE);
          String rowTitle = row.getAttribute(OWS_TITLE_ATTRIBUTE);
          writer.addLink(encodeDocId(rowUrl), rowTitle);
        }
      }
      log.exiting("SiteAdaptor", "processFolder");
    }

    private boolean elementHasName(Element ele, QName name) {
      return name.getLocalPart().equals(ele.getLocalName())
          && name.getNamespaceURI().equals(ele.getNamespaceURI());
    }

    private Element getFirstChildWithName(Xml xml, QName name) {
      for (Object oChild : xml.getAny()) {
        if (!(oChild instanceof Element)) {
          continue;
        }
        Element child = (Element) oChild;
        if (elementHasName(child, name)) {
          return child;
        }
      }
      return null;
    }

    private <T> T getFirstChildOfType(Xml xml, Class<T> type) {
      for (Object oChild : xml.getAny()) {
        if (!type.isInstance(oChild)) {
          continue;
        }
        return type.cast(oChild);
      }
      return null;
    }

    private List<Element> getChildrenWithName(Element ele, QName name) {
      List<Element> l = new ArrayList<Element>();
      NodeList nl = ele.getChildNodes();
      for (int i = 0; i < nl.getLength(); i++) {
        Node n = nl.item(i);
        if (!(n instanceof Element)) {
          continue;
        }
        Element child = (Element) n;
        if (elementHasName(child, name)) {
          l.add(child);
        }
      }
      return l;
    }

    private List<Attr> getAllAttributes(Element ele) {
      NamedNodeMap map = ele.getAttributes();
      List<Attr> attrs = new ArrayList<Attr>(map.getLength());
      for (int i = 0; i < map.getLength(); i++) {
        attrs.add((Attr) map.item(i));
      }
      return attrs;
    }

    private long addMetadata(Response response, String name, String value) {
      long size = 0;
      if ("ows_MetaInfo".equals(name)) {
        // ows_MetaInfo is parsed out into other fields for us by SharePoint.
        // We filter it since it only duplicates those other fields.
        return 0;
      }
      if (name.startsWith("ows_")) {
        name = name.substring("ows_".length());
      }
      name = decodeMetadataName(name);
      if (ALTERNATIVE_VALUE_PATTERN.matcher(value).find()) {
        // This is a lookup field. We need to take alternative values only.
        // Ignore the integer part. 314;#pi;#42;#the answer
        String[] parts = value.split(";#");
        for (int i = 1; i < parts.length; i += 2) {
          if (parts[i].isEmpty()) {
            continue;
          }
          response.addMetadata(name, parts[i]);
          // +30 for per-metadata-possible overhead, just to make sure that we
          // don't count too few.
          size += name.length() + parts[i].length() + 30;
        }
      } else if (value.startsWith(";#") && value.endsWith(";#")) {
        // This is a multi-choice field. Values will be in the form:
        // ;#value1;#value2;#
        for (String part : value.split(";#")) {
          if (part.isEmpty()) {
            continue;
          }
          response.addMetadata(name, part);
          // +30 for per-metadata-possible overhead, just to make sure that we
          // don't count too few.
          size += name.length() + part.length() + 30;
        }
      } else {
        response.addMetadata(name, value);
        // +30 for per-metadata-possible overhead, just to make sure that we
        // don't count too few.
        size += name.length() + value.length() + 30;
      }
      return size;
    }

    private Acl.Builder generateAcl(List<Permission> permissions,
        final long necessaryPermissionMask) throws IOException {
      List<Principal> permits = new LinkedList<Principal>();
      MemberIdMapping mapping = getMemberIdMapping();
      MemberIdMapping newMapping = null;
      for (Permission permission : permissions) {
        // Although it is named "mask", this is really a bit-field of
        // permissions.
        long mask = permission.getMask().longValue();
        if ((necessaryPermissionMask & mask) != necessaryPermissionMask) {
          continue;
        }
        Integer id = permission.getMemberid();
        Principal principal = mapping.getPrincipal(id);
        if (principal == null) {
          if (newMapping == null) {
            newMapping = refreshMemberIdMapping(mapping);
          }
          principal = newMapping.getPrincipal(id);
        }
        if (principal == null) {
          log.log(Level.WARNING, "Could not resolve member id {0}", id);
          continue;
        }
        permits.add(principal);
      }
      return new Acl.Builder().setEverythingCaseInsensitive()
          .setPermits(permits);
    }

    private void addPermitUserToAcl(int userId, Acl.Builder aclToUpdate)
        throws IOException {
      if (userId == -1) {
        return;
      }
      Principal principal = getMemberIdMapping().getPrincipal(userId);
      // MemberIdMapping will have information about users with explicit
      // permissions on SharePoint or users which are direct members of
      // SharePoint groups. MemberIdMapping might not have information
      // about all valid SharePoint Users. To get all valid SharePoint users
      // under SiteCollection, use SiteUserMapping.
      if (principal == null) {
        principal = getSiteUserMapping().getPrincipal(userId);
      }
      if (principal == null) {
        log.log(Level.WARNING, "Could not resolve user id {0}", userId);
        return;
      }

      List<Principal> permits
          = new LinkedList<Principal>(aclToUpdate.build().getPermits());
      permits.add(principal);
      aclToUpdate.setPermits(permits);
    }

    private boolean isPermitted(long permission,
        long necessaryPermission) {
      return (necessaryPermission & permission) == necessaryPermission;
    }

    private boolean isAllowAnonymousPeekForWeb(CachedWeb w) {
      return isPermitted(w.anonymousPermMask, OPEN_MASK);
    }

    private boolean isAllowAnonymousReadForWeb(CachedWeb w) {
      boolean allowAnonymousRead
          = (w.allowAnonymousAccess == TrueFalseType.TRUE)
          && (w.anonymousViewListItems == TrueFalseType.TRUE)
          && isPermitted(w.anonymousPermMask, LIST_ITEM_MASK);
      return allowAnonymousRead;
    }

    private boolean isAllowAnonymousReadForList(CachedList l) {
      boolean allowAnonymousRead
          = (l.readSecurity != LIST_READ_SECURITY_ENABLED)
          && (l.allowAnonymousAccess == TrueFalseType.TRUE)
          && (l.anonymousViewListItems == TrueFalseType.TRUE)
          && isPermitted(l.anonymousPermMask, VIEW_LIST_ITEMS_MASK);
      return allowAnonymousRead;
    }

    private boolean isDenyAnonymousAccessOnVirtualServer(
        CachedVirtualServer vs) {
      if ((LIST_ITEM_MASK & vs.anonymousDenyMask) != 0) {
        return true;
      }
      // Anonymous access is denied if deny read policy is specified for any
      // user or group.
      return vs.policyContainsDeny;
    }

    private void getAspxDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteAdaptor", "getAspxDocContent",
          new Object[] {request, response});

      CachedWeb w = rareModCache.getWeb(siteDataClient);
      if (isWebNoIndex(w)) {
        log.fine("Document marked for NoIndex");
        response.respondNotFound();
        log.exiting("SiteAdaptor", "getAspxDocContent");
        return;
      }

      boolean allowAnonymousAccess
          = isAllowAnonymousReadForWeb(w)
          // Check if anonymous access is denied by web application policy
          && !isDenyAnonymousAccessOnVirtualServer(
              rareModCache.getVirtualServer());
      if (!allowAnonymousAccess) {
        String aspxId = request.getDocId().getUniqueId();
        String parentId = aspxId.substring(0, aspxId.lastIndexOf('/'));
        response.setAcl(new Acl.Builder()
            .setInheritFrom(new DocId(parentId))
            .build());
      }
      response.addMetadata(METADATA_OBJECT_TYPE, "Aspx");      
      response.addMetadata(METADATA_PARENT_WEB_TITLE, w.webTitle);
      getFileDocContent(request, response, true);
      log.exiting("SiteAdaptor", "getAspxDocContent");
    }

    /**
     * Blindly retrieve contents of DocId as if it were a file's URL. To prevent
     * security issues, this should only be used after the DocId has been
     * verified to be a valid document on the SharePoint instance. In addition,
     * ACLs and other metadata and security measures should be set before making
     * this call.
     */
    private void getFileDocContent(Request request, Response response,
        boolean setLastModified) throws IOException {
      log.entering("SiteAdaptor", "getFileDocContent",
          new Object[] {request, response});
      URI displayUrl = docIdToUri(request.getDocId());
      FileInfo fi = httpClient.issueGetRequest(displayUrl.toURL(),
          authenticationHandler.getAuthenticationCookies());
      if (fi == null) {
        response.respondNotFound();
        return;
      }
      try {
        response.setDisplayUrl(displayUrl);
        String contentType = fi.getFirstHeaderWithName("Content-Type");
        if (contentType != null) {
          response.setContentType(contentType);
        }
        String lastModifiedString = fi.getFirstHeaderWithName("Last-Modified");
        if (lastModifiedString != null && setLastModified) {
          try {
            response.setLastModified(
                dateFormatRfc1123.get().parse(lastModifiedString));
          } catch (ParseException ex) {
            log.log(Level.INFO, "Could not parse Last-Modified: {0}",
                lastModifiedString);
          }
        }
        IOHelper.copyStream(fi.getContents(), response.getOutputStream());
      } finally {
        fi.getContents().close();
      }
      log.exiting("SiteAdaptor", "getFileDocContent");
    }

    private void getListItemDocContent(Request request, Response response,
        String listId, String itemId) throws IOException {
      log.entering("SiteAdaptor", "getListItemDocContent",
          new Object[] {request, response, listId, itemId});
      CachedList l = rareModCache.getList(siteDataClient, listId);

      CachedWeb w = rareModCache.getWeb(siteDataClient);
      if (TrueFalseType.TRUE.equals(l.noIndex) || isWebNoIndex(w)) {
        log.fine("Document marked for NoIndex");
        response.respondNotFound();
        log.exiting("SiteAdaptor", "getListItemDocContent");
        return;
      }

      boolean applyReadSecurity =
          (l.readSecurity == LIST_READ_SECURITY_ENABLED);
      ItemData i = siteDataClient.getContentItem(listId, itemId);

      Xml xml = i.getXml();
      Element data = getFirstChildWithName(xml, DATA_ELEMENT);
      Element row = getChildrenWithName(data, ROW_ELEMENT).get(0);

      String modifiedString = row.getAttribute(OWS_LAST_MODIFIED_ATTRIBUTE);
      if (modifiedString == null) {
        log.log(Level.FINE, "No last modified information for list item");
      } else {
        // This should be in the form of "1234;#DATE".
        modifiedString = modifiedString.split(";#", 2)[1];
        try {
          response.setLastModified(
              modifiedDateFormat.get().parse(modifiedString));
        } catch (ParseException ex) {
          log.log(Level.INFO, "Could not parse ows_Modified: {0}",
              modifiedString);
        }
      }

      // This should be in the form of "1234;#{GUID}". We want to extract the
      // {GUID}.
      String scopeId
          = row.getAttribute(OWS_SCOPEID_ATTRIBUTE).split(";#", 2)[1];
      scopeId = scopeId.toLowerCase(Locale.ENGLISH);

      // Anonymous access is disabled if read security is applicable for list.
      // Anonymous access for list items is disabled if it does not inherit
      // its effective permissions from list.

      // Even if anonymous access is enabled on list, it can be turned off
      // on Web level by setting Anonymous access to "Nothing" on Web.
      // Anonymous User must have minimum "Open" permission on Web
      // for anonymous access to work on List and List Items.
      boolean allowAnonymousAccess = isAllowAnonymousReadForList(l)
          && scopeId.equals(l.scopeId.toLowerCase(Locale.ENGLISH))
          && isAllowAnonymousPeekForWeb(w)
          && !isDenyAnonymousAccessOnVirtualServer(
              rareModCache.getVirtualServer());

      if (!allowAnonymousAccess) {
      Acl.Builder acl = null;
      if (!applyReadSecurity) {
        String rawFileDirRef = row.getAttribute(OWS_FILEDIRREF_ATTRIBUTE);
        // This should be in the form of "1234;#site/list/path". We want to
        // extract the site/list/path. Path relative to host, even though it
        // doesn't have a leading '/'.
        DocId folderDocId = encodeDocId("/" + rawFileDirRef.split(";#")[1]);
        DocId rootFolderDocId = encodeDocId(l.rootFolder);
        DocId listDocId = encodeDocId(l.defaultViewUrl);
        // If the parent is the List, we must use the list's docId instead of
        // folderDocId, since the root folder is a List and not actually a
        // Folder.
        boolean parentIsList = folderDocId.equals(rootFolderDocId);
        DocId parentDocId = parentIsList ? listDocId : folderDocId;
        String parentScopeId;
        if (parentIsList) {
          com.microsoft.schemas.sharepoint.soap.List list
              = siteDataClient.getContentList(listId);
          parentScopeId
              = list.getMetadata().getScopeID().toLowerCase(Locale.ENGLISH);
        } else {
          // Instead of using getUrlSegments and getContent(ListItem), we could
          // use just getContent(Folder). However, getContent(Folder) always
          // returns children which could make the call very expensive. In
          // addition, getContent(ListItem) returns all the metadata for the
          // folder instead of just its scope so if in the future we need more
          // metadata we will already have it. GetContentEx(Folder) may provide
          // a way to get the folder's scope without its children, but it wasn't
          // investigated.
          Holder<String> folderListId = new Holder<String>();
          Holder<String> folderItemId = new Holder<String>();
          boolean result = siteDataClient.getUrlSegments(
              folderDocId.getUniqueId(), folderListId, folderItemId);
          if (!result) {
            throw new IOException("Could not find parent folder's itemId");
          }
          if (!listId.equals(folderListId.value)) {
            throw new AssertionError("Unexpected listId value");
          }
          ItemData folderItem
              = siteDataClient.getContentItem(listId, folderItemId.value);
          Element folderData = getFirstChildWithName(
              folderItem.getXml(), DATA_ELEMENT);
          Element folderRow
              = getChildrenWithName(folderData, ROW_ELEMENT).get(0);
          parentScopeId = folderRow.getAttribute(OWS_SCOPEID_ATTRIBUTE)
              .split(";#", 2)[1].toLowerCase(Locale.ENGLISH);
        }
        if (scopeId.equals(parentScopeId)) {
          acl = new Acl.Builder().setInheritFrom(parentDocId);
        } else {
          // We have to search for the correct scope within the scopes element.
          // The scope provided in the metadata is for the parent list, not for
          // the item
          Scopes scopes = getFirstChildOfType(xml, Scopes.class);
          for (Scopes.Scope scope : scopes.getScope()) {
            if (scope.getId().toLowerCase(Locale.ENGLISH).equals(scopeId)) {
              acl = generateAcl(scope.getPermission(), LIST_ITEM_MASK)
                  .setInheritFrom(siteDocId, SITE_COLLECTION_ADMIN_FRAGMENT);
              break;
            }
          }
        }

        if (acl == null) {
          throw new IOException("Unable to find permission scope for item: "
              + request.getDocId());
        }
      } else {
        final String fragmentName = "readSecurity";
        List<Permission> permission = null;
        Scopes scopes = getFirstChildOfType(xml, Scopes.class);
        for (Scopes.Scope scope : scopes.getScope()) {
          if (scope.getId().toLowerCase(Locale.ENGLISH).equals(scopeId)) {
            permission = scope.getPermission();
            break;
          }
        }
        if (permission == null) {
          permission
              = i.getMetadata().getScope().getPermissions().getPermission();
        }
        acl = generateAcl(permission, LIST_ITEM_MASK)
            .setInheritFrom(request.getDocId(), fragmentName);
        int authorId = -1;
        String authorValue = row.getAttribute(OWS_AUTHOR_ATTRIBUTE);
        if (authorValue != null) {
          String[] authorInfo = authorValue.split(";#", 2);
          if (authorInfo.length == 2) {
            authorId = Integer.parseInt(authorInfo[0]);
          }
        }
        Acl.Builder aclNamedResource
            = generateAcl(permission, READ_SECURITY_LIST_ITEM_MASK)
            .setInheritFrom(siteDocId, SITE_COLLECTION_ADMIN_FRAGMENT)
            .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT);
        addPermitUserToAcl(authorId, aclNamedResource);
        response.putNamedResource(fragmentName, aclNamedResource.build());
      }
      response.setAcl(acl
          .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
          .build());
      }

      // This should be in the form of "1234;#0". We want to extract the 0.
      String type = row.getAttribute(OWS_FSOBJTYPE_ATTRIBUTE).split(";#", 2)[1];
      boolean isFolder = "1".equals(type);
      String title = row.getAttribute(OWS_TITLE_ATTRIBUTE);
      String serverUrl = row.getAttribute(OWS_SERVERURL_ATTRIBUTE);

      long metadataLength = 0;
      for (Attr attribute : getAllAttributes(row)) {
        metadataLength
            += addMetadata(response, attribute.getName(), attribute.getValue());
      }
      metadataLength += addMetadata(response,
          METADATA_PARENT_WEB_TITLE, w.webTitle);
      metadataLength += addMetadata(response, METADATA_LIST_GUID, listId); 
      
      if (isFolder) {
        String root = encodeDocId(l.rootFolder).getUniqueId();
        root += "/";
        String folder = encodeDocId(serverUrl).getUniqueId();
        if (!folder.startsWith(root)) {
          throw new AssertionError();
        }
        URI displayPage = sharePointUrlToUri(l.defaultViewUrl);
        if (serverUrl.contains("&") || serverUrl.contains("=")
            || serverUrl.contains("%")) {
          throw new AssertionError();
        }
        try {
          // SharePoint percent-encodes '/'s in serverUrl, but accepts them
          // encoded or unencoded. We leave them unencoded for simplicity of
          // implementation and to not deal with the possibility of
          // double-encoding.
          response.setDisplayUrl(new URI(displayPage.getScheme(),
              displayPage.getAuthority(), displayPage.getPath(),
              "RootFolder=" + serverUrl, null));
        } catch (URISyntaxException ex) {
          throw new IOException(ex);
        }
        metadataLength += addMetadata(
            response, METADATA_OBJECT_TYPE, ObjectType.FOLDER.value());
        HtmlResponseWriter writer
            = createHtmlResponseWriter(response, metadataLength);
        writer.start(request.getDocId(), ObjectType.FOLDER, null);
        processAttachments(listId, itemId, row, writer);
        processFolder(listId, folder.substring(root.length()), writer);
        writer.finish();
        log.exiting("SiteAdaptor", "getListItemDocContent");
        return;
      }
      String contentTypeId = row.getAttribute(OWS_CONTENTTYPEID_ATTRIBUTE);
      if (contentTypeId != null
          && contentTypeId.startsWith(CONTENTTYPEID_DOCUMENT_PREFIX)) {
        // This is a file (or "Document" in SharePoint-speak), so display its
        // contents.
        metadataLength += addMetadata(
            response, METADATA_OBJECT_TYPE, "Document");
        getFileDocContent(request, response, false);
      } else {
        // Some list item.
        URI displayPage = sharePointUrlToUri(l.defaultViewItemUrl);
        try {
          response.setDisplayUrl(new URI(displayPage.getScheme(),
              displayPage.getAuthority(), displayPage.getPath(),
              "ID=" + itemId, null));
        } catch (URISyntaxException ex) {
          throw new IOException(ex);
        }
        metadataLength += addMetadata(
            response, METADATA_OBJECT_TYPE, ObjectType.LIST_ITEM.value());
        HtmlResponseWriter writer
            = createHtmlResponseWriter(response, metadataLength);
        writer.start(request.getDocId(), ObjectType.LIST_ITEM, title);
        processAttachments(listId, itemId, row, writer);
        writer.finish();
      }
      log.exiting("SiteAdaptor", "getListItemDocContent");
    }

    private void processAttachments(String listId, String itemId, Element row,
        HtmlResponseWriter writer) throws IOException {
      String strAttachments = row.getAttribute(OWS_ATTACHMENTS_ATTRIBUTE);
      int attachments = (strAttachments == null || "".equals(strAttachments))
          ? 0 : Integer.parseInt(strAttachments);
      if (attachments > 0) {
        writer.startSection(ObjectType.LIST_ITEM_ATTACHMENTS);
        Item item
            = siteDataClient.getContentListItemAttachments(listId, itemId);
        for (Item.Attachment attachment : item.getAttachment()) {
          writer.addLink(encodeDocId(attachment.getURL()), null);
        }
      }
    }

    private boolean getAttachmentDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteAdaptor", "getAttachmentDocContent", new Object[] {
          request, response});
      String url = request.getDocId().getUniqueId();
      if (!url.contains("/Attachments/")) {
        log.fine("Not an attachment: does not contain /Attachments/");
        log.exiting("SiteAdaptor", "getAttachmentDocContent", false);
        return false;
      }
      String[] parts = url.split("/Attachments/", 2);
      String listUrl = parts[0] + "/AllItems.aspx";
      parts = parts[1].split("/", 2);
      if (parts.length != 2) {
        log.fine("Could not separate attachment file name and list item id");
        log.exiting("SiteAdaptor", "getAttachmentDocContent", false);
        return false;
      }
      String itemId = parts[0];
      log.log(Level.FINE, "Detected possible attachment: "
          + "listUrl={0}, itemId={1}", new Object[] {listUrl, itemId});
      Holder<String> listIdHolder = new Holder<String>();
      boolean result
          = siteDataClient.getUrlSegments(listUrl, listIdHolder, null);
      if (!result) {
        log.fine("Could not get list id from list url");
        log.exiting("SiteAdaptor", "getAttachmentDocContent", false);
        return false;
      }
      String listId = listIdHolder.value;
      if (listId == null) {
        log.fine("List URL does not point to a list");
        log.exiting("SiteAdaptor", "getAttachmentDocContent", false);
        return false;
      }
      // We have verified that the part before /Attachments/ is a List. Since
      // lists can't have "Attachments" as a child folder, we are very certain
      // that if the document exists it is an attachment.
      log.fine("Suspected attachment verified as being an attachment, assuming "
          + "it exists.");
      CachedList l = rareModCache.getList(siteDataClient, listId);
      CachedWeb w = rareModCache.getWeb(siteDataClient);
      if (TrueFalseType.TRUE.equals(l.noIndex) || isWebNoIndex(w)) {
        log.fine("Document marked for NoIndex");
        response.respondNotFound();
        log.exiting("SiteAdaptor", "getAttachmentDocContent", true);
        return true;
      }
      // TODO(ejona): Figure out a way to give a Not Found if the itemId is
      // wrong. getContentItem() will throw an exception if the itemId does not
      // exist.
      ItemData itemData = siteDataClient.getContentItem(listId, itemId);
      Xml xml = itemData.getXml();
      Element data = getFirstChildWithName(xml, DATA_ELEMENT);
      Element row = getChildrenWithName(data, ROW_ELEMENT).get(0);
      String scopeId
          = row.getAttribute(OWS_SCOPEID_ATTRIBUTE).split(";#", 2)[1];
      scopeId = scopeId.toLowerCase(Locale.ENGLISH);

      boolean allowAnonymousAccess = isAllowAnonymousReadForList(l)
          && scopeId.equals(l.scopeId.toLowerCase(Locale.ENGLISH))
          && isAllowAnonymousPeekForWeb(w)
          && !isDenyAnonymousAccessOnVirtualServer(
              rareModCache.getVirtualServer());
      if (!allowAnonymousAccess) {
        String listItemUrl = row.getAttribute(OWS_SERVERURL_ATTRIBUTE);
        response.setAcl(new Acl.Builder()
            .setInheritFrom(encodeDocId(listItemUrl))
            .build());
      }
      response.addMetadata(METADATA_OBJECT_TYPE, "Attachment");
      response.addMetadata(METADATA_PARENT_WEB_TITLE, w.webTitle);
      response.addMetadata(METADATA_LIST_GUID, listId);
      // If the attachment doesn't exist, then this responds Not Found.
      getFileDocContent(request, response, true);
      log.exiting("SiteAdaptor", "getAttachmentDocContent", true);
      return true;
    }

    private String decodeClaim(String loginName, String name
        , boolean isDomainGroup) {
      if (!loginName.startsWith(IDENTITY_CLAIMS_PREFIX)
          && !loginName.startsWith(OTHER_CLAIMS_PREFIX)) {
        return loginName;
      }
      // AD User
      if (loginName.startsWith("i:0#.w|")) {
        return loginName.substring(7);
      // AD Group
      } else if (loginName.startsWith("c:0+.w|")) {
        return name;
      } else if (loginName.equals("c:0(.s|true")) {
        return "Everyone";
      } else if (loginName.equals("c:0!.s|windows")) {
        return "NT AUTHORITY\\authenticated users";
      // Forms authentication role  
      } else if (loginName.startsWith("c:0-.f|")) {
        return loginName.substring(7).replace("|", ":");
      // Forms authentication user  
      } else if (loginName.startsWith("i:0#.f|")) {
        return loginName.substring(7).replace("|", ":");
      }
      log.log(Level.WARNING, "Unsupported claims value {0}", loginName);
      return null;
    }

    private Map<String, PrincipalInfo> resolvePrincipals(
        List<String> principalsToResolve) {
      Map<String, PrincipalInfo> resolved
          = new HashMap<String, PrincipalInfo>();
      if (principalsToResolve.isEmpty()) {
        return resolved;
      }
      ArrayOfString aos = new ArrayOfString();
      aos.getString().addAll(principalsToResolve);
      ArrayOfPrincipalInfo resolvePrincipals = people.resolvePrincipals(
          aos, SPPrincipalType.ALL, false);
      List<PrincipalInfo> principals = resolvePrincipals.getPrincipalInfo();
      // using loginname from input list principalsToResolve as a key
      // instead of returned PrincipalInfo.getAccountName() as with claims
      // authentication PrincipalInfo.getAccountName() is always encoded.
      // e.g. if login name from Policy is NT Authority\Local Service
      // returned account name is i:0#.w|NT Authority\Local Service
      for (int i = 0; i < principalsToResolve.size(); i++) {
         resolved.put(principalsToResolve.get(i), principals.get(i));
      }
      return resolved;
    }

    private MemberIdMapping retrieveMemberIdMapping() throws IOException {
      log.entering("SiteAdaptor", "retrieveMemberIdMapping");
      Site site = siteDataClient.getContentSite();
      Map<Integer, Principal> map = new HashMap<Integer, Principal>();
      for (GroupMembership.Group group : site.getGroups().getGroup()) {
        map.put(group.getGroup().getID(), new GroupPrincipal(
            group.getGroup().getName(),
            defaultNamespace + "_" + site.getMetadata().getURL()));
      }
      for (UserDescription user : site.getWeb().getUsers().getUser()) {
        Principal principal = userDescriptionToPrincipal(user);
        if (principal == null) {
          log.log(Level.WARNING,
              "Unable to determine login name. Skipping user with ID {0}",
              user.getID());
          continue;
        }
        map.put(user.getID(), principal);
      }
      MemberIdMapping mapping = new MemberIdMapping(map);
      log.exiting("SiteAdaptor", "retrieveMemberIdMapping", mapping);
      return mapping;
    }

    private Map<GroupPrincipal, Collection<Principal>> computeMembersForGroups(
        GroupMembership groups) {
      Map<GroupPrincipal, Collection<Principal>> defs
          = new HashMap<GroupPrincipal, Collection<Principal>>();
      for (GroupMembership.Group group : groups.getGroup()) {
        GroupPrincipal groupPrincipal = new GroupPrincipal(
            group.getGroup().getName(), defaultNamespace + "_" + siteUrl);
        Collection<Principal> members = new LinkedList<Principal>();
        // We always provide membership details, even for empty groups.
        defs.put(groupPrincipal, members);
        if (group.getUsers() == null) {
          continue;
        }
        for (UserDescription user : group.getUsers().getUser()) {
          Principal principal = userDescriptionToPrincipal(user);
          if (principal == null) {
            log.log(Level.WARNING,
                "Unable to determine login name. Skipping user with ID {0}",
                user.getID());
            continue;
          }
          members.add(principal);
        }
      }
      return defs;
    }

    private Principal userDescriptionToPrincipal(UserDescription user) {
      boolean isDomainGroup = (user.getIsDomainGroup() == TrueFalseType.TRUE);
      String userName
          = decodeClaim(user.getLoginName(), user.getName(), isDomainGroup);
      if (userName == null) {
        return null;
      }
      if (isDomainGroup) {
        return new GroupPrincipal(userName, defaultNamespace);
      } else {
        return new UserPrincipal(userName, defaultNamespace);
      }
    }

    private MemberIdMapping retrieveSiteUserMapping()
        throws IOException {
      log.entering("SiteAdaptor", "retrieveSiteUserMapping");
      GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult result
          = userGroup.getUserCollectionFromSite();
      Map<Integer, Principal> map = new HashMap<Integer, Principal>();
      MemberIdMapping mapping;
      if (result == null) {
        mapping = new MemberIdMapping(map);
        log.exiting("SiteAdaptor", "retrieveSiteUserMapping", mapping);
        return mapping;
      }
      GetUserCollectionFromSiteResult.GetUserCollectionFromSite siteUsers
           = result.getGetUserCollectionFromSite();
      if (siteUsers.getUsers() == null) {
        mapping = new MemberIdMapping(map);
        log.exiting("SiteAdaptor", "retrieveSiteUserMapping", mapping);
        return mapping;
      }
      for (User user : siteUsers.getUsers().getUser()) {
        boolean isDomainGroup = (user.getIsDomainGroup()
            == com.microsoft.schemas.sharepoint.soap.directory.TrueFalseType.TRUE);
        String userName =
            decodeClaim(user.getLoginName(), user.getName(), isDomainGroup);

        if (userName == null) {
          log.log(Level.WARNING,
              "Unable to determine login name. Skipping user with ID {0}",
              user.getID());
          continue;
        }
        map.put((int) user.getID(),
            new UserPrincipal(userName, defaultNamespace));
      }
      mapping = new MemberIdMapping(map);
      log.exiting("SiteAdaptor", "retrieveSiteUserMapping", mapping);
      return mapping;
    }

    private SiteAdaptor getAdaptorForUrl(String url) throws IOException {
      log.entering("SiteAdaptor", "getAdaptorForUrl", url);
      Holder<String> site = new Holder<String>();
      Holder<String> web = new Holder<String>();
      long result = siteDataClient.getSiteAndWeb(url, site, web);

      if (result != 0) {
        log.exiting("SiteAdaptor", "getAdaptorForUrl", null);
        return null;
      }
      SiteAdaptor siteAdaptor = getSiteAdaptor(site.value, web.value);
      log.exiting("SiteAdaptor", "getAdaptorForUrl", siteAdaptor);
      return siteAdaptor;
    }

    private HtmlResponseWriter createHtmlResponseWriter(Response response)
        throws IOException {
      return createHtmlResponseWriter(response, 0);
    }

    private HtmlResponseWriter createHtmlResponseWriter(
        Response response, long metadataLength) throws IOException {
      response.setContentType("text/html; charset=utf-8");
      // TODO(ejona): Get locale from request.
      return new HtmlResponseWriter(response.getOutputStream(), CHARSET,
          context.getDocIdEncoder(), Locale.ENGLISH,
          maxIndexableSize - metadataLength, context.getDocIdPusher(),
          executor);
    }

    public SiteDataClient getSiteDataClient() {
      return siteDataClient;
    }
  }

  @VisibleForTesting
  static class FileInfo {
    /** Non-null contents. */
    private final InputStream contents;
    /** Non-null headers. Alternates between header name and header value. */
    private final List<String> headers;

    private FileInfo(InputStream contents, List<String> headers) {
      this.contents = contents;
      this.headers = headers;
    }

    public InputStream getContents() {
      return contents;
    }

    public List<String> getHeaders() {
      return headers;
    }

    public int getHeaderCount() {
      return headers.size() / 2;
    }

    public String getHeaderName(int i) {
      return headers.get(2 * i);
    }

    public String getHeaderValue(int i) {
      return headers.get(2 * i + 1);
    }

    /**
     * Find the first header with {@code name}, ignoring case.
     */
    public String getFirstHeaderWithName(String name) {
      String nameLowerCase = name.toLowerCase(Locale.ENGLISH);
      for (int i = 0; i < getHeaderCount(); i++) {
        String headerNameLowerCase
            = getHeaderName(i).toLowerCase(Locale.ENGLISH);
        if (headerNameLowerCase.equals(nameLowerCase)) {
          return getHeaderValue(i);
        }
      }
      return null;
    }

    public static class Builder {
      private InputStream contents;
      private List<String> headers = Collections.emptyList();

      public Builder(InputStream contents) {
        setContents(contents);
      }

      public Builder setContents(InputStream contents) {
        if (contents == null) {
          throw new NullPointerException();
        }
        this.contents = contents;
        return this;
      }

      /**
       * Sets the headers recieved as a response. List must alternate between
       * header name and header value.
       */
      public Builder setHeaders(List<String> headers) {
        if (headers == null) {
          throw new NullPointerException();
        }
        if (headers.size() % 2 != 0) {
          throw new IllegalArgumentException(
              "headers must have an even number of elements");
        }
        this.headers = Collections.unmodifiableList(
            new ArrayList<String>(headers));
        return this;
      }

      public FileInfo build() {
        return new FileInfo(contents, headers);
      }
    }
  }

  @VisibleForTesting
  interface HttpClient {
    /**
     * The caller must call {@code fileInfo.getContents().close()} after use.
     *
     * @return {@code null} if not found, {@code FileInfo} instance otherwise
     */
    public FileInfo issueGetRequest(URL url, List<String> authenticationCookies)
        throws IOException;
  }

  static class HttpClientImpl implements HttpClient {
    @Override
    public FileInfo issueGetRequest(URL url, List<String> authenticationCookies)
        throws IOException {
      // Handle Unicode. Java does not properly encode the GET.
      try {
        url = new URL(url.toURI().toASCIIString());
      } catch (URISyntaxException ex) {
        throw new IOException(ex);
      }
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
     
      if (authenticationCookies.isEmpty()) {
        conn.addRequestProperty("X-FORMS_BASED_AUTH_ACCEPTED", "f");
      } else {
        for (String cookie : authenticationCookies) {
          conn.addRequestProperty("Cookie", cookie);
        }
      }
      conn.setDoInput(true);
      conn.setDoOutput(false);
      if (conn.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
        return null;
      }
      if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new IOException("Got status code: " + conn.getResponseCode());
      }
      List<String> headers = new LinkedList<String>();
      // Start at 1 since index 0 is special.
      for (int i = 1;; i++) {
        String key = conn.getHeaderFieldKey(i);
        if (key == null) {
          break;
        }
        String value = conn.getHeaderField(i);
        headers.add(key);
        headers.add(value);
      }
      log.log(Level.FINER, "Response HTTP headers: {0}", headers);
      return new FileInfo.Builder(conn.getInputStream()).setHeaders(headers)
          .build();
    }
  }

  @VisibleForTesting
  interface SoapFactory {
    /**
     * The {@code endpoint} string is a SharePoint URL, meaning that spaces are
     * not encoded.
     */
    public SiteDataSoap newSiteData(String endpoint);

    public UserGroupSoap newUserGroup(String endpoint);
    
    public AuthenticationSoap newAuthentication(String endpoint);
    
    public PeopleSoap newPeople(String endpoint);
  }

  @VisibleForTesting
  static class SoapFactoryImpl implements SoapFactory {
    private final Service siteDataService;
    private final Service userGroupService;
    private final Service authenticationService;
    private final Service peopleService;

    public SoapFactoryImpl() {
      this.siteDataService = SiteDataClient.createSiteDataService();
      this.userGroupService = Service.create(
          UserGroupSoap.class.getResource("UserGroup.wsdl"),
          new QName(XMLNS_DIRECTORY, "UserGroup"));
      this.authenticationService = Service.create(
          AuthenticationSoap.class.getResource("Authentication.wsdl"),
          new QName(XMLNS, "Authentication"));
      this.peopleService = Service.create(
          PeopleSoap.class.getResource("People.wsdl"),
          new QName(XMLNS, "People"));
    }

    private static String handleEncoding(String endpoint) {
      // Handle Unicode. Java does not properly encode the POST path.
      return URI.create(endpoint).toASCIIString();
    }

    @Override
    public SiteDataSoap newSiteData(String endpoint) {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(handleEncoding(endpoint)).build();
      return siteDataService.getPort(endpointRef, SiteDataSoap.class);
    }

    @Override
    public UserGroupSoap newUserGroup(String endpoint) {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(handleEncoding(endpoint)).build();
      return userGroupService.getPort(endpointRef, UserGroupSoap.class);
    }
    
    @Override
    public AuthenticationSoap newAuthentication(String endpoint) {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(handleEncoding(endpoint)).build();
      return 
          authenticationService.getPort(endpointRef, AuthenticationSoap.class);
    }

    @Override
    public PeopleSoap newPeople(String endpoint) {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(handleEncoding(endpoint)).build();
      return peopleService.getPort(endpointRef, PeopleSoap.class);      
    }
  }

  private static class NtlmAuthenticator extends Authenticator {
    private final String username;
    private final char[] password;
    private final Set<String> permittedHosts = new HashSet<String>();

    public NtlmAuthenticator(String username, String password) {
      this.username = username;
      this.password = password.toCharArray();
    }

    public void addPermitForHost(URL urlContainingHost) {
      permittedHosts.add(urlToHostString(urlContainingHost));
    }
    
    private boolean isPermittedHost(URL toVerify) {
      return permittedHosts.contains(urlToHostString(toVerify));
    }
    

    private String urlToHostString(URL url) {
      // If the port is missing (so that the default is used), we replace it
      // with the default port for the protocol in order to prevent being able
      // to prevent being tricked into connecting to a different port (consider
      // being configured for https, but then getting tricked to use http and
      // evenything being in the clear).
      return "" + url.getHost()
          + ":" + (url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
      URL url = getRequestingURL();
      if (isPermittedHost(url)) {
        return new PasswordAuthentication(username, password);
      } else {
        return super.getPasswordAuthentication();
      }
    }
  }

  private class MemberIdMappingCallable implements Callable<MemberIdMapping> {
    private final String siteUrl;

    public MemberIdMappingCallable(String siteUrl) {
      if (siteUrl == null) {
        throw new NullPointerException();
      }
      this.siteUrl = siteUrl;
    }

    @Override
    public MemberIdMapping call() throws Exception {
      try {
        return memberIdsCache.get(siteUrl);
      } catch (ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof Exception) {
          throw (Exception) cause;
        } else if (cause instanceof Error) {
          throw (Error) cause;
        } else {
          throw new AssertionError(cause);
        }
      }
    }
  }

  @VisibleForTesting
  class SiteUserIdMappingCallable implements Callable<MemberIdMapping> {
    private final String siteUrl;

    public SiteUserIdMappingCallable(String siteUrl) {
      if (siteUrl == null) {
        throw new NullPointerException();
      }
      this.siteUrl = siteUrl;
    }

    @Override
    public MemberIdMapping call() throws Exception {
      try {
        return siteUserCache.get(siteUrl);
      } catch (ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof Exception) {
          throw (Exception) cause;
        } else if (cause instanceof Error) {
          throw (Error) cause;
        } else {
          throw new AssertionError(cause);
        }
      }
    }
  }

  private class MemberIdsCacheLoader
      extends AsyncCacheLoader<String, MemberIdMapping> {
    @Override
    protected Executor executor() {
      return executor;
    }

    @Override
    public MemberIdMapping load(String site) throws IOException {
      return getSiteAdaptor(site, site).retrieveMemberIdMapping();
    }
  }

  private class SiteUserCacheLoader
      extends AsyncCacheLoader<String, MemberIdMapping> {
    @Override
    protected Executor executor() {
      return executor;
    }

    @Override
    public MemberIdMapping load(String site) throws IOException {
      return getSiteAdaptor(site, site).retrieveSiteUserMapping();
    }
  }

  private static class CachedThreadPoolFactory
      implements Callable<ExecutorService> {
    @Override
    public ExecutorService call() {
      return Executors.newCachedThreadPool();
    }
  }
}
