package org.jbei.ice.lib.net;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.jbei.ice.lib.access.AccessTokens;
import org.jbei.ice.lib.access.PermissionException;
import org.jbei.ice.lib.account.AccountController;
import org.jbei.ice.lib.account.TokenHash;
import org.jbei.ice.lib.common.logging.Logger;
import org.jbei.ice.lib.dto.ConfigurationKey;
import org.jbei.ice.lib.dto.web.RegistryPartner;
import org.jbei.ice.lib.dto.web.RemotePartnerStatus;
import org.jbei.ice.lib.utils.Utils;
import org.jbei.ice.storage.DAOFactory;
import org.jbei.ice.storage.hibernate.dao.RemotePartnerDAO;
import org.jbei.ice.storage.model.RemotePartner;

import java.util.Date;

/**
 * Partners for web of registries
 *
 * @author Hector Plahar
 */
public class WebPartners {

    private final RemotePartnerDAO dao;
    private final TokenHash tokenHash;
    private RemoteContact remoteContact;
    private final AccountController accountController;

    public WebPartners() {
        this.dao = DAOFactory.getRemotePartnerDAO();
        this.tokenHash = new TokenHash();
        this.remoteContact = new RemoteContact();
        this.accountController = new AccountController();
    }

    public WebPartners(RemoteContact remoteContact) {
        this();
        this.remoteContact = remoteContact;
    }

    /**
     * Retrieve a partner based on partner token and unique identifier
     *
     * @param token partner token generated by this ICE instance and sent to other instance
     * @param url   unique identifier for partner instance
     * @return found partner
     */
    public RegistryPartner get(String token, String url) {
        String urlToken = AccessTokens.getUrlToken(url);
        if (urlToken == null || token == null || !token.equalsIgnoreCase(urlToken))
            return null;

        RemotePartner remotePartner = dao.getByUrl(url);
        if (remotePartner == null) {
            // likely scenario
            RegistryPartner partner = new RegistryPartner();
            partner.setUrl(url);
            return partner;
        }

        return remotePartner.toDataTransferObject();
    }

    /**
     * Process a web partner add request from a remote instance
     *
     * @param newPartner information about partner
     * @return information about this ICE instance (name, url) with a token that is to be sent
     * as a response
     */
    public RegistryPartner processRemoteWebPartnerAdd(RegistryPartner newPartner) {
        if (!isInWebOfRegistries())
            return null;

        if (newPartner == null || StringUtils.isEmpty(newPartner.getApiKey())) {
            String errMsg = "Cannot add partner with null info or no api key";
            Logger.error(errMsg);
            throw new IllegalArgumentException(errMsg);
        }

        String partnerUrl = newPartner.getUrl();
        if (partnerUrl == null || !isValidUrl(partnerUrl)) {
            Logger.error("Invalid url " + partnerUrl);
            return null;
        }
        return handleRemoteAddRequest(newPartner);
    }

    /**
     * Validates the url by pre-pending "https://" as the scheme
     *
     * @param url without scheme
     * @return true if url validates successfully, false otherwise
     */
    protected boolean isValidUrl(String url) {
        url = "https://" + url;
        UrlValidator validator = new UrlValidator();
        return validator.isValid(url);
    }

    public boolean remove(String userId, long id) {
        if (!accountController.isAdministrator(userId))
            throw new PermissionException(userId + " is not an admin");

        RemotePartner partner = dao.get(id);
        if (partner == null)
            return false;

        dao.delete(partner);
        // todo : contact deleted partner since they cannot contact anymore?
        return true;
    }

    protected String getThisUri() {
        return Utils.getConfigValue(ConfigurationKey.URI_PREFIX);
    }

    public RegistryPartner updateAPIKey(String userId, long id) {
        if (!isInWebOfRegistries())
            return null;

        if (!accountController.isAdministrator(userId))
            throw new PermissionException(userId + " is not an admin");

        RegistryPartner thisPartner = getThisInstanceWithNewApiKey();
        if (thisPartner == null) {
            Logger.error("Cannot exchange api token with remote host due to invalid local url");
            return null;
        }

        RemotePartner partner = dao.get(id);
        if (partner == null) {
            throw new IllegalArgumentException("Cannot retrieve partner with id " + id);
        }

        // contact partner (with new key) to refresh its api key for this partner
        RegistryPartner remotePartner = remoteContact.refreshPartnerKey(thisPartner, partner.getUrl(),
                partner.getApiKey());
        if (remotePartner == null) {
            // contact failed (keeping existing key)
            Logger.error("Remote contact of partner " + partner.getUrl() + " to update api key failed");
            return null;
        }

        // contact succeeded with return of api key, generate new salt
        partner.setSalt(tokenHash.generateSalt());
        String hash = tokenHash.encryptPassword(thisPartner.getApiKey() + remotePartner.getUrl(), partner.getSalt());
        partner.setAuthenticationToken(hash);
        partner.setApiKey(remotePartner.getApiKey()); // todo : check api key (validate?)
        partner = dao.update(partner);
        return partner.toDataTransferObject();
    }

