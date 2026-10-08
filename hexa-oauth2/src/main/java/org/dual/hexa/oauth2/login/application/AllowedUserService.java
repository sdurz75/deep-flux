package org.dual.hexa.oauth2.login.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedEntry;
import org.dual.hexa.oauth2.login.domain.AllowedUser;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.port.in.IAllowedUsers;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.out.IAllowedUserStore;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AllowedUserService implements IAllowedUsers {

    private final IAllowedUserStore store;
    private final IOAuthEnvironment env;
    private final IOAuthAccess access;
    private final Messages messages;
    private final Clock clock;

    @Autowired
    AllowedUserService(IAllowedUserStore store, IOAuthEnvironment env, IOAuthAccess access, Messages messages) {
        this(store, env, access, messages, Clock.systemUTC());
    }

    AllowedUserService(IAllowedUserStore store, IOAuthEnvironment env, IOAuthAccess access, Messages messages, Clock clock) {
        this.store = store;
        this.env = env;
        this.access = access;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AllowedEntry> list() {
        List<AllowedEntry> entries = new ArrayList<>();
        env.allowedEmails().forEach(email -> entries.add(new AllowedEntry(null, AllowKind.EMAIL, email, true, false, null)));
        env.allowedDomains().forEach(domain -> entries.add(new AllowedEntry(null, AllowKind.DOMAIN, domain, true, false, null)));
        store.findAll().forEach(user -> entries.add(view(user)));
        return entries;
    }

    @Override
    @Transactional
    public AllowedEntry add(AllowKind kind, String value) {
        if (kind == null) {
            throw new OAuthException(messages.get("oauth2.error.kindRequired"));
        }
        String clean = kind == AllowKind.EMAIL ? AllowPolicy.normalizeEmail(value) : AllowPolicy.normalizeDomain(value);
        if (clean.length() > MAX_VALUE || !(kind == AllowKind.EMAIL ? AllowPolicy.isEmail(clean) : AllowPolicy.isDomain(clean))) {
            throw new OAuthException(messages.get(kind == AllowKind.EMAIL ? "oauth2.error.emailFormat" : "oauth2.error.domainFormat"));
        }
        boolean fromEnv = kind == AllowKind.EMAIL ? env.allowedEmails().contains(clean) : env.allowedDomains().contains(clean);
        if (fromEnv || store.findByKindAndValue(kind, clean).isPresent()) {
            throw new OAuthException(messages.get("oauth2.error.entryDuplicate", clean));
        }
        return view(store.save(new AllowedUser(kind, clean, clock.instant())));
    }

    @Override
    @Transactional
    public void remove(Long id) {
        AllowedUser user = store.findById(id).orElseThrow(() -> new OAuthException(messages.get("oauth2.error.notFound")));
        long others = store.count() - 1 + env.allowedEmails().size() + env.allowedDomains().size();
        if (access.isEnabled() && others <= 0) {
            throw new OAuthException(messages.get("oauth2.error.lastAllowed"));
        }
        store.delete(user);
    }

    private static AllowedEntry view(AllowedUser user) {
        return new AllowedEntry(user.getId(), user.getKind(), user.getValue(), false, user.isBound(), user.getLastLoginAt());
    }
}
