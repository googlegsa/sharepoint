package com.google.enterprise.adaptor.sharepoint;

import com.google.common.base.Preconditions;

import java.io.IOException;

/*
 * ActiveDirectoryClient factory to get instance of
 * {@link ActiveDirectoryClient}
 */
public interface ActiveDirectoryClientFactory {

  public ActiveDirectoryClient newActiveDirectoryClient(String host,
      int port, String username, String password, String method)
          throws IOException;

  public static class ActiveDirectoryClientFactoryImpl implements
      ActiveDirectoryClientFactory {
    @Override
    public ActiveDirectoryClient newActiveDirectoryClient(String host, int port,
        String username, String password, String method) throws IOException {
      Preconditions.checkNotNull(host);
      Preconditions.checkArgument(!("".equals(host)));
      Preconditions.checkNotNull(username);
      Preconditions.checkArgument(!("".equals(username)));
      Preconditions.checkNotNull(password);
      Preconditions.checkArgument(!("".equals(password)));
      Preconditions.checkArgument(port > 0);
      return ActiveDirectoryClient.getInstance(host, port,
          username, password, method);
    }
  }
}
