package org.jbei.ice.client.entry.view.detail;

import org.jbei.ice.shared.dto.entry.PartInfo;

public class PartInfoView extends EntryInfoView<PartInfo> {

    public PartInfoView(PartInfo partInfo) {
        super(partInfo);
    }

    @Override
    protected void addShortFieldValues() {
        addShortField("Packaging Format", info.getPackageFormat());
    }

    @Override
    protected void addLongFields() {
    }
}