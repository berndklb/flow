/*
 * Copyright 2000-2018 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.flow.server.ccdm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.function.Function;

import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.PushConfiguration;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.internal.UsageStatistics;
import com.vaadin.flow.server.BootstrapHandler;
import com.vaadin.flow.server.DevModeHandler;
import com.vaadin.flow.server.ServletHelper;
import com.vaadin.flow.server.ServletHelper.RequestType;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.Version;
import com.vaadin.flow.server.communication.ServerRpcHandler;
import com.vaadin.flow.server.communication.UidlWriter;
import com.vaadin.flow.server.communication.WebComponentBootstrapHandler;
import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.theme.ThemeDefinition;

import elemental.json.Json;
import elemental.json.JsonObject;
import elemental.json.JsonValue;
import elemental.json.impl.JsonUtil;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Processes a UIDL request from the client.
 *
 * Uses {@link ServerRpcHandler} to execute client-to-server RPC invocations and
 * {@link UidlWriter} to write state changes and client RPC calls back to the
 * client.
 *
 * @author Vaadin Ltd
 * @since 1.0
 */
public class JsInitHandler extends WebComponentBootstrapHandler {
    private static final String REQ_PARAM_URL = "url";
    private static final String PATH_PREFIX = "/web-component/web-component";
    
    private static Logger getLogger() {
        return LoggerFactory.getLogger(BootstrapHandler.class.getName());
    }

    private static class JsInitBootstrapContext extends BootstrapContext {

        private JsInitBootstrapContext(VaadinRequest request,
                                             VaadinResponse response, UI ui,
                                             Function<VaadinRequest, String> callback) {
            super(request, response, ui.getInternals().getSession(), ui, callback);
        }

        @Override
        protected Optional<ThemeDefinition> getTheme() {
            return Optional.empty();
        }
    }

    /**
     * Creates a new bootstrap handler with default page builder.
     */
    public JsInitHandler() {
        super(new PageBuilder() {
            public Document getBootstrapPage(BootstrapContext context) {
                return null;
            }
        });
    }


    @Override
    protected boolean canHandleRequest(VaadinRequest request) {
        return ServletHelper.isRequestType(request, RequestType.INIT);
    }

    /**
     * Returns the request's base url to use in constructing and initialising ui.
     * @param request Request to the url for.
     * @return Request's url.
     */
    protected String getRequestUrl(VaadinRequest request) {
        return ((VaadinServletRequest)request).getRequestURL().toString();
    }

    @Override
    protected BootstrapContext createAndInitUI(
            Class<? extends UI> uiClass, VaadinRequest request,
            VaadinResponse response, VaadinSession session) {

        BootstrapContext context = super.createAndInitUI(JsInitUI.class,
                request, response, session);
        JsonObject config = context.getApplicationParameters();

        String requestURL = getRequestUrl(request);
        String serviceUrl = getServiceUrl(request);

        String pushURL = context.getSession().getConfiguration().getPushURL();
        if (pushURL == null) {
            pushURL = serviceUrl;
        } else {
            try {
                URI uri = new URI(serviceUrl);
                pushURL = uri.resolve(new URI(pushURL)).toASCIIString();
            } catch (URISyntaxException exception) {
                throw new IllegalStateException(String.format(
                        "Can't resolve pushURL '%s' based on the service URL '%s'",
                        pushURL, serviceUrl), exception);
            }
        }
        PushConfiguration pushConfiguration = context.getUI()
                .getPushConfiguration();
        pushConfiguration.setPushUrl(pushURL);

        assert serviceUrl.endsWith("/");
        config.put(ApplicationConstants.SERVICE_URL, serviceUrl);
        config.put(ApplicationConstants.APP_WC_MODE, true);
        // TODO(manolo) revise this
        config.put("pushScript", getPushScript(context));
        config.put("requestURL", requestURL);

        return context;
    }

    @Override
    protected BootstrapContext createBootstrapContext(VaadinRequest request,
                                                      VaadinResponse response, UI ui, Function<VaadinRequest, String> callback) {
        return new JsInitBootstrapContext(request, response, ui, callback);
    }


