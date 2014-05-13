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

import com.microsoft.schemas.sharepoint.soap.ArrayOfSFPUrl;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSList;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSListWithTime;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSProperty;
import com.microsoft.schemas.sharepoint.soap.ArrayOfSWebWithTime;
import com.microsoft.schemas.sharepoint.soap.ArrayOfString;
import com.microsoft.schemas.sharepoint.soap.ObjectType;
import com.microsoft.schemas.sharepoint.soap.SListMetadata;
import com.microsoft.schemas.sharepoint.soap.SSiteMetadata;
import com.microsoft.schemas.sharepoint.soap.SWebMetadata;
import com.microsoft.schemas.sharepoint.soap.SiteDataSoap;
import java.util.Map;
import javax.xml.ws.Binding;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.EndpointReference;

import javax.xml.ws.Holder;

abstract class DelegatingSiteData implements SiteDataSoap, BindingProvider {
  protected abstract SiteDataSoap delegate();

  @Override
  public void getSiteAndWeb(String strUrl, Holder<Long> getSiteAndWebResult,
      Holder<String> strSite, Holder<String> strWeb) {
    delegate().getSiteAndWeb(strUrl, getSiteAndWebResult, strSite, strWeb);
  }

  @Override
  public void getSite(Holder<Long> getSiteResult,
      Holder<SSiteMetadata> sSiteMetadata, Holder<ArrayOfSWebWithTime> vWebs,
      Holder<String> strUsers, Holder<String> strGroups,
      Holder<ArrayOfString> vGroups) {
    delegate().getSite(getSiteResult, sSiteMetadata, vWebs, strUsers,
        strGroups, vGroups);
  }

  @Override
  public void getWeb(Holder<Long> getWebResult,
      Holder<SWebMetadata> sWebMetadata, Holder<ArrayOfSWebWithTime> vWebs,
      Holder<ArrayOfSListWithTime> vLists, Holder<ArrayOfSFPUrl> vFPUrls,
      Holder<String> strRoles, Holder<ArrayOfString> vRolesUsers,
      Holder<ArrayOfString> vRolesGroups) {
    delegate().getWeb(getWebResult, sWebMetadata, vWebs, vLists, vFPUrls,
        strRoles, vRolesUsers, vRolesGroups);
  }

  @Override
  public void getList(String strListName, Holder<Long> getListResult,
      Holder<SListMetadata> sListMetadata,
      Holder<ArrayOfSProperty> vProperties) {
    delegate()
        .getList(strListName, getListResult, sListMetadata, vProperties);
  }

  @Override
  public String getListItems(String strListName, String strQuery,
      String strViewFields, long uRowLimit) {
    return delegate()
        .getListItems(strListName, strQuery, strViewFields, uRowLimit);
  }

  @Override
  public void enumerateFolder(String strFolderUrl,
      Holder<Long> enumerateFolderResult, Holder<ArrayOfSFPUrl> vUrls) {
    delegate().enumerateFolder(strFolderUrl, enumerateFolderResult, vUrls);
  }

  @Override
  public void getAttachments(String strListName, String strItemId,
      Holder<Long> getAttachmentsResult, Holder<ArrayOfString> vAttachments) {
    delegate().getAttachments(strListName, strItemId, getAttachmentsResult,
        vAttachments);
  }

  @Override
  public void getURLSegments(String strURL,
      Holder<Boolean> getURLSegmentsResult, Holder<String> strWebID,
      Holder<String> strBucketID, Holder<String> strListID,
      Holder<String> strItemID) {
    delegate().getURLSegments(strURL, getURLSegmentsResult, strWebID,
        strBucketID, strListID, strItemID);
  }

  @Override
  public void getListCollection(Holder<Long> getListCollectionResult,
      Holder<ArrayOfSList> vLists) {
    delegate().getListCollection(getListCollectionResult, vLists);
  }

  @Override
  public void getContent(ObjectType objectType, String objectId,
      String folderUrl, String itemId, boolean retrieveChildItems,
      boolean securityOnly, Holder<String> lastItemIdOnPage,
      Holder<String> getContentResult) {
    delegate().getContent(objectType, objectId, folderUrl, itemId,
        retrieveChildItems, securityOnly, lastItemIdOnPage, getContentResult);
  }

  @Override
  public void getSiteUrl(String url, Holder<Long> getSiteUrlResult,
      Holder<String> siteUrl, Holder<String> siteId) {
    delegate().getSiteUrl(url, getSiteUrlResult, siteUrl, siteId);
  }

  @Override
  public void getChanges(ObjectType objectType, String contentDatabaseId,
      Holder<String> lastChangeId, Holder<String> currentChangeId,
      Integer timeout, Holder<String> getChangesResult,
      Holder<Boolean> moreChanges) {
    delegate().getChanges(objectType, contentDatabaseId, lastChangeId,
        currentChangeId, timeout, getChangesResult, moreChanges);
  }

  @Override
  public String getChangesEx(int version, String xmlInput) {
    return delegate().getChangesEx(version, xmlInput);
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
  public <T extends EndpointReference> T getEndpointReference(Class<T> clazz) {
    return ((BindingProvider) delegate()).getEndpointReference(clazz);
  }
}


