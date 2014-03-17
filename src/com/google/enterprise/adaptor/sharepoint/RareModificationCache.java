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
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;

import com.microsoft.schemas.sharepoint.soap.List;
import com.microsoft.schemas.sharepoint.soap.PolicyUser;
import com.microsoft.schemas.sharepoint.soap.TrueFalseType;
import com.microsoft.schemas.sharepoint.soap.VirtualServer;
import com.microsoft.schemas.sharepoint.soap.Web;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Cache with items that rarely change, so items have a long lifetime in the
 * cache.
 */
class RareModificationCache {
  private final Executor executor;
  private final VirtualServerKey virtualServerKey;
  private final LoadingCache<CacheKey<?>, Object> cache
      = CacheBuilder.newBuilder()
      .expireAfterAccess(5, TimeUnit.MINUTES)
      .expireAfterWrite(30, TimeUnit.MINUTES)
      .build(new AsyncCacheLoader<CacheKey<?>, Object>() {
        @Override
        protected Executor executor() {
          return executor;
        }

        @Override
        public Object load(CacheKey<?> key) throws IOException {
          return key.computeValue();
        }
      });

  public RareModificationCache(SiteDataClient virtualServerSiteDataClient,
      Executor executor) {
    if (virtualServerSiteDataClient == null || executor == null) {
      throw new NullPointerException();
    }
    this.executor = executor;
    this.virtualServerKey = new VirtualServerKey(virtualServerSiteDataClient);
  }

  /**
   * Getter that handles type-safety, and expected to be used for all retrievals
   * from the cache.
   */
  @VisibleForTesting
  <T> T get(CacheKey<T> key) throws IOException {
    try {
      @SuppressWarnings("unchecked")
      T t = (T) cache.get(key);
      return t;
    } catch (ExecutionException e) {
      throw new IOException(e);
    }
  }

  public CachedVirtualServer getVirtualServer() throws IOException {
    return get(virtualServerKey);
  }

  public CachedWeb getWeb(SiteDataClient siteDataClient) throws IOException {
    return get(new WebKey(siteDataClient));
  }

  public CachedList getList(SiteDataClient siteDataClient, String listId)
      throws IOException {
    return get(new ListKey(siteDataClient, listId));
  }

  /**
   * The key used to identify a cache entry. Therefore, {@link Object#equals}
   * and {@link Object#hashCode} must be valid.
   */
  @VisibleForTesting
  interface CacheKey<V> {
    public V computeValue() throws IOException;
  }

  /**
   * Key for the Virtual Server, of which there is only one instance. Therefore,
   * it does not need to override equals and hashCode.
   */
  @VisibleForTesting
  static final class VirtualServerKey implements CacheKey<CachedVirtualServer> {
    private final SiteDataClient siteDataClient;

    public VirtualServerKey(SiteDataClient siteDataClient) {
      this.siteDataClient = siteDataClient;
    }

    @Override
    public CachedVirtualServer computeValue() throws IOException {
      return new CachedVirtualServer(siteDataClient.getContentVirtualServer());
    }
  }

  public static final class CachedVirtualServer {
    public final long anonymousDenyMask;
    public final boolean policyContainsDeny;

    private CachedVirtualServer(VirtualServer vs) {
      this.anonymousDenyMask
          = vs.getPolicies().getAnonymousDenyMask().longValue();
      boolean policyContainsDeny = false;
      for (PolicyUser policyUser : vs.getPolicies().getPolicyUser()) {
        long deny = policyUser.getDenyMask().longValue();
        // If at least one necessary bit is masked, then deny user.
        if ((SharePointAdaptor.LIST_ITEM_MASK & deny) != 0) {
          policyContainsDeny = true;
          break;
        }
      }
      this.policyContainsDeny = policyContainsDeny;
    }
  }

  @VisibleForTesting
  static final class WebKey implements CacheKey<CachedWeb> {
    private final SiteDataClient siteDataClient;

    public WebKey(SiteDataClient siteDataClient) {
      this.siteDataClient = siteDataClient;
    }

    @Override
    public CachedWeb computeValue() throws IOException {
      return new CachedWeb(siteDataClient.getContentWeb());
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof WebKey)) {
        return false;
      }
      WebKey webKey = (WebKey) o;
      return siteDataClient.equals(webKey.siteDataClient);
    }

    @Override
    public int hashCode() {
      return siteDataClient.hashCode();
    }
  }

  public static final class CachedWeb {
    public final String noIndex;
    public final TrueFalseType allowAnonymousAccess;
    public final TrueFalseType anonymousViewListItems;
    public final long anonymousPermMask;
    public final String webTitle;

    public CachedWeb(Web w) {
      this.noIndex = w.getMetadata().getNoIndex();
      this.allowAnonymousAccess = w.getMetadata().getAllowAnonymousAccess();
      this.anonymousViewListItems = w.getMetadata().getAnonymousViewListItems();
      this.anonymousPermMask
          = w.getMetadata().getAnonymousPermMask().longValue();
      this.webTitle = w.getMetadata().getTitle();
    }
  }

  @VisibleForTesting
  static final class ListKey implements CacheKey<CachedList> {
    private final SiteDataClient siteDataClient;
    private final String listId;

    public ListKey(SiteDataClient siteDataClient, String listId) {
      this.siteDataClient = siteDataClient;
      this.listId = listId.toUpperCase(Locale.ENGLISH);
    }

    @Override
    public CachedList computeValue() throws IOException {
      return new CachedList(siteDataClient.getContentList(listId));
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ListKey)) {
        return false;
      }
      ListKey listKey = (ListKey) o;
      return siteDataClient.equals(listKey.siteDataClient)
          && listId.equals(listKey.listId);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(new Object[] {siteDataClient, listId});
    }
  }

  public static final class CachedList {
    public final TrueFalseType noIndex;
    public final int readSecurity;
    public final TrueFalseType allowAnonymousAccess;
    public final TrueFalseType anonymousViewListItems;
    public final long anonymousPermMask;
    public final String rootFolder;
    public final String defaultViewUrl;
    public final String defaultViewItemUrl;
    /**
     * This field must not be used for general ACL inheritance of direct
     * decendants of the list. This is intended only for use when determining
     * whether anonymous access is permitted for list items and attachments.
     */
    public final String scopeId;

    public CachedList(List l) {
      this.noIndex = l.getMetadata().getNoIndex();
      this.readSecurity = l.getMetadata().getReadSecurity();
      this.allowAnonymousAccess = l.getMetadata().getAllowAnonymousAccess();
      this.anonymousViewListItems = l.getMetadata().getAnonymousViewListItems();
      this.anonymousPermMask
          = l.getMetadata().getAnonymousPermMask().longValue();
      this.rootFolder = l.getMetadata().getRootFolder();
      this.defaultViewUrl = "/".equals(l.getMetadata().getDefaultViewUrl()) 
          ? l.getMetadata().getRootFolder() 
          : l.getMetadata().getDefaultViewUrl();
      this.defaultViewItemUrl = l.getMetadata().getDefaultViewItemUrl();
      this.scopeId = l.getMetadata().getScopeID();
    }
  }
}
