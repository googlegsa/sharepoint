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

import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.Request;

import java.util.Date;

class GetContentsRequest implements Request {
  private DocId docId;
  Date lastAccessTime;

  public GetContentsRequest(DocId docId) {
    this(docId, null);
  }
  
  public GetContentsRequest(DocId docId, Date lastAccessTime) {
    this.docId = docId;
    this.lastAccessTime = lastAccessTime;
  } 

  @Override
  public boolean hasChangedSinceLastAccess(Date lastModified) {
    if (lastAccessTime == null) {
      return true;
    }
    if (lastModified == null) {
      throw new NullPointerException("last modified is null");
    }
    Date lastModifiedAdjusted
        = new Date(1000 * (lastModified.getTime() / 1000));
    return lastAccessTime.before(lastModifiedAdjusted);
    
  }

  @Override
  public Date getLastAccessTime() {
    return lastAccessTime;
  }

  @Override
  public DocId getDocId() {
    return docId;
  }
  
  @Override
  public boolean canRespondWithNoContent(Date lastModified) {
    return !hasChangedSinceLastAccess(lastModified);
  }
}
