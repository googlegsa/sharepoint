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
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdEncoder;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GetDocIdsErrorHandler;
import com.google.enterprise.adaptor.SensitiveValueDecoder;
import com.google.enterprise.adaptor.StatusSource;

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

  public MockAdaptorContext(Config config, DocIdPusher pusher) {
    if (config == null) {
      throw new NullPointerException();
    }
    this.config = config;
    this.pusher = pusher;
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
  public GetDocIdsErrorHandler getGetDocIdsErrorHandler() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SensitiveValueDecoder getSensitiveValueDecoder() {
    return sensitiveValueDecoder;
  }

  @Override
  public void removeStatusSource(StatusSource source) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setGetDocIdsErrorHandler(GetDocIdsErrorHandler handler) {
    throw new UnsupportedOperationException();
  }
}
