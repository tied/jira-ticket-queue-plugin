package ru.mail.jira.plugins.disposition.web.action;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.plugin.webfragment.model.JiraHelper;
import com.atlassian.jira.web.action.issue.AbstractIssueSelectAction;
import org.jetbrains.annotations.NotNull;
import ru.mail.jira.plugins.disposition.customfields.IssueDispositionCF;
import ru.mail.jira.plugins.disposition.manager.DispositionManager;
import ru.mail.jira.plugins.disposition.web.CookieHelper;

import java.util.ArrayList;
import java.util.Collection;

/**
 * @author g.chernyshev
 */
public class ChangeIssueDispositionAction extends AbstractIssueSelectAction {

    @NotNull
    private final DispositionManager dispositionManager;

    private double disposition;

    public ChangeIssueDispositionAction(@NotNull DispositionManager dispositionManager) {
        this.dispositionManager = dispositionManager;
    }

    @Override
    public String doDefault() throws Exception {

        CustomField field = dispositionManager.getCustomFieldByIssueAndType(IssueDispositionCF.class, getIssueObject());
        if (field != null) {
            Object value = getIssueObject().getCustomFieldValue(field);
            if (value != null) {
                setDisposition((Double) value);
            }
        }

        return super.doDefault();
    }


    @Override
    protected String doExecute() throws Exception {

        Collection<String> errors = new ArrayList<String>();
        dispositionManager.setDisposition(getIssueObject(), disposition, CookieHelper.getUsers(request), errors);

        if (!errors.isEmpty()) {
            for (String error : errors) {
                addErrorMessage(error);
            }
            return ERROR;
        }

        if (isInlineDialogMode()) {
            return returnComplete();
        }

        return getRedirect(getViewUrl());
    }


    /* ------------------------------------------------------------------------------------------ */

    @SuppressWarnings("unused")
    public double getDisposition() {
        return disposition;
    }

    @SuppressWarnings("unused")
    public void setDisposition(double disposition) {
        this.disposition = disposition;
    }
}
