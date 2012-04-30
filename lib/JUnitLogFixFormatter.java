// Copyright 2011 Google Inc. All Rights Reserved.
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

import junit.framework.AssertionFailedError;
import junit.framework.Test;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitResultFormatter;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitTest;

import java.io.*;
import java.util.*;
import java.util.logging.*;

/**
 * Ant formatter for fixing logging output when runing multiple JUnit tests in
 * the same JVM.
 *
 * <p>The default {@link ConsoleHandler} caches the value of {@code System.err}.
 * The Ant task changes {@code System.err} after every test. Thus, only the
 * first test gets logging logged when running JUnit tests from Ant. This
 * formatter changes swaps out any ConsoleHandlers with new console handlers
 * that have the correct {@code System.err} reference, and the swaps them back
 * after the test is over.
 */
public class JUnitLogFixFormatter implements JUnitResultFormatter {
  private List<ConsoleHandler> removedHandlers
      = new ArrayList<ConsoleHandler>();
  private List<ConsoleHandler> addedHandlers = new ArrayList<ConsoleHandler>();

  @Override
  public void startTestSuite(JUnitTest suite) throws BuildException {
    if (removedHandlers.size() != 0) {
      throw new IllegalStateException("removedHandlers must be empty");
    }
    if (addedHandlers.size() != 0) {
      throw new IllegalStateException("addedHandlers must be empty");
    }

    Logger root = Logger.getLogger("");
    for (Handler handler : root.getHandlers()) {
      if (handler instanceof ConsoleHandler) {
        removedHandlers.add((ConsoleHandler) handler);
      }
    }

    for (ConsoleHandler oldHandler : removedHandlers) {
      ConsoleHandler newHandler = new ConsoleHandler();
      newHandler.setLevel(oldHandler.getLevel());
      newHandler.setFilter(oldHandler.getFilter());
      newHandler.setFormatter(oldHandler.getFormatter());
      try {
        newHandler.setEncoding(oldHandler.getEncoding());
      } catch (UnsupportedEncodingException ex) {
        throw new IllegalStateException(ex);
      }
      newHandler.setErrorManager(oldHandler.getErrorManager());
      root.addHandler(newHandler);
      addedHandlers.add(newHandler);
      root.removeHandler(oldHandler);
    }
  }

  @Override
  public void endTestSuite(JUnitTest suite) throws BuildException {
    Logger root = Logger.getLogger("");
    for (ConsoleHandler handler : removedHandlers) {
      root.addHandler(handler);
    }
    removedHandlers.clear();

    for (ConsoleHandler handler : addedHandlers) {
      root.removeHandler(handler);
    }
    addedHandlers.clear();
  }

  @Override
  public void setOutput(OutputStream out) {}

  @Override
  public void setSystemError(String err) {}

  @Override
  public void setSystemOutput(String out) {}

  @Override
  public void addError(Test test, Throwable t) {}

  @Override
  public void addFailure(Test test, AssertionFailedError t) {}

  @Override
  public void endTest(Test test) {}

  @Override
  public void startTest(Test test) {}
}
