// Copyright 2017 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.filenet;

import static com.google.enterprise.adaptor.filenet.FileNetAdaptor.percentEscape;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.InvalidConfigurationException;
import com.google.enterprise.adaptor.SensitiveValueDecoder;

import com.filenet.api.core.ObjectStore;
import com.filenet.api.util.Id;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

// TODO(bmj): move this into FileNetAdaptor?
class ConfigOptions {
  private static final Logger logger =
      Logger.getLogger(ConfigOptions.class.getName());

  private static final Splitter splitter =
      Splitter.on(',').omitEmptyStrings().trimResults();

  private final SensitiveValueDecoder sensitiveValueDecoder;
  private final String contentEngineUrl;
  private final String username;
  private final String password;
  private final String objectStoreName;

  private final ObjectFactory objectFactory;
  private final String displayUrlPattern;
  private final boolean markAllDocsAsPublic;
  private final String additionalWhereClause;
  private final Set<String> includedMetadata;
  private final Set<String> excludedMetadata;
  private final ThreadLocal<SimpleDateFormat> metadataDateFormat;
  private final String authenticatedUsersGroup;
  private final String globalNamespace;
  private final int maxFeedUrls;

  public ConfigOptions(AdaptorContext context)
      throws InvalidConfigurationException {
    Config config = context.getConfig();
    sensitiveValueDecoder = context.getSensitiveValueDecoder();

    contentEngineUrl = config.getValue("filenet.contentEngineUrl");
    logger.log(Level.CONFIG, "filenet.contentEngineUrl: {0}", contentEngineUrl);
    try {
      new ValidatedUri(contentEngineUrl).logUnreachableHost();
    } catch (URISyntaxException e) {
      throw new InvalidConfigurationException(
          "Invalid filenet.contentEngineUrl: " + e.getMessage());
    }

    objectStoreName = config.getValue("filenet.objectStore");
    if (objectStoreName.isEmpty()) {
      throw new InvalidConfigurationException(
          "filenet.objectStore may not be empty");
    }
    logger.log(Level.CONFIG, "filenet.objectStore: {0}", objectStoreName);

    username = config.getValue("filenet.username");
    password = config.getValue("filenet.password");

    String objectFactoryName = config.getValue("filenet.objectFactory");
    if (objectFactoryName.isEmpty()) {
      throw new InvalidConfigurationException(
          "filenet.objectFactory may not be empty");
    }
    try {
      objectFactory = (ObjectFactory) Class.forName(objectFactoryName)
          .getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException | InstantiationException
             | IllegalAccessException | NoSuchMethodException e) {
      throw new InvalidConfigurationException(
          "Unable to instantiate object factory: " + objectFactoryName, e);
    } catch (InvocationTargetException e) {
      throw new InvalidConfigurationException(
          "Unable to instantiate object factory: " + objectFactoryName,
          e.getCause());
    }
    logger.log(Level.CONFIG, "filenet.objectFactory: {0}", objectFactoryName);

    // TODO(jlacey): Replace this with a MessageFormat pattern.
    String pattern = config.getValue("filenet.displayUrlPattern");
    try {
      URI uri = new URI(
          getDisplayUrl(pattern, Id.ZERO_ID, Id.ZERO_ID, objectStoreName));
      if (!uri.isAbsolute()) {
        URI ceUri = new URI(contentEngineUrl);
        pattern = ceUri.getScheme() + "://" + ceUri.getRawAuthority()
            + (pattern.startsWith("/") ? "" : "/") + pattern;
      }
      new ValidatedUri(
          getDisplayUrl(pattern, Id.ZERO_ID, Id.ZERO_ID, objectStoreName))
          .logUnreachableHost();
      this.displayUrlPattern = pattern;
      logger.log(Level.CONFIG, "displayUrlPattern: {0}", pattern);
    } catch (IllegalArgumentException | URISyntaxException e) {
      throw new InvalidConfigurationException(
          "Invalid displayUrlPattern: " + e.getMessage());
    }

    markAllDocsAsPublic =
        Boolean.parseBoolean(config.getValue("adaptor.markAllDocsAsPublic"));
    logger.log(Level.CONFIG, "adaptor.markAllDocsAsPublic: {0}",
        markAllDocsAsPublic);

    authenticatedUsersGroup =
        config.getValue("filenet.authenticatedUsersGroup");
    logger.log(Level.CONFIG, "filenet.authenticatedUsersGroup: {0}",
        authenticatedUsersGroup);

    globalNamespace = config.getValue("adaptor.namespace");
    logger.log(Level.CONFIG, "adaptor.namespace: {0}", globalNamespace);

    // TODO(bmj): validate where clauses
    additionalWhereClause = config.getValue("filenet.additionalWhereClause");
    logger.log(Level.CONFIG, "filenet.additionalWhereClause: {0}",
        additionalWhereClause);

    // TODO(bmj): validate column names?
    excludedMetadata = ImmutableSet.copyOf(
        splitter.split(config.getValue("filenet.excludedMetadata")));
    logger.log(Level.CONFIG, "filenet.excludedMetadata: {0}", excludedMetadata);

    // TODO(bmj): validate column names?
    includedMetadata = ImmutableSet.copyOf(
        splitter.split(config.getValue("filenet.includedMetadata")));
    logger.log(Level.CONFIG, "filenet.includedMetadata: {0}", includedMetadata);

    final String metadataDateFormat =
        config.getValue("filenet.metadataDateFormat");
    try {
      new SimpleDateFormat(metadataDateFormat);
    } catch (IllegalArgumentException e) {
      throw new InvalidConfigurationException(
          "Invalid filenet.metadataDateFormat value: " + e.getMessage());
    }
    logger.log(Level.CONFIG, "filenet.metadataDateFormat: {0}",
        metadataDateFormat);
    this.metadataDateFormat =
        new ThreadLocal<SimpleDateFormat>() {
          @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat(metadataDateFormat);
          }
        };

