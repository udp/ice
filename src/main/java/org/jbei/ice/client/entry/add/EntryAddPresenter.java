package org.jbei.ice.client.entry.add;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import org.jbei.ice.client.AbstractPresenter;
import org.jbei.ice.client.AppController;
import org.jbei.ice.client.Page;
import org.jbei.ice.client.RegistryServiceAsync;
import org.jbei.ice.client.entry.add.form.EntryCreateWidget;
import org.jbei.ice.client.entry.add.form.IEntryFormSubmit;
import org.jbei.ice.client.entry.add.form.SampleLocationWidget;
import org.jbei.ice.shared.AutoCompleteField;
import org.jbei.ice.shared.EntryAddType;
import org.jbei.ice.shared.dto.EntryInfo;
import org.jbei.ice.shared.dto.EntryInfo.EntryType;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.shared.HandlerManager;
import com.google.gwt.user.client.History;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.FocusWidget;
import com.google.gwt.user.client.ui.HasWidgets;
import com.google.gwt.view.client.SelectionChangeEvent;
import com.google.gwt.view.client.SelectionChangeEvent.Handler;
import com.google.gwt.view.client.SingleSelectionModel;

public class EntryAddPresenter extends AbstractPresenter {

    private final RegistryServiceAsync service;
    private final HandlerManager eventBus;
    private final IEntryAddView display;
    private SingleSelectionModel<EntryAddType> menuSelectionModel;
    private final HashMap<EntryAddType, EntryCreateWidget> formsCache;
    private HashMap<AutoCompleteField, ArrayList<String>> autoCompleteData;

    public EntryAddPresenter(RegistryServiceAsync service, HandlerManager eventBus,
            IEntryAddView display) {

        this.service = service;
        this.eventBus = eventBus;
        this.display = display;

        formsCache = new HashMap<EntryAddType, EntryCreateWidget>();

        bind();

        initSelectionModel();
    }

    private void initSelectionModel() {
        menuSelectionModel = new SingleSelectionModel<EntryAddType>();
        display.getMenu().setRowData(Arrays.asList(EntryAddType.values()));
        display.getMenu().setSelectionModel(menuSelectionModel);
        menuSelectionModel.addSelectionChangeHandler(new Handler() {

            @Override
            public void onSelectionChange(SelectionChangeEvent event) {
                EntryAddType selected = menuSelectionModel.getSelectedObject();
                getSampleLocation(selected);
                EntryCreateWidget form = getEntryForm(selected);
                display.setCurrentForm(form, ("New " + selected.getDisplay()));
            }
        });
    }

    private void getSampleLocation(EntryAddType selected) {

        EntryType type = null;

        switch (selected) {

        case ARABIDOPSIS:
            type = EntryType.ARABIDOPSIS;
            break;

        case PLASMID:
            type = EntryType.PLASMID;
            break;

        case PART:
            type = EntryType.PART;
            break;

        case STRAIN:
            type = EntryType.STRAIN;
            break;

        default:
            return;
        }

        // TODO : check cache 
        service.retrieveStorageSchemes(AppController.sessionId, type,
            new AsyncCallback<HashMap<String, ArrayList<String>>>() {

                @Override
                public void onFailure(Throwable caught) {
                    Window.alert("Failed to retrieve the sample location data: "
                            + caught.getMessage());
                }

                @Override
                public void onSuccess(HashMap<String, ArrayList<String>> result) {
                    SampleLocationWidget sampleLocation = new SampleLocationWidget(result);
                    // TODO : cache.
                    display.getCurrentForm().getEntrySubmitForm().setSampleLocation(sampleLocation);
                }
            });

    }

    protected void bind() {

        // TODO : look in caching to avoid making the following call every time page is loaded
        service.retrieveAutoCompleteData(AppController.sessionId,
            new AsyncCallback<HashMap<AutoCompleteField, ArrayList<String>>>() {

                @Override
                public void onFailure(Throwable caught) {
                    Window.alert("Failed to retrieve the autocomplete data: " + caught.getMessage());
                }

                @Override
                public void onSuccess(HashMap<AutoCompleteField, ArrayList<String>> result) {
                    autoCompleteData = new HashMap<AutoCompleteField, ArrayList<String>>(result);
                }
            });

    }

    /**
     * Makes an rpc to save the set of entrys
     * 
     * @param hasEntry
     *            set of entrys to be saved.
     */
    protected void save(final HashSet<EntryInfo> entrySet) {
        if (entrySet == null || entrySet.isEmpty())
            return;

        this.service.createEntry(AppController.sessionId, entrySet,
            new AsyncCallback<ArrayList<Long>>() {

                @Override
                public void onFailure(Throwable caught) {
                    Window.alert("Failure to create entry");
                }

                @Override
                public void onSuccess(ArrayList<Long> result) {
                    if (result.size() != entrySet.size()) {
                        Window.alert("Your entries could not be created. Please try again later or contact your administrator");
                    } else {
                        if (entrySet.size() == 1) {
                            long id = result.get(0);
                            History.newItem(Page.ENTRY_VIEW.getLink() + ";id=" + id);
                        } else {
                            History.newItem(Page.COLLECTIONS.getLink());
                        }
                    }
                }
            });
    }

    /**
     * creates a new form based on specific types of entries.
     * To create a new entry/form, add the type to {@link EntryAddType} and create a new form here
     * 
     * @param type
     *            EntryType
     * @return form specific to type
     */
    protected EntryCreateWidget getEntryForm(EntryAddType type) {

        if (formsCache.containsKey(type))
            return formsCache.get(type);

        String creatorName = AppController.accountInfo.getFullName();
        String creatorEmail = AppController.accountInfo.getEmail();

        final EntryCreateWidget form = EntryFormFactory.entryForm(type, autoCompleteData,
            creatorName, creatorEmail);

        if (form == null)
            return null;

        final IEntryFormSubmit formSubmit = form.getEntrySubmitForm();
        formSubmit.getSubmit().addClickHandler(new ClickHandler() {

            @Override
            public void onClick(ClickEvent event) {
                FocusWidget focus = formSubmit.validateForm();
                if (focus != null) {
                    focus.setFocus(true);
                    return;
                }

                formSubmit.populateEntries();
                save(formSubmit.getEntries());
            }
        });

        formsCache.put(type, form);

        return form;
    }

    @Override
    public void go(HasWidgets container) {

        container.clear();
        container.add(this.display.asWidget());
    }
}