    /**
     * Updates the api token of a remote partner using information sent by that partner
     *
     * @param url           URL of partner making request. This is obtained from the old api key
     * @param remotePartner information sent by remote partner
     * @return information about this partner including a new api token
     */
    public RegistryPartner updateRemoteAPIKey(String url, RegistryPartner remotePartner) {
        RemotePartner remotePartnerModel = dao.getByUrl(url);
        if (remotePartnerModel == null) {
            Logger.error("Could not find a local record of partner with url " + url);
            // todo : so create a new one?
            return null;
        }

        Logger.info("Refreshing local api key for " + url);
        RegistryPartner thisInstance = getThisInstanceWithNewApiKey();
        remotePartnerModel.setUrl(remotePartner.getUrl());
        if (!StringUtils.isEmpty(remotePartner.getName()))
            remotePartnerModel.setName(remotePartner.getName());
        remotePartnerModel.setApiKey(remotePartner.getApiKey()); // todo : no need to validate since url is authenticated
        String salt = tokenHash.generateSalt();
        remotePartnerModel.setSalt(salt);
        String hash = tokenHash.encryptPassword(thisInstance.getApiKey() + remotePartner.getUrl(), salt);
        remotePartnerModel.setAuthenticationToken(hash);
        dao.update(remotePartnerModel);

        return thisInstance;
    }

    // only updates the status
    public RegistryPartner update(String userId, long id, RegistryPartner partner) {
        if (!isInWebOfRegistries())
            return null;

        if (!accountController.isAdministrator(userId))
            throw new PermissionException(userId + " is not an admin");

        RemotePartner existing = dao.get(id);
        if (existing == null)
            throw new IllegalArgumentException("Cannot retrieve partner with id " + id);

        Logger.info(userId + ": updating partner (" + existing.getUrl() + ") to " + partner.toString());
        existing.setPartnerStatus(partner.getStatus());
        return dao.update(existing).toDataTransferObject();
    }

    /**
     * Adds the registry instance specified by the url to the list of existing partners (if not already in there)
     * and sends a request to the remote instance that includes a security token that the remote instance
     * can use to communicate with this instance.
     * <p>
     * Information about the remote instance is still saved even when it cannot be communicated with. This
     * allows a future communication attempt.
     *
     * @param userId  id of user performing action (must have admin privileges)
     * @param partner registry partner object that contains unique uniform resource identifier for the registry
     * @return add partner ofr
     */
    public RegistryPartner addNewPartner(String userId, RegistryPartner partner) {
        if (!isInWebOfRegistries())
            return null;

        // check for admin privileges before granting request
        if (!accountController.isAdministrator(userId))
            throw new PermissionException("Non admin attempting to add remote partner");

        if (StringUtils.isEmpty(partner.getUrl()))
            throw new IllegalArgumentException("Cannot add partner without valid url");

        // check if there is a partner with that url
        RemotePartner remotePartner = dao.getByUrl(partner.getUrl());
        if (remotePartner != null) {
            // if so just update the api key
            return updateAPIKey(userId, remotePartner.getId());
        }

        Logger.info(userId + ": adding WoR partner [" + partner.getUrl() + "]");

        // create information about this instance to send to potential partner
        // including a random token for use when contacting this instance
        RegistryPartner thisPartner = getThisInstanceWithNewApiKey();

        // check that url is valid (rest client pre-prepends https so do the same)
        if (thisPartner == null) {
            // will not contact
            Logger.error("Cannot exchange api token with remote host due to invalid local url");
            remotePartner = new RemotePartner();
            remotePartner.setName(partner.getName());
            remotePartner.setUrl(partner.getUrl());
            remotePartner.setPartnerStatus(RemotePartnerStatus.NOT_CONTACTED);
            remotePartner.setAdded(new Date());
            return dao.create(remotePartner).toDataTransferObject();

        } else {
            RegistryPartner newPartner = remoteContact.contactPotentialPartner(thisPartner, partner.getUrl());
            if (newPartner == null) {
                // contact failed
                Logger.error("Remote contact of partner " + partner.getUrl() + " failed");
                partner.setStatus(RemotePartnerStatus.CONTACT_FAILED);
            } else {
                // contact succeeded with return of api key
                partner.setStatus(RemotePartnerStatus.APPROVED);
                partner.setApiKey(newPartner.getApiKey()); // todo : check api key (validate?)
            }
        }

        // if status is not approved, then the token is irrelevant since it is not stored and was not
        // successfully transmitted
        return createRemotePartnerObject(partner, thisPartner.getApiKey());
    }

