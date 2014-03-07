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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.microsoft.schemas.sharepoint.soap.SiteDataSoap;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test cases for {@link RareModificationCache}. */
public class RareModificationCacheTest {
  private ExecutorService executor = Executors.newCachedThreadPool();
  private SiteDataSoap siteDataSoap = new DelegatingSiteData() {
    @Override
    protected SiteDataSoap delegate() {
      throw new UnsupportedOperationException();
    }
  };
  private SiteDataClient siteDataClient
      = new SiteDataClient(siteDataSoap, false);
  private RareModificationCache cache
      = new RareModificationCache(siteDataClient, executor);

  @After
  public void shutdownExecutor() {
    executor.shutdownNow();
  }

  @Test(expected = NullPointerException.class)
  public void testNullSiteDataClient() {
    new RareModificationCache(null, executor);
  }

  @Test(expected = NullPointerException.class)
  public void testNullExecutor() {
    new RareModificationCache(siteDataClient, null);
  }

  @Test
  public void testEquals() {
    SiteDataClient siteDataClient2 = new SiteDataClient(siteDataSoap, false);

    Object v1 = new RareModificationCache.VirtualServerKey(siteDataClient);
    Object w1 = new RareModificationCache.WebKey(siteDataClient);
    Object w2 = new RareModificationCache.WebKey(siteDataClient2);
    Object l1 = new RareModificationCache.ListKey(siteDataClient, "{SomeGUID}");
    Object l2
        = new RareModificationCache.ListKey(siteDataClient2, "{SomeGUID}");
    Object l3 = new RareModificationCache.ListKey(siteDataClient, "{DiffGUID}");
    Object l4 = new RareModificationCache.ListKey(siteDataClient, "{someguid}");

    assertEquals(v1, v1);
    assertEquals(v1.hashCode(), v1.hashCode());
    assertEquals(w1, w1);
    assertEquals(w1.hashCode(), w1.hashCode());
    assertEquals(l1, l1);
    assertEquals(l1.hashCode(), l1.hashCode());

    assertNotEquals(v1, w1);
    assertNotEquals(v1.hashCode(), w1.hashCode());
    assertNotEquals(v1, l1);
    assertNotEquals(v1.hashCode(), l1.hashCode());
    assertNotEquals(w1, v1);
    assertNotEquals(w1.hashCode(), v1.hashCode());
    assertNotEquals(w1, l1);
    assertNotEquals(w1.hashCode(), l1.hashCode());
    assertNotEquals(l1, v1);
    assertNotEquals(l1.hashCode(), v1.hashCode());
    assertNotEquals(l1, w1);
    assertNotEquals(l1.hashCode(), w1.hashCode());

    assertNotEquals(w1, w2);
    assertNotEquals(w1.hashCode(), w2.hashCode());
    assertNotEquals(l1, l2);
    assertNotEquals(l1.hashCode(), l2.hashCode());
    assertNotEquals(l1, l3);
    assertNotEquals(l1.hashCode(), l3.hashCode());
    assertEquals(l1, l4);
    assertEquals(l1.hashCode(), l4.hashCode());
  }

  @Test(expected = IOException.class)
  public void testGetIOException() throws IOException {
    cache.get(new RareModificationCache.CacheKey<Object>() {
      @Override
      public Object computeValue() throws IOException {
        throw new IOException();
      }
    });
  }

  @Test(expected = UncheckedExecutionException.class)
  public void testGetRuntimeException() throws IOException {
    cache.get(new RareModificationCache.CacheKey<Object>() {
      @Override
      public Object computeValue() throws IOException {
        throw new RuntimeException();
      }
    });
  }

  @Test(expected = ExecutionError.class)
  public void testGetError() throws IOException {
    cache.get(new RareModificationCache.CacheKey<Object>() {
      @Override
      public Object computeValue() throws IOException {
        throw new Error();
      }
    });
  }

  private void assertNotEquals(Object o, Object o2) {
    assertFalse(o.equals(o2));
  }
}
