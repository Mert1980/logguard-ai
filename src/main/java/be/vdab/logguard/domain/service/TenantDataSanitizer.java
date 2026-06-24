package be.vdab.logguard.domain.service;

import java.util.regex.Pattern;

/**
 * Strips tenant/PII data from free text before it goes into an LLM payload (FR-21):
 * Belgian KBO numbers, email addresses, and LDAP DN fragments. Deliberately KEEPS UUIDs/GUIDs
 * (correlation ids), numeric ids and port numbers. Pure domain — no Spring annotations.
 */
public class TenantDataSanitizer {

    // user@host.tld
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    // Belgian KBO: 0XXX.XXX.XXX
    private static final Pattern KBO = Pattern.compile("\\b0\\d{3}\\.\\d{3}\\.\\d{3}\\b");
    // LDAP DN fragment in free text: cn=...,ou=...(,o=/dc=...)
    private static final Pattern LDAP_DN =
            Pattern.compile("(?i)\\bcn=[^,]+(?:,(?:ou|o|dc)=[^,\\s]+)+");

    public String sanitize(String input) {
        if (input == null) {
            return null;
        }
        String result = EMAIL.matcher(input).replaceAll("[EMAIL]");
        result = KBO.matcher(result).replaceAll("[KBO]");
        result = LDAP_DN.matcher(result).replaceAll("[LDAP-DN]");
        return result;
    }
}
