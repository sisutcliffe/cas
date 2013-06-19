/*
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.authentication.principal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.validation.constraints.NotNull;

import org.jasig.cas.authentication.Credential;
import org.ldaptive.ConnectionFactory;
import org.ldaptive.LdapAttribute;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.Response;
import org.ldaptive.SearchExecutor;
import org.ldaptive.SearchFilter;
import org.ldaptive.SearchResult;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

/**
 * Resolves a principal using a 2-stage resolution strategy:
 *
 * <ol>
 *     <li>Resolve a principal ID from a (presumably simple) {@link PrincipalResolver}.</li>
 *     <li>Uses the resolved principal ID as an input to resolve attributes and optional principal ID from LDAP.</li>
 * </ol>
 *
 * @author Marvin Addison
 * @author Misagh Moayyed
 * @since 4.0
 */
public class CredentialsToLdapAttributePrincipalResolver
        extends AbstractLdapPersonDirectoryPrincipalResolver implements InitializingBean {

    /** Map of directory attribute name to CAS attribute name. */
    private Map<String, String> attributeMapping = new HashMap<String, String>();

    /** Flag that indicates whether multiple search results are allowed for a given credential. */
    private boolean allowMultipleResults = false;

    /** Performs the LDAP search operation. */
    @NotNull
    private final SearchExecutor searchExecutor;

    /** Source of LDAP connections. */
    @NotNull
    private final ConnectionFactory connectionFactory;

    /** Username attribute referenced by the search query. **/
    @NotNull
    private String usernameAttribute = "user";

    /**
     * The CredentialsToPrincipalResolver that resolves the principal from the
     * request.
     */
    @NotNull
    private PrincipalResolver principalResolver;

    /**
     * Creates a new instance with the requisite parameters.
     *
     * @param  connectionFactory  Source of LDAP connections for search operation.
     * @param  searchExecutor  Executes the search operation.
     */
    public CredentialsToLdapAttributePrincipalResolver(final ConnectionFactory connectionFactory,
                                                       final SearchExecutor searchExecutor) {
        this.connectionFactory = connectionFactory;
        this.searchExecutor = searchExecutor;
    }

    /**
     * Sets whether to allow multiple search results for a given credential.
     * This is false by default, which is sufficient and secure for more deployments.
     * Setting this to true may have security consequences.
     *
     * @param  allowMultiple  True to allow multiple search results in which case the first result
     *                        returned is used to construct the principal, or false to indicate that
     *                        a runtime exception should be raised on multiple search results.
     */
    public final void setAllowMultipleResults(final boolean allowMultiple) {
        this.allowMultipleResults = allowMultiple;
    }

    /**
     * Sets the mapping of directory attribute name to CAS attribute name.
     *
     * @param  mapping  Attribute name mapping.  Keys are LDAP directory attribute names and
     *                  values are corresponding CAS attribute names.
     */
    public final void setAttributeMapping(final Map<String, String> mapping) {
        this.attributeMapping = mapping;
    }

    /**
     * Username attribute to be used by the search query.
     *
     * @param usernameAttr the username attribute
     */
    public final void setUsernameAttribute(final String usernameAttr) {
        this.usernameAttribute = usernameAttr;
    }

    /**
     * @param principalResolver The principalResolver
     * to set.
     */
    public final void setPrincipalResolver(final PrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.notNull(this.principalResolver, "principalResolver cannot be null");
        Assert.notNull(searchExecutor.getSearchFilter(), "SearchExecutor#searchFilter cannot be null.");
        final String filterString = searchExecutor.getSearchFilter().getFilter();
        Assert.notNull(filterString, "SearchExecutor#searchFilter#filter cannot be null.");
    }

    @Override
    public final boolean supports(final Credential credential) {
        return this.principalResolver.supports(credential);
    }

    @Override
    protected String extractPrincipalId(final Credential credential) {
        final Principal principal = this.principalResolver.resolve(credential);

        if (principal == null) {
            logger.warn("Initial principal could not be resolved from request via {}, returning null",
                    this.principalResolver.getClass().getSimpleName());
            return null;
        }

        logger.debug("Resolved {}. Trying LDAP resolve now...", principal);

        final Principal ldapPrincipal = resolveFromLDAP(principal);

        if (ldapPrincipal == null) {
            logger.info("Initial principal {} was not found in LDAP, returning null", principal.getId());
            return null;
        }

        logger.debug("Resolved {} to {}", principal, ldapPrincipal);
        return ldapPrincipal.getId();
    }

    /**
     * Retrieve the resolved principal from LDAP.
     * @param resolvedPrincipal the resolved principal from credentials
     * @return the resolved LDAP principal
     */
    protected final Principal resolveFromLDAP(final Principal resolvedPrincipal) {
        final SearchResult result;
        try {
            logger.debug("Attempting to resolve LDAP principal for {}.", resolvedPrincipal);

            final Set<String> attributesToReturn = new HashSet<String>(this.attributeMapping.keySet());
            attributesToReturn.add(usernameAttribute);
            final String[] attrs = attributesToReturn.toArray(new String[]{});

            final Response<SearchResult> response = searchExecutor.search(connectionFactory,
                    createSearchFilter(resolvedPrincipal), attrs);
            logger.debug("LDAP response: {}", response);
            result = response.getResult();
        } catch (final LdapException e) {
            logger.error("LDAP error resolving principal from {}.", resolvedPrincipal, e);
            return null;
        }
        if (result.getEntries().size() > 1 && !allowMultipleResults) {
            throw new IllegalStateException("Multiple search results found but not allowed.");
        }
        final Principal ldapPrincipal;
        if (result.getEntries().isEmpty()) {
            logger.debug("No results found for {}.", resolvedPrincipal);
            ldapPrincipal = null;
        } else {
            ldapPrincipal = principalFromEntry(result.getEntry());
        }
        logger.debug("Resolved principal {}", ldapPrincipal);
        return ldapPrincipal;
    }

    /**
     * Creates a CAS principal from an LDAP entry.
     *
     * @param  entry  LDAP entry.
     * @return  Resolved CAS principal.
     */
    private Principal principalFromEntry(final LdapEntry entry) {
        final LdapAttribute nameAttribute = entry.getAttribute(this.usernameAttribute);
        if (nameAttribute == null) {
            logger.warn("Username attribute {} not found on {}; Returning null principal.",
                    this.usernameAttribute, entry);
            return null;
        }
        final String id = nameAttribute.getStringValue();
        final Map<String, Object> attributes = new HashMap<String, Object>(entry.getAttributes().size());
        Object value;
        for (LdapAttribute attribute : entry.getAttributes()) {
            if (this.usernameAttribute.equals(attribute.getName())) {
                continue;
            }
            logger.debug("Resolving LDAP attribute [{}]", attribute.getName());
            if (attribute.size() == 1) {
                if (attribute.isBinary()) {
                    value = attribute.getBinaryValue();
                } else {
                    value = attribute.getStringValue();
                }
            } else {
                if (attribute.isBinary()) {
                    value = attribute.getBinaryValues();
                } else {
                    value = attribute.getStringValues();
                }
            }
            final String attrName = mapAttributeName(attribute.getName());
            logger.debug("Resolved LDAP attribute [{}] with value [{}]", attrName, value);
            attributes.put(attrName, value);
        }
        return new SimplePrincipal(id, attributes);
    }

    /**
     * Maps an LDAP attribute name onto a CAS attribute name.
     *
     * @param  ldapName  LDAP attribute name.
     * @return  Mapped name if a mapping exists for the given attribute, otherwise the original name.
     */
    private String mapAttributeName(final String ldapName) {
        final String localName = attributeMapping.get(ldapName);
        return localName != null ? localName : ldapName;
    }

    /**
     * Constructs a new search filter using {@link SearchExecutor#searchFilter} as a template and
     * the username from the credential as a parameter.
     *
     * @param  principal
     * @return  Search filter with parameters applied.
     */
    protected final SearchFilter createSearchFilter(final Principal principal) {
        final SearchFilter filter = new SearchFilter();
        filter.setFilter(searchExecutor.getSearchFilter().getFilter());
        filter.setParameter(0, principal.getId());
        logger.debug("Constructed LDAP search filter [{}] for principal id [{}]", filter.format(), principal.getId());
        return filter;
    }
}
