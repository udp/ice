package org.jbei.ice.services.webservices;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;

import org.apache.commons.lang.NotImplementedException;
import org.jbei.ice.controllers.AccountController;
import org.jbei.ice.controllers.EntryController;
import org.jbei.ice.controllers.SampleController;
import org.jbei.ice.controllers.SearchController;
import org.jbei.ice.controllers.SequenceAnalysisController;
import org.jbei.ice.controllers.SequenceController;
import org.jbei.ice.controllers.StorageController;
import org.jbei.ice.controllers.common.ControllerException;
import org.jbei.ice.lib.authentication.InvalidCredentialsException;
import org.jbei.ice.lib.composers.formatters.FastaFormatter;
import org.jbei.ice.lib.composers.formatters.GenbankFormatter;
import org.jbei.ice.lib.logging.Logger;
import org.jbei.ice.lib.managers.AccountManager;
import org.jbei.ice.lib.managers.EntryManager;
import org.jbei.ice.lib.managers.ManagerException;
import org.jbei.ice.lib.managers.StorageManager;
import org.jbei.ice.lib.models.Account;
import org.jbei.ice.lib.models.Entry;
import org.jbei.ice.lib.models.EntryFundingSource;
import org.jbei.ice.lib.models.Link;
import org.jbei.ice.lib.models.Name;
import org.jbei.ice.lib.models.Part;
import org.jbei.ice.lib.models.Plasmid;
import org.jbei.ice.lib.models.Sample;
import org.jbei.ice.lib.models.SelectionMarker;
import org.jbei.ice.lib.models.Sequence;
import org.jbei.ice.lib.models.SessionData;
import org.jbei.ice.lib.models.Storage;
import org.jbei.ice.lib.models.Storage.StorageType;
import org.jbei.ice.lib.models.Strain;
import org.jbei.ice.lib.models.TraceSequence;
import org.jbei.ice.lib.parsers.GeneralParser;
import org.jbei.ice.lib.permissions.PermissionException;
import org.jbei.ice.lib.search.blast.BlastResult;
import org.jbei.ice.lib.search.blast.ProgramTookTooLongException;
import org.jbei.ice.lib.search.lucene.SearchResult;
import org.jbei.ice.lib.utils.SerializationUtils;
import org.jbei.ice.lib.vo.FeaturedDNASequence;
import org.jbei.ice.lib.vo.IDNASequence;
import org.jbei.ice.lib.vo.SequenceTraceFile;
import org.jbei.ice.web.common.ViewException;

@WebService(targetNamespace = "https://api.registry.jbei.org/")
public class RegistryAPI {
    public String login(@WebParam(name = "login") String login,
            @WebParam(name = "password") String password) throws SessionException, ServiceException {
        String sessionId = null;

        try {
            SessionData sessionData = AccountController.authenticate(login, password);

            sessionId = sessionData.getSessionKey();
        } catch (InvalidCredentialsException e) {
            Logger.warn("Invalid credentials provided by user: " + login);

            throw new SessionException("Invalid credentials!");
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        log("User by login '" + login + "' successfully logged in");

        return sessionId;
    }

    public void logout(@WebParam(name = "sessionId") String sessionId) throws ServiceException {
        try {
            AccountController.deauthenticate(sessionId);
            log(sessionId, "Logged out");
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }
    }

    public boolean isAuthenticated(@WebParam(name = "sessionId") String sessionId)
            throws ServiceException {
        boolean authenticated = false;

        try {
            authenticated = AccountController.isAuthenticated(sessionId);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return authenticated;
    }

    /**
     * Ideally this must be folded into login() by using an extra param
     * 
     * @param sessionId
     *            valid session id to ensure user successfully logged in.
     * @param login
     *            user login being checked for moderator status
     * @return true if user is a designated moderator, false otherwise
     */
    public boolean isModerator(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "login") String login) throws SessionException, ServiceException {
        Account account = validateAccount(sessionId);

        try {
            return AccountController.isModerator(account);
        } catch (ControllerException e) {
            Logger.error(e);
            throw new ServiceException("Error accessing account for " + login);
        }
    }

    public Entry getEntryByName(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "name") String name) throws ServiceException {
        log(sessionId, "getEntryByName: " + name);
        try {
            EntryController entryController = getEntryController(sessionId);
            return entryController.getByName(name);
        } catch (ControllerException e) {
            Logger.error(e);
            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            Logger.error(e);
            throw new ServiceException("No permission to view entry with name " + name);
        } catch (Exception e) {
            Logger.error(e);
            throw new ServiceException("Registry Service Internal Error!");
        }
    }

