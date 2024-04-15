package org.rundeck.app.data.providers


import grails.compiler.GrailsCompileStatic
import grails.gorm.DetachedCriteria
import groovy.transform.Synchronized
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import org.hibernate.StaleStateException
import org.rundeck.app.config.SysConfigProp
import org.rundeck.app.config.SystemConfig
import org.rundeck.app.config.SystemConfigurable
import org.rundeck.app.data.model.v1.user.LoginStatus
import org.rundeck.app.data.model.v1.user.RdUser
import org.rundeck.app.data.model.v1.user.dto.SaveUserResponse
import org.rundeck.app.data.model.v1.user.dto.UserFilteredResponse
import org.rundeck.app.data.model.v1.user.dto.UserProperties
import org.rundeck.app.data.providers.v1.user.UserDataProvider
import org.rundeck.spi.data.DataAccessException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.validation.Errors
import rundeck.User
import rundeck.services.ConfigurationService
import rundeck.services.FrameworkService
import rundeck.services.data.UserDataService

import javax.transaction.Transactional

@GrailsCompileStatic(TypeCheckingMode.SKIP)
@Slf4j
@Transactional
class GormUserDataProvider implements UserDataProvider, SystemConfigurable{
    @Autowired
    UserDataService userDataService
    @Autowired
    FrameworkService frameworkService
    @Autowired
    ConfigurationService configurationService

    public static final String SESSION_ID_ENABLED = 'userService.login.track.sessionId.enabled'
    public static final String SESSION_ID_METHOD = 'userService.login.track.sessionId.method'
    public static final int DEFAULT_TIMEOUT = 30
    public static final String SESSION_ABANDONED_MINUTES = 'userService.login.track.sessionAbandoned'
    public static final String SHOW_LOGIN_STATUS = 'gui.userSummaryShowLoginStatus'
    public static final String NAME_CASE_SENSITIVE_ENABLED = "login.nameCaseSensitiveEnabled"

    @Override
    RdUser get(Long userid) {
        return User.get(userid)
    }

    @Override
    @Transactional
    @Synchronized
    User findOrCreateUser(String login) throws DataAccessException {
        User user = findUserByLoginCaseSensitivity(login)
        if (!user) {
            User newUser = new User(login: login)
            if (!newUser.save(flush: true)) {
                throw new DataAccessException("unable to save user: ${login}")
            }
            user = newUser
        }
        return user
    }

    @Override
    @Transactional
    User registerLogin(String login, String sessionId) throws DataAccessException {
        User user = findOrCreateUser(login)
        user.lastLogin = new Date()
        user.lastLoggedHostName = frameworkService.getServerHostname()
        user.lastSessionId = null
        if (isSessionIdRegisterEnabled()) {
            user.lastSessionId = (sessionIdRegisterMethod == 'plain') ? sessionId : sessionId.encodeAsSHA256()
        }
        try {
            if (!user.save(flush: true)) {
                throw new DataAccessException("unable to save user: ${user}, ${user.errors.allErrors.join(',')}")
            }
            return user
        } catch (StaleStateException exception) {
            log.warn("registerLogin: for ${login}, caught StaleStateException: $exception")
            return null
        } catch (OptimisticLockingFailureException exception) {
            log.warn("registerLogin: for ${login}, caught OptimisticLockingFailureException: $exception")
            return null
        }
    }

    @Override
    @Transactional
    User registerLogout(String login) throws DataAccessException {
        User user = findOrCreateUser(login)
        user.lastLogout = new Date()
        if (!user.save(flush: true)) {
            throw new DataAccessException("unable to save user: ${user}, ${user.errors.allErrors.join(',')}")
        }
        return user
    }

    @Override
    @Transactional
    SaveUserResponse updateUserProfile(String username, String lastName, String firstName, String email) {
        User u = findOrCreateUser(username)
        u.setFirstName(firstName)
        u.setLastName(lastName)
        u.setEmail(email)
        Boolean isUpdated = u.save(flush: true)
        Errors errors = u.errors
        return new SaveUserResponse(user: u, isSaved: isUpdated, errors: errors)
    }

    @Override
    @Transactional
    SaveUserResponse createUserWithProfile(String login, String lastName, String firstName, String email) {
        User u = new User(login: login, firstName: firstName, lastName: lastName, email: email)
        Boolean isUpdated = u.save(flush: true)
        Errors errors = u.errors
        return new SaveUserResponse(user: u, isSaved: isUpdated, errors: errors)
    }

    @Override
    String getLoginStatus(RdUser user) {
        String status = LoginStatus.NOTLOGGED.value
        if (user) {
            Date lastDate = user.lastLogin
            if (lastDate != null) {
                int minutes = configurationService.getInteger(SESSION_ABANDONED_MINUTES, DEFAULT_TIMEOUT)
                Calendar calendar = Calendar.getInstance()
                calendar.setTime(lastDate)
                calendar.add(Calendar.MINUTE, minutes)
                if (user.lastLogout != null) {
                    if (lastDate.after(user.lastLogout)) {
                        if (calendar.getTime().before(new Date())) {
                            status = LoginStatus.ABANDONED.value
                        } else {
                            status = LoginStatus.LOGGEDIN.value
                        }
                    } else {
                        status = LoginStatus.LOGGEDOUT.value
                    }
                } else if (calendar.getTime().after(new Date())) {
                    status = LoginStatus.LOGGEDIN.value
                } else {
                    status = LoginStatus.ABANDONED.value
                }
            } else {
                status = LoginStatus.NOTLOGGED.value
            }
        }
        return status
    }

