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

import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.AsyncDocIdPusher;
import com.google.enterprise.adaptor.AuthnAuthority;
import com.google.enterprise.adaptor.AuthzAuthority;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdEncoder;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.ExceptionHandler;
import com.google.enterprise.adaptor.PollingIncrementalLister;
import com.google.enterprise.adaptor.SensitiveValueDecoder;
import com.google.enterprise.adaptor.Session;
import com.google.enterprise.adaptor.StatusSource;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Mock AdaptorContext.
 */
class MockAdaptorContext implements AdaptorContext {
  private final DocIdEncoder docIdEncoder = new DocIdEncoder() {
    @Override
    public URI encodeDocId(DocId docId) {
      URI base = URI.create("http://localhost/");
      URI resource;
      try {
        resource = new URI(null, null, "/" + docId.getUniqueId(), null);
      } catch (URISyntaxException ex) {
        throw new AssertionError();
      }
      return base.resolve(resource);
    }
  };
  private final Config config;
  private final DocIdPusher pusher;
  private final SensitiveValueDecoder sensitiveValueDecoder
      = new SensitiveValueDecoder() {
    @Override
    public String decodeValue(String nonReadable) {
      return nonReadable;
    }
  };
  private PollingIncrementalLister pollingIncrementalLister;
  
  private final AccumulatingAsyncDocIdPusher asynPusher;

  public MockAdaptorContext(Config config, DocIdPusher pusher) {
    if (config == null) {
      throw new NullPointerException();
    }
    this.config = config;
    this.pusher = pusher;
    this.asynPusher = new AccumulatingAsyncDocIdPusher(pusher);
  }

  @Override
  public void addStatusSource(StatusSource source) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Config getConfig() {
    return config;
  }

  @Override
  public DocIdEncoder getDocIdEncoder() {
    return docIdEncoder;
  }

  @Override
  public DocIdPusher getDocIdPusher() {
    if (pusher == null) {
      throw new UnsupportedOperationException();
    } else {
      return pusher;
    }
  }

  @Override
  public ExceptionHandler getGetDocIdsFullErrorHandler() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SensitiveValueDecoder getSensitiveValueDecoder() {
    return sensitiveValueDecoder;
  }

  @Override
  public void setGetDocIdsFullErrorHandler(ExceptionHandler handler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Session getUserSession(HttpExchange ex, boolean create) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpContext createHttpContext(String path, HttpHandler handler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ExceptionHandler getGetDocIdsIncrementalErrorHandler() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setGetDocIdsIncrementalErrorHandler(ExceptionHandler h) {
    throw new UnsupportedOperationException();
  }

  @Override
  public synchronized void setPollingIncrementalLister(
      PollingIncrementalLister lister) {
    this.pollingIncrementalLister = lister;
  }

  public synchronized PollingIncrementalLister getPollingIncrementalLister() {
    return pollingIncrementalLister;
  }

  @Override
  public void setAuthzAuthority(AuthzAuthority authzAuthority) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAuthnAuthority(AuthnAuthority authnAuthority) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AsyncDocIdPusher getAsyncDocIdPusher() {
    return asynPusher;
  }
}