    public long getNumberOfPublicEntries() throws ServiceException {
        long result = 0;

        try {
            result = EntryManager.getNumberOfVisibleEntries();
        } catch (ManagerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return result;
    }

    public ArrayList<SearchResult> search(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "query") String query) throws ServiceException, SessionException {
        ArrayList<SearchResult> results = null;

        log(sessionId, "search: " + query);
        try {
            SearchController searchController = getSearchController(sessionId);

            results = searchController.find(query);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return results;
    }

    public ArrayList<BlastResult> blastn(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "querySequence") String querySequence) throws SessionException,
            ServiceException {
        log(sessionId, "blastn: " + querySequence);
        ArrayList<BlastResult> results = null;

        try {
            SearchController searchController = getSearchController(sessionId);

            results = searchController.blastn(querySequence);

        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (ProgramTookTooLongException e) {
            Logger.error(e);

            throw new ServiceException(
                    "It took to long to search for sequence, try shorter sequence.");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return results;
    }

    public ArrayList<BlastResult> tblastx(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "querySequence") String querySequence) throws SessionException,
            ServiceException {
        log(sessionId, "tblastx: " + querySequence);
        ArrayList<BlastResult> results = null;

        try {
            SearchController searchController = getSearchController(sessionId);

            results = searchController.tblastx(querySequence);

        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (ProgramTookTooLongException e) {
            Logger.error(e);

            throw new ServiceException(
                    "It took to long to search for sequence, try shorter sequence.");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return results;
    }

    public Entry getByRecordId(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "getByRecordId: " + entryId);
        Entry entry = null;

        try {
            EntryController entryController = getEntryController(sessionId);

            entry = entryController.getByRecordId(entryId);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permissions to read this entry by entryId: "
                    + entryId);
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return entry;
    }

    public Entry getByPartNumber(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "partNumber") String partNumber) throws SessionException,
            ServiceException, ServicePermissionException {
        log(sessionId, "getByPartNumber: " + partNumber);
        Entry entry = null;

        try {
            EntryController entryController = getEntryController(sessionId);

            entry = entryController.getByPartNumber(partNumber);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            throw new ServicePermissionException(
                    "No permissions to read this entry by partNumber: " + partNumber);
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return entry;
    }

    public boolean hasReadPermissions(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "hasReadPermission: " + entryId);
        boolean result = false;

        try {
            EntryController entryController = getEntryController(sessionId);

            result = entryController.hasReadPermissionByRecordId(entryId);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return result;
    }

    public boolean hasWritePermissions(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException {
        log(sessionId, "hasWritePermissions: " + entryId);
        boolean result = false;

        try {
            EntryController entryController = getEntryController(sessionId);

            result = entryController.hasWritePermissionByRecordId(entryId);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return result;
    }

    public Plasmid createPlasmid(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "plasmid") Plasmid plasmid) throws SessionException, ServiceException {
        log(sessionId, "createPlasmid");
        Entry newEntry = null;
        try {
            EntryController entryController = getEntryController(sessionId);

            Entry remoteEntry = createEntry(sessionId, plasmid);

            newEntry = entryController.createEntry(remoteEntry);

            log("User '" + entryController.getAccount().getEmail() + "' created plasmid: '"
                    + plasmid.getRecordId() + "', " + plasmid.getId());
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return (Plasmid) newEntry;
    }

    public Strain createStrain(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "strain") Strain strain) throws SessionException, ServiceException {
        log(sessionId, "createStrain");
        Entry newEntry = null;
        try {
            EntryController entryController = getEntryController(sessionId);

            Entry remoteEntry = createEntry(sessionId, strain);

            newEntry = entryController.createEntry(remoteEntry);

            log("User '" + entryController.getAccount().getEmail() + "' created strain: '"
                    + strain.getRecordId() + "', " + strain.getId());
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return (Strain) newEntry;
    }

    public Part createPart(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "part") Part part) throws SessionException, ServiceException {
        log(sessionId, "createPart");
        Entry newEntry = null;
        try {
            EntryController entryController = getEntryController(sessionId);

            Entry remoteEntry = createEntry(sessionId, part);

            newEntry = entryController.createEntry(remoteEntry);

            log("User '" + entryController.getAccount().getEmail() + "' created part: '"
                    + part.getRecordId() + "', " + part.getId());
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return (Part) newEntry;
    }

    public Plasmid updatePlasmid(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "plasmid") Plasmid plasmid) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "updatePlasmid");
        Entry savedEntry = null;

        try {
            EntryController entryController = getEntryController(sessionId);

            savedEntry = entryController.save(updateEntry(sessionId, plasmid));

            log("User '" + entryController.getAccount().getEmail() + "' update plasmid: '"
                    + savedEntry.getRecordId() + "', " + savedEntry.getId());
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permissions to save this entry!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return (Plasmid) savedEntry;
    }

    public Strain updateStrain(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "strain") Strain strain) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "updateStrain");
        Entry savedEntry = null;

        try {
            EntryController entryController = getEntryController(sessionId);

            savedEntry = entryController.save(updateEntry(sessionId, strain));

            log("User '" + entryController.getAccount().getEmail() + "' update strain: '"
                    + savedEntry.getRecordId() + "', " + savedEntry.getId());
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permissions to save this entry!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return (Strain) savedEntry;
    }

