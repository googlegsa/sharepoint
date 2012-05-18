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

import com.google.common.collect.Lists;
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

import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.client.Options;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.NTCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.regex.Pattern;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * SharePoint Adaptor for the GSA.
 */
public class SharePointAdaptor extends AbstractAdaptor
    implements PollingIncrementalAdaptor {
  private static final Charset CHARSET = Charset.forName("UTF-8");
  private static final QName DATA_ELEMENT
      = new QName("urn:schemas-microsoft-com:rowset", "data");
  private static final QName ROW_ELEMENT = new QName("#RowsetSchema", "row");
  private static final QName OWS_FSOBJTYPE_ATTRIBUTE
      = new QName("ows_FSObjType");
  private static final QName OWS_TITLE_ATTRIBUTE = new QName("ows_Title");
  private static final QName OWS_SERVERURL_ATTRIBUTE
      = new QName("ows_ServerUrl");
  private static final QName OWS_CONTENTTYPE_ATTRIBUTE
      = new QName("ows_ContentType");
  private static final QName OWS_ATTACHMENTS_ATTRIBUTE
      = new QName("ows_Attachments");
  private static final Pattern ALTERNATIVE_VALUE_PATTERN
      = Pattern.compile("^\\d+;#");

  private static final Logger log
      = Logger.getLogger(SharePointAdaptor.class.getName());

  private final ConcurrentMap<String, SiteDataClient> clients
      = new ConcurrentSkipListMap<String, SiteDataClient>();
  private final XMLInputFactory xmlInputFactory = XMLInputFactory.newFactory();
  private final DocId virtualServerDocId = new DocId("");
  private MultiThreadedHttpConnectionManager httpManager;
  private HttpClient httpClient;
  private AdaptorContext context;
  private String virtualServer;
  private final ConcurrentSkipListMap<String, String> contentDatabaseChangeId
      = new ConcurrentSkipListMap<String, String>();

  @Override
  public void initConfig(Config config) {
    config.addKey("sharepoint.server", null);
    config.addKey("sharepoint.username", null);
    config.addKey("sharepoint.password", null);
    config.addKey("sharepoint.domain", "");
  }

  @Override
  public void init(AdaptorContext context) throws IOException {
    this.context = context;
    Config config = context.getConfig();
    virtualServer = config.getValue("sharepoint.server");
    String username = config.getValue("sharepoint.username");
    String password = config.getValue("sharepoint.password");
    String domain = config.getValue("sharepoint.domain");

    log.log(Level.CONFIG, "VirtualServer: {0}", virtualServer);
    log.log(Level.CONFIG, "Username: {0}", username);
    log.log(Level.CONFIG, "Password: {0}", password);
    log.log(Level.CONFIG, "Domain: {0}", domain);

    httpManager = new MultiThreadedHttpConnectionManager();
    httpClient = new HttpClient(httpManager);

    Credentials creds = new NTCredentials(username, password,
        config.getServerHostname(), domain);
    httpClient.getState().setCredentials(AuthScope.ANY, creds);

    if (false) {
      contentDatabaseChangeId.put("{4fb7dea1-2912-4927-9eda-1ea2f0977cf8}",
          "1;0;4fb7dea1-2912-4927-9eda-1ea2f0977cf8;634717634720100000;597");
    }
  }

  @Override
  public void destroy() {
    httpManager.shutdown();
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
    SiteDataStub.VirtualServer vs = null;
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
        for (SiteDataStub.ContentDatabase_type0 cdT0
            : vs.getContentDatabases().getContentDatabase()) {
          discoveredContentDatabases.add(cdT0.getID());
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
      SiteDataStub.ContentDatabase cd;
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
      CursorPaginator<SiteDataStub.SPContentDatabase, String> changesPaginator
          = client.getChangesContentDatabase(contentDatabase, changeId);
      SiteDataStub.SPContentDatabase changes;
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

  private SiteDataClient getSiteDataClient(String site) throws AxisFault {
    SiteDataClient client = clients.get(site);
    if (client == null) {
      client = new SiteDataClient(site);
      clients.putIfAbsent(site, client);
      client = clients.get(site);
    }
    return client;
  }

  public static void main(String[] args) {
    AbstractAdaptor.main(new SharePointAdaptor(), args);
  }

  private class SiteDataClient {
    private static final String XMLNS
        = "http://schemas.microsoft.com/sharepoint/soap/";

    private final SiteDataStub stub;
    private final String siteUrl;

    public SiteDataClient(String site) throws AxisFault {
      log.entering("SiteDataClient", "SiteDataClient",
          new Object[] {site});
      if (!site.endsWith("/")) {
        // Always end with a '/' for a cannonical form.
        site = site + "/";
      }
      this.siteUrl = site;
      this.stub = new SiteDataStub(site + "_vti_bin/SiteData.asmx");
      Options options = stub._getServiceClient().getOptions();
      options.setProperty(HTTPConstants.CACHED_HTTP_CLIENT, httpClient);
      log.exiting("SiteDataClient", "SiteDataClient");
    }

    private void getDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getDocContent",
          new Object[] {request, response});
      String url = request.getDocId().getUniqueId();
      if (getAttachmentDocContent(request, response)) {
        // Success, it was an attachment.
        log.exiting("SiteDataClient", "getDocContent");
        return;
      }

      SiteDataStub.GetURLSegmentsResponse urlResponse
          = getUrlSegments(request.getDocId().getUniqueId());
      if (!urlResponse.getGetURLSegmentsResult()) {
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
      if (urlResponse.getStrWebID() != null) {
        getSiteDocContent(request, response, urlResponse.getStrWebID());
      } else if (urlResponse.getStrItemID() != null) {
        getListItemDocContent(request, response, urlResponse.getStrListID(),
            urlResponse.getStrItemID());
      } else if (urlResponse.getStrListID() != null) {
        getListDocContent(request, response, urlResponse.getStrListID());
      } else if (urlResponse.getStrBucketID() != null) {
        log.log(Level.FINE, "responding not found");
        response.respondNotFound();
      } else {
        // Assume it is a top-level site.
        getSiteDocContent(request, response, urlResponse.getStrWebID());
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

    private String encodeUrl(DocId docId) {
      log.entering("SiteDataClient", "encodeUrl", docId);
      URI uri = context.getDocIdEncoder().encodeDocId(docId);
      String encoded = uri.toASCIIString();
      log.exiting("SiteDataClient", "encodeUrl", encoded);
      return encoded;
    }

    private String encodeUrl(String url) {
      log.entering("SiteDataClient", "encodeUrl", url);
      URI uri = context.getDocIdEncoder().encodeDocId(encodeDocId(url));
      String encoded = uri.toASCIIString();
      log.exiting("SiteDataClient", "encodeUrl", encoded);
      return encoded;
    }

    private String liUrl(String url) {
      // TODO(ejona): Fix raw string concatenation.
      return "<li><a href=\"" + encodeUrl(url) + "\">" + url + "</a></li>";
    }

    private void getVirtualServerDocContent(Request request, Response response)
        throws IOException {
      log.entering("SiteDataClient", "getVirtualServerDocContent",
          new Object[] {request, response});
      SiteDataStub.VirtualServer vs = getContentVirtualServer();
      response.setContentType("text/html");
      Writer writer
          = new OutputStreamWriter(response.getOutputStream(), CHARSET);
      writer.write("<!DOCTYPE html>\n"
          + "<html><head>"
          + "<title>VirtualServer " + vs.getMetadata().getURL() + "</title>"
          + "</head>"
          + "<body>"
          + "<h1>VirtualServer " + vs.getMetadata().getURL() + "</h1>"
          + "<p>Sites</p>"
          + "<ul>");
      if (vs.getContentDatabases() != null) {
        DocIdEncoder encoder = context.getDocIdEncoder();
        for (SiteDataStub.ContentDatabase_type0 cdT0
            : vs.getContentDatabases().getContentDatabase()) {
          SiteDataStub.ContentDatabase cd
              = getContentContentDatabase(cdT0.getID(), true);
          if (cd.getSites() != null && cd.getSites().getSite() != null) {
            for (SiteDataStub.Site_type0 site : cd.getSites().getSite()) {
              writer.write(liUrl(site.getURL()));
            }
          }
        }
      }
      writer.write("</ul></body></html>");
      writer.flush();
      log.exiting("SiteDataClient", "getVirtualServerDocContent");
    }

    private void getSiteDocContent(Request request, Response response,
        String id) throws IOException {
      log.entering("SiteDataClient", "getSiteDocContent",
          new Object[] {request, response, id});
      SiteDataStub.Web w = getContentWeb(id);
      response.setContentType("text/html");
      Writer writer
          = new OutputStreamWriter(response.getOutputStream(), CHARSET);
      writer.write("<!DOCTYPE html>\n"
          + "<html><head>"
          + "<title>Site " + w.getMetadata().getTitle() + "</title>"
          + "</head>"
          + "<body>"
          + "<h1>Site " + w.getMetadata().getTitle() + "</h1>");

      // TODO(ejona): w.getMetadata().getNoIndex()
      DocIdEncoder encoder = context.getDocIdEncoder();
      if (w.getWebs() != null && w.getWebs().getWeb() != null) {
        writer.write("<p>Sites</p><ul>");
        for (SiteDataStub.Web_type0 web : w.getWebs().getWeb()) {
          writer.write(liUrl(web.getURL()));
        }
        writer.write("</ul>");
      }
      if (w.getLists() != null && w.getLists().getList() != null) {
        writer.write("<p>Lists</p><ul>");
        for (SiteDataStub.List_type0 list : w.getLists().getList()) {
          writer.write(liUrl(list.getDefaultViewUrl()));
        }
        writer.write("</ul>");
      }
      if (w.getFPFolder() != null) {
        SiteDataStub.FolderData f = w.getFPFolder();
        if (f.getFolders() != null) {
          writer.write("<p>Folders</p><ul>");
          for (SiteDataStub.Folders folders : f.getFolders()) {
            if (folders.getFolder() != null) {
              for (SiteDataStub.Folder_type0 folder : folders.getFolder()) {
                writer.write(liUrl(folder.getURL()));
              }
            }
          }
          writer.write("</ul>");
        }
        if (f.getFiles() != null) {
          writer.write("<p>Files</p><ul>");
          for (SiteDataStub.Files files : f.getFiles()) {
            if (files.getFile() != null) {
              for (SiteDataStub.File_type0 file : files.getFile()) {
                writer.write(liUrl(file.getURL()));
              }
            }
          }
          writer.write("</ul>");
        }
      }
      writer.write("</body></html>");
      writer.flush();
      log.exiting("SiteDataClient", "getSiteDocContent");
    }

    private void getListDocContent(Request request, Response response,
        String id) throws IOException {
      log.entering("SiteDataClient", "getListDocContent",
          new Object[] {request, response, id});
      SiteDataStub.List l = getContentList(id);
      processFolder(id, "", response);
      log.exiting("SiteDataClient", "getListDocContent");
    }

    private void processFolder(String listGuid, String folderPath,
        Response response) throws IOException {
      log.entering("SiteDataClient", "processFolder",
          new Object[] {listGuid, folderPath, response});
      response.setContentType("text/html");
      Writer writer
          = new OutputStreamWriter(response.getOutputStream(), CHARSET);
      writer.write("<!DOCTYPE html>\n"
          + "<html><head>"
          + "<title>Folder " + folderPath + "</title>"
          + "</head>"
          + "<body>"
          + "<h1>Folder " + folderPath + "</h1>");

      Paginator<SiteDataStub.Folder> folderPaginator
          = getContentFolder(listGuid, folderPath);
      writer.write("<p>List items</p><ul>");
      SiteDataStub.Folder folder;
      while ((folder = folderPaginator.next()) != null) {
        SiteDataStub.Xml xml = folder.getFolder().getXml();

        OMElement data = getFirstChildWithName(xml, DATA_ELEMENT);
        for (OMElement row : getChildrenWithName(data, ROW_ELEMENT)) {
          String rowUrl = row.getAttributeValue(OWS_SERVERURL_ATTRIBUTE);
          String rowTitle = row.getAttributeValue(OWS_TITLE_ATTRIBUTE);
          // TODO(ejona): Fix raw string concatenation.
          writer.write("<li><a href=\"" + encodeUrl(rowUrl) + "\">" + rowTitle
              + "</a></li>");
        }
      }
      writer.write("</ul>");

      writer.write("</body></html>");
      writer.flush();
      log.exiting("SiteDataClient", "processFolder");
    }

    private OMElement getFirstChildWithName(SiteDataStub.Xml xml, QName name) {
      for (OMElement child : xml.getExtraElement()) {
        if (child.getQName().equals(name)) {
          return child;
        }
      }
      return null;
    }

    private List<OMElement> getChildrenWithName(OMElement ele, QName name) {
      @SuppressWarnings("unchecked")
      Iterator<OMElement> children = ele.getChildrenWithName(ROW_ELEMENT);
      return Lists.newArrayList(children);
    }

    private List<OMAttribute> getAllAttributes(OMElement ele) {
      @SuppressWarnings("unchecked")
      Iterator<OMAttribute> attributes = ele.getAllAttributes();
      return Lists.newArrayList(attributes);
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
      String[] parts = url.split("/", 4);
      try {
        url = parts[0] + "/" + parts[1] + "/" + parts[2] + "/" +
            new URI(null, null, parts[3], null).toASCIIString();
      } catch (URISyntaxException ex) {
        throw new IOException(ex);
      }
      GetMethod method = new GetMethod(url);
      int statusCode = httpClient.executeMethod(method);
      if (statusCode != HttpStatus.SC_OK) {
        throw new IOException("Got status code: " + statusCode);
      }
      InputStream is = method.getResponseBodyAsStream();
      IOHelper.copyStream(is, response.getOutputStream());
      method.releaseConnection();
      log.exiting("SiteDataClient", "getFileDocContent");
    }

    private void getListItemDocContent(Request request, Response response,
        String listId, String itemId) throws IOException {
      log.entering("SiteDataClient", "getListItemDocContent",
          new Object[] {request, response, listId, itemId});
      SiteDataStub.ItemData i = getContentItem(listId, itemId);
      SiteDataStub.Xml xml = i.getXml();

      OMElement data = getFirstChildWithName(xml, DATA_ELEMENT);
      OMElement row = getChildrenWithName(data, ROW_ELEMENT).get(0);
      // This should be in the form of "1234;#0". We want to extract the 0.
      String type =
          row.getAttributeValue(OWS_FSOBJTYPE_ATTRIBUTE).split(";#", 2)[1];
      boolean isFolder = "1".equals(type);
      String title = row.getAttributeValue(OWS_TITLE_ATTRIBUTE);
      if (title == null) {
        title = "Unknown title";
      }
      String serverUrl = row.getAttributeValue(OWS_SERVERURL_ATTRIBUTE);

      for (OMAttribute attribute : getAllAttributes(row)) {
        addMetadata(response, attribute.getLocalName(),
            attribute.getAttributeValue());
      }

      if (isFolder) {
        SiteDataStub.List l = getContentList(listId);
        String root
            = encodeDocId(l.getMetadata().getRootFolder()).getUniqueId();
        String folder = encodeDocId(serverUrl).getUniqueId();
        if (!folder.startsWith(root)) {
          throw new AssertionError();
        }
        processFolder(listId, folder.substring(root.length()), response);
        log.exiting("SiteDataClient", "getListItemDocContent");
        return;
      }
      String contentType = row.getAttributeValue(OWS_CONTENTTYPE_ATTRIBUTE);
      // TODO(ejona): This is likely unreliable. Investigate a better way.
      if ("Document".equals(contentType)) {
        // This is a file, so display its contents.
        getFileDocContent(request, response);
      } else {
        // Some list item.
        response.setContentType("text/html");
        Writer writer
            = new OutputStreamWriter(response.getOutputStream(), CHARSET);
        writer.write("<!DOCTYPE html>\n"
            + "<html><head>"
            + "<title>List Item " + title + "</title>"
            + "</head>"
            + "<body>"
            + "<h1>List Item " + title + "</h1>");
        String strAttachments
            = row.getAttributeValue(OWS_ATTACHMENTS_ATTRIBUTE);
        int attachments = strAttachments == null
            ? 0 : Integer.parseInt(strAttachments);
        if (attachments > 0) {
          writer.write("<p>Attachments</p><ul>");
          SiteDataStub.Item item
              = getContentListItemAttachments(listId, itemId);
          if (item.getAttachment() != null) {
            for (SiteDataStub.Attachment_type0 attachment
                : item.getAttachment()) {
              writer.write(liUrl(attachment.getURL()));
            }
          }
          writer.write("</ul>");
        }
        writer.write("</body></html>");
        writer.flush();
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
      SiteDataStub.GetURLSegmentsResponse urlResponse = getUrlSegments(listUrl);
      if (!urlResponse.getGetURLSegmentsResult()) {
        log.fine("Could not get list id from list url");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      String listId = urlResponse.getStrListID();
      if (listId == null) {
        log.fine("List URL does not point to a list");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      SiteDataStub.Item item = getContentListItemAttachments(listId, itemId);
      boolean verifiedIsAttachment = false;
      if (item.getAttachment() != null) {
        for (SiteDataStub.Attachment_type0 attachment : item.getAttachment()) {
          if (url.equals(attachment.getURL())) {
            verifiedIsAttachment = true;
            break;
          }
        }
      }
      if (verifiedIsAttachment) {
        log.fine("Suspected attachment verified as being a real attachment. "
            + "Proceeding to provide content.");
      } else {
        log.fine("Suspected attachment not listed in item's attachment list");
        log.exiting("SiteDataClient", "getAttachmentDocContent", false);
        return false;
      }
      // Because SP is silly, the path of the URI is unencoded, but the rest of
      // the URI is correct. Thus, we split up the path from the host, and then
      // turn them into URIs separately, and then turn everything into a
      // properly-escaped string.
      parts = url.split("/", 4);
      String host = parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
      // host must be properly-encoded already.
      URI hostUri = URI.create(host);
      URI pathUri;
      try {
        pathUri = new URI(null, null, parts[3], null);
      } catch (URISyntaxException ex) {
        throw new IOException(ex);
      }
      url = hostUri.resolve(pathUri).toASCIIString();
      GetMethod method = new GetMethod(url);
      int statusCode = httpClient.executeMethod(method);
      if (statusCode != HttpStatus.SC_OK) {
        throw new IOException("Got status code: " + statusCode);
      }
      InputStream is = method.getResponseBodyAsStream();
      IOHelper.copyStream(is, response.getOutputStream());
      log.exiting("SiteDataClient", "getAttachmentDocContent", true);
      return true;
    }

    /**
     * @return new change id
     */
    private void getModifiedDocIds(SiteDataStub.SPContentDatabase changes,
        DocIdPusher pusher) throws IOException, InterruptedException {
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

    private void getModifiedDocIdsContentDatabase(
        SiteDataStub.SPContentDatabase changes, List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsContentDatabase",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())) {
        docIds.add(virtualServerDocId);
      }
      if (changes.getSPSite() != null) {
        for (SiteDataStub.SPSite_type0 site : changes.getSPSite()) {
          getModifiedDocIdsSite(site, docIds);
        }
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsContentDatabase");
    }

    private void getModifiedDocIdsSite(SiteDataStub.SPSite_type0 changes,
        List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsSite",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())
          && !"Delete".equals(changes.getChange())) {
        docIds.add(new DocId(changes.getSite().getMetadata().getURL()));
      }
      if (changes.getSPWeb() != null) {
        for (SiteDataStub.SPWeb_type0 web : changes.getSPWeb()) {
          getModifiedDocIdsWeb(web, docIds);
        }
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsSite");
    }

    private void getModifiedDocIdsWeb(SiteDataStub.SPWeb_type0 changes,
        List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsWeb",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())
          && !"Delete".equals(changes.getChange())) {
        docIds.add(new DocId(changes.getWeb().getMetadata().getURL()));
      }
      if (changes.getSPWebChoice_type1() != null) {
        for (SiteDataStub.SPWebChoice_type1 choice
            : changes.getSPWebChoice_type1()) {
          if (choice.getSPFolder() != null) {
            getModifiedDocIdsFolder(choice.getSPFolder(), docIds);
          }
          if (choice.getSPList() != null) {
            getModifiedDocIdsList(choice.getSPList(), docIds);
          }
          if (choice.getSPFile() != null) {
            getModifiedDocIdsFile(choice.getSPFile(), docIds);
          }
        }
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsWeb");
    }

    private void getModifiedDocIdsFolder(SiteDataStub.SPFolder_type0 changes,
        List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsFolder",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())
          && !"Delete".equals(changes.getChange())) {
        docIds.add(encodeDocId(changes.getDisplayUrl()));
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsFolder");
    }

    private void getModifiedDocIdsList(SiteDataStub.SPList_type0 changes,
        List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsList",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())
          && !"Delete".equals(changes.getChange())) {
        docIds.add(encodeDocId(changes.getDisplayUrl()));
      }
      if (changes.getSPListChoice_type0() != null) {
        for (SiteDataStub.SPListChoice_type0 choice
            : changes.getSPListChoice_type0()) {
          // Ignore view change detection.

          if (choice.getSPListItem() != null) {
            getModifiedDocIdsListItem(choice.getSPListItem(), docIds);
          }
        }
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsList");
    }

    private void getModifiedDocIdsListItem(
        SiteDataStub.SPListItem_type0 changes, List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsListItem",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())
          && !"Delete".equals(changes.getChange())) {
        docIds.add(encodeDocId(changes.getDisplayUrl()));
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsListItem");
    }

    private void getModifiedDocIdsFile(SiteDataStub.SPFile_type0 changes,
        List<DocId> docIds) {
      log.entering("SiteDataClient", "getModifiedDocIdsFile",
          new Object[] {changes, docIds});
      if (!"Unchanged".equals(changes.getChange())
          && !"Delete".equals(changes.getChange())) {
        docIds.add(encodeDocId(changes.getDisplayUrl()));
      }
      log.exiting("SiteDataClient", "getModifiedDocIdsFile");
    }

    private SiteDataStub.VirtualServer getContentVirtualServer()
        throws IOException {
      log.entering("SiteDataClient", "getContentVirtualServer");
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.VirtualServer);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      log.log(Level.FINE, "GetContent(VirtualServer): Result={0}, "
          + "LastItemIdOnPage={1}", new Object[] {
          response.getGetContentResult(), response.getLastItemIdOnPage()});
      String xml = response.getGetContentResult();
      xml = xml.replace("<VirtualServer>",
          "<VirtualServer xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = createXmlStreamReader(xml);
      SiteDataStub.VirtualServer vs;
      try {
        vs = SiteDataStub.VirtualServer.Factory.parse(reader);
      } catch (Exception ex) {
        throw new XmlProcessingException(ex);
      }
      log.exiting("SiteDataClient", "getContentVirtualServer", vs);
      return vs;
    }

    private SiteDataClient getClientForUrl(String url) throws IOException {
      log.entering("SiteDataClient", "getClientForUrl", url);
      SiteDataStub.GetSiteAndWeb request = new SiteDataStub.GetSiteAndWeb();
      request.setStrUrl(url);
      SiteDataStub.GetSiteAndWebResponse response = stub.getSiteAndWeb(request);
      log.log(Level.FINE, "GetSiteAndWeb: Result={0}, StrSite={1}, StrWeb={2}",
          new Object[] {response.getGetSiteAndWebResult(),
          response.getStrSite(), response.getStrWeb()});
      if (response.getGetSiteAndWebResult().longValue() != 0) {
        log.exiting("SiteDataClient", "getClientForUrl", null);
        return null;
      }
      SiteDataClient client = getSiteDataClient(response.getStrWeb());
      log.exiting("SiteDataClient", "getClientForUrl", client);
      return client;
    }

    private SiteDataStub.GetURLSegmentsResponse getUrlSegments(String url)
        throws IOException {
      log.entering("SiteDataClient", "getUrlSegments", url);
      SiteDataStub.GetURLSegments urlRequest
          = new SiteDataStub.GetURLSegments();
      urlRequest.setStrURL(url);
      SiteDataStub.GetURLSegmentsResponse urlResponse
          = stub.getURLSegments(urlRequest);
      log.log(Level.FINE, "GetURLSegments: Result={0}, StrWebID={1}, "
          + "StrItemID={2}, StrListID={3}, StrBucketID={4}",
          new Object[] {urlResponse.getGetURLSegmentsResult(),
            urlResponse.getStrWebID(), urlResponse.getStrListID(),
            urlResponse.getStrItemID(), urlResponse.getStrBucketID()});
      log.exiting("SiteDataClient", "getUrlSegments", urlResponse);
      return urlResponse;
    }

    private SiteDataStub.ContentDatabase getContentContentDatabase(String id,
        boolean retrieveChildItems) throws IOException {
      log.entering("SiteDataClient", "getContentContentDatabase", id);
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.ContentDatabase);
      request.setRetrieveChildItems(retrieveChildItems);
      request.setSecurityOnly(false);
      request.setObjectId(id);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      log.log(Level.FINE, "GetContent(ContentDatabase): Result={0}, "
          + "LastItemIdOnPage={1}", new Object[] {
          response.getGetContentResult(), response.getLastItemIdOnPage()});
      String xml = response.getGetContentResult();
      xml = xml.replace("<ContentDatabase>",
          "<ContentDatabase xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = createXmlStreamReader(xml);
      SiteDataStub.ContentDatabase cd;
      try {
        cd = SiteDataStub.ContentDatabase.Factory.parse(reader);
      } catch (Exception ex) {
        throw new XmlProcessingException(ex);
      }
      log.exiting("SiteDataClient", "getContentContentDatabase", cd);
      return cd;
    }

    private SiteDataStub.Web getContentWeb(String id) throws IOException {
      log.entering("SiteDataClient", "getContentWeb", id);
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.Site);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setObjectId(id);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      log.log(Level.FINE, "GetContent(Site): Result={0}, LastItemIdOnPage={1}",
          new Object[] {response.getGetContentResult(),
          response.getLastItemIdOnPage()});
      String xml = response.getGetContentResult();
      xml = xml.replace("<Web>", "<Web xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = createXmlStreamReader(xml);
      SiteDataStub.Web web;
      try {
        web = SiteDataStub.Web.Factory.parse(reader);
      } catch (Exception ex) {
        throw new XmlProcessingException(ex);
      }
      log.exiting("SiteDataClient", "getContentWeb", web);
      return web;
    }

    private SiteDataStub.List getContentList(String id) throws IOException {
      log.entering("SiteDataClient", "getContentList", id);
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.List);
      request.setRetrieveChildItems(false);
      request.setSecurityOnly(false);
      request.setObjectId(id);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      log.log(Level.FINE, "GetContent(List): Result={0}, LastItemIdOnPage={1}",
          new Object[] {response.getGetContentResult(),
          response.getLastItemIdOnPage()});
      String xml = response.getGetContentResult();
      xml = xml.replace("<List>", "<List xmlns='" + XMLNS + "'>");
      XMLStreamReader reader = createXmlStreamReader(xml);
      SiteDataStub.List list;
      try {
        list = SiteDataStub.List.Factory.parse(reader);
      } catch (Exception ex) {
        throw new XmlProcessingException(ex);
      }
      log.exiting("SiteDataClient", "getContentList", list);
      return list;
    }

    private SiteDataStub.ItemData getContentItem(String listId, String itemId)
        throws IOException {
      log.entering("SiteDataClient", "getContentItem",
          new Object[] {listId, itemId});
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.ListItem);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setObjectId(listId);
      request.setFolderUrl("");
      request.setItemId(itemId);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      log.log(Level.FINE, "GetContent(ListItem): Result={0}, "
          + "LastItemIdOnPage={1}", new Object[] {
          response.getGetContentResult(), response.getLastItemIdOnPage()});
      String xml = response.getGetContentResult();
      xml = xml.replace("<Item>", "<ItemData xmlns='" + XMLNS + "'>");
      xml = xml.replace("</Item>", "</ItemData>");
      XMLStreamReader reader = createXmlStreamReader(xml);
      SiteDataStub.ItemData data;
      try {
        data = SiteDataStub.ItemData.Factory.parse(reader);
      } catch (Exception ex) {
        throw new XmlProcessingException(ex);
      }
      log.exiting("SiteDataClient", "getContentItem", data);
      return data;
    }

    private Paginator<SiteDataStub.Folder> getContentFolder(String guid,
        String url) {
      log.entering("SiteDataClient", "getContentFolder",
          new Object[] {guid, url});
      final SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.Folder);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setFolderUrl(url);
      request.setObjectId(guid);
      request.setLastItemIdOnPage("");
      log.exiting("SiteDataClient", "getContentFolder");
      return new Paginator<SiteDataStub.Folder>() {
        @Override
        public SiteDataStub.Folder next() throws IOException {
          if (request.getLastItemIdOnPage() == null) {
            return null;
          }
          log.log(Level.FINE, "GetContent request: ObjectType={0}, "
              + "ObjectId={1}, LastItemIdOnPage={2}, RetrieveChildItems={3}, "
              + "FolderUrl={4}", new Object[] {request.getObjectType(),
              request.getObjectId(), request.getLastItemIdOnPage(),
              request.getRetrieveChildItems(), request.getFolderUrl()});
          SiteDataStub.GetContentResponse response = stub.getContent(request);
          log.log(Level.FINE, "GetContent(Folder): Result={0}, "
              + "LastItemIdOnPage={1}", new Object[] {
              response.getGetContentResult(), response.getLastItemIdOnPage()});
          request.setLastItemIdOnPage(response.getLastItemIdOnPage());
          String xml = response.getGetContentResult();
          xml = xml.replace("<Folder>", "<Folder xmlns='" + XMLNS + "'>");
          XMLStreamReader reader = createXmlStreamReader(xml);
          try {
            return SiteDataStub.Folder.Factory.parse(reader);
          } catch (Exception ex) {
            throw new XmlProcessingException(ex);
          }
        }
      };
    }

    private SiteDataStub.Item getContentListItemAttachments(String listId,
        String itemId) throws IOException {
      log.entering("SiteDataClient", "getContentListItemAttachments",
          new Object[] {listId, itemId});
      SiteDataStub.GetContent request = new SiteDataStub.GetContent();
      request.setObjectType(SiteDataStub.ObjectType.ListItemAttachments);
      request.setRetrieveChildItems(true);
      request.setSecurityOnly(false);
      request.setObjectId(listId);
      request.setFolderUrl("");
      request.setItemId(itemId);
      SiteDataStub.GetContentResponse response = stub.getContent(request);
      log.log(Level.FINE, "GetContent(ListItemAttachments): Result={0}, "
          + "LastItemIdOnPage={1}", new Object[] {
          response.getGetContentResult(), response.getLastItemIdOnPage()});
      String xml = response.getGetContentResult();
      xml = xml.replace("<Item ", "<Item xmlns='" + XMLNS + "' ");
      XMLStreamReader reader = createXmlStreamReader(xml);
      SiteDataStub.Item item;
      try {
        item = SiteDataStub.Item.Factory.parse(reader);
      } catch (Exception ex) {
        throw new XmlProcessingException(ex);
      }
      log.exiting("SiteDataClient", "getContentListItemAttachments", item);
      return item;
    }

    /**
     * Get a paginator that allows looping over all the changes since {@code
     * startChangeId}. If next() throws an XmlProcessingException, it is
     * guaranteed to be after state has been updated so that a subsequent call
     * to next() will provide the next page and not repeat the erroring page.
     */
    private CursorPaginator<SiteDataStub.SPContentDatabase, String>
        getChangesContentDatabase(String contentDatabaseGuid,
            String startChangeId) {
      log.entering("SiteDataClient", "getChangesContentDatabase",
          new Object[] {contentDatabaseGuid, startChangeId});
      final SiteDataStub.GetChanges request = new SiteDataStub.GetChanges();
      request.setObjectType(SiteDataStub.ObjectType.ContentDatabase);
      request.setContentDatabaseId(contentDatabaseGuid);
      request.setLastChangeId(startChangeId);
      request.setTimeout(15);
      log.exiting("SiteDataClient", "getChangesContentDatabase");
      return new CursorPaginator<SiteDataStub.SPContentDatabase, String>() {
        @Override
        public SiteDataStub.SPContentDatabase next() throws IOException {
          if (request.getLastChangeId().equals(request.getCurrentChangeId())) {
            return null;
          }
          log.log(Level.FINE, "Request: ObjectType={0}, ContentDatabaseId={1}, "
              + "LastChangeId={2}, CurrentChangeId={3}, Timeout={4}",
              new Object[] {request.getObjectType(),
                request.getContentDatabaseId(), request.getLastChangeId(),
                request.getCurrentChangeId(), request.getTimeout()});
          SiteDataStub.GetChangesResponse response = stub.getChanges(request);
          log.log(Level.FINE, "GetChanges(ContentDatabase): Result={0}, "
              + "MoreChanges={1}, CurrentChangeId={2}, LastChangeId={3}",
              new Object[] {
                response.getGetChangesResult(), response.getMoreChanges(),
                response.getCurrentChangeId(), response.getLastChangeId()});
          // Update state for next iteration.
          request.setLastChangeId(response.getLastChangeId());
          request.setCurrentChangeId(response.getCurrentChangeId());
          // XmlProcessingExceptions fine after this point.
          String xml = response.getGetChangesResult();
          xml = xml.replace("<SPContentDatabase ",
              "<SPContentDatabase xmlns='" + XMLNS + "' ");
          XMLStreamReader reader = createXmlStreamReader(xml);
          try {
            return SiteDataStub.SPContentDatabase.Factory.parse(reader);
          } catch (Exception ex) {
            throw new XmlProcessingException(ex);
          }
        }

        @Override
        public String getCursor() {
          return request.getLastChangeId();
        }
      };
    }

    private XMLStreamReader createXmlStreamReader(String xml)
        throws IOException {
      try {
        return xmlInputFactory.createXMLStreamReader(new StringReader(xml));
      } catch (XMLStreamException xse) {
        throw new XmlProcessingException(xse);
      }
    }
  }

  /**
   * Container exception for wrapping xml processing exceptions in IOExceptions.
   */
  private class XmlProcessingException extends IOException {
    public XmlProcessingException(Throwable cause) {
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
}
