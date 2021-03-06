package ru.mail.jira.plugins.disposition.manager;

import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.rest.json.beans.JiraBaseUrls;
import com.atlassian.jira.issue.index.IndexException;
import com.atlassian.jira.issue.index.IssueIndexManager;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchProvider;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.jql.builder.JqlQueryBuilder;
import com.atlassian.jira.jql.parser.JqlParseException;
import com.atlassian.jira.jql.parser.JqlQueryParser;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.user.util.UserUtil;
import com.atlassian.jira.util.I18nHelper;
import com.atlassian.jira.util.ImportUtils;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.query.Query;
import com.atlassian.query.order.SortOrder;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joda.time.DateTime;
import ru.mail.jira.plugins.disposition.customfields.IssueDispositionCF;
import ru.mail.jira.plugins.disposition.notificationcenter.IssueChange;
import ru.mail.jira.plugins.disposition.notificationcenter.IssueChangeReason;
import ru.mail.jira.plugins.disposition.notificationcenter.NotificationCenter;

import java.util.*;
import java.util.regex.Pattern;

/**
 * User: g.chernyshev
 * Date: 6/8/12
 * Time: 7:40 PM
 */
public class DispositionManagerImpl implements DispositionManager {

    private static final Double DISPOSITION_START = 0.0;
    public static final Double DISPOSITION_STEP = 1.0;

    private static final int SHIFT_UP = -1;
    private static final int SHIFT_DOWN = 1;

    private static final Logger log = Logger.getLogger(DispositionManagerImpl.class);
    public static final String QUEUE_MANAGER_GROUP_NAME = "queueManager";

    @NotNull
    private final JqlQueryParser jqlQueryParser;

    @NotNull
    private final SearchProvider searchProvider;

    @NotNull
    private final SearchService searchService;

    @NotNull
    private final CustomFieldManager customFieldManager;

    @NotNull
    private final JiraBaseUrls jiraBaseUrls;

    @NotNull
    private final I18nHelper.BeanFactory i18nFactory;

    @NotNull
    private final DispositionConfigurationManager dispositionConfigurationManager;

    private NotificationCenter notificationCenter;

    private static DispositionManagerImpl instance;

    public static DispositionManagerImpl getInstance() {
        return instance;
    }

    private GroupManager groupManager = ComponentAccessor.getGroupManager();
    private UserUtil userUtil = ComponentAccessor.getUserUtil();


    public DispositionManagerImpl(@NotNull JqlQueryParser jqlQueryParser, @NotNull SearchProvider searchProvider, @NotNull SearchService searchService, @NotNull CustomFieldManager customFieldManager, @NotNull JiraBaseUrls jiraBaseUrls, @NotNull I18nHelper.BeanFactory i18nFactory, @NotNull DispositionConfigurationManager dispositionConfigurationManager) {
        this.jqlQueryParser = jqlQueryParser;
        this.searchProvider = searchProvider;
        this.searchService = searchService;
        this.customFieldManager = customFieldManager;
        this.jiraBaseUrls = jiraBaseUrls;
        this.i18nFactory = i18nFactory;
        this.dispositionConfigurationManager = dispositionConfigurationManager;
        this.notificationCenter = NotificationCenter.getInstance();
        instance = this;
    }

    @Override
    public void resetDisposition(@NotNull User userToBeReset, @NotNull Double step, @NotNull Collection<String> errors) throws JqlParseException, SearchException {

        User user = ComponentManager.getInstance().getJiraAuthenticationContext().getLoggedInUser();
        IssueChangeReason reason = new IssueChangeReason();
        reason.setReasonType(IssueChangeReason.MANUALY_CHANED);
        reason.setUser(user);

        final I18nHelper i18n = i18nFactory.getInstance(user);

        Collection<CustomField> fields = getCustomFieldsByIssueAndType(IssueDispositionCF.class, null);
        if (fields.isEmpty()) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.custom.field.not.found"));
            return;
        }

