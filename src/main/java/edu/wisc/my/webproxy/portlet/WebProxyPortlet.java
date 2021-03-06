/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package edu.wisc.my.webproxy.portlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.Writer;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.GenericPortlet;
import javax.portlet.PortletException;
import javax.portlet.PortletMode;
import javax.portlet.PortletPreferences;
import javax.portlet.PortletRequest;
import javax.portlet.PortletRequestDispatcher;
import javax.portlet.PortletSession;
import javax.portlet.ReadOnlyException;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;
import javax.portlet.ValidatorException;
import javax.portlet.WindowState;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.jasig.web.util.SecureSessionKeyGenerator;
import org.jasig.web.util.SessionKeyGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.portlet.context.PortletApplicationContextUtils;
import org.springframework.web.portlet.context.PortletWebRequest;
import org.springframework.web.util.WebUtils;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import edu.wisc.my.webproxy.beans.PortletPreferencesWrapper;
import edu.wisc.my.webproxy.beans.cache.CacheEntry;
import edu.wisc.my.webproxy.beans.cache.CacheWriter;
import edu.wisc.my.webproxy.beans.cache.PageCache;
import edu.wisc.my.webproxy.beans.config.CacheConfigImpl;
import edu.wisc.my.webproxy.beans.config.ConfigPage;
import edu.wisc.my.webproxy.beans.config.ConfigUtils;
import edu.wisc.my.webproxy.beans.config.GeneralConfigImpl;
import edu.wisc.my.webproxy.beans.config.HttpClientConfigImpl;
import edu.wisc.my.webproxy.beans.config.HttpHeaderConfigImpl;
import edu.wisc.my.webproxy.beans.config.StaticHtmlConfigImpl;
import edu.wisc.my.webproxy.beans.filtering.ChainingSaxFilter;
import edu.wisc.my.webproxy.beans.filtering.HtmlOutputFilter;
import edu.wisc.my.webproxy.beans.filtering.HtmlParser;
import edu.wisc.my.webproxy.beans.http.HttpManager;
import edu.wisc.my.webproxy.beans.http.HttpManagerService;
import edu.wisc.my.webproxy.beans.http.HttpTimeoutException;
import edu.wisc.my.webproxy.beans.http.IHeader;
import edu.wisc.my.webproxy.beans.http.IKeyManager;
import edu.wisc.my.webproxy.beans.http.ParameterPair;
import edu.wisc.my.webproxy.beans.http.Request;
import edu.wisc.my.webproxy.beans.http.Response;
import edu.wisc.my.webproxy.beans.interceptors.PostInterceptor;
import edu.wisc.my.webproxy.beans.interceptors.PreInterceptor;
import edu.wisc.my.webproxy.beans.security.CasAuthenticationHandler;
import edu.wisc.my.webproxy.servlet.ProxyServlet;
import edu.wisc.my.webproxy.util.ExtendedLRUTrackingModelPasser;
import edu.wisc.my.webproxy.util.ExtendedModelPasser;

/**
 * 
 * @author Dave Grimwood <a
 *         href="mailto:dgrimwood@unicon.net">dgrimwood@unicon.net </a>
 * @version $Id$
 */
public class WebProxyPortlet extends GenericPortlet {

    private static final Log LOG = LogFactory.getLog(WebProxyPortlet.class);

    private static final String HEADER = "/WEB-INF/jsp/header.jsp";

    private static final String FOOTER = "/WEB-INF/jsp/footer.jsp";

    private static final String MANUAL = "/WEB-INF/jsp/manual.jsp";

    public final static String preferenceKey = WebProxyPortlet.class.getName();
    
    private final SessionKeyGenerator sessionKeyGenerator = new SecureSessionKeyGenerator();
    private final ExtendedModelPasser modelPasser = new ExtendedLRUTrackingModelPasser() {{
        setMaxSize(1000);
    }};
    
    
    /**
     * @see javax.portlet.Portlet#destroy()
     */
    public void destroy() {
        super.destroy();
    }
    /**
     * @see javax.portlet.GenericPortlet#init()
     */
    public void init() throws PortletException {
        super.init();
    }
    
    private static class Mutex implements Serializable {
    }
    
    @Override
    public void render(RenderRequest request, RenderResponse response) throws PortletException, IOException {
        //Since portlets don't have a good way to stick a mutex in the session check on every render request
        PortletSession portletSession = request.getPortletSession(false);
        if (portletSession == null || portletSession.isNew() || portletSession.getAttribute(WebUtils.SESSION_MUTEX_ATTRIBUTE) == null) {
            portletSession = request.getPortletSession();
            portletSession.setAttribute(WebUtils.SESSION_MUTEX_ATTRIBUTE, new Mutex());
        }
        
        
        // We're overriding render() on GenericPortlet for the sole purpose of 
        // *not* executing the following commented below, which would typically 
        // set all titles from this portlet to 'Authenticated Web Proxy.'
        //
        // response.setTitle(getTitle(request));
        doDispatch(request, response);

    }

