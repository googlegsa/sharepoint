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
import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdEncoder;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.IOHelper;
import com.google.enterprise.adaptor.PollingIncrementalAdaptor;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;

import com.microsoft.schemas.sharepoint.soap.ContentDatabase;
import com.microsoft.schemas.sharepoint.soap.ContentDatabases;
import com.microsoft.schemas.sharepoint.soap.Files;
import com.microsoft.schemas.sharepoint.soap.FolderData;
import com.microsoft.schemas.sharepoint.soap.Folders;
import com.microsoft.schemas.sharepoint.soap.Item;
import com.microsoft.schemas.sharepoint.soap.ItemData;
import com.microsoft.schemas.sharepoint.soap.Lists;
import com.microsoft.schemas.sharepoint.soap.ObjectType;
import com.microsoft.schemas.sharepoint.soap.SPContentDatabase;
import com.microsoft.schemas.sharepoint.soap.SPFile;
import com.microsoft.schemas.sharepoint.soap.SPFolder;
import com.microsoft.schemas.sharepoint.soap.SPList;
import com.microsoft.schemas.sharepoint.soap.SPListItem;
import com.microsoft.schemas.sharepoint.soap.SPSite;
import com.microsoft.schemas.sharepoint.soap.SPWeb;
import com.microsoft.schemas.sharepoint.soap.SiteDataSoap;
import com.microsoft.schemas.sharepoint.soap.Sites;
import com.microsoft.schemas.sharepoint.soap.VirtualServer;
import com.microsoft.schemas.sharepoint.soap.Web;
import com.microsoft.schemas.sharepoint.soap.Webs;
import com.microsoft.schemas.sharepoint.soap.Xml;

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

/**
 * SharePoint Adaptor for the GSA.
 */
