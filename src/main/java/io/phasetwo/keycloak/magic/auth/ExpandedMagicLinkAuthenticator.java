package io.phasetwo.keycloak.magic.auth;

import io.phasetwo.keycloak.magic.MagicLink;
import io.phasetwo.keycloak.magic.auth.token.ExpandedMagicLinkActionToken;
import io.phasetwo.keycloak.magic.auth.token.ExpandedMagicLinkActionTokenHandler;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.utils.StringUtil;

import java.util.Map;
import java.util.OptionalInt;

import static org.keycloak.services.validation.Validation.FIELD_USERNAME;

@JBossLog
public class ExpandedMagicLinkAuthenticator extends UsernamePasswordForm {

    static final String CREATE_NONEXISTENT_USER_CONFIG_PROPERTY = "ext-magic-create-nonexistent-user";
    static final String UPDATE_PROFILE_ACTION_CONFIG_PROPERTY = "ext-magic-update-profile-action";
    static final String UPDATE_PASSWORD_ACTION_CONFIG_PROPERTY = "ext-magic-update-password-action";

    static final String ACTION_TOKEN_PERSISTENT_CONFIG_PROPERTY = "ext-magic-allow-token-reuse";
    public static final String EMAIL_SENT = "EMAIL_SENT";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        log.debug("MagicLinkAuthenticator.authenticate");
        String attemptedUsername = getAttemptedUsername(context);
        String validSession = context.getAuthenticationSession()
                .getAuthNote(ExpandedMagicLinkActionTokenHandler.VALID_SESSION);
        log.info("SessionId: %s + tabId: %s.".formatted(
                        context.getAuthenticationSession().getParentSession().getId(),
                        context.getAuthenticationSession().getTabId()
                )
        );
        if (StringUtil.isNotBlank(validSession)) {
            UserModel user;
            if (isValidEmail(attemptedUsername)) {
                user = context.getSession().users().getUserByEmail(context.getRealm(), attemptedUsername);
            } else {
                user = context.getSession().users().getUserByUsername(context.getRealm(), attemptedUsername);
            }
            context.setUser(user);
            context.getAuthenticationSession().setAuthenticatedUser(user);
            context.success();
        } else if (attemptedUsername == null) {
            super.authenticate(context);
        } else {
            String emailSent = context.getAuthenticationSession().getAuthNote(EMAIL_SENT);
            if (StringUtil.isBlank(emailSent)) {
                log.debugf(
                        "Found attempted username %s from previous authenticator, skipping login form",
                        attemptedUsername);
                action(context);
            } else {
                context.challenge(
                        context.form().createForm("view-email-enhanced.ftl")
                );
            }
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        log.debug("MagicLinkAuthenticator.action");

        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();

        String email = trimToNull(formData.getFirst(AuthenticationManager.FORM_USERNAME));
        // check for empty email
        if (email == null) {
            // - first check for email from previous authenticator
            email = getAttemptedUsername(context);
        }
        log.debugf("email in action is %s", email);
        // - throw error if still empty
        if (email == null) {
            context.getEvent().error(Errors.USER_NOT_FOUND);
            Response challengeResponse =
                    challenge(context, getDefaultChallengeMessage(context), FIELD_USERNAME);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER, challengeResponse);
            return;
        }
        String clientId = context.getSession().getContext().getClient().getClientId();

        EventBuilder event = context.newEvent();

        UserModel user =
                MagicLink.getOrCreate(
                        context.getSession(),
                        context.getRealm(),
                        email,
                        isForceCreate(context, false),
                        isUpdateProfile(context, false),
                        isUpdatePassword(context, false),
                        MagicLink.registerEvent(event));

