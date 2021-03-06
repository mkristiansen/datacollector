/**
 * Copyright 2016 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.lib.security.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.streamsets.datacollector.util.Configuration;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Map;

public class TestRemoteSSOService {

  @Test
  public void testDefaultConfigs() throws Exception {
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());

    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "authToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    service.setConfiguration(conf);
    Assert.assertEquals(RemoteSSOService.DPM_BASE_URL_DEFAULT + "/security/login", service.getLoginPageUrl());
    Assert.assertEquals(RemoteSSOService.DPM_BASE_URL_DEFAULT + "/security/_logout", service.getLogoutUrl());
    Assert.assertEquals(10000, service.getConnectionTimeout());
  }


  @Test
  public void testCustomConfigs() throws Exception {
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());

    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "authToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 30);
    conf.set(RemoteSSOService.SECURITY_SERVICE_CONNECTION_TIMEOUT_CONFIG, 2000);
    service.setConfiguration(conf);
    Assert.assertEquals("http://foo/security/login", service.getLoginPageUrl());
    Assert.assertEquals("http://foo/security/_logout", service.getLogoutUrl());
    Assert.assertEquals(2000, service.getConnectionTimeout());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testLowValidateAuthTokenFrequency() throws Exception {
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());

    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "authToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 29);
    conf.set(RemoteSSOService.SECURITY_SERVICE_CONNECTION_TIMEOUT_CONFIG, 2000);
    service.setConfiguration(conf);
  }

  @Test
  public void testAuthConnection() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "serviceToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 30);
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());
    service.setConfiguration(conf);
    service.setApplicationAuthToken("serviceToken");
    service.setComponentId("serviceComponentId");

    Assert.assertEquals("http://foo", service.createAuthConnection("http://foo").getURL().toExternalForm());


    HttpURLConnection conn = Mockito.mock(HttpURLConnection.class);

    Mockito
        .doReturn(conn)
        .when(service)
        .createAuthConnection(Mockito.eq("http://foo/security/rest/v1/validateAuthToken/component"));

    Assert.assertEquals(conn,
        service.getAuthConnection("POST", "http://foo/security/rest/v1/validateAuthToken/component", 5000)
    );

    Mockito.verify(conn).setRequestMethod(Mockito.eq("POST"));
    Mockito.verify(conn).setDoOutput(Mockito.eq(true));
    Mockito.verify(conn).setDoInput(Mockito.eq(true));
    Mockito.verify(conn).setUseCaches(Mockito.eq(false));
    Mockito.verify(conn).setConnectTimeout(Mockito.eq(5000));
    Mockito.verify(conn).setReadTimeout(Mockito.eq(5000));
    Mockito.verify(conn).setRequestProperty(Mockito.eq(SSOConstants.X_REST_CALL), Mockito.eq("-"));
    Mockito.verify(conn).setRequestProperty(Mockito.eq(SSOConstants.X_APP_AUTH_TOKEN), Mockito.eq("serviceToken"));
    Mockito
        .verify(conn)
        .setRequestProperty(Mockito.eq(RemoteSSOService.CONTENT_TYPE), Mockito.eq(RemoteSSOService.APPLICATION_JSON));
    Mockito
        .verify(conn)
        .setRequestProperty(Mockito.eq(RemoteSSOService.ACCEPT), Mockito.eq(RemoteSSOService.APPLICATION_JSON));
    Mockito
        .verify(conn)
        .setRequestProperty(Mockito.eq(SSOConstants.X_APP_COMPONENT_ID), Mockito.eq("serviceComponentId"));
  }

  @Test
  public void testHttpOK() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "serviceToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 30);
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());
    service.setConfiguration(conf);

    HttpURLConnection conn = Mockito.mock(HttpURLConnection.class);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    Mockito.when(conn.getOutputStream()).thenReturn(outputStream);

    ByteArrayOutputStream responseData = new ByteArrayOutputStream();
    new ObjectMapper().writeValue(responseData, ImmutableMap.of("a", "A"));
    responseData.close();
    InputStream inputStream = new ByteArrayInputStream(responseData.toByteArray());
    Mockito.when(conn.getInputStream()).thenReturn(inputStream);
    Mockito.when(conn.getHeaderField(SSOConstants.X_APP_CONNECTION_TIMEOUT)).thenReturn("1000");

    Mockito.doReturn(HttpURLConnection.HTTP_OK).when(conn).getResponseCode();
    Mockito.doReturn(conn).when(service).getAuthConnection(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());

    Map got = service.doAuthRestCall("http://foo", ImmutableMap.of("b", "B"), Map.class);

    Assert.assertEquals(ImmutableMap.of("a", "A"), got);

    got = (Map) new ObjectMapper().readValue(outputStream.toByteArray(), Map.class);

    Assert.assertEquals(ImmutableMap.of("b", "B"), got);

    Assert.assertEquals(1000, service.getConnectionTimeout());
  }

  @Test
  public void testHttpIOEx() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "serviceToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 30);
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());
    service.setConfiguration(conf);

    HttpURLConnection conn = Mockito.mock(HttpURLConnection.class);

    Mockito.doThrow(new IOException()).when(conn).getOutputStream();
    Mockito.doReturn(conn).when(service).getAuthConnection(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());
    try {
      service.doAuthRestCall("http://localhost", new Object(), Map.class);
      Assert.fail("Expected Runtime exception but didn't get any");
    } catch (RuntimeException e) {
      // expected
    }
  }

  @Test
  public void testHttpForbidden() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "serviceToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 30);
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());
    service.setConfiguration(conf);

    HttpURLConnection conn = Mockito.mock(HttpURLConnection.class);

    Mockito.doReturn(HttpURLConnection.HTTP_FORBIDDEN).when(conn).getResponseCode();
    Mockito.doReturn(conn).when(service).getAuthConnection(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());

    Map got = service.doAuthRestCall("http://localhost", null, Map.class);

    Assert.assertNull(got);
  }


  @Test
  public void testValidateUserTokenWithSecurityService() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "serviceToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 30);
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());
    service.setConfiguration(conf);

    SSOPrincipal principal = TestSSOPrincipalJson.createPrincipal();

    // valid
    Mockito.doReturn(principal).when(service).doAuthRestCall(Mockito.anyString(), Mockito.any(), Mockito.<Class>any());
    Assert.assertEquals(principal, service.validateUserTokenWithSecurityService("foo"));
    Assert.assertEquals("foo", principal.getTokenStr());

    ArgumentCaptor<ValidateUserAuthTokenJson> userTokenCapture =
        ArgumentCaptor.forClass(ValidateUserAuthTokenJson.class);

    Mockito.verify(service).doAuthRestCall(Mockito.anyString(), userTokenCapture.capture(), Mockito.<Class>any());
    Assert.assertNotNull(userTokenCapture.getValue());
    Assert.assertEquals("foo", userTokenCapture.getValue().getAuthToken());

    // null
    Mockito.doReturn(null).when(service).doAuthRestCall(Mockito.anyString(), Mockito.any(), Mockito.<Class>any());
    Assert.assertNull(service.validateUserTokenWithSecurityService("foo"));
  }

  @Test
  public void testValidateAppTokenWithSecurityService() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    conf.set(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "serviceToken");
    conf.set(RemoteSSOService.SECURITY_SERVICE_COMPONENT_ID_CONFIG, "serviceComponentId");
    conf.set(RemoteSSOService.SECURITY_SERVICE_VALIDATE_AUTH_TOKEN_FREQ_CONFIG, 30);
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());
    service.setConfiguration(conf);

    SSOPrincipal principal = TestSSOPrincipalJson.createPrincipal();

    // valid
    Mockito.doReturn(principal).when(service).doAuthRestCall(Mockito.anyString(), Mockito.any(), Mockito.<Class>any());
    Assert.assertEquals(principal, service.validateAppTokenWithSecurityService("foo", "bar"));
    Assert.assertEquals("foo", principal.getTokenStr());

    ArgumentCaptor<ValidateComponentAuthTokenJson> userTokenCapture =
        ArgumentCaptor.forClass(ValidateComponentAuthTokenJson.class);

    Mockito.verify(service).doAuthRestCall(Mockito.anyString(), userTokenCapture.capture(), Mockito.<Class>any());
    Assert.assertNotNull(userTokenCapture.getValue());
    Assert.assertEquals("foo", userTokenCapture.getValue().getAuthToken());
    Assert.assertEquals("bar", userTokenCapture.getValue().getComponentId());

    // null
    Mockito.doReturn(null).when(service).doAuthRestCall(Mockito.anyString(), Mockito.any(), Mockito.<Class>any());
    Assert.assertNull(service.validateAppTokenWithSecurityService("foo", "bar"));
  }


  @Test
  public void testRegister() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://foo");
    RemoteSSOService service = Mockito.spy(new RemoteSSOService());
    service.setConfiguration(conf);
    service.setApplicationAuthToken("appToken");
    service.setComponentId("componentId");

    Mockito.doReturn(true).when(service).doAuthRestCall(Mockito.anyString(), Mockito.any());

    ArgumentCaptor<Map> registrationData = ArgumentCaptor.forClass(Map.class);

    Map<String, String> attributes = ImmutableMap.of("a", "A");
    service.register(attributes);
    Mockito.verify(service).doAuthRestCall(Mockito.anyString(), registrationData.capture());
    Assert.assertNotNull(registrationData.getValue());
    Assert.assertEquals("appToken", registrationData.getValue().get("authToken"));
    Assert.assertEquals("componentId", registrationData.getValue().get("componentId"));
    Assert.assertEquals(attributes, registrationData.getValue().get("attributes"));
  }

  @Test
  public void testRegisterRetryAttempts() throws Exception {
    Configuration conf = new Configuration();
    conf.set(RemoteSSOService.DPM_BASE_URL_CONFIG, "http://notAValidDPMURL");
    conf.set(RemoteSSOService.DPM_REGISTRATION_RETRY_ATTEMPTS, "7");

    RemoteSSOService ssoService = Mockito.spy(new RemoteSSOService());
    ssoService.setConfiguration(conf);
    ssoService.setApplicationAuthToken("appToken");
    ssoService.setComponentId("componentId");
    Mockito.doNothing().when(ssoService).sleep(Mockito.anyInt());

    try {
      ssoService.register(Collections.<String, String>emptyMap());
      Assert.fail();
    } catch (RuntimeException ex) {
      ArgumentCaptor<Integer> sleepCaptor = ArgumentCaptor.forClass(Integer.class);
      Mockito.verify(ssoService, Mockito.times(6)).sleep(sleepCaptor.capture());
      Assert.assertEquals(ImmutableList.of(2, 4, 8, 16, 16, 16), sleepCaptor.getAllValues());
      Assert.assertEquals("DPM registration failed after '7' attempts", ex.getMessage());
    }
  }

}