        // iterate over all matched custom fields and reset order for selected user
        for (CustomField field : fields) {
            String jql = replaceCurrentUser(dispositionConfigurationManager.getQuery(field), userToBeReset.getName());
            if (null == jql || jql.isEmpty()) {
                errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.jql.empty", field.getName()));
                return;
            }

            Query query = jqlQueryParser.parseQuery(jql);
            SearchResults searchResults = searchProvider.search(query, user, PagerFilter.getUnlimitedFilter());
            if (null == searchResults) {
                errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.search.null"));
                return;
            }

            Double disposition = DISPOSITION_START;
            DateTime timestamp = new DateTime();
            for (Issue issue : searchResults.getIssues()) {
                Double prevValue = getIssueValue(issue, field);

                disposition += step;
                updateValue(field, prevValue, disposition, issue, reason, timestamp, true);
            }

        }
    }

    @Override
    public boolean validateDisposition(@NotNull Issue issue, @Nullable Double value, @NotNull Collection<User> users, @NotNull Collection<String> errors) {
        User currentUser = ComponentManager.getInstance().getJiraAuthenticationContext().getLoggedInUser();

        final I18nHelper i18n = i18nFactory.getInstance(currentUser);

        if (value == null) {
            return true;
        }

        if (value <= 0) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.field.is.negative"));
            return false;
        }

        CustomField field = getCustomFieldByIssueAndType(IssueDispositionCF.class, issue);
        if (field == null) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.custom.field.for.issue.not.found", issue.getKey()));
            return false;
        }

        final User user;
        try {
            user = getSuitableUser(issue, field, users);
        } catch (SearchException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        } catch (JqlParseException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
        if (user == null) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.issue.not.in.jql.for.user", issue.getKey()));
            return false;
        }

        String jql = replaceCurrentUser(dispositionConfigurationManager.getQuery(field), user.getName());
        if (null == jql || jql.isEmpty()) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.jql.empty", field.getName()));
            return false;
        }

        // if issue in not in configured Jql - return
        try {
            if (issueNotInJQL(jql, issue, user)) {
                errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.issue.not.in.jql", issue.getKey()));
                return false;
            }
        } catch (JqlParseException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        } catch (SearchException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }

        return true;
    }

    @Override
    public void setDisposition(@NotNull Issue issue, @NotNull Double value, @NotNull Collection<User> users, @NotNull Collection<String> errors) throws JqlParseException, SearchException {

        if (!validateDisposition(issue, value, users, errors)) {
            return;
        }

        CustomField field = getCustomFieldByIssueAndType(IssueDispositionCF.class, issue);
        assert field != null;
        final User user = getSuitableUser(issue, field, users);
        assert user != null;
        String jql = replaceCurrentUser(dispositionConfigurationManager.getQuery(field), user.getName());
        assert jql != null;

        final I18nHelper i18n = i18nFactory.getInstance(user);

        Group group = groupManager.getGroup(QUEUE_MANAGER_GROUP_NAME);
        if (!groupManager.isUserInGroup(user, group)) {
            String contactList = "";
            Collection<User> queueManagers = groupManager.getUsersInGroup(group.getName());
            for(User manager:queueManagers) {
                contactList += String.format("%s(%s) ", manager.getDisplayName(), manager.getEmailAddress());
            }
            errors.add(String.format(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.permission.denied"), contactList));
            return;
        }

        DateTime timestamp = new DateTime();
        //TODO: Сделать возможность менять положение в разных очередях
        IssueChangeReason reasonForDraggedIssue = new IssueChangeReason();
        IssueChangeReason reasonForShiftedIssues = new IssueChangeReason();

        reasonForDraggedIssue.setReasonType(IssueChangeReason.MANUALY_CHANED);
        reasonForDraggedIssue.setUser(user);

        reasonForShiftedIssues.setCausalIssue(issue);
        reasonForShiftedIssues.setUser(user);


        Double prevValue = getIssueValue(issue, field);
        // set value of our issue
        if(prevValue == null) {
            reasonForShiftedIssues.setReasonType(IssueChangeReason.INSERTED_ABOVE);
            shiftIssuesDown(jql, value, field, user, issue, reasonForShiftedIssues, timestamp);
            updateValue(field, prevValue, value, issue, reasonForDraggedIssue, timestamp, true);
        }
        else if(value > prevValue) {
            reasonForShiftedIssues.setReasonType(IssueChangeReason.REMOVED_ABOVE);
            shiftIssuesUp(jql, value, field, user, issue, reasonForShiftedIssues, timestamp);
            updateValue(field, prevValue, value, issue, reasonForDraggedIssue, timestamp, true);
        }
        else if (value < prevValue) {
            Double lowValue = value + DISPOSITION_STEP;
            reasonForShiftedIssues.setReasonType(IssueChangeReason.INSERTED_ABOVE);
            shiftIssuesDown(jql, lowValue, field, user, issue, reasonForShiftedIssues, timestamp);
            updateValue(field, prevValue, value, issue, reasonForDraggedIssue, timestamp, true);
        }

    }

    @Override
    public void setDisposition(@Nullable Issue high, @NotNull Issue dragged, @Nullable Issue low, @NotNull Collection<User> users, @NotNull Collection<String> errors, @Nullable Integer index, @Nullable String queueID) throws SearchException, JqlParseException {

        DateTime timestamp = new DateTime();

        User currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();

        final I18nHelper i18n = i18nFactory.getInstance(currentUser);

        // assume, that all issues have the same custom field
        @Nullable
        CustomField field = getCustomFieldByID(queueID);
        if (field == null) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.custom.field.for.issue.not.found", dragged.getKey()));
            return;
        }

        final User user = getSuitableUser(dragged, field, users);
        if (user == null) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.issue.not.in.jql.for.user", dragged.getKey()));
            return;
        }

        Group group = groupManager.getGroup(QUEUE_MANAGER_GROUP_NAME);
        if (!groupManager.isUserInGroup(user, group)) {
            String contactList = "";
            Collection<User> queueManagers = groupManager.getUsersInGroup(group.getName());
            for(User manager:queueManagers) {
                contactList += String.format("%s(%s) ", manager.getDisplayName(), manager.getEmailAddress());
            }
            errors.add(String.format(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.permission.denied"), contactList));
            return;
        }


        String jql = replaceCurrentUser(dispositionConfigurationManager.getQuery(field), user.getName());
        if (jql == null || jql.isEmpty()) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.jql.empty", field.getName()));
            return;
        }

        if (!validate(jql, high, dragged, low, errors, user, i18n)) {
            return;
        }

        if (index == null) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.index.null"));
            return;
        }

        // get values from issues
        Double highValue = getValueForField(high, field);
        Double draggedValue = getIssueValue(dragged, field);
        Double lowValue = getValueForField(low, field);

        // one of issues - high or low - should have value
        if (highValue == null && lowValue == null) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.high.and.low.uninitialized"));
            return;
        }


        @Nullable
        Long average = null;
        if (highValue != null && lowValue != null) {

            if (lowValue <= highValue) {
                errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.incorrect.order", getQueryLink(jql, user)));
                return;
            }

            if (!areIssuesSideBySide(jql, highValue, lowValue, field, user)) {
                errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.incorrect.order", getQueryLink(jql, user)));
                return;
            }

            average = getAverage(highValue, lowValue);
        }

        IssueChangeReason reasonForDraggedIssue = new IssueChangeReason();
        IssueChangeReason reasonForShiftedIssues = new IssueChangeReason();
        reasonForDraggedIssue.setCausalIssue(dragged);
        reasonForShiftedIssues.setCausalIssue(dragged);
        reasonForDraggedIssue.setUser(currentUser);
        reasonForShiftedIssues.setUser(currentUser);
        reasonForDraggedIssue.setReasonType(IssueChangeReason.MANUALY_CHANED);

        if (average == null) {
            if (highValue == null) {
                /*
                  If some issue is dragged to first position (empty highValue),
                  then try to get average between min disposition and lowValue. If it's not possible - shift issues down.
                */

                if (index == 0 && !isMinValue(jql, lowValue, field, user)) {
                    // If it's lowest value in view, but not in query - error
                    errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.incorrect.order", getQueryLink(jql, user)));
                    return;
                }

                Long lowAverage = getAverage(0.0, lowValue);

                if (lowAverage == null || index != 0) {
                    reasonForShiftedIssues.setReasonType(IssueChangeReason.INSERTED_ABOVE);
                    shiftIssuesDown(jql, lowValue, field, user, dragged, reasonForShiftedIssues, timestamp);
                    updateValue(field, draggedValue, lowValue, dragged, reasonForDraggedIssue, timestamp, true);
                } else {
                    updateValue(field, draggedValue, (double) lowAverage, dragged, reasonForDraggedIssue, timestamp, true);
                }
            } else {
                if (lowValue != null) {
                    if (draggedValue != null && draggedValue < highValue) {
                        /*
                         Issue is dragged from top to bottom.
                         Shift other issues up.
                        */
                        reasonForShiftedIssues.setReasonType(IssueChangeReason.REMOVED_ABOVE);
                        shiftIssuesUp(jql, highValue, field, user, dragged, reasonForShiftedIssues, timestamp);
                        updateValue(field, draggedValue, highValue, dragged, reasonForDraggedIssue, timestamp, true);
                    } else {
                        /*
                          Issue is dragged between two issues with initialized values.
                          Shift down in this case.
                        */
                        reasonForShiftedIssues.setReasonType(IssueChangeReason.INSERTED_ABOVE);
                        shiftIssuesDown(jql, lowValue, field, user, dragged, reasonForShiftedIssues, timestamp);
                        updateValue(field, draggedValue, lowValue, dragged, reasonForDraggedIssue, timestamp, true);
                    }
                } else {
                    /*
                     Value of bottom (low) issue is uninitialized.
                     Add default step value to high issue's value.
                    */

                    if (!isMaxValue(jql, highValue, field, user)) {
                        // If it's highest value in view, but not in query - error
                        errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.incorrect.order", getQueryLink(jql, user)));
                        return;
                    }
                    reasonForShiftedIssues.setReasonType(IssueChangeReason.REMOVED_ABOVE);
                    shiftIssuesUp(jql, highValue, field, user, dragged, reasonForShiftedIssues, timestamp);
                    updateValue(field, draggedValue, highValue, dragged, reasonForDraggedIssue, timestamp, true);
                }
            }
        } else {
            // we've found average value for dragged issue - no need to shift other issues
            updateValue(field, draggedValue, (double) average, dragged, reasonForDraggedIssue, timestamp, true);
        }
    }

    private Double getIssueValue(Issue issue, CustomField field) {
        return (Double) issue.getCustomFieldValue(field);
    }

    private Double getValueForField(Issue issue, CustomField field) {
        Double value;
        if (issue != null)
            value = getIssueValue(issue, field);
        else
            value = null;
        return value;
    }

    public void shiftIssuesDown(@NotNull String jql, @NotNull Double startValue, @NotNull CustomField field, @NotNull User user, @NotNull Issue currentIssue, IssueChangeReason reason, DateTime timestamp) {
        try {
            shiftIssues(jql, startValue, field, user, currentIssue, SHIFT_DOWN, reason, timestamp);
        } catch (JqlParseException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        } catch (SearchException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }

    public void shiftIssuesUp(@NotNull String jql, @NotNull Double startValue, @NotNull CustomField field, @NotNull User user, @NotNull Issue currentIssue, IssueChangeReason reason, DateTime timestamp) {
        try {
            shiftIssues(jql, startValue, field, user, currentIssue, SHIFT_UP, reason, timestamp);
        } catch (JqlParseException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        } catch (SearchException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }

    @Nullable
    public CustomField getCustomFieldByIssueAndType(@NotNull Class<?> type, @Nullable Issue issue) {

        Collection<CustomField> fields = (issue == null) ?
                customFieldManager.getCustomFieldObjects() : customFieldManager.getCustomFieldObjects(issue);

        return findAnyCustomField(type, fields);
    }

    private CustomField findAnyCustomField(Class<?> type, Collection<CustomField> fields) {
        for (CustomField cf : fields) {
            if (type.isAssignableFrom(cf.getCustomFieldType().getClass())) {
                return cf;
            }
        }
        return null;
    }

    @Nullable
    public CustomField getCustomFieldByID(@Nullable String queueID) {
        return customFieldManager.getCustomFieldObject(queueID);
    }

    @Override
    public String getQueryLink(@NotNull String jql, @NotNull User user) throws JqlParseException {
        Query query = jqlQueryParser.parseQuery(jql);
        return jiraBaseUrls.baseUrl() + "/secure/IssueNavigator.jspa?reset=true" + searchService.getQueryString(user, query);
    }

    @Nullable
    public String replaceCurrentUser(@Nullable String jql, @Nullable String user) {
        if (jql == null || user == null) {
            return null;
        }
        return jql.replaceAll(Pattern.quote("currentUser()"), "\"" + user + "\"");
    }


    /*-------------------------------------   Private helper methods   ----------------------------------------*/

    /**
     * Search suitable user for configured Jql query
     *
     * @param issue - current dragged issue
     * @param field - configured disposition custom field
     * @param users - list of users to choose from  @return - suitable user
     * @throws SearchException
     * @throws JqlParseException
     */
    @Nullable
    private User getSuitableUser(@NotNull Issue issue, @NotNull CustomField field, @NotNull Collection<User> users) throws SearchException, JqlParseException {

        String jql = dispositionConfigurationManager.getQuery(field);
        if (jql == null) {
            return null;
        }

        for (User user : users) {
            if (!issueNotInJQL(jql, issue, user)) {
                return user;
            }
        }

        return null;
    }


    /**
     * Validate simple rules
     *
     * @param high    - issue, above dragged
     * @param dragged - currently dragged issue
     * @param low     - issue, below dragged
     * @param errors  - collection to add errors
     * @param user    - suitable user
     * @param i18n    - internalisation bean
     * @return - true if all checks are passed, else otherwise
     * @throws SearchException
     * @throws JqlParseException
     */
    private boolean validate(@NotNull String jql, @Nullable Issue high, @NotNull Issue dragged, @Nullable Issue low, @NotNull Collection<String> errors, @NotNull final User user, @NotNull final I18nHelper i18n) throws SearchException, JqlParseException {

        if (high == null && low == null) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.high.and.low.null"));
            return false;
        }

        if (high != null && issueNotInJQL(jql, high, user)) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.issue.not.in.jql", high.getKey()));
            return false;
        }

        if (low != null && issueNotInJQL(jql, low, user)) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.issue.not.in.jql", low.getKey()));
            return false;
        }

        if (issueNotInJQL(jql, dragged, user)) {
            errors.add(i18n.getText("ru.mail.jira.plugins.disposition.manager.error.issue.not.in.jql", dragged.getKey()));
            return false;
        }

        return true;
    }


    /**
     * Check, that current issue is not in configured jql query
     *
     * @param jql   - configured JQL query
     * @param issue - current issue
     * @param user  - searcher
     * @return - true if issue is not in query false otherwise
     * @throws JqlParseException
     * @throws SearchException
     */
    private boolean issueNotInJQL(@NotNull String jql, @NotNull Issue issue, @NotNull User user) throws JqlParseException, SearchException {
        Query query = jqlQueryParser.parseQuery(jql);
        JqlQueryBuilder jqlQueryBuilder = JqlQueryBuilder.newBuilder(query);
        jqlQueryBuilder.where().and().issue(issue.getKey());

        return searchProvider.searchCount(jqlQueryBuilder.buildQuery(), user) != 1;
    }

    /**
     * Check if disposition with current value already exists
     *
     * @param jql   - configured jql query
     * @param value - disposition value
     * @param field - disposition custom field
     * @param user  - searcher
     * @return - true if disposition with given value found in configured Jql, else otherwise
     * @throws JqlParseException
     * @throws SearchException
     */
    private boolean isDispositionInJQL(@NotNull String jql, @NotNull Double value, @NotNull CustomField field, @NotNull User user) throws JqlParseException, SearchException {
        Query query = jqlQueryParser.parseQuery(jql);
        JqlQueryBuilder jqlQueryBuilder = JqlQueryBuilder.newBuilder(query);
        jqlQueryBuilder.where().and().customField(field.getIdAsLong()).eq(value.toString());

        return searchProvider.searchCount(jqlQueryBuilder.buildQuery(), user) >= 1;
    }

    /**
     * Check that issues with High and Low values are side by side
     *
     * @param jql   - configured jql query
     * @param high  - local minimum value (high issues have lower disposition value)
     * @param low   - local maximum value (low issues have higher disposition value)
     * @param field - disposition custom field
     * @param user  - searcher
     * @return - true is issues close to each other, false otherwise
     * @throws JqlParseException
     * @throws SearchException
     */
    private boolean areIssuesSideBySide(@NotNull String jql, @NotNull Double high, @NotNull Double low, @NotNull CustomField field, @NotNull User user) throws JqlParseException, SearchException {
        Query query = jqlQueryParser.parseQuery(jql);
        JqlQueryBuilder jqlQueryBuilder = JqlQueryBuilder.newBuilder(query);
        jqlQueryBuilder.where().and().customField(field.getIdAsLong()).gt(high.toString()).and().customField(field.getIdAsLong()).lt(low.toString());

        return searchProvider.searchCount(jqlQueryBuilder.buildQuery(), user) == 0;
    }

    /**
     * Check that value is maximum disposition value for configured query
     *
     * @param jql   - configured jql query
     * @param value - searched value
     * @param field - disposition custom field
     * @param user  - searcher
     * @return - true if value is maximum for configured query, false otherwise
     * @throws JqlParseException
     * @throws SearchException
     */
    private boolean isMaxValue(@NotNull String jql, Double value, @NotNull CustomField field, @NotNull User user) throws JqlParseException, SearchException {
        Query query = jqlQueryParser.parseQuery(jql);
        JqlQueryBuilder jqlQueryBuilder = JqlQueryBuilder.newBuilder(query);
        jqlQueryBuilder.where().and().customField(field.getIdAsLong()).gt(value.toString());

        return searchProvider.searchCount(jqlQueryBuilder.buildQuery(), user) == 0;

    }

    /**
     * Check that value is minimum disposition value for configured query
     *
     * @param jql   - configured jql query
     * @param value - searched value
     * @param field - disposition custom field
     * @param user  - searcher
     * @return - true if value is minimum for configured query, false otherwise
     * @throws JqlParseException
     * @throws SearchException
     */
    private boolean isMinValue(@NotNull String jql, Double value, @NotNull CustomField field, @NotNull User user) throws JqlParseException, SearchException {
        Query query = jqlQueryParser.parseQuery(jql);
        JqlQueryBuilder jqlQueryBuilder = JqlQueryBuilder.newBuilder(query);
        jqlQueryBuilder.where().and().customField(field.getIdAsLong()).lt(value.toString());

        return searchProvider.searchCount(jqlQueryBuilder.buildQuery(), user) == 0;

    }

    /**
     * Get all custom fields of specified type for issue
     *
     * @param type  - type of custom field to search
     * @param issue - issue to get custom fields from
     * @return - collection of founded fields
     */
    @NotNull
    private Collection<CustomField> getCustomFieldsByIssueAndType(@NotNull Class<?> type, @Nullable Issue issue) {
        Set<CustomField> result = new TreeSet<CustomField>();
        Collection<CustomField> fields = (null == issue) ?
                customFieldManager.getCustomFieldObjects() : customFieldManager.getCustomFieldObjects(issue);

        for (CustomField cf : fields) {
            if (type.isAssignableFrom(cf.getCustomFieldType().getClass())) {
                result.add(cf);
            }
        }
        return result;
    }


    /**
     * Shift issues up/down - change disposition in turn
     *
     * @param jql          - query, used to get list of issues
     * @param startValue   - value of disposition field, from which we are starting shifting
     * @param field        - disposition custom field
     * @param user         - searcher
     * @param currentIssue - issue, currently moved - should be skipped from query
     * @param shiftValue   - direction of shifting (up/down)
     * @param reason
     * @throws JqlParseException
     * @throws SearchException
     */
    private void shiftIssues(@NotNull String jql, @Nullable Double startValue, @NotNull CustomField field, @NotNull User user, @NotNull Issue currentIssue, int shiftValue, IssueChangeReason reason, DateTime timestamp) throws JqlParseException, SearchException {

        if (startValue == null) {
            return;
        }

        if (!isDispositionInJQL(jql, startValue, field, user)) {
            return;
        }

        Query query = jqlQueryParser.parseQuery(jql);
        JqlQueryBuilder jqlQueryBuilder = JqlQueryBuilder.newBuilder(query);

        if (shiftValue == SHIFT_DOWN) {
            jqlQueryBuilder.where().and().customField(field.getIdAsLong()).gtEq(startValue.toString()).and().not().issue(currentIssue.getKey());
            jqlQueryBuilder.orderBy().add(field.getName(), SortOrder.ASC);
        } else {
            jqlQueryBuilder.where().and().customField(field.getIdAsLong()).ltEq(startValue.toString()).and().not().issue(currentIssue.getKey());
            jqlQueryBuilder.orderBy().add(field.getName(), SortOrder.DESC, true);
        }


        SearchResults searchResults = searchProvider.search(jqlQueryBuilder.buildQuery(), user, PagerFilter.getUnlimitedFilter());
        if (searchResults == null) {
            return;
        }

        Collection<Issue> issues = new LinkedHashSet<Issue>();
        Issue prevIssue = null;

        // search for space in minimum 2, so that we can increase other issues order
        for (Issue issue : searchResults.getIssues()) {
            if (prevIssue == null) {
                prevIssue = issue;
                issues.add(issue);
                continue;
            }

            Double value = getIssueValue(issue, field);
            Double prevValue = getIssueValue(prevIssue, field);

            if (getAverage(prevValue, value) != null) {
                break;
            }

            prevIssue = issue;
            issues.add(issue);
        }


        // increase disposition of close (near) issues by 1
        for (Issue issue : issues) {
            Double disposition = getIssueValue(issue, field);
            DispositionUtils.setSkipShift(true);
            updateValue(field, disposition, disposition + shiftValue, issue, reason, timestamp, true);
            DispositionUtils.setSkipShift(false);
        }
    }


    /**
     * Get average for two values
     *
     * @param first  - value of first disposition
     * @param second - value of second disposition
     * @return - average value if found or null
     */
    @Nullable
    private Long getAverage(@NotNull Double first, @NotNull Double second) {
        // check for space between values
        if (Math.abs(second - first) >= 2) {
            return Math.round((second + first) / 2);
        }
        return null;
    }

    /**
     * Update custom field value for current issue
     *  @param customField - custom field to be updated
     * @param prevValue   - previous value
     * @param newValue    - new value
     * @param issue       - issue to be changed
     * @param reason      - issue because of which the change occurred
     * @param reindex     - should the issue be reindexed
     */
    private void updateValue(@NotNull CustomField customField, @Nullable Double prevValue, @Nullable Double newValue, @NotNull Issue issue, @Nullable IssueChangeReason reason, @NotNull DateTime timestamp, boolean reindex) {
        customField.updateValue(null, issue, new ModifiedValue(prevValue, newValue), new DefaultIssueChangeHolder());
        if (reindex) {
            indexIssue(issue);
        }
//        if(reason != null)
//            log.error(String.format(
//                    "DISPOSITION. DispositionManagerImpl:731. Issue: %s, prevValue-newValue: %f-%f, causalIssue: %s, reasonType: %d, user: %s",
//                    issue.getKey(), prevValue, newValue, reason.getCausalIssue().getKey(), reason.getReasonType(), reason.getUserDisplayName()));
//        else
//            log.error(String.format(
//                    "DISPOSITION. DispositionManagerImpl:731. NOREASON. Issue: %s, prevValue-newValue: %f-%f",
//                    issue.getKey(), prevValue, newValue));
        notificationCenter.createUpdatedValueMessages(getMessageData(customField, prevValue, newValue, issue, reason, timestamp));
    }

    private IssueChange getMessageData(CustomField customField, Double prevValue, Double newValue, Issue issue, IssueChangeReason reason, DateTime timestamp) {
        IssueChange issueChange = new IssueChange();
        issueChange.setQueueName(customField.getName());
        issueChange.setIssue(issue);
        issueChange.setPrevValue(prevValue);
        issueChange.setNewValue(newValue);
        issueChange.setTimestamp(timestamp);
        if(reason != null)
            issueChange.addReason(reason);
        return issueChange;
    }


    /**
     * Reindex issue (Lucene)
     *
     * @param issue - issue to be reindexed
     */
    private void indexIssue(@NotNull Issue issue) {
        try {
            boolean oldValue = ImportUtils.isIndexIssues();
            ImportUtils.setIndexIssues(true);
            IssueIndexManager issueIndexManager = ComponentManager.getInstance().getIndexManager();
            issueIndexManager.reIndex(issue);
            ImportUtils.setIndexIssues(oldValue);
        } catch (IndexException e) {
            log.error("Unable to index issue: " + issue.getKey(), e);
        }
    }

    @NotNull
    public DispositionConfigurationManager getDispositionConfigurationManager() {
        return dispositionConfigurationManager;
    }

    @NotNull
    public SearchProvider getSearchProvider() {
        return searchProvider;
    }

}
