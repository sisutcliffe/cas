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
package org.jasig.cas.adaptors.ldap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.jasig.cas.authentication.AbstractPasswordPolicyEnforcer;
import org.jasig.cas.authentication.LdapPasswordPolicyEnforcementException;
import org.jasig.cas.util.LdapUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Days;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.util.Assert;

/**
 * Class that fetches a password expiration date from an AD/LDAP database.
 * Based on AccountStatusGetter by Bart Ophelders & Johan Peeters.
 *
 * @author Eric Pierce
 * @author Misagh Moayyed
 */
public class LdapPasswordPolicyEnforcer extends AbstractPasswordPolicyEnforcer {

    private static final class LdapPasswordPolicyResult {

        private String dateResult = null;
        private String noWarnAttributeResult = null;
        private String userId = null;
        private String validDaysResult = null;
        private String warnDaysResult = null;

        public LdapPasswordPolicyResult(final String userId) {
            this.userId = userId;
        }

        public String getDateResult() {
            return this.dateResult;
        }

        public String getNoWarnAttributeResult() {
            return this.noWarnAttributeResult;
        }

        public String getUserId() {
            return this.userId;
        }

        public String getValidDaysResult() {
            return this.validDaysResult;
        }

        public String getWarnDaysResult() {
            return this.warnDaysResult;
        }

        public void setDateResult(final String date) {
            this.dateResult = date;
        }

        public void setNoWarnAttributeResult(final String noWarnAttributeResult) {
            this.noWarnAttributeResult = noWarnAttributeResult;
        }

        public void setValidDaysResult(final String valid) {
            this.validDaysResult = valid;
        }

        public void setWarnDaysResult(final String warn) {
            this.warnDaysResult = warn;
        }
    }

    /** Default time zone used in calculations. **/
    private static final DateTimeZone DEFAULT_TIME_ZONE = DateTimeZone.UTC;

    /** The default maximum number of results to return. */
    private static final int DEFAULT_MAX_NUMBER_OF_RESULTS = 10;

    /** The default timeout. */
    private static final int DEFAULT_TIMEOUT = 1000;

    private static final long YEARS_FROM_1601_1970 = 1970 - 1601;

    private static final int PASSWORD_STATUS_PASS = -1;

    /** Value set by AD that indicates an account whose password never expires. **/
    private static final double PASSWORD_STATUS_NEVER_EXPIRE = Math.pow(2, 63) - 1;

    /**
     * Consider leap years, divide by 4.
     * Consider non-leap centuries, (1700,1800,1900). 2000 is a leap century
     */
    private static final long TOTAL_SECONDS_FROM_1601_1970 =
            (YEARS_FROM_1601_1970 * 365 + YEARS_FROM_1601_1970 / 4 - 3) * 24 * 60 * 60;

    /** The list of valid scope values. */
    private static final int[] VALID_SCOPE_VALUES = new int[] {SearchControls.OBJECT_SCOPE,
            SearchControls.ONELEVEL_SCOPE, SearchControls.SUBTREE_SCOPE };

    /** The filter path to the lookup value of the user. */
    private String filter;

    /** Whether the LdapTemplate should ignore partial results. */
    private boolean ignorePartialResultException = false;

    /** LdapTemplate to execute ldap queries. */
    private LdapTemplate ldapTemplate;

    /** The maximum number of results to return. */
    private int maxNumberResults = LdapPasswordPolicyEnforcer.DEFAULT_MAX_NUMBER_OF_RESULTS;

    /** The attribute that contains the data that will determine if password warning is skipped.  */
    private String noWarnAttribute;

    /** The value that will cause password warning to be bypassed.  */
    private List<String> noWarnValues;

    /** The scope. */
    private int scope = SearchControls.SUBTREE_SCOPE;

    /** The search base to find the user under. */
    private String searchBase;

    /** The amount of time to wait. */
    private int timeout = LdapPasswordPolicyEnforcer.DEFAULT_TIMEOUT;

    /** default number of days a password is valid. */
    private int validDays = 180;

    /** default number of days that a warning message will be displayed. */
    private int warningDays = 30;

    /** The attribute that contains the date the password will expire or last password change. */
    protected String dateAttribute;

    /** The format of the date in DateAttribute. */
    protected String dateFormat;

