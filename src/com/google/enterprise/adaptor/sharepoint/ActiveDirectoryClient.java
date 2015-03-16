package com.google.enterprise.adaptor.sharepoint;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.io.IOException;
import java.util.Hashtable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

/*
 * ActiveDirectory client to convert SID to corresponding domain\\accountname
 * format.
 */
public class ActiveDirectoryClient {
  private static final Logger log =
      Logger.getLogger(ActiveDirectoryClient.class.getName());
  private final ADServer adServer;
  private final LoadingCache<String, String> cache =
      CacheBuilder.newBuilder()
  // Cache will auto expire in 30 minutes after initial write or update.
  .expireAfterWrite(30, TimeUnit.MINUTES)
  .build(new CacheLoader<String, String>() {
    @Override
    public String load(String key) throws IOException {
      log.log(Level.FINE, "Performing SID lookup for {0}", key);
      String resolved = adServer.getUserAccountBySid(key);
      log.log(Level.FINE, "SID {0} resolved to {1}",
          new Object[] {key, resolved});
      if (resolved == null) {
        // CacheBuilder doesn't allow to return null here.
        // Throwing IOEXception will result in repeated attempts
        // to resolve unknown SID. To avoid repeated attempts to resolve
        // SID, returning empty string here.
        log.log(Level.WARNING, "Could not resolve SID {0}."
            + "Returning empty string", key);
        return "";
      }
      return resolved;
    }
  });

  public String getUserAccountBySid(String sid) throws IOException {
    Preconditions.checkNotNull(sid);
    Preconditions.checkArgument(sid.startsWith("S-1-")
        || sid.startsWith("s-1-"), "Invalid SID: %s", sid);
    try {
      String domainSid = sid.substring(0, sid.lastIndexOf("-"));
      String domain = cache.get(domainSid);
      if ("".equals(domain)) {
        log.log(Level.WARNING, "Could not resolve domain for domain SID {0}."
            + " Returning null as account name for SID {1}",
            new Object[] {domainSid, sid});
        return null;
      }
      String accountname = cache.get(sid);
      if ("".equals(accountname)) {
        log.log(Level.WARNING, "Could not resolve accountname for SID {0}."
            + " Returning null as account name.", sid);
        return null;
      }

      String logonName = domain + "\\" + accountname;
      log.log(Level.FINE, "Returning logon name as {0} for SID {1}",
          new Object[] {logonName, sid});
      return logonName;
    } catch (ExecutionException e) {
      throw new IOException(e);
    }
  }

  public static ActiveDirectoryClient getInstance(String host, int port,
      String username, String password, String method) throws IOException {
    return new ActiveDirectoryClient(new ADServerImpl(
        host, port, username, password, method));
  }

  @VisibleForTesting
  ActiveDirectoryClient(ADServer adServer) throws IOException {
    Preconditions.checkNotNull(adServer);
    this.adServer = adServer;
    adServer.start();
  }

  interface ADServer {
    /*
     * Resolves input SID to user account name. Returns null if SID is not 
     * available.
     */
    public String getUserAccountBySid(String sid) throws IOException;
    
    /*
     * Initializes LDAP Context and verifies that successful connection can
     * established with AD server using provided connection properties.
     */
    public void start() throws IOException;
  }

  static class ADServerImpl implements ADServer {
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final SearchControls searchCtls;

    private final String[] attributes = new String[] {
        "sAMAccountName", "name" };

    private volatile LdapContext context;
    private String dn;

    ADServerImpl(String host, int port, String username, String password,
        String method) {
      Preconditions.checkNotNull(host);
      Preconditions.checkArgument(!("".equals(host)));
      Preconditions.checkNotNull(username);
      Preconditions.checkArgument(!("".equals(username)));
      Preconditions.checkNotNull(password);
      Preconditions.checkArgument(!("".equals(password)));
      Preconditions.checkArgument(port > 0);
      this.host = host;
      this.port = port;
      this.username = username;
      this.password = password;
      this.protocol = "ssl".equalsIgnoreCase(method) ? "ldaps" : "ldap";
      this.searchCtls = new SearchControls();
      searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
      searchCtls.setReturningAttributes(attributes);
    }

