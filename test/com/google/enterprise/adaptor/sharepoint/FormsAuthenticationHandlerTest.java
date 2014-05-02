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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class FormsAuthenticationHandlerTest {
  
  @Rule
  public ExpectedException thrown = ExpectedException.none();   
  
  static class UnsupportedScheduledExecutor extends CallerRunsExecutor 
      implements ScheduledExecutorService {

    public ScheduledFuture<?> schedule(Runnable command, long delay,
        TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay,
        TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
        long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
        long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException(); 
    }
    
  }
  
  static class MockScheduledExecutor 
      extends UnsupportedScheduledExecutor {
    long executionDelay;
    TimeUnit executionTimeUnit;   
    
    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay,
        TimeUnit unit) {
      executionDelay = delay;
      executionTimeUnit = unit;
      return null;
    }
  }
  
  static class MockFormsAuthenticationHandler
      extends FormsAuthenticationHandler {

    public MockFormsAuthenticationHandler(String username, String password,
        ScheduledExecutorService executor) {
      super(username, password, executor);
    }

    @Override
    boolean isFormsAuthentication() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    AuthenticationResult authenticate() throws IOException {
      throw new UnsupportedOperationException();
    }    
  }  
  
  @Test
  public void testConstructor() {
    new MockFormsAuthenticationHandler("username", "password",
        new UnsupportedScheduledExecutor());
  }
  
  @Test
  public void testNullUserName() {
    thrown.expect(NullPointerException.class);
    new MockFormsAuthenticationHandler(null, "password",
        new UnsupportedScheduledExecutor());
  }
  
  @Test
  public void testNullPassword() {
    thrown.expect(NullPointerException.class);
    new MockFormsAuthenticationHandler("username", null,
        new UnsupportedScheduledExecutor());
  }
  
  @Test
  public void testNullScheduledExecutor() {
    thrown.expect(NullPointerException.class);
    new MockFormsAuthenticationHandler("username", "password", null);
  } 
  
  @Test
  public void testEmptyUsernamePassword() throws IOException {
    FormsAuthenticationHandler formsHandler 
        = new MockFormsAuthenticationHandler("", "",
            new UnsupportedScheduledExecutor());
    formsHandler.start();    
    assertTrue(formsHandler.getAuthenticationCookies().isEmpty());
  }
  
  @Test
  public void testFormsAuthenticationNoError() throws IOException {
    MockScheduledExecutor executor = new MockScheduledExecutor();
    FormsAuthenticationHandler formsHandler 
        = new MockFormsAuthenticationHandler("username", "password", executor) {
          @Override public boolean isFormsAuthentication() throws IOException {
            return true;
          }
          @Override public AuthenticationResult authenticate()
              throws IOException {
            return new AuthenticationResult("AuthenCookie", 99, "NO_ERROR");
          }
        };
    formsHandler.start();
    assertTrue(formsHandler.isFormsAuthentication());
    assertEquals(Collections.unmodifiableList(Arrays.asList("AuthenCookie")),
        formsHandler.getAuthenticationCookies());
    assertEquals(50, executor.executionDelay);
    assertEquals(TimeUnit.SECONDS, executor.executionTimeUnit);
    executor.shutdown();
  }
  
  @Test
  public void testFormsAuthenticationPasswordMismatch() throws IOException {
    FormsAuthenticationHandler formsHandler 
        = new MockFormsAuthenticationHandler("username", "password",
            new UnsupportedScheduledExecutor()) {
          @Override public boolean isFormsAuthentication() throws IOException {
            return true;
          }
          @Override public AuthenticationResult authenticate()
              throws IOException {
            return new AuthenticationResult(null, 99, "PASSWORD_MISMATCH");
          }
        };
    formsHandler.start();
    assertTrue(formsHandler.isFormsAuthentication());
    assertTrue(formsHandler.getAuthenticationCookies().isEmpty());
  }
}