    @Override
    public void doDispatch(final RenderRequest request, final RenderResponse response) throws PortletException, IOException {
        final ApplicationContext context = PortletApplicationContextUtils.getWebApplicationContext(this.getPortletContext());
        ApplicationContextLocator.setApplicationContext(context);
        
        final WebRequestInterceptor interceptor = (WebRequestInterceptor)context.getBean("openEntityManagerInViewInterceptor", WebRequestInterceptor.class);
        final WebRequest webRequest = new PortletWebRequest(request, response);

        Exception dispatchException = null;
        try {
            try {
                interceptor.preHandle(webRequest);
            }
            catch (Exception e) {
                throw new PortletException(e);
            }
            
            final PortletMode mode = request.getPortletMode();
            final WindowState windowState = request.getWindowState();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Rendering with PortletMode='" + mode + "' and WindowState='" + windowState + "'");
            }
            
            //If the windowstate is minimized do not call doView
            if (!windowState.equals(WindowState.MINIMIZED)) {
                if (PortletMode.VIEW.equals(mode) || PortletMode.EDIT.equals(mode)) {
                    if (!manualLogin(request, response)) {
                        renderContent(request, response);
                    }
                }
                else if (WebproxyConstants.CONFIG_MODE.equals(mode)) {
                    ConfigPage currentConfig = getConfig(request.getPortletSession());
    
                    PortletRequestDispatcher headerDispatch = getPortletContext().getRequestDispatcher(HEADER);
                    headerDispatch.include(request, response);
    
                    try {
                        currentConfig.render(getPortletContext(), request, response);
                    }
                    catch (PortletException pe) {
                        LOG.error("Caught an exception trying to retreive portlet preferences in configuration mode: ", pe);
                    }
    
                    PortletRequestDispatcher footerDispatch = getPortletContext().getRequestDispatcher(FOOTER);
                    footerDispatch.include(request, response);
                }
                else {
                    throw new PortletException("'" + mode + "' Not Implemented");
                }
            }
            
            try {
                interceptor.postHandle(webRequest, null);
            }
            catch (Exception e) {
                throw new PortletException(e);
            }
        }
        catch (Exception e) {
            dispatchException = e;
            if (e instanceof RuntimeException) {
                throw (RuntimeException)e;
            }
            if (e instanceof PortletException) {
                throw (PortletException)e;
            }
            if (e instanceof IOException) {
                throw (IOException)e;
            }
            
            throw new PortletException(e);
        }
        finally {
            ApplicationContextLocator.setApplicationContext(null);
            
            try {
                interceptor.afterCompletion(webRequest, dispatchException);
            }
            catch (Exception e1) {
                throw new PortletException(e1);
            }
        }
    }


    private void renderContent(final RenderRequest request, final RenderResponse response) throws PortletException, IOException {
        //Gets a reference to the ApplicationContext created by the
        //ContextLoaderListener which is configured in the web.xml for
        //this portlet
        final ApplicationContext context = PortletApplicationContextUtils.getWebApplicationContext(this.getPortletContext());
        ApplicationContextLocator.setApplicationContext(context);

        //retrieve portlet preferences from PortletPreferencesWrapper object
        // substitution
        final Map userInfo = (Map)request.getAttribute(RenderRequest.USER_INFO);
        PortletPreferences myPreferences = request.getPreferences();
        myPreferences = new PortletPreferencesWrapper(myPreferences, userInfo);

        //Get users session
        PortletSession session = request.getPortletSession();
        
        String sUrl = null;
        
        //Try getting the URL specified by the url key from the model passer
        final String urlKey = request.getParameter(GeneralConfigImpl.BASE_URL_KEY);
        if (urlKey != null) {
            final Map<String, ?> urlModel = this.modelPasser.getModelFromPortlet(request, response, urlKey);
            if (urlModel != null) {
                sUrl = (String)urlModel.get(GeneralConfigImpl.BASE_URL);
            }
        }
        
        //No URL specified in request/model, use the default from the portlet preferences/session
        if (sUrl == null) {
            if (session.getAttribute(GeneralConfigImpl.BASE_URL) != null) {
                // The BASE_URL configured in PortletPreferences has been 
                // overridden by another feature;  CAS proxy AuthN, at a minimum, 
                // uses this approach to add it's proxy ticket to the request URL.
                sUrl = (String) session.getAttribute(GeneralConfigImpl.BASE_URL);
            } else {
                // Use value configured in PortletPreferences
                sUrl = ConfigUtils.checkEmptyNullString(myPreferences.getValue(GeneralConfigImpl.BASE_URL, null), null);
            }
            if (sUrl == null) {
                throw new PortletException("No Initial URL Configured");
            }
        }

        //use Edit Url if in Edit mode
        final PortletMode mode = request.getPortletMode();
        if (PortletMode.EDIT.equals(mode)) {
            String sTemp = myPreferences.getValue(GeneralConfigImpl.EDIT_URL, null);
            if (sTemp!=null){
                sUrl = sTemp;
            }
        }
        
        String sRequestType = request.getParameter(WebproxyConstants.REQUEST_TYPE);

        HttpManagerService findingService = (HttpManagerService) context.getBean("HttpManagerService", HttpManagerService.class);
        final HttpManager httpManager = findingService.findManager(request);
        httpManager.setRenderData(request, response);

        this.doFormAuth(httpManager, request);

        final boolean sUseCache = new Boolean(myPreferences.getValue(CacheConfigImpl.USE_CACHE, null)).booleanValue();
        if (sUseCache) {
            final PageCache cache = (PageCache)context.getBean("PageCache", PageCache.class);
            final String cacheScope = myPreferences.getValue(CacheConfigImpl.CACHE_SCOPE, null);

            final IKeyManager keyManager = (IKeyManager)context.getBean("keyManager", IKeyManager.class);
            String cacheKey = null;
            /*
             * If scope is user get a key unique to this portlet instance.
             * Otherwise use the url alone as the key.  This will share the response across all
             * instances of web proxy portlet.
             */
            if (cacheScope == null || cacheScope.equals(CacheConfigImpl.CACHE_SCOPE_USER))
                cacheKey = keyManager.generateCacheKey(sUrl, request);
            else 
                cacheKey = sUrl;

            final CacheEntry cachedData = cache.getCachedPage(cacheKey);

            if (cachedData != null) {
                if (LOG.isTraceEnabled())
                    LOG.trace("Using cached content for key '" + cacheKey + "'");

                response.setContentType(cachedData.getContentType());
                response.getWriter().write(cachedData.getContent());
                return;
            }
        }

        Response httpResponse = null;
        try {
            boolean redirect = true;
            final int maxRedirects = ConfigUtils.parseInt(myPreferences.getValue(HttpClientConfigImpl.MAX_REDIRECTS, null), 5);
            for (int index = 0; index < maxRedirects && redirect; index++) {
                this.doHttpAuth(request, httpManager);

                //create request object
                final Request httpRequest = httpManager.createRequest();

                //set URL in request
                httpRequest.setUrl(sUrl);

                //Set headers
                final String[] headerNames = myPreferences.getValues(HttpHeaderConfigImpl.HEADER_NAME, new String[0]);
                final String[] headerValues = myPreferences.getValues(HttpHeaderConfigImpl.HEADER_VALUE, new String[0]);
                if (headerNames.length == headerValues.length) {
                    final List<IHeader> headerList = new ArrayList<IHeader>(headerNames.length);
                    
                    for (int headerIndex = 0; headerIndex < headerNames.length; headerIndex++) {
                        final IHeader h = httpRequest.createHeader(headerNames[headerIndex], headerValues[headerIndex]);
                        headerList.add(h);
                    }
                    
                    httpRequest.setHeaders(headerList.toArray(new IHeader[headerList.size()]));
                }
                else {
                    LOG.error("Invalid data in preferences. Header name array length does not equal header value array length");
                }
                //check to see if form was a GET form
                //set Type (e.g., GET, POST, HEAD)
                if (sRequestType == null) {
                    httpRequest.setType(WebproxyConstants.GET_REQUEST);
                }
                else {
                    httpRequest.setType(sRequestType);

                    //If post add any parameters to the method
                    if (sRequestType.equals(WebproxyConstants.POST_REQUEST)) {
                        final List<ParameterPair> postParameters = new ArrayList<ParameterPair>(request.getParameterMap().size());
                        
                        final String paramKey = request.getParameter(GeneralConfigImpl.POST_PARAM_KEY);
                        final Map<String, ?> parameters = this.modelPasser.getModelFromPortlet(request, response, paramKey);
                        
                        for (final Map.Entry<String, ?> paramEntry : parameters.entrySet()) {
                            final String paramName = paramEntry.getKey();

                            if (!paramName.startsWith(WebproxyConstants.UNIQUE_CONSTANT)) {
                                final String[] values = (String[])paramEntry.getValue();
                                
                                for (int valIndex = 0; valIndex < values.length; valIndex++) {
                                    final ParameterPair param = new ParameterPair(paramName, values[valIndex]);
                                    postParameters.add(param);
                                }
                            }
                        }

                        final ParameterPair[] params = postParameters.toArray(new ParameterPair[postParameters.size()]);
                        httpRequest.setParameters(params);
                    }
                }

                //Check to see if pre-interceptors are used.
                final String sPreInterceptor = ConfigUtils.checkEmptyNullString(myPreferences.getValue(GeneralConfigImpl.PRE_INTERCEPTOR_CLASS, null), null);
                if (sPreInterceptor != null) {
                    try {
                        final Class preInterceptorClass = Class.forName(sPreInterceptor);
                        PreInterceptor myPreInterceptor = (PreInterceptor)preInterceptorClass.newInstance();
                        myPreInterceptor.intercept(request, response, httpRequest);
                    }
                    catch (ClassNotFoundException cnfe) {
                        final String msg = "Could not find specified pre-interceptor class '" + sPreInterceptor + "'";
                        LOG.error(msg, cnfe);
                        throw new PortletException(msg, cnfe);
                    }
                    catch (InstantiationException ie) {
                        final String msg = "Could instatiate specified pre-interceptor class '" + sPreInterceptor + "'";
                        LOG.error(msg, ie);
                        throw new PortletException(msg, ie);
                    }
                    catch (IllegalAccessException iae) {
                        final String msg = "Could instatiate specified pre-interceptor class '" + sPreInterceptor + "'";
                        LOG.error(msg, iae);
                        throw new PortletException(msg, iae);
                    }
                    catch (ClassCastException cce) {
                        final String msg = "Could not cast '" + sPreInterceptor + "' to 'edu.wisc.my.webproxy.beans.interceptors.PreInterceptor'";
                        LOG.error(msg, cce);
                        throw new PortletException(msg, cce);
                    }
                }

                try {
                    //send httpRequest
                    httpResponse = httpManager.doRequest(httpRequest);
                }
                catch (HttpTimeoutException hte) {
                    final boolean sUseExpired = new Boolean(myPreferences.getValue(CacheConfigImpl.USE_EXPIRED, null)).booleanValue();
                    if (sUseCache && sUseExpired) {
                        LOG.info("Request '" + sUrl + "' timed out. Attempting to use expired cache data.");
                        final PageCache cache = (PageCache)context.getBean("PageCache", PageCache.class);

                        final IKeyManager keyManager = (IKeyManager)context.getBean("keyManager", IKeyManager.class);
                        
                        final String cacheScope = myPreferences.getValue(CacheConfigImpl.CACHE_SCOPE, null);
                        String cacheKey = null;
                        /*
                         * If scope is user get a key unique to this portlet instance.
                         * Otherwise use the url alone as the key.  This will share the response across all
                         * instances of web proxy portlet.
                         */
                        if (cacheScope  == null || cacheScope.equals(CacheConfigImpl.CACHE_SCOPE_USER))
                            cacheKey = keyManager.generateCacheKey(sUrl, request);
                        else 
                            cacheKey = sUrl;      


                        final CacheEntry cachedData = cache.getCachedPage(cacheKey, true);

                        if (cachedData != null) {
                            final int retryDelay = ConfigUtils.parseInt(myPreferences.getValue(CacheConfigImpl.RETRY_DELAY, null), -1);
                            
                            if (retryDelay > 0) {
                                final boolean persistData = new Boolean(myPreferences.getValue(CacheConfigImpl.PERSIST_CACHE, null)).booleanValue();
                                
                                cachedData.setExpirationDate(new Date(System.currentTimeMillis() + (retryDelay * 1000)));
                                cache.cachePage(cacheKey, cachedData, persistData);
                            }
                            
                            if (LOG.isTraceEnabled())
                                LOG.trace("Using cached content for key '" + cacheKey + "'");

                            response.setContentType(cachedData.getContentType());
                            response.getWriter().write(cachedData.getContent());
                            return;
                        }
                    }
                    
                    //If cached content was used this won't be reached, all other
                    //cases an exception needs to be thrown.
                    LOG.warn("Request '" + httpRequest + "' timed out", hte);
                    throw hte;
                    //TODO handle timeout cleanly
                }
                
                //Track last activity time in session
                session.setAttribute(HttpClientConfigImpl.SESSION_TIMEOUT, new Long(System.currentTimeMillis()));

                //Check to see if post-interceptors are used
                final String sPostInterceptor = ConfigUtils.checkEmptyNullString(myPreferences.getValue(GeneralConfigImpl.POST_INTERCEPTOR_CLASS, null), null);
                if (sPostInterceptor != null) {
                    try {
                        final Class postInterceptorClass = Class.forName(sPostInterceptor);
                        PostInterceptor myPostInterceptor = (PostInterceptor)postInterceptorClass.newInstance();
                        myPostInterceptor.intercept(request, response, httpResponse);
                    }
                    catch (ClassNotFoundException cnfe) {
                        final String msg = "Could not find specified post-interceptor class '" + sPostInterceptor + "'";
                        LOG.error(msg, cnfe);
                        throw new PortletException(msg, cnfe);
                    }
                    catch (InstantiationException ie) {
                        final String msg = "Could instatiate specified post-interceptor class '" + sPostInterceptor + "'";
                        LOG.error(msg, ie);
                        throw new PortletException(msg, ie);
                    }
                    catch (IllegalAccessException iae) {
                        final String msg = "Could instatiate specified post-interceptor class '" + sPostInterceptor + "'";
                        LOG.error(msg, iae);
                        throw new PortletException(msg, iae);
                    }
                    catch (ClassCastException cce) {
                        final String msg = "Could not cast '" + sPostInterceptor + "' to 'edu.wisc.my.webproxy.beans.interceptors.PostInterceptor'";
                        LOG.error(msg, cce);
                        throw new PortletException(msg, cce);
                    }
                }
           
                //store the state
                findingService.saveHttpManager(request, httpManager);

                //Check to see if redirected
                final String tempUrl = checkRedirect(sUrl, httpResponse);
                //TODO make sure this works
                if (sUrl.equals(tempUrl))
                    redirect = false;
                else
                    sUrl = tempUrl;
            }
            
            final String realUrl = httpResponse.getRequestUrl();
            

            //check response object for binary content
            String sContentType = httpResponse.getContentType();
            if (sContentType != null) {
                StringTokenizer st = new StringTokenizer(sContentType, ";");
                sContentType = st.nextToken();
            }
            else {
                sContentType = "text/html";
            }
            
            //TODO how do we handle 'unknown'?
            if ("unknown".equals(sContentType))
                sContentType = "text/html";

            final List acceptedContent = (List)context.getBean("ContentTypeBean", List.class);
            boolean matches = false;
            for (Iterator iterateContent = acceptedContent.iterator(); iterateContent.hasNext() && !matches; ) {
                final String sAcceptedContent = (String)iterateContent.next();
                final Pattern contentPattern = Pattern.compile(sAcceptedContent, Pattern.CASE_INSENSITIVE);
                final Matcher contentMatcher = contentPattern.matcher(sContentType);
                
                matches = contentMatcher.matches();
            }

            response.setContentType(sContentType);
            
            //Get InputStream and OutputStream
            InputStream in = null;
            Writer out = null;
            try {
                in = httpResponse.getResponseBodyAsStream();
                out = response.getWriter();

                if (!matches) {
                    //TODO Display page with direct link to content and back link to previous URL
                }
                else {
                    if (realUrl != null)
                        sUrl = realUrl;
                    
                    session.setAttribute(GeneralConfigImpl.BASE_URL, sUrl);

                	//prepend the back button to the outputstream
                    if (PortletMode.EDIT.equals(mode)) {
                        out.write(createBackButton(response));
                    }
                    //Matched a filterable content type, parse and filter stream.
                    if (sUseCache) {
                        final PageCache cache = (PageCache)context.getBean("PageCache", PageCache.class);
    
                        final IKeyManager keyManager = (IKeyManager)context.getBean("keyManager", IKeyManager.class);
                        final String cacheScope = myPreferences.getValue(CacheConfigImpl.CACHE_SCOPE, null);
                        String cacheKey = null;
                        /*
                         * If scope is user get a key unique to this portlet instance.
                         * Otherwise use the url alone as the key.  This will share the response across all
                         * instances of web proxy portlet.
                         */
                        if (cacheScope  == null || cacheScope.equals(CacheConfigImpl.CACHE_SCOPE_USER))
                            cacheKey = keyManager.generateCacheKey(sUrl, request);
                        else 
                            cacheKey = sUrl;      

                        final int cacheExprTime = ConfigUtils.parseInt(myPreferences.getValue(CacheConfigImpl.CACHE_TIMEOUT, null), -1);
                        final boolean persistData = new Boolean(myPreferences.getValue(CacheConfigImpl.PERSIST_CACHE, null)).booleanValue();
                        
                        final CacheEntry entryBase = new CacheEntry();
                        entryBase.setContentType(sContentType);
                        
                        if (cacheExprTime >= 0)
                            entryBase.setExpirationDate(new Date(System.currentTimeMillis() + cacheExprTime * 1000));
    
                        out = new CacheWriter(out, entryBase, cache, cacheKey, persistData);
                    }
                    //Write out static header data
                    final String sHeader = ConfigUtils.checkEmptyNullString(myPreferences.getValue(StaticHtmlConfigImpl.STATIC_HEADER, null), null);
                    if (sHeader != null) {
                        out.write(sHeader);
                    }   
                    final HtmlParser htmlParser = (HtmlParser)context.getBean("HtmlParserBean", HtmlParser.class);
                    final HtmlOutputFilter outFilter = new HtmlOutputFilter(out);
                    try {
                        htmlParser.setRenderData(request, response);
                         
                        //Setup filter chain
                        final List saxFilters = (List)context.getBean("SaxFilterBean", List.class);
                        ChainingSaxFilter parent = null;
                        final Iterator filterItr = saxFilters.iterator();
                        if (filterItr.hasNext()) {
                            parent = (ChainingSaxFilter)filterItr.next();
                            outFilter.setParent(parent);
                            
                            while (filterItr.hasNext()) {
                                final ChainingSaxFilter nextParent = (ChainingSaxFilter)filterItr.next();
                                parent.setParent(nextParent);
                                parent = nextParent;
                            }
                        }
                        
                        //This call should be chained so it only needs to be done on the end filter
                        outFilter.setRenderData(request, response);
    
                        //Get the xmlReader and set a reference to the last filter for Lexical Handling
                        final XMLReader xmlReader = htmlParser.getReader(parent);
                        //Set the parent of the last filter so parsing will work
                        parent.setParent(xmlReader);

                        try {
                            outFilter.parse(new InputSource(in));
                        }
                        catch (SAXException se) {
                            throw new PortletException("A error occured while parsing the content", se);
                        }
                            
                        //Write out static footer data
                        final String sFooter = ConfigUtils.checkEmptyNullString(myPreferences.getValue(StaticHtmlConfigImpl.STATIC_FOOTER, null), null);
                        if (sFooter != null) {
                            out.write(sFooter);
                        }                        
                    }
                    finally {
                        htmlParser.clearData();
                        outFilter.clearData();
                    }
                }
            }
            finally {
                if (in != null)
                    in.close();
                
                if (out != null) {
                    out.flush();
                    out.close();
                }
            }
        }
        finally {
            if (httpResponse != null)
                httpResponse.close();
        }
    }

    /**
     * Creates new URL for Forms with GET methods
     * @param url
     * @param request
     */
    private String newGetUrl(String url, ActionRequest request) throws IOException {
        StringBuilder newUrl = new StringBuilder(url).append("?");

        for (Enumeration e = request.getParameterNames(); e.hasMoreElements();) {
            final String paramName = (String)e.nextElement();

            if (!paramName.startsWith(WebproxyConstants.UNIQUE_CONSTANT)) {
                final String[] values = request.getParameterValues(paramName);
                for (int valIndex = 0; valIndex < values.length; valIndex++) {
                    newUrl.append(URLEncoder.encode(paramName, "UTF-8")).append("=").append(URLEncoder.encode(values[valIndex], "UTF-8"));

                    if ((valIndex + 1) != values.length || e.hasMoreElements()) {
                        newUrl.append("&");
                    }
                }
            }
        }

        return newUrl.toString();
    }



    /**
     * @param portletRequest
     * @param httpRequest
     */
    private void doHttpAuth(final PortletRequest portletRequest, HttpManager manager) {
        final PortletPreferences myPreferences = new PortletPreferencesWrapper(portletRequest.getPreferences(), (Map)portletRequest.getAttribute(PortletRequest.USER_INFO));

        final boolean authEnabled = new Boolean(myPreferences.getValue(HttpClientConfigImpl.AUTH_ENABLE, null)).booleanValue();
        final String authType = ConfigUtils.checkEmptyNullString(myPreferences.getValue(HttpClientConfigImpl.AUTH_TYPE, ""), "");

        if (authEnabled && HttpClientConfigImpl.AUTH_TYPE_BASIC.equals(authType) || HttpClientConfigImpl.AUTH_TYPE_NTLM.equals(authType)) {
            final PortletSession session = portletRequest.getPortletSession();

            String userName = (String)session.getAttribute(HttpClientConfigImpl.USER_NAME);
            if (userName == null)
                userName = myPreferences.getValue(HttpClientConfigImpl.USER_NAME, "");

            String password = (String)session.getAttribute(HttpClientConfigImpl.PASSWORD);
            if (password == null)
                password = myPreferences.getValue(HttpClientConfigImpl.PASSWORD, "");

            final Credentials creds;
            if (HttpClientConfigImpl.AUTH_TYPE_BASIC.equals(authType)) {
                creds = new UsernamePasswordCredentials(userName, password);
            }
            else {
                String domain = (String)session.getAttribute(HttpClientConfigImpl.DOMAIN);
                if (domain == null)
                    domain = myPreferences.getValue(HttpClientConfigImpl.DOMAIN, "");

                final String host = portletRequest.getProperty("REMOTE_HOST");

                creds = new NTCredentials(userName, password, domain, host);
            }

            manager.setCredentials(creds);
        }
    }

    private String doFormAuth(final HttpManager httpManager, PortletRequest request) throws PortletException, IOException {
        final PortletSession session = request.getPortletSession();
        final PortletPreferences prefs = new PortletPreferencesWrapper(request.getPreferences(), (Map)request.getAttribute(PortletRequest.USER_INFO));

        final boolean authEnabled = new Boolean(prefs.getValue(HttpClientConfigImpl.AUTH_ENABLE, null)).booleanValue();
        final String authType = ConfigUtils.checkEmptyNullString(prefs.getValue(HttpClientConfigImpl.AUTH_TYPE, ""), "");

        final String sessionTimeoutStr = prefs.getValue(HttpClientConfigImpl.SESSION_TIMEOUT, null);
        long sessionTimeout = 25; //25 Minute default
        try {
            sessionTimeout = Long.parseLong(sessionTimeoutStr);
        }
        catch (NumberFormatException nfe) {
            //Ignore NFE, sessionTimeout has a default value
        }
        //Convert Minutes to Milliseconds
        sessionTimeout *= 60000;

        final boolean sessionExpired;

        final Long lastActivity = (Long)session.getAttribute(HttpClientConfigImpl.SESSION_TIMEOUT);
        if (lastActivity == null || (lastActivity.longValue() + sessionTimeout) <= System.currentTimeMillis()) {
            sessionExpired = true;
        }
        else {
            sessionExpired = false;
        }

        if (authEnabled && sessionExpired && HttpClientConfigImpl.AUTH_TYPE_FORM.equals(authType)) {
            //get all loginAttributes for Posting
            final String[] sStaticParameterNames = prefs.getValues(HttpClientConfigImpl.STATIC_PARAM_NAMES, new String[0]);
            final String[] sStaticParameterValues = prefs.getValues(HttpClientConfigImpl.STATIC_PARAM_VALUES, new String[0]);

            final String[] sDynamicParameterNames = prefs.getValues(HttpClientConfigImpl.DYNAMIC_PARAM_NAMES, new String[0]);
            final String[] sDynamicParameterValues = new String[sDynamicParameterNames.length];

            //get and set additional dynamic post paramters
            final String[] postAttributes = (String[])session.getAttribute(HttpClientConfigImpl.DYNAMIC_PARAM_VALUES);
            if (postAttributes != null) {
                for (int index = 0; index < postAttributes.length && index < sDynamicParameterValues.length; index++) {
                    sDynamicParameterValues[index] = postAttributes[index];
                }
            }

            final List<ParameterPair> sLoginAttributes = new ArrayList<ParameterPair>(sDynamicParameterNames.length + sStaticParameterNames.length);

            for (int dynamicIndex = 0; dynamicIndex < sDynamicParameterNames.length; dynamicIndex++) {
                final String value;
                if (dynamicIndex < sDynamicParameterValues.length)
                    value = ConfigUtils.checkEmptyNullString(sDynamicParameterValues[dynamicIndex], "");
                else
                    value = "";

                final ParameterPair pair = new ParameterPair(sDynamicParameterNames[dynamicIndex], value);
                sLoginAttributes.add(pair);
            }
            for (int staticIndex = 0; staticIndex < sStaticParameterNames.length; staticIndex++) {
                final String value;
                if (staticIndex < sStaticParameterValues.length)
                    value = ConfigUtils.checkEmptyNullString(sStaticParameterValues[staticIndex], "");
                else
                    value = "";

                final ParameterPair pair = new ParameterPair(sStaticParameterNames[staticIndex], value);
                sLoginAttributes.add(pair);
            }

            //create Post object
            final Request authPost = httpManager.createRequest();

            authPost.setType(WebproxyConstants.POST_REQUEST);

            final String authUrl = ConfigUtils.checkEmptyNullString(prefs.getValue(HttpClientConfigImpl.AUTH_URL, ""), "");
            authPost.setUrl(authUrl);

            authPost.setParameters(sLoginAttributes.toArray(new ParameterPair[sLoginAttributes.size()]));

            final Response authResponse = httpManager.doRequest(authPost);
            session.setAttribute(HttpClientConfigImpl.SESSION_TIMEOUT, new Long(System.currentTimeMillis()));

            //Check for redirect
            final String redirUrl = checkRedirect(authUrl, authResponse);
            //Get new State
            // TODO: save state

            //close response
            authResponse.close();

            if (!authUrl.equals(redirUrl))
                return redirUrl;
            else
                return null;
        }

        return null;
    }

    private boolean manualLogin(RenderRequest request, RenderResponse response) throws PortletException, IOException {
        PortletSession session = request.getPortletSession();
        PortletPreferences myPreferences = request.getPreferences();

        final boolean authEnabled = new Boolean(myPreferences.getValue(HttpClientConfigImpl.AUTH_ENABLE, null)).booleanValue();

        if (authEnabled) {
            final String authType = myPreferences.getValue(HttpClientConfigImpl.AUTH_TYPE, null);

            if (HttpClientConfigImpl.AUTH_TYPE_BASIC.equals(authType)) {
                final boolean userNamePrompt = new Boolean(myPreferences.getValue(HttpClientConfigImpl.PROMPT_USER_NAME, null)).booleanValue();
                final boolean passwordPrompt = new Boolean(myPreferences.getValue(HttpClientConfigImpl.PROMPT_PASSWORD, null)).booleanValue();
                
                String userName = (String)session.getAttribute(HttpClientConfigImpl.USER_NAME);
                if (userName == null)
                    userName = myPreferences.getValue(HttpClientConfigImpl.USER_NAME, null);
                
                String password = (String)session.getAttribute(HttpClientConfigImpl.PASSWORD);
                if (password == null)
                    password = myPreferences.getValue(HttpClientConfigImpl.PASSWORD, null);
                
                userName = ConfigUtils.checkEmptyNullString(userName, null);
                password = ConfigUtils.checkEmptyNullString(password, null);
                
                if ((userNamePrompt && userName==null) || (passwordPrompt && password==null)) {
                    PortletRequestDispatcher manualLoginDispatch = getPortletContext().getRequestDispatcher(MANUAL);
                    manualLoginDispatch.include(request, response);
                    return true;
                }
            }
            else if (HttpClientConfigImpl.AUTH_TYPE_FORM.equals(authType)) {
                final String[] dynamicParamNames = myPreferences.getValues(HttpClientConfigImpl.DYNAMIC_PARAM_NAMES, new String[0]);

                final String[] dynamicParamValues = myPreferences.getValues(HttpClientConfigImpl.DYNAMIC_PARAM_VALUES, new String[dynamicParamNames.length]);
                final String[] sessionDynamicParamValues = (String[])session.getAttribute(HttpClientConfigImpl.DYNAMIC_PARAM_VALUES);
                boolean emptyValue = false;
                for (int index = 0; index < dynamicParamValues.length; index++) {
                    if (dynamicParamValues[index] == null && sessionDynamicParamValues != null && index < sessionDynamicParamValues.length)
                        dynamicParamValues[index] = sessionDynamicParamValues[index];

                    if (dynamicParamValues[index] == null)
                        emptyValue = true;
                }

                session.setAttribute(HttpClientConfigImpl.DYNAMIC_PARAM_VALUES, dynamicParamValues);

                if (emptyValue) {
                    PortletRequestDispatcher manualLoginDispatch = getPortletContext().getRequestDispatcher(MANUAL);
                    manualLoginDispatch.include(request, response);
                    return true;
                }
            }
            else if (HttpClientConfigImpl.AUTH_TYPE_CAS.equals(authType)) {
                Boolean authenticated = (Boolean)session.getAttribute(CasAuthenticationHandler.CAS_AUTHENTICATED_SESSION_FLAG);
                
                if (authenticated != Boolean.TRUE) {
                    final ApplicationContext context = PortletApplicationContextUtils.getWebApplicationContext(this.getPortletContext());
                    CasAuthenticationHandler casHandler = (CasAuthenticationHandler) context.getBean("casAuthenticationHandler");
                    Map userInfo = (Map) request.getAttribute(PortletRequest.USER_INFO);
                    String myProxyTicket = (String) userInfo.get("casProxyTicket");
                    String destination = casHandler.authenticate (request, myProxyTicket);
                    
                    // The authentication handler is expected to return a URL with a ticket attached
                    if (destination != null) {
                        session.setAttribute(GeneralConfigImpl.BASE_URL, destination);
                        session.setAttribute(CasAuthenticationHandler.CAS_AUTHENTICATED_SESSION_FLAG, Boolean.TRUE);
                    }
                }
            }
            else {
                return false;
            }
        }

        return false;
    }

    public void processAction(final ActionRequest request, final ActionResponse response) throws PortletException, IOException {
        final ApplicationContext context = PortletApplicationContextUtils.getWebApplicationContext(this.getPortletContext());
        
        final WebRequestInterceptor interceptor = (WebRequestInterceptor)context.getBean("openEntityManagerInViewInterceptor", WebRequestInterceptor.class);
        final WebRequest webRequest = new PortletWebRequest(request, response);

        try {
            interceptor.preHandle(webRequest);
        }
        catch (Exception e) {
            throw new PortletException(e);
        }
        
        Exception dispatchException = null;
        try {
            
        final PortletMode mode = request.getPortletMode();
        final WindowState windowState = request.getWindowState();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing action with PortletMode='" + mode + "' and WindowState='" + windowState + "'");
        }
        
        final PortletSession session = request.getPortletSession();
        final Map userInfo = (Map)request.getAttribute(PortletRequest.USER_INFO);
        final PortletPreferences pp = new PortletPreferencesWrapper(request.getPreferences(), userInfo);

        final String manualAuthSubmit = request.getParameter("AUTH_CREDS");
        if (manualAuthSubmit != null) {
            this.processManualAuthForm(request);
        }
        //if Back to application button is selected, set PortletMode to 'VIEW'
        else if(request.getPortletMode().equals(PortletMode.EDIT)){
            //TODO review EDIT mode code
            if(ConfigUtils.checkEmptyNullString(request.getParameter(WebproxyConstants.BACK_BUTTON), null)!=null){
                response.setPortletMode(PortletMode.VIEW);
            }
        }
        else {
            //Gets a reference to the ApplicationContext created by the
            //ContextLoaderListener which is configured in the web.xml for
            //this portlet
            ApplicationContextLocator.setApplicationContext(context);

            if (request.getPortletMode().equals(WebproxyConstants.CONFIG_MODE)) {
                this.processConfigAction(request, response);
                return;
            }
            
            if (request.getPortletMode().equals(PortletMode.EDIT)) {
                String editUrl = request.getParameter(GeneralConfigImpl.EDIT_URL);
                session.setAttribute(GeneralConfigImpl.EDIT_URL, editUrl);
                response.setPortletMode(PortletMode.VIEW);         
                return;
            }
            
            final HttpManagerService findingService = (HttpManagerService) context.getBean("HttpManagerService", HttpManagerService.class);
            final HttpManager httpManager = findingService.findManager(request);
            httpManager.setActionData(request, response);
            
            //retrieve URL from request object
            String sUrl = request.getParameter(WebproxyConstants.BASE_URL);
            String sRequestType = request.getProperty("REQUEST_METHOD");
            boolean matches = false;

            //Check if the URL is marked for pass-through, this means it will be redirected to the proxy servlet no matter what
            final boolean passThrough = Boolean.parseBoolean(request.getParameter(WebproxyConstants.PASS_THROUGH));
            if (!passThrough) {
                //TODO do cache check for content type
    
                if(request.getParameter(WebproxyConstants.UNIQUE_CONSTANT + ".getMethod")!=null){
                    sRequestType=WebproxyConstants.GET_REQUEST;
                    sUrl = newGetUrl(sUrl, request);
                }
                
                this.doFormAuth(httpManager, request);
    
                String sContentType = null;
    
                Response httpResponse = null;
                try {
                    boolean redirect = true;
                    final int maxRedirects = ConfigUtils.parseInt(pp.getValue(HttpClientConfigImpl.MAX_REDIRECTS, null), 5);
                    
                    for (int index = 0; index < maxRedirects && redirect; index++) {
                        this.doHttpAuth(request, httpManager);
    
                        //create request object
                        final Request httpRequest = httpManager.createRequest();
    
                        //set URL in request
                        httpRequest.setUrl(sUrl);
    
                        //Set Type to HEAD
                        httpRequest.setType(WebproxyConstants.HEAD_REQUEST);
     
                        final String[] headerNames = pp.getValues(HttpHeaderConfigImpl.HEADER_NAME, new String[0]);
                        final String[] headerValues = pp.getValues(HttpHeaderConfigImpl.HEADER_VALUE, new String[0]);
                        if (headerNames.length == headerValues.length) {
                            final List<IHeader> headerList = new ArrayList<IHeader>(headerNames.length);
                            
                            for (int headerIndex = 0; headerIndex < headerNames.length; headerIndex++) {
                                final IHeader h = httpRequest.createHeader(headerNames[headerIndex], headerValues[headerIndex]);
                                headerList.add(h);
                            }
                            
                            httpRequest.setHeaders(headerList.toArray(new IHeader[headerList.size()]));
                        }
                        else {
                            LOG.error("Invalid data in preferences. Header name array length does not equal header value array length");
                        }
                    
                        //Check to see if pre-interceptors are used.
                        final String sPreInterceptor = ConfigUtils.checkEmptyNullString(pp.getValue(GeneralConfigImpl.PRE_INTERCEPTOR_CLASS, null), null);
                        if (sPreInterceptor != null) {
                            try {
                                final Class preInterceptorClass = Class.forName(sPreInterceptor);
                                PreInterceptor myPreInterceptor = (PreInterceptor)preInterceptorClass.newInstance();
                                myPreInterceptor.intercept(request, response, httpRequest);
                            }
                            catch (ClassNotFoundException cnfe) {
                                final String msg = "Could not find specified pre-interceptor class '" + sPreInterceptor + "'";
                                LOG.error(msg, cnfe);
                                throw new PortletException(msg, cnfe);
                            }
                            catch (InstantiationException ie) {
                                final String msg = "Could instatiate specified pre-interceptor class '" + sPreInterceptor + "'";
                                LOG.error(msg, ie);
                                throw new PortletException(msg, ie);
                            }
                            catch (IllegalAccessException iae) {
                                final String msg = "Could instatiate specified pre-interceptor class '" + sPreInterceptor + "'";
                                LOG.error(msg, iae);
                                throw new PortletException(msg, iae);
                            }
                            catch (ClassCastException cce) {
                                final String msg = "Could not cast '" + sPreInterceptor + "' to 'edu.wisc.my.webproxy.beans.interceptors.PreInterceptor'";
                                LOG.error(msg, cce);
                                throw new PortletException(msg, cce);
                            }
                        }
                        
                        //send httpRequest
                        httpResponse = httpManager.doRequest(httpRequest);
                        
                        session.setAttribute(HttpClientConfigImpl.SESSION_TIMEOUT, new Long(System.currentTimeMillis()));
                        
                        //Check to see if post-interceptors are used
                        final String sPostInterceptor = ConfigUtils.checkEmptyNullString(pp.getValue(GeneralConfigImpl.POST_INTERCEPTOR_CLASS, null), null);
                        if (sPostInterceptor != null) {
                            try {
                                final Class postInterceptorClass = Class.forName(sPostInterceptor);
                                PostInterceptor myPostInterceptor = (PostInterceptor)postInterceptorClass.newInstance();
                                myPostInterceptor.intercept(request, response, httpResponse);
                            }
                            catch (ClassNotFoundException cnfe) {
                                final String msg = "Could not find specified post-interceptor class '" + sPostInterceptor + "'";
                                LOG.error(msg, cnfe);
                                throw new PortletException(msg, cnfe);
                            }
                            catch (InstantiationException ie) {
                                final String msg = "Could instatiate specified post-interceptor class '" + sPostInterceptor + "'";
                                LOG.error(msg, ie);
                                throw new PortletException(msg, ie);
                            }
                            catch (IllegalAccessException iae) {
                                final String msg = "Could instatiate specified post-interceptor class '" + sPostInterceptor + "'";
                                LOG.error(msg, iae);
                                throw new PortletException(msg, iae);
                            }
                            catch (ClassCastException cce) {
                                final String msg = "Could not cast '" + sPostInterceptor + "' to 'edu.wisc.my.webproxy.beans.interceptors.PostInterceptor'";
                                LOG.error(msg, cce);
                                throw new PortletException(msg, cce);
                            }
                        }
    
                        findingService.saveHttpManager(request, httpManager);
    
                        final String tempUrl = checkRedirect(sUrl, httpResponse);
                        //if not redirect, set redirect to false to break from while
                        if (tempUrl.equals(sUrl))
                            redirect = false;
                    }
    
                    //check response object for binary content
                    if (httpResponse.getContentType() != null) {
                        StringTokenizer st = new StringTokenizer(httpResponse.getContentType(), ";");
                        sContentType = st.nextToken();
                    }
                }
                finally {
                    if (httpResponse != null)
                        httpResponse.close();
                }
                
                if (sContentType != null) {
                    final List acceptedContent = (List)context.getBean("ContentTypeBean", List.class);
        
                    String sAcceptedContent = null;
                    Iterator iterateContent = acceptedContent.iterator();
                    while (iterateContent.hasNext() && !matches) {
                        sAcceptedContent = (String)iterateContent.next();
                        Pattern contentPattern = Pattern.compile(sAcceptedContent, Pattern.CASE_INSENSITIVE);
                        Matcher contentMatcher = contentPattern.matcher(sContentType);
                        if (contentMatcher.matches())
                            matches = true;
                    }
                }
            }

            if (!matches) {
                final int protocolEnd = sUrl.indexOf("//");
                final int queryStringStart = sUrl.indexOf("?");
                final int fileBaseStart = (protocolEnd < 0 ? 0 : protocolEnd + 2); //Add 2 to exclude the protocol seperator
                final int fileBaseEnd = (queryStringStart < 0 ? sUrl.length() : queryStringStart);
                final String fileBase = sUrl.substring(fileBaseStart, fileBaseEnd);
                
                final Map<String, Object> model = new LinkedHashMap<String, Object>();
                
                model.put(WebproxyConstants.REQUEST_TYPE, sRequestType);
                model.put(ProxyServlet.URL_PARAM, sUrl);

                if (WebproxyConstants.POST_REQUEST.equals(sRequestType)) {
                    model.put(ProxyServlet.POST_PARAMETERS, request.getParameterMap());
                }
                
                model.put(PortletPreferences.class.getName(), pp);
                model.put(ProxyServlet.HTTP_MANAGER, httpManager);
                
                final IKeyManager keyManager = (IKeyManager)context.getBean("keyManager", IKeyManager.class);
                model.put(IKeyManager.PORTLET_INSTANCE_KEY, keyManager.getInstanceKey(request));
                
                final String sessionKey = this.sessionKeyGenerator.getNextSessionKey(session);
                this.modelPasser.passModelToServlet(request, response, sessionKey, model);
                
                final StringBuilder servletUrl = new StringBuilder();
                servletUrl.append(request.getContextPath());
                servletUrl.append("/ProxyServlet/"); //TODO make this an init parameter
                servletUrl.append(fileBase);
                servletUrl.append("?");
                servletUrl.append(URLEncoder.encode(ProxyServlet.SESSION_KEY, "UTF-8"));
                servletUrl.append("=");
                servletUrl.append(URLEncoder.encode(sessionKey, "UTF-8"));
                
                response.sendRedirect(servletUrl.toString());
                return;
            }
            
            
            //A proxied request, redirect to a render request with the request type and parameters
            final Map params = request.getParameterMap();
            if (params != null){
                //If the request is a POST there may be too much data for the generated render URL, stick it in the session via the modelPasser and 
                if ("POST".equals(sRequestType)) {
                    final String paramKey = this.sessionKeyGenerator.getNextSessionKey(session);
                    this.modelPasser.passModelToServlet(request, response, paramKey, new LinkedHashMap(params));
                    response.setRenderParameter(GeneralConfigImpl.POST_PARAM_KEY, paramKey);
                }
                else {
                    response.setRenderParameters(params);
                }
            }
            
            final String urlKey = this.sessionKeyGenerator.getNextSessionKey(session);
            response.setRenderParameter(WebproxyConstants.REQUEST_TYPE, sRequestType);
            response.setRenderParameter(GeneralConfigImpl.BASE_URL_KEY, urlKey);
            this.modelPasser.passModelToServlet(request, response, urlKey, Collections.singletonMap(GeneralConfigImpl.BASE_URL, sUrl));
        }
        
        try {
            interceptor.postHandle(webRequest, null);
        }
        catch (Exception e) {
            throw new PortletException(e);
        }
        
        }
        catch (Exception e) {
            dispatchException = e;
            if (e instanceof RuntimeException) {
                throw (RuntimeException)e;
            }
            if (e instanceof PortletException) {
                throw (PortletException)e;
            }
            if (e instanceof IOException) {
                throw (IOException)e;
            }
            
            throw new PortletException(e);
        }
        finally {
            try {
                interceptor.afterCompletion(webRequest, dispatchException);
            }
            catch (Exception e) {
                throw new PortletException(e);
            }
        }
    }

    /**
     * @param request
     * @param response
     * @throws ReadOnlyException
     */
    private void processConfigAction(final ActionRequest request, final ActionResponse response) throws PortletException {
        final PortletSession session = request.getPortletSession();
        final Map userInfo = (Map)request.getAttribute(PortletRequest.USER_INFO);
        final PortletPreferences pp = new PortletPreferencesWrapper(request.getPreferences(), userInfo);

                 if (request.getParameter("configPlacer") != null) {
                    try {
                        session.setAttribute("configPlacer", new Integer(request.getParameter("configPlacer")));
                    }
                    catch (NumberFormatException e) {
                        LOG.error("Caught NumberFormatException when retrieving configuration page placer", e);
                    }
                }
                else {
                    if (request.getParameter("cancel") != null) {
                        //Signal that config is done
                        response.setPortletMode(PortletMode.VIEW);
                    }
                    else {
                        ConfigPage tempConfig = getConfig(session);
                        Integer configPlacer = (Integer)session.getAttribute("configPlacer");
                        boolean error = false;
                        try {
                            tempConfig.process(request, response);
                        }
                        catch (Exception e) {
                            LOG.error(new StringBuffer("Caught RuntimeException when calling action on ").append(tempConfig.getName()).toString(), e);
                            response.setRenderParameter("msg", e.getMessage());
                            error = true;
                        }
                        if (request.getParameter("next") != null) {
                            // user has clicked on next
                            if(!error){
                                configPlacer = new Integer(configPlacer.intValue() + 1);
                            }
                            session.setAttribute("configPlacer", configPlacer);
                        }
                        else if (request.getParameter("previous") != null) {
                            // user has clicked on back1
                            if(!error){
                                configPlacer = new Integer(configPlacer.intValue() - 1);
                            }
                            session.setAttribute("configPlacer", configPlacer);
                        }
                        else if (request.getParameter("apply") != null) {
                            //Signal that config is done
                            response.setPortletMode(PortletMode.VIEW);
                        }
                        else {
                            response.setRenderParameter("msg", "Thank you for submitting the parameters.");
                            pp.reset("configPlacer");
                            session.removeAttribute("configList");
                        }
                    }
                }

    }

    /**
     * @param request
     * @throws ReadOnlyException
     * @throws IOException
     * @throws ValidatorException
     */
    private void processManualAuthForm(final ActionRequest request) throws ReadOnlyException, IOException, ValidatorException {
        final PortletSession session = request.getPortletSession();
        final Map userInfo = (Map)request.getAttribute(PortletRequest.USER_INFO);
        final PortletPreferences pp = new PortletPreferencesWrapper(request.getPreferences(), userInfo);

        final String authType = pp.getValue(HttpClientConfigImpl.AUTH_TYPE, null);

        if (HttpClientConfigImpl.AUTH_TYPE_BASIC.equals(authType)) {
            final String userName = ConfigUtils.checkEmptyNullString(request.getParameter(HttpClientConfigImpl.USER_NAME), "");
            final String password = ConfigUtils.checkEmptyNullString(request.getParameter(HttpClientConfigImpl.PASSWORD), "");

            if (userName.length() > 0) {
                session.setAttribute(HttpClientConfigImpl.USER_NAME, userName);

                final boolean userNamePersist = new Boolean(pp.getValue(HttpClientConfigImpl.PERSIST_USER_NAME, null)).booleanValue();
                if (userNamePersist)
                    pp.setValue(HttpClientConfigImpl.USER_NAME, userName);
            }
            if (password.length() > 0) {
                session.setAttribute(HttpClientConfigImpl.PASSWORD, password);

                final boolean passwordPersist = new Boolean(pp.getValue(HttpClientConfigImpl.PERSIST_PASSWORD, null)).booleanValue();
                if (passwordPersist)
                    pp.setValue(HttpClientConfigImpl.PASSWORD, password);
            }

            pp.store();
        }
        else if (HttpClientConfigImpl.AUTH_TYPE_FORM.equals(authType)) {
            final String[] dynamicParamNames = pp.getValues(HttpClientConfigImpl.DYNAMIC_PARAM_NAMES, new String[0]);
            String[] dynamicParamValues = ConfigUtils.checkNullStringArray(request.getParameterValues(HttpClientConfigImpl.DYNAMIC_PARAM_VALUES),
                                                                           new String[0]);

            if (dynamicParamValues.length == dynamicParamNames.length) {
                dynamicParamValues = ConfigUtils.checkArrayForNulls(dynamicParamValues, "");

                session.setAttribute(HttpClientConfigImpl.DYNAMIC_PARAM_VALUES, dynamicParamValues);

                final String[] persistedParamValues = new String[dynamicParamValues.length];
                final String[] dynamicParamPersist = pp.getValues(HttpClientConfigImpl.DYNAMIC_PARAM_PERSIST, new String[0]);
                for (int index = 0; index < dynamicParamPersist.length; index++) {
                    try {
                        final int paramIndex = Integer.parseInt(dynamicParamPersist[index]);
                        persistedParamValues[paramIndex] = dynamicParamValues[paramIndex];
                    }
                    catch (NumberFormatException nfe) {
                    }
                }
                pp.setValues(HttpClientConfigImpl.DYNAMIC_PARAM_VALUES, persistedParamValues);
                pp.store();
            }
            else {
                LOG.warn("Invalid data submitted during manual authentication prompt. dynamicParamNames.length='" + dynamicParamNames.length
                        + "' != dynamicParamValues.length='" + dynamicParamValues.length + "'");
            }
        }
        else {
            throw new IllegalArgumentException("Unknown authType specified '" + authType + "'");
        }
    }
    
    private static final Pattern URL_WITH_PROTOCOL = Pattern.compile("[^:]*://.*");
    private static final Pattern URL_BASE = Pattern.compile("([^:]*://[^/]*).*");

    public static String checkRedirect(String sUrl, Response httpResponse) {
        int statusCode = httpResponse.getStatusCode();

        if ((statusCode == Response.SC_MOVED_TEMPORARILY) || (statusCode == Response.SC_MOVED_PERMANENTLY) || (statusCode == Response.SC_SEE_OTHER)
                || (statusCode == Response.SC_TEMPORARY_REDIRECT)) {
            final IHeader[] headers = httpResponse.getHeaders();

            for (int index = 0; index < headers.length; index++) {
                if ("location".equalsIgnoreCase(headers[index].getName())) {
                    final String location = headers[index].getValue();
                    final String calculatedLocation;
                    
                    //Location is null, this may be invalid but just re-use the same URL
                    if (location == null) {
                        calculatedLocation = sUrl;
                    }
                    //Absolute redirect that includes the protocol and server
                    else if (URL_WITH_PROTOCOL.matcher(location).matches()) {
                        calculatedLocation =  location;
                    }
                    //Absolute redirect relative the the current server
                    else if (location.startsWith("/")) {
                        final Matcher matcher = URL_BASE.matcher(sUrl);
                        if (!matcher.matches()) {
                            throw new IllegalArgumentException("URL '" + sUrl + "' doesn't match regex: " + URL_BASE.pattern());
                        }
                        final String sUrlBase = matcher.group(1);
                        calculatedLocation = sUrlBase + location;
                    }
                    //Broken redirect that is relative to the current path.
                    else {
                        calculatedLocation = sUrl.substring(0, sUrl.lastIndexOf('/')) + "/" + location;
                    }
                    
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Handling " + statusCode + " redirect from '" + sUrl + "' to  '" + location + "'. Calculated redirect URL is: " + calculatedLocation);
                    }
                    
                    return calculatedLocation;
                }
            }
        }

        return sUrl;
    }

    private ConfigPage getConfig(PortletSession session) {

        final ApplicationContext context = PortletApplicationContextUtils.getWebApplicationContext(this.getPortletContext());

        final List configurationList = (List)context.getBean("ConfigBean", List.class);

        session.setAttribute("configList", configurationList);

        ConfigPage currentConfig = null;

        Integer configPlacer = null;

        //retrieve doAction Parameter
        configPlacer = (Integer)session.getAttribute("configPlacer");

        if (configPlacer == null)
            configPlacer = new Integer(0);

        currentConfig = (ConfigPage)configurationList.get(configPlacer.intValue());

        session.setAttribute("configPlacer", configPlacer);

        return currentConfig;
    }

    //create Back Button to leave editMode
    private String createBackButton(RenderResponse response){
        StringBuilder backButton = new StringBuilder("<br><form name=\"back\" action=\"").append(response.createActionURL()).append(">\" method=\"post\"><input type=\"submit\" name=\"").append(WebproxyConstants.BACK_BUTTON).append("\" value=\"Back to Application\"></form>");
        return backButton.toString();
    }
}