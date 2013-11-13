package org.jbei.ice.client.entry.display.model;

import org.jbei.ice.client.service.RegistryService;
import org.jbei.ice.client.service.RegistryServiceAsync;
import org.jbei.ice.lib.shared.dto.entry.AutoCompleteField;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.SuggestOracle;

/**
 * @author Hector Plahar
 */
public class AutoCompleteSuggestOracle extends SuggestOracle {

    private final RegistryServiceAsync service = GWT.create(RegistryService.class);
    private final AutoCompleteField field;

    public AutoCompleteSuggestOracle(AutoCompleteField field) {
        this.field = field;
    }

    @Override
    public void requestSuggestions(final Request request, final Callback callback) {
        service.getAutoCompleteSuggestion(field, request, new AsyncCallback<Response>() {

            @Override
            public void onSuccess(Response result) {
                callback.onSuggestionsReady(request, result);
            }

            @Override
            public void onFailure(Throwable caught) {
                callback.onSuggestionsReady(request, new Response());
            }
        });
    }
}