        // check for no/invalid email address
        if (user == null || trimToNull(user.getEmail()) == null || !isValidEmail(user.getEmail())) {
            context.getEvent().event(EventType.LOGIN_ERROR).error(Errors.INVALID_EMAIL);
            Response challengeResponse =
                    challenge(context, getDefaultChallengeMessage(context), FIELD_USERNAME);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER, challengeResponse);
            return;
        }

        log.debugf("user is %s %s", user.getEmail(), user.isEnabled());

        // check for enabled user
        if (!enabledUser(context, user)) {
            return; // the enabledUser method sets the challenge
        }

        ExpandedMagicLinkActionToken token =
                MagicLink.createExpandedActionToken(
                        user,
                        clientId,
                        OptionalInt.empty(),
                        rememberMe(context),
                        context.getAuthenticationSession(),
                        isActionTokenPersistent(context, true));
        String link = MagicLink.linkFromActionToken(context.getSession(), context.getRealm(), token);
        boolean sent = MagicLink.sendMagicLinkEmail(context.getSession(), user, link);
        log.debugf("sent email to %s? %b. Link? %s", user.getEmail(), sent, link);

        context
                .getAuthenticationSession()
                .setAuthNote(AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME, email);
        context
                .getAuthenticationSession()
                .setAuthNote(EMAIL_SENT, "true");
        context.challenge(context.form().createForm("view-email-enhanced.ftl"));
    }

    private boolean rememberMe(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        String rememberMe = formData.getFirst("rememberMe");
        return context.getRealm().isRememberMe()
                && rememberMe != null
                && rememberMe.equalsIgnoreCase("on");
    }

    private boolean isForceCreate(AuthenticationFlowContext context, boolean defaultValue) {
        return is(context, CREATE_NONEXISTENT_USER_CONFIG_PROPERTY, defaultValue);
    }

    private boolean isUpdateProfile(AuthenticationFlowContext context, boolean defaultValue) {
        return is(context, UPDATE_PROFILE_ACTION_CONFIG_PROPERTY, defaultValue);
    }

    private boolean isUpdatePassword(AuthenticationFlowContext context, boolean defaultValue) {
        return is(context, UPDATE_PASSWORD_ACTION_CONFIG_PROPERTY, defaultValue);
    }

    private boolean isActionTokenPersistent(AuthenticationFlowContext context, boolean defaultValue) {
        return is(context, ACTION_TOKEN_PERSISTENT_CONFIG_PROPERTY, defaultValue);
    }

    private boolean is(AuthenticationFlowContext context, String propName, boolean defaultValue) {
        AuthenticatorConfigModel authenticatorConfig = context.getAuthenticatorConfig();
        if (authenticatorConfig == null) return defaultValue;

        Map<String, String> config = authenticatorConfig.getConfig();
        if (config == null) return defaultValue;

        String v = config.get(propName);
        if (v == null || "".equals(v)) return defaultValue;

        return v.trim().toLowerCase().equals("true");
    }

    private static boolean isValidEmail(String email) {
        try {
            InternetAddress a = new InternetAddress(email);
            a.validate();
            return true;
        } catch (AddressException e) {
            return false;
        }
    }

    private String getAttemptedUsername(AuthenticationFlowContext context) {
        if (context.getUser() != null && context.getUser().getEmail() != null) {
            return context.getUser().getEmail();
        }
        String username =
                trimToNull(context.getAuthenticationSession().getAuthNote(ATTEMPTED_USERNAME));
        if (username != null) {
            if (isValidEmail(username)) {
                return username;
            }
            UserModel user = context.getSession().users().getUserByUsername(context.getRealm(), username);
            if (user != null && user.getEmail() != null) {
                return user.getEmail();
            }
        }
        return null;
    }

    private static String trimToNull(final String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if ("".equalsIgnoreCase(trimmed)) trimmed = null;
        return trimmed;
    }

    @Override
    protected boolean validateForm(
            AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        log.debug("validateForm");
        return validateUser(context, formData);
    }

    @Override
    protected Response challenge(
            AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        log.debug("challenge");
        LoginFormsProvider forms = context.form();
        if (!formData.isEmpty()) forms.setFormData(formData);
        return forms.createLoginUsername();
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        log.debug("createLoginForm");
        return form.createLoginUsername();
    }

    @Override
    protected String getDefaultChallengeMessage(AuthenticationFlowContext context) {
        log.debug("getDefaultChallengeMessage");
        return context.getRealm().isLoginWithEmailAllowed()
                ? Messages.INVALID_USERNAME_OR_EMAIL
                : Messages.INVALID_USERNAME;
    }
}