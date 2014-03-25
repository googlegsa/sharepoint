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

public class AuthenticationResult {
  private final String cookie;
  private final int cookieTimeOut;
  private final String errorCode;

  public AuthenticationResult(String cookie,
      int cookieTimeOut, String errorCode) {
    if (cookieTimeOut <= 0) {
      throw new IllegalArgumentException();
    }
    this.cookie = cookie;
    this.cookieTimeOut = cookieTimeOut;
    this.errorCode = errorCode;      
  }

  public String getCookie() {
    return cookie;
  }

  public int getCookieTimeOut() {
    return cookieTimeOut;
  }

  public String getErrorCode() {
    return errorCode;
  }
}
