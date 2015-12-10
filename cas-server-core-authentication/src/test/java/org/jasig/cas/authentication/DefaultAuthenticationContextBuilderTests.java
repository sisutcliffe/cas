package org.jasig.cas.authentication;


import org.jasig.cas.authentication.handler.support.SimpleTestUsernamePasswordAuthenticationHandler;
import org.jasig.cas.authentication.principal.DefaultPrincipalFactory;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/**
 * This is {@link DefaultAuthenticationContextBuilderTests}.
 *
 * @author Misagh Moayyed
 * @since 4.2.0
 */
public class DefaultAuthenticationContextBuilderTests {

    private AuthenticationManager manager;
    private DefaultAuthenticationContextBuilder builder;

    @Before
    public void setup() {
        this.builder = new DefaultAuthenticationContextBuilder();
        final DefaultPrincipalElectionStrategy principalStrategy = new DefaultPrincipalElectionStrategy();
        principalStrategy.setPrincipalFactory(new DefaultPrincipalFactory());
        builder.setPrincipalElectionStrategy(principalStrategy);

        final AcceptUsersAuthenticationHandler handler = new AcceptUsersAuthenticationHandler();
        handler.setUsers(Collections.singletonMap("casuser", "Mellon"));
        this.manager = new PolicyBasedAuthenticationManager(handler, new SimpleTestUsernamePasswordAuthenticationHandler());
    }

    @Test
    public void checkBuilderWithSingleAuthN() throws Exception {
        final Authentication authN = manager.authenticate(TestUtils.getCredentialsWithSameUsernameAndPassword());
        builder.collect(authN);
        final AuthenticationContext ctx = builder.build();
        assertNotNull(ctx);
        assertNotNull(ctx.getAuthentication());
        assertEquals(ctx.getAuthentication().getPrincipal(), authN.getPrincipal());
    }

    @Test
    public void checkBuilderWithManyAuthNAsDuplicate() throws Exception {
        final Authentication authN1 = manager.authenticate(TestUtils.getCredentialsWithSameUsernameAndPassword());
        builder.collect(authN1);

        final Authentication authN2 = manager.authenticate(TestUtils.getCredentialsWithSameUsernameAndPassword());
        builder.collect(authN2);

        assertEquals(builder.size(), 1);

        final AuthenticationContext ctx = builder.build();
        assertNotNull(ctx);
        assertNotNull(ctx.getAuthentication());
        assertEquals(ctx.getAuthentication().getPrincipal(), authN1.getPrincipal());
    }

    @Test
    public void checkBuilderWithDuplicateAuthN() throws Exception {
        final Authentication authN1 = manager.authenticate(TestUtils.getCredentialsWithSameUsernameAndPassword());
        builder.collect(authN1);
        builder.collect(authN1);
        assertEquals(builder.size(), 1);
    }

    @Test
    public void verifyBuilderAttributesWithManyAuthN() throws Exception {
        final Authentication authN1 = manager.authenticate(TestUtils.getCredentialsWithSameUsernameAndPassword());
        builder.collect(authN1);

        final Authentication authN2 = manager.authenticate(TestUtils.getCredentialsWithDifferentUsernameAndPassword("casuser", "Mellon"));
        builder.collect(authN2);

        assertEquals(builder.size(), 2);

        final AuthenticationContext ctx = builder.build();
        assertNotNull(ctx);
        assertNotNull(ctx.getAuthentication());
        assertEquals(ctx.getAuthentication().getPrincipal(), authN1.getPrincipal());
        assertEquals(ctx.getAuthentication().getAttributes().size(), 1);
        assertTrue(ctx.getAuthentication().getAttributes().containsKey(AuthenticationManager.AUTHENTICATION_METHOD_ATTRIBUTE));
    }
}