    public Part updatePart(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "part") Part part) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "updatePart");
        Entry savedEntry = null;

        try {
            EntryController entryController = getEntryController(sessionId);

            savedEntry = entryController.save(updateEntry(sessionId, part));

            log("User '" + entryController.getAccount().getEmail() + "' update part: '"
                    + savedEntry.getRecordId() + "', " + savedEntry.getId());
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permissions to save this entry!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return (Part) savedEntry;
    }

    protected Entry createEntry(String sessionId, Entry entry) throws SessionException,
            ServiceException {
        if (entry == null) {
            throw new ServiceException("Failed to create null Entry!");
        }

        // Validate recordType
        if (entry instanceof Plasmid) {
            entry.setRecordType("plasmid");
        } else if (entry instanceof Strain) {
            entry.setRecordType("strain");
        } else if (entry instanceof Part) {
            entry.setRecordType("part");
        } else {
            throw new ServiceException(
                    "Invalid entry class! Accepted entries with classes Plasmid, Strain and Part.");
        }

        // Validate creator
        if (entry.getCreator() == null || entry.getCreator().isEmpty()) {
            throw new ServiceException("Creator is mandatory field!");
        }

        // Validate owner and ownerEmail
        if (entry.getOwner() == null || entry.getOwner().isEmpty() || entry.getOwnerEmail() == null
                || entry.getOwnerEmail().isEmpty()) {
            throw new ServiceException("Owner and OwnerEmail are mandatory fields!");
        }

        // Validate short description
        if (entry.getShortDescription() == null || entry.getShortDescription().isEmpty()) {
            throw new ServiceException("Short Description is mandatory field!");
        }

        // Validate status
        if (entry.getStatus() == null) {
            throw new ServiceException(
                    "Invalid status! Expected type: 'complete', 'in progress' or 'planned'.");
        } else if (!entry.getStatus().equals("complete")
                && !entry.getStatus().equals("in progress") && !entry.getStatus().equals("planned")) {
            throw new ServiceException(
                    "Invalid status! Expected type: 'complete', 'in progress' or 'planned'.");
        }

        // Validate bioSafetyLevel
        if (entry.getBioSafetyLevel() != 1 && entry.getBioSafetyLevel() != 2) {
            throw new ServiceException("Invalid bio safety level! Expected: '1' or '2'");
        }

        // Validate name
        if (entry.getNames() == null || entry.getNames().size() == 0) {
            throw new ServiceException("Name is mandatory! Expected at least one name.");
        } else {
            for (Name name : entry.getNames()) {
                if (name.getName() == null || name.getName().isEmpty()) {
                    throw new ServiceException("Name can't be null or empty!");
                }

                name.setEntry(entry);
            }
        }

        // Validate selection markers
        if (entry.getSelectionMarkers() != null && entry.getSelectionMarkers().size() > 0) {
            for (SelectionMarker selectionMarker : entry.getSelectionMarkers()) {
                if (selectionMarker.getName() == null || selectionMarker.getName().isEmpty()) {
                    throw new ServiceException("Selection Marker can't be null or empty!");
                }

                selectionMarker.setEntry(entry);
            }
        }

        // Validate links
        if (entry.getLinks() != null && entry.getLinks().size() > 0) {
            for (Link link : entry.getLinks()) {
                if (link.getLink() == null || link.getLink().isEmpty()) {
                    throw new ServiceException("Link can't be null or empty!");
                }

                link.setEntry(entry);
            }
        }

        // Validate entry funding sources
        if (entry.getEntryFundingSources() == null || entry.getEntryFundingSources().size() == 0) {
            throw new ServiceException(
                    "FundingSource is mandatory! Expected at least one FundingSource.");
        } else {
            for (EntryFundingSource entryFundingSource : entry.getEntryFundingSources()) {
                if (entryFundingSource.getFundingSource() == null) {
                    throw new ServiceException("FundingSource can't be null!");
                }

                if (entryFundingSource.getFundingSource().getFundingSource() == null
                        || entryFundingSource.getFundingSource().getFundingSource().isEmpty()) {
                    throw new ServiceException("FundingSource can't be null or empty!");
                }

                if (entryFundingSource.getFundingSource().getPrincipalInvestigator() == null
                        || entryFundingSource.getFundingSource().getPrincipalInvestigator()
                                .isEmpty()) {
                    throw new ServiceException("PrincipalInvestigator can't be null or empty!");
                }

                entryFundingSource.setEntry(entry);
            }
        }

        return entry;
    }

    protected Entry updateEntry(String sessionId, Entry entry) throws SessionException,
            ServiceException, ServicePermissionException {
        Entry currentEntry = null;

        try {
            EntryController entryController = getEntryController(sessionId);

            try {
                currentEntry = entryController.getByRecordId(entry.getRecordId());
            } catch (PermissionException e) {
                throw new ServicePermissionException("No permissions to read this entry!");
            }

            if (currentEntry == null) {
                throw new ServiceException("Invalid recordId for entry!");
            }

            if (!entryController.hasWritePermission(currentEntry)) {
                throw new ServicePermissionException("No permissions to change this entry!");
            }
        } catch (ControllerException e) {
            throw new ServiceException(e);
        }

        // Validate and set creator
        if (entry.getCreator() == null || entry.getCreator().isEmpty()) {
            throw new ServiceException("Creator is mandatory field!");
        } else {
            currentEntry.setCreator(entry.getCreator());
            currentEntry.setCreatorEmail(entry.getCreatorEmail());
        }

        // Validate and set owner
        if (entry.getOwner() == null || entry.getOwner().isEmpty()) {
            throw new ServiceException("Owner is mandatory field!");
        } else {
            currentEntry.setOwner(entry.getOwner());
        }

        // Validate and set ownerEmail
        if (entry.getOwnerEmail() == null || entry.getOwnerEmail().isEmpty()) {
            throw new ServiceException("OwnerEmail is mandatory field!");
        } else {
            currentEntry.setOwnerEmail(entry.getOwnerEmail());
        }

        // Validate and set short description
        if (entry.getShortDescription() == null || entry.getShortDescription().isEmpty()) {
            throw new ServiceException("Short Description is mandatory field!");
        } else {
            currentEntry.setShortDescription(entry.getShortDescription());
        }

        // Validate status
        if (entry.getStatus() == null) {
            throw new ServiceException(
                    "Invalid status! Expected type: 'complete', 'in progress' or 'planned'.");
        } else if (!entry.getStatus().equals("complete")
                && !entry.getStatus().equals("in progress") && !entry.getStatus().equals("planned")) {
            throw new ServiceException(
                    "Invalid status! Expected type: 'complete', 'in progress' or 'planned'.");
        } else {
            currentEntry.setStatus(entry.getStatus());
        }

        // Validate bioSafetyLevel
        if (entry.getBioSafetyLevel() != 1 && entry.getBioSafetyLevel() != 2) {
            throw new ServiceException("Invalid bio safety level! Expected: '1' or '2'");
        } else {
            currentEntry.setBioSafetyLevel(entry.getBioSafetyLevel());
        }

        currentEntry.setAlias(entry.getAlias());
        currentEntry.setKeywords(entry.getKeywords());
        currentEntry.setLongDescription(entry.getLongDescription());
        currentEntry.setReferences(entry.getReferences());
        currentEntry.setIntellectualProperty(entry.getIntellectualProperty());

        if (entry instanceof Plasmid) {
            ((Plasmid) currentEntry).setBackbone(((Plasmid) entry).getBackbone());
            ((Plasmid) currentEntry).setCircular(((Plasmid) entry).getCircular());
            ((Plasmid) currentEntry).setOriginOfReplication(((Plasmid) entry)
                    .getOriginOfReplication());
            ((Plasmid) currentEntry).setPromoters(((Plasmid) entry).getPromoters());
        } else if (entry instanceof Strain) {
            ((Strain) currentEntry).setHost(((Strain) entry).getHost());
            ((Strain) currentEntry).setPlasmids(((Strain) entry).getPlasmids());
            ((Strain) currentEntry).setGenotypePhenotype(((Strain) entry).getGenotypePhenotype());
        } else if (entry instanceof Part) {
            ((Part) currentEntry).setPackageFormat(((Part) entry).getPackageFormat());
        }

        // Validate and set name
        if (entry.getNames() == null || entry.getNames().size() == 0) {
            throw new ServiceException("Name is mandatory! Expected at least one name.");
        } else {
            for (Name name : entry.getNames()) {
                if (name.getName() == null || name.getName().isEmpty()) {
                    throw new ServiceException("Name can't be null or empty!");
                }

                boolean existName = false;
                for (Name currentEntryName : currentEntry.getNames()) {
                    if (currentEntryName.getName().equals(name.getName())) {
                        existName = true;

                        break;
                    }
                }

                if (!existName) {
                    name.setEntry(currentEntry);

                    currentEntry.getNames().add(name);
                }
            }
        }

        // Validate and set selection markers
        if (entry.getSelectionMarkers() != null && entry.getSelectionMarkers().size() > 0) {
            for (SelectionMarker selectionMarker : entry.getSelectionMarkers()) {
                if (selectionMarker.getName() == null || selectionMarker.getName().isEmpty()) {
                    throw new ServiceException("Selection Marker can't be null or empty!");
                }

                boolean existSelectionMarker = false;
                for (SelectionMarker currentEntrySelectionMarker : currentEntry
                        .getSelectionMarkers()) {
                    if (currentEntrySelectionMarker.getName().equals(selectionMarker.getName())) {
                        existSelectionMarker = true;

                        break;
                    }
                }

                if (!existSelectionMarker) {
                    selectionMarker.setEntry(currentEntry);

                    currentEntry.getSelectionMarkers().add(selectionMarker);
                }
            }
        } else {
            currentEntry.setSelectionMarkers(null);
        }

        if (entry.getLinks() != null && entry.getLinks().size() > 0) {
            for (Link link : entry.getLinks()) {
                if (link.getLink() == null || link.getLink().isEmpty()) {
                    throw new ServiceException("Link can't be null or empty!");
                }

                boolean existLink = false;
                for (Link currentEntryLink : currentEntry.getLinks()) {
                    if (currentEntryLink.getUrl().equals(link.getUrl())
                            && currentEntryLink.getLink().equals(link.getLink())) {
                        existLink = true;

                        break;
                    }
                }

                if (!existLink) {
                    link.setEntry(currentEntry);

                    currentEntry.getLinks().add(link);
                }
            }
        } else {
            currentEntry.setLinks(null);
        }

        // Validate and set entry funding sources
        if (entry.getEntryFundingSources() == null || entry.getEntryFundingSources().size() == 0) {
            throw new ServiceException(
                    "FundingSource is mandatory! Expected at least one FundingSource.");
        } else {
            for (EntryFundingSource entryFundingSource : entry.getEntryFundingSources()) {
                if (entryFundingSource.getFundingSource() == null) {
                    throw new ServiceException("FundingSource can't be null!");
                }

                if (entryFundingSource.getFundingSource().getFundingSource() == null
                        || entryFundingSource.getFundingSource().getFundingSource().isEmpty()) {
                    throw new ServiceException("FundingSource can't be null or empty!");
                }

                if (entryFundingSource.getFundingSource().getPrincipalInvestigator() == null
                        || entryFundingSource.getFundingSource().getPrincipalInvestigator()
                                .isEmpty()) {
                    throw new ServiceException("PrincipalInvestigator can't be null or empty!");
                }

                boolean existEntryFundingSource = false;
                for (EntryFundingSource currentEntryEntryFundingSource : currentEntry
                        .getEntryFundingSources()) {

                    if (currentEntryEntryFundingSource.getFundingSource().getFundingSource()
                            .equals(entryFundingSource.getFundingSource().getFundingSource())
                            && currentEntryEntryFundingSource
                                    .getFundingSource()
                                    .getPrincipalInvestigator()
                                    .equals(
                                        entryFundingSource.getFundingSource()
                                                .getPrincipalInvestigator())) {
                        existEntryFundingSource = true;

                        break;
                    }

                }

                if (!existEntryFundingSource) {
                    entryFundingSource.setEntry(currentEntry);

                    currentEntry.getEntryFundingSources().add(entryFundingSource);
                }

                entryFundingSource.setEntry(entry);
            }
        }

        return currentEntry;
    }

    public void removeEntry(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "removeEntry: " + entryId);
        try {
            EntryController entryController = getEntryController(sessionId);

            Entry entry = entryController.getByRecordId(entryId);

            entryController.delete(entry);

            log("User '" + entryController.getAccount().getEmail() + "' removed entry: '" + entryId
                    + "'");
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permissions to delete this entry!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }
    }

    public FeaturedDNASequence getSequence(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "getSequence: " + entryId);
        FeaturedDNASequence sequence = null;

        try {
            SequenceController sequenceController = getSequenceController(sessionId);
            EntryController entryController = getEntryController(sessionId);

            Entry entry = entryController.getByRecordId(entryId);

            sequence = SequenceController.sequenceToDNASequence(sequenceController
                    .getByEntry(entry));

            log("User '" + entryController.getAccount().getEmail() + "' pulled sequence: '"
                    + entryId + "'");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permission to read this entry");
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return sequence;
    }

    public String getOriginalGenBankSequence(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "getOriginalGenbankSequence: " + entryId);
        String genbankSequence = "";

        try {
            SequenceController sequenceController = getSequenceController(sessionId);
            EntryController entryController = getEntryController(sessionId);

            Entry entry = entryController.getByRecordId(entryId);

            Sequence sequence = sequenceController.getByEntry(entry);

            if (sequence != null) {
                genbankSequence = sequence.getSequenceUser();
            }

            log("User '" + entryController.getAccount().getEmail()
                    + "' pulled original genbank sequence: '" + entryId + "'");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permission to read this entry");
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return genbankSequence;
    }

    public String getGenBankSequence(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "getGenBankSequence: " + entryId);
        String genbankSequence = "";

        try {
            SequenceController sequenceController = getSequenceController(sessionId);
            EntryController entryController = getEntryController(sessionId);

            Entry entry = entryController.getByRecordId(entryId);

            Sequence sequence = sequenceController.getByEntry(entry);

            if (sequence != null) {
                GenbankFormatter genbankFormatter = new GenbankFormatter(entry.getNamesAsString());
                genbankFormatter
                        .setCircular((sequence.getEntry() instanceof Plasmid) ? ((Plasmid) entry)
                                .getCircular() : false);

                genbankSequence = SequenceController.compose(sequence, genbankFormatter);
            }

            log("User '" + entryController.getAccount().getEmail()
                    + "' pulled generated genbank sequence: '" + entryId + "'");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permission to read this entry");
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return genbankSequence;
    }

    public String getFastaSequence(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "getFastaSequence: " + entryId);
        String fastaSequence = "";

        try {
            SequenceController sequenceController = getSequenceController(sessionId);
            EntryController entryController = getEntryController(sessionId);

            Entry entry = entryController.getByRecordId(entryId);

            Sequence sequence = sequenceController.getByEntry(entry);

            if (sequence != null) {
                fastaSequence = SequenceController.compose(sequence,
                    new FastaFormatter(entry.getNamesAsString()));
            }

            log("User '" + entryController.getAccount().getEmail()
                    + "' pulled generated fasta sequence: '" + entryId + "'");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permission to read this entry");
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return fastaSequence;
    }

    public FeaturedDNASequence createSequence(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId,
            @WebParam(name = "sequence") FeaturedDNASequence featuredDNASequence)
            throws SessionException, ServiceException, ServicePermissionException {
        log(sessionId, "createSequence: " + entryId);
        Entry entry = null;
        FeaturedDNASequence savedFeaturedDNASequence = null;

        try {
            EntryController entryController = getEntryController(sessionId);
            SequenceController sequenceController = getSequenceController(sessionId);

            try {
                entry = entryController.getByRecordId(entryId);
            } catch (PermissionException e) {
                throw new ServicePermissionException("No permissions to read this entry!");
            }

            if (entry == null) {
                throw new ServiceException("Entry doesn't exist!");
            }

            if (entryController.hasSequence(entry)) {
                throw new ServiceException(
                        "Entry has sequence already assigned. Remove it first and then create new one.");
            }

            Sequence sequence = SequenceController.dnaSequenceToSequence(featuredDNASequence);

            sequence.setEntry(entry);

            try {
                savedFeaturedDNASequence = SequenceController
                        .sequenceToDNASequence(sequenceController.save(sequence));

                log("User '" + entryController.getAccount().getEmail() + "' saved sequence: '"
                        + entryId + "'");
            } catch (PermissionException e) {
                throw new ServicePermissionException("No permissions to save this sequence!");
            }
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return savedFeaturedDNASequence;
    }

    @WebMethod(exclude = true)
    public FeaturedDNASequence updateSequence(FeaturedDNASequence sequence) {
        throw new NotImplementedException(
                "this method not implemented on purpose; remove and create new one");
    }

    public void removeSequence(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "removeSequence: " + entryId);
        try {
            EntryController entryController = getEntryController(sessionId);

            Entry entry = null;

            try {
                entry = entryController.getByRecordId(entryId);
            } catch (PermissionException e) {
                throw new ServicePermissionException("No permission to read this entry");
            }

            SequenceController sequenceController = getSequenceController(sessionId);

            Sequence sequence = sequenceController.getByEntry(entry);

            if (sequence != null) {
                try {
                    sequenceController.delete(sequence);

                    log("User '" + entryController.getAccount().getEmail()
                            + "' removed sequence: '" + entryId + "'");
                } catch (PermissionException e) {
                    throw new ServicePermissionException("No permission to delete sequence");
                }
            }
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }
    }

    public FeaturedDNASequence uploadSequence(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId, @WebParam(name = "sequence") String sequence)
            throws SessionException, ServiceException, ServicePermissionException {
        log(sessionId, "uploadSequence: " + entryId);
        EntryController entryController = getEntryController(sessionId);
        SequenceController sequenceController = getSequenceController(sessionId);

        FeaturedDNASequence dnaSequence = (FeaturedDNASequence) SequenceController.parse(sequence);

        if (dnaSequence == null) {
            throw new ServiceException("Couldn't parse sequence file! Supported formats: "
                    + GeneralParser.getInstance().availableParsersToString());
        }

        Entry entry = null;

        FeaturedDNASequence savedFeaturedDNASequence = null;
        Sequence modelSequence = null;
        try {
            try {
                entry = entryController.getByRecordId(entryId);

                if (entryController.hasSequence(entry)) {
                    throw new ServiceException(
                            "Entry has sequence already assigned. Remove it first and then upload new one.");
                }
            } catch (PermissionException e) {
                throw new ServicePermissionException("No permissions to read entry!", e);
            }

            try {
                modelSequence = SequenceController.dnaSequenceToSequence(dnaSequence);

                modelSequence.setEntry(entry);
                modelSequence.setSequenceUser(sequence);

                Sequence savedSequence = sequenceController.save(modelSequence);

                savedFeaturedDNASequence = SequenceController.sequenceToDNASequence(savedSequence);

                log("User '" + entryController.getAccount().getEmail()
                        + "' uploaded new sequence: '" + entryId + "'");
            } catch (PermissionException e) {
                throw new ServicePermissionException("No permissions to save sequence to entry!", e);
            }
        } catch (ControllerException e) {
            throw new ViewException(e);
        } catch (Exception e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        return savedFeaturedDNASequence;
    }

    public ArrayList<Sample> retrieveEntrySamples(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "entryId") String entryId) throws SessionException, ServiceException,
            ServicePermissionException {
        log(sessionId, "retrieveEntrySamples: " + entryId);
        SampleController sampleController = getSampleController(sessionId);
        EntryController entryController = getEntryController(sessionId);

        try {
            Entry entry = entryController.getByRecordId(entryId);
            return sampleController.getSamples(entry);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        } catch (PermissionException e) {
            throw new ServicePermissionException("No permissions to view entry");
        }
    }

    public ArrayList<Sample> retrieveSamplesByBarcode(
            @WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "barcode") String barcode) throws SessionException, ServiceException {
        log(sessionId, "retrieveSamplesByBarcode: " + barcode);
        SampleController sampleController = getSampleController(sessionId);
        StorageController storageController = getStorageController(sessionId);

        try {
            Storage storage = storageController.retrieveStorageTube(barcode.trim());
            if (storage == null) {
                return null;
            }
            return sampleController.getSamplesByStorage(storage);
        } catch (ControllerException e) {
            Logger.error(e);
            throw new ServiceException(e);
        }
    }

    /**
     * Checks if all samples have a common plate. If not, it determines which plate
     * is the most likely.
     * 
     * @param sessionId
     * @param samples
     *            samples containing tube storage location with well parent
     * @return null if samples have a common plate, if not plate id (storage index) of most likely
     *         plate is returned
     * @throws SessionException
     * @throws ServiceException
     */
    public String samplePlate(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "samples") Sample[] samples) throws SessionException, ServiceException {
        log(sessionId, "samplePlate");
        StorageController storageController = getStorageController(sessionId);
        HashMap<String, Integer> plateIndex = new HashMap<String, Integer>();

        Sample initial = samples[0];
        Storage tube = initial.getStorage();

        // get tube by barcode and parent
        try {
            tube = storageController.retrieveStorageTube(tube.getIndex());
        } catch (ControllerException e) {
            Logger.error(e.getMessage());
            throw new ServiceException("Error retrieving storage location for tube "
                    + tube.getIndex());
        }
        if (tube == null) {
            throw new ServiceException("Error retrieving storage location for tube");
        }

        Storage plate = tube.getParent().getParent();
        String highestFreqPlate = plate.getIndex();
        int highestFreqCount = 1;
        plateIndex.put(highestFreqPlate, highestFreqCount);

        for (int i = 1; i < samples.length; i += 1) {

            Sample sample = samples[i];

            String barcode = sample.getStorage().getIndex();
            if ("No Tube".equals(barcode) || "No Read".equals(barcode)) {
                continue;
            }

            try {
                tube = storageController.retrieveStorageTube(barcode);
            } catch (ControllerException e) {
                Logger.error(e.getMessage());
                throw new ServiceException("Error retrieving storage location for tube");
            }
            plate = tube.getParent().getParent();

            // check if this is new (not in plates map)
            Integer value = plateIndex.get(plate.getIndex());
            if (value == null) {
                // new
                plateIndex.put(plate.getIndex(), 1);
            } else {
                // update count
                value += 1;
                plateIndex.put(plate.getIndex(), value);
                if (value > highestFreqCount) {
                    highestFreqCount = value;
                    highestFreqPlate = plate.getIndex();
                }
            }
        }

        if (plateIndex.keySet().size() == 1) {
            return null;
        }

        return highestFreqPlate;
    }

    // Need moderator privileges to run this
    public void createStrainSample(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "recordId") String recordId, @WebParam(name = "rack") String rack,
            @WebParam(name = "location") String location,
            @WebParam(name = "barcode") String barcode, @WebParam(name = "label") String label)
            throws ServiceException, PermissionException, SessionException {
        log(sessionId, "createStrainsample: " + recordId + "," + location + "," + barcode);
        Account account = null;

        try {
            account = AccountController.getAccountBySessionKey(sessionId);
        } catch (ControllerException e) {
            Logger.error(e);
            throw new ServiceException("Registry Service Internal Error!");
        }

        try {
            if (!AccountManager.isModerator(account)) {
                log("Account " + account.getEmail() + " attempting to access createStrainSample()");
                throw new PermissionException("Account does not have permissions");
            }
        } catch (ManagerException e) {
            log(e.getMessage());
            throw new ServiceException("Registry Service Internal Error!");
        }

        // check if there is an existing sample with barcode
        StorageController storageController = getStorageController(sessionId);
        SampleController sampleController = getSampleController(sessionId);

        try {
            Storage storage = storageController.retrieveStorageTube(barcode.trim());
            if (storage != null) {
                ArrayList<Sample> samples = sampleController.getSamplesByStorage(storage);
                if (samples != null && !samples.isEmpty()) {
                    log("Barcode \"" + barcode + "\" already has a sample associated with it");
                    return;
                }
            }
        } catch (ControllerException e) {
            Logger.error(e);
            throw new ServiceException(e);
        }

        log("Creating new strain sample for entry \"" + recordId + "\" and label \"" + label + "\"");
        // TODO : this is a hack till we migrate to a single strain default
        Storage strainScheme = null;
        try {
            List<Storage> schemes = storageController.retrieveAllStorageSchemes();
            for (Storage storage : schemes) {
                if (storage.getStorageType() == StorageType.SCHEME
                        && "Strain Storage Matrix Tubes".equals(storage.getName())) {
                    strainScheme = storage;
                    break;
                }
            }
            if (strainScheme == null) {
                log("Could not locate default strain scheme (Strain Storage Matrix Tubes[Plate, Well, Tube])");
                throw new ServiceException("Registry Service Internal Error!");
            }

            Storage newLocation = StorageManager.getLocation(strainScheme, new String[] { rack,
                    location, barcode });

            Entry entry = getEntryController(sessionId).getByRecordId(recordId);
            if (entry == null) {
                throw new ServiceException("Could not retrieve entry with id " + recordId);
            }

            Sample sample = sampleController.createSample(label, account.getEmail(), "");
            sample.setEntry(entry);
            sample.setStorage(newLocation);
            Sample saved = sampleController.saveSample(sample, false);
            if (saved == null) {
                throw new ServiceException("Unable to create sample");
            }
        } catch (ControllerException ce) {
            log(ce.getMessage());
            throw new ServiceException(ce.getMessage());
        } catch (ManagerException e) {
            log(e.getMessage());
            throw new ServiceException(e.getMessage());
        }

    }

    /**
     * 
     * @param sessionId
     *            valid session id
     * @param codes
     *            indexed by location. null values indicate no samples
     * @throws SessionException
     * @throws ServiceException
     * @return list of samples
     */
    public List<Sample> checkAndUpdateSamplesStorage(
            @WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "samples") Sample[] samples, @WebParam(name = "plateId") String plateId)
            throws SessionException, ServiceException {
        log(sessionId, "checkAndUpdateSamplesStorage: " + plateId);
        StorageController storageController = getStorageController(sessionId);
        SampleController sampleController = getSampleController(sessionId);

        // count of plates seen so far
        List<Sample> retSamples = new LinkedList<Sample>();

        // for each sample (unique elements are the barcode (tube index))
        for (Sample sample : samples) {

            // sent storage location. note that barcode can be no read or no tube
            String barcode = sample.getStorage().getIndex();
            if ("No Tube".equals(barcode) || "No Read".equals(barcode)) {

                retSamples.add(sample);
                continue;
            }

            String location = sample.getStorage().getParent().getIndex();

            Storage recordedTube = null;
            try {
                // stored storage location
                recordedTube = storageController.retrieveStorageTube(barcode);
                Storage recordedWell = recordedTube.getParent();
                Storage recordedPlate = recordedWell.getParent();
                Storage parentScheme = recordedPlate.getParent();

                boolean samePlate = (plateId == null);
                boolean sameWell = recordedWell.getIndex().equals(location);

                if (samePlate) {
                    if (sameWell) {
                        ArrayList<Sample> ret = sampleController.getSamplesByStorage(recordedTube);
                        if (ret != null && !ret.isEmpty()) {
                            retSamples.add(ret.get(0));
                        }

                        continue; // no changes needed
                    } else {
                        // same plate but different well                        
                        Storage well = storageController.retrieveStorageBy("Well", location,
                            StorageType.WELL, recordedPlate.getId());
                        if (well == null) {
                            throw new ServiceException(
                                    "Could not retrieve new location for storage");
                        }
                        recordedTube.setParent(well);
                        storageController.update(recordedTube);
                    }
                } else {
                    // different plate (update using the passed parameter)
                    Storage newPlate = storageController.retrieveStorageBy("Plate", plateId,
                        StorageType.PLATE96, parentScheme.getId());
                    if (sameWell) {
                        // update plate only
                        recordedWell.setParent(newPlate);
                        storageController.update(recordedWell);

                    } else {
                        // update plate and well
                        Storage well = storageController.retrieveStorageBy("Well", location,
                            StorageType.WELL, newPlate.getId());
                        recordedTube.setParent(well);
                        storageController.update(recordedTube);
                    }
                }
            } catch (ControllerException e) {
                Logger.error(e);
                throw new ServiceException("Error retrieving/updating some records!");
            }

            ArrayList<Sample> ret;
            try {
                ret = sampleController.getSamplesByStorage(recordedTube);
                if (ret != null && !ret.isEmpty()) {
                    retSamples.add(ret.get(0));
                }
            } catch (ControllerException e) {

                Logger.error(e);
                sample.setStorage(recordedTube);
                retSamples.add(sample);
            }

        }
        return retSamples;
    }

    /**
     * Get a list of trace file associated with an entry.
     * 
     * @param sessionId
     * @param recordId
     * @return
     * @throws ServiceException
     * @throws SessionException
     */
    public List<TraceSequence> listTraceSequenceFiles(
            @WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "recordId") String recordId) throws ServiceException, SessionException {
        log(sessionId, "listTraceSequenceFiles: " + recordId);
        List<TraceSequence> result = new ArrayList<TraceSequence>();
        SequenceAnalysisController sequenceAnalysisController = getSequenceAnalysisController(sessionId);
        EntryController entryController = getEntryController(sessionId);

        Entry entry = null;
        try {
            entry = entryController.getByRecordId(recordId);
            if (entry == null) {
                throw new ServiceException("Could not retrieve entry");
            }
        } catch (ControllerException e) {
            log(e.getMessage());
            throw new ServiceException("Could not retrieve entry: " + e.getMessage());
        } catch (PermissionException e) {
            log(e.getMessage());
            throw new ServiceException("No permission to view entry: " + recordId);
        }
        List<TraceSequence> traces = null;
        try {
            traces = sequenceAnalysisController.getTraceSequences(entry);
            if (traces == null) {
                return result;
            }
        } catch (ControllerException e) {
            throw new ServiceException("Could not retrieve traces: " + e.getMessage());
        }
        for (TraceSequence trace : traces) {
            //null out entry to reduce output.
            trace.setEntry(null);
            // null out traceSequenceAlignment.traceSequence, as it causes infinite nesting  in xml for some reason.
            trace.getTraceSequenceAlignment().setTraceSequence(null);
            result.add(trace);
        }

        return result;
    }

    public String uploadTraceSequenceFile(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "recordId") String recordId,
            @WebParam(name = "fileName") String fileName,
            @WebParam(name = "base64FileData") String base64FileData) throws ServiceException,
            SessionException {
        log(sessionId, "uploadTraceSequenceFile: " + recordId + "," + fileName);
        TraceSequence result = null;

        SequenceAnalysisController sequenceAnalysisController = getSequenceAnalysisController(sessionId);
        EntryController entryController = getEntryController(sessionId);
        byte[] bytes = SerializationUtils.deserializeStringToBytes(base64FileData);
        if (bytes == null) {
            throw new ServiceException("Invalid File Data!");
        }
        Account account = validateAccount(sessionId);
        String depositor = account.getEmail();
        Entry entry = null;
        try {
            entry = entryController.getByRecordId(recordId);
            if (entry == null) {
                throw new ServiceException("Could not retrieve entry!");
            }
        } catch (ControllerException e1) {
            log(e1.getMessage());
            throw new ServiceException("Could not retrieve entry!");
        } catch (PermissionException e1) {
            log(e1.getMessage());
            throw new ServiceException("You do not have permission to view entry!");
        }
        ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
        String sequence = null;
        try {
            IDNASequence temp = sequenceAnalysisController.parse(bytes);

            if (temp == null) {
                throw new ServiceException("Could not parse trace file!");
            } else {
                sequence = temp.getSequence();
            }
        } catch (ControllerException e) {
            log(e.getMessage());
            throw new ServiceException("Could not parse trace file!: " + e.getMessage());
        }
        try {
            result = sequenceAnalysisController.uploadTraceSequence(entry, fileName, depositor,
                sequence, inputStream);
            sequenceAnalysisController.rebuildAllAlignments(entry);
        } catch (ControllerException e) {
            log(e.getMessage());
            throw new ServiceException("Could not upload trace seqence!: " + e.getMessage());
        }

        return result.getFileId();
    }

    public SequenceTraceFile getTraceSequenceFile(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "fileId") String fileId) throws ServiceException, SessionException {
        log(sessionId, "getTraceSequenceFile: " + fileId);
        SequenceAnalysisController sequenceAnalysisController = getSequenceAnalysisController(sessionId);
        SequenceTraceFile traceFile = null;
        try {
            TraceSequence traceSequence = sequenceAnalysisController
                    .getTraceSequenceByFileId(fileId);
            if (traceSequence == null) {
                throw new ServiceException("Could not retrieve Trace Sequence");
            }
            traceFile = sequenceAnalysisController.getSequenceTraceFile(traceSequence);

        } catch (ControllerException e) {
            log(e.getMessage());
            throw new ServiceException(e.getMessage());
        }

        return traceFile;
    }

    public void deleteTraceSequenceFile(@WebParam(name = "sessionId") String sessionId,
            @WebParam(name = "fileId") String fileId) throws ServiceException, SessionException {
        log(sessionId, "deleteTraceSequenceFile: " + fileId);
        SequenceAnalysisController sequenceAnalysisController = getSequenceAnalysisController(sessionId);

        TraceSequence traceSequence;
        try {
            traceSequence = sequenceAnalysisController.getTraceSequenceByFileId(fileId);
            if (traceSequence == null) {
                throw new ServiceException("No such fileId found");
            }
            Entry entry = traceSequence.getEntry();
            sequenceAnalysisController.removeTraceSequence(traceSequence);
            sequenceAnalysisController.rebuildAllAlignments(entry);
        } catch (ControllerException e) {
            log(e.getMessage());
            throw new ServiceException("Could not delete TraceSequence: " + e.getMessage());
        } catch (PermissionException e) {
            log(e.getMessage());
            throw new ServiceException("Deletion of this trace is not permitted");
        }

    }

    protected SequenceAnalysisController getSequenceAnalysisController(String sessionId)
            throws ServiceException, SessionException {
        Account account = validateAccount(sessionId);
        return new SequenceAnalysisController(account);
    }

    protected StorageController getStorageController(@WebParam(name = "sessionId") String sessionId)
            throws ServiceException, SessionException {
        Account account = validateAccount(sessionId);
        return new StorageController(account);
    }

    protected SampleController getSampleController(@WebParam(name = "sessionId") String sessionId)
            throws SessionException, ServiceException {
        Account account = validateAccount(sessionId);
        return new SampleController(account);
    }

    protected EntryController getEntryController(@WebParam(name = "sessionId") String sessionId)
            throws SessionException, ServiceException {
        Account account = validateAccount(sessionId);

        return new EntryController(account);
    }

    protected SequenceController getSequenceController(
            @WebParam(name = "sessionId") String sessionId) throws ServiceException,
            SessionException {
        Account account = validateAccount(sessionId);

        return new SequenceController(account);
    }

    protected SearchController getSearchController(@WebParam(name = "sessionId") String sessionId)
            throws SessionException, ServiceException {
        return new SearchController(validateAccount(sessionId));
    }

    protected Account validateAccount(@WebParam(name = "sessionId") String sessionId)
            throws ServiceException, SessionException {
        if (!isAuthenticated(sessionId)) {
            throw new SessionException("Unauthorized access! Authorize first!");
        }

        Account account = null;

        try {
            account = AccountController.getAccountBySessionKey(sessionId);
        } catch (ControllerException e) {
            Logger.error(e);

            throw new ServiceException("Registry Service Internal Error!");
        }

        if (account == null) {
            Logger.error("Failed to lookup account!");

            throw new ServiceException("Registry Service Internal Error!");
        }

        return account;
    }

    private void log(String message) {
        Logger.info("RegistryAPI: " + message);
    }

    private void log(String sessionId, String message) {
        Account account = null;
        try {
            account = validateAccount(sessionId);
            if (account != null) {
                message = account.getEmail() + "\t" + message;
            }
        } catch (ServiceException e) {
            // it's ok, session expired.
            message = "invalid account\t" + message;
        } catch (SessionException e) {
            // It's ok, session expired.
            message = "invalid account\t" + message;
        }
        Logger.info("RegistryAPI: " + message);
    }
}