public class SharePointAdaptor extends AbstractAdaptor
    implements PollingIncrementalAdaptor {
  private static final Charset CHARSET = Charset.forName("UTF-8");
  private static final String XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";
  private static final QName DATA_ELEMENT
      = new QName("urn:schemas-microsoft-com:rowset", "data");
  private static final QName ROW_ELEMENT = new QName("#RowsetSchema", "row");
  private static final String OWS_FSOBJTYPE_ATTRIBUTE = "ows_FSObjType";
  private static final String OWS_TITLE_ATTRIBUTE = "ows_Title";
  private static final String OWS_SERVERURL_ATTRIBUTE = "ows_ServerUrl";
  private static final String OWS_CONTENTTYPEID_ATTRIBUTE = "ows_ContentTypeId";
  /**
   * As described at http://msdn.microsoft.com/en-us/library/aa543822.aspx .
   */
  private static final String CONTENTTYPEID_DOCUMENT_PREFIX = "0x0101";
  private static final String OWS_ATTACHMENTS_ATTRIBUTE = "ows_Attachments";
  private static final Pattern ALTERNATIVE_VALUE_PATTERN
      = Pattern.compile("^\\d+;#");
  /**
   * The JAXBContext is expensive to initialize, so we share a copy between
   * instances.
   */
  private static final JAXBContext jaxbContext;
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

  private final ConcurrentMap<String, SiteDataClient> clients
      = new ConcurrentSkipListMap<String, SiteDataClient>();
  private final DocId virtualServerDocId = new DocId("");
  private AdaptorContext context;
  private String virtualServer;
  private final ConcurrentSkipListMap<String, String> contentDatabaseChangeId
      = new ConcurrentSkipListMap<String, String>();
  private final SiteDataFactory siteDataFactory;
  private final HttpClient httpClient;
  private NtlmAuthenticator ntlmAuthenticator;

  public SharePointAdaptor() {
    this(new SiteDataFactoryImpl(), new HttpClientImpl());
  }

  @VisibleForTesting
  SharePointAdaptor(SiteDataFactory siteDataFactory, HttpClient httpClient) {
    if (siteDataFactory == null || httpClient == null) {
      throw new NullPointerException();
    }
    this.siteDataFactory = siteDataFactory;
    this.httpClient = httpClient;
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
    config.addKey("sharepoint.server", null);
    config.addKey("sharepoint.username", null);
    config.addKey("sharepoint.password", null);
  }

  @Override
  public void init(AdaptorContext context) throws IOException {
    this.context = context;
    Config config = context.getConfig();
    virtualServer = config.getValue("sharepoint.server");
    String username = config.getValue("sharepoint.username");
    String password = context.getSensitiveValueDecoder().decodeValue(
        config.getValue("sharepoint.password"));

    log.log(Level.CONFIG, "VirtualServer: {0}", virtualServer);
    log.log(Level.CONFIG, "Username: {0}", username);
    log.log(Level.CONFIG, "Password: {0}", password);

    ntlmAuthenticator = new NtlmAuthenticator(username, password);
    // Unfortunately, this is a JVM-wide modification.
    Authenticator.setDefault(ntlmAuthenticator);

    if (false) {
      contentDatabaseChangeId.put("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}",
          "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634717634720100000;597");
    }
  }

  @Override
  public void destroy() {
    Authenticator.setDefault(null);
  }

  @Override
  public void getDocContent(Request request, Response response)
      throws IOException {
    log.entering("SharePointAdaptor", "getDocContent",
        new Object[] {request, response});
    DocId id = request.getDocId();
    SiteDataClient virtualServerClient = getSiteDataClient(virtualServer);
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
    SiteDataClient client = getSiteDataClient(virtualServer);
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
          = client.getChangesContentDatabase(contentDatabase, changeId);
      SPContentDatabase changes;
      try {
        while ((changes = changesPaginator.next()) != null) {
          try {
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

  private SiteDataClient getSiteDataClient(String site) {
    SiteDataClient client = clients.get(site);
    if (client == null) {
      if (!site.endsWith("/")) {
        // Always end with a '/' for a canonical form.
        site = site + "/";
      }
      String endpoint = site + "_vti_bin/SiteData.asmx";
      ntlmAuthenticator.addToWhitelist(endpoint);
      SiteDataSoap siteDataSoap = siteDataFactory.newSiteData(endpoint);

      client = new SiteDataClient(site, siteDataSoap);
      clients.putIfAbsent(site, client);
      client = clients.get(site);
    }
    return client;
  }

  public static void main(String[] args) {
    AbstractAdaptor.main(new SharePointAdaptor(), args);
  }

  @VisibleForTesting
  class SiteDataClient {
    private final CheckedExceptionSiteDataSoap siteData;
    private final String siteUrl;

    public SiteDataClient(String site, SiteDataSoap siteDataSoap) {
      log.entering("SiteDataClient", "SiteDataClient",
          new Object[] {site, siteDataSoap});
      if (!site.endsWith("/")) {
        throw new AssertionError();
      }
      this.siteUrl = site;
      siteDataSoap = LoggingWSHandler.create(SiteDataSoap.class, siteDataSoap);
      this.siteData = new CheckedExceptionSiteDataSoapAdapter(siteDataSoap);
      log.exiting("SiteDataClient", "SiteDataClient");
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
        getSiteDocContent(request, response, null);
      }
      log.exiting("SiteDataClient", "getDocContent");
    }

    private DocId encodeDocId(String url) {
      log.entering("SiteDataClient", "encodeDocId", url);
      if (url.toLowerCase().startsWith("https://")
          || url.toLowerCase().startsWith("http://")) {
        // Leave as-is.
      } else if (!url.startsWith("/")) {
        url = siteUrl + url;
      } else {
        // Rip off everthing after the third slash (including the slash).
        // Get http://example.com from http://example.com/some/folder.
        String[] parts = siteUrl.split("/", 4);
        url = parts[0] + "//" + parts[2] + url;
      }
      DocId docId = new DocId(url);
      log.exiting("SiteDataClient", "encodeDocId", docId);
      return docId;
    }

    private void getVirtualServerDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getVirtualServerDocContent",
          new Object[] {request, response});
      VirtualServer vs = getContentVirtualServer();
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

    private void getSiteDocContent(Request request, Response response,
        String id) throws IOException {
      log.entering("SiteDataClient", "getSiteDocContent",
          new Object[] {request, response, id});
      Web w = getContentWeb(id);
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
          = getContentFolder(listGuid, folderPath);
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

    private void getAspxDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getAspxDocContent",
          new Object[] {request, response});
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
      String url = request.getDocId().getUniqueId();
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
      URL finalUrl = hostUri.resolve(pathUri).toURL();
      FileInfo fi = httpClient.issueGetRequest(finalUrl);
      try {
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
      ItemData i = getContentItem(listId, itemId);
      Xml xml = i.getXml();

      Element data = getFirstChildWithName(xml, DATA_ELEMENT);
      Element row = getChildrenWithName(data, ROW_ELEMENT).get(0);
      // This should be in the form of "1234;#0". We want to extract the 0.
      String type = row.getAttribute(OWS_FSOBJTYPE_ATTRIBUTE).split(";#", 2)[1];
      boolean isFolder = "1".equals(type);
      String title = row.getAttribute(OWS_TITLE_ATTRIBUTE);
      String serverUrl = row.getAttribute(OWS_SERVERURL_ATTRIBUTE);

      for (Attr attribute : getAllAttributes(row)) {
        addMetadata(response, attribute.getName(), attribute.getValue());
      }

      if (isFolder) {
        com.microsoft.schemas.sharepoint.soap.List l = getContentList(listId);
        String root
            = encodeDocId(l.getMetadata().getRootFolder()).getUniqueId();
        root += "/";
        String folder = encodeDocId(serverUrl).getUniqueId();
        if (!folder.startsWith(root)) {
          throw new AssertionError();
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
        response.setContentType("text/html");
        HtmlResponseWriter writer = createHtmlResponseWriter(response);
        writer.start(request.getDocId(), ObjectType.LIST_ITEM, title);
        String strAttachments = row.getAttribute(OWS_ATTACHMENTS_ATTRIBUTE);
        int attachments = strAttachments == null
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
      log.fine("Suspected attachment verified as being a real attachment. "
          + "Proceeding to provide content.");
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
      SiteDataClient client = getSiteDataClient(web.value);
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

    private Web getContentWeb(String id) throws IOException {
      log.entering("SiteDataClient", "getContentWeb", id);
      Holder<String> result = new Holder<String>();
      siteData.getContent(ObjectType.SITE, id, null, null, true, false, null,
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

    private Paginator<ItemData> getContentFolder(final String guid,
        final String url) {
      log.entering("SiteDataClient", "getContentFolder",
          new Object[] {guid, url});
      final Holder<String> lastItemIdOnPage = new Holder<String>("");
      log.exiting("SiteDataClient", "getContentFolder");
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
            String startChangeId) {
      log.entering("SiteDataClient", "getChangesContentDatabase",
          new Object[] {contentDatabaseGuid, startChangeId});
      final Holder<String> lastChangeId = new Holder<String>(startChangeId);
      final Holder<String> currentChangeId = new Holder<String>();
      log.exiting("SiteDataClient", "getChangesContentDatabase");
      return new CursorPaginator<SPContentDatabase, String>() {
        @Override
        public SPContentDatabase next() throws IOException {
          if (lastChangeId.value.equals(currentChangeId.value)) {
            return null;
          }
          Holder<String> result = new Holder<String>();
          Holder<Boolean> moreChanges = new Holder<Boolean>();
          siteData.getChanges(ObjectType.CONTENT_DATABASE, contentDatabaseGuid,
              lastChangeId, currentChangeId, 15, result, null);
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
        unmarshaller.setSchema(schema);
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

  private interface Paginator<E> {
    /**
     * Get the next page of the series. If an exception is thrown, the state of
     * the paginator is undefined.
     *
     * @return the next page of data, or {@code null} if no more pages available
     */
    public E next() throws IOException;
  }

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
    /** Non-null headers. */
    private final List<String> headers;

    private FileInfo(InputStream contents, List<String> headers) {
      this.contents = contents;
      this.headers = headers;
    }

    public InputStream getContents() {
      return contents;
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
     */
    public FileInfo issueGetRequest(URL url) throws IOException;
  }

  private static class HttpClientImpl implements HttpClient {
    @Override
    public FileInfo issueGetRequest(URL url) throws IOException {
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setDoInput(true);
      conn.setDoOutput(false);
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
    public SiteDataSoap newSiteData(String endpoint);
  }

  private static class SiteDataFactoryImpl implements SiteDataFactory {
    private final Service siteDataService;

    public SiteDataFactoryImpl() {
      URL url = SiteDataSoap.class.getResource("SiteData.wsdl");
      QName qname = new QName(XMLNS, "SiteData");
      this.siteDataService = Service.create(url, qname);
    }

    @Override
    public SiteDataSoap newSiteData(String endpoint) {
      String endpointString
          = "<wsa:EndpointReference"
          + " xmlns:wsa='http://www.w3.org/2005/08/addressing'>"
          + "<wsa:Address>" + endpoint + "</wsa:Address>"
          + "</wsa:EndpointReference>";
      EndpointReference endpointRef = EndpointReference.readFrom(
          new StreamSource(new StringReader(endpointString)));
      return siteDataService.getPort(endpointRef, SiteDataSoap.class);
    }
  }

  private static class NtlmAuthenticator extends Authenticator {
    // URLs are not comparable, so use String instead.
    private final Set<String> whitelist = new ConcurrentSkipListSet<String>();
    private final String username;
    private final char[] password;

    public NtlmAuthenticator(String username, String password) {
      this.username = username;
      this.password = password.toCharArray();
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
      String urlString = getRequestingURL().toString();
      if (whitelist.contains(urlString)) {
        return new PasswordAuthentication(username, password);
      } else {
        return super.getPasswordAuthentication();
      }
    }

    public void addToWhitelist(String url) {
      whitelist.add(url);
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
}
