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

import static org.junit.Assert.*;

import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;

import com.microsoft.schemas.sharepoint.soap.ObjectType;

import org.junit.*;
import org.junit.rules.ExpectedException;

import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Test cases for {@link HtmlResponseWriter}.
 */
public class HtmlResponseWriterTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private MockAdaptorContext context = new MockAdaptorContext(
      new Config(), new AccumulatingDocIdPusher());
  private StringWriter stringWriter = new StringWriter();
  private HtmlResponseWriter writer = new HtmlResponseWriter(stringWriter,
      context.getDocIdEncoder(), Locale.ENGLISH);

  @Test
  public void testConstructorNullWriter() {
    thrown.expect(NullPointerException.class);
    new HtmlResponseWriter(null, context.getDocIdEncoder(), Locale.ENGLISH);
  }

  @Test
  public void testConstructorNullDocIdEncoder() {
    thrown.expect(NullPointerException.class);
    new HtmlResponseWriter(new StringWriter(), null, Locale.ENGLISH);
  }

  @Test
  public void testConstructorNullLocale() {
    thrown.expect(NullPointerException.class);
    new HtmlResponseWriter(new StringWriter(), context.getDocIdEncoder(), null);
  }

  @Test
  public void testStartTwice() throws Exception {
    writer.start(new DocId(""), ObjectType.SITE, null);
    thrown.expect(IllegalStateException.class);
    writer.start(new DocId(""), ObjectType.SITE, null);
  }

  @Test
  public void testStartSectionPremature() throws Exception {
    thrown.expect(IllegalStateException.class);
    writer.startSection(ObjectType.SITE);
  }

  @Test
  public void testAddLinkPremature() throws Exception {
    thrown.expect(IllegalStateException.class);
    writer.addLink(new DocId(""), null);
  }

  @Test
  public void testAddLinkNullDocId() throws Exception {
    writer.start(new DocId(""), ObjectType.SITE, null);
    writer.startSection(ObjectType.SITE);
    thrown.expect(NullPointerException.class);
    writer.addLink(null, null);
  }

  @Test
  public void testFinishPremature() throws Exception {
    thrown.expect(IllegalStateException.class);
    writer.finish();
  }

  @Test
  public void testFinishNoSections() throws Exception {
    final String golden = "<!DOCTYPE html>\n"
        + "<html><head><title>Site a</title></head>"
        + "<body><h1>Site a</h1></body></html>";
    writer.start(new DocId("a"), ObjectType.SITE, "");
    writer.finish();
    writer.close();
    assertEquals(golden, stringWriter.toString());
  }

  @Test
  public void testRelativizeBaseNoScheme() throws Exception {
    URI base = new URI(null, "example.com", "/", null, null);
    URI uri = new URI("http://example.com/some/where/file");
    assertEquals(uri, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeBaseNoAuthority() throws Exception {
    URI base = new URI("http", null, "/", null, null);
    URI uri = new URI("http://example.com/some/where/file");
    assertEquals(uri, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeBaseDifferentScheme() throws Exception {
    URI base = new URI("file", "example.com", "/", null, null);
    URI uri = new URI("http://example.com/some/where/file");
    assertEquals(uri, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeBaseDifferentAuthority() throws Exception {
    URI base = new URI("http", "google.com", "/", null, null);
    URI uri = new URI("http://example.com/some/where/file");
    assertEquals(uri, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeBaseMatches() throws Exception {
    URI base = new URI("http", "example.com", null, null, null);
    URI uri = new URI("http://example.com/some/where/file");
    URI golden = new URI(null, null, "/some/where/file", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeSimplePathChild() throws Exception {
    URI base = new URI("http", "example.com", "/some/where", null, null);
    URI uri = new URI("http://example.com/some/where/file");
    URI golden = new URI(null, null, "where/file", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeSimplePathParent() throws Exception {
    URI base = new URI("http", "example.com", "/some/where/file", null, null);
    URI uri = new URI("http://example.com/some/where");
    // Note that "." or "./" would not suffice, since we need "where" to not end
    // with a slash.
    URI golden = new URI(null, null, "../where", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeComplexPathFileBaseFolder()
      throws Exception {
    URI base = new URI("http", "example.com", "/some/there/", null, null);
    URI uri = new URI("http://example.com/some/where/file");
    URI golden = new URI(null, null, "../where/file", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeComplexPathFileBaseFile() throws Exception {
    URI base = new URI("http", "example.com", "/some/there/file", null, null);
    URI uri = new URI("http://example.com/some/where/file");
    URI golden = new URI(null, null, "../where/file", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeComplexPathFolderBaseFolder()
      throws Exception {
    URI base = new URI("http", "example.com", "/some/there/", null, null);
    URI uri = new URI("http://example.com/some/where/folder/");
    URI golden = new URI(null, null, "../where/folder/", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeComplexPathFolderBaseFile() throws Exception {
    URI base = new URI("http", "example.com", "/some/there/file", null, null);
    URI uri = new URI("http://example.com/some/where/folder/");
    URI golden = new URI(null, null, "../where/folder/", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeSame() throws Exception {
    URI uri = new URI("http://example.com/some/where/file");
    URI golden = new URI(null, null, null, null, "");
    assertEquals(golden, HtmlResponseWriter.relativize(uri, uri));
  }

  @Test
  public void testRelativizeNoMistakenScheme() throws Exception {
    URI base = new URI("http", "example.com", "/", null, null);
    URI uri = new URI("http://example.com/http://google.com/");
    URI golden = new URI(null, null, "./http://google.com/", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeNoMistakenSchemeNoSlash() throws Exception {
    URI base = new URI("http", "example.com", "/", null, null);
    URI uri = new URI("http://example.com/http:");
    URI golden = new URI(null, null, "./http:", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeNoMistakenSchemeSlashFirst() throws Exception {
    URI base = new URI("http", "example.com", "/", null, null);
    URI uri = new URI("http://example.com/http/:");
    URI golden = new URI(null, null, "http/:", null, null);
    assertEquals(golden, HtmlResponseWriter.relativize(base, uri));
  }

  @Test
  public void testRelativizeSchemeImpliesAbsoluteUriAssumption()
      throws Exception {
    thrown.expect(URISyntaxException.class);
    new URI("http", "example.com", "doesnt/start/with/a/slash", null, null);
  }
}
