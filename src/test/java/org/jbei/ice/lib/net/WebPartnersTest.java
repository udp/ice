package org.jbei.ice.lib.net;

import org.jbei.ice.lib.AccountCreator;
import org.jbei.ice.lib.access.AccessTokens;
import org.jbei.ice.lib.access.TokenVerification;
import org.jbei.ice.lib.dto.web.RegistryPartner;
import org.jbei.ice.lib.dto.web.RemotePartnerStatus;
import org.jbei.ice.storage.hibernate.HibernateUtil;
import org.jbei.ice.storage.model.Account;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Hector Plahar
 */
public class WebPartnersTest {

    private WebPartners otherPartner;
    private WebPartners thisPartner;

    @Before
    public void setUp() throws Exception {
        HibernateUtil.initializeMock();
        HibernateUtil.beginTransaction();

        thisPartner = createThisPartnerObject();

        RemoteContact remoteContact = new RemoteContact() {
            public RegistryPartner contactPotentialPartner(RegistryPartner partner, String url) {
                AccessTokens.setToken(url, partner.getApiKey());
                return thisPartner.processRemoteWebPartnerAdd(partner);
            }

            public boolean apiKeyValidates(String myURL, RegistryPartner registryPartner) {
                RegistryPartner partner = thisPartner.get(registryPartner.getApiKey(), registryPartner.getUrl());
                return partner != null;
            }
        };

        otherPartner = new WebPartners(remoteContact) {
            protected boolean isInWebOfRegistries() {
                return true;
            }

            protected RegistryPartner getThisInstanceWithNewApiKey() {
                String myURL = "registry-test2.jbei.org";
                RegistryPartner thisPartner = new RegistryPartner();
                String myName = "Registry test2";
                thisPartner.setName(myName);
                thisPartner.setUrl(myURL);
                thisPartner.setApiKey("abc");
                return thisPartner;
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        HibernateUtil.commitTransaction();
    }

    @Test
    public void testGet() throws Exception {

    }

    @Test
    public void testAddNewPartner() throws Exception {
        Account admin = AccountCreator.createTestAccount("WebPartnersTest.testAddNewPartner", true);
        String adminUser = admin.getEmail();
        WebPartners partners = createThisPartnerObject();

        // create registryPartner for add
        RegistryPartner partner = new RegistryPartner();
        partner.setUrl("registry-test2.jbei.org");
        RegistryPartner added = partners.addNewPartner(adminUser, partner);
        Assert.assertNotNull(added);
        Assert.assertEquals(partner.getUrl(), "registry-test2.jbei.org");
        Assert.assertEquals(added.getStatus(), RemotePartnerStatus.APPROVED);
    }

    @Test
    public void testUpdateAPIKey() throws Exception {
        Account admin = AccountCreator.createTestAccount("WebPartnersTest.testUpdateAPIKey", true);
        String adminUser = admin.getEmail();

        // add reg-test2 as new partner
        RegistryPartner partner = new RegistryPartner();
        partner.setUrl("registry-test2.jbei.org");
        WebPartners webPartners = createThisPartnerObject();
        RegistryPartner added = webPartners.addNewPartner(adminUser, partner);
        Assert.assertNotNull(added);
        Assert.assertEquals(added.getStatus(), RemotePartnerStatus.APPROVED);
        String apiKey = partner.getApiKey();
        Assert.assertNotNull(apiKey);

        // update api keys with new partner
        RegistryPartner partner2 = webPartners.updateAPIKey(adminUser, added.getId());
        Assert.assertNotNull(partner2);
    }
//
//    @Test
//    public void testUpdate() throws Exception {
//        Account admin = AccountCreator.createTestAccount("WebPartnersTest.testUpdate", true);
//        String adminUser = admin.getEmail();
//
//    }

    private RemoteContact createRemoteContact() {
        return new RemoteContact() {
            public RegistryPartner refreshPartnerKey(RegistryPartner partner, String url, String worToken) {
                TokenVerification tokenVerification = new TokenVerification();
                Assert.assertNotNull(tokenVerification.verifyPartnerToken(partner.getUrl(), worToken));
                return otherPartner.updateRemoteAPIKey(partner.getUrl(), partner);
            }

            public RegistryPartner contactPotentialPartner(RegistryPartner partner, String url) {
                AccessTokens.setToken(partner.getUrl(), partner.getApiKey());
                return otherPartner.processRemoteWebPartnerAdd(partner);
            }

            public boolean apiKeyValidates(String myURL, RegistryPartner registryPartner) {
                RegistryPartner partner = otherPartner.get(registryPartner.getApiKey(), registryPartner.getUrl());
                return partner != null;
            }
        };
    }

    private WebPartners createThisPartnerObject() {
        return new WebPartners(createRemoteContact()) {
            protected boolean isInWebOfRegistries() {
                return true;
            }

            protected RegistryPartner getThisInstanceWithNewApiKey() {
                String myURL = "registry-test.jbei.org";
                RegistryPartner thisPartner = new RegistryPartner();
                String myName = "Registry test";
                thisPartner.setName(myName);
                thisPartner.setUrl(myURL);
                thisPartner.setApiKey("efg");
                return thisPartner;
            }
        };
    }
}