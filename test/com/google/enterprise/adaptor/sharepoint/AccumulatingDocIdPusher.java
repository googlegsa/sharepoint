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
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.PushErrorHandler;

import java.util.*;

class AccumulatingDocIdPusher implements DocIdPusher {
  private List<Record> records = new ArrayList<Record>();

  @Override
  public DocId pushDocIds(Iterable<DocId> docIds)
      throws InterruptedException {
    return pushDocIds(docIds, null);
  }

  @Override
  public DocId pushDocIds(Iterable<DocId> docIds,
                          PushErrorHandler handler)
      throws InterruptedException {
    List<Record> records = new ArrayList<Record>();
    for (DocId docId : docIds) {
      records.add(new Record.Builder(docId).build());
    }
    Record record = pushRecords(records, handler);
    return record == null ? null : record.getDocId();
  }

  @Override
  public Record pushRecords(Iterable<Record> records)
      throws InterruptedException {
    return pushRecords(records, null);
  }

  @Override
  public Record pushRecords(Iterable<Record> records,
                            PushErrorHandler handler)
      throws InterruptedException {
    for (Record record : records) {
      this.records.add(record);
    }
    return null;
  }

  public List<Record> getRecords() {
    return Collections.unmodifiableList(records);
  }

  public void reset() {
    records.clear();
  }

  @Override
  public DocId pushNamedResources(Map<DocId, Acl> resources)
      throws InterruptedException {
    return pushNamedResources(resources, null);
  }

  @Override
  public DocId pushNamedResources(Map<DocId, Acl> resources,
                                  PushErrorHandler hanlder)
      throws InterruptedException {
    throw new UnsupportedOperationException();
  }
}