    try {
      maxFeedUrls = Integer.parseInt(config.getValue("feed.maxUrls"));
      if (maxFeedUrls < 3) {
        throw new InvalidConfigurationException(
            "feed.maxUrls must be greater than 2: " + maxFeedUrls);
      }
    } catch (NumberFormatException e) {
      throw new InvalidConfigurationException(
          "Invalid feed.maxUrls value: " + config.getValue("feed.maxUrls"));
    }
    logger.log(Level.CONFIG, "feed.maxUrls: {0}", maxFeedUrls);
  }

  public String getContentEngineUrl() {
    return contentEngineUrl;
  }

  public String getUsername() {
    return username;
  }

  public String getPassword() {
    return password;
  }

  public ObjectFactory getObjectFactory() {
    return objectFactory;
  }

  public AutoConnection getConnection() {
    return objectFactory.getConnection(contentEngineUrl, username,
        sensitiveValueDecoder.decodeValue(password));
  }

  public ObjectStore getObjectStore(AutoConnection connection) {
    return objectFactory.getObjectStore(connection, objectStoreName);
  }

  public URI getDisplayUrl(Id guid, Id vsId) {
    return URI.create(getDisplayUrl(displayUrlPattern, guid,
        vsId, objectStoreName));
  }

  private static String getDisplayUrl(String displayUrlPattern, Id guid,
      Id vsId, String objectStoreName) {
    return MessageFormat.format(displayUrlPattern,
        new Object[] { percentEscape(guid), percentEscape(vsId),
            objectStoreName });
  }

  public boolean markAllDocsAsPublic() {
    return markAllDocsAsPublic;
  }

  public String getAuthenticatedUsersGroup() {
    return authenticatedUsersGroup;
  }

  public String getGlobalNamespace() {
    return globalNamespace;
  }

  public String getAdditionalWhereClause() {
    return additionalWhereClause;
  }

  public Set<String> getExcludedMetadata() {
    return excludedMetadata;
  }

  public Set<String> getIncludedMetadata() {
    return includedMetadata;
  }

  public SimpleDateFormat getMetadataDateFormat() {
    return metadataDateFormat.get();
  }

  public int getMaxFeedUrls() {
    return maxFeedUrls;
  }
}
