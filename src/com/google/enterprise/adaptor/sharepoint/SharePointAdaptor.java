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
import com.google.enterprise.adaptor.PollingIncrementalAdaptor;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.UserPrincipal;

import com.microsoft.schemas.sharepoint.soap.ContentDatabase;
import com.microsoft.schemas.sharepoint.soap.ContentDatabases;
import com.microsoft.schemas.sharepoint.soap.Files;
import com.microsoft.schemas.sharepoint.soap.FolderData;
import com.microsoft.schemas.sharepoint.soap.Folders;
import com.microsoft.schemas.sharepoint.soap.GroupDescription;
import com.microsoft.schemas.sharepoint.soap.GroupMembership;
import com.microsoft.schemas.sharepoint.soap.Item;
import com.microsoft.schemas.sharepoint.soap.ItemData;
import com.microsoft.schemas.sharepoint.soap.Lists;
import com.microsoft.schemas.sharepoint.soap.ObjectType;
import com.microsoft.schemas.sharepoint.soap.Permission;
import com.microsoft.schemas.sharepoint.soap.PolicyUser;
import com.microsoft.schemas.sharepoint.soap.SPContentDatabase;
import com.microsoft.schemas.sharepoint.soap.SPFile;
import com.microsoft.schemas.sharepoint.soap.SPFolder;
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
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromSiteResponse;
import com.microsoft.schemas.sharepoint.soap.directory.GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult;
import com.microsoft.schemas.sharepoint.soap.directory.User;
import com.microsoft.schemas.sharepoint.soap.directory.UserGroupSoap;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xml.sax.SAXException;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.ws.EndpointReference;
import javax.xml.ws.Holder;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.wsaddressing.W3CEndpointReferenceBuilder;

/**
 * SharePoint Adaptor for the GSA.
 */
