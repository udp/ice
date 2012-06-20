package org.jbei.ice.server;

import com.google.gwt.user.client.ui.SuggestOracle;
import com.google.gwt.user.client.ui.SuggestOracle.Request;
import com.google.gwt.user.client.ui.SuggestOracle.Suggestion;
import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import org.apache.commons.lang.ArrayUtils;
import org.jbei.ice.client.RegistryService;
import org.jbei.ice.client.entry.view.model.SampleStorage;
import org.jbei.ice.client.exception.AuthenticationException;
import org.jbei.ice.controllers.common.ControllerException;
import org.jbei.ice.lib.account.AccountController;
import org.jbei.ice.lib.account.model.Account;
import org.jbei.ice.lib.bulkimport.BulkImport;
import org.jbei.ice.lib.bulkimport.BulkImportController;
import org.jbei.ice.lib.entry.EntryController;
import org.jbei.ice.lib.entry.attachment.Attachment;
import org.jbei.ice.lib.entry.attachment.AttachmentController;
import org.jbei.ice.lib.entry.model.Entry;
import org.jbei.ice.lib.entry.model.Plasmid;
import org.jbei.ice.lib.entry.model.Strain;
import org.jbei.ice.lib.entry.sample.SampleController;
import org.jbei.ice.lib.entry.sample.StorageController;
import org.jbei.ice.lib.entry.sample.StorageDAO;
import org.jbei.ice.lib.entry.sample.model.Sample;
import org.jbei.ice.lib.entry.sample.model.Storage;
import org.jbei.ice.lib.entry.sequence.SequenceAnalysisController;
import org.jbei.ice.lib.entry.sequence.SequenceController;
import org.jbei.ice.lib.entry.sequence.TraceSequenceDAO;
import org.jbei.ice.lib.folder.Folder;
import org.jbei.ice.lib.folder.FolderController;
import org.jbei.ice.lib.group.Group;
import org.jbei.ice.lib.group.GroupController;
import org.jbei.ice.lib.logging.Logger;
import org.jbei.ice.lib.managers.ManagerException;
import org.jbei.ice.lib.models.News;
import org.jbei.ice.lib.models.Sequence;
import org.jbei.ice.lib.models.TraceSequence;
import org.jbei.ice.lib.news.NewsController;
import org.jbei.ice.lib.parsers.GeneralParser;
import org.jbei.ice.lib.permissions.PermissionException;
import org.jbei.ice.lib.permissions.PermissionsController;
import org.jbei.ice.lib.search.SearchController;
import org.jbei.ice.lib.search.blast.ProgramTookTooLongException;
import org.jbei.ice.lib.utils.BulkImportEntryData;
import org.jbei.ice.lib.utils.Emailer;
import org.jbei.ice.lib.utils.JbeirSettings;
import org.jbei.ice.lib.utils.PopulateInitialDatabase;
import org.jbei.ice.lib.utils.RichTextRenderer;
import org.jbei.ice.lib.utils.UtilsController;
import org.jbei.ice.lib.vo.IDNASequence;
import org.jbei.ice.shared.AutoCompleteField;
import org.jbei.ice.shared.ColumnField;
import org.jbei.ice.shared.EntryAddType;
import org.jbei.ice.shared.FolderDetails;
import org.jbei.ice.shared.QueryOperator;
import org.jbei.ice.shared.dto.*;
import org.jbei.ice.shared.dto.permission.PermissionInfo;
import org.jbei.ice.shared.dto.permission.PermissionInfo.PermissionType;
import org.jbei.ice.shared.dto.permission.PermissionSuggestion;
import org.jbei.ice.web.utils.WebUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RegistryServiceImpl extends RemoteServiceServlet implements RegistryService {

    private static final long serialVersionUID = 1L;

    @Override
    public String getSetting(String name) {
        return JbeirSettings.getSetting(name);
    }

    @Override
    public AccountInfo login(String name, String pass) {

        try {
            AccountController controller = new AccountController();
            AccountInfo info = controller.authenticate(name, pass);
            if (info == null)
                return null;

            Logger.info("User by login '" + name + "' successfully logged in");
            Account account = controller.getByEmail(info.getEmail());
            EntryController entryController = new EntryController();
            boolean isModerator = controller.isModerator(account);

            long visibleEntryCount;
            if (isModerator)
                visibleEntryCount = entryController.getAllEntryCount();
            else
                visibleEntryCount = entryController.getNumberOfVisibleEntries(account);

            info.setVisibleEntryCount(visibleEntryCount);

            // get the count of the user's entries
            long ownerEntryCount = entryController.getOwnerEntryCount(account);
            info.setUserEntryCount(ownerEntryCount);
            return info;
        } catch (ControllerException e) {
            Logger.error(e);
        } catch (Exception e) {
            Logger.error(e);
        }
        return null;
    }

    @Override
    public ArrayList<AccountInfo> retrieveAllUserAccounts(String sid) throws AuthenticationException {
        AccountController controller = new AccountController();

        try {
            Account account = retrieveAccountForSid(sid);
            boolean isModerator = controller.isModerator(account);
            if (!isModerator) {
                Logger.warn(account.getEmail()
                                    + " attempting to retrieve all user accounts without moderation privileges");
                return null;
            }

            Logger.info(account.getEmail() + ": retrieving all user accounts");
            EntryController entryController = new EntryController();

            // retrieve all user accounts
            Set<Account> accounts = controller.getAllByFirstName();
            if (accounts == null)
                return null;

            ArrayList<AccountInfo> infos = new ArrayList<AccountInfo>();
            for (Account userAccount : accounts) {
                AccountInfo info = new AccountInfo();
                long count;
                try {
                    count = entryController.getOwnerEntryCount(userAccount);
                    info.setUserEntryCount(count);
                } catch (ControllerException e) {
                    Logger.error("Error retrieving entry count for user " + userAccount.getEmail());
                    info.setUserEntryCount(-1);
                }

                info.setEmail(userAccount.getEmail());
                info.setModerator(controller.isModerator(userAccount));
                info.setFirstName(userAccount.getFirstName());
                info.setLastName(userAccount.getLastName());
                infos.add(info);
            }

            return infos;

        } catch (ControllerException e) {
            Logger.error(e);
        }
        return null;
    }

    @Override
    public boolean handleForgotPassword(String email, String url) throws AuthenticationException {
        AccountController controller = new AccountController();
        try {
            Logger.info("Resetting password for user " + email);
            controller.resetPassword(email, true, url);
            return true;
        } catch (ControllerException e) {
            Logger.error("Error resetting password for user " + email, e);
            return false;
        }
    }

    @Override
    public boolean updateAccountPassword(String sid, String email, String password) throws AuthenticationException {
        Account account;

        try {
            account = retrieveAccountForSid(sid);

            Logger.info(account.getEmail() + ": updating password for account " + email);
            AccountController controller = new AccountController();
            controller.updatePassword(email, password);
            return true;

        } catch (ControllerException e) {
            Logger.error(e);
            return false;
        }
    }

    @Override
    public AccountInfo createNewAccount(AccountInfo info, String url) {

        try {
            AccountController controller = new AccountController();
            String newPassword = controller.createNewAccount(info.getFirstName(),
                                                             info.getLastName(), info.getInitials(), info.getEmail(),
                                                             info.getInstitution(),
                                                             info.getDescription());

            if (url != null && !url.isEmpty()) {
                // send email
                String subject = "Account created successfully";
                StringBuilder stringBuilder = new StringBuilder();

                stringBuilder
                        .append("Dear " + info.getEmail() + ", ")
                        .append("\n\nThank you for creating a "
                                        + JbeirSettings.getSetting("PROJECT_NAME"))
                        .append(" account. \nBy accessing ")
                        .append("this site with the password provided at the bottom ")
                        .append("you agree to the following terms:\n\n");

                String terms = "Biological Parts IP Disclaimer: \n\n"
                        + "The JBEI Registry of Biological Parts Software is licensed under a standard BSD\n"
                        + "license. Permission or license to use the biological parts registered in\n"
                        + "the JBEI Registry of Biological Parts is not included in the BSD license\n"
                        + "to use the JBEI Registry Software. Berkeley Lab and JBEI make no representation\n"
                        + "that the use of the biological parts registered in the JBEI Registry of\n"
                        + "Biological Parts will not infringe any patent or other proprietary right.";

                stringBuilder.append(terms);

                stringBuilder.append("\n\nYour new password is: ").append(newPassword)
                             .append("\nPlease go to the following link and change your password:\n\n")
                             .append(url);

                Emailer.send(info.getEmail(), subject, stringBuilder.toString());
            }

            return info;
        } catch (ControllerException e) {
            Logger.error("Error creating new account", e);
            return null;
        }
    }

    @Override
    public AccountInfo retrieveAccount(String email) {
        Account account = null;
        AccountController controller = new AccountController();

        try {
            account = controller.getByEmail(email);
        } catch (ControllerException e) {
            Logger.error("Error retrieving account", e);
        }

        if (account == null)
            return null;

        AccountInfo info = new AccountInfo();
        info.setEmail(account.getEmail());
        info.setFirstName(account.getFirstName());
        info.setLastName(account.getLastName());
        return info;
    }

    @Override
    public AccountInfo updateAccount(String sid, String email, AccountInfo info) throws AuthenticationException {
        Account account;
        AccountController controller = new AccountController();

        try {
            account = retrieveAccountForSid(sid);
            if (account == null)
                return null;

            // require a user is a moderator or updating self account
            if (!controller.isModerator(account) && !email.equals(account.getEmail()))
                return null;

            account = controller.getByEmail(email);
            if (account == null)
                return null;

            account.setIsSubscribed(1);
            account.setModificationTime(Calendar.getInstance().getTime());

            account.setFirstName(info.getFirstName());
            account.setLastName(info.getLastName());
            account.setInitials(info.getInitials());
            account.setInstitution(info.getInstitution());
            account.setDescription(info.getDescription());

            controller.save(account);

            return info;

        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public AccountInfo sessionValid(String sid) {

        AccountController controller = new AccountController();
        EntryController entryController = new EntryController();

        try {
            if (AccountController.isAuthenticated(sid)) {
                Account account = controller.getAccountBySessionKey(sid);
                AccountInfo info = this.accountToInfo(account);
                int entryCount = entryController.getOwnerEntryCountBy(info.getEmail());
                info.setUserEntryCount(entryCount);

                boolean isModerator = controller.isModerator(account);
                info.setModerator(isModerator);

                long visibleEntryCount;
                if (isModerator)
                    visibleEntryCount = entryController.getAllEntryCount();
                else
                    visibleEntryCount = entryController.getNumberOfVisibleEntries(account);

                info.setVisibleEntryCount(visibleEntryCount);
                return info;
            }
        } catch (ControllerException e) {
            Logger.error(e);
        }
        return null;
    }

    @Override
    public boolean logout(String sessionId) {
        Logger.info("Deauthenticating session \"" + sessionId + "\"");
        try {
            AccountController.deauthenticate(sessionId);
            return true;
        } catch (ControllerException e) {
            Logger.error(e);
            return false;
        }
    }

    @Override
    public LinkedList<EntryInfo> retrieveEntryData(String sid, ColumnField field, boolean asc,
            LinkedList<Long> entryIds) throws AuthenticationException {

        try {
            Account account = this.retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": retrieving entry data for " + entryIds.size()
                                + " entries");
            EntryController entryController = new EntryController();
            return entryController.retrieveEntriesByIdSetSort(account, entryIds, field, asc);
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public LinkedList<Long> sortEntryList(String sessionId, LinkedList<Long> ids,
            ColumnField field, boolean asc) throws AuthenticationException {

        try {
            Account account = this.retrieveAccountForSid(sessionId);
            Logger.info(account.getEmail() + ": sorting entry list of size " + ids.size() + " by "
                                + field.getName() + (asc ? " ASC" : " DESC"));

            EntryController controller = new EntryController();
            return controller.sortList(ids, field, asc);
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public ArrayList<FolderDetails> retrieveUserCollections(String sessionId, String userId)
            throws AuthenticationException {
        ArrayList<FolderDetails> results = new ArrayList<FolderDetails>();

        try {
            Account account = retrieveAccountForSid(sessionId);
            Logger.info(account.getEmail() + ": retrieving user collections for user " + userId);
            AccountController controller = new AccountController();
            FolderController folderController = new FolderController();
            Account userAccount = controller.getByEmail(userId);

            // get user folder
            List<Folder> userFolders = folderController.getFoldersByOwner(userAccount);
            if (userFolders != null) {
                for (Folder folder : userFolders) {
                    long id = folder.getId();
                    FolderDetails details = new FolderDetails(id, folder.getName(), false);
                    BigInteger folderSize = folderController.getFolderSize(id);
                    details.setCount(folderSize);
                    details.setDescription(folder.getDescription());
                    results.add(details);
                }
            }
            return results;
        } catch (ControllerException ce) {
            Logger.error(ce);
            return null;
        }
    }

    @Override
    public ArrayList<FolderDetails> retrieveCollections(String sessionId) throws AuthenticationException {

        ArrayList<FolderDetails> results = new ArrayList<FolderDetails>();
        try {
            Account account = retrieveAccountForSid(sessionId);
            Logger.info(account.getEmail() + ": retrieving  collections");
            AccountController controller = new AccountController();
            FolderController folderController = new FolderController();
            Account system = controller.getSystemAccount();
            List<Folder> folders = folderController.getFoldersByOwner(system);

            for (Folder folder : folders) {
                long id = folder.getId();
                FolderDetails details = new FolderDetails(id, folder.getName(), true);
                BigInteger folderSize = folderController.getFolderSize(id);
                details.setCount(folderSize);
                details.setDescription(folder.getDescription());
                results.add(details);
            }

            // get user folder
            List<Folder> userFolders = folderController.getFoldersByOwner(account);
            if (userFolders != null) {
                for (Folder folder : userFolders) {
                    long id = folder.getId();
                    FolderDetails details = new FolderDetails(id, folder.getName(), false);
                    BigInteger folderSize = folderController.getFolderSize(id);
                    details.setCount(folderSize);
                    details.setDescription(folder.getDescription());
                    results.add(details);
                }
            }

            return results;

        } catch (ControllerException ce) {
            Logger.error(ce);
            return null;
        }
    }

    @Override
    public FolderDetails retrieveEntriesForFolder(String sessionId, long folderId) throws AuthenticationException {

        try {
            Account account = this.retrieveAccountForSid(sessionId);
            Logger.info(account.getEmail() + ": retrieving entries for folder " + folderId);
            FolderController folderController = new FolderController();
            Folder folder = folderController.getFolderById(folderId);
            if (folder == null)
                return null;

            AccountController controller = new AccountController();
            Account system = controller.getSystemAccount();
            boolean isSystem = system.getEmail().equals(folder.getOwnerEmail());
            FolderDetails details = new FolderDetails(folder.getId(), folder.getName(), isSystem);

            BigInteger folderSize = folderController.getFolderSize(folderId);

            details.setCount(folderSize);
            details.setDescription(folder.getDescription());
            ArrayList<Long> contents = folderController.getFolderContents(folderId);

            details.setContents(contents);
            return details;
        } catch (ControllerException e) {
            Logger.error(e);
        }
        return null;
    }

    @Override
    public FolderDetails deleteFolder(String sessionId, long folderId) throws AuthenticationException {
        try {
            Account account = this.retrieveAccountForSid(sessionId);
            Logger.info(account.getEmail() + ": deleting folder " + folderId);
            FolderController folderController = new FolderController();
            Folder folder = folderController.getFolderById(folderId);
            if (folder == null)
                return null;

            AccountController controller = new AccountController();
            Account system = controller.getSystemAccount();
            boolean isSystem = system.getEmail().equals(folder.getOwnerEmail());
            if (isSystem) {
                Logger.info("Cannot delete system folder");
                return null;
            }

            FolderDetails details = new FolderDetails(folder.getId(), folder.getName(), isSystem);
            BigInteger folderSize = folderController.getFolderSize(folderId);
            details.setCount(folderSize);
            details.setDescription(folder.getDescription());
            ArrayList<Long> contents = folderController.getFolderContents(folderId);

            details.setContents(contents);

            if (contents.size() > 0) {
                // delete the contents first
                if (folderController.removeFolderContents(account, folder.getId(), contents) == null)
                    return null;
            }

            folderController.delete(folder);
            return details;

        } catch (ControllerException e) {
            Logger.error(e);
        }
        return null;
    }

    @Override
    public FolderDetails retrieveUserEntries(String sid, String userId) throws AuthenticationException {

        try {
            Account account = this.retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": retrieving user entries for " + userId);
            EntryController entryController = new EntryController();
            FolderDetails details = new FolderDetails(0, "My Entries", true);
            ArrayList<Long> entries = entryController.getEntryIdsByOwner(userId);
            details.setContents(entries);
            return details;
        } catch (ControllerException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public FolderDetails retrieveAllVisibleEntryIDs(String sid) throws AuthenticationException {
        Account account;
        AccountController controller = new AccountController();

        try {
            account = retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": retrieving all visible entry ids");
            EntryController entryController = new EntryController();

            ArrayList<Long> entries;
            if (controller.isModerator(account))
                entries = entryController.getAllEntryIDs();
            else {
                entries = new ArrayList<Long>();
                entries.addAll(entryController.getAllVisibleEntryIDs(account));
            }

            FolderDetails details = new FolderDetails(-1, "Available Entries", true);
            details.setContents(entries);
            return details;
        } catch (ControllerException e) {
            Logger.error(e);
        }
        return null;
    }

    protected Account retrieveAccountForSid(String sid) throws AuthenticationException {

        try {
            boolean isAuthenticated = AccountController.isAuthenticated(sid);
            AccountController controller = new AccountController();

            if (!isAuthenticated)
                throw new AuthenticationException("Session failed authentication: " + sid);

            return controller.getAccountBySessionKey(sid);
        } catch (ControllerException ce) {
            throw new AuthenticationException();
        }
    }

    @Override
    public HashMap<AutoCompleteField, ArrayList<String>> retrieveAutoCompleteData(String sid) {
        HashMap<AutoCompleteField, ArrayList<String>> data = new HashMap<AutoCompleteField, ArrayList<String>>();

        UtilsController controller = new UtilsController();

        // origin of replication
        ArrayList<String> origin = new ArrayList<String>();
        origin.addAll(controller.getUniqueOriginOfReplications());
        data.put(AutoCompleteField.ORIGIN_OF_REPLICATION, origin);

        // selection markers
        try {
            ArrayList<String> markers = new ArrayList<String>();
            markers.addAll(controller.getUniqueSelectionMarkers());
            data.put(AutoCompleteField.SELECTION_MARKERS, markers);
        } catch (ControllerException e) {
            Logger.error(e);
        }

        // promoters
        ArrayList<String> promoters = new ArrayList<String>();
        promoters.addAll(controller.getUniquePromoters());
        data.put(AutoCompleteField.PROMOTERS, promoters);

        // plasmid names
        ArrayList<String> plasmidNames = new ArrayList<String>();
        plasmidNames.addAll(controller.getUniquePublicPlasmidNames());
        data.put(AutoCompleteField.PLASMID_NAME, plasmidNames);

        return data;
    }

    @Override
    public ArrayList<SequenceAnalysisInfo> retrieveEntryTraceSequences(String sid, long entryId)
            throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sid);
            if (account == null)
                return null;

            Entry entry = new EntryController().get(account, entryId);
            if (entry == null)
                return null;

            List<TraceSequence> sequences = TraceSequenceDAO.getByEntry(entry);
            return EntryToInfoFactory.getSequenceAnaylsis(sequences);

        } catch (ControllerException ce) {
            Logger.error(ce);
        } catch (PermissionException e) {
            Logger.error(e);
        } catch (ManagerException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public ArrayList<SequenceAnalysisInfo> deleteEntryTraceSequences(String sid, long entryId, ArrayList<String> fileId)
            throws AuthenticationException {

        try {
            Account account = retrieveAccountForSid(sid);
            Entry entry = new EntryController().get(account, entryId);
            if (entry == null)
                return null;

            SequenceAnalysisController controller = new SequenceAnalysisController();
            for (String id : fileId) {
                TraceSequence sequence = controller.getTraceSequenceByFileId(id);
                if (sequence == null) {
                    Logger.warn("Could not retrieve trace sequence by file Id " + id);
                    continue;
                }
                controller.removeTraceSequence(account, sequence);
            }
            List<TraceSequence> sequences = TraceSequenceDAO.getByEntry(entry);
            return EntryToInfoFactory.getSequenceAnaylsis(sequences);

        } catch (ControllerException ce) {
            Logger.error(ce);
        } catch (ManagerException me) {
            Logger.error(me);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public EntryInfo retrieveEntryDetails(String sid, long id) throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": retrieving entry details for " + id);
            Entry entry;
            try {
                entry = new EntryController().get(account, id);
            } catch (PermissionException e) {
                Logger.warn(e.getMessage());
                return null;
            }

            AttachmentController attachmentController = new AttachmentController(account);
            SampleController sampleController = new SampleController();

            ArrayList<Attachment> attachments = attachmentController.getByEntry(entry);
            ArrayList<Sample> samples = sampleController.getSamplesByEntry(entry);
            List<TraceSequence> sequences = TraceSequenceDAO.getByEntry(entry);

            Map<Sample, LinkedList<Storage>> sampleMap = new HashMap<Sample, LinkedList<Storage>>();
            for (Sample sample : samples) {
                Storage storage = sample.getStorage();

                LinkedList<Storage> storageList = new LinkedList<Storage>();
                List<Storage> storages = StorageDAO.getStoragesUptoScheme(storage);
                if (storages != null)
                    storageList.addAll(storages);
                Storage scheme = StorageDAO.getSchemeContainingParentStorage(storage);
                if (scheme != null)
                    storageList.add(scheme);

                sampleMap.put(sample, storageList);
            }

            SequenceController sequenceController = new SequenceController();
            boolean hasSequence = (sequenceController.getByEntry(entry) != null);

            EntryInfo info = EntryToInfoFactory.getInfo(account, entry, attachments, sampleMap,
                                                        sequences, hasSequence);

            //
            //  the parsed versions are separated out into complementary fields
            //
            String html = RichTextRenderer.richTextToHtml(info.getLongDescriptionType(),
                                                          info.getLongDescription());
            String parsed = getParsedNotes(html);
            info.setLongDescription(info.getLongDescription());
            info.setParsedDescription(parsed);
            String parsedShortDesc = WebUtils.linkifyText(account, info.getShortDescription());
            info.setLinkifiedShortDescription(parsedShortDesc);
            String parsedLinks = WebUtils.linkifyText(account, info.getLinks());
            info.setLinkifiedLinks(parsedLinks);

            // group with write permissions
            PermissionsController permissionsController = new PermissionsController();
            info.setCanEdit(permissionsController.hasWritePermission(account, entry));

            return info;

        } catch (ManagerException e) {
            Logger.error(e);
            return null;
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public EntryInfo retrieveEntryTipDetails(String sid, long id) throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sid);
            Entry entry;
            try {
                entry = new EntryController().get(account, id);
            } catch (PermissionException e) {
                Logger.info(account.getEmail() + ": attempting to view entry details for entry "
                                    + id + " but does not have read permission");
                return null;
            }

            Logger.info(account.getEmail() + ": retrieving entry details for " + id);

            AttachmentController attachmentController = new AttachmentController(account);
            SampleController sampleController = new SampleController();
            ArrayList<Attachment> attachments = attachmentController.getByEntry(entry);
            ArrayList<Sample> samples = sampleController.getSamplesByEntry(entry);
            List<TraceSequence> sequences = TraceSequenceDAO.getByEntry(entry);

            Map<Sample, LinkedList<Storage>> sampleMap = new HashMap<Sample, LinkedList<Storage>>();
            for (Sample sample : samples) {
                Storage storage = sample.getStorage();

                LinkedList<Storage> storageList = new LinkedList<Storage>();

                List<Storage> storages = StorageDAO.getStoragesUptoScheme(storage);
                if (storages != null)
                    storageList.addAll(storages);
                Storage scheme = StorageDAO.getSchemeContainingParentStorage(storage);
                if (scheme != null)
                    storageList.add(scheme);

                sampleMap.put(sample, storageList);
            }

            SequenceController sequenceController = new SequenceController();
            boolean hasSequence = (sequenceController.getByEntry(entry) != null);

            EntryInfo info = EntryToInfoFactory.getInfo(account, entry, attachments, sampleMap,
                                                        sequences, hasSequence);

            //
            //  the parsed versions are separated out into complementary fields
            //
            String html = RichTextRenderer.richTextToHtml(info.getLongDescriptionType(),
                                                          info.getLongDescription());
            String parsed = getParsedNotes(html);
            info.setLongDescription(info.getLongDescription());
            info.setParsedDescription(parsed);
            String parsedShortDesc = WebUtils.linkifyText(account, info.getShortDescription());
            info.setLinkifiedShortDescription(parsedShortDesc);
            String parsedLinks = WebUtils.linkifyText(account, info.getLinks());
            info.setLinkifiedLinks(parsedLinks);

            // group with write permissions
            PermissionsController permissionsController = new PermissionsController();
            info.setCanEdit(permissionsController.hasWritePermission(account, entry));

            return info;

        } catch (ManagerException e) {
            Logger.error(e);
            return null;
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    private String getParsedNotes(String s) {
        if (s == null) {
            return null;
        }

        final StringBuilder buffer = new StringBuilder();
        int newlineCount = 0;

        buffer.append("<p>");
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);

            switch (c) {
                case '\n':
                    newlineCount++;
                    break;

                case '\r':
                    break;

                default:
                    if (newlineCount == 1) {
                        buffer.append("<br/>");
                    } else if (newlineCount > 1) {
                        buffer.append("</p><p>");
                    }

                    buffer.append(c);
                    newlineCount = 0;
                    break;
            }
        }
        if (newlineCount == 1) {
            buffer.append("<br/>");
        } else if (newlineCount > 1) {
            buffer.append("</p><p>");
        }
        buffer.append("</p>");
        return buffer.toString();

    }

//    @Override
//    public AccountInfo retrieveAccountInfoForSession(String sid) {
//        Account account;
//        try {
//            account = retrieveAccountForSid(sid);
//            return accountToInfo(account);
//        } catch (ControllerException e) {
//            Logger.error(e);
//        } catch (AuthenticationException e) {
//            Logger.error(e);
//        }
//
//        return null;
//    }

    private AccountInfo accountToInfo(Account account) {
        if (account == null)
            return null;

        AccountInfo info = new AccountInfo();
        info.setEmail(account.getEmail());
        info.setFirstName(account.getFirstName());
        info.setLastName(account.getLastName());
        info.setInstitution(account.getInstitution());
        info.setDescription(account.getDescription());
        info.setInitials(account.getInitials());

        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d yyyy");
        Date memberSinceDate = account.getCreationTime();
        if (memberSinceDate != null)
            info.setSince(dateFormat.format(memberSinceDate));

        return info;
    }

    //
    // SEARCH
    // 

    @Override
    public ArrayList<Long> retrieveSearchResults(String sid, ArrayList<SearchFilterInfo> filters)
            throws AuthenticationException {
        ArrayList<Long> results = new ArrayList<Long>();

        if (filters == null || filters.isEmpty())
            return results;

        ArrayList<QueryFilter> queryFilters = new ArrayList<QueryFilter>();
        for (SearchFilterInfo filter : filters) {
            QueryFilter queryFilter = new QueryFilter(filter);
            queryFilters.add(queryFilter);
        }

        try {

            Account account = this.retrieveAccountForSid(sid);
            SearchController search = new SearchController();
            Set<Long> filterResults = search.runSearch(account, queryFilters); // TODO : this takes a while
            results.addAll(filterResults);

            return results;
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public ArrayList<BlastResultInfo> blastSearch(String sid, String query, QueryOperator program)
            throws AuthenticationException {

        try {
            Account account = this.retrieveAccountForSid(sid);
            ArrayList<BlastResultInfo> blastResults;

            SearchController searchController = new SearchController();
            switch (program) {
                case BLAST_N:
                    blastResults = searchController.runBlastN(account, query);
                    break;

                case TBLAST_X:
                    blastResults = searchController.runTblastx(account, query);
                    break;
                //                String proteinQuery = SequenceUtils.translateToProtein(query);  as far as I can
                // tell this is only for display to user

                default:
                    return null;
            }

            return blastResults;

            // filter results

        } catch (ControllerException ce) {
            Logger.error(ce);
            return null;
        } catch (ProgramTookTooLongException e) {
            Logger.error(e);
            return null;
        }
    }

    //
    // SAMPLES
    //

    // Uses email identifier from session if parameter instance is null
    @Override
    public LinkedList<Long> retrieveSamplesByDepositor(String sid, String email, ColumnField field, boolean asc)
            throws AuthenticationException {

        Account account = this.retrieveAccountForSid(sid);
        if (field == null)
            field = ColumnField.CREATED;

        String depositor = (email == null) ? account.getEmail() : email;
        SampleController sampleController = new SampleController();

        // sort param
        try {
            return sampleController.retrieveSamplesByDepositor(depositor, field, asc);
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public LinkedList<SampleInfo> retrieveSampleInfo(String sid, LinkedList<Long> sampleIds,
            ColumnField sortField, boolean asc)
            throws AuthenticationException {
        LinkedList<SampleInfo> data = null;

        try {
            Account account = this.retrieveAccountForSid(sid);
            SampleController sampleController = new SampleController();

            LinkedList<Sample> results = sampleController.retrieveSamplesByIdSet(sampleIds, asc);
            if (results != null) {
                data = new LinkedList<SampleInfo>();

                for (Sample sample : results) { // TODO
                    SampleInfo info = new SampleInfo();
                    info.setSampleId(String.valueOf(sample.getId()));
                    info.setCreationTime(sample.getCreationTime());
                    EntryInfo view = EntryViewFactory.createTipView(account, sample.getEntry());
                    info.setEntryInfo(view);
                    info.setLabel(sample.getLabel());
                    info.setNotes(sample.getNotes());
                    Storage storage = sample.getStorage();
                    if (storage != null) {
                        info.setLocationId(String.valueOf(storage.getId()));
                        info.setLocation(storage.getIndex());
                    }
                    data.add(info);
                }
            }

            return data;
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public FolderDetails retrieveFolderDetails(String sid, long folderId) throws AuthenticationException {
        Account account;
        AccountController controller = new AccountController();

        try {
            account = this.retrieveAccountForSid(sid);
            FolderController folderController = new FolderController();
            Folder folder = folderController.getFolderById(folderId);
            if (folder == null)
                return null;

            Logger.info(account.getEmail() + ": retrieving folder details for folder "
                                + folder.getName());

            long id = folder.getId();
            boolean isSystemFolder = folder.getOwnerEmail().equals(
                    controller.getSystemAccount().getEmail());
            FolderDetails details = new FolderDetails(id, folder.getName(), isSystemFolder);
            BigInteger folderSize = folderController.getFolderSize(id);
            details.setCount(folderSize);
            details.setDescription(folder.getDescription());
            return details;

        } catch (ControllerException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public FolderDetails createUserCollection(String sid, String name, String description,
            ArrayList<Long> contents) throws AuthenticationException {
        try {
            Account account = this.retrieveAccountForSid(sid);
            EntryController entryController = new EntryController();
            FolderController folderController = new FolderController();
            Logger.info(account.getEmail() + ": creating new folder with name " + name);

            Folder folder = folderController.createNewFolder(account.getEmail(), name, description);

            FolderDetails details = new FolderDetails(folder.getId(), folder.getName(), false);
            details.setDescription(folder.getDescription());

            if (contents != null && !contents.isEmpty()) {

                ArrayList<Entry> entrys = new ArrayList<Entry>(entryController.getEntriesByIdSet(
                        account, contents));
                folderController.addFolderContents(folder.getId(), entrys);
                details.setContents(contents);
                BigInteger size = BigInteger.valueOf(contents.size());
                details.setCount(size);
            } else {
              details.setCount(BigInteger.valueOf(0));
              }

            return details;
        } catch (ControllerException e) {
            Logger.error(e.getMessage());
            return null;
        }
    }

    @Override
    public ArrayList<FolderDetails> moveToUserCollection(String sid, long source,
            ArrayList<Long> destination, ArrayList<Long> entryIds)
            throws AuthenticationException {

        try {
            Account account = this.retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": moving entries to user collection.");
            EntryController entryController = new EntryController();
            FolderController folderController = new FolderController();

            ArrayList<Entry> entrys = new ArrayList<Entry>(entryController.getEntriesByIdSet(account, entryIds));
            if (folderController.removeFolderContents(account, source, entryIds) != null) {
                ArrayList<FolderDetails> results = new ArrayList<FolderDetails>();

                for (long folderId : destination) {
                    Folder folder = folderController.addFolderContents(folderId, entrys);
                    FolderDetails details = new FolderDetails(folder.getId(), folder.getName(),
                                                              false);
                    BigInteger folderSize = folderController.getFolderSize(folder.getId());
                    details.setCount(folderSize);
                    details.setDescription(folder.getDescription());
                    results.add(details);
                }

                Folder sourceFolder = folderController.getFolderById(source);
                BigInteger folderSize = folderController.getFolderSize(source);
                FolderDetails sourceDetails = new FolderDetails(sourceFolder.getId(),
                                                                sourceFolder.getName(), false);
                sourceDetails.setCount(folderSize);
                results.add(sourceDetails);
                return results;
            }
        } catch (ControllerException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public FolderDetails removeFromUserCollection(String sid, long source, ArrayList<Long> entryIds) throws
            AuthenticationException {
        try {
            Account account = this.retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": removing from user collection.");
            FolderController folderController = new FolderController();

            Folder folder = folderController.removeFolderContents(account, source, entryIds);
            if (folder == null)
                return null;

            FolderDetails details = new FolderDetails(folder.getId(), folder.getName(), false);
            BigInteger folderSize = folderController.getFolderSize(source);
            details.setCount(folderSize);
            details.setDescription(folder.getDescription());
            return details;
        } catch (ControllerException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public ArrayList<FolderDetails> addEntriesToCollection(String sid, ArrayList<Long> destination,
            ArrayList<Long> entryIds) throws AuthenticationException {

        ArrayList<FolderDetails> results = new ArrayList<FolderDetails>();

        try {
            Account account = this.retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": adding entries to collection.");
            EntryController entryController = new EntryController();
            FolderController folderController = new FolderController();

            ArrayList<Entry> entrys = new ArrayList<Entry>(entryController.getEntriesByIdSet(
                    account, entryIds));
            for (long folderId : destination) {
                Folder folder = folderController.addFolderContents(folderId, entrys);
                if (folder == null)
                    folder = folderController.getFolderById(folderId);
                FolderDetails details = new FolderDetails(folder.getId(), folder.getName(), false);
                BigInteger size = folderController.getFolderSize(folderId);
                details.setCount(size);
                details.setDescription(folder.getDescription());
                results.add(details);
            }
            return results;
        } catch (ControllerException e) {
            Logger.error(e);
        }
        return null;
    }

    @Override
    public AccountInfo retrieveProfileInfo(String sid, String userId) throws AuthenticationException {

        try {
            Account account = retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": retrieving profile info for " + userId);
            AccountController controller = new AccountController();
            EntryController entryController = new EntryController();
            account = controller.getByEmail(userId);
            if (account == null)
                return null;

            AccountInfo accountInfo = accountToInfo(account);

            // get the count for samples
            SampleController sampleController = new SampleController();
            int sampleCount = sampleController.getSampleCountBy(accountInfo.getEmail());
            accountInfo.setUserSampleCount(sampleCount);
            boolean isModerator = controller.isModerator(account);
            long visibleEntryCount;
            if (isModerator)
                visibleEntryCount = entryController.getAllEntryCount();
            else
                visibleEntryCount = entryController.getNumberOfVisibleEntries(account);
            accountInfo.setVisibleEntryCount(visibleEntryCount);
            int entryCount = entryController.getOwnerEntryCountBy(accountInfo.getEmail());
            accountInfo.setUserEntryCount(entryCount);

            return accountInfo;
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public boolean removeSequence(String sid, long entryId) throws AuthenticationException {

        try {
            Account account = retrieveAccountForSid(sid);
            if (account == null)
                return false;

            Entry entry = null;
            EntryController entryController = new EntryController();

            try {
                entry = entryController.get(account, entryId);
                if (entry == null) {
                    Logger.info("Could not retrieve entry with id " + entryId);
                    return false;
                }
            } catch (PermissionException e) {
                Logger.warn(account.getEmail() + " attempting to retrieve entry " + entryId
                                    + " but does not have permissions");
            }

            SequenceController sequenceController = new SequenceController();
            Sequence sequence = sequenceController.getByEntry(entry);

            if (sequence != null) {
                try {
                    sequenceController.delete(account, sequence);
                    Logger.info("User '" + account.getEmail() + "' removed sequence: '" + entryId
                                        + "'");
                    return true;
                } catch (PermissionException e) {
                    Logger.warn(account.getEmail() + " attempting to delete sequence for entry "
                                        + entryId + " but does not have permissions");
                }
            }
        } catch (ControllerException e) {
            Logger.error(e);
            return false;
        }
        return false;
    }

    @Override
    public ArrayList<BulkImportDraftInfo> retrieveImportDraftData(String sid,
            String email) throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sid);
            if (account == null)
                return null;

            BulkImportController biController = new BulkImportController(account);
            ArrayList<BulkImport> results = biController.retrieveByUser(account);
            ArrayList<BulkImportDraftInfo> info = new ArrayList<BulkImportDraftInfo>();

            if (results != null) {
                for (BulkImport draft : results) {
                    BulkImportDraftInfo draftInfo = new BulkImportDraftInfo();
                    List<BulkImportEntryData> primary = draft.getPrimaryData();
                    if (primary != null)
                        draftInfo.setCount(draft.getPrimaryData().size());
                    else
                        draftInfo.setCount(-1);
                    draftInfo.setCreated(draft.getCreationTime());
                    draftInfo.setId(draft.getId());

                    Account draftAccount = draft.getAccount();
                    draftInfo.setName(draft.getName());
                    draftInfo.setType(EntryAddType.stringToType(draft.getType()));

                    // set the account info
                    AccountInfo accountInfo = new AccountInfo();
                    accountInfo.setEmail(draftAccount.getEmail());
                    accountInfo.setFirstName(draftAccount.getFirstName());
                    accountInfo.setLastName(draftAccount.getLastName());
                    draftInfo.setAccount(accountInfo);
                    info.add(draftInfo);
                }
            }

            return info;

        } catch (ControllerException ce) {
            Logger.error(ce);
            return null;
        }
    }

    @Override
    public ArrayList<BulkImportDraftInfo> retrieveDraftsPendingVerification(String sid) throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sid);
            if (account == null)
                return null;

            AccountController controller = new AccountController();
            if (!controller.isModerator(account))
                return null;

            BulkImportController biController = new BulkImportController(account);

            ArrayList<BulkImport> results = new ArrayList<BulkImport>(biController.retrieveAll());
            ArrayList<BulkImportDraftInfo> info = new ArrayList<BulkImportDraftInfo>();

            for (BulkImport draft : results) {
                BulkImportDraftInfo draftInfo = new BulkImportDraftInfo();
                List<BulkImportEntryData> primary = draft.getPrimaryData();
                if (primary != null)
                    draftInfo.setCount(draft.getPrimaryData().size());
                else
                    draftInfo.setCount(-1);
                draftInfo.setCreated(draft.getCreationTime());
                draftInfo.setId(draft.getId());
                Account draftAccount = draft.getAccount();
                draftInfo.setName(draftAccount.getFullName());
                draftInfo.setType(EntryAddType.stringToType(draft.getType()));

                // set the account info
                AccountInfo accountInfo = new AccountInfo();
                accountInfo.setEmail(draftAccount.getEmail());
                accountInfo.setFirstName(draftAccount.getFirstName());
                accountInfo.setLastName(draftAccount.getLastName());
                draftInfo.setAccount(accountInfo);
                info.add(draftInfo);
            }

            return info;

        } catch (ControllerException ce) {
            Logger.error(ce);
            return null;
        }
    }

    @Override
    public BulkImportDraftInfo deleteDraftPendingVerification(String sid, long draftId) throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sid);
            if (account == null)
                return null;

            AccountController controller = new AccountController();
            if (!controller.isModerator(account))
                return null;

            BulkImportController biController = new BulkImportController(account);
            BulkImport draft = biController.retrieveById(draftId);
            if (draft == null)
                return null;

            Logger.info(account.getEmail() + ": deleting bulk import draft with id "
                                + draft.getId());

            biController.deleteDraft(draft);

            BulkImportDraftInfo draftInfo = new BulkImportDraftInfo();
            List<BulkImportEntryData> primary = draft.getPrimaryData();
            if (primary != null)
                draftInfo.setCount(draft.getPrimaryData().size());
            else
                draftInfo.setCount(-1);
            draftInfo.setCreated(draft.getCreationTime());
            draftInfo.setId(draft.getId());
            Account draftAccount = draft.getAccount();
            draftInfo.setName(draftAccount.getFullName());
            draftInfo.setType(EntryAddType.stringToType(draft.getType()));

            // set the account info
            AccountInfo accountInfo = new AccountInfo();
            accountInfo.setEmail(draftAccount.getEmail());
            accountInfo.setFirstName(draftAccount.getFirstName());
            accountInfo.setLastName(draftAccount.getLastName());
            draftInfo.setAccount(accountInfo);
            return draftInfo;

        } catch (ControllerException ce) {
            Logger.error(ce);
            return null;
        }

    }

    @Override
    public BulkImportDraftInfo retrieveBulkImport(String sid, long id) throws AuthenticationException {

        BulkImport bi;
        Account account;

        try {
            account = retrieveAccountForSid(sid);
            if (account == null)
                return null;

            BulkImportController biController = new BulkImportController(account);
            bi = biController.retrieveById(id);
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }

        BulkImportDraftInfo draftInfo = new BulkImportDraftInfo();
        draftInfo.setCount(bi.getPrimaryData().size());
        draftInfo.setCreated(bi.getCreationTime());
        draftInfo.setId(bi.getId());
        draftInfo.setName(bi.getName());
        EntryAddType type = EntryAddType.stringToType(bi.getType());
        if (type != null)
            draftInfo.setType(type);

        // primary data
        ArrayList<EntryInfo> primary = new ArrayList<EntryInfo>();
        String ownerEmail = bi.getAccount().getEmail();

        List<BulkImportEntryData> data = bi.getPrimaryData();
        for (BulkImportEntryData datum : data) {
            Entry entry = datum.getEntry();
            entry.setOwnerEmail(ownerEmail);

            EntryInfo info = EntryToInfoFactory.getInfo(account, entry, null, null, null, false);
            byte[] array = ArrayUtils.toPrimitive(bi.getAttachmentFile());

            // check for attachments
            if (array != null) {
                ArrayList<AttachmentInfo> attInfos = new ArrayList<AttachmentInfo>();
                try {
                    LinkedList<String> fileNames = BulkImportController.extractZip(array);
                    for (String name : fileNames) {
                        AttachmentInfo attachment = new AttachmentInfo();
                        attachment.setFilename(name);
                        attInfos.add(attachment);
                    }

                } catch (IOException e) {
                    Logger.error(e);
                }
                info.setAttachments(attInfos);
            }

            // check for sequences
            array = ArrayUtils.toPrimitive(bi.getSequenceFile());
            if (array != null) {
                ArrayList<SequenceAnalysisInfo> seqInfos = new ArrayList<SequenceAnalysisInfo>();
                try {
                    LinkedList<String> fileNames = BulkImportController.extractZip(array);
                    for (String name : fileNames) {
                        SequenceAnalysisInfo sequence = new SequenceAnalysisInfo();
                        sequence.setName(name);
                        seqInfos.add(sequence);
                    }

                } catch (IOException e) {
                    Logger.error(e);
                }
                info.setSequenceAnalysis(seqInfos);
            }

            primary.add(info);
        }
        draftInfo.setPrimary(primary);

        // secondary data (if any)
        List<BulkImportEntryData> data2 = bi.getSecondaryData();
        if (data2 != null && !data2.isEmpty()) {
            ArrayList<EntryInfo> secondary = new ArrayList<EntryInfo>();
            for (BulkImportEntryData datum : data2) {
                Entry entry2 = datum.getEntry();
                entry2.setOwnerEmail(ownerEmail);

                EntryInfo info = EntryToInfoFactory.getInfo(account, entry2, null, null, null,
                                                            false);
                byte[] array = ArrayUtils.toPrimitive(bi.getAttachmentFile());

                // check for attachments
                if (array != null) {
                    ArrayList<AttachmentInfo> attInfos = new ArrayList<AttachmentInfo>();
                    try {
                        LinkedList<String> fileNames = BulkImportController.extractZip(array);
                        for (String name : fileNames) {
                            AttachmentInfo attachment = new AttachmentInfo();
                            attachment.setFilename(name);
                            attInfos.add(attachment);
                        }

                    } catch (IOException e) {
                        Logger.error(e);
                    }
                    info.setAttachments(attInfos);
                }

                // check for sequences
                array = ArrayUtils.toPrimitive(bi.getSequenceFile());
                if (array != null) {
                    ArrayList<SequenceAnalysisInfo> seqInfos = new ArrayList<SequenceAnalysisInfo>();
                    try {
                        LinkedList<String> fileNames = BulkImportController.extractZip(array);
                        for (String name : fileNames) {
                            SequenceAnalysisInfo sequence = new SequenceAnalysisInfo();
                            sequence.setName(name);
                            seqInfos.add(sequence);
                        }

                    } catch (IOException e) {
                        Logger.error(e);
                    }
                    info.setSequenceAnalysis(seqInfos);
                }
                secondary.add(info);
            }
            draftInfo.setSecondary(secondary);
        }

        return draftInfo;
    }

    @Override
    public BulkImportDraftInfo updateBulkImportDraft(String sessionId, long id, String email,
            String name, ArrayList<EntryInfo> primary,
            ArrayList<EntryInfo> secondary) throws AuthenticationException {
        Account account;
        try {
            account = retrieveAccountForSid(sessionId);
            if (account == null)
                return null;

            BulkImportController controller = new BulkImportController(account);
            BulkImport result = controller.updateBulkImportDraft(id, name, account, primary,
                                                                 secondary);

            // result to DTO
            BulkImportDraftInfo draftInfo = new BulkImportDraftInfo();
            draftInfo.setId(result.getId());
            draftInfo.setCount(result.getPrimaryData().size());
            draftInfo.setCreated(result.getCreationTime());
            draftInfo.setName(result.getName());
            return draftInfo;

        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public BulkImportDraftInfo saveBulkImportDraft(String sid, String email, String name,
            ArrayList<EntryInfo> primary,
            ArrayList<EntryInfo> secondary) throws AuthenticationException {

        Account account;
        try {
            account = retrieveAccountForSid(sid);
            if (account == null)
                return null;

            if (primary.isEmpty())
                return null;

            BulkImportController controller = new BulkImportController(account);
            BulkImport draft = controller.createBulkImport(account, primary, secondary, email);
            draft.setName(name);

            BulkImportController biController = new BulkImportController(account);
            BulkImport result = biController.createBulkImportRecord(draft);

            // result to DTO
            BulkImportDraftInfo draftInfo = new BulkImportDraftInfo();
            draftInfo.setId(result.getId());
            draftInfo.setCount(result.getPrimaryData().size());
            draftInfo.setCreated(result.getCreationTime());
            draftInfo.setName(result.getName());
            return draftInfo;

        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public boolean submitBulkImport(String sid, String email, ArrayList<EntryInfo> primary,
            ArrayList<EntryInfo> secondary) throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sid);
            if (account == null)
                return false;

            if (primary.isEmpty())
                return false;

            BulkImportController controller = new BulkImportController(account);
            BulkImport bulkImport = controller.createBulkImport(account, primary, secondary, email);
            controller.submitBulkImportForVerification(bulkImport);
            return true;

        } catch (ControllerException ce) {
            Logger.error(ce);
            return false;
        }
    }


    @Override
    public Long createEntry(String sid, EntryInfo info) throws AuthenticationException {
        try {
            Account account = this.retrieveAccountForSid(sid);
            Logger.info(account.getEmail() + ": creating new entry");
            EntryController controller = new EntryController();
            Entry entry = InfoToModelFactory.infoToEntry(info);

            SampleController sampleController = new SampleController();
            StorageController storageController = new StorageController();
            ArrayList<SampleStorage> sampleMap = info.getSampleStorage();

            if (sampleMap != null) {
                for (SampleStorage sampleStorage : sampleMap) {
                    SampleInfo sampleInfo = sampleStorage.getSample();
                    LinkedList<StorageInfo> locations = sampleStorage.getStorageList();

                    Sample sample = sampleController.createSample(sampleInfo.getLabel(),
                                                                  account.getEmail(), sampleInfo.getNotes());
                    sample.setEntry(entry);

                    if (locations == null || locations.isEmpty()) {

                        // create sample, but not location
                        try {
                            Logger.info("Creating sample without location");
                            sampleController.saveSample(account, sample);
                        } catch (PermissionException e) {
                            Logger.error(e);
                            sample = null;
                        } catch (ControllerException e) {
                            Logger.error(e);
                            sample = null;
                        }
                    } else {
                        // create sample and location
                        String[] labels = new String[locations.size()];
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < labels.length; i++) {
                            labels[i] = locations.get(i).getDisplay();
                            sb.append(labels[i]);
                            if (i - 1 < labels.length)
                                sb.append("/");
                        }

                        Logger.info("Creating sample with locations " + sb.toString());
                        Storage storage;
                        try {
                            Storage scheme = storageController.get(
                                    Long.parseLong(sampleInfo.getLocationId()), false);
                            storage = storageController.getLocation(scheme, labels);
                            storage = storageController.update(storage);
                            sample.setStorage(storage);
                        } catch (NumberFormatException e) {
                            Logger.error(e);
                            continue;
                        } catch (ControllerException e) {
                            Logger.error(e);
                            continue;
                        }
                    }

                    if (sample != null) {
                        try {
                            sampleController.saveSample(account, sample);
                        } catch (ControllerException e) {
                            Logger.error(e);
                        } catch (PermissionException e) { // having to deal with permission exceptions do not make
                            // sense in this context since entry was created by user
                            Logger.error(e);
                        }
                    }
                }
            }

            return controller.createEntry(entry).getId();
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public ArrayList<Long> createStrainWithPlasmid(String sid, HashSet<EntryInfo> infoSet) throws
            AuthenticationException {

        Account account;

        try {
            account = retrieveAccountForSid(sid);


            Logger.info(account.getEmail() + ": creating strain with plasmid");
            EntryController controller = new EntryController();


            Strain strain = null;
            Plasmid plasmid = null;

            for (EntryInfo info : infoSet) {
                Entry entry = InfoToModelFactory.infoToEntry(info);
                switch (info.getType()) {
                    case PLASMID:
                        plasmid = (Plasmid) entry;
                        break;

                    case STRAIN:
                        strain = (Strain) entry;
                        break;
                }
            }
            HashSet<Entry> results = controller.createStrainWithPlasmid(strain, plasmid);
            ArrayList<Long> ids = new ArrayList<Long>();

            for (Entry result : results) {
                ids.add(result.getId());
            }
            return ids;
        } catch (ControllerException ce) {
            Logger.error(ce);
            return null;
        }
    }

    @Override
    public SampleStorage createSample(String sessionId, SampleStorage sampleStorage,
            long entryId) throws AuthenticationException {

        Account account = retrieveAccountForSid(sessionId);
        Logger.info(account.getEmail() + ": creating sample for entry with id " + entryId);

        EntryController controller = new EntryController();
        SampleController sampleController = new SampleController();
        StorageController storageController = new StorageController();

        Entry entry;
        try {
            entry = controller.get(account, entryId);
            if (entry == null) {
                Logger.error("Could not retrieve entry with id " + entryId
                                     + ". Skipping sample creation");
                return null;
            }
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        } catch (PermissionException e) {
            Logger.error(e);
            return null;
        }

        SampleInfo sampleInfo = sampleStorage.getSample();
        LinkedList<StorageInfo> locations = sampleStorage.getStorageList();

        Sample sample = sampleController.createSample(sampleInfo.getLabel(), account.getEmail(),
                                                      sampleInfo.getNotes());
        sample.setEntry(entry);

        if (locations == null || locations.isEmpty()) {
            Logger.info("Creating sample without location");

            // create sample, but not location
            try {
                sample = sampleController.saveSample(account, sample);
                sampleStorage.getSample().setSampleId(sample.getId() + "");
                sampleStorage.getSample().setDepositor(account.getEmail());
                return sampleStorage;
            } catch (PermissionException e) {
                Logger.error(e);
                return null;
            } catch (ControllerException e) {
                Logger.error(e);
                return null;
            }
        }

        // create sample and location
        String[] labels = new String[locations.size()];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            labels[i] = locations.get(i).getDisplay();
            sb.append(labels[i]);
            if (i - 1 < labels.length)
                sb.append("/");
        }

        Logger.info("Creating sample with locations " + sb.toString());

        Storage storage = null;
        try {
            Storage scheme = storageController.get(Long.parseLong(sampleInfo.getLocationId()), false);
            storage = storageController.getLocation(scheme, labels);
            storage = storageController.update(storage);
            sample.setStorage(storage);
            sample = sampleController.saveSample(account, sample);
            sampleStorage.getSample().setSampleId(sample.getId() + "");
            sampleStorage.getSample().setDepositor(account.getEmail());
            return sampleStorage;
        } catch (NumberFormatException e) {
            Logger.error(e);
        } catch (ControllerException e) {
            Logger.error(e);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public boolean updateEntry(String sid, EntryInfo info) throws AuthenticationException {

        try {
            Account account = retrieveAccountForSid(sid);

            Logger.info(account.getEmail() + ": updating entry " + info.getId());
            EntryController controller = new EntryController();
            Entry existing = controller.getByRecordId(account, info.getRecordId());

            Entry entry = InfoToModelFactory.infoToEntry(info, existing);
            controller.save(account, entry);
            return true;

        } catch (ControllerException e) {
            Logger.error(e);
        } catch (PermissionException e) {
            Logger.error(e);
        }
        return false;
    }

    @Override
    public HashMap<SampleInfo, ArrayList<String>> retrieveStorageSchemes(String sessionId,
            EntryType type) throws AuthenticationException {

        Account account = retrieveAccountForSid(sessionId);
        HashMap<SampleInfo, ArrayList<String>> schemeMap = new HashMap<SampleInfo, ArrayList<String>>();
        StorageController storageController = new StorageController();
        List<Storage> schemes;
        try {
            schemes = storageController.getStorageSchemesForEntryType(type.getName());
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }

        for (Storage scheme : schemes) {

            SampleInfo sampleInfo = new SampleInfo();
            sampleInfo.setLocation(scheme.getName());
            sampleInfo.setLocationId(String.valueOf(scheme.getId()));

            ArrayList<String> schemeOptions = new ArrayList<String>();
            Storage storage;

            try {
                storage = storageController.get(scheme.getId(), false);

                if (storage != null && storage.getSchemes() != null) {
                    for (Storage storageScheme : storage.getSchemes()) {
                        schemeOptions.add(storageScheme.getName());
                    }
                }
            } catch (ControllerException e) {
                Logger.error(e);
                continue;
            }

            switch (type) {
                case STRAIN:
                    sampleInfo.setLabel(PopulateInitialDatabase.DEFAULT_STRAIN_STORAGE_SCHEME_NAME);
                    break;
                case PLASMID:
                    sampleInfo.setLabel(PopulateInitialDatabase.DEFAULT_PLASMID_STORAGE_SCHEME_NAME);
                    break;
                case PART:
                    sampleInfo.setLabel(PopulateInitialDatabase.DEFAULT_PART_STORAGE_SCHEME_NAME);
                    break;
                case ARABIDOPSIS:
                    sampleInfo
                            .setLabel(PopulateInitialDatabase.DEFAULT_ARABIDOPSIS_STORAGE_SCHEME_NAME);
                    break;
            }

            schemeMap.put(sampleInfo, schemeOptions);
        }

        return schemeMap;
    }

    @Override
    public SuggestOracle.Response getPermissionSuggestions(Request req) {

        SuggestOracle.Response resp = new SuggestOracle.Response();
        List<Suggestion> suggestions = new ArrayList<Suggestion>(req.getLimit());

        try {
            // TODO : split tokens if there are spaces. this is for a manager
            AccountController controller = new AccountController();
            Set<Account> accounts = controller.getMatchingAccounts(req.getQuery(), req.getLimit());
            for (Account account : accounts) {
                PermissionSuggestion object = new PermissionSuggestion(PermissionType.READ_ACCOUNT,
                                                                       account.getId(), account.getFullName());
                suggestions.add(object);
            }
            GroupController groupController = new GroupController();
            Set<Group> groups = groupController.getMatchingGroups(req.getQuery(), req.getLimit());
            for (Group group : groups) {
                PermissionSuggestion object = new PermissionSuggestion(PermissionType.READ_GROUP,
                                                                       group.getId(), group.getLabel());
                suggestions.add(object);
            }
        } catch (ControllerException e) {
            Logger.error(e);
        }

        resp.setSuggestions(suggestions);
        return resp;
    }

    @Override
    public ArrayList<PermissionInfo> retrievePermissionData(String sessionId, Long entryId)
            throws AuthenticationException {

        ArrayList<PermissionInfo> results;
        Entry entry;

        // TODO : problem here is that we keep checking for permissions for everything
        // TODO : there needs to be a single call that returns all about the entry

        final Account account;
        try {
            account = retrieveAccountForSid(sessionId);
            EntryController controller = new EntryController();
            entry = controller.get(account, entryId);
            if (entry == null)
                return null;

        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        } catch (PermissionException e) {
            Logger.error(e);
            return null;
        }

        results = new ArrayList<PermissionInfo>();
        PermissionsController permissionsController = new PermissionsController();

        try {
            Set<Account> readAccounts = permissionsController.getReadUser(account, entry);
            for (Account readAccount : readAccounts) {
                results.add(new PermissionInfo(PermissionType.READ_ACCOUNT, readAccount.getId(),
                                               readAccount.getFullName()));
            }
        } catch (ControllerException me) {
            Logger.error(me);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        try {
            Set<Account> writeAccounts = permissionsController.getWriteUser(account, entry);
            for (Account writeAccount : writeAccounts) {
                results.add(new PermissionInfo(PermissionType.WRITE_ACCOUNT, writeAccount.getId(),
                                               writeAccount.getFullName()));
            }
        } catch (ControllerException me) {
            Logger.error(me);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        try {
            Set<Group> readGroups = permissionsController.getReadGroup(account, entry);
            for (Group group : readGroups) {
                results.add(new PermissionInfo(PermissionType.READ_GROUP, group.getId(), group
                        .getLabel()));
            }
        } catch (ControllerException me) {
            Logger.error(me);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        try {
            Set<Group> writeGroups = permissionsController.getWriteGroup(account, entry);
            for (Group group : writeGroups) {
                results.add(new PermissionInfo(PermissionType.WRITE_GROUP, group.getId(), group
                        .getLabel()));
            }
        } catch (ControllerException me) {
            Logger.error(me);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        return results;
    }

    @Override
    public ArrayList<NewsItem> retrieveNewsItems(String sessionId) throws AuthenticationException {

        retrieveAccountForSid(sessionId);
        ArrayList<NewsItem> items = new ArrayList<NewsItem>();
        ArrayList<News> results;

        try {

            results = new NewsController().retrieveAll();
            for (News news : results) {
                NewsItem item = new NewsItem(String.valueOf(news.getId()), news.getCreationTime(),
                                             news.getTitle(), news.getBody());
                items.add(item);
            }
        } catch (ControllerException e) {
            Logger.error(e);
        }

        return items;
    }

    @Override
    public NewsItem createNewsItem(String sessionId, NewsItem item) throws AuthenticationException {

        try {
            retrieveAccountForSid(sessionId);
            News news = new News();
            news.setTitle(item.getHeader());
            news.setBody(item.getBody());

            NewsController controller = new NewsController();
            News saved = controller.save(news);
            item.setCreationDate(saved.getCreationTime());
            item.setId(String.valueOf(saved.getId()));
            return item;
        } catch (ControllerException e) {
            Logger.error(e);
        }

        return null;
    }

    @Override
    public FolderDetails updateFolder(String sid, long folderId, FolderDetails update) throws AuthenticationException {
        Account account;
        try {
            FolderController folderController = new FolderController();
            account = retrieveAccountForSid(sid);
            Folder folder = folderController.getFolderById(folderId);
            if (folder == null)
                return null;

            Logger.info(account.getEmail() + ": updating folder " + folder.getName() + " with id "
                                + folder.getId());
            folder.setName(update.getName());
            folder.setDescription(update.getDescription());
            Folder updated = folderController.updateFolder(folder);
            update.setId(updated.getId());
            return update;
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }
    }

    @Override
    public boolean addPermission(String sessionId, long entryId, PermissionInfo permissionInfo) throws
            AuthenticationException {

        Account account;
        try {
            account = retrieveAccountForSid(sessionId);
            if (account == null)
                return false;

            Logger.info(account.getEmail() + ": updating permissions for entry with id \""
                                + entryId + "\"");
            EntryController entryController = new EntryController();
            PermissionsController permissionController = new PermissionsController();
            Entry entry = entryController.get(account, entryId);
            if (entry == null)
                return false;

            permissionController.addPermission(account, permissionInfo.getType(), entry, permissionInfo.getId());
            return true;

        } catch (ControllerException e) {
            Logger.error(e);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        return false;
    }

    @Override
    public boolean removePermission(String sessionId, long entryId, PermissionInfo permissionInfo) throws
            AuthenticationException {

        Account account;
        try {
            account = retrieveAccountForSid(sessionId);
            if (account == null)
                return false;

            Logger.info(account.getEmail() + ": removing permissions for entry with id \""
                                + entryId + "\"");
            EntryController entryController = new EntryController();
            Entry entry = entryController.get(account, entryId);
            if (entry == null)
                return false;

            PermissionsController permissionController = new PermissionsController();
            permissionController.removePermission(account, permissionInfo.getType(), entry,
                                                  permissionInfo.getId());
            return true;

        } catch (ControllerException e) {
            Logger.error(e);
        } catch (PermissionException e) {
            Logger.error(e);
        }

        return false;
    }

    @Override
    public boolean saveSequence(String sessionId, long entryId, String sequenceUser) throws AuthenticationException {

        Account account;
        Entry entry;

        try {
            account = retrieveAccountForSid(sessionId);
            if (account == null)
                return false;

            Logger.info(account.getEmail() + ": saving sequence for entry " + entryId);
            EntryController entryController = new EntryController();
            entry = entryController.get(account, entryId);
            if (entry == null) {
                Logger.error("Could not retrieve entry with id " + entryId);
                return false;
            }
        } catch (ControllerException e) {
            Logger.error(e);
            return false;
        } catch (PermissionException e) {
            Logger.error(e);
            return false;
        }

        SequenceController sequenceController = new SequenceController();
        IDNASequence dnaSequence = SequenceController.parse(sequenceUser);

        if (dnaSequence == null || dnaSequence.getSequence().equals("")) {
            String errorMsg = "Couldn't parse sequence file! Supported formats: "
                    + GeneralParser.getInstance().availableParsersToString()
                    + ". "
                    + "If you believe this is an error, please contact the administrator with your file";

            Logger.error(errorMsg);
            return false;
        }

        Sequence sequence = null;

        try {
            sequence = SequenceController.dnaSequenceToSequence(dnaSequence);
            sequence.setSequenceUser(sequenceUser);
            sequence.setEntry(entry);
            return sequenceController.save(account, sequence) != null;
        } catch (ControllerException e) {
            Logger.error(e);
        } catch (PermissionException e) {
            Logger.error(e);
        }
        return false;
    }

    @Override
    public boolean sendFeedback(String email, String message) {
        Emailer.send(email, JbeirSettings.getSetting("PROJECT_NAME"),
                     "Thank you for sending your feedback.\n\nBest regards,\nRegistry Team");

        Emailer.send(JbeirSettings.getSetting("ADMIN_EMAIL"), "Registry site feedback", message);
        if (!JbeirSettings.getSetting("ADMIN_EMAIL").equals(
                JbeirSettings.getSetting("MODERATOR_EMAIL"))) {
            Emailer.send(JbeirSettings.getSetting("MODERATOR_EMAIL"), "Registry site feedback",
                         message);
        }

        return true;
    }

    @Override
    public HashMap<EntryType, Long> retrieveEntryCounts(String sessionId) throws AuthenticationException {
        // admin only 
        Account account = null;
        AccountController controller = new AccountController();
        EntryController entryController = new EntryController();

        try {
            account = retrieveAccountForSid(sessionId);
            if (!controller.isModerator(account)) {
                Logger.warn(account.getEmail()
                                    + ": attempting to retrieve admin only feature (entry Counts)");
                return null;
            }
        } catch (ControllerException ce) {
            Logger.error(ce);
        }

        Logger.info(account.getEmail() + ": retrieving entry type counts");
        HashMap<EntryType, Long> counts = new HashMap<EntryType, Long>();
        for (EntryType type : EntryType.values()) {
            long count;
            try {
                count = entryController.retrieveEntryByType(account, type.getName());
                counts.put(type, count);
            } catch (ControllerException e) {
                Logger.error("Could not retrieve counts for " + type.getName(), e);
            }
        }

        return counts;
    }

    // Groups //
    @Override
    public ArrayList<GroupInfo> retrieveAllGroups(String sessionId) throws AuthenticationException {

        Account account = null;
        AccountController controller = new AccountController();
        GroupController groupController = new GroupController();

        try {
            account = retrieveAccountForSid(sessionId);
            if (account == null)
                return null;

            if (!controller.isModerator(account)) {
                Logger.warn(account.getEmail()
                                    + ": attempting to retrieve admin only feature (groups)");
                return null;
            }
        } catch (ControllerException ce) {
            Logger.error(ce);
        }

        // retrieve all groups
        Logger.info(account.getEmail() + ": retrieving all entries");
        Set<Group> groups;
        try {
            groups = groupController.getAllGroups();
        } catch (ControllerException e) {
            Logger.error(e);
            return null;
        }

        if (groups == null)
            return null;

        ArrayList<GroupInfo> infos = new ArrayList<GroupInfo>();
        for (Group group : groups) {
            GroupInfo info = new GroupInfo();
            info.setId(group.getId());
            info.setLabel(group.getLabel());
            info.setDescription(group.getDescription());
            Group parent = group.getParent();
            if (parent != null) {
                info.setParentId(parent.getId());
            }
            infos.add(info);
        }

        return infos;
    }

    @Override
    public boolean deleteEntryAttachment(String sid, String fileId) throws AuthenticationException {
        Account account;
        try {
            account = retrieveAccountForSid(sid);
            if (account == null)
                return false;

            AttachmentController controller = new AttachmentController(account);
            Attachment attachment = controller.getAttachmentByFileId(fileId);
            if (attachment == null)
                return false;

            controller.delete(attachment);
            return true;
        } catch (ControllerException ce) {
            Logger.error(ce);
        } catch (PermissionException e) {
            Logger.error(e);
        }
        return false;
    }

    @Override
    public ArrayList<FolderDetails> deleteEntry(String sessionId, EntryInfo info) throws AuthenticationException {
        try {
            Account account = retrieveAccountForSid(sessionId);
            Logger.info(account.getEmail() + ": deleting entry " + info.getId());
            EntryController controller = new EntryController();
            AccountController accountController = new AccountController();
            FolderController folderController = new FolderController();

            Entry entry = controller.get(account, info.getId());
            if (entry == null)
                return null;

            controller.delete(account, entry);

            ArrayList<FolderDetails> folderList = new ArrayList<FolderDetails>();
            List<Folder> folders = folderController.getFoldersByEntry(entry);
            String systemEmail = accountController.getSystemAccount().getEmail();
            ArrayList<Long> entryIds = new ArrayList<Long>();
            entryIds.add(entry.getId());
            if (folders != null) {
                for (Folder folder : folders) {
                    try {
                        Folder returned = folderController.removeFolderContents(account,
                                                                                folder.getId(), entryIds);
                        boolean isSystem = systemEmail.equals(returned.getOwnerEmail());
                        FolderDetails details = new FolderDetails(returned.getId(),
                                                                  returned.getName(), isSystem);
                        BigInteger size = folderController.getFolderSize(folder.getId());
                        details.setCount(size);
                        folderList.add(details);
                    } catch (ControllerException me) {
                        continue;
                    }
                }
            }

            return folderList;

        } catch (ControllerException ce) {
            Logger.error(ce);
        } catch (PermissionException e) {
            Logger.error(e);
        }
        return null;
    }
}