    @Override
    UserFilteredResponse findWithFilters(boolean loggedInOnly, HashMap<String, String> filters, Integer offset, Integer max) {
        int timeOutMinutes = configurationService.getInteger(SESSION_ABANDONED_MINUTES, DEFAULT_TIMEOUT)
        boolean showLoginStatus = configurationService.getBoolean(SHOW_LOGIN_STATUS, false)
        Calendar calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, -timeOutMinutes)

        Integer totalRecords = new DetachedCriteria(User).build {
            if (showLoginStatus && loggedInOnly) {
                or {
                    and {
                        isNotNull("lastLogin")
                        isNotNull("lastLogout")
                        gtProperty("lastLogin", "lastLogout")
                        gt("lastLogin", calendar.getTime())
                    }
                    and {
                        isNotNull("lastLogin")
                        isNull("lastLogout")
                        gt("lastLogin", calendar.getTime())
                    }
                }
            }
            if (filters) {
                filters.each { k, v ->
                    eq(k, v)
                }
            }

        }.count() as Integer

        List<RdUser> users = []
        if (totalRecords > 0) {
            users = User.createCriteria().list(max: max, offset: offset) {
                if (showLoginStatus && loggedInOnly) {
                    or {
                        and {
                            isNotNull("lastLogin")
                            isNotNull("lastLogout")
                            gtProperty("lastLogin", "lastLogout")
                            gt("lastLogin", calendar.getTime())
                        }
                        and {
                            isNotNull("lastLogin")
                            isNull("lastLogout")
                            gt("lastLogin", calendar.getTime())
                        }
                    }
                }

                if (filters) {
                    filters.each { k, v ->
                        eq(k, v)
                    }
                }
                order("login", "asc")
            } as List<RdUser>
        }
        def response = new UserFilteredResponse()
        response.setTotalRecords(totalRecords)
        response.setUsers(users)
        response.setShowLoginStatus(showLoginStatus)
        return response
    }

    @Override
    boolean validateUserExists(String username) {
        return User.countByLogin(username) > 0
    }

    @Override
    List<RdUser> listAllOrderByLogin() {
        List<RdUser> response = User.listOrderByLogin() as List<RdUser>
        return response
    }

    @Override
    List<RdUser> findAll() {
        List<RdUser> response = User.findAll() as List<RdUser>
        return response
    }

    @Override
    RdUser findByLogin(String login) {
        User.withNewSession {
            return findUserByLoginCaseSensitivity(login)
        }
    }

    @Override
    RdUser buildUser(String login) {
        return new User(login: login)
    }

    @Override
    @Transactional
    SaveUserResponse updateFilterPref(String login, String filterPref) {
        User user = findUserByLoginCaseSensitivity(login)
        user.filterPref = filterPref
        Boolean isSaved = user.save()
        return new SaveUserResponse(user: user, isSaved: isSaved, errors: user.errors)
    }

    @Override
    String getEmailWithNewSession(String login) {
        if (!login) { return "" }
        User.withNewSession {
            def userLogin = findUserByLoginCaseSensitivity(login)
            if (!userLogin || !userLogin.email) { return "" }
            return userLogin.email
        }
    }

    @Override
    HashMap<String, UserProperties> getInfoFromUsers(List<String> usernames) {
        def infoList = new DetachedCriteria(User).in("login", usernames).projections {
                property("login")
                property("firstName")
                property("lastName")
                property("email")
        }.list() as List<String[]>
        HashMap<String, UserProperties> result = [:]
        infoList.each { row -> result[row[0]] = new UserProperties(firstname: row[1], lastname: row[2], email: row[3]) }
        return result
    }

    @Override
    Integer count() {
        return count(null)
    }

    @Override
    Integer count(Date fromLoginDate) {
        Integer count = new DetachedCriteria(User).count {
            if(fromLoginDate) {
                gt("lastLogin", fromLoginDate)
            }
        } as Integer
        return count
    }

    @Override
    RdUser buildUser() {
        return new User()
    }

    /**
     * It looks for property to enable session id related data to be stored at DB.
     * @return boolean
     */
    def isSessionIdRegisterEnabled() {
        configurationService.getBoolean(SESSION_ID_ENABLED, false)
    }

    /**
     * It looks for property that set method for session id to be stored at DB.
     * @return string
     */
    def getSessionIdRegisterMethod() {
        configurationService.getString(SESSION_ID_METHOD, 'hash')
    }

    /**
     Checks if login name case sensitivity is enabled.
     @return {@code true} if login name case sensitivity is enabled, {@code false} otherwise.
     */
    def isLoginNameCaseSensitiveEnabled(){
        return configurationService?.getBoolean(NAME_CASE_SENSITIVE_ENABLED,false)
    }

    /**
     * Finds a user by their login name, considering the case sensitivity if enabled.
     * @param login The login name of the user to search for.
     * @return The User object corresponding to the provided login name.
     */
    User findUserByLoginCaseSensitivity(String login) {
        return isLoginNameCaseSensitiveEnabled() ? User.findByLogin(login) : User.findByLoginIlike(login)
    }

    @Override
    List<SysConfigProp> getSystemConfigProps() {
        [
                SystemConfig.builder().with {
                    key "rundeck.$NAME_CASE_SENSITIVE_ENABLED"
                    description "Enable case sensitiveness on login name "
                    defaultValue "false"
                    required false
                    datatype "Boolean"
                    build()
                }
        ]
    }
}
