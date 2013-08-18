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

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.enterprise.adaptor.Principal;

import java.util.*;

/**
 * Immutable lookup from identifier to name for users and groups.
 */
class MemberIdMapping {
  private static final Interner<Principal> interner
      = Interners.newWeakInterner();

  private final Map<Integer, Principal> principals;

  public MemberIdMapping(Map<Integer, ? extends Principal> principals) {
    Map<Integer, Principal> tmp = new HashMap<Integer, Principal>(principals);
    // Most of the purpose for this class is to allow future memory
    // optimizations without having to tweak all the calling code. Thus, it
    // makes sense for this class to do intern()ing.
    for (Map.Entry<Integer, Principal> me : tmp.entrySet()) {
      me.setValue(interner.intern(me.getValue()));
    }
    this.principals = Collections.unmodifiableMap(principals);
  }

  public Principal getPrincipal(Integer id) {
    return principals.get(id);
  }

  @Override
  public String toString() {
    return "MemberIdMapping(" + principals + ")";
  }
}
