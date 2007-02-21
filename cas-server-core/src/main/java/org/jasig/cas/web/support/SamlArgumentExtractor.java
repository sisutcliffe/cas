/*
 * Copyright 2005 The JA-SIG Collaborative. All rights reserved. See license
 * distributed with this file and available online at
 * http://www.ja-sig.org/products/cas/overview/license/
 */
package org.jasig.cas.web.support;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jasig.cas.authentication.principal.SamlService;
import org.jasig.cas.authentication.principal.Service;
import org.springframework.util.StringUtils;
import org.springframework.webflow.execution.RequestContext;

/**
 * Retrieve the ticket and artifact based on the SAML 1.1 profile.
 * 
 * @author Scott Battaglia
 * @version $Revision$ $Date$
 * @since 3.1
 */
public final class SamlArgumentExtractor extends AbstractArgumentExtractor {

    private static final Log LOG = LogFactory
        .getLog(SamlArgumentExtractor.class);

    private static final String PARAM_SERVICE = "TARGET";

    private static final String PARAM_TICKET = "SAMLart";

    public String getArtifactParameterName() {
        return PARAM_TICKET;
    }

    public String getServiceParameterName() {
        return PARAM_SERVICE;
    }

    public Service extractService(final HttpServletRequest request) {
        final String service = request.getParameter(getServiceParameterName());

        if (!StringUtils.hasText(service)) {
            return null;
        }

        return new SamlService(WebUtils.stripJsessionFromUrl(service));
    }

    public String constructUrlForRedirect(final RequestContext context) {
        final String service = context.getRequestParameters().get(
            getServiceParameterName());
        final String serviceTicket = WebUtils
            .getServiceTicketFromRequestScope(context);

        if (service == null) {
            return null;
        }

        final StringBuilder buffer = new StringBuilder();

        buffer.append(service);
        buffer.append(service.indexOf('?') != -1 ? "&" : "?");
        buffer.append(getArtifactParameterName());
        buffer.append("=");
        try {
            buffer.append(URLEncoder.encode(serviceTicket, "UTF-8"));
        } catch (final UnsupportedEncodingException e) {
            LOG.error(e, e);
            buffer.append(serviceTicket);
        }
        buffer.append("&");
        buffer.append(getServiceParameterName());
        buffer.append("=");

        try {
            buffer.append(URLEncoder.encode(service, "UTF-8"));
        } catch (final UnsupportedEncodingException e) {
            LOG.error(e, e);
            buffer.append(service);
        }

        return buffer.toString();
    }
}