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

import java.util.*;

/**
 * Immutable lookup from identifier to name for users and groups.
 */
class MemberIdMapping {
  private final Map<Integer, String> users;
  private final Map<Integer, String> groups;

  public MemberIdMapping(Map<Integer, String> users,
      Map<Integer, String> groups) {
    this.users
        = Collections.unmodifiableMap(new HashMap<Integer, String>(users));
    this.groups
        = Collections.unmodifiableMap(new HashMap<Integer, String>(groups));
  }

  public String getUserName(Integer id) {
    return users.get(id);
  }

  public String getGroupName(Integer id) {
    return groups.get(id);
  }

  @Override
  public String toString() {
    return "MemberIdMapping(users=" + users + ",groups=" + groups + ")";
  }
}
