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

import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.Metadata;
import com.google.enterprise.adaptor.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class GetContentsResponse implements Response {
  private OutputStream os;
  private String contentType;
  private Metadata metadata = new Metadata();
  private Acl acl;
  private boolean secure;
  private Date lastModified;
  private List<URI> anchorUris = new ArrayList<URI>();
  private List<String> anchorTexts = new ArrayList<String>();
  private boolean notFound;
  private boolean noIndex;
  private boolean noFollow;
  private boolean noArchive;
  private boolean lock;
  private boolean crawlOnce;
  private URI displayUrl;
  private Map<String, Acl> namedResources = new HashMap<String, Acl>();
  private boolean noContent;

  public GetContentsResponse(OutputStream os) {
    this.os = os;
  }

  @Override
  public void respondNotModified() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void respondNotFound() {
    notFound = true;
  }
  
  @Override
  public void respondNoContent() throws IOException {    
    noContent = true;
  }

  @Override
  public OutputStream getOutputStream() {
    return os;
  }

  @Override
  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  @Override
  public void addMetadata(String key, String value) {
    this.metadata.add(key, value);
  }

  @Override
  public void setAcl(Acl acl) {
    this.acl = acl;
  }

  @Override
  public void setSecure(boolean secure) {
    this.secure = secure;
  }

  @Override
  public void setLastModified(Date lastModified) {
    this.lastModified = lastModified;
  }

  @Override
  public void addAnchor(URI uri, String text) {
    anchorUris.add(uri);
    anchorTexts.add(text);
  }

  @Override
  public void setNoIndex(boolean noIndex) {
    this.noIndex = noIndex;
  }

  @Override
  public void setNoFollow(boolean noFollow) {
    this.noFollow = noFollow;
  }

  @Override
  public void setNoArchive(boolean noArchive) {
    this.noArchive = noArchive;
  }

  @Override
  public void setLock(boolean lock) {
    this.lock = lock;
  }

  @Override
  public void setCrawlOnce(boolean crawlOnce) {
    this.crawlOnce = crawlOnce;
  }

  @Override
  public void setDisplayUrl(URI displayUrl) {
    this.displayUrl = displayUrl;
  }

  @Override
  public void putNamedResource(String fragment, Acl acl) {
    namedResources.put(fragment, acl);
  }

  public String getContentType() {
    return contentType;
  }

  /** Returns reference to modifiable accumulated metadata. */
  public Metadata getMetadata() {
    return metadata;
  }

  public Acl getAcl() {
    return acl;
  }

  public Date getLastModified() {
    return lastModified;
  }

  public List<URI> getAnchorUris() {
    return anchorUris;
  }

  public List<String> getAnchorTexts() {
    return anchorTexts;
  }

  public boolean isNotFound() {
    return notFound;
  }

  public boolean isNoIndex() {
    return noIndex;
  }

  public boolean isNoFollow() {
    return noFollow;
  }

  public boolean isNoArchive() {
    return noArchive;
  }

  public boolean isLock() {
    return lock;
  }

  public boolean isCrawlOnce() {
    return crawlOnce;
  }

  public URI getDisplayUrl() {
    return displayUrl;
  }

  public Map<String, Acl> getNamedResources() {
    return namedResources;
  }
  
  public boolean isNoContent() {
    return noContent;
  }
 
}
