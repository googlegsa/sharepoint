// Copyright 2014 Google Inc. All Rights Reserved.
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
import com.google.enterprise.adaptor.AsyncDocIdPusher;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;

import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AccumulatingAsyncDocIdPusher implements AsyncDocIdPusher{
  private static final Logger log
      = Logger.getLogger(AccumulatingAsyncDocIdPusher.class.getName());

  private final DocIdPusher pusher;
  AccumulatingAsyncDocIdPusher(DocIdPusher pusher) {
    if (pusher == null) {
      throw new NullPointerException();
    }
    this.pusher = pusher;
  }
  @Override
  public boolean pushDocId(DocId docid) {
    try {
      pusher.pushDocIds(Collections.singletonList(docid));
      return true;
    } catch (InterruptedException e) {
      log.log(Level.WARNING, "Interrupted during pushDocId", e);
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public boolean pushRecord(DocIdPusher.Record record) {
    try {
      pusher.pushRecords(Collections.singletonList(record));
      return true;
    } catch (InterruptedException e) {
      log.log(Level.WARNING, "Interrupted during pushRecord", e);
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public boolean pushNamedResource(DocId docid, Acl acl) {
    try {
      pusher.pushNamedResources(Collections.singletonMap(docid, acl));
      return true;
    } catch (InterruptedException e) {
      log.log(Level.WARNING, "Interrupted during pushNamedResource", e);
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
