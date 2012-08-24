package org.jbei.ice.client.bulkupload.sheet.cell;

import org.jbei.ice.client.bulkupload.model.SheetCellData;

import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.BlurHandler;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

/**
 * Default cell for the import sheet
 *
 * @author Hector Plahar
 */
public class InputSheetCell extends SheetCell {

    private final TextBox input;
    private int currentRow;

    public InputSheetCell() {
        super();
        input = new TextBox();
        input.setStyleName("cell_input");

        input.addBlurHandler(new BlurHandler() {
            @Override
            public void onBlur(BlurEvent event) {
                String s = setDataForRow(currentRow);
                input.setText(s);
            }
        });
    }

    @Override
    public void setText(String text) {
        input.setText(text);
    }

    /**
     * Sets data for row specified in the param
     *
     * @param row current row user is working on
     * @return display for user entered value
     */
    @Override
    public String setDataForRow(int row) {
        String ret = input.getText();
        SheetCellData datum = new SheetCellData();
        datum.setId(ret);
        datum.setValue(ret);
        setWidgetValue(row, datum);
        input.setText("");
        return ret;
    }

    public void setFocus(int row) {
        input.setFocus(true);
        currentRow = row;
    }

    @Override
    public Widget getWidget(int row, boolean isCurrentSelection, int tabIndex) {
        input.setTabIndex(tabIndex);
        return input;
    }
}