public class SharePointAdaptor extends AbstractAdaptor
    implements PollingIncrementalAdaptor {
  /** Charset used in generated HTML responses. */
  private static final Charset CHARSET = Charset.forName("UTF-8");
  /** SharePoint's namespace. */
  private static final String XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";
  private static final String XMLNS_DIRECTORY
      = "http://schemas.microsoft.com/sharepoint/soap/directory/";

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
  /**
   * Matches a SP-encoded value that contains one or more values. See {@link
   * SiteDataClient.addMetadata}.
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

  private static final long LIST_ITEM_MASK
      = OPEN_MASK | VIEW_PAGES_MASK | VIEW_LIST_ITEMS_MASK;
  private static final long READ_SECURITY_LIST_ITEM_MASK
      = OPEN_MASK | VIEW_PAGES_MASK | VIEW_LIST_ITEMS_MASK | MANAGE_LIST_MASK;

  private static final int LIST_READ_SECURITY_ENABLED = 2;
  /**
   * The JAXBContext is expensive to initialize, so we share a copy between
   * instances.
   */
  private static final JAXBContext jaxbContext;
  /**
   * XML Schema of requests and responses. Used to validate responses match
   * expectations.
   */
  private static final Schema schema;

  static {
    try {
      jaxbContext = JAXBContext.newInstance(
          "com.microsoft.schemas.sharepoint.soap");
    } catch (JAXBException ex) {
      throw new RuntimeException("Could not initialize JAXBContext", ex);
    }

    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      Document doc = dbf.newDocumentBuilder()
          .parse(SiteDataSoap.class.getResourceAsStream("SiteData.wsdl"));
      String schemaNs = XMLConstants.W3C_XML_SCHEMA_NS_URI;
      Node schemaNode = doc.getElementsByTagNameNS(schemaNs, "schema").item(0);
      schema = SchemaFactory.newInstance(schemaNs).newSchema(
          new DOMSource(schemaNode));
    } catch (IOException ex) {
      throw new RuntimeException("Could not initialize Schema", ex);
    } catch (SAXException ex) {
      throw new RuntimeException("Could not initialize Schema", ex);
    } catch (ParserConfigurationException ex) {
      throw new RuntimeException("Could not initialize Schema", ex);
    }
  }

  private static final Logger log
      = Logger.getLogger(SharePointAdaptor.class.getName());

  /**
   * Map from Site or Web URL to SiteDataClient object used to communicate with
   * that Site/Web.
   */
  private final ConcurrentMap<String, SiteDataClient> clients
      = new ConcurrentSkipListMap<String, SiteDataClient>();
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
  /** Map from Content Database GUID to last known Change Token for that DB. */
  private final ConcurrentSkipListMap<String, String> contentDatabaseChangeId
      = new ConcurrentSkipListMap<String, String>();
  /** Production factory for all SiteDataSoap communication objects. */
  private final SiteDataFactory siteDataFactory;
  private final UserGroupFactory userGroupFactory;
  /** Client for initiating raw HTTP connections. */
  private final HttpClient httpClient;
  private final Callable<ExecutorService> executorFactory;
  private ExecutorService executor;
  private boolean xmlValidation;
  /** Authenticator instance that authenticates with SP. */
  /**
   * Cached value of whether we are talking to a SP 2010 server or not. This
   * value is used in case of error in certain situations.
   */
  private boolean isSp2010;

  public SharePointAdaptor() {
    this(new SiteDataFactoryImpl(), new UserGroupFactoryImpl(),
        new HttpClientImpl(), new CachedThreadPoolFactory());
  }

  @VisibleForTesting
  SharePointAdaptor(SiteDataFactory siteDataFactory,
      UserGroupFactory userGroupFactory, HttpClient httpClient,
      Callable<ExecutorService> executorFactory) {
    if (siteDataFactory == null || httpClient == null
        || userGroupFactory == null || executorFactory == null) {
      throw new NullPointerException();
    }
    this.siteDataFactory = siteDataFactory;
    this.userGroupFactory = userGroupFactory;
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
  }

  @Override
  public void init(AdaptorContext context) throws Exception {
    this.context = context;
    Config config = context.getConfig();
    virtualServer = config.getValue("sharepoint.server");
    String username = config.getValue("sharepoint.username");
    String password = context.getSensitiveValueDecoder().decodeValue(
        config.getValue("sharepoint.password"));
    xmlValidation = Boolean.parseBoolean(
        config.getValue("sharepoint.xmlValidation"));

    log.log(Level.CONFIG, "VirtualServer: {0}", virtualServer);
    log.log(Level.CONFIG, "Username: {0}", username);
    log.log(Level.CONFIG, "Password: {0}", password);

    URL virtualServerUrl = new URL(virtualServer);
    Authenticator ntlmAuthenticator = new NtlmAuthenticator(username, password,
        virtualServerUrl.getHost(), virtualServerUrl.getPort());
    // Unfortunately, this is a JVM-wide modification.
    Authenticator.setDefault(ntlmAuthenticator);

    executor = executorFactory.call();

    // Test out configuration.
    try {
      getSiteDataClient(virtualServer, virtualServer).getContentVirtualServer();
    } catch (Exception e) {
      // Don't leak the executor.
      destroy();
      throw e;
    }
  }

  @Override
  public void destroy() {
    Authenticator.setDefault(null);
    executor.shutdown();
    try {
      executor.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    executor.shutdownNow();
    executor = null;
  }

  @Override
  public void getDocContent(Request request, Response response)
      throws IOException {
    log.entering("SharePointAdaptor", "getDocContent",
        new Object[] {request, response});
    DocId id = request.getDocId();
    SiteDataClient virtualServerClient
        = getSiteDataClient(virtualServer, virtualServer);
    if (id.equals(virtualServerDocId)) {
      virtualServerClient.getVirtualServerDocContent(request, response);
    } else {
      SiteDataClient client
          = virtualServerClient.getClientForUrl(id.getUniqueId());
      if (client == null) {
        log.log(Level.FINE, "responding not found");
        response.respondNotFound();
        log.exiting("SharePointAdaptor", "getDocContent");
        return;
      }
      client.getDocContent(request, response);
    }
    log.exiting("SharePointAdaptor", "getDocContent");
  }

  @Override
  public void getDocIds(DocIdPusher pusher) throws InterruptedException {
    log.entering("SharePointAdaptor", "getDocIds", pusher);
    pusher.pushDocIds(Arrays.asList(virtualServerDocId));
    log.exiting("SharePointAdaptor", "getDocIds");
  }

  @Override
  public void getModifiedDocIds(DocIdPusher pusher)
      throws InterruptedException, IOException {
    log.entering("SharePointAdaptor", "getModifiedDocIds", pusher);
    SiteDataClient client = getSiteDataClient(virtualServer, virtualServer);
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
      // We don't set isSp2010 here, because we don't know what version of
      // server we are talking to. However, if isSp2010 is still its default,
      // then contentDatabaseChangeId is also its default and is empty. When
      // contentDatabaseChangeId is empty, we won't end up using isSp2010.
      discoveredContentDatabases
        = new HashSet<String>(contentDatabaseChangeId.keySet());
    } else {
      String version = vs.getMetadata().getVersion();
      // Version is missing for SP 2007 (but its version is 12). SP 2010 is 14.
      isSp2010 = version != null && version.startsWith("14.");

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
              isSp2010);
      try {
        while (true) {
          try {
            SPContentDatabase changes = changesPaginator.next();
            if (changes == null) {
              break;
            }
            client.getModifiedDocIds(changes, pusher);
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
        continue;
      }
    }
    log.exiting("SharePointAdaptor", "getModifiedDocIds", pusher);
  }

  private SiteDataClient getSiteDataClient(String site, String web)
      throws IOException {
    if (web.endsWith("/")) {
      // Always end without a '/' for a canonical form.
      web = web.substring(0, web.length() - 1);
    }
    SiteDataClient client = clients.get(web);
    if (client == null) {
      if (site.endsWith("/")) {
        // Always end without a '/' for a canonical form.
        site = site.substring(0, site.length() - 1);
      }
      String endpoint = web + "/_vti_bin/SiteData.asmx";
      SiteDataSoap siteDataSoap = siteDataFactory.newSiteData(endpoint);

      String endpointUserGroup = site + "/_vti_bin/UserGroup.asmx";
      UserGroupSoap userGroupSoap
          = userGroupFactory.newUserGroup(endpointUserGroup);

      client = new SiteDataClient(site, web, siteDataSoap, userGroupSoap,
          new MemberIdMappingCallable(site),
          new SiteUserIdMappingCallable(site));
      clients.putIfAbsent(web, client);
      client = clients.get(web);
    }
    return client;
  }

  private static URI spUrlToUri(String url) throws IOException {
    // Because SP is silly, the path of the URI is unencoded, but the rest of
    // the URI is correct. Thus, we split up the path from the host, and then
    // turn them into URIs separately, and then turn everything into a
    // properly-escaped string.
    String[] parts = url.split("/", 4);
    String host = parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
    // Host must be properly-encoded already.
    URI hostUri = URI.create(host);
    URI pathUri;
    try {
      pathUri = new URI(null, null, parts[3], null);
    } catch (URISyntaxException ex) {
      throw new IOException(ex);
    }
    return hostUri.resolve(pathUri);
  }

  public static void main(String[] args) {
    AbstractAdaptor.main(new SharePointAdaptor(), args);
  }

  @VisibleForTesting
  class SiteDataClient {
    private final CheckedExceptionSiteDataSoap siteData;
    private final UserGroupSoap userGroup;
    private final String siteUrl;
    private final String webUrl;
    /**
     * Callable for accessing an up-to-date instance of {@link MemberIdMapping}.
     * Using a callable instead of accessing {@link #memberIdsCache} directly as
     * this allows mocking out the cache during testing.
     */
    private final Callable<MemberIdMapping> memberIdMappingCallable;
    private final Callable<MemberIdMapping> siteUserIdMappingCallable;

    public SiteDataClient(String site, String web, SiteDataSoap siteDataSoap,
        UserGroupSoap userGroupSoap,
        Callable<MemberIdMapping> memberIdMappingCallable,
        Callable<MemberIdMapping> siteUserIdMappingCallable) {
      log.entering("SiteDataClient", "SiteDataClient",
          new Object[] {site, web, siteDataSoap});
      if (site.endsWith("/")) {
        throw new AssertionError();
      }
      if (web.endsWith("/")) {
        throw new AssertionError();
      }
      if (siteDataSoap == null || memberIdMappingCallable == null) {
        throw new NullPointerException();
      }
      this.siteUrl = site;
      this.webUrl = web;
      siteDataSoap = LoggingWSHandler.create(SiteDataSoap.class, siteDataSoap);
      this.userGroup = userGroupSoap;
      this.siteData = new CheckedExceptionSiteDataSoapAdapter(siteDataSoap);
      this.memberIdMappingCallable = memberIdMappingCallable;
      this.siteUserIdMappingCallable = siteUserIdMappingCallable;
      log.exiting("SiteDataClient", "SiteDataClient");
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
      log.entering("SiteDataClient", "getDocContent",
          new Object[] {request, response});
      String url = request.getDocId().getUniqueId();
      if (getAttachmentDocContent(request, response)) {
        // Success, it was an attachment.
        log.exiting("SiteDataClient", "getDocContent");
        return;
      }

      Holder<String> listId = new Holder<String>();
      Holder<String> itemId = new Holder<String>();
      Holder<Boolean> result = new Holder<Boolean>();
      // No need to retrieve webId, since it isn't populated when you contact a
      // web's SiteData.asmx page instead of its parent site's.
      siteData.getURLSegments(request.getDocId().getUniqueId(), result, null,
          null, listId, itemId);
      if (!result.value) {
        // It may still be an aspx page.
        if (request.getDocId().getUniqueId().toLowerCase(Locale.ENGLISH)
            .endsWith(".aspx")) {
          getAspxDocContent(request, response);
        } else {
          log.log(Level.FINE, "responding not found");
          response.respondNotFound();
        }
        log.exiting("SiteDataClient", "getDocContent");
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
      log.exiting("SiteDataClient", "getDocContent");
    }

    private DocId encodeDocId(String url) {
      log.entering("SiteDataClient", "encodeDocId", url);
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
      log.exiting("SiteDataClient", "encodeDocId", docId);
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
      log.entering("SiteDataClient", "getVirtualServerDocContent",
          new Object[] {request, response});
      VirtualServer vs = getContentVirtualServer();

      final long necessaryPermissionMask = LIST_ITEM_MASK;
      // A PolicyUser is either a user or group, but we aren't provided with
      // which. Thus, we treat PolicyUsers as both a user and a group in ACLs
      // and understand that only one of the two entries will have an effect.
      List<UserPrincipal> permitUsers = new ArrayList<UserPrincipal>();
      List<GroupPrincipal> permitGroups = new ArrayList<GroupPrincipal>();
      List<UserPrincipal> denyUsers = new ArrayList<UserPrincipal>();
      List<GroupPrincipal> denyGroups = new ArrayList<GroupPrincipal>();
      for (PolicyUser policyUser : vs.getPolicies().getPolicyUser()) {
        // TODO(ejona): special case NT AUTHORITY\LOCAL SERVICE.
        String loginName = policyUser.getLoginName();
        long grant = policyUser.getGrantMask().longValue();
        if ((necessaryPermissionMask & grant) == necessaryPermissionMask) {
          permitUsers.add(new UserPrincipal(loginName));
          permitGroups.add(new GroupPrincipal(loginName));
        }
        long deny = policyUser.getDenyMask().longValue();
        // If at least one necessary bit is masked, then deny user.
        if ((necessaryPermissionMask & deny) != 0) {
          denyUsers.add(new UserPrincipal(loginName));
          denyGroups.add(new GroupPrincipal(loginName));
        }
      }
      response.setAcl(new Acl.Builder()
          .setEverythingCaseInsensitive()
          .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
          .setPermitUsers(permitUsers).setPermitGroups(permitGroups)
          .setDenyUsers(denyUsers).setDenyGroups(denyGroups).build());

      response.setContentType("text/html");
      HtmlResponseWriter writer = createHtmlResponseWriter(response);
      writer.start(request.getDocId(), ObjectType.VIRTUAL_SERVER,
          vs.getMetadata().getURL());

      writer.startSection(ObjectType.SITE);
      DocIdEncoder encoder = context.getDocIdEncoder();
      for (ContentDatabases.ContentDatabase cdcd
          : vs.getContentDatabases().getContentDatabase()) {
        ContentDatabase cd = getContentContentDatabase(cdcd.getID(), true);
        if (cd.getSites() != null) {
          for (Sites.Site site : cd.getSites().getSite()) {
            writer.addLink(encodeDocId(site.getURL()), null);
          }
        }
      }
      writer.finish();
      log.exiting("SiteDataClient", "getVirtualServerDocContent");
    }

    private void getSiteDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getSiteDocContent",
          new Object[] {request, response});
      Web w = getContentWeb();

      if (webUrl.endsWith("/")) {
        throw new AssertionError();
      }
      boolean allowAnonymousAccess = isAllowAnonymousReadForWeb(w);
      // Check if anonymous access is denied by web application policy
      // only if anonymous access is enabled for web as checking web application
      // policy is additional web service call.
      // TODO(ejona): Add caching for web application policy.
      if (allowAnonymousAccess) {
        allowAnonymousAccess 
            = !isDenyAnonymousAcessOnVirtualServer(getContentVirtualServer());
      }

      if (!allowAnonymousAccess) {
        int slashIndex = webUrl.lastIndexOf("/");
        // The parentUrl is not the same as the siteUrl, since there may be
        // multiple levels of webs.
        String parentUrl = webUrl.substring(0, slashIndex);
        boolean isSiteCollection = siteUrl.equals(webUrl);
        final boolean includePermissions;
        if (isSiteCollection) {
          includePermissions = true;
        } else {
          SiteDataClient parentClient = getClientForUrl(parentUrl);
          Web parentW = parentClient.getContentWeb();
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
              .setInheritFrom(virtualServerDocId);
        } else {
          acl = new Acl.Builder().setInheritFrom(new DocId(parentUrl));
        }
        response.setAcl(acl
            .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
            .build());
      }

      response.setDisplayUrl(URI.create(w.getMetadata().getURL()));
      response.setContentType("text/html");
      HtmlResponseWriter writer = createHtmlResponseWriter(response);
      writer.start(request.getDocId(), ObjectType.SITE,
          w.getMetadata().getTitle());

      // TODO(ejona): w.getMetadata().getNoIndex()
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
      log.exiting("SiteDataClient", "getSiteDocContent");
    }

    private void getListDocContent(Request request, Response response,
        String id) throws IOException {
      log.entering("SiteDataClient", "getListDocContent",
          new Object[] {request, response, id});
      com.microsoft.schemas.sharepoint.soap.List l = getContentList(id);
      Web w = getContentWeb();

      boolean allowAnonymousAccess
          = isAllowAnonymousReadForList(l) && isAllowAnonymousPeekForWeb(w);

      if (allowAnonymousAccess) {
        allowAnonymousAccess 
            = !isDenyAnonymousAcessOnVirtualServer(getContentVirtualServer());
      }

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
              .setInheritFrom(virtualServerDocId);
        }
        response.setAcl(acl
            .setInheritanceType(Acl.InheritanceType.PARENT_OVERRIDES)
            .build());
      }

      response.setDisplayUrl(sharePointUrlToUri(
          l.getMetadata().getDefaultViewUrl()));
      response.setContentType("text/html");
      HtmlResponseWriter writer = createHtmlResponseWriter(response);
      writer.start(request.getDocId(), ObjectType.LIST,
          l.getMetadata().getTitle());
      processFolder(id, "", writer);
      writer.finish();
      log.exiting("SiteDataClient", "getListDocContent");
    }

    /**
     * {@code writer} should already have had {@link HtmlResponseWriter#start}
     * called.
     */
    private void processFolder(String listGuid, String folderPath,
        HtmlResponseWriter writer) throws IOException {
      log.entering("SiteDataClient", "processFolder",
          new Object[] {listGuid, folderPath, writer});
      Paginator<ItemData> folderPaginator
          = getContentFolderChildren(listGuid, folderPath);
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
      log.exiting("SiteDataClient", "processFolder");
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

    private void addMetadata(Response response, String name, String value) {
      if (name.startsWith("ows_")) {
        name = name.substring("ows_".length());
      }
      if (ALTERNATIVE_VALUE_PATTERN.matcher(value).find()) {
        // This is a lookup field. We need to take alternative values only.
        // Ignore the integer part. 314;#pi;#42;#the answer
        String[] parts = value.split(";#");
        for (int i = 1; i < parts.length; i += 2) {
          if (parts[i].isEmpty()) {
            continue;
          }
          response.addMetadata(name, parts[i]);
        }
      } else if (value.startsWith(";#") && value.endsWith(";#")) {
        // This is a multi-choice field. Values will be in the form:
        // ;#value1;#value2;#
        for (String part : value.split(";#")) {
          if (part.isEmpty()) {
            continue;
          }
          response.addMetadata(name, part);
        }
      } else {
        response.addMetadata(name, value);
      }
    }

    private Acl.Builder generateAcl(List<Permission> permissions,
        final long necessaryPermissionMask) throws IOException {
      List<UserPrincipal> permitUsers = new LinkedList<UserPrincipal>();
      List<GroupPrincipal> permitGroups = new LinkedList<GroupPrincipal>();
      MemberIdMapping mapping = getMemberIdMapping();
      for (Permission permission : permissions) {
        // Although it is named "mask", this is really a bit-field of
        // permissions.
        long mask = permission.getMask().longValue();
        if ((necessaryPermissionMask & mask) != necessaryPermissionMask) {
          continue;
        }
        Integer id = permission.getMemberid();
        String userName = mapping.getUserName(id);
        String groupName = mapping.getGroupName(id);
        if (userName != null) {
          permitUsers.add(new UserPrincipal(userName));
        } else if (groupName != null) {
          permitGroups.add(new GroupPrincipal(groupName));
        } else {
          log.log(Level.WARNING, "Could not resolve member id {0}", id);
        }
      }
      return new Acl.Builder().setEverythingCaseInsensitive()
          .setPermitUsers(permitUsers).setPermitGroups(permitGroups);
    }

    private void addPermitUserToAcl(int userId, Acl.Builder aclToUpdate)
        throws IOException {
      if (userId == -1) {
        return;
      }
      String userName = getUserName(userId);
      if (userName == null) {
        log.log(Level.WARNING, "Could not resolve user id {0}", userId);
        return;
      }

      List<UserPrincipal> permitUsers
          = new LinkedList<UserPrincipal>(aclToUpdate.build().getPermitUsers());
      permitUsers.add(new UserPrincipal(userName));
      aclToUpdate.setPermitUsers(permitUsers);
    }

    private boolean isPermitted(long permission,
        long necessaryPermission) {
      return (necessaryPermission & permission) == necessaryPermission;
    }

    private boolean isAllowAnonymousPeekForWeb(Web w) {
      return isPermitted(
          w.getMetadata().getAnonymousPermMask().longValue(), OPEN_MASK);
    }

    private boolean isAllowAnonymousReadForWeb(Web w) {
      boolean allowAnonymousRead
          = (w.getMetadata().getAllowAnonymousAccess() == TrueFalseType.TRUE)
          && (w.getMetadata().getAnonymousViewListItems() == TrueFalseType.TRUE)
          && isPermitted(
            w.getMetadata().getAnonymousPermMask().longValue(), LIST_ITEM_MASK);
      return allowAnonymousRead;
    }

    private boolean isAllowAnonymousReadForList (
        com.microsoft.schemas.sharepoint.soap.List l) {
      boolean allowAnonymousRead
          = (l.getMetadata().getReadSecurity() != LIST_READ_SECURITY_ENABLED)
          && (l.getMetadata().getAllowAnonymousAccess() == TrueFalseType.TRUE)
          && (l.getMetadata().getAnonymousViewListItems() == TrueFalseType.TRUE)
          && isPermitted(
            l.getMetadata().getAnonymousPermMask().longValue(),
            VIEW_LIST_ITEMS_MASK);
      return allowAnonymousRead;
    }

    private boolean isDenyAnonymousAcessOnVirtualServer(VirtualServer vs) { 
      long anonymousDenyMask
          =  vs.getPolicies().getAnonymousDenyMask().longValue();
      if ((LIST_ITEM_MASK & anonymousDenyMask) != 0) {   
        return true;
      }
      // Anonymous access is denied if deny read policy is specified for any
      // user or group.
      for (PolicyUser policyUser : vs.getPolicies().getPolicyUser()) {
        long deny = policyUser.getDenyMask().longValue();
        // If at least one necessary bit is masked, then deny user.
        if ((LIST_ITEM_MASK & deny) != 0) {
          return true;
        }
      }
      return false;
    }

    private String getUserName(int userId) throws IOException {
      String userName = getMemberIdMapping().getUserName(userId);
      // MemberIdMapping will have information about users with explicit
      // permissions on SharePoint or users which are direct members of
      // SharePoint groups. MemberIdMapping might not have information
      // about all valid SharePoint Users. To get all valid SharePoint users
      // under SiteCollection, use SiteUserMapping.
      if (userName == null) {
        userName = getSiteUserMapping().getUserName(userId);
      }
      return userName;
    }

    private void getAspxDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getAspxDocContent",
          new Object[] {request, response});
      Web w = getContentWeb();
      boolean allowAnonymousAccess = isAllowAnonymousReadForWeb(w);
      // Check if anonymous access is denied by web application policy
      // only if anonymous access is enabled for web as checking web application
      // policy is additional web service call.
      // TODO(ejona): Add caching for web application policy.
      if (allowAnonymousAccess) {
        allowAnonymousAccess 
            = !isDenyAnonymousAcessOnVirtualServer(getContentVirtualServer());
      }
      if (!allowAnonymousAccess) {
        String aspxId = request.getDocId().getUniqueId();
        String parentId = aspxId.substring(0, aspxId.lastIndexOf('/'));
        response.setAcl(new Acl.Builder()
            .setInheritFrom(new DocId(parentId))
            .build());
      }
      getFileDocContent(request, response);
      log.exiting("SiteDataClient", "getAspxDocContent");
    }

    /**
     * Blindly retrieve contents of DocId as if it were a file's URL. To prevent
     * security issues, this should only be used after the DocId has been
     * verified to be a valid document on the SharePoint instance. In addition,
     * ACLs and other metadata and security measures should be set before making
     * this call.
     */
    private void getFileDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getFileDocContent",
          new Object[] {request, response});
      URI displayUrl = docIdToUri(request.getDocId());
      FileInfo fi = httpClient.issueGetRequest(displayUrl.toURL());
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
        IOHelper.copyStream(fi.getContents(), response.getOutputStream());
      } finally {
        fi.getContents().close();
      }
      log.exiting("SiteDataClient", "getFileDocContent");
    }

    private void getListItemDocContent(Request request, Response response,
        String listId, String itemId) throws IOException {
      log.entering("SiteDataClient", "getListItemDocContent",
          new Object[] {request, response, listId, itemId});
      com.microsoft.schemas.sharepoint.soap.List l = getContentList(listId);
      boolean applyReadSecurity =
          (l.getMetadata().getReadSecurity() == LIST_READ_SECURITY_ENABLED);
      ItemData i = getContentItem(listId, itemId);

      Xml xml = i.getXml();
      Element data = getFirstChildWithName(xml, DATA_ELEMENT);
      Element row = getChildrenWithName(data, ROW_ELEMENT).get(0);

      // This should be in the form of "1234;#{GUID}". We want to extract the
      // {GUID}.
      String scopeId
          = row.getAttribute(OWS_SCOPEID_ATTRIBUTE).split(";#", 2)[1];
      scopeId = scopeId.toLowerCase(Locale.ENGLISH);

      String listScopeId
          = l.getMetadata().getScopeID().toLowerCase(Locale.ENGLISH);
      // Anonymous access is disabled if read security is applicable for list.
      // Anonymous access for list items is disabled if it does not inherit
      // its effective permissions from list.

      boolean allowAnonymousAccess = isAllowAnonymousReadForList(l)
          && scopeId.equals(listScopeId);

      // Check anomymous access on web only if anonymous access is applicable
      // for list and list item.
      if (allowAnonymousAccess) {
        Web w = getContentWeb();
        // Even if anonymous access is enabled on list, it can be turned off
        // on Web level by setting Anonymous access to "Nothing" on Web.
        // Anonymous User must have minimum "Open" permission on Web
        // for anonymous access to work on List and List Items.
        allowAnonymousAccess = isAllowAnonymousPeekForWeb(w);
      }

      if (allowAnonymousAccess) {
        allowAnonymousAccess 
            = !isDenyAnonymousAcessOnVirtualServer(getContentVirtualServer());
      }

      if (!allowAnonymousAccess) {
      Acl.Builder acl = null;
      if (!applyReadSecurity) {
        String rawFileDirRef = row.getAttribute(OWS_FILEDIRREF_ATTRIBUTE);
        // This should be in the form of "1234;#site/list/path". We want to
        // extract the site/list/path. Path relative to host, even though it
        // doesn't have a leading '/'.
        DocId folderDocId = encodeDocId("/" + rawFileDirRef.split(";#")[1]);
        DocId rootFolderDocId = encodeDocId(l.getMetadata().getRootFolder());
        DocId listDocId = encodeDocId(l.getMetadata().getDefaultViewUrl());
        // If the parent is the List, we must use the list's docId instead of
        // folderDocId, since the root folder is a List and not actually a
        // Folder.
        boolean parentIsList = folderDocId.equals(rootFolderDocId);
        DocId parentDocId = parentIsList ? listDocId : folderDocId;
        String parentScopeId;
        // If a folder doesn't inherit its list's scope, then all of the
        // folder's descendent list items are guaranteed to have a different
        // scope than the list. We use this knowledge as a performance
        // optimization to prevent issuing requests to discover the folder's
        // scopeId when a child list item and list have the same scope.
        if (parentIsList || scopeId.equals(listScopeId)) {
          parentScopeId = listScopeId;
        } else {
            // Instead of using getURLSegments and getContent(ListItem),
            // we could use just getContent(Folder).
            // However, getContent(Folder) always returns children which could
            // make the call very expensive. In addition, getContent(ListItem)
            // returns all the metadata for the folder instead of just its scope
            // so if in the future we need more metadata we will already have
            // it. GetContentEx(Folder) may provide a way to get the folder's
            // scope without its children, but it wasn't investigated.
          Holder<String> folderListId = new Holder<String>();
          Holder<String> folderItemId = new Holder<String>();
          Holder<Boolean> result = new Holder<Boolean>();
          siteData.getURLSegments(folderDocId.getUniqueId(), result, null,
              null, folderListId, folderItemId);
          if (!result.value) {
            throw new IOException("Could not find parent folder's itemId");
          }
          if (!listId.equals(folderListId.value)) {
            throw new AssertionError("Unexpected listId value");
          }
          ItemData folderItem = getContentItem(listId, folderItemId.value);
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
                  .setInheritFrom(virtualServerDocId);
              break;
            }
          }
        }

        if (acl == null) {
          throw new IOException("Unable to find permission scope for item: "
              + request.getDocId());
        }
      } else {
        DocId namedResource
            = new DocId(request.getDocId().getUniqueId() + "_READ_SECURITY");
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
            .setInheritFrom(namedResource);
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
            .setInheritFrom(virtualServerDocId)
            .setInheritanceType(Acl.InheritanceType.AND_BOTH_PERMIT);
        addPermitUserToAcl(authorId, aclNamedResource);
        final Map<DocId, Acl> map = new TreeMap<DocId, Acl>();
        map.put(namedResource, aclNamedResource.build());
        executor.execute(new Runnable() {
          @Override
          public void run() {
            try {
              context.getDocIdPusher().pushNamedResources(map);
            } catch (InterruptedException ie) {
              log.log(Level.WARNING, "Error pushing named resource", ie);
            }
          }
        });
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

      for (Attr attribute : getAllAttributes(row)) {
        addMetadata(response, attribute.getName(), attribute.getValue());
      }

      if (isFolder) {
        String root
            = encodeDocId(l.getMetadata().getRootFolder()).getUniqueId();
        root += "/";
        String folder = encodeDocId(serverUrl).getUniqueId();
        if (!folder.startsWith(root)) {
          throw new AssertionError();
        }
        URI displayPage
            = sharePointUrlToUri(l.getMetadata().getDefaultViewUrl());
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
        response.setContentType("text/html");
        HtmlResponseWriter writer = createHtmlResponseWriter(response);
        writer.start(request.getDocId(), ObjectType.FOLDER, null);
        processFolder(listId, folder.substring(root.length()), writer);
        writer.finish();
        log.exiting("SiteDataClient", "getListItemDocContent");
        return;
      }
      String contentTypeId = row.getAttribute(OWS_CONTENTTYPEID_ATTRIBUTE);
      if (contentTypeId != null
          && contentTypeId.startsWith(CONTENTTYPEID_DOCUMENT_PREFIX)) {
        // This is a file (or "Document" in SharePoint-speak), so display its
        // contents.
        getFileDocContent(request, response);
      } else {
        // Some list item.
        URI displayPage
            = sharePointUrlToUri(l.getMetadata().getDefaultViewItemUrl());
        try {
          response.setDisplayUrl(new URI(displayPage.getScheme(),
              displayPage.getAuthority(), displayPage.getPath(),
              "ID=" + itemId, null));
        } catch (URISyntaxException ex) {
          throw new IOException(ex);
        }
        response.setContentType("text/html");
        HtmlResponseWriter writer = createHtmlResponseWriter(response);
        writer.start(request.getDocId(), ObjectType.LIST_ITEM, title);
        String strAttachments = row.getAttribute(OWS_ATTACHMENTS_ATTRIBUTE);
        int attachments = (strAttachments == null || "".equals(strAttachments))
            ? 0 : Integer.parseInt(strAttachments);
        if (attachments > 0) {
          writer.startSection(ObjectType.LIST_ITEM_ATTACHMENTS);
          Item item = getContentListItemAttachments(listId, itemId);
          for (Item.Attachment attachment : item.getAttachment()) {
            writer.addLink(encodeDocId(attachment.getURL()), null);
          }
        }
        writer.finish();
      }
      log.exiting("SiteDataClient", "getListItemDocContent");
    }

    private boolean getAttachmentDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getAttachmentDocContent", new Object[] {
          request, response});
      String url = request.getDocId().getUniqueId();
      if (!url.contains("/Attachments/")) {
        log.fine("Not an attachment: does not contain /Attachments/");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      String[] parts = url.split("/Attachments/", 2);
      String listUrl = parts[0] + "/AllItems.aspx";
      parts = parts[1].split("/", 2);
      if (parts.length != 2) {
        log.fine("Could not separate attachment file name and list item id");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      String itemId = parts[0];
      log.log(Level.FINE, "Detected possible attachment: "
          + "listUrl={0}, itemId={1}", new Object[] {listUrl, itemId});
      Holder<String> listIdHolder = new Holder<String>();
      Holder<Boolean> result = new Holder<Boolean>();
      siteData.getURLSegments(listUrl, result, null, null, listIdHolder, null);
      if (!result.value) {
        log.fine("Could not get list id from list url");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      String listId = listIdHolder.value;
      if (listId == null) {
        log.fine("List URL does not point to a list");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      Item item = getContentListItemAttachments(listId, itemId);
      boolean verifiedIsAttachment = false;
      for (Item.Attachment attachment : item.getAttachment()) {
        if (url.equals(attachment.getURL())) {
          verifiedIsAttachment = true;
          break;
        }
      }
      if (!verifiedIsAttachment) {
        log.fine("Suspected attachment not listed in item's attachment list");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      ItemData itemData = getContentItem(listId, itemId);
      Xml xml = itemData.getXml();
      Element data = getFirstChildWithName(xml, DATA_ELEMENT);
      Element row = getChildrenWithName(data, ROW_ELEMENT).get(0);
      log.fine("Suspected attachment verified as being a real attachment. "
          + "Proceeding to provide content.");
      com.microsoft.schemas.sharepoint.soap.List l = getContentList(listId);
      String scopeId
          = row.getAttribute(OWS_SCOPEID_ATTRIBUTE).split(";#", 2)[1];
      scopeId = scopeId.toLowerCase(Locale.ENGLISH);

      String listScopeId
          = l.getMetadata().getScopeID().toLowerCase(Locale.ENGLISH);
      boolean allowAnonymousAccess = isAllowAnonymousReadForList(l)
          && scopeId.equals(listScopeId);
      if (allowAnonymousAccess) {
        allowAnonymousAccess 
            = !isDenyAnonymousAcessOnVirtualServer(getContentVirtualServer());
      }
      if (!allowAnonymousAccess) {
        String listItemUrl = row.getAttribute(OWS_SERVERURL_ATTRIBUTE);
        response.setAcl(new Acl.Builder()
            .setInheritFrom(encodeDocId(listItemUrl))
            .build());
      }
      getFileDocContent(request, response);
      log.exiting("SiteDataClient", "getAttachmentDocContent", true);
      return true;
    }

    @VisibleForTesting
    void getModifiedDocIds(SPContentDatabase changes, DocIdPusher pusher)
        throws IOException, InterruptedException {
      log.entering("SiteDataClient", "getModifiedDocIds",
          new Object[] {changes, pusher});
      List<DocId> docIds = new ArrayList<DocId>();
      getModifiedDocIdsContentDatabase(changes, docIds);
      List<DocIdPusher.Record> records
        = new ArrayList<DocIdPusher.Record>(docIds.size());
      DocIdPusher.Record.Builder builder
          = new DocIdPusher.Record.Builder(new DocId("fake"))
          .setCrawlImmediately(true);
      for (DocId docId : docIds) {
        records.add(builder.setDocId(docId).build());
      }
      pusher.pushRecords(records);
      log.exiting("SiteDataClient", "getModifiedDocIds");
    }

    private void getModifiedDocIdsContentDatabase(SPContentDatabase changes,
        List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsContentDatabase",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())) {
        docIds.add(virtualServerDocId);
      }
      for (SPSite site : changes.getSPSite()) {
        getModifiedDocIdsSite(site, docIds);
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsContentDatabase");
    }

    private void getModifiedDocIdsSite(SPSite changes, List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsSite",
          new Object[] {changes, docIds});
      if (isModified(changes.getChange())) {
        docIds.add(new DocId(changes.getSite().getMetadata().getURL()));
      }
      for (SPWeb web : changes.getSPWeb()) {
        getModifiedDocIdsWeb(web, docIds);
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsSite");
    }

    private void getModifiedDocIdsWeb(SPWeb changes, List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsWeb",
          new Object[] {changes, docIds});
      if (isModified(changes.getChange())) {
        docIds.add(new DocId(changes.getWeb().getMetadata().getURL()));
      }
      for (Object choice : changes.getSPFolderOrSPListOrSPFile()) {
        if (choice instanceof SPFolder) {
          getModifiedDocIdsFolder((SPFolder) choice, docIds);
        }
        if (choice instanceof SPList) {
          getModifiedDocIdsList((SPList) choice, docIds);
        }
        if (choice instanceof SPFile) {
          getModifiedDocIdsFile((SPFile) choice, docIds);
        }
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsWeb");
    }

    private void getModifiedDocIdsFolder(SPFolder changes, List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsFolder",
          new Object[] {changes, docIds});
      if (isModified(changes.getChange())) {
        docIds.add(encodeDocId(changes.getDisplayUrl()));
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsFolder");
    }

    private void getModifiedDocIdsList(SPList changes, List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsList",
          new Object[] {changes, docIds});
      if (isModified(changes.getChange())) {
        docIds.add(encodeDocId(changes.getDisplayUrl()));
      }
      for (Object choice : changes.getSPViewOrSPListItem()) {
        // Ignore view change detection.

        if (choice instanceof SPListItem) {
          getModifiedDocIdsListItem((SPListItem) choice, docIds);
        }
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsList");
    }

    private void getModifiedDocIdsListItem(SPListItem changes,
        List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsListItem",
          new Object[] {changes, docIds});
      if (isModified(changes.getChange())) {
        Object oData = changes.getListItem().getAny();
        if (!(oData instanceof Element)) {
          log.log(Level.WARNING, "Unexpected object type for data: {0}",
              oData.getClass());
        } else {
          Element data = (Element) oData;
          String url = data.getAttribute(OWS_SERVERURL_ATTRIBUTE);
          if (url == null) {
            log.log(Level.WARNING, "Could not find server url attribute for "
                + "list item {0}", changes.getId());
          } else {
            docIds.add(encodeDocId(url));
          }
        }
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsListItem");
    }

    private void getModifiedDocIdsFile(SPFile changes, List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsFile",
          new Object[] {changes, docIds});
      if (isModified(changes.getChange())) {
        docIds.add(encodeDocId(changes.getDisplayUrl()));
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsFile");
    }

    private boolean isModified(String change) {
      return !"Unchanged".equals(change) && !"Delete".equals(change);
    }

    private MemberIdMapping retrieveMemberIdMapping() throws IOException {
      log.entering("SiteDataClient", "retrieveMemberIdMapping");
      Site site = getContentSite();
      Map<Integer, String> groupMap = new HashMap<Integer, String>();
      for (GroupMembership.Group group : site.getGroups().getGroup()) {
        GroupDescription gd = group.getGroup();
        groupMap.put(gd.getID(), gd.getName().intern());
      }
      Map<Integer, String> userMap = new HashMap<Integer, String>();
      for (UserDescription user : site.getWeb().getUsers().getUser()) {
        userMap.put(user.getID(), user.getLoginName().intern());
      }
      MemberIdMapping mapping = new MemberIdMapping(userMap, groupMap);
      log.exiting("SiteDataClient", "retrieveMemberIdMapping", mapping);
      return mapping;
    }

    private MemberIdMapping retrieveSiteUserMapping()
        throws IOException {
      log.entering("SiteDataClient", "retrieveSiteUserMapping");
      GetUserCollectionFromSiteResponse.GetUserCollectionFromSiteResult result
          = userGroup.getUserCollectionFromSite();
      Map<Integer, String> userMap = new HashMap<Integer, String>();
      Map<Integer, String> groupMap = new HashMap<Integer, String>();
      MemberIdMapping mapping;
      if (result == null) {
        mapping = new MemberIdMapping(userMap, groupMap);
        log.exiting("SiteDataClient", "retrieveSiteUserMapping", mapping);
        return mapping;
      }
      GetUserCollectionFromSiteResult.GetUserCollectionFromSite siteUsers
           = result.getGetUserCollectionFromSite();
      if (siteUsers.getUsers() == null) {
        mapping = new MemberIdMapping(userMap, groupMap);
        log.exiting("SiteDataClient", "retrieveSiteUserMapping", mapping);
        return mapping;
      }
      for (User user : siteUsers.getUsers().getUser()) {
        userMap.put((int) user.getID(), user.getLoginName());
      }
      mapping = new MemberIdMapping(userMap, groupMap);
      log.exiting("SiteDataClient", "retrieveSiteUserMapping", mapping);
      return mapping;
    }

    private VirtualServer getContentVirtualServer() throws IOException {
      log.entering("SiteDataClient", "getContentVirtualServer");
      Holder<String> result = new Holder<String>();
      siteData.getContent(ObjectType.VIRTUAL_SERVER, null, null, null, true,
          false, null, result);
      String xml = result.value;
      xml = xml.replace("<VirtualServer>",
          "<VirtualServer xmlns='" + XMLNS + "'>");
      VirtualServer vs = jaxbParse(xml, VirtualServer.class);
      log.exiting("SiteDataClient", "getContentVirtualServer", vs);
      return vs;
    }

    private SiteDataClient getClientForUrl(String url) throws IOException {
      log.entering("SiteDataClient", "getClientForUrl", url);
      Holder<Long> result = new Holder<Long>();
      Holder<String> site = new Holder<String>();
      Holder<String> web = new Holder<String>();
      siteData.getSiteAndWeb(url, result, site, web);

      if (result.value != 0) {
        log.exiting("SiteDataClient", "getClientForUrl", null);
        return null;
      }
      SiteDataClient client = getSiteDataClient(site.value, web.value);
      log.exiting("SiteDataClient", "getClientForUrl", client);
      return client;
    }

    private ContentDatabase getContentContentDatabase(String id,
        boolean retrieveChildItems) throws IOException {
      log.entering("SiteDataClient", "getContentContentDatabase", id);
      Holder<String> result = new Holder<String>();
      siteData.getContent(ObjectType.CONTENT_DATABASE, id, null, null,
          retrieveChildItems, false, null, result);
      String xml = result.value;
      xml = xml.replace("<ContentDatabase>",
          "<ContentDatabase xmlns='" + XMLNS + "'>");
      ContentDatabase cd = jaxbParse(xml, ContentDatabase.class);
      log.exiting("SiteDataClient", "getContentContentDatabase", cd);
      return cd;
    }

    private Site getContentSite() throws IOException {
      log.entering("SiteDataClient", "getContentSite");
      Holder<String> result = new Holder<String>();
      final boolean retrieveChildItems = true;
      // When ObjectType is SITE_COLLECTION, retrieveChildItems is the only
      // input value consulted.
      siteData.getContent(ObjectType.SITE_COLLECTION, null, null, null,
          retrieveChildItems, false, null, result);
      String xml = result.value;
      xml = xml.replace("<Site>", "<Site xmlns='" + XMLNS + "'>");
      Site site = jaxbParse(xml, Site.class);
      log.exiting("SiteDataClient", "getContentSite", site);
      return site;
    }

    private Web getContentWeb() throws IOException {
      log.entering("SiteDataClient", "getContentWeb");
      Holder<String> result = new Holder<String>();
      siteData.getContent(ObjectType.SITE, null, null, null, true, false, null,
          result);
      String xml = result.value;
      xml = xml.replace("<Web>", "<Web xmlns='" + XMLNS + "'>");
      Web web = jaxbParse(xml, Web.class);
      log.exiting("SiteDataClient", "getContentWeb", web);
      return web;
    }

    private com.microsoft.schemas.sharepoint.soap.List getContentList(String id)
        throws IOException {
      log.entering("SiteDataClient", "getContentList", id);
      Holder<String> result = new Holder<String>();
      siteData.getContent(ObjectType.LIST, id, null, null, false, false, null,
          result);
      String xml = result.value;
      xml = xml.replace("<List>", "<List xmlns='" + XMLNS + "'>");
      com.microsoft.schemas.sharepoint.soap.List list = jaxbParse(xml,
          com.microsoft.schemas.sharepoint.soap.List.class);
      log.exiting("SiteDataClient", "getContentList", list);
      return list;
    }

    private ItemData getContentItem(String listId, String itemId)
        throws IOException {
      log.entering("SiteDataClient", "getContentItem",
          new Object[] {listId, itemId});
      Holder<String> result = new Holder<String>();
      siteData.getContent(ObjectType.LIST_ITEM, listId, "", itemId, false,
          false, null, result);
      String xml = result.value;
      xml = xml.replace("<Item>", "<ItemData xmlns='" + XMLNS + "'>");
      xml = xml.replace("</Item>", "</ItemData>");
      ItemData data = jaxbParse(xml, ItemData.class);
      log.exiting("SiteDataClient", "getContentItem", data);
      return data;
    }

    private Paginator<ItemData> getContentFolderChildren(final String guid,
        final String url) {
      log.entering("SiteDataClient", "getContentFolderChildren",
          new Object[] {guid, url});
      final Holder<String> lastItemIdOnPage = new Holder<String>("");
      log.exiting("SiteDataClient", "getContentFolderChildren");
      return new Paginator<ItemData>() {
        @Override
        public ItemData next() throws IOException {
          if (lastItemIdOnPage.value == null) {
            return null;
          }
          Holder<String> result = new Holder<String>();
          siteData.getContent(ObjectType.FOLDER, guid, url, null, true, false,
              lastItemIdOnPage, result);
          String xml = result.value;
          xml = xml.replace("<Folder>", "<Folder xmlns='" + XMLNS + "'>");
          return jaxbParse(xml, ItemData.class);
        }
      };
    }

    private Item getContentListItemAttachments(String listId, String itemId)
        throws IOException {
      log.entering("SiteDataClient", "getContentListItemAttachments",
          new Object[] {listId, itemId});
      Holder<String> result = new Holder<String>();
      siteData.getContent(ObjectType.LIST_ITEM_ATTACHMENTS, listId, "",
          itemId, true, false, null, result);
      String xml = result.value;
      xml = xml.replace("<Item ", "<Item xmlns='" + XMLNS + "' ");
      Item item = jaxbParse(xml, Item.class);
      log.exiting("SiteDataClient", "getContentListItemAttachments", item);
      return item;
    }

    /**
     * Get a paginator that allows looping over all the changes since {@code
     * startChangeId}. If next() throws an XmlProcessingException, it is
     * guaranteed to be after state has been updated so that a subsequent call
     * to next() will provide the next page and not repeat the erroring page.
     */
    private CursorPaginator<SPContentDatabase, String>
        getChangesContentDatabase(final String contentDatabaseGuid,
            String startChangeId, final boolean isSp2010) {
      log.entering("SiteDataClient", "getChangesContentDatabase",
          new Object[] {contentDatabaseGuid, startChangeId});
      final Holder<String> lastChangeId = new Holder<String>(startChangeId);
      final Holder<String> lastLastChangeId = new Holder<String>();
      final Holder<String> currentChangeId = new Holder<String>();
      final Holder<Boolean> moreChanges = new Holder<Boolean>(true);
      log.exiting("SiteDataClient", "getChangesContentDatabase");
      return new CursorPaginator<SPContentDatabase, String>() {
        @Override
        public SPContentDatabase next() throws IOException {
          if (!moreChanges.value) {
            return null;
          }
          lastLastChangeId.value = lastChangeId.value;
          Holder<String> result = new Holder<String>();
          // In non-SP2010, the timeout is a number of seconds. In SP2010, the
          // timeout is n * 60, where n is the number of items you want
          // returned. However, in SP2010, asking for more than 10 items seems
          // to lose results. If timeout is less than 60 in SP 2010, then it
          // causes an infinite loop.
          int timeout = isSp2010 ? 10 * 60 : 15;
          siteData.getChanges(ObjectType.CONTENT_DATABASE, contentDatabaseGuid,
              lastChangeId, currentChangeId, timeout, result, moreChanges);
          // XmlProcessingExceptions fine after this point.
          String xml = result.value;
          xml = xml.replace("<SPContentDatabase ",
              "<SPContentDatabase xmlns='" + XMLNS + "' ");
          return jaxbParse(xml, SPContentDatabase.class);
        }

        @Override
        public String getCursor() {
          return lastChangeId.value;
        }
      };
    }

    @VisibleForTesting
    <T> T jaxbParse(String xml, Class<T> klass)
        throws XmlProcessingException {
      Source source = new StreamSource(new StringReader(xml));
      try {
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        if (xmlValidation) {
          unmarshaller.setSchema(schema);
        }
        return unmarshaller.unmarshal(source, klass).getValue();
      } catch (JAXBException ex) {
        throw new XmlProcessingException(ex, xml);
      }
    }

    private HtmlResponseWriter createHtmlResponseWriter(Response response)
        throws IOException {
      Writer writer
          = new OutputStreamWriter(response.getOutputStream(), CHARSET);
      // TODO(ejona): Get locale from request.
      return new HtmlResponseWriter(writer, context.getDocIdEncoder(),
          Locale.ENGLISH);
    }
  }

  /**
   * Container exception for wrapping xml processing exceptions in IOExceptions.
   */
  private static class XmlProcessingException extends IOException {
    public XmlProcessingException(JAXBException cause, String xml) {
      super("Error when parsing xml: " + xml, cause);
    }
  }

  /**
   * Container exception for wrapping WebServiceExceptions in a checked
   * exception.
   */
  private static class WebServiceIOException extends IOException {
    public WebServiceIOException(WebServiceException cause) {
      super(cause);
    }
  }

  /**
   * An object that can be paged through.
   *
   * @param <E> element type returned by {@link #next}
   */
  private interface Paginator<E> {
    /**
     * Get the next page of the series. If an exception is thrown, the state of
     * the paginator is undefined.
     *
     * @return the next page of data, or {@code null} if no more pages available
     */
    public E next() throws IOException;
  }

  /**
   * An object that can be paged through, but also provide a cursor for learning
   * its current position.
   *
   * @param <E> element type returned by {@link #next}
   * @param <C> cursor type
   */
  private interface CursorPaginator<E, C> extends Paginator<E> {
    /**
     * Provides a cursor for the current position. The intent is that you could
     * get a cursor (even in the event of {@link #next} throwing an exception)
     * and use it to create a query that would continue without repeating
     * results.
     */
    public C getCursor();
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
    public FileInfo issueGetRequest(URL url) throws IOException;
  }

  static class HttpClientImpl implements HttpClient {
    @Override
    public FileInfo issueGetRequest(URL url) throws IOException {
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
  interface SiteDataFactory {
    /**
     * The {@code endpoint} string is a SharePoint URL, meaning that spaces are
     * not encoded.
     */
    public SiteDataSoap newSiteData(String endpoint) throws IOException;
  }

  static class SiteDataFactoryImpl implements SiteDataFactory {
    private final Service siteDataService;

    public SiteDataFactoryImpl() {
      URL url = SiteDataSoap.class.getResource("SiteData.wsdl");
      QName qname = new QName(XMLNS, "SiteData");
      this.siteDataService = Service.create(url, qname);
    }

    @Override
    public SiteDataSoap newSiteData(String endpoint) throws IOException {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(spUrlToUri(endpoint).toString()).build();
      return siteDataService.getPort(endpointRef, SiteDataSoap.class);
    }
  }

  @VisibleForTesting
  interface UserGroupFactory {
    public UserGroupSoap newUserGroup(String endpoint);
  }

  static class UserGroupFactoryImpl implements UserGroupFactory {
    private final Service userGroupService;

    public UserGroupFactoryImpl() {
      URL url = UserGroupSoap.class.getResource("UserGroup.wsdl");
      QName qname = new QName(XMLNS_DIRECTORY, "UserGroup");
      this.userGroupService = Service.create(url, qname);
    }

    @Override
    public UserGroupSoap newUserGroup(String endpoint) {
      EndpointReference endpointRef = new W3CEndpointReferenceBuilder()
          .address(endpoint).build();
      return userGroupService.getPort(endpointRef, UserGroupSoap.class);
    }
  }

  private static class NtlmAuthenticator extends Authenticator {
    private final String username;
    private final char[] password;
    private final String host;
    private final int port;

    public NtlmAuthenticator(String username, String password, String host,
        int port) {
      this.username = username;
      this.password = password.toCharArray();
      this.host = host;
      this.port = port;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
      URL url = getRequestingURL();
      // If the port is missing (so that the default is used), then the port
      // will be -1 here. The port needs to be consistently specified or
      // missing.
      if (host.equals(url.getHost()) && port == url.getPort()) {
        return new PasswordAuthentication(username, password);
      } else {
        return super.getPasswordAuthentication();
      }
    }
  }

  /**
   * A subset of SiteDataSoap that throws WebServiceIOExceptions instead of the
   * WebServiceException (which is a RuntimeException).
   */
  private static interface CheckedExceptionSiteDataSoap {
    public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
        Holder<String> strSite, Holder<String> strWeb)
        throws WebServiceIOException;

    public void getURLSegments(String strURL,
        Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
        Holder<String> strBucketID, Holder<String> strListID,
        Holder<String> strItemID) throws WebServiceIOException;

    public void getContent(ObjectType objectType, String objectId,
        String folderUrl, String itemId, boolean retrieveChildItems,
        boolean securityOnly, Holder<String> lastItemIdOnPage,
        Holder<String> getContentResult) throws WebServiceIOException;

    public void getChanges(ObjectType objectType, String contentDatabaseId,
        Holder<String> lastChangeId, Holder<String> currentChangeId,
        Integer timeout, Holder<String> getChangesResult,
        Holder<Boolean> moreChanges) throws WebServiceIOException;
  }

  private static class CheckedExceptionSiteDataSoapAdapter
      implements CheckedExceptionSiteDataSoap {
    private final SiteDataSoap siteData;

    public CheckedExceptionSiteDataSoapAdapter(SiteDataSoap siteData) {
      this.siteData = siteData;
    }

    @Override
    public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
        Holder<String> strSite, Holder<String> strWeb)
        throws WebServiceIOException {
      try {
        siteData.getSiteAndWeb(strUrl, getSiteAndWebResult, strSite, strWeb);
      } catch (WebServiceException ex) {
        throw new WebServiceIOException(ex);
      }
    }

    @Override
    public void getURLSegments(String strURL,
        Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
        Holder<String> strBucketID, Holder<String> strListID,
        Holder<String> strItemID) throws WebServiceIOException {
      try {
        siteData.getURLSegments(strURL, getURLSegmentsResult, strWebID,
            strBucketID, strListID, strItemID);
      } catch (WebServiceException ex) {
        throw new WebServiceIOException(ex);
      }
    }

    @Override
    public void getContent(ObjectType objectType, String objectId,
        String folderUrl, String itemId, boolean retrieveChildItems,
        boolean securityOnly, Holder<String> lastItemIdOnPage,
        Holder<String> getContentResult) throws WebServiceIOException {
      try {
        siteData.getContent(objectType, objectId, folderUrl, itemId,
            retrieveChildItems, securityOnly, lastItemIdOnPage,
            getContentResult);
      } catch (WebServiceException ex) {
        throw new WebServiceIOException(ex);
      }
    }

    @Override
    public void getChanges(ObjectType objectType, String contentDatabaseId,
        Holder<String> lastChangeId, Holder<String> currentChangeId,
        Integer timeout, Holder<String> getChangesResult,
        Holder<Boolean> moreChanges) throws WebServiceIOException {
      try {
        siteData.getChanges(objectType, contentDatabaseId, lastChangeId,
            currentChangeId, timeout, getChangesResult, moreChanges);
      } catch (WebServiceException ex) {
        throw new WebServiceIOException(ex);
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
      return getSiteDataClient(site, site).retrieveMemberIdMapping();
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
      return getSiteDataClient(site, site).retrieveSiteUserMapping();
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