    /**
     * Handles requests from remote ice instances that will like to be in a WoR config with this instance
     * Serves the dual purpose of:
     * <ul>
     * <li>please add me as a partner to your list with token</li>
     * <li>add accepted; use this as the authorization token</li>
     * </ul>
     * <p>
     * Note that the request is rejected if this ICE instance has not opted to be a member of web of
     * registries
     *
     * @param request partner request object containing all information needed with a validated url
     * @return information about this instance to be sent to the remote
     */
    protected RegistryPartner handleRemoteAddRequest(RegistryPartner request) {
        if (request == null || StringUtils.isEmpty(request.getApiKey())) {
            Logger.error("Received invalid partner add request");
            return null;
        }

        Logger.info("Processing request to connect by " + request.getUrl());

        String myURL = getThisUri();
        if (request.getUrl().equalsIgnoreCase(myURL))
            return null;
        boolean apiKeyValidates = remoteContact.apiKeyValidates(myURL, request);
        if (!apiKeyValidates) {
            Logger.error("Received api token could not be validated");
            return null;
        }

        // request should contain api key for use to contact third party
        RemotePartner partner = dao.getByUrl(request.getUrl());
        RegistryPartner thisInstance = getThisInstanceWithNewApiKey();

        // create new partner object or update existing with new token hash
        if (partner != null) {
            Logger.info("Updating authentication for existing");
            // validated. update the authorization token
            partner.setApiKey(request.getApiKey());
            partner.setSalt(tokenHash.generateSalt());
            partner.setAuthenticationToken(tokenHash.encryptPassword(thisInstance.getApiKey() +
                    request.getUrl(), partner.getSalt()));
            dao.update(partner);
        } else {
            // save in db
            request.setStatus(RemotePartnerStatus.APPROVED);
            createRemotePartnerObject(request, thisInstance.getApiKey());
        }
        Logger.info("Successfully added remote partner " + request.getUrl());

        // send information about this instance (with token) as response
        return thisInstance;
    }

    protected RegistryPartner getThisInstanceWithNewApiKey() {
        String myURL = getThisUri();
        if (!isValidUrl(myURL))
            return null;

        RegistryPartner thisPartner = new RegistryPartner();
        String myName = Utils.getConfigValue(ConfigurationKey.PROJECT_NAME);
        thisPartner.setName(myName);
        thisPartner.setUrl(myURL);
        thisPartner.setApiKey(tokenHash.generateRandomToken());
        return thisPartner;
    }

    /**
     * Checks if the web of registries admin config value has been set to enable this ICE instance
     * to join the web of registries configuration
     *
     * @return true if value has been set to the affirmative, false otherwise
     */
    protected boolean isInWebOfRegistries() {
        String value = Utils.getConfigValue(ConfigurationKey.JOIN_WEB_OF_REGISTRIES);
        return ("yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value));
    }

    protected RegistryPartner createRemotePartnerObject(RegistryPartner newPartner, String token) {
        RemotePartner remotePartner = new RemotePartner();
        remotePartner.setName(newPartner.getName());
        remotePartner.setUrl(newPartner.getUrl());
        remotePartner.setPartnerStatus(newPartner.getStatus());
        if (newPartner.getStatus() == RemotePartnerStatus.APPROVED) {
            remotePartner.setSalt(tokenHash.generateSalt());
            String hash = tokenHash.encryptPassword(token + newPartner.getUrl(), remotePartner.getSalt());
            remotePartner.setAuthenticationToken(hash);
            remotePartner.setApiKey(newPartner.getApiKey());
        }
        remotePartner.setAdded(new Date());
        return dao.create(remotePartner).toDataTransferObject();
    }
}