    @Override
    public String getUserAccountBySid(String sid) throws IOException {      
      Preconditions.checkNotNull(sid);
      Preconditions.checkArgument(sid.startsWith("S-1-")
          || sid.startsWith("s-1-"), "Invalid SID: %s", sid);      
      refreshConnection();
      String query = String.format("(objectSid=%s)", sid);
      String searchBase = (port == 389 || port == 636) ? dn : "";
      log.log(Level.FINE, "Querying host {0} on port {1} with query {2} and"
          + " search base {3}", new Object[] {host, port, query, searchBase});
      try {
        NamingEnumeration<SearchResult> results = context.search(searchBase,
            query, searchCtls);
        if (!results.hasMoreElements()) {
          log.log(Level.WARNING, "No result found on host {0} on port {1}"
              + " with query {2} and search base {3}. Returing null.",
              new Object[] {host, port, query, searchBase});
          return null;
        }
        SearchResult sr = results.next();
        Attributes attrbs = sr.getAttributes();
        // use sAMAccountName when available
        String sAMAccountName = (String) getAttribute(attrbs, "sAMAccountName");
        if (!Strings.isNullOrEmpty(sAMAccountName)) {
          return sAMAccountName;
        }
        log.log(Level.FINER, "sAMAccountName is null for SID {0}. This might"
            + " be domain object.", sid);
        String name = (String) getAttribute(attrbs, "name");
        if (!Strings.isNullOrEmpty(name)) {
          return name;
        }
        log.log(Level.WARNING, "name is null for SID {0}. Returing null.", sid);
        return null;
      } catch (NamingException ne) {
        throw new IOException(ne);
      }
    }

    @Override
    public void start() throws IOException {
      initializeContext();
      refreshConnection();
    }

    private synchronized void initializeContext() throws IOException {
      // Check if current context is still useful by calling 
      // context.getAttributes.
      if (context != null) {
        try {
          context.getAttributes("");
          return;
        } catch (NamingException ignore) {
          ignore = null;
        }
      }
      Hashtable<String, String> env = new Hashtable<String, String>();
      env.put(Context.INITIAL_CONTEXT_FACTORY,
          "com.sun.jndi.ldap.LdapCtxFactory");
      env.put("com.sun.jndi.ldap.read.timeout", "90000");
      env.put(Context.SECURITY_AUTHENTICATION, "simple");
      env.put(Context.SECURITY_PRINCIPAL, username);
      env.put(Context.SECURITY_CREDENTIALS, password);
      String ldapUrl = String.format("%s://%s:%d", protocol, host, port);
      env.put(Context.PROVIDER_URL, ldapUrl);
      try {
        context = new InitialLdapContext(env, null);
      } catch (NamingException ne) {
        throw new IOException(ne);
      }
    }
    
    private void refreshConnection() throws IOException {
      refreshConnection(true);
    }
    private void refreshConnection(boolean retry) throws IOException {
      if (context == null) {
        throw new IOException("LDAP Context not initialized.");
      }
      try {
        Attributes attributes = context.getAttributes("");
        dn = (String) getAttribute(attributes, "defaultNamingContext");
      } catch (CommunicationException ce) {
        if (retry) {
          log.log(Level.INFO, "Error refreshing LDAP connection to host {0}"
              + " on port {1} for SID lookup. Retrying.",
              new Object[] {host, port});
          initializeContext();
          refreshConnection(false);
        } else {
          throw new IOException(ce);
        }
      } catch (NamingException ne) {
        if (retry) {
          log.log(Level.INFO, "Error refreshing LDAP connection to host {0}"
              + " on port {1} for SID lookup. Retrying.",
              new Object[] {host, port});
          initializeContext();
          refreshConnection(false);
        } else {
          throw new IOException(ne);
        }
      }
    }

    private Object getAttribute(Attributes attributes, String name)
        throws NamingException {
      Attribute attribute = attributes.get(name);
      if (attribute != null) {
        return attribute.get(0);
      } else {
        return null;
      }
    }
  }
}

