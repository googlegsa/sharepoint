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

import com.google.common.annotations.VisibleForTesting;

import com.microsoft.schemas.sharepoint.soap.ContentDatabase;
import com.microsoft.schemas.sharepoint.soap.Item;
import com.microsoft.schemas.sharepoint.soap.ItemData;
import com.microsoft.schemas.sharepoint.soap.ObjectType;
import com.microsoft.schemas.sharepoint.soap.SPContentDatabase;
import com.microsoft.schemas.sharepoint.soap.Site;
import com.microsoft.schemas.sharepoint.soap.SiteDataSoap;
import com.microsoft.schemas.sharepoint.soap.VirtualServer;
import com.microsoft.schemas.sharepoint.soap.Web;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.util.logging.*;

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
import javax.xml.ws.Holder;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;

class SiteDataClient {
  /** SharePoint's namespace. */
  private static final String XMLNS
      = "http://schemas.microsoft.com/sharepoint/soap/";

  private static final Logger log
      = Logger.getLogger(SiteDataClient.class.getName());
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

  private final CheckedExceptionSiteDataSoap siteData;
  private final boolean xmlValidation;

  public SiteDataClient(SiteDataSoap siteDataSoap, boolean xmlValidation) {
    if (siteDataSoap == null) {
      throw new NullPointerException();
    }
    siteDataSoap = LoggingWSHandler.create(SiteDataSoap.class, siteDataSoap);
    this.siteData = new CheckedExceptionSiteDataSoapAdapter(siteDataSoap);
    this.xmlValidation = xmlValidation;
  }

  public long getSiteAndWeb(String strUrl, Holder<String> strSite,
      Holder<String> strWeb) throws IOException {
    Holder<Long> getSiteAndWebResult = new Holder<Long>();
    siteData.getSiteAndWeb(strUrl, getSiteAndWebResult, strSite, strWeb);
    return getSiteAndWebResult.value;
  }

  public boolean getUrlSegments(String strURL, Holder<String> strListID,
      Holder<String> strItemID) throws IOException {
    Holder<Boolean> getURLSegmentsResult = new Holder<Boolean>();
    // The returned web is not useful because we already know the web containing
    // the URL. We don't have a use for the returned bucket.
    siteData.getURLSegments(strURL, getURLSegmentsResult, null, null,
        strListID, strItemID);
    return getURLSegmentsResult.value;
  }

  public VirtualServer getContentVirtualServer() throws IOException {
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

  public ContentDatabase getContentContentDatabase(String id,
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

  public Site getContentSite() throws IOException {
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

  public Web getContentWeb() throws IOException {
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

  public com.microsoft.schemas.sharepoint.soap.List getContentList(String id)
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

  public ItemData getContentItem(String listId, String itemId)
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

  public Paginator<ItemData> getContentFolderChildren(final String guid,
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

  public Item getContentListItemAttachments(String listId, String itemId)
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
  public CursorPaginator<SPContentDatabase, String>
      getChangesContentDatabase(final String contentDatabaseGuid,
          String startChangeId, final boolean isSp2007) {
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
        // In SP 2007, the timeout is a number of seconds. In SP2010 and above,
        // the timeout is n * 60, where n is the number of items you want
        // returned. However, asking for more than 10 items seems
        // to lose results. If timeout is less than 60 in SP 2010 / 2013,
        // then it causes an infinite loop.
        int timeout = isSp2007 ? 15 : 10 * 60;
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
    if (xml.contains("&#31;")) {
      // Unit separator is sometimes present in ows_MetaInfo of the response
      // XML, but it prevents the XML from being parsed. Since we don't actually
      // care about MetaInfo we strip it out.
      xml = xml.replace("&#31;", "");
    }
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

  /**
   * Container exception for wrapping xml processing exceptions in IOExceptions.
   */
  public static class XmlProcessingException extends IOException {
    public XmlProcessingException(JAXBException cause, String xml) {
      super("Error when parsing xml: " + xml, cause);
    }
  }

  /**
   * Container exception for wrapping WebServiceExceptions in a checked
   * exception.
   */
  public static class WebServiceIOException extends IOException {
    public WebServiceIOException(WebServiceException cause) {
      super(cause);
    }
  }

  /**
   * An object that can be paged through.
   *
   * @param <E> element type returned by {@link #next}
   */
  public interface Paginator<E> {
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
  public interface CursorPaginator<E, C> extends Paginator<E> {
    /**
     * Provides a cursor for the current position. The intent is that you could
     * get a cursor (even in the event of {@link #next} throwing an exception)
     * and use it to create a query that would continue without repeating
     * results.
     */
    public C getCursor();
  }

  public static Service createSiteDataService() {
    return Service.create(
        SiteDataSoap.class.getResource("SiteData.wsdl"),
        new QName(XMLNS, "SiteData"));
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