    /** The attribute that contains the number of days the user's password is valid. */
    protected String validDaysAttribute;

    /** Disregard WarnPeriod and warn all users of password expiration. */
    protected Boolean warnAll = Boolean.FALSE;

    /** The attribute that contains the user's warning days. */
    protected String warningDaysAttribute;

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.notNull(this.ldapTemplate, "ldapTemplate cannot be null");
        Assert.notNull(this.filter, "filter cannot be null");
        Assert.notNull(this.searchBase, "searchBase cannot be null");
        Assert.notNull(this.warnAll, "warnAll cannot be null");
        Assert.notNull(this.dateAttribute, "dateAttribute cannot be null");
        Assert.notNull(this.dateFormat, "dateFormat cannot be null");
        Assert.isTrue(this.filter.contains("%u") || this.filter.contains("%U"), "filter must contain %u");

        this.ldapTemplate.setIgnorePartialResultException(this.ignorePartialResultException);

        for (final int element : VALID_SCOPE_VALUES) {
            if (this.scope == element) {
                return;
            }
        }
        throw new IllegalStateException("You must set a valid scope. Valid scope values are: "
                    + Arrays.toString(VALID_SCOPE_VALUES));
    }

    /**
     * {@inheritDoc}
     * @return Number of days left to the expiration date, or {@value #PASSWORD_STATUS_PASS}
     */
    @Override
    public long getNumberOfDaysToPasswordExpirationDate(final String userId)
            throws LdapPasswordPolicyEnforcementException {
        String msgToLog = null;

        final LdapPasswordPolicyResult ldapResult = getEnforcedPasswordPolicy(userId);

        if (ldapResult == null) {
            logger.debug("Ldap password policy cannot be established for [{}]. Skipping all checks...", userId);
            return PASSWORD_STATUS_PASS;
        }

        if (StringUtils.isBlank(ldapResult.getDateResult())) {
            logger.debug("Ldap password policy could not determine the date value for {}. Skipping all checks for [{}]...",
                    this.dateAttribute, userId);
            return PASSWORD_STATUS_PASS;
        }

        if (!StringUtils.isEmpty(this.noWarnAttribute)) {
            logger.debug("No warning attribute value for {} is set to ",
                    this.noWarnAttribute, ldapResult.getNoWarnAttributeResult());
        }

        if (isPasswordSetToNeverExpire(ldapResult.getNoWarnAttributeResult())) {
            logger.debug("Account password will never expire. Skipping password warning check...");

            return PASSWORD_STATUS_PASS;
        }

        if (StringUtils.isEmpty(ldapResult.getWarnDaysResult())) {
            logger.debug("No warning days value is found for {}. Using system default of {}",
                    userId, this.warningDays);
        } else {
            this.warningDays = Integer.parseInt(ldapResult.getWarnDaysResult());
        }

        if (StringUtils.isEmpty(ldapResult.getValidDaysResult())) {
            logger.debug("No maximum password valid days found for {}. Using system default of {} days",
                    ldapResult.getUserId(), this.validDays);
        } else {
            this.validDays = Integer.parseInt(ldapResult.getValidDaysResult());
        }

        final DateTime expireTime = getExpirationDateToUse(ldapResult.getDateResult());

        if (expireTime == null) {
            msgToLog = "Expiration date cannot be determined for date " + ldapResult.getDateResult();

            final LdapPasswordPolicyEnforcementException exc = new LdapPasswordPolicyEnforcementException(msgToLog);
            logger.error(msgToLog, exc);

            throw exc;
        }

        return getDaysToExpirationDate(userId, expireTime);
    }

    /**
     * Method to set the data source and generate a LDAPTemplate.
     *
     * @param contextSource the data source to use.
     */
    public void setContextSource(final ContextSource contextSource) {
        this.ldapTemplate = new LdapTemplate(contextSource);
    }

    /**
     * @param dateAttribute The DateAttribute to set.
     */
    public void setDateAttribute(final String dateAttribute) {
        this.dateAttribute = dateAttribute;
        logger.debug("Date attribute: {}", dateAttribute);
    }

    /**
     * @param dateFormat String to pass to SimpleDateFormat() that describes the
     * date in the ExpireDateAttribute. This parameter is required.
     */
    public void setDateFormat(final String dateFormat) {
        this.dateFormat = dateFormat;
        logger.debug("Date format: {}", dateFormat);
    }

    /**
     * @param filter The LDAP filter to set.
     */
    public void setFilter(final String filter) {
        this.filter = filter;

        logger.debug("Search filter: {}", filter);
    }

    public void setIgnorePartialResultException(final boolean ignorePartialResultException) {
        this.ignorePartialResultException = ignorePartialResultException;
    }

    /**
     * @param maxNumberResults The maxNumberResults to set.
     */
    public void setMaxNumberResults(final int maxNumberResults) {
        this.maxNumberResults = maxNumberResults;
    }

    /**
     * @param noWarnAttribute The noWarnAttribute to set.
     */
    public void setNoWarnAttribute(final String noWarnAttribute) {
        this.noWarnAttribute = noWarnAttribute;

        logger.debug("Attribute to flag warning bypass: {}", noWarnAttribute);
    }

    /**
     * @param noWarnValues The noWarnAttribute to set.
     */
    public void setNoWarnValues(final List<String> noWarnValues) {
        this.noWarnValues = noWarnValues;

        logger.debug("Value to flag warning bypass: {}", noWarnValues.toString());
    }

    /**
     * @param scope The scope to set.
     */
    public void setScope(final int scope) {
        this.scope = scope;
    }

    /**
     * @param searchBase The searchBase to set.
     */
    public void setSearchBase(final String searchBase) {
        this.searchBase = searchBase;
        logger.debug("Search base: {}", searchBase);
    }

    /**
     * @param timeout The timeout to set.
     */
    public void setTimeout(final int timeout) {
        this.timeout = timeout;
        logger.debug("Timeout: {}", this.timeout);
    }

    /**
     * @param validDays Number of days that a password is valid for.
     * Used as a default if DateAttribute is not set or is not found in the LDAP results
     */
    public void setValidDays(final int validDays) {
        this.validDays = validDays;
        logger.debug("Password valid days: {}", validDays);
    }

    /**
     * @param validDaysAttribute The ValidDaysAttribute to set.
     */
    public void setValidDaysAttribute(final String validDaysAttribute) {
        this.validDaysAttribute = validDaysAttribute;
        logger.debug("Valid days attribute: {}", validDaysAttribute);
    }

    /**
     * @param warnAll Disregard warningPeriod and warn all users of password expiration.
     */
    public void setWarnAll(final Boolean warnAll) {
        this.warnAll = warnAll;
        logger.debug("warnAll: {}", warnAll);
    }

    /**
     * @param warningDays Number of days before expiration that a warning
     * message is displayed to set. Used as a default if warningDaysAttribute is
     * not set or is not found in the LDAP results. This parameter is required.
     */
    public void setWarningDays(final int warningDays) {
        this.warningDays = warningDays;
        logger.debug("Default warningDays: {}", warningDays);
    }

    /**
     * @param warnDays The WarningDaysAttribute to set.
     */
    public void setWarningDaysAttribute(final String warnDays) {
        this.warningDaysAttribute = warnDays;
        logger.debug("Warning days attribute: {}", warnDays);
    }

    /***
     * Converts the numbers in Active Directory date fields for pwdLastSet, accountExpires,
     * lastLogonTimestamp, lastLogon, and badPasswordTime to a common date format.
     * @param dateValue date value to convert
     * @return {@link DateTime} converted to AD format
     */
    private DateTime convertDateToActiveDirectoryFormat(final String dateValue) {
        final long l = NumberUtils.toLong(dateValue.trim());

        final long totalSecondsSince1601 = l / 10000000;
        final long totalSecondsSince1970 = totalSecondsSince1601 - TOTAL_SECONDS_FROM_1601_1970;

        final DateTime dt = new DateTime(totalSecondsSince1970 * 1000, DEFAULT_TIME_ZONE);

        logger.info("Recalculated {} {} attribute to {}",
                this.dateFormat, this.dateAttribute, dt.toString());

        return dt;
    }

    /**
     * Parses and formats the retrieved date value from Ldap.
     * @param ldapResult the date result retrieved from ldap
     * @return newly constructed date object whose value was passed
     */
    private DateTime formatDateByPattern(final String ldapResult) {
        final DateTimeFormatter fmt = DateTimeFormat.forPattern(this.dateFormat);
        final DateTime date = new DateTime(DateTime.parse(ldapResult, fmt), DEFAULT_TIME_ZONE);
        return date;
    }

    /**
     * Determines the expiration date to use based on the settings.
     * @param ldapDateResult date result retrieved from ldap
     * @return Constructed {@link #org.joda.time.DateTime DateTime} object which indicates the expiration date
     */
    private DateTime getExpirationDateToUse(final String ldapDateResult) {
        DateTime dateValue = null;
        if (isUsingActiveDirectory()) {
            dateValue = convertDateToActiveDirectoryFormat(ldapDateResult);
        } else {
            dateValue = formatDateByPattern(ldapDateResult);
        }

        DateTime expireDate = dateValue.plusDays(this.validDays);
        logger.debug("Retrieved date value {} for date attribute {} and added {} days. The final expiration date is {}",
                dateValue.toString(), this.dateAttribute, this.validDays, expireDate.toString());

        return expireDate;
    }

    /**
     * Calculates the number of days left to the expiration date based on the
     * {@code expireDate} parameter.
     * @param expireDate password expiration date
     * @param userId the authenticating user id
     * @return number of days left to the expiration date, or {@value #PASSWORD_STATUS_PASS}
     * @throws LdapPasswordPolicyEnforcementException if authentication fails as the result of a date mismatch
     */
    private long getDaysToExpirationDate(final String userId, final DateTime expireDate)
            throws LdapPasswordPolicyEnforcementException {

        logger.debug("Calculating number of days left to the expiration date for user {}", userId);

        final DateTime currentTime = new DateTime(DEFAULT_TIME_ZONE);

        logger.info("Current date is {}, expiration date is {}", currentTime.toString(), expireDate.toString());

        final Days d = Days.daysBetween(currentTime, expireDate);
        int daysToExpirationDate = d.getDays();

        if (expireDate.equals(currentTime) || expireDate.isBefore(currentTime)) {
            final Formatter fmt = new Formatter();

            fmt.format("Authentication failed because account password has expired with %s ", daysToExpirationDate)
               .format("to expiration date. Verify the value of the %s attribute ", this.dateAttribute)
               .format("and ensure it's not before the current date, which is %s", currentTime.toString());

            final LdapPasswordPolicyEnforcementException exc =
                    new LdapPasswordPolicyEnforcementException(fmt.toString());

            logger.error(fmt.toString(), exc);
            IOUtils.closeQuietly(fmt);
            throw exc;
        }

        /*
         * Warning period begins from X number of ways before the expiration date
         */
        DateTime warnPeriod = new DateTime(DateTime.parse(expireDate.toString()), DEFAULT_TIME_ZONE);

        warnPeriod = warnPeriod.minusDays(this.warningDays);
        logger.info("Warning period begins on {}", warnPeriod.toString());

        if (this.warnAll) {
            logger.info("Warning all. The password for {} will expire in {} days.", userId, daysToExpirationDate);
        } else if (currentTime.equals(warnPeriod) || currentTime.isAfter(warnPeriod)) {
            logger.info("Password will expire in {} days.", daysToExpirationDate);
        } else {
            logger.info("Password is not expiring. {} days left to the warning", daysToExpirationDate);
            daysToExpirationDate = PASSWORD_STATUS_PASS;
        }

        return daysToExpirationDate;
    }

    private LdapPasswordPolicyResult getEnforcedPasswordPolicy(final String userId) {
        final LdapPasswordPolicyResult ldapResult = getResultsFromLdap(userId);
        if (ldapResult == null) {
            logger.warn("Ldap password policy could not be established for user {}.", userId);
        }
        return ldapResult;
    }

    /**
     * Retrieves the password policy results from the configured ldap repository based on the attributes defined.
     * @param userId authenticating user id
     * @return {@code null} if the user id cannot be found, or the {@code LdapPasswordPolicyResult} instance.
     */
    private LdapPasswordPolicyResult getResultsFromLdap(final String userId) {

        String[] attributeIds;
        final List<String> attributeList = new ArrayList<String>();

        attributeList.add(this.dateAttribute);

        if (this.warningDaysAttribute != null) {
            attributeList.add(this.warningDaysAttribute);
        }

        if (this.validDaysAttribute != null) {
            attributeList.add(this.validDaysAttribute);
        }

        if (this.noWarnAttribute != null) {
            attributeList.add(this.noWarnAttribute);
        }

        attributeIds = new String[attributeList.size()];
        attributeList.toArray(attributeIds);

        final String searchFilter = LdapUtils.getFilterWithValues(this.filter, userId);

        logger.debug("Starting search with searchFilter: {}", searchFilter);

        String attributeListLog = attributeIds[0];

        for (int i = 1; i < attributeIds.length; i++) {
            attributeListLog = attributeListLog.concat(":" + attributeIds[i]);
        }

        logger.debug("Returning attributes {}", attributeListLog);

        try {
            final AttributesMapper mapper = new AttributesMapper() {
                @Override
                public Object mapFromAttributes(final Attributes attrs) throws NamingException {
                    final LdapPasswordPolicyResult result = new LdapPasswordPolicyResult(userId);

                    if (LdapPasswordPolicyEnforcer.this.dateAttribute != null) {
                        if (attrs.get(LdapPasswordPolicyEnforcer.this.dateAttribute) != null) {
                            final String date = (String) attrs.get(LdapPasswordPolicyEnforcer.this.dateAttribute).get();
                            result.setDateResult(date);
                        }
                    }

                    if (LdapPasswordPolicyEnforcer.this.warningDaysAttribute != null) {
                        if (attrs.get(LdapPasswordPolicyEnforcer.this.warningDaysAttribute) != null) {
                            final String warn = (String) attrs.get(LdapPasswordPolicyEnforcer
                                    .this.warningDaysAttribute).get();
                            result.setWarnDaysResult(warn);
                        }
                    }

                    if (LdapPasswordPolicyEnforcer.this.noWarnAttribute != null) {
                        if (attrs.get(LdapPasswordPolicyEnforcer.this.noWarnAttribute) != null) {
                            final String attrib = (String) attrs.get(LdapPasswordPolicyEnforcer
                                    .this.noWarnAttribute).get();
                            result.setNoWarnAttributeResult(attrib);
                        }
                    }

                    if (attrs.get(LdapPasswordPolicyEnforcer.this.validDaysAttribute) != null) {
                        final String valid = (String) attrs.get(LdapPasswordPolicyEnforcer
                                .this.validDaysAttribute).get();
                        result.setValidDaysResult(valid);
                    }

                    return result;
                }
            };

            final List<?> resultList = this.ldapTemplate.search(this.searchBase,
                    searchFilter, getSearchControls(attributeIds), mapper);

            if (resultList.size() > 0) {
                return (LdapPasswordPolicyResult) resultList.get(0);
            }
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
        return null;

    }

    private SearchControls getSearchControls(final String[] attributeIds) {
        final SearchControls constraints = new SearchControls();

        constraints.setSearchScope(this.scope);
        constraints.setReturningAttributes(attributeIds);
        constraints.setTimeLimit(this.timeout);
        constraints.setCountLimit(this.maxNumberResults);

        return constraints;
    }

    /**
     * Determines if the password value is set to never expire.
     * It will check the value against the previously defined list of {@link #noWarnValues}.
     * If that fails, checks the value against {@link #PASSWORD_STATUS_NEVER_EXPIRE}
     *
     * @param pswValue retrieved password value
     * @return boolean that indicates whether  or not password warning should proceed.
     */
    private boolean isPasswordSetToNeverExpire(final String pswValue) {
        boolean ignoreChecks = this.noWarnValues.contains(pswValue);
        if (!ignoreChecks && StringUtils.isNumeric(pswValue)) {
            final double psw = Double.parseDouble(pswValue);
            ignoreChecks = psw == PASSWORD_STATUS_NEVER_EXPIRE;
        }
        return ignoreChecks;
    }

    /**
     * Determines whether the {@link #dateFormat} field is configured for ActiveDirectory.
     * Accepted values are {@code ActiveDirectory} or {@code AD}
     * @return boolean that says whether or not {@link #dateFormat} is defined for ActiveDirectory.
     */
    private boolean isUsingActiveDirectory() {
        return this.dateFormat.equalsIgnoreCase("ActiveDirectory") || this.dateFormat.equalsIgnoreCase("AD");
    }
}