    @Override
    public boolean synchronizedHandleRequest(VaadinSession session, VaadinRequest request, VaadinResponse response) throws IOException {
        if (session.getService().getDeploymentConfiguration().isCompatibilityMode()) {
            return super.synchronizedHandleRequest(session, request, response);
        } else {
            // Find UI class
            Class<? extends UI> uiClass = getUIClass(request);

            BootstrapContext context = createAndInitUI(uiClass, request, response,
                    session);

            ServletHelper.setResponseNoCacheHeaders(response::setHeader,
                    response::setDateHeader);

            JsonObject json = Json.createObject();
            
            DeploymentConfiguration config = context.getSession()
                    .getConfiguration();
            
            if (!config.isProductionMode()) {
                json.put("stats", getStats());
            }
            json.put("errors", getErrors());
            
            if (context.getPushMode().isEnabled()) {
                json.put("pushScript", getPushScript(context));
            }
            
            JsonObject initialUIDL = getInitialUidl(context.getUI());
            json.put("appConfig", getAppConfig(initialUIDL, context));
            
            writeResponse(response, json);
            return true;
        }
    }
    
    
    private JsonObject getStats() {
        JsonObject stats = Json.createObject();
        UsageStatistics.getEntries().forEach(entry -> {
            String name = entry.getName();
            String version = entry.getVersion();
            
            JsonObject json = Json.createObject();
            json.put("is", name);
            json.put("version", version);
            
            String escapedName = Json.create(name).toJson();
            stats.put(escapedName, json);
        });
        return stats;
    }

    private JsonValue getErrors() {
        JsonObject errors = Json.createObject();
        DevModeHandler devMode = DevModeHandler.getDevModeHandler();
        if (devMode != null) {
            String errorMsg = devMode.getFailedOutput();
            if (errorMsg != null) {
                errors.put("webpack-dev-server", errorMsg);
            }
        }
        return errors.keys().length > 0 ? errors : Json.createNull();
    }
    
    private String getPushScript(BootstrapContext context) {
        VaadinRequest request = context.getRequest();
        // Parameter appended to JS to bypass caches after version upgrade.
        String versionQueryParam = "?v=" + Version.getFullVersion();
        // Load client-side dependencies for push support
        String pushJSPath = context.getRequest().getService()
                .getContextRootRelativePath(request);

        if (request.getService().getDeploymentConfiguration()
                .isProductionMode()) {
            pushJSPath += ApplicationConstants.VAADIN_PUSH_JS;
        } else {
            pushJSPath += ApplicationConstants.VAADIN_PUSH_DEBUG_JS;
        }

        pushJSPath += versionQueryParam;
        return pushJSPath;
    }
    
    protected JsonObject getInitialUidl(UI ui) {
        JsonObject json = new UidlWriter().createUidl(ui, false);

        VaadinSession session = ui.getSession();
        if (session.getConfiguration().isXsrfProtectionEnabled()) {
            writeSecurityKeyUIDL(json, ui);
        }
        writePushIdUIDL(json, session);
        if (getLogger().isDebugEnabled()) {
            getLogger().debug("Initial UIDL: {}", json.asString());
        }
        return json;
    }
    
    private void writePushIdUIDL(JsonObject response,
            VaadinSession session) {
        String pushId = session.getPushId();
        response.put(ApplicationConstants.UIDL_PUSH_ID, pushId);
    }
    
    private void writeSecurityKeyUIDL(JsonObject response, UI ui) {
        String seckey = ui.getCsrfToken();
        response.put(ApplicationConstants.UIDL_SECURITY_TOKEN_ID, seckey);
    }
    
    private JsonObject getAppConfig(JsonValue initialUIDL,
            BootstrapContext context) {
        
        boolean productionMode = context.getSession().getConfiguration()
                .isProductionMode();
        
        JsonObject appConfig = context.getApplicationParameters();

        appConfig.put("productionMode", Json.create(productionMode));
        appConfig.put("appId", context.getAppId());
        appConfig.put("uidl", initialUIDL);
        appConfig.put("clientScript", BootstrapHandler.clientEngineFile.get());
        
        return appConfig;
    }
    private void writeResponse(VaadinResponse response, JsonObject json) throws IOException {
        response.setContentType("application/json");
        try (BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(response.getOutputStream(), UTF_8))) {
            writer.append(JsonUtil.stringify(json));
        }
    }

    protected String getServiceUrl(VaadinRequest request) {
        // get service url from 'url' parameter
        String url = request.getParameter(REQ_PARAM_URL);
        // if 'url' parameter was not available, use request url
        if (url == null) {
            url = ((VaadinServletRequest) request).getRequestURL().toString();
        }
        return url
                // +1 is to keep the trailing slash
                .substring(0, url.indexOf(PATH_PREFIX) + 1)
                // replace http:// or https:// with // to work with https:// proxies
                // which proxies to the same http:// url
                .replaceFirst("^" + ".*://", "//");
    }
}