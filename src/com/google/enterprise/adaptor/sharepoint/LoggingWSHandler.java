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

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.xml.ws.Holder;

/**
 * InvocationHandler that can wrap WebService instances and log input/output
 * values. This is more helpful than manual logging because it checks the
 * mode of a parameter and only prints it when appropriate. The mode of a
 * parameter defines when it is sent, so this logging method accurately
 * represents the exchange of information.
 */
class LoggingWSHandler implements InvocationHandler {
  private static final Logger log
      = Logger.getLogger(LoggingWSHandler.class.getName());

  private final Object wrapped;

  public LoggingWSHandler(Object wrapped) {
    this.wrapped = wrapped;
  }

  public static <T> T create(Class<T> webServiceInterface, T wrapped) {
    InvocationHandler invokeHandler = new LoggingWSHandler(wrapped);
    Object oInstance = Proxy.newProxyInstance(
        LoggingWSHandler.class.getClassLoader(),
        new Class<?>[] {webServiceInterface}, invokeHandler);
    @SuppressWarnings("unchecked")
    T tInstance = (T) oInstance;
    return tInstance;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args)
      throws Throwable {
    final Level logLevel = Level.FINE;
    if (log.isLoggable(logLevel)) {
      WebMethod webMethod = method.getAnnotation(WebMethod.class);
      if (webMethod != null) {
        String inArgs = formArgumentString(method, args, WebParam.Mode.IN);
        log.log(logLevel, "WS Request {0}: {1}",
            new Object[] {webMethod.operationName(), inArgs});
      }
    }
    Object ret;
    try {
      ret = method.invoke(wrapped, args);
    } catch (IllegalAccessException ex) {
      throw new RuntimeException("Misconfigured LoggingWSHandler", ex);
    } catch (IllegalArgumentException ex) {
      throw new RuntimeException("Misconfigured LoggingWSHandler", ex);
    } catch (InvocationTargetException ex) {
      throw ex.getCause();
    }
    if (log.isLoggable(logLevel)) {
      WebMethod webMethod = method.getAnnotation(WebMethod.class);
      if (webMethod != null) {
        String outArgs = formArgumentString(method, args, WebParam.Mode.OUT);
        log.log(logLevel, "WS Response {0}: {1}",
            new Object[] {webMethod.operationName(), outArgs});
      }
    }
    return ret;
  }

  private String formArgumentString(Method method, Object[] args,
      WebParam.Mode mode) {
    StringBuilder argsBuffer = new StringBuilder();
    Annotation[][] annotates = method.getParameterAnnotations();
    if (annotates.length != 0 && annotates.length != args.length) {
      throw new AssertionError();
    }
    for (int i = 0; i < annotates.length; i++) {
      for (Annotation annotate : annotates[i]) {
        if (!(annotate instanceof WebParam)) {
          break;
        }
        WebParam webParam = (WebParam) annotate;
        if (webParam.mode() == mode
            || webParam.mode() == WebParam.Mode.INOUT) {
          argsBuffer.append(", ").append(webParam.name()).append("=");
          if (webParam.mode() == WebParam.Mode.IN) {
            argsBuffer.append("" + args[i]);
          } else {
            Holder<?> holder = (Holder<?>) args[i];
            argsBuffer.append(
                holder == null ? "<null holder>" : "" + holder.value);
          }
        }
      }
    }
    return argsBuffer.length() > 1 ? argsBuffer.substring(2) : "";
  }
}
