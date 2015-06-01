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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.xml.ws.Holder;

/**
 * Test cases for {@link LoggingWSHandler}.
 */
public class LoggingWSHandlerTest {
  private Logger logger = Logger.getLogger(LoggingWSHandler.class.getName());
  private LoggingHandler logLog = new LoggingHandler();
  private Level oldLevel;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void installLoggingHandler() {
    logLog.setLevel(Level.FINEST);
    logger.addHandler(logLog);
  }

  private void setLevel(Level level) {
    oldLevel = logger.getLevel();
    logger.setLevel(level);
  }

  @After
  public void restoreLogLevel() {
    logger.removeHandler(logLog);
    if (oldLevel != null) {
      logger.setLevel(oldLevel);
    }
  }

  @Test
  public void testPlain() {
    setLevel(Level.FINEST);
    LoggingWSHandler.create(PlainInterface.class, new PlainClass())
        .normalMethod("should not appear");
    assertEquals(0, logLog.getLog().size());
  }

  @Test
  public void testBasicFlow() {
    setLevel(Level.FINEST);
    Holder<Long> inputOutput = new Holder<Long>(2L);
    Holder<Boolean> output = new Holder<Boolean>();
    LoggingWSHandler.create(WebServiceInterface.class, new WebServiceClass())
        .webServiceMethod("input arg", inputOutput, output);
    assertEquals(3, logLog.getLog().size());
    LogRecord record = logLog.getLog().get(0);
    assertEquals("WS Request {0}: {1}", record.getMessage());
    assertArrayEquals(new Object[] {"webServiceMethod",
        "inputOnly=input arg, inputOutput=2"}, record.getParameters());
    record = logLog.getLog().get(1);
    assertEquals("WS Response {0}: {1}", record.getMessage());
    assertArrayEquals(new Object[] {"webServiceMethod",
        "inputOutput=3, outputOnly=true"}, record.getParameters());
  }

  @Test
  public void testNoLog() {
    setLevel(Level.WARNING);
    Holder<Long> inputOutput = new Holder<Long>(2L);
    Holder<Boolean> output = new Holder<Boolean>();
    LoggingWSHandler.create(WebServiceInterface.class, new WebServiceClass())
        .webServiceMethod("input arg", inputOutput, output);
    assertEquals(0, logLog.getLog().size());
  }

  @Test
  public void testNullArgs() {
    setLevel(Level.FINEST);
    LoggingWSHandler.create(WebServiceInterface.class, new WebServiceClass())
        .webServiceMethod(null, null, null);
    assertEquals(3, logLog.getLog().size());
    LogRecord record = logLog.getLog().get(0);
    assertEquals("WS Request {0}: {1}", record.getMessage());
    assertArrayEquals(new Object[] {"webServiceMethod",
        "inputOnly=null, inputOutput=<null holder>"}, record.getParameters());
    record = logLog.getLog().get(1);
    assertEquals("WS Response {0}: {1}", record.getMessage());
    assertArrayEquals(new Object[] {"webServiceMethod",
        "inputOutput=<null holder>, outputOnly=<null holder>"},
        record.getParameters());
  }

  @Test
  public void testNoArgs() {
    setLevel(Level.FINEST);
    LoggingWSHandler.create(WebServiceInterface.class, new WebServiceClass())
        .noArgMethod();
    assertEquals(3, logLog.getLog().size());
    LogRecord record = logLog.getLog().get(0);
    assertEquals("WS Request {0}: {1}", record.getMessage());
    assertArrayEquals(new Object[] {"noArgMethod", ""},
        record.getParameters());
    record = logLog.getLog().get(1);
    assertEquals("WS Response {0}: {1}", record.getMessage());
    assertArrayEquals(new Object[] {"noArgMethod", ""},
        record.getParameters());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWrongObject() {
    Class noGenericsInterface = WebServiceInterface.class;
    Object o = LoggingWSHandler.create(noGenericsInterface, new Object());
    WebServiceInterface wsi = (WebServiceInterface) o;
    thrown.expect(RuntimeException.class);
    wsi.noArgMethod();
  }

  private static interface PlainInterface {
    public void normalMethod(String arg);
  }

  private static class PlainClass implements PlainInterface {
    @Override
    public void normalMethod(String arg) {}
  }

  private static interface WebServiceInterface {
    @WebMethod(operationName = "webServiceMethod")
    public void webServiceMethod(
      @WebParam(name = "inputOnly")
      @SomeAnnotation
      String inputOnly,
      @WebParam(name = "inputOutput", mode = WebParam.Mode.INOUT)
      Holder<Long> inputOutput,
      @WebParam(name = "outputOnly", mode = WebParam.Mode.OUT)
      Holder<Boolean> outputOnly);

    @WebMethod(operationName = "noArgMethod")
    public void noArgMethod();
  }

  private static class WebServiceClass implements WebServiceInterface {
    @Override
    public void webServiceMethod(String inputOnly, Holder<Long> inputOutput,
        Holder<Boolean> outputOnly) {
      if (inputOutput != null) {
        inputOutput.value += 1;
      }
      if (outputOnly != null) {
        outputOnly.value = true;
      }
    }

    @Override
    public void noArgMethod() {}
  }

  @Retention(value = RetentionPolicy.RUNTIME)
  private @interface SomeAnnotation {}

  private static class LoggingHandler extends Handler {
    private final List<LogRecord> log = new ArrayList<LogRecord>();

    @Override
    public void close() {}

    @Override
    public void flush() {}

    @Override
    public void publish(LogRecord record) {
      log.add(record);
    }

    public List<LogRecord> getLog() {
      return log;
    }
  }
}